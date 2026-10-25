// Package search finds things to ingest (§3.1a). Two backends, chosen in
// the dashboard: the source sites' own APIs (richer results, need keys) or
// yt-dlp's search (no keys). Either way the result is a URL, and ingest from
// there is identical. Search degrades, never fails (Q73).
package search

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Result is one candidate. Plays and duration let the picker show what
// you're choosing between.
type Result struct {
	Title       string `json:"title"`
	Uploader    string `json:"uploader"`
	URL         string `json:"url"`
	Thumbnail   string `json:"thumbnail,omitempty"`
	Source      string `json:"source"` // "soundcloud", "youtube"
	DurationSec int    `json:"durationSec"`
	Plays       int64  `json:"plays"`
	// Preview: the source only offers a short preview (a SoundCloud Go+
	// track), and adding it will be refused.
	Preview bool `json:"preview,omitempty"`
	// ReleaseSec is the song's length as released, on the results that are
	// that song, when a label's own upload of it was found (ByRelease).
	ReleaseSec int `json:"releaseSec,omitempty"`
	// Kind is "album" or "playlist" for a collection; empty for a song.
	Kind string `json:"kind,omitempty"`
	// Tracks is a collection's number of songs, when the source says.
	Tracks int `json:"tracks,omitempty"`
	// Asked: a collection that is what the search asked for.
	Asked bool `json:"asked,omitempty"`
}

// Sources a result can come from, besides yt-dlp's extractor names.
const SourceYouTubeMusic = "youtube music"

// LooksLikePreview is what a search result can tell without fetching the
// track: SoundCloud reports a preview's length as exactly 30 seconds.
func LooksLikePreview(source string, durationSec float64) bool {
	return source == "soundcloud" && durationSec == 30
}

type Backend interface {
	Search(ctx context.Context, query string, n int) ([]Result, error)
}

const (
	BackendYtDlp = "ytdlp"
	BackendAPI   = "api"
)

// Service picks the configured backend and falls back to yt-dlp search
// silently, recording why so the dashboard can say which is actually running.
type Service struct {
	DB    *db.DB
	YtDlp Backend
	// NewAPI builds the source-API backend from the stored keys; nil if none are set.
	NewAPI func(soundCloudID, youTubeKey string) Backend
	// Bandcamp searches Bandcamp's albums; nil: not searched.
	Bandcamp func(ctx context.Context, query string, n int) ([]Result, error)

	mu     sync.Mutex
	status string
}

// Search returns results and the name of the backend that produced them.
func (s *Service) Search(ctx context.Context, query string, n int) ([]Result, string, error) {
	want, _ := s.DB.Setting(ctx, db.SettingSearchBackend)
	if want == BackendAPI {
		sc, _ := s.DB.Setting(ctx, db.SettingSoundCloudID)
		yt, _ := s.DB.Setting(ctx, db.SettingYouTubeKey)
		switch api := s.NewAPI(sc, yt); {
		case api == nil:
			s.setStatus("Source APIs selected but no keys are set; using yt-dlp search.")
		default:
			res, err := api.Search(ctx, query, n)
			if err == nil {
				s.setStatus("Using the source APIs.")
				return res, BackendAPI, nil
			}
			s.setStatus(fmt.Sprintf("Source APIs failed (%v); using yt-dlp search.", err))
		}
	} else {
		s.setStatus("Using yt-dlp search.")
	}
	if s.YtDlp == nil {
		return nil, "", errors.New("yt-dlp is not installed")
	}
	res, err := s.YtDlp.Search(ctx, query, n)
	return res, BackendYtDlp, err
}

// Status says which backend the last search actually used, and why.
func (s *Service) Status() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.status == "" {
		return "No searches yet."
	}
	return s.status
}

func (s *Service) setStatus(v string) {
	s.mu.Lock()
	s.status = v
	s.mu.Unlock()
}

// YtDlpSearch queries SoundCloud, YouTube and YouTube Music's songs
// through yt-dlp at once. A label's own upload of the song asked for comes
// first; then SoundCloud and YouTube, interleaved, SoundCloud first because
// much of this library lives there.
type YtDlpSearch struct{ Runner ytdlp.Runner }

type part struct {
	res []Result
	err error
}

