package playlists

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

type env struct {
	s          *Service
	g          *ingest.Ingester
	db         *db.DB
	drop       string
	alice, bob int64
	titles     map[int64]string
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	d.SetSetting(context.Background(), db.SettingStorePath, filepath.Join(dir, "store"))
	user := func(name string) int64 {
		res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", name)
		id, _ := res.LastInsertId()
		return id
	}
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8}}
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	return &env{&Service{DB: d}, g, d, drop, user("alice"), user("bob"), map[int64]string{}}
}

// song adds a song to the given Libraries.
func (e *env) song(t *testing.T, title string, users ...int64) int64 {
	t.Helper()
	out, err := e.g.Ingest(context.Background(), ingest.Request{
		Path:   testutil.MP3{Title: title, Artist: "Skeler", Body: title}.Write(t, filepath.Join(e.drop, title+".mp3")),
		Source: "url", ForUsers: users})
	if err != nil {
		t.Fatal(err)
	}
	e.titles[out.TrackID] = title
	return out.TrackID
}

// list reads a playlist's entries as "A B C", hidden ones included.
func (e *env) list(t *testing.T, id int64) string {
	t.Helper()
	rows, err := e.db.Query("SELECT track_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var tr int64
		rows.Scan(&tr)
		out = append(out, e.titles[tr])
	}
	return strings.Join(out, " ")
}

func (e *env) has(user, track int64) bool {
	var n int
	e.db.QueryRow("SELECT COUNT(*) FROM pointers WHERE user_id = ? AND track_id = ?", user, track).Scan(&n)
	return n == 1
}

func TestMove(t *testing.T) {
	for _, c := range []struct {
		name   string
		in     []int64
		track  int64
		n      int
		after  int64
		afterN int
		want   []int64
		ok     bool
	}{
		{"down", []int64{1, 2, 3}, 1, 0, 3, 0, []int64{2, 3, 1}, true},
		{"up", []int64{1, 2, 3}, 3, 0, 1, 0, []int64{1, 3, 2}, true},
		{"to the top", []int64{1, 2, 3}, 3, 0, 0, 0, []int64{3, 1, 2}, true},
		{"the second of a pair", []int64{1, 2, 1}, 1, 1, 0, 0, []int64{1, 1, 2}, true},
		{"after the second of a pair", []int64{1, 2, 1}, 2, 0, 1, 1, []int64{1, 1, 2}, true},
		{"after itself", []int64{1, 2}, 1, 0, 1, 0, []int64{1, 2}, true},
		{"gone", []int64{1, 2}, 3, 0, 1, 0, []int64{1, 2}, false},
		{"its neighbour gone", []int64{1, 2}, 1, 0, 3, 0, []int64{1, 2}, false},
	} {
		if got, ok := Move(c.in, c.track, c.n, c.after, c.afterN); ok != c.ok || !slices.Equal(got, c.want) {
			t.Errorf("%s: got %v %v, want %v %v", c.name, got, ok, c.want, c.ok)
		}
	}
}

func TestOpsReplayWhatWasDoneOnce(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	a, b, c := e.song(t, "Ascend", e.alice), e.song(t, "Bloom", e.alice), e.song(t, "Crush", e.alice)
	other := e.song(t, "Drift", e.bob)

	// Made offline: created, then added to, before Cosine ever heard of it.
	ops := []Op{
		{ID: "o1", Kind: OpCreate, Name: "Night drive", Tracks: []int64{a, b}},
		{ID: "o2", Kind: OpAdd, New: "o1", Tracks: []int64{c, other}}, // bob's song isn't alice's to add
		{ID: "o3", Kind: OpMove, New: "o1", Track: c, After: 0},
		{ID: "o4", Kind: OpRemove, New: "o1", Track: b},
		{ID: "o5", Kind: OpRename, New: "o1", Name: "Late drive"},
	}
	res, err := e.s.Apply(ctx, e.alice, ops)
	if err != nil {
		t.Fatal(err)
	}
	id := res[0].Playlist
	if id == 0 || res[1].Playlist != id || res[4].Skipped != "" {
		t.Fatalf("%+v", res)
	}
	if got := e.list(t, id); got != "Crush Ascend" {
		t.Fatal(got)
	}
	var name string
	e.db.QueryRow("SELECT name FROM playlists WHERE id = ?", id).Scan(&name)
	if name != "Late drive" {
		t.Fatal(name)
	}

	// The answer was lost and the phone sends it all again: nothing doubles.
	again, err := e.s.Apply(ctx, e.alice, ops)
	if err != nil || again[0].Playlist != id {
		t.Fatalf("%+v %v", again, err)
	}
	var lists int
	e.db.QueryRow("SELECT COUNT(*) FROM playlists").Scan(&lists)
	if lists != 1 || e.list(t, id) != "Crush Ascend" {
		t.Fatal(lists, e.list(t, id))
	}

	// Meanwhile another device added Bloom back at the end; this phone,
	// offline, moved Ascend to the top. Both stay.
	if err := e.s.Edit(ctx, e.alice, id, Edit{Add: []int64{b}}); err != nil {
		t.Fatal(err)
	}
	res, _ = e.s.Apply(ctx, e.alice, []Op{{ID: "o6", Kind: OpMove, Playlist: id, Track: a, After: 0}})
	if res[0].Skipped != "" || e.list(t, id) != "Ascend Crush Bloom" {
		t.Fatal(res, e.list(t, id))
	}

	// Taking out a song already gone, or editing a deleted playlist, is
	// skipped, not refused: the phone lets those go.
	res, _ = e.s.Apply(ctx, e.alice, []Op{
		{ID: "o7", Kind: OpRemove, Playlist: id, Track: other},
		{ID: "o8", Kind: OpDelete, Playlist: id},
		{ID: "o9", Kind: OpAdd, Playlist: id, Tracks: []int64{a}},
		{ID: "o10", Kind: OpRename, Playlist: id, Name: " "},
		{ID: "o11", Kind: "shuffle", Playlist: id},
	})
	if res[0].Skipped != SkipMissing || res[1].Skipped != "" || res[2].Skipped != SkipGone {
		t.Fatalf("%+v", res)
	}
	// Nor can bob edit alice's playlists.
	res, _ = e.s.Apply(ctx, e.bob, []Op{{ID: "b1", Kind: OpAdd, Playlist: id, Tracks: []int64{other}}})
	if res[0].Skipped != SkipGone {
		t.Fatalf("%+v", res)
	}
}

