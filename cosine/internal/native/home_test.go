package native

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/homes"
)

func TestHomeIsKeptPerAccountAndDevice(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "sesame", false)
	bob, _ := as.CreateUser(ctx, "bob", "hunter2", false)
	api := &API{Auth: as, Version: "t", Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Homes: &homes.Service{DB: d}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if !slices.Contains(caps["capabilities"].([]any), any("home-layout")) {
		t.Fatal(caps)
	}
	alice := loginAs(t, srv, "alice", "sesame")
	if code, out := call(t, srv, "GET", "/cosine/v1/home/phone", alice, nil); code != 200 || out["layout"] != nil {
		t.Fatal("nothing yet:", code, out)
	}
	layout := map[string]any{"tiles": []any{map[string]any{"kind": "now-playing", "size": "4x2"}}, "updated": 1700000000000.0}
	if code, out := call(t, srv, "PUT", "/cosine/v1/home/phone", alice, map[string]any{"layout": layout}); code != 200 {
		t.Fatal(code, out)
	}
	_, out := call(t, srv, "GET", "/cosine/v1/home/phone", alice, nil)
	got := out["layout"].(map[string]any)
	if got["updated"] != 1700000000000.0 || got["tiles"].([]any)[0].(map[string]any)["kind"] != "now-playing" {
		t.Fatal("kept as sent:", out)
	}

	// The desktop's is its own; another account's is theirs.
	if _, out := call(t, srv, "GET", "/cosine/v1/home/desktop", alice, nil); out["layout"] != nil {
		t.Fatal(out)
	}
	bobs := loginAs(t, srv, "bob", "hunter2")
	if _, out := call(t, srv, "GET", "/cosine/v1/home/phone", bobs, nil); out["layout"] != nil {
		t.Fatal("bob sees alice's Home:", out)
	}

	// Only known devices, and only a JSON object of reasonable size.
	if code, _ := call(t, srv, "GET", "/cosine/v1/home/toaster", alice, nil); code != http.StatusNotFound {
		t.Fatal(code)
	}
	for _, bad := range []any{"tiles", []any{1, 2}, nil, map[string]any{"pad": strings.Repeat("x", homes.MaxSize)}} {
		if code, _ := call(t, srv, "PUT", "/cosine/v1/home/phone", alice, map[string]any{"layout": bad}); code != http.StatusBadRequest {
			t.Fatal(bad, code)
		}
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/home/phone", "", nil); code != http.StatusUnauthorized {
		t.Fatal(code)
	}

	// An account made later under a reused id starts with nothing.
	call(t, srv, "PUT", "/cosine/v1/home/phone", bobs, map[string]any{"layout": layout})
	as.DeleteUser(ctx, bob.ID)
	d.Exec("INSERT INTO users(id, name, password_enc, created_at) VALUES(?, 'carol', x'00', 1)", bob.ID)
	if l, err := api.Homes.Layout(ctx, bob.ID, "phone"); err != nil || l != nil {
		t.Fatal("carol has bob's Home:", string(l), err)
	}
}
