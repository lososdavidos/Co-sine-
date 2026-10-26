// Package server wires Cosine together: one process, one SQLite file, one Store.
package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/bandcamp"
	"github.com/lososdavidos/Co-sine-/cosine/internal/dashboard"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/deezer"
	"github.com/lososdavidos/Co-sine-/cosine/internal/discogs"
	"github.com/lososdavidos/Co-sine-/cosine/internal/fetch"
	"github.com/lososdavidos/Co-sine-/cosine/internal/inbox"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/mirror"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/native"
	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
	"github.com/lososdavidos/Co-sine-/cosine/internal/portraits"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
	"github.com/lososdavidos/Co-sine-/cosine/internal/scrobble"
	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
	"github.com/lososdavidos/Co-sine-/cosine/internal/subsonic"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

type Config struct {
	DataDir string // holds cosine.db, secret.key and cached artwork
	Listen  string
	Version string
}

type Server struct {
	cfg       Config
	log       *slog.Logger
	db        *db.DB
	ingester  *ingest.Ingester
	fetcher   *fetch.Fetcher // nil without yt-dlp
	portraits *portraits.Service
	scrobbler *scrobble.Service
	handler   http.Handler

	inboxMu     sync.Mutex
	inboxCancel context.CancelFunc
	inboxDone   chan struct{}
}

func New(cfg Config, log *slog.Logger) (*Server, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		return nil, err
	}
	d, err := db.Open(filepath.Join(cfg.DataDir, "cosine.db"))
	if err != nil {
		return nil, fmt.Errorf("opening database: %w", err)
	}
	key, err := auth.LoadOrCreateKey(filepath.Join(cfg.DataDir, "secret.key"))
	if err != nil {
		d.Close()
		return nil, err
	}
	as, err := auth.New(d, key)
	if err != nil {
		d.Close()
		return nil, err
	}
	s := &Server{cfg: cfg, log: log, db: d}

	// The resolver chain (§3.2): MusicBrainz, Discogs, Bandcamp, then the
	// source's own metadata. The first confident answer wins.
	const threshold = 0.8
	lookups := &http.Client{Timeout: 30 * time.Second}
	ua := musicbrainz.UserAgent(cfg.Version)
	mb := &musicbrainz.Client{HTTP: lookups, DB: d, UserAgent: ua}
	dg := discogs.New(lookups, d, ua, func() string {
		t, _ := d.Setting(context.Background(), db.SettingDiscogsToken)
		return t
	})
	bc := bandcamp.New(lookups, d, ua)
	covers := musicbrainz.CoverArt{HTTP: lookups, UserAgent: ua}
	dz := deezer.New(lookups, ua)
	s.portraits = &portraits.Service{DB: d, DataDir: cfg.DataDir, Deezer: dz,
		Links: mb.ArtistLinks, Image: covers.Image, Log: log.With("component", "portraits")}
	s.ingester = &ingest.Ingester{
		DB:              d,
		DataDir:         cfg.DataDir,
		ReviewThreshold: threshold,
		Log:             log,
		Probe:           ingest.FFProbe(),
		Covers:          covers.Front,
		Images:          covers.Image,
		Resolver: resolve.Chain{
			MinConfidence: threshold,
			Resolvers: []resolve.Resolver{
				musicbrainz.Resolver{Client: mb},
				resolve.Lookup{Matcher: dg},
				resolve.Lookup{Matcher: bc},
				resolve.SourceMetadata{},
			},
		},
	}
	reviews := &review.Service{DB: d, Ingester: s.ingester, Sources: []review.Source{mb, dg, bc},
		Threshold: threshold, Log: log.With("component", "review")}
	if s.ingester.Probe == nil {
		log.Warn("ffprobe not found: track durations will be 0 until it is installed")
	}

	listening := &stats.Service{DB: d}
	lists := &playlists.Service{DB: d, Stats: listening}
	s.scrobbler = &scrobble.Service{DB: d, HTTP: &http.Client{Timeout: 30 * time.Second}, Version: cfg.Version,
		Log: log.With("component", "scrobble")}
	dash := &dashboard.Dashboard{DB: d, Auth: as, Ingester: s.ingester, Stats: listening, OnInboxChange: s.startInbox, Log: log}
	nativeAPI := &native.API{Auth: as, Review: reviews, Stats: listening, Mirror: &mirror.Service{DB: d, Playlists: lists},
		Ingester: s.ingester, Playlists: lists, Scrobbler: s.scrobbler, Version: cfg.Version, Log: log}
	dash.Review = reviews

	// URL ingest exists only with yt-dlp; without it the capability is not
	// advertised and Sine hides Add entirely (§2.1).
	if runner, err := ytdlp.Find(); err != nil {
		log.Warn("yt-dlp not found: adding music from links is disabled until it is installed")
	} else {
		// yt-dlp unpacks itself on every run: onto the disk, not into a
		// memory-backed /tmp. Copies left by runs killed mid-way go now.
		runner.TempDir = filepath.Join(cfg.DataDir, "tmp")
		os.RemoveAll(runner.TempDir)
		if err := os.MkdirAll(runner.TempDir, 0o700); err != nil {
			d.Close()
			return nil, err
		}
		httpClient := &http.Client{Timeout: 30 * time.Second}
		searcher := &search.Service{
			DB:     d,
			YtDlp:  search.YtDlpSearch{Runner: runner},
			NewAPI: search.NewSourceAPIs(httpClient),
			// YouTube Music lists songs only to the signed in, so the
			// length of the song as released comes from Deezer.
			Released: func(ctx context.Context, q string) (search.Result, bool) {
				tracks, err := dz.SearchTracks(ctx, q, 10)
				if err != nil {
					return search.Result{}, false
				}
				found := make([]search.Released, len(tracks))
				for i, t := range tracks {
					found[i] = search.Released{Title: firstNonEmpty(t.TitleShort, t.Title), Version: t.TitleVersion,
						Artist: t.Artist.Name, DurationSec: t.DurationSec}
				}
				return search.AskedRelease(q, found)
			},
			Bandcamp: func(ctx context.Context, q string, n int) ([]search.Result, error) {
				albums, err := bc.Albums(ctx, q, n)
				out := make([]search.Result, 0, len(albums))
				for _, a := range albums {
					out = append(out, search.Result{Kind: search.KindAlbum, Title: a.Title, Uploader: a.Artist,
						URL: a.URL, Thumbnail: a.Cover, Source: "bandcamp"})
				}
				return out, err
			},
		}
		s.fetcher = &fetch.Fetcher{
			DB: d, Ingester: s.ingester, Runner: runner, Search: searcher,
			WorkDir: filepath.Join(cfg.DataDir, "fetch"), Workers: 2, HTTP: httpClient, Log: log.With("component", "fetch"),
			Playlists: lists,
		}
		dash.Fetcher, dash.Search = s.fetcher, searcher
		dash.YtDlpVersion = runner.Version(context.Background())
		nativeAPI.Fetcher = s.fetcher
		log.Info("yt-dlp found", "path", runner.Bin, "version", dash.YtDlpVersion)
	}

	api := &subsonic.API{DB: d, Auth: as, DataDir: cfg.DataDir, Version: cfg.Version, Log: log, Playlists: lists,
		OnPlay: s.scrobbler.Nudge}
	mux := http.NewServeMux()
	mux.Handle("/rest/", api.Handler())
	nativeAPI.Register(mux)
	mux.Handle("/", dash.Handler())
	s.handler = mux
	return s, nil
}

