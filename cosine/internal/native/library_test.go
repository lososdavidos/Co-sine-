package native

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
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
	alice, _ := as.CreateUser(ctx, "alice", "open sesame", false)
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
	if c := caps["capabilities"].([]any); len(c) != 2 || c[0] != "remove" || c[1] != "upload" {
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

func TestUploadAPI(t *testing.T) {
	// A file from the phone is filed like any other, and lands in the
	// uploader's Library only.
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "open sesame", false)
	bob, _ := as.CreateUser(ctx, "bob", "hunter22", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	api := &API{Auth: as, Version: "t", Log: log, Ingester: g}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	tok := login(t, srv)

	send := func(name string, body []byte) (int, map[string]any) {
		req, _ := http.NewRequest("POST", srv.URL+"/cosine/v1/library/upload?name="+url.QueryEscape(name), bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+tok)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var out map[string]any
		json.NewDecoder(res.Body).Decode(&out)
		return res.StatusCode, out
	}
	mp3, _ := os.ReadFile(testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "up"}.Write(t, filepath.Join(t.TempDir(), "x.mp3")))
	code, out := send("Skeler - Tides.mp3", mp3)
	if code != http.StatusCreated || out["duplicate"] != false || !strings.HasPrefix(out["track"].(string), "tr-") {
		t.Fatal(code, out)
	}
	var mine, bobs int
	d.QueryRow("SELECT COUNT(*) FROM pointers WHERE user_id = ?", alice.ID).Scan(&mine)
	d.QueryRow("SELECT COUNT(*) FROM pointers WHERE user_id = ?", bob.ID).Scan(&bobs)
	if mine != 1 || bobs != 0 {
		t.Fatal("alice", mine, "bob", bobs)
	}
	// The same bytes again: already there.
	if code, out := send("again.mp3", mp3); code != http.StatusCreated || out["duplicate"] != true {
		t.Fatal(code, out)
	}
	if code, _ := send("notes.txt", []byte("hello")); code != http.StatusUnsupportedMediaType {
		t.Fatal("a text file was taken:", code)
	}
	if code, _ := send("", mp3); code != http.StatusBadRequest {
		t.Fatal("no name:", code)
	}
	// Nothing left behind but what was filed.
	if left, _ := os.ReadDir(filepath.Join(dir, "uploads")); len(left) != 0 {
		t.Fatal(left)
	}
}
