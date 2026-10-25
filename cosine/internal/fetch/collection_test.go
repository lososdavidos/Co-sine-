package fetch

import (
	"context"
	"strings"
	"testing"
	"time"
)

// release is the release a source link's song was filed under.
func (e *env) release(t *testing.T, link string) (title string, trackNo int) {
	t.Helper()
	err := e.db.QueryRow(`SELECT r.title, COALESCE(t.track_no, 0) FROM objects o JOIN tracks t ON t.id = o.track_id
		JOIN releases r ON r.id = t.release_id WHERE o.source_url = ?`, link).Scan(&title, &trackNo)
	if err != nil {
		t.Fatal(link, err)
	}
	return title, trackNo
}

// playlist lists an account's playlist by name, as "titles in order".
func (e *env) playlist(t *testing.T, user int64, name string) string {
	t.Helper()
	rows, err := e.db.Query(`SELECT t.title FROM playlists p JOIN playlist_entries e ON e.playlist_id = p.id
		JOIN tracks t ON t.id = e.track_id WHERE p.owner_id = ? AND p.name = ? ORDER BY e.position`, user, name)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		rows.Scan(&s)
		out = append(out, s)
	}
	return strings.Join(out, ", ")
}

func (e *env) settle(t *testing.T, user int64) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		var active int
		e.db.QueryRow("SELECT COUNT(*) FROM ingest_jobs WHERE user_id = ? AND status IN ('queued', 'running')", user).Scan(&active)
		if active == 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("jobs never finished")
}

func TestAPlaylistIsNeverFiledAsAnAlbum(t *testing.T) {
	set := "https://soundcloud.com/someone/sets/night-drive"
	a, b, c := "https://soundcloud.com/x/ascend", "https://soundcloud.com/y/bloom", "https://soundcloud.com/z/crush"
	e := setup(t, map[string]any{
		set: map[string]any{"_type": "playlist", "id": "9", "title": "Night drive", "webpage_url": set,
			"extractor_key": "SoundcloudSet", "album_type": "playlist", "uploader": "someone",
			"entries": []map[string]any{{"_type": "url", "url": a}, {"_type": "url", "url": b}, {"_type": "url", "url": c}}},
		// What other tools do: the playlist's name taken for each song's album.
		a: track(a, "1", "Skeler - Ascend", "x", "ascend", map[string]any{"album": "Night drive"}),
		b: track(b, "2", "plenka - Bloom", "y", "bloom", nil),
		c: track(c, "3", "Deadcrow - Crush", "z", "crush", nil),
	})
	ctx := context.Background()
	// Crush is already in alice's library.
	first, _ := e.f.Submit(ctx, e.alice, []string{c}, false, nil)
	e.wait(t, first[0].ID, StatusDone)

	l, err := e.f.Lookup(ctx, e.alice, set)
	if err != nil {
		t.Fatal(err)
	}
	if l.Kind != KindCollection || l.Collection != "playlist" || l.Title != "Night drive" || l.URL != set || !l.Items[2].InLibrary {
		t.Fatalf("%+v", l)
	}
	// Add ticks what isn't in the library: Ascend and Bloom.
	if _, err := e.f.Submit(ctx, e.alice, []string{a, b}, false, &From{URL: l.URL, Playlist: true}); err != nil {
		t.Fatal(err)
	}
	e.settle(t, e.alice)

	for _, link := range []string{a, b, c} {
		if rel, _ := e.release(t, link); rel == "Night drive" {
			t.Errorf("%s filed under the playlist's name", link)
		}
	}
	if got := e.playlist(t, e.alice, "Night drive"); got != "Ascend, Bloom, Crush" {
		t.Fatal("the playlist, in the source's order:", got)
	}

	// Adding it again fills the same playlist, not a second one.
	if _, err := e.f.Submit(ctx, e.alice, []string{set}, false, nil); err != nil {
		t.Fatal(err)
	}
	e.settle(t, e.alice)
	var n int
	e.db.QueryRow("SELECT COUNT(*) FROM playlists WHERE owner_id = ?", e.alice).Scan(&n)
	if got := e.playlist(t, e.alice, "Night drive"); n != 1 || got != "Ascend, Bloom, Crush" {
		t.Fatal(n, got)
	}

	// Every song already here: the playlist alone, with no links to fetch.
	ok, err := e.f.Submit(ctx, e.alice, nil, false, &From{URL: set, Playlist: true})
	if err != nil || len(ok) != 0 {
		t.Fatal(ok, err)
	}
	e.db.Exec("DELETE FROM playlists WHERE owner_id = ?", e.alice)
	if _, err := e.f.Submit(ctx, e.alice, nil, false, &From{URL: set, Playlist: true}); err != nil {
		t.Fatal(err)
	}
	if got := e.playlist(t, e.alice, "Night drive"); got != "Ascend, Bloom, Crush" {
		t.Fatal("made again from the songs already here:", got)
	}
	if _, err := e.f.Submit(ctx, e.alice, nil, false, nil); err == nil {
		t.Fatal("no links and no playlist is nothing to do")
	}

	// Without keeping the playlist: the songs, no playlist.
	if _, err := e.f.Submit(ctx, e.bob, []string{a}, false, &From{URL: set}); err != nil {
		t.Fatal(err)
	}
	e.settle(t, e.bob)
	e.db.QueryRow("SELECT COUNT(*) FROM playlists WHERE owner_id = ?", e.bob).Scan(&n)
	if n != 0 {
		t.Fatal("bob didn't want the playlist")
	}
}

func TestAnAlbumLendsItsNameAndOrder(t *testing.T) {
	set := "https://soundcloud.com/skeler/sets/tides-ep"
	one, two := "https://soundcloud.com/skeler/tides", "https://soundcloud.com/skeler/surge"
	e := setup(t, map[string]any{
		set: map[string]any{"_type": "playlist", "id": "7", "title": "Tides EP", "webpage_url": set,
			"extractor_key": "SoundcloudSet", "album_type": "ep", "album_artists": []string{"Skeler"},
			"entries": []map[string]any{{"_type": "url", "url": one}, {"_type": "url", "url": two}}},
		one: track(one, "1", "Tides", "Skeler", "tides", nil),
		two: track(two, "2", "Surge", "Skeler", "surge", nil),
	})
	ctx := context.Background()
	l, _ := e.f.Lookup(ctx, e.alice, set)
	if l.Collection != "album" || l.Artist != "Skeler" || l.Title != "Tides EP" {
		t.Fatalf("%+v", l)
	}
	// Queue everything: the album's own link.
	jobs, _ := e.f.Submit(ctx, e.alice, []string{set}, false, nil)
	e.wait(t, jobs[0].ID, StatusExpanded)
	e.settle(t, e.alice)
	for i, link := range []string{one, two} {
		if rel, no := e.release(t, link); rel != "Tides EP" || no != i+1 {
			t.Errorf("%s: %q track %d", link, rel, no)
		}
	}
	var n int
	e.db.QueryRow("SELECT COUNT(*) FROM playlists").Scan(&n)
	if n != 0 {
		t.Fatal("an album is not a playlist")
	}
}
