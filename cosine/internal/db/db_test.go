package db

import (
	"path/filepath"
	"testing"
)

func TestRebuildingTracksKeepsEverything(t *testing.T) {
	path := filepath.Join(t.TempDir(), "c.db")
	all := migrations
	t.Cleanup(func() { migrations = all })
	migrations = all[:10]
	d, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{
		"INSERT INTO users(id, name, password_enc, created_at) VALUES(1, 'alice', x'00', 0)",
		"INSERT INTO artists(id, name) VALUES(1, 'Deadcrow')",
		"INSERT INTO releases(id, artist_id, title, created_at) VALUES(1, 1, 'Hollow', 0)",
		"INSERT INTO tracks(id, release_id, artist_id, title, track_no, resolver, tier, confidence, created_at, mbid) VALUES(7, 1, 1, 'Skit', 4, 'tags', 4, 1, 0, 'm')",
		"INSERT INTO pointers(user_id, track_id, added_at) VALUES(1, 7, 0)",
	} {
		if _, err := d.Exec(q); err != nil {
			t.Fatal(q, err)
		}
	}
	d.Close()

	migrations = all
	if d, err = Open(path); err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	var mbid string
	var pointers int
	d.QueryRow("SELECT mbid FROM tracks WHERE id = 7").Scan(&mbid)
	d.QueryRow("SELECT COUNT(*) FROM pointers WHERE track_id = 7").Scan(&pointers)
	if mbid != "m" || pointers != 1 {
		t.Fatal("lost in the rebuild:", mbid, pointers)
	}
	if _, err := d.Exec("INSERT INTO tracks(release_id, artist_id, title, track_no, resolver, tier, confidence, created_at) VALUES(1, 1, 'Skit', 11, 'tags', 4, 1, 0)"); err != nil {
		t.Fatal("a second Skit is still refused:", err)
	}
	if _, err := d.Exec("INSERT INTO pointers(user_id, track_id, added_at) VALUES(1, 999, 0)"); err == nil {
		t.Fatal("foreign keys are off after migrating")
	}
}
