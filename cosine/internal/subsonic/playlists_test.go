package subsonic

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

// titles lists a playlist's songs as "A B C".
func (e *env) titles(t *testing.T, user, pass, id string) string {
	t.Helper()
	p := ok(t, e.get(t, user, pass, "getPlaylist", url.Values{"id": {id}}))["playlist"].(map[string]any)
	entries, _ := p["entry"].([]any)
	var out []string
	for _, en := range entries {
		out = append(out, en.(map[string]any)["title"].(string))
	}
	return strings.Join(out, " ")
}

func failed(t *testing.T, r map[string]any, code int) {
	t.Helper()
	if r["status"] != "failed" || r["error"].(map[string]any)["code"] != float64(code) {
		t.Fatalf("want error %d: %v", code, r)
	}
}

func TestPlaylistsAreMadeAndEditedByAnyClient(t *testing.T) {
	e := setup(t)
	a := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Ascend", Artist: "Skeler", Album: "Tides EP", Picture: []byte("img"), Body: "1"}, e.alice.ID).TrackID)
	b := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Bloom", Artist: "plenka", Body: "2"}, e.alice.ID).TrackID)
	c := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Crush", Artist: "Deadcrow", Body: "3"}, e.alice.ID).TrackID)
	d := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Drift", Artist: "rxrrim", Body: "4"}, e.bob.ID).TrackID)

	created := ok(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"name": {"Night drive"}, "songId": {a, b}}))
	p := created["playlist"].(map[string]any)
	id := p["id"].(string)
	if p["name"] != "Night drive" || p["songCount"] != float64(2) || len(p["entry"].([]any)) != 2 || p["coverArt"] == nil {
		t.Fatalf("created: %v", p)
	}
	ok(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {id}, "songIdToAdd": {c, a}}))
	if got := e.titles(t, "alice", "sesame", id); got != "Ascend Bloom Crush Ascend" {
		t.Fatal("after adding:", got)
	}
	ok(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {id}, "songIndexToRemove": {"0", "3"}, "name": {"Late"}}))
	if got := e.titles(t, "alice", "sesame", id); got != "Bloom Crush" {
		t.Fatal("after removing:", got)
	}
	// Reordering is a replacement, as Subsonic clients do it.
	ok(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"playlistId": {id}, "songId": {c, a, b}}))
	if got := e.titles(t, "alice", "sesame", id); got != "Crush Ascend Bloom" {
		t.Fatal("after reordering:", got)
	}

	// Only songs of one's own library, and only one's own playlists.
	failed(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {id}, "songIdToAdd": {d}}), ErrNotFound)
	failed(t, e.get(t, "bob", "hunter2", "updatePlaylist", url.Values{"playlistId": {id}, "name": {"mine"}}), ErrNotFound)
	failed(t, e.get(t, "bob", "hunter2", "getPlaylist", url.Values{"id": {id}}), ErrNotFound)
	failed(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"name": {"  "}}), ErrMissingParam)

	lists := ok(t, e.get(t, "alice", "sesame", "getPlaylists", nil))["playlists"].(map[string]any)["playlist"].([]any)
	if first := lists[0].(map[string]any); first["name"] != "Late" || first["songCount"] != float64(3) || first["readonly"] != nil {
		t.Fatalf("%v", first)
	}

	ok(t, e.get(t, "alice", "sesame", "deletePlaylist", url.Values{"id": {id}}))
	failed(t, e.get(t, "alice", "sesame", "getPlaylist", url.Values{"id": {id}}), ErrNotFound)
	ok(t, e.get(t, "alice", "sesame", "getSong", url.Values{"id": {a}})) // the songs stay
}

func TestASongGoneFromTheLibraryKeepsItsPlaceInAPlaylist(t *testing.T) {
	e := setup(t)
	var ids []string
	for i, title := range []string{"Ascend", "Bloom", "Crush"} {
		ids = append(ids, "tr-"+itoa(e.add(t, testutil.MP3{Title: title, Artist: "Skeler", Body: string(rune('a' + i))}, e.alice.ID).TrackID))
	}
	a, b, c := ids[0], ids[1], ids[2]
	id := ok(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"name": {"Mix"}, "songId": {a, b, c}}))["playlist"].(map[string]any)["id"].(string)

	bID := strings.TrimPrefix(b, "tr-")
	e.db.Exec("DELETE FROM pointers WHERE user_id = ? AND track_id = ?", e.alice.ID, bID)
	if got := e.titles(t, "alice", "sesame", id); got != "Ascend Crush" {
		t.Fatal(got)
	}
	// Positions are those shown: index 1 is Crush, not the hidden Bloom.
	ok(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {id}, "songIdToAdd": {c}}))
	ok(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {id}, "songIndexToRemove": {"1"}}))
	if got := e.titles(t, "alice", "sesame", id); got != "Ascend Crush" {
		t.Fatal(got)
	}
	// Reordering carries Bloom along after Ascend, which it followed.
	ok(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"playlistId": {id}, "songId": {c, a}}))
	e.db.Exec("INSERT INTO pointers(user_id, track_id, added_at) VALUES(?, ?, 0)", e.alice.ID, bID)
	if got := e.titles(t, "alice", "sesame", id); got != "Crush Ascend Bloom" {
		t.Fatal("Bloom back in the library:", got)
	}
}