func TestSharedPlaylistsPlayInFullForEveryone(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	carol := func() int64 {
		res, _ := e.db.Exec("INSERT INTO users(name, password_enc, created_at) VALUES('carol', x'00', 0)")
		id, _ := res.LastInsertId()
		return id
	}()
	a, b := e.song(t, "Ascend", e.alice), e.song(t, "Bloom", e.alice, e.bob)
	dropped := e.song(t, "Crush", e.alice, e.bob)
	if _, err := e.g.Dismiss(ctx, e.bob, []int64{dropped}); err != nil {
		t.Fatal(err)
	}
	id, err := e.s.Create(ctx, e.alice, "Ours", []int64{a, b, dropped})
	if err != nil {
		t.Fatal(err)
	}

	if err := e.s.Share(ctx, e.bob, id, "carol"); err != ErrNotFound {
		t.Fatal("only someone on it, and then only its owner, shares it:", err)
	}
	for to, want := range map[string]error{"alice": ErrSelf, "nobody": ErrNoAccount, "bob": nil} {
		if err := e.s.Share(ctx, e.alice, id, to); err != want {
			t.Fatalf("sharing with %s: %v", to, err)
		}
	}
	if err := e.s.Share(ctx, e.alice, id, "bob"); err != nil {
		t.Fatal("sharing again changes nothing:", err)
	}
	// Invited is not yet on it.
	if err := e.s.Edit(ctx, e.bob, id, Edit{Add: []int64{b}}); err != ErrNotFound {
		t.Fatal(err)
	}
	if e.has(e.bob, a) {
		t.Fatal("nothing is added before accepting")
	}

	// Accepting adds what bob doesn't have, but not what he removed himself.
	added, err := e.s.Accept(ctx, e.bob, id)
	if err != nil || added != 1 || !e.has(e.bob, a) || e.has(e.bob, dropped) {
		t.Fatal(added, err, e.has(e.bob, a), e.has(e.bob, dropped))
	}
	if _, err := e.s.Accept(ctx, e.bob, id); err != ErrNoInvite {
		t.Fatal("an invitation is taken up once:", err)
	}

	// What bob adds goes to alice's Library too; he can't share it on.
	mine := e.song(t, "Drift", e.bob)
	if err := e.s.Edit(ctx, e.bob, id, Edit{Add: []int64{mine}}); err != nil {
		t.Fatal(err)
	}
	if !e.has(e.alice, mine) || e.list(t, id) != "Ascend Bloom Crush Drift" {
		t.Fatal(e.has(e.alice, mine), e.list(t, id))
	}
	if err := e.s.Share(ctx, e.bob, id, "carol"); err != ErrNotOwner {
		t.Fatal(err)
	}

	// Carol says no; bob leaves, and it stays alice's with his songs in it.
	e.s.Share(ctx, e.alice, id, "carol")
	if err := e.s.Decline(ctx, carol, id); err != nil {
		t.Fatal(err)
	}
	if err := e.s.Delete(ctx, e.bob, id); err != nil {
		t.Fatal(err)
	}
	if ms, _ := e.s.Members(ctx, id); len(ms) != 0 {
		t.Fatal(ms)
	}
	if e.list(t, id) != "Ascend Bloom Crush Drift" || !e.has(e.bob, a) {
		t.Fatal("leaving keeps the playlist and the songs")
	}

	// Asked again, then the invitation withdrawn.
	e.s.Share(ctx, e.alice, id, "bob")
	if ms, _ := e.s.Members(ctx, id); len(ms) != 1 || ms[0] != (Member{"bob", false}) {
		t.Fatal(ms)
	}
	if err := e.s.Unshare(ctx, e.alice, id, "bob"); err != nil {
		t.Fatal(err)
	}
	if _, err := e.s.Accept(ctx, e.bob, id); err != ErrNoInvite {
		t.Fatal(err)
	}
}
