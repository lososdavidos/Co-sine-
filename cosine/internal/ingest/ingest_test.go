package ingest

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

type fixture struct {
	g     *Ingester
	db    *db.DB
	root  string
	drop  string
	users []int64
}

func setup(t *testing.T) fixture {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "cosine.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	root := filepath.Join(dir, "store")
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, root)
	var users []int64
	for _, name := range []string{"alice", "bob"} {
		res, err := d.ExecContext(ctx, "INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", name)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		users = append(users, id)
	}
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	g := &Ingester{
		DB:              d,
		Resolver:        resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8},
		DataDir:         dir,
		ReviewThreshold: 0.8,
		Log:             slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	return fixture{g, d, root, drop, users}
}

func count(t *testing.T, d *db.DB, q string, args ...any) int {
	t.Helper()
	var n int
	if err := d.QueryRow(q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestIngestFilesIntoCanonicalTreeAndBroadcasts(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	src := testutil.MP3{Title: "Tides", Artist: "Skeler", Album: "Tides EP", Track: "2", Picture: []byte("jpegdata"), Body: "a"}.
		Write(t, filepath.Join(f.drop, "whatever.mp3"))

	out, err := f.g.Ingest(ctx, Request{Path: src, Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join("Skeler", "Tides EP", "02 Tides.mp3")
	if out.RelPath != want {
		t.Fatalf("rel %q want %q", out.RelPath, want)
	}
	if _, err := os.Stat(filepath.Join(f.root, want)); err != nil {
		t.Fatal("file not in Store:", err)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatal("source should have moved")
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM pointers WHERE track_id = ?", out.TrackID); n != 2 {
		t.Fatalf("inbox drop should reach every account, got %d pointers", n)
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM tracks WHERE id = ? AND reviewed = 0", out.TrackID); n != 1 {
		t.Fatal("tier-4 resolve must land in the review queue")
	}
	var art string
	f.db.QueryRow("SELECT art_path FROM releases").Scan(&art)
	if b, _ := os.ReadFile(filepath.Join(f.g.DataDir, art)); string(b) != "jpegdata" {
		t.Fatalf("embedded artwork not saved: %q", art)
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM ingest_jobs WHERE status = 'done'"); n != 1 {
		t.Fatal("job not recorded")
	}
}

func TestDuplicateCreatesPointersNotObjects(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	m := testutil.MP3{Title: "drift", Artist: "barnacle boi", Body: "same"}
	first, err := f.g.Ingest(ctx, Request{Path: m.Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox", ForUsers: []int64{f.users[0]}})
	if err != nil {
		t.Fatal(err)
	}
	dup := m.Write(t, filepath.Join(f.drop, "b.mp3"))
	second, err := f.g.Ingest(ctx, Request{Path: dup, Source: "upload", ForUsers: []int64{f.users[1]}})
	if err != nil {
		t.Fatal(err)
	}
	if !second.Duplicate || second.TrackID != first.TrackID {
		t.Fatalf("%+v", second)
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM objects"); n != 1 {
		t.Fatalf("objects = %d", n)
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM pointers"); n != 2 {
		t.Fatalf("pointers = %d", n)
	}
	if _, err := os.Stat(dup); !os.IsNotExist(err) {
		t.Fatal("duplicate source should be removed")
	}
}

func TestSecondVersionJoinsTheSameTrack(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	a, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "320"}.Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	b, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "v0"}.Write(t, filepath.Join(f.drop, "b.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	if a.TrackID != b.TrackID {
		t.Fatal("same resolved identity must be one Track")
	}
	if a.RelPath == b.RelPath {
		t.Fatal("versions need distinct files")
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM objects WHERE track_id = ?", a.TrackID); n != 2 {
		t.Fatalf("objects for track = %d", n)
	}
}

func TestDismissedTrackStaysDismissed(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	m := testutil.MP3{Title: "glass", Artist: "plenka", Body: "x"}
	out, err := f.g.Ingest(ctx, Request{Path: m.Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	f.db.Exec("DELETE FROM pointers WHERE user_id = ?", f.users[1])
	f.db.Exec("INSERT INTO dismissed(user_id, track_id, dismissed_at) VALUES(?, ?, 0)", f.users[1], out.TrackID)

	if _, err := f.g.Ingest(ctx, Request{Path: m.Write(t, filepath.Join(f.drop, "again.mp3")), Source: "inbox"}); err != nil {
		t.Fatal(err)
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM pointers WHERE user_id = ?", f.users[1]); n != 0 {
		t.Fatal("a dismissed Track came back")
	}
}

func TestRejectsNonAudio(t *testing.T) {
	f := setup(t)
	p := filepath.Join(f.drop, "cover.jpg")
	os.WriteFile(p, []byte("x"), 0o644)
	if _, err := f.g.Ingest(context.Background(), Request{Path: p, Source: "inbox"}); err != ErrNotAudio {
		t.Fatal(err)
	}
}

func TestRelocateAndVerify(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	out, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "a", Artist: "x", Body: "1"}.Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	newRoot := filepath.Join(t.TempDir(), "nas")
	n, err := f.g.Relocate(ctx, newRoot)
	if err != nil || n != 1 {
		t.Fatal(n, err)
	}
	if got, _ := f.db.Setting(ctx, db.SettingStorePath); got != newRoot {
		t.Fatal(got)
	}
	full := filepath.Join(newRoot, out.RelPath)
	if _, err := os.Stat(full); err != nil {
		t.Fatal(err)
	}

	os.Rename(full, full+".away")
	if m, _ := f.g.Verify(ctx); m != 1 {
		t.Fatal("missing file not flagged")
	}
	os.Rename(full+".away", full)
	if m, _ := f.g.Verify(ctx); m != 0 {
		t.Fatal("returned file not cleared")
	}
	if c := count(t, f.db, "SELECT COUNT(*) FROM objects WHERE missing = 1"); c != 0 {
		t.Fatal(c)
	}
}

func TestOneArtistAcrossSpellings(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	for i, artist := range []string{"Skeler", "skeler.", "SKELER"} {
		name := filepath.Join(f.drop, fmt.Sprintf("%d.mp3", i))
		if _, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: fmt.Sprintf("t%d", i), Artist: artist, Body: name}.Write(t, name), Source: "inbox"}); err != nil {
			t.Fatal(err)
		}
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM artists"); n != 1 {
		t.Fatalf("%d artists for one", n)
	}
	entries, _ := os.ReadDir(f.root)
	if len(entries) != 1 || entries[0].Name() != "Skeler" {
		t.Fatalf("one folder, spelled as first seen: %v", entries)
	}
	// A name that is only symbols matches only itself. (Given as source
	// metadata: the fixture's ID3 tags are Latin-1.)
	for i, artist := range []string{"✝✝✝", "†††"} {
		name := filepath.Join(f.drop, fmt.Sprintf("s%d.mp3", i))
		if _, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Body: name}.Write(t, name), Source: "url",
			SourceInfo: &resolve.SourceInfo{Title: "x", Uploader: artist}}); err != nil {
			t.Fatal(artist, err)
		}
	}
	if n := count(t, f.db, "SELECT COUNT(*) FROM artists"); n != 3 {
		t.Fatalf("%d artists; symbol-only names must not merge", n)
	}
}

