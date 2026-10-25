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

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/mirror"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestSyncAPI(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "sesame", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}, Probe: func(string) int { return 200 }}
	os.MkdirAll(filepath.Join(dir, "drop"), 0o755)
	add := func(title, body string) {
		if _, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: title, Artist: "Skeler", Body: body}.
			Write(t, filepath.Join(dir, "drop", body+".mp3")), Source: "inbox"}); err != nil {
			t.Fatal(err)
		}
	}
	add("Tides", "a")
	api := &API{Auth: as, Version: "t", Log: log, Mirror: &mirror.Service{DB: d}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); len(c) != 1 || c[0] != "sync" {
		t.Fatal(caps)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/sync", "", nil); code != http.StatusUnauthorized {
		t.Fatal("the library needs a session")
	}
	tok := login(t, srv)
	code, full := call(t, srv, "GET", "/cosine/v1/sync", tok, nil)
	if code != 200 || full["full"] != true || len(full["tracks"].([]any)) != 1 || len(full["artists"].([]any)) != 1 {
		t.Fatal(code, full)
	}
	cursor := itoa(int(full["cursor"].(float64)))
	add("Surge", "b")
	_, delta := call(t, srv, "GET", "/cosine/v1/sync?after="+cursor, tok, nil)
	if delta["full"] != false || len(delta["tracks"].([]any)) != 1 || delta["tracks"].([]any)[0].(map[string]any)["title"] != "Surge" {
		t.Fatal(delta)
	}
	if r, ok := delta["removedTracks"].([]any); !ok || len(r) != 0 {
		t.Fatal("removedTracks is always a list:", delta)
	}
}
