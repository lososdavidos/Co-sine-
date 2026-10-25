package portraits

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/deezer"
)

type env struct {
	s        *Service
	db       *db.DB
	dir      string
	requests atomic.Int32
	down     atomic.Bool
	now      time.Time
}

// pic is a Deezer picture URL; empty hash is Deezer's "no picture".
func pic(hash string) string {
	return "https://cdn-images.dzcdn.net/images/artist/" + hash + "/1000x1000-000000-80-0-0.jpg"
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	e := &env{db: d, dir: dir, now: time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		e.requests.Add(1)
		if e.down.Load() {
			w.Write([]byte(`{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}`))
			return
		}
		switch r.URL.Path + "?" + r.URL.Query().Get("q") {
		case "/artist/5292512?":
			w.Write([]byte(`{"id":5292512,"name":"Halsey","picture_xl":"` + pic("halsey") + `"}`))
		case "/search/artist?Skeler":
			// Two namesakes: the better-known one is someone else.
			w.Write([]byte(`{"data":[
				{"id":1,"name":"Skeler","nb_fan":90000,"picture_xl":"` + pic("other-skeler") + `"},
				{"id":2,"name":"skeler.","nb_fan":40000,"picture_xl":"` + pic("skeler") + `"},
				{"id":3,"name":"Skeler Tribute","picture_xl":"` + pic("tribute") + `"}]}`))
		case "/artist/1/top?", "/artist/1/albums?":
			w.Write([]byte(`{"data":[{"title":"Somebody Else's Song"}]}`))
		case "/artist/2/top?":
			w.Write([]byte(`{"data":[{"title":"Tides (feat. plenka)"}]}`))
		case "/artist/2/albums?":
			w.Write([]byte(`{"data":[]}`))
		case "/search/artist?Deadcrow":
			w.Write([]byte(`{"data":[{"id":4,"name":"Deadcrow","picture_xl":"` + pic("") + `"}]}`))
		default:
			w.Write([]byte(`{"error":{"type":"DataException","message":"no data","code":800}}`))
		}
	}))
	t.Cleanup(srv.Close)
	dz := deezer.New(srv.Client(), "test")
	dz.BaseURL = srv.URL
	dz.Web.Pace = func() time.Duration { return time.Millisecond }
	e.s = &Service{
		DB: d, DataDir: dir, Deezer: dz,
		Links: func(_ context.Context, mbid string) ([]string, error) {
			if mbid == "mb-halsey" {
				return []string{"https://www.wikidata.org/wiki/Q18534249", "https://www.deezer.com/artist/5292512"}, nil
			}
			return nil, nil
		},
		Image: func(_ context.Context, url string) ([]byte, string) {
			if !strings.HasPrefix(url, "https://cdn-images.dzcdn.net/") {
				return nil, ""
			}
			return []byte("jpeg of " + url), "jpg"
		},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now: func() time.Time { return e.now },
	}
	return e
}

// add files a track, with a file in the Store, by an artist.
func (e *env) add(t *testing.T, artist, mbid, title string) int64 {
	t.Helper()
	ctx := context.Background()
	e.db.ExecContext(ctx, "INSERT OR IGNORE INTO artists(name, mbid) VALUES(?, NULLIF(?, ''))", artist, mbid)
	var aid int64
	e.db.QueryRowContext(ctx, "SELECT id FROM artists WHERE name = ?", artist).Scan(&aid)
	res, _ := e.db.ExecContext(ctx, "INSERT INTO releases(artist_id, title, created_at) VALUES(?, ?, 0)", aid, title)
	rid, _ := res.LastInsertId()
	res, _ = e.db.ExecContext(ctx, `INSERT INTO tracks(release_id, artist_id, title, resolver, tier, confidence, created_at)
		VALUES(?, ?, ?, 'test', 1, 1, 0)`, rid, aid, title)
	tid, _ := res.LastInsertId()
	if _, err := e.db.ExecContext(ctx, `INSERT INTO objects(hash, track_id, rel_path, size, suffix, content_type, source, created_at)
		VALUES(?, ?, ?, 1, 'mp3', 'audio/mpeg', 'inbox', 0)`, title, tid, artist+"/"+title+".mp3"); err != nil {
		t.Fatal(err)
	}
	return aid
}

func (e *env) art(t *testing.T, artistID int64) (path string, checked int64) {
	t.Helper()
	var p *string
	var c *int64
	e.db.QueryRow("SELECT art_path, art_checked_at FROM artists WHERE id = ?", artistID).Scan(&p, &c)
	if p != nil {
		path = *p
	}
	if c != nil {
		checked = *c
	}
	return
}

func TestPicturesFoundByLinkAndByASharedSong(t *testing.T) {
	e := setup(t)
	halsey := e.add(t, "Halsey", "mb-halsey", "Bad at Love")
	skeler := e.add(t, "Skeler", "", "Tides")
	deadcrow := e.add(t, "Deadcrow", "", "Hollow")
	e.add(t, "Various Artists", "", "Wave Vol. 1")

	found, err := e.s.Run(context.Background())
	if err != nil || found != 2 {
		t.Fatal(found, err)
	}
	// Halsey through MusicBrainz's link to her Deezer page.
	if p, _ := e.art(t, halsey); p != "art/artist-"+itoa(halsey)+".jpg" {
		t.Fatal(p)
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "art", "artist-"+itoa(halsey)+".jpg")); string(b) != "jpeg of "+pic("halsey") {
		t.Fatal(string(b))
	}
	// Skeler by name: not the better-known namesake, the one with "Tides".
	if p, _ := e.art(t, skeler); p == "" {
		t.Fatal("no picture for Skeler")
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "art", "artist-"+itoa(skeler)+".jpg")); string(b) != "jpeg of "+pic("skeler") {
		t.Fatal(string(b))
	}
	// Deadcrow's Deezer page has no picture: looked at, nothing saved.
	if p, checked := e.art(t, deadcrow); p != "" || checked != e.now.UnixMilli() {
		t.Fatal(p, checked)
	}
	var logged int
	e.db.QueryRow("SELECT COUNT(*) FROM changes WHERE entity = 'artist'").Scan(&logged)
	if logged != 2 {
		t.Fatal("found pictures should reach the mirror:", logged)
	}

	// Nothing is due again until a month has passed.
	before := e.requests.Load()
	if n, err := e.s.Run(context.Background()); n != 0 || err != nil || e.requests.Load() != before {
		t.Fatal("asked again too soon")
	}
	e.now = e.now.Add(Recheck + time.Hour)
	e.s.Run(context.Background())
	if e.requests.Load() == before {
		t.Fatal("Deadcrow is due again after a month")
	}
}

func TestADownDeezerMarksNobody(t *testing.T) {
	e := setup(t)
	skeler := e.add(t, "Skeler", "", "Tides")
	e.down.Store(true)
	if _, err := e.s.Run(context.Background()); err == nil {
		t.Fatal("expected Deezer to be unavailable")
	}
	if _, checked := e.art(t, skeler); checked != 0 {
		t.Fatal("an unanswered look must not count as a look")
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
