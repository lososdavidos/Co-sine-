package mirror

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
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
	root, drop string
	alice, bob int64
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	root := filepath.Join(dir, "store")
	d.SetSetting(context.Background(), db.SettingStorePath, root)
	user := func(name string) int64 {
		res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", name)
		id, _ := res.LastInsertId()
		return id
	}
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8}}
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	return &env{&Service{DB: d}, g, d, root, drop, user("alice"), user("bob")}
}

func (e *env) add(t *testing.T, m testutil.MP3, users ...int64) ingest.Outcome {
	t.Helper()
	out, err := e.g.Ingest(context.Background(), ingest.Request{
		Path: m.Write(t, filepath.Join(e.drop, m.Title+m.Body+".mp3")), Source: "url", ForUsers: users})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func ids[T any](items []T, id func(T) string) string {
	var out []string
	for _, it := range items {
		out = append(out, id(it))
	}
	sort.Strings(out)
	return strings.Join(out, " ")
}

func trackIDs(c Changes) string  { return ids(c.Tracks, func(t Track) string { return t.Title }) }
func albumIDs(c Changes) string  { return ids(c.Albums, func(a Album) string { return a.Name }) }
func artistIDs(c Changes) string { return ids(c.Artists, func(a Artist) string { return a.Name }) }

func TestFullThenOnlyWhatChanged(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Album: "Tides EP", Track: "1", Body: "a"}, e.alice)
	e.add(t, testutil.MP3{Title: "Surge", Artist: "Skeler", Album: "Tides EP", Track: "2", Body: "b"}, e.alice)
	e.add(t, testutil.MP3{Title: "Glass", Artist: "plenka", Body: "c"}, e.alice)
	e.add(t, testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "d"}, e.bob)

	full, err := e.s.Since(ctx, e.alice, 0)
	if err != nil || !full.Full || full.Cursor == 0 {
		t.Fatal(full, err)
	}
	if trackIDs(full) != "Glass Surge Tides" || albumIDs(full) != "Glass Tides EP" || artistIDs(full) != "Skeler plenka" {
		t.Fatalf("%s / %s / %s", trackIDs(full), albumIDs(full), artistIDs(full))
	}
	var tides Track
	for _, tr := range full.Tracks {
		if tr.Title == "Tides" {
			tides = tr
		}
	}
	if tides.TrackNumber != 1 || tides.Album != "Tides EP" || tides.Artist != "Skeler" || tides.SizeBytes == 0 ||
		tides.Suffix != "mp3" || !strings.HasPrefix(tides.ID, "tr-") || !strings.HasPrefix(tides.AlbumID, "al-") {
		t.Fatalf("%+v", tides)
	}
	if full.Albums[0].Created == "" {
		t.Fatal("albums carry when they were added")
	}

	// Nothing since: nothing.
	same, _ := e.s.Since(ctx, e.alice, full.Cursor)
	if same.Full || len(same.Tracks)+len(same.Removed)+len(same.Albums) != 0 || same.Cursor != full.Cursor {
		t.Fatalf("%+v", same)
	}

	// A new track on the EP brings the EP whole; bob's new track isn't alice's.
	e.add(t, testutil.MP3{Title: "Undertow", Artist: "Skeler", Album: "Tides EP", Track: "3", Body: "e"}, e.alice)
	e.add(t, testutil.MP3{Title: "Ember", Artist: "Deadcrow", Body: "f"}, e.bob)
	delta, _ := e.s.Since(ctx, e.alice, full.Cursor)
	if delta.Full || trackIDs(delta) != "Surge Tides Undertow" || albumIDs(delta) != "Tides EP" ||
		artistIDs(delta) != "Skeler" || len(delta.Removed) != 0 {
		t.Fatalf("%+v", delta)
	}
	// Bob sees his own.
	if b, _ := e.s.Since(ctx, e.bob, full.Cursor); trackIDs(b) != "Ember" {
		t.Fatalf("%+v", b)
	}

	// A cursor this server never issued (a restored database): start over.
	if again, _ := e.s.Since(ctx, e.alice, delta.Cursor+100); !again.Full || len(again.Tracks) != 4 {
		t.Fatalf("%+v", again)
	}
}

func TestDeletedMergedAndNewPictures(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	glass := e.add(t, testutil.MP3{Title: "Glass", Artist: "plenka", Body: "c"}, e.alice)
	tides := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "a"}, e.alice)
	start, _ := e.s.Since(ctx, e.alice, 0)

	// A file deleted from the Store takes its track out of the library.
	os.Remove(filepath.Join(e.root, glass.RelPath))
	e.g.Verify(ctx)
	c, _ := e.s.Since(ctx, e.alice, start.Cursor)
	if strings.Join(c.Removed, " ") != "tr-"+itoa(glass.TrackID) || len(c.Tracks) != 0 {
		t.Fatalf("%+v", c)
	}

	// A correction that folds one track into another removes the first.
	other := e.add(t, testutil.MP3{Title: "Tides (rip)", Artist: "Skeler", Body: "z"}, e.alice)
	c, _ = e.s.Since(ctx, e.alice, c.Cursor)
	cur := c.Cursor
	if other.TrackID == tides.TrackID {
		t.Fatal("expected two tracks before the correction")
	}
	to := ingest.Identity{Result: resolve.Result{Artist: "Skeler", Title: "Tides", Release: "Tides", Source: "manual", Tier: resolve.TierUser, Confidence: 1}, Reviewed: true}
	if _, merged, err := e.g.Refile(ctx, other.TrackID, to); err != nil || !merged {
		t.Fatal(merged, err)
	}
	c, _ = e.s.Since(ctx, e.alice, cur)
	if !contains(c.Removed, "tr-"+itoa(other.TrackID)) || trackIDs(c) != "Tides" {
		t.Fatalf("%+v", c)
	}
	cur = c.Cursor

	// A picture found for an artist reaches the mirror on its own.
	var skeler int64
	e.db.QueryRow("SELECT id FROM artists WHERE name = 'Skeler'").Scan(&skeler)
	e.db.Exec("UPDATE artists SET art_path = 'art/artist-1.jpg' WHERE id = ?", skeler)
	db.LogChange(ctx, e.db, 0, "artist", itoa(skeler), "upsert")
	c, _ = e.s.Since(ctx, e.alice, cur)
	if len(c.Artists) != 1 || c.Artists[0].CoverArt != "ar-"+itoa(skeler) {
		t.Fatalf("%+v", c)
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func itoa(n int64) string { return strings.TrimPrefix(trackID(n), "tr-") }

func TestARemovalReachesOnlyThatAccount(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	tides := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "a"}, e.alice, e.bob)
	a0, _ := e.s.Since(ctx, e.alice, 0)
	b0, _ := e.s.Since(ctx, e.bob, 0)

	if _, err := e.g.Dismiss(ctx, e.alice, []int64{tides.TrackID}); err != nil {
		t.Fatal(err)
	}
	a, _ := e.s.Since(ctx, e.alice, a0.Cursor)
	if strings.Join(a.Removed, " ") != "tr-"+itoa(tides.TrackID) || len(a.Tracks) != 0 {
		t.Fatalf("%+v", a)
	}
	b, _ := e.s.Since(ctx, e.bob, b0.Cursor)
	if len(b.Removed) != 0 {
		t.Fatalf("bob still has it: %+v", b)
	}
}
