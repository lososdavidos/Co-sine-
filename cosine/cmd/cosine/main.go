// Command cosine is the Sine & Cosine music server.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/lososdavidos/Co-sine-/cosine/internal/server"
)

// version is set at build time: -ldflags "-X main.version=..."
var version = "dev"

func main() {
	cfg := server.Config{Version: version}
	flag.StringVar(&cfg.DataDir, "data", env("COSINE_DATA", "./data"), "directory for the database, key and artwork cache (env COSINE_DATA)")
	flag.StringVar(&cfg.Listen, "listen", env("COSINE_LISTEN", ":4534"), "address to listen on (env COSINE_LISTEN)")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	s, err := server.New(cfg, log)
	if err != nil {
		log.Error("starting", "err", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := s.Run(ctx); err != nil {
		log.Error("server stopped", "err", err)
		os.Exit(1)
	}
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
