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
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestRemoveAPI(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "sesame", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	os.MkdirAll(filepath.Join(dir, "drop"), 0o755)
	out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "x"}.
		Write(t, filepath.Join(dir, "drop", "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	api := &API{Auth: as, Version: "t", Log: log, Ingester: g}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); len(c) != 1 || c[0] != "remove" {
		t.Fatal(caps)
	}
	tok := login(t, srv)
	if code, _ := call(t, srv, "POST", "/cosine/v1/library/remove", tok, map[string]any{"tracks": []string{}}); code != http.StatusBadRequest {
		t.Fatal("nothing to remove is a bad request")
	}
	code, body := call(t, srv, "POST", "/cosine/v1/library/remove", tok, map[string]any{"tracks": []string{"tr-" + itoa(int(out.TrackID))}})
	if code != 200 || body["removed"] != float64(1) {
		t.Fatal(code, body)
	}
	var n int
	d.QueryRow("SELECT COUNT(*) FROM pointers WHERE user_id = ?", alice.ID).Scan(&n)
	if n != 0 {
		t.Fatal("still in alice's library")
	}
}
