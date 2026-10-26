package native

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/scrobble"
)

func TestConnectingScrobblers(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "sesame", false)
	lb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		valid := r.Header.Get("Authorization") == "Token good"
		json.NewEncoder(w).Encode(map[string]any{"valid": valid, "user_name": "alice_lb"})
	}))
	t.Cleanup(lb.Close)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	api := &API{Auth: as, Version: "t", Log: log, Scrobbler: &scrobble.Service{DB: d, Log: log, ListenBrainzURL: lb.URL}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if !slices.Contains(caps["capabilities"].([]any), any("scrobbling")) {
		t.Fatal(caps)
	}
	tok := login(t, srv)
	_, st := call(t, srv, "GET", "/cosine/v1/scrobbling", tok, nil)
	if st["lastfmAvailable"] != false || len(st["connected"].([]any)) != 0 {
		t.Fatal(st)
	}
	if code, body := call(t, srv, "POST", "/cosine/v1/scrobbling/lastfm/start", tok, nil); code != http.StatusConflict {
		t.Fatal("Last.fm needs the server's API account first:", code, body)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/scrobbling/listenbrainz", tok, map[string]string{"token": "bad"}); code != http.StatusBadRequest {
		t.Fatal(code)
	}
	code, body := call(t, srv, "POST", "/cosine/v1/scrobbling/listenbrainz", tok, map[string]string{"token": "good"})
	if code != 200 || body["username"] != "alice_lb" {
		t.Fatal(code, body)
	}
	_, st = call(t, srv, "GET", "/cosine/v1/scrobbling", tok, nil)
	if c := st["connected"].([]any); len(c) != 1 || c[0].(map[string]any)["service"] != "listenbrainz" ||
		c[0].(map[string]any)["username"] != "alice_lb" {
		t.Fatal(st)
	}
	call(t, srv, "DELETE", "/cosine/v1/scrobbling/listenbrainz", tok, nil)
	if _, st = call(t, srv, "GET", "/cosine/v1/scrobbling", tok, nil); len(st["connected"].([]any)) != 0 {
		t.Fatal(st)
	}
}