func TestForgetMissingKeepsHistoryForAComeback(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	keep, _ := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "keep"}.
		Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	src := testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "gone"}
	gone, _ := f.g.Ingest(ctx, Request{Path: src.Write(t, filepath.Join(f.drop, "b.mp3")), Source: "inbox"})
	f.db.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, 5)", f.users[0], gone.TrackID)
	if _, err := f.db.Exec("UPDATE pointers SET starred_at = 9, pin_hash = ? WHERE track_id = ?", gone.Hash, gone.TrackID); err != nil {
		t.Fatal(err)
	}

	// Nothing missing: nothing to forget.
	if n, err := f.g.Forget(ctx); n != 0 || err != nil {
		t.Fatal(n, err)
	}
	// Deleted outside Cosine.
	os.Remove(filepath.Join(f.root, gone.RelPath))
	if n, _ := f.g.Verify(ctx); n != 1 {
		t.Fatal("not noticed")
	}
	if n, err := f.g.Forget(ctx); n != 1 || err != nil {
		t.Fatal(n, err)
	}
	if count(t, f.db, "SELECT COUNT(*) FROM objects") != 1 ||
		count(t, f.db, "SELECT COUNT(*) FROM tracks WHERE id = ?", gone.TrackID) != 1 ||
		count(t, f.db, "SELECT COUNT(*) FROM plays WHERE track_id = ?", gone.TrackID) != 1 {
		t.Fatal("forgetting the file should keep the Track and its history")
	}
	if _, err := os.Stat(filepath.Join(f.root, "Deadcrow")); !os.IsNotExist(err) {
		t.Fatal("the emptied folders were left behind")
	}
	// Added again: the same Track, with its star.
	again, err := f.g.Ingest(ctx, Request{Path: src.Write(t, filepath.Join(f.drop, "c.mp3")), Source: "inbox"})
	if err != nil || again.Duplicate || again.TrackID != gone.TrackID {
		t.Fatal(again, err)
	}
	if count(t, f.db, "SELECT COUNT(*) FROM pointers WHERE track_id = ? AND starred_at = 9", gone.TrackID) != len(f.users) {
		t.Fatal("stars lost")
	}

	// An unmounted Store looks like every file deleted: never forget then.
	os.Rename(f.root, f.root+".away")
	if _, err := f.g.Forget(ctx); !errors.Is(err, ErrStoreGone) {
		t.Fatal(err)
	}
	os.Rename(f.root+".away", f.root)
	os.MkdirAll(f.root, 0o755)
	for _, rel := range []string{keep.RelPath, again.RelPath} {
		os.Remove(filepath.Join(f.root, rel))
	}
	if _, err := f.g.Forget(ctx); !errors.Is(err, ErrStoreGone) || count(t, f.db, "SELECT COUNT(*) FROM objects") != 2 {
		t.Fatal("every file missing must not be forgotten:", err)
	}
}
