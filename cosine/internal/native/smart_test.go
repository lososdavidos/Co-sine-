package native

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
)

func TestSmartPlaylistRules(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "sesame", false)
	api := &API{Auth: as, Version: "t", Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Playlists: &playlists.Service{DB: d, Stats: &stats.Service{DB: d}}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); !slices.Contains(c, any("playlists")) || !slices.Contains(c, any("smart-playlists")) {
		t.Fatal(caps)
	}
	tok := login(t, srv)
	code, body := call(t, srv, "GET", "/cosine/v1/smart", tok, nil)
	lists := body["playlists"].([]any)
	if code != 200 || len(lists) != 8 || len(body["rules"].([]any)) != 7 {
		t.Fatal(code, body)
	}
	if first := lists[0].(map[string]any); first["name"] != "Recently added" || first["days"] != float64(30) ||
		first["description"] != "Added in the last 30 days" {
		t.Fatal(first)
	}

	code, made := call(t, srv, "POST", "/cosine/v1/smart", tok, map[string]any{
		"name": "Heavy rotation", "rule": "most-played", "span": "30d", "months": 3, "size": 9999})
	if code != http.StatusCreated || made["span"] != "30d" || made["months"] != nil || made["size"] != float64(500) ||
		made["description"] != "Most played in the last 30 days" {
		t.Fatal(code, made)
	}
	id := made["id"].(string)
	code, changed := call(t, srv, "PUT", "/cosine/v1/smart/"+id, tok, map[string]any{
		"name": "Gathering dust", "rule": "forgotten", "months": 12})
	if code != 200 || changed["id"] != id || changed["months"] != float64(12) || changed["span"] != nil {
		t.Fatal(code, changed)
	}
	for _, bad := range []map[string]any{
		{"name": "x", "rule": "loved-by-strangers"},
		{"name": "", "rule": "recent"},
		{"name": "x", "rule": "most-played", "span": "forever"},
	} {
		if code, _ := call(t, srv, "POST", "/cosine/v1/smart", tok, bad); code != http.StatusBadRequest {
			t.Error(bad, code)
		}
	}
	if code, _ := call(t, srv, "PUT", "/cosine/v1/smart/sp-999", tok, map[string]any{"name": "x", "rule": "recent"}); code != http.StatusNotFound {
		t.Error("someone else's or no one's:", code)
	}
	_, body = call(t, srv, "GET", "/cosine/v1/smart", tok, nil)
	if lists := body["playlists"].([]any); len(lists) != 9 || lists[8].(map[string]any)["name"] != "Gathering dust" {
		t.Fatal(lists)
	}
}