func TestSmartPlaylistsAreListedReadOnly(t *testing.T) {
	e := setup(t)
	a := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Ascend", Artist: "Skeler", Body: "1"}, e.alice.ID).TrackID)
	e.add(t, testutil.MP3{Title: "Bloom", Artist: "plenka", Body: "2"}, e.alice.ID)
	ok(t, e.get(t, "alice", "sesame", "scrobble", url.Values{"id": {a}}))

	lists := ok(t, e.get(t, "alice", "sesame", "getPlaylists", nil))["playlists"].(map[string]any)["playlist"].([]any)
	byName := map[string]map[string]any{}
	for _, l := range lists {
		m := l.(map[string]any)
		byName[m["name"].(string)] = m
	}
	added, played := byName["Recently added"], byName["Recently played"]
	if len(lists) != 6 || added == nil || played == nil || byName["Never played"] == nil {
		t.Fatalf("the default smart playlists: %v", lists)
	}
	if added["readonly"] != true || added["songCount"] != float64(2) || !strings.HasPrefix(added["id"].(string), "sp-") {
		t.Fatalf("%v", added)
	}
	if got := e.titles(t, "alice", "sesame", played["id"].(string)); got != "Ascend" {
		t.Fatal("recently played:", got)
	}
	if got := e.titles(t, "alice", "sesame", byName["Never played"]["id"].(string)); got != "Bloom" {
		t.Fatal("never played:", got)
	}

	sp := played["id"].(string)
	failed(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {sp}, "songIdToAdd": {a}}), ErrNotAuthorized)
	failed(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"playlistId": {sp}, "songId": {a}}), ErrNotAuthorized)
	ok(t, e.get(t, "alice", "sesame", "updatePlaylist", url.Values{"playlistId": {sp}, "name": {"Lately"}}))
	ok(t, e.get(t, "alice", "sesame", "deletePlaylist", url.Values{"id": {byName["Forgotten"]["id"].(string)}}))
	lists = ok(t, e.get(t, "alice", "sesame", "getPlaylists", nil))["playlists"].(map[string]any)["playlist"].([]any)
	var names []string
	for _, l := range lists {
		names = append(names, l.(map[string]any)["name"].(string))
	}
	if got := strings.Join(names, ","); got != "Recently added,Most played this year,Lately,Never played,This time last year" {
		t.Fatal("renamed and deleted, not brought back:", got)
	}
	// Bob's are his own.
	failed(t, e.get(t, "bob", "hunter2", "getPlaylist", url.Values{"id": {sp}}), ErrNotFound)
}

func TestASharedPlaylistIsEveryMembersOverSubsonic(t *testing.T) {
	e := setup(t)
	a := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Ascend", Artist: "Skeler", Body: "1"}, e.alice.ID).TrackID)
	b := "tr-" + itoa(e.add(t, testutil.MP3{Title: "Bloom", Artist: "plenka", Body: "2"}, e.bob.ID).TrackID)
	id := ok(t, e.get(t, "alice", "sesame", "createPlaylist", url.Values{"name": {"Ours"}, "songId": {a}}))["playlist"].(map[string]any)["id"].(string)
	lists := &playlists.Service{DB: e.db}
	pid, _ := strconv.ParseInt(strings.TrimPrefix(id, "pl-"), 10, 64)
	lists.Share(context.Background(), e.alice.ID, pid, "bob")
	failed(t, e.get(t, "bob", "hunter2", "getPlaylist", url.Values{"id": {id}}), ErrNotFound) // not before accepting
	lists.Accept(context.Background(), e.bob.ID, pid)

	// Bob sees it as alice's, adds his song, and alice gets it.
	got := ok(t, e.get(t, "bob", "hunter2", "getPlaylist", url.Values{"id": {id}}))["playlist"].(map[string]any)
	if got["owner"] != "alice" || got["songCount"] != float64(1) {
		t.Fatal(got)
	}
	ok(t, e.get(t, "bob", "hunter2", "updatePlaylist", url.Values{"playlistId": {id}, "songIdToAdd": {b}}))
	if got := e.titles(t, "alice", "sesame", id); got != "Ascend Bloom" {
		t.Fatal(got)
	}
	// Deleting it, for bob, is leaving it.
	ok(t, e.get(t, "bob", "hunter2", "deletePlaylist", url.Values{"id": {id}}))
	failed(t, e.get(t, "bob", "hunter2", "getPlaylist", url.Values{"id": {id}}), ErrNotFound)
	if got := e.titles(t, "alice", "sesame", id); got != "Ascend Bloom" {
		t.Fatal(got)
	}
}
