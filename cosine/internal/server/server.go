// Package server wires Cosine together: one process, one SQLite file, one Store.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/dashboard"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/inbox"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/subsonic"
)

type Config struct {
	DataDir string // holds cosine.db, secret.key and cached artwork
	Listen  string
	Version string
}

type Server struct {
	cfg      Config
	log      *slog.Logger
	db       *db.DB
	ingester *ingest.Ingester
	handler  http.Handler

	inboxMu     sync.Mutex
	inboxCancel context.CancelFunc
	inboxDone   chan struct{}
}

// Capabilities are the native features this Cosine offers beyond Subsonic
// (§9.3). Sine hides anything not listed. Additive only: an entry, once
// shipped, never changes meaning.
var Capabilities = []string{}

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
	s.ingester = &ingest.Ingester{
		DB:              d,
		DataDir:         cfg.DataDir,
		ReviewThreshold: 0.8,
		Log:             log,
		Probe:           ingest.FFProbe(),
		Resolver: resolve.Chain{
			MinConfidence: 0.8,
			Resolvers:     []resolve.Resolver{resolve.SourceMetadata{}},
		},
	}
	if s.ingester.Probe == nil {
		log.Warn("ffprobe not found: track durations will be 0 until it is installed")
	}

	api := &subsonic.API{DB: d, Auth: as, DataDir: cfg.DataDir, Version: cfg.Version, Log: log}
	dash := &dashboard.Dashboard{DB: d, Auth: as, Ingester: s.ingester, OnInboxChange: s.startInbox, Log: log}

	mux := http.NewServeMux()
	mux.Handle("/rest/", api.Handler())
	mux.HandleFunc("GET /cosine/v1/capabilities", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"version": cfg.Version, "capabilities": Capabilities})
	})
	mux.Handle("/", dash.Handler())
	s.handler = mux
	return s, nil
}

func (s *Server) Handler() http.Handler { return s.handler }

// Run serves until ctx is cancelled, then shuts down gracefully.
func (s *Server) Run(ctx context.Context) error {
	defer s.db.Close()

	go func() {
		if n, err := s.ingester.Verify(ctx); err != nil {
			s.log.Error("verifying the Store", "err", err)
		} else if n > 0 {
			s.log.Warn("files missing from the Store", "count", n)
		}
	}()
	if p, _ := s.db.Setting(ctx, db.SettingInboxPath); p != "" {
		s.startInbox(p)
	}
	defer s.stopInbox()

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
