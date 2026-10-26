package native

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/mirror"
	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func loginAs(t *testing.T, srv *httptest.Server, name, pass string) string {
	t.Helper()
	sum := md5.Sum([]byte(pass + "salt1"))
	code, out := call(t, srv, "POST", "/cosine/v1/auth", "", map[string]string{
		"username": name, "token": hex.EncodeToString(sum[:]), "salt": "salt1"})
	if code != 200 {
		t.Fatal(code, out)
	}
	return out["token"].(string)
}

func TestPlaylistsOfflineAndShared(t *testing.T) {
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "sesame", false)
	as.CreateUser(ctx, "bob", "hunter2", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, Log: log, Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	os.MkdirAll(filepath.Join(dir, "drop"), 0o755)
	song := func(title string) string {
		out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: title, Artist: "Skeler", Body: title}.
			Write(t, filepath.Join(dir, "drop", title+".mp3")), Source: "url", ForUsers: []int64{alice.ID}})
		if err != nil {
			t.Fatal(err)
		}
		return "tr-" + itoa(int(out.TrackID))
	}
	a, b := song("Ascend"), song("Bloom")
	lists := &playlists.Service{DB: d, Stats: &stats.Service{DB: d}}
	api := &API{Auth: as, Version: "t", Log: log, Playlists: lists, Mirror: &mirror.Service{DB: d, Playlists: lists}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); !slices.Contains(c, any("playlist-sync")) || !slices.Contains(c, any("shared-playlists")) {
		t.Fatal(caps)
	}
	tok, bobTok := login(t, srv), loginAs(t, srv, "bob", "hunter2")

	// Made and filled offline, sent on reconnect.
	ops := map[string]any{"ops": []map[string]any{
		{"id": "p1", "op": "create", "name": "Night drive", "tracks": []string{a}},
		{"id": "p2", "op": "add", "new": "p1", "tracks": []string{b}},
		{"id": "p3", "op": "move", "new": "p1", "track": b},
	}}
	code, body := call(t, srv, "POST", "/cosine/v1/playlists/ops", tok, ops)
	results, _ := body["results"].([]any)
	if code != 200 || len(results) != 3 {
		t.Fatal(code, body)
	}
	id := results[0].(map[string]any)["playlist"].(string)
	if results[2].(map[string]any)["playlist"] != id || results[2].(map[string]any)["skipped"] != nil {
		t.Fatal(results)
	}
	// Sent again, because the answer was lost: the same answer, nothing doubled.
	_, again := call(t, srv, "POST", "/cosine/v1/playlists/ops", tok, ops)
	if again["results"].([]any)[0].(map[string]any)["playlist"] != id {
		t.Fatal(again)
	}
	code, synced := call(t, srv, "GET", "/cosine/v1/sync?after=0", tok, nil)
	pls := synced["playlists"].([]any)
	if code != 200 || len(pls) != 1 {
		t.Fatal(code, synced)
	}
	if p := pls[0].(map[string]any); p["id"] != id || p["name"] != "Night drive" || p["mine"] != true ||
		len(p["tracks"].([]any)) != 2 || p["tracks"].([]any)[0] != b {
		t.Fatal(p)
	}
	if len(synced["smart"].([]any)) != 8 || synced["smartTag"] == "" {
		t.Fatal(synced["smart"], synced["smartTag"])
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/playlists/ops", tok, map[string]any{"ops": []map[string]any{{"op": "add"}}}); code != http.StatusBadRequest {
		t.Fatal("an op with no id can't be told from a resend", code)
	}

	// Shared with bob, who sees an invitation and accepts it.
	_, accounts := call(t, srv, "GET", "/cosine/v1/accounts", tok, nil)
	if names := accounts["accounts"].([]any); len(names) != 1 || names[0] != "bob" {
		t.Fatal(accounts)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/playlists/"+id+"/share", tok, map[string]string{"with": "nobody"}); code != http.StatusNotFound {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/playlists/"+id+"/share", tok, map[string]string{"with": "bob"}); code != 200 {
		t.Fatal(code)
	}
	_, bobSync := call(t, srv, "GET", "/cosine/v1/sync?after=0", bobTok, nil)
	if inv := bobSync["invites"].([]any); len(inv) != 1 || inv[0].(map[string]any)["owner"] != "alice" ||
		inv[0].(map[string]any)["songs"] != float64(2) {
		t.Fatal(bobSync)
	}
	code, accepted := call(t, srv, "POST", "/cosine/v1/playlists/"+id+"/accept", bobTok, nil)
	if code != 200 || accepted["added"] != float64(2) {
		t.Fatal(code, accepted)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/playlists/"+id+"/accept", bobTok, nil); code != http.StatusNotFound {
		t.Fatal("accepted once", code)
	}
	// Bob can edit it, but not share it on.
	_, res := call(t, srv, "POST", "/cosine/v1/playlists/ops", bobTok, map[string]any{"ops": []map[string]any{
		{"id": "b1", "op": "remove", "playlist": id, "track": a}}})
	if res["results"].([]any)[0].(map[string]any)["skipped"] != nil {
		t.Fatal(res)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/playlists/"+id+"/share", bobTok, map[string]string{"with": "alice"}); code != http.StatusForbidden {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "DELETE", "/cosine/v1/playlists/"+id+"/share/bob", tok, nil); code != 200 {
		t.Fatal(code)
	}
	_, bobSync = call(t, srv, "GET", "/cosine/v1/sync?after=0", bobTok, nil)
	if len(bobSync["playlists"].([]any)) != 0 || len(bobSync["tracks"].([]any)) != 2 {
		t.Fatal("no longer shared, but the songs stay his:", bobSync)
	}
}
