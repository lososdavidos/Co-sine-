package native

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestStatsAPIIsOnesOwnListening(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "sesame", false)
	bob, _ := as.CreateUser(ctx, "bob", "x", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}, Probe: func(string) int { return 200 }}
	os.MkdirAll(filepath.Join(dir, "drop"), 0o755)
	out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "x"}.
		Write(t, filepath.Join(dir, "drop", "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	for _, p := range []struct {
		user int64
		at   time.Time
	}{{alice.ID, now.AddDate(0, 0, -1)}, {alice.ID, now.AddDate(0, 0, -2)}, {bob.ID, now.AddDate(0, 0, -1)}} {
		d.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, ?)", p.user, out.TrackID, p.at.UnixMilli())
	}
	api := &API{Auth: as, Version: "t", Log: log, Stats: &stats.Service{DB: d, Now: func() time.Time { return now }}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); len(c) != 1 || c[0] != "stats" {
		t.Fatal(caps)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/stats", "", nil); code != http.StatusUnauthorized {
		t.Fatal("stats need a session")
	}
	tok := login(t, srv)
	code, body := call(t, srv, "GET", "/cosine/v1/stats?window=30d", tok, nil)
	rep := body["report"].(map[string]any)
	if code != 200 || rep["summary"].(map[string]any)["plays"] != float64(2) || len(body["windows"].([]any)) != 3 {
		t.Fatal(code, body)
	}
	if top := rep["tracks"].([]any)[0].(map[string]any); top["id"] != "tr-"+itoa(int(out.TrackID)) || top["plays"] != float64(2) {
		t.Fatal(top)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/stats?window=2099", tok, nil); code != http.StatusBadRequest {
		t.Fatal("a bad window is a 400")
	}
	code, body = call(t, srv, "GET", "/cosine/v1/stats/years", tok, nil)
	if code != 200 || len(body["years"].([]any)) != 1 {
		t.Fatal(code, body)
	}
	code, body = call(t, srv, "GET", "/cosine/v1/views/most-played?window=year", tok, nil)
	if tracks := body["tracks"].([]any); code != 200 || len(tracks) != 1 || tracks[0].(map[string]any)["plays"] != float64(2) {
		t.Fatal(code, body)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/views/nope", tok, nil); code != http.StatusNotFound {
		t.Fatal("an unknown view is a 404")
	}
	if code, body := call(t, srv, "GET", "/cosine/v1/views", tok, nil); code != 200 || len(body["views"].([]any)) != len(stats.Views) {
		t.Fatal(code, body)
	}
}
