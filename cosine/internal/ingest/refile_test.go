package ingest

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestRefileMovesFilesAndKeepsEveryonesState(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	out, err := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "tides", Artist: "skeler official", Picture: []byte("img"), Body: "1"}.
		Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	f.db.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, 5)", f.users[0], out.TrackID)
	f.db.Exec("UPDATE pointers SET starred_at = 9 WHERE user_id = ?", f.users[1])

	to := Identity{Result: resolve.Result{Artist: "Skeler", Release: "Tides", Title: "Tides", TrackNo: 1, Year: 2019,
		Source: "musicbrainz", Tier: 1, Confidence: 1, IDs: resolve.IDs{MBRecording: "rec", MBRelease: "rel"}}, Reviewed: true}
	id, merged, err := f.g.Refile(ctx, out.TrackID, to)
	if err != nil || merged || id != out.TrackID {
		t.Fatal(id, merged, err)
	}
	want := filepath.Join(f.root, "Skeler", "Tides", "01 Tides.mp3")
	if _, err := os.Stat(want); err != nil {
		t.Fatal("file not moved:", err)
	}
	if _, err := os.Stat(filepath.Join(f.root, "skeler official")); !os.IsNotExist(err) {
		t.Fatal("old folder left behind")
	}
	got, _ := f.g.Identity(ctx, id)
	if got.Artist != "Skeler" || got.IDs.MBRelease != "rel" || !got.Reviewed || got.Year != 2019 {
		t.Fatalf("%+v", got)
	}
	if count(t, f.db, "SELECT COUNT(*) FROM plays WHERE track_id = ?", id) != 1 ||
		count(t, f.db, "SELECT COUNT(*) FROM pointers WHERE track_id = ? AND starred_at = 9", id) != 1 {
		t.Fatal("per-account state lost")
	}
	if count(t, f.db, "SELECT COUNT(*) FROM artists WHERE name = 'skeler official'") != 0 ||
		count(t, f.db, "SELECT COUNT(*) FROM releases") != 1 {
		t.Fatal("orphans not cleaned up")
	}
	var art string
	f.db.QueryRow("SELECT art_path FROM releases").Scan(&art)
	if b, _ := os.ReadFile(filepath.Join(f.g.DataDir, art)); string(b) != "img" {
		t.Fatal("cover not carried to the corrected release:", art)
	}
}

func TestRefileOntoAnExistingTrackMergesThem(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	good, _ := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "flac"}.
		Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox", ForUsers: []int64{f.users[0]}})
	rip, _ := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "hollow sc rip", Artist: "deadcrow", Body: "rip"}.
		Write(t, filepath.Join(f.drop, "b.mp3")), Source: "inbox", ForUsers: []int64{f.users[1]}})
	f.db.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, 1)", f.users[1], rip.TrackID)
	f.db.Exec("INSERT INTO playlists(id, owner_id, name, created_at, updated_at) VALUES(1, ?, 'p', 0, 0)", f.users[1])
	f.db.Exec("INSERT INTO playlist_entries(playlist_id, position, track_id) VALUES(1, 0, ?)", rip.TrackID)

	target, _ := f.g.Identity(ctx, good.TrackID)
	id, merged, err := f.g.Refile(ctx, rip.TrackID, Identity{Result: target.Result, Reviewed: true})
	if err != nil || !merged || id != good.TrackID {
		t.Fatal(id, merged, err)
	}
	if count(t, f.db, "SELECT COUNT(*) FROM tracks") != 1 ||
		count(t, f.db, "SELECT COUNT(*) FROM objects WHERE track_id = ?", id) != 2 {
		t.Fatal("the rip should now be a second version of the same Track")
	}
	if count(t, f.db, "SELECT COUNT(*) FROM pointers WHERE track_id = ?", id) != 2 ||
		count(t, f.db, "SELECT COUNT(*) FROM plays WHERE track_id = ?", id) != 1 ||
		count(t, f.db, "SELECT COUNT(*) FROM playlist_entries WHERE track_id = ?", id) != 1 {
		t.Fatal("state did not follow the merge")
	}
	var a, b string
	f.db.QueryRow("SELECT MIN(rel_path), MAX(rel_path) FROM objects").Scan(&a, &b)
	if a == b || filepath.Dir(a) != filepath.Dir(b) {
		t.Fatal("versions should sit side by side:", a, b)
	}
}

func TestRefileRefusesEmptyIdentity(t *testing.T) {
	f := setup(t)
	out, _ := f.g.Ingest(context.Background(), Request{Path: testutil.MP3{Title: "a", Artist: "b", Body: "x"}.
		Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	if _, _, err := f.g.Refile(context.Background(), out.TrackID, Identity{}); err == nil {
		t.Fatal("expected refusal")
	}
	if _, _, err := f.g.Refile(context.Background(), 999, Identity{Result: resolve.Result{Artist: "a", Title: "b"}}); err != ErrNoTrack {
		t.Fatal(err)
	}
}

func TestCoverArchiveOutranksEmbeddedArt(t *testing.T) {
	f := setup(t)
	f.g.Covers = func(_ context.Context, rel, rg string) ([]byte, string) {
		if rel == "rel" {
			return []byte("caa"), "jpg"
		}
		return nil, ""
	}
	ctx := context.Background()
	out, _ := f.g.Ingest(ctx, Request{Path: testutil.MP3{Title: "Tides", Artist: "Skeler", Picture: []byte("embedded"), Body: "1"}.
		Write(t, filepath.Join(f.drop, "a.mp3")), Source: "inbox"})
	var src string
	f.db.QueryRow("SELECT art_source FROM releases").Scan(&src)
	if src != ArtEmbedded {
		t.Fatal(src)
	}
	cur, _ := f.g.Identity(ctx, out.TrackID)
	cur.IDs.MBRelease = "rel"
	f.g.Refile(ctx, out.TrackID, cur)
	var art string
	f.db.QueryRow("SELECT art_source, art_path FROM releases").Scan(&src, &art)
	if b, _ := os.ReadFile(filepath.Join(f.g.DataDir, art)); src != ArtCAA || string(b) != "caa" {
		t.Fatal(src, string(b))
	}
}