func (s *Server) Handler() http.Handler { return s.handler }

// Run serves until ctx is cancelled, then shuts down gracefully.
func (s *Server) Run(ctx context.Context) error {
	defer s.db.Close()

	go s.watchStore(ctx)
	go s.findPortraits(ctx)
	go s.scrobbler.Run(ctx)
	if p, _ := s.db.Setting(ctx, db.SettingInboxPath); p != "" {
		s.startInbox(p)
	}
	defer s.stopInbox()
	fetchDone := make(chan struct{})
	if s.fetcher != nil {
		go func() { s.fetcher.Run(ctx); close(fetchDone) }()
	} else {
		close(fetchDone)
	}
	defer func() { <-fetchDone }()

	srv := &http.Server{Addr: s.cfg.Listen, Handler: s.handler, ReadHeaderTimeout: 10 * time.Second}
	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	s.log.Info("cosine listening", "addr", s.cfg.Listen, "data", s.cfg.DataDir, "version", s.cfg.Version)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdown); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// storeCheck is how often the Store is checked for files deleted or put
// back outside Cosine. A play of a deleted file notices sooner.
const storeCheck = 5 * time.Minute

// watchStore checks the Store at startup and every storeCheck after, so a
// deleted file leaves everyone's library and one put back returns (§2.2).
func (s *Server) watchStore(ctx context.Context) {
	last := -1
	for {
		n, err := s.ingester.Verify(ctx)
		switch {
		case err != nil && ctx.Err() == nil:
			s.log.Error("checking the Store", "err", err)
		case err == nil && n != last:
			if n > 0 {
				s.log.Warn("files missing from the Store", "count", n)
			} else if last > 0 {
				s.log.Info("no files missing from the Store any more")
			}
			last = n
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(storeCheck):
		}
	}
}

// portraitCheck is how often artists are looked over for a missing picture.
const portraitCheck = 10 * time.Minute

// findPortraits looks for artist pictures in the background (§6.9): new
// artists within portraitCheck of arriving, the rest as they come due.
func (s *Server) findPortraits(ctx context.Context) {
	for {
		n, err := s.portraits.Run(ctx)
		switch {
		case err != nil && ctx.Err() == nil:
			s.log.Warn("looking for artist pictures", "err", err)
		case n > 0:
			s.log.Info("found artist pictures", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(portraitCheck):
		}
	}
}

// startInbox (re)starts the watcher on path.
func (s *Server) startInbox(path string) {
	s.stopInbox()
	s.inboxMu.Lock()
	defer s.inboxMu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	s.inboxCancel, s.inboxDone = cancel, done
	w := &inbox.Watcher{
		Dir:    path,
		Settle: 2 * time.Second,
		Log:    s.log.With("component", "inbox"),
		Ingest: func(ctx context.Context, p string) error {
			_, err := s.ingester.Ingest(ctx, ingest.Request{Path: p, Source: "inbox"})
			return err
		},
	}
	go func() {
		defer close(done)
		if err := w.Run(ctx); err != nil {
			s.log.Error("inbox watcher stopped", "dir", path, "err", err)
		}
	}()
	s.log.Info("watching inbox", "dir", path)
}

func (s *Server) stopInbox() {
	s.inboxMu.Lock()
	defer s.inboxMu.Unlock()
	if s.inboxCancel != nil {
		s.inboxCancel()
		<-s.inboxDone
		s.inboxCancel, s.inboxDone = nil, nil
	}
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}