func (y YtDlpSearch) Search(ctx context.Context, query string, n int) ([]Result, error) {
	run := func(prefix, source string) part {
		entries, err := y.Runner.Search(ctx, prefix, query, n)
		out := make([]Result, 0, len(entries))
		for _, e := range entries {
			out = append(out, Result{
				Title: e.Title, Uploader: e.Poster(), URL: e.Link(), Thumbnail: e.CoverURL(),
				Source: source, DurationSec: int(e.Duration + 0.5), Plays: e.ViewCount,
				Preview: LooksLikePreview(source, e.Duration),
			})
		}
		return part{out, err}
	}
	var sc, yt, songs part
	var wg sync.WaitGroup
	wg.Go(func() { sc = run("scsearch", "soundcloud") })
	wg.Go(func() { yt = run("ytsearch", "youtube") })
	wg.Go(func() { songs = y.songs(ctx, query) })
	wg.Wait()
	if sc.err != nil && yt.err != nil && songs.err != nil {
		return nil, sc.err
	}
	// YouTube Music always answers with something; songs that aren't what
	// was asked for go last.
	var asked, others []Result
	for _, r := range songs.res {
		if Asked(query, r) {
			asked = append(asked, r)
		} else {
			others = append(others, r)
		}
	}
	out := append(asked, interleave(sc.res, yt.res)...)
	return append(out, others...), nil
}

// musicSongs is how many YouTube Music songs are described in full.
const musicSongs = 3

// MusicSearchURL is YouTube Music's search, Songs section.
func MusicSearchURL(query string) string {
	return "https://music.youtube.com/search?q=" + url.QueryEscape(query) + "#songs"
}

// songs searches YouTube Music's Songs: what labels upload of each release,
// track by track, to an "Artist - Topic" channel. It is the album audio,
// without a music video's intro, at the release's length, and it carries
// the release's artist, title and album. The search lists only ids and
// titles, so the first few are described in full, side by side.
func (y YtDlpSearch) songs(ctx context.Context, query string) part {
	found, err := y.Runner.SearchURL(ctx, MusicSearchURL(query), musicSongs)
	if err != nil {
		return part{err: err}
	}
	described := make([]Result, len(found))
	var wg sync.WaitGroup
	for i, e := range found {
		wg.Go(func() {
			info, err := y.Runner.Probe(ctx, e.Link())
			if err != nil || info.Duration <= 0 {
				return // one that can't be described isn't offered
			}
			described[i] = Result{
				Title: firstNonEmpty(info.Track, info.Title), Uploader: firstNonEmpty(info.Artist, resolve.CleanArtist(info.Poster())),
				URL: info.Link(), Thumbnail: info.CoverURL(), Source: SourceYouTubeMusic,
				DurationSec: int(info.Duration + 0.5), Plays: info.ViewCount,
			}
		})
	}
	wg.Wait()
	var out []Result
	for _, r := range described {
		if r.URL != "" {
			out = append(out, r)
		}
	}
	return part{res: out}
}

// Asked reports whether a result is what a search asked for: its artist and
// title cover the query's words, in any order.
func Asked(query string, r Result) bool {
	return resolve.Similarity(query, r.Uploader+" "+r.Title) >= 0.7
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func interleave(a, b []Result) []Result {
	out := make([]Result, 0, len(a)+len(b))
	for i := 0; i < len(a) || i < len(b); i++ {
		if i < len(a) {
			out = append(out, a[i])
		}
		if i < len(b) {
			out = append(out, b[i])
		}
	}
	return out
}

// LooksLikeURL decides whether Add's single field holds a link or a query
// (§6.10). Share sheets send text like "Listen to X on SoundCloud
// https://on.soundcloud.com/abc", so the first link in the text wins.
func LooksLikeURL(input string) (string, bool) {
	for _, f := range strings.Fields(input) {
		f = strings.Trim(f, "<>()[]\"'")
		lower := strings.ToLower(f)
		if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") {
			return f, true
		}
	}
	t := strings.TrimSpace(input)
	// A bare "soundcloud.com/artist/track": one token, a dot before a slash.
	if !strings.ContainsAny(t, " \t\n") {
		if dot, slash := strings.Index(t, "."), strings.Index(t, "/"); dot > 0 && slash > dot {
			return "https://" + t, true
		}
	}
	return "", false
}
