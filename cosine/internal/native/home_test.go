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
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/devices"
	"github.com/lososdavidos/Co-sine-/cosine/internal/homes"
)

func TestHomeIsKeptPerAccountAndDevice(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "open sesame", false)
	bob, _ := as.CreateUser(ctx, "bob", "hunter22", false)
	api := &API{Auth: as, Version: "t", Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Homes: &homes.Service{DB: d}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if !slices.Contains(caps["capabilities"].([]any), any("home-layout")) {
		t.Fatal(caps)
	}
	alice := loginAs(t, srv, "alice", "open sesame")
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
	bobs := loginAs(t, srv, "bob", "hunter22")
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

func TestDevicesAPI(t *testing.T) {
	srv, _ := deviceServer(t)
	tok := login(t, srv)
	if code, _ := call(t, srv, "GET", "/cosine/v1/devices/sine-a/session", tok, nil); code != http.StatusNotFound {
		t.Fatal("no session yet:", code)
	}
	code, _ := call(t, srv, "PUT", "/cosine/v1/devices/sine-a/session", tok, map[string]any{
		"name": "Pixel 8", "kind": "phone", "queue": []string{"tr-1", "tr-2"}, "current": 1, "positionMs": 4000, "playing": true})
	if code != 200 {
		t.Fatal(code)
	}
	code, s := call(t, srv, "GET", "/cosine/v1/devices/sine-a/session", tok, nil)
	if code != 200 || s["current"] != float64(1) || s["positionMs"] != float64(4000) || len(s["queue"].([]any)) != 2 {
		t.Fatal(code, s)
	}
	_, list := call(t, srv, "GET", "/cosine/v1/devices", tok, nil)
	ds := list["devices"].([]any)
	if len(ds) != 1 || ds[0].(map[string]any)["device"].(map[string]any)["name"] != "Pixel 8" {
		t.Fatal(list)
	}
	if code, _ := call(t, srv, "PUT", "/cosine/v1/devices/sine-b", tok, map[string]any{"name": "Toaster", "kind": "toaster"}); code != http.StatusBadRequest {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "DELETE", "/cosine/v1/devices/sine-a", tok, nil); code != 200 {
		t.Fatal(code)
	}
}

func deviceServer(t *testing.T) (*httptest.Server, *db.DB) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(context.Background(), "alice", "open sesame", false)
	api := &API{Auth: as, Version: "t", Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Devices: &devices.Service{DB: d}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, d
}

func TestRemoteControl(t *testing.T) {
	// One device tells another what to do, through Cosine (§4B).
	srv, _ := deviceServer(t)
	tok := login(t, srv)
	call(t, srv, "PUT", "/cosine/v1/devices/tablet", tok, map[string]any{"name": "Tablet", "kind": "phone"})
	if code, _ := call(t, srv, "POST", "/cosine/v1/devices/tablet/commands", tok, map[string]any{"type": "pause"}); code != http.StatusConflict {
		t.Fatal("not listening yet, so 409:", code)
	}
	got := make(chan map[string]any, 1)
	go func() {
		_, out := call(t, srv, "GET", "/cosine/v1/devices/tablet/commands?wait=5", tok, nil)
		got <- out
	}()
	// Once it's listening, it's listed so, and a command reaches it at once.
	var listening bool
	for i := 0; i < 50 && !listening; i++ {
		time.Sleep(20 * time.Millisecond)
		_, list := call(t, srv, "GET", "/cosine/v1/devices", tok, nil)
		for _, d := range list["devices"].([]any) {
			listening = listening || d.(map[string]any)["listening"] == true
		}
	}
	if !listening {
		t.Fatal("not listed as listening")
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/devices/tablet/commands", tok, map[string]any{"type": "seek", "positionMs": 9000}); code != http.StatusAccepted {
		t.Fatal(code)
	}
	select {
	case out := <-got:
		cmds := out["commands"].([]any)
		if len(cmds) != 1 || cmds[0].(map[string]any)["type"] != "seek" || cmds[0].(map[string]any)["positionMs"] != float64(9000) {
			t.Fatal(out)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the command didn't arrive")
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/devices/tablet/commands", tok, map[string]any{"type": "explode"}); code != http.StatusBadRequest {
		t.Fatal(code)
	}
}
