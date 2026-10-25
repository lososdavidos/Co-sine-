package discogs

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// Trimmed from real Discogs answers: a digital single by an artist
// MusicBrainz doesn't carry, as Discogs returns it.
const plumeSearch = `{"pagination":{"items":1},"results":[
 {"id":15537159,"type":"release","master_id":0,"title":"skeler. - Plume","year":"2019","format":["File","MP3","Single"]}]}`

const plumeRelease = `{"id":15537159,"title":"Plume","year":2019,"released":"2019-06-12",
 "artists":[{"name":"skeler.","anv":"","join":"","id":7979237}],
 "formats":[{"name":"File","descriptions":["MP3","Single"]}],
 "tracklist":[{"position":"1","type_":"track","title":"Plume","duration":"3:14"}],
 "images":[{"type":"secondary","uri":"https://i.discogs.com/plume.jpeg","width":600,"height":600}]}`

// A compilation master: Various on the cover, each track its own credit.
const compSearch = `{"results":[
 {"id":901,"type":"release","master_id":77,"title":"Various - Wave Vol. 1"},
 {"id":902,"type":"release","master_id":77,"title":"Various - Wave Vol. 1"},
 {"id":77,"type":"master","title":"Various - Wave Vol. 1"}]}`

const compMaster = `{"id":77,"title":"Wave Vol. 1","year":2018,
 "artists":[{"name":"Various","anv":"","join":""}],
 "tracklist":[
  {"position":"","type_":"heading","title":"Side A"},
  {"position":"A1","type_":"track","title":"Hollow","duration":"4:01","artists":[{"name":"Deadcrow","join":""}]},
  {"position":"A2","type_":"track","title":"Tides","duration":"3:21","artists":[{"name":"Skeler (2)","join":"Feat.","anv":""},{"name":"plenka","join":""}]}],
 "images":[{"type":"secondary","uri":"https://i.discogs.com/back.jpeg"},{"type":"primary","uri":"https://i.discogs.com/front.jpeg"}]}`

type fake struct {
	mu      sync.Mutex
	paths   []string
	queries []string
	auth    []string
}

func newClient(t *testing.T, token string) (*Client, *fake) {
	f := &fake{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.paths = append(f.paths, r.URL.Path)
		f.queries = append(f.queries, r.URL.RawQuery)
		f.auth = append(f.auth, r.Header.Get("Authorization"))
		f.mu.Unlock()
		switch {
		case r.URL.Path == "/database/search" && strings.EqualFold(r.URL.Query().Get("track"), "plume"):
			w.Write([]byte(plumeSearch))
		case r.URL.Path == "/database/search" && r.URL.Query().Get("track") == "Tides":
			w.Write([]byte(compSearch))
		case r.URL.Path == "/database/search":
			w.Write([]byte(`{"results":[]}`))
		case r.URL.Path == "/releases/15537159":
			w.Write([]byte(plumeRelease))
		case r.URL.Path == "/masters/77":
			w.Write([]byte(compMaster))
		default:
			w.WriteHeader(http.StatusNotFound)
			w.Write([]byte(`{"message":"Release not found."}`))
		}
	}))
	t.Cleanup(srv.Close)
	c := New(srv.Client(), nil, "Cosine/test", func() string { return token })
	c.BaseURL = srv.URL
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	return c, f
}

func TestDigitalSingleMatches(t *testing.T) {
	c, f := newClient(t, "")
	r, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Skeler", Title: "Plume", Release: "Plume", DurationSec: 195})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	want := resolve.Result{Artist: "skeler.", Release: "Plume", Title: "Plume", TrackNo: 1, Year: 2019,
		Source: "discogs", Tier: resolve.TierDiscogs, Confidence: 1,
		IDs: resolve.IDs{Discogs: "release/15537159"}, Cover: "https://i.discogs.com/plume.jpeg"}
	if r != want {
		t.Fatalf("got  %+v\nwant %+v", r, want)
	}
	if f.auth[0] != "" {
		t.Fatal("sent credentials without a token")
	}
}

func TestCompilationKeepsEachTracksArtist(t *testing.T) {
	c, f := newClient(t, "tok")
	r, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Skeler ft. plenka", Title: "Tides (feat. plenka)", DurationSec: 201})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	if r.Artist != "Various Artists" || r.TrackArtist != "Skeler Feat. plenka" || r.Release != "Wave Vol. 1" ||
		r.TrackNo != 2 || r.IDs.Discogs != "master/77" || r.Cover != "https://i.discogs.com/front.jpeg" {
		t.Fatalf("%+v", r)
	}
	if r.Confidence < 0.8 {
		t.Fatalf("confidence %v for a right match", r.Confidence)
	}
	// Searched by the lead artist and the bare title; the master was
	// looked up once, not once per pressing.
	if q, _ := url.ParseQuery(f.queries[0]); q.Get("artist") != "Skeler" || q.Get("track") != "Tides" {
		t.Fatal(f.queries[0])
	}
	if strings.Join(f.paths, " ") != "/database/search /masters/77" || f.auth[1] != "Discogs token=tok" {
		t.Fatal(f.paths, f.auth)
	}
}

func TestWrongLengthIsNeverConfident(t *testing.T) {
	c, _ := newClient(t, "")
	r, ok, _ := c.Match(context.Background(), resolve.Hint{Artist: "Skeler", Title: "Plume", DurationSec: 3600})
	if !ok || r.Confidence > 0.6 {
		t.Fatalf("an hour-long mix matched a 3-minute single at %v", r.Confidence)
	}
	r, ok, _ = c.Match(context.Background(), resolve.Hint{Artist: "plenka", Title: "Plume"})
	if ok && r.Confidence >= 0.8 {
		t.Fatalf("the wrong artist matched at %v", r.Confidence)
	}
}

func TestNothingFoundAndUnusableHints(t *testing.T) {
	c, f := newClient(t, "")
	if _, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "nobody", Title: "nothing"}); ok || err != nil {
		t.Fatal(ok, err)
	}
	if _, ok, _ := c.Match(context.Background(), resolve.Hint{Artist: "Unknown artist", Title: "untitled"}); ok || len(f.paths) != 1 {
		t.Fatal("asked Discogs about an unknown artist")
	}
}

func TestUnavailable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	c := New(srv.Client(), nil, "t", nil)
	c.BaseURL = srv.URL
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	_, _, err := c.Match(context.Background(), resolve.Hint{Artist: "Skeler", Title: "Plume"})
	if !errors.Is(err, ErrUnavailable) || !errors.Is(err, resolve.ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestPositions(t *testing.T) {
	cases := []struct {
		p           string
		running     int
		disc, track int
	}{{"3", 1, 0, 3}, {"2-5", 9, 2, 5}, {"1-4", 4, 0, 4}, {"A1", 1, 0, 1}, {"B", 2, 0, 2}}
	for _, c := range cases {
		if d, tr := position(c.p, c.running); d != c.disc || tr != c.track {
			t.Errorf("position(%q) = %d, %d", c.p, d, tr)
		}
	}
	if seconds("3:14") != 194 || seconds("1:02:03") != 3723 || seconds("") != 0 {
		t.Fatal("seconds")
	}
}

func TestAnAlbumOutranksTheSingle(t *testing.T) {
	tracks := func(n int) string {
		var b strings.Builder
		for i := 1; i <= n; i++ {
			title := "Filler"
			if i == 1 {
				title = "Around the World"
			}
			if i > 1 {
				b.WriteString(",")
			}
			b.WriteString(`{"position":"` + strconv.Itoa(i) + `","type_":"track","title":"` + title + `","duration":"3:58"}`)
		}
		return b.String()
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/database/search":
			w.Write([]byte(`{"results":[{"id":1,"type":"master"},{"id":2,"type":"master"}]}`))
		case "/masters/1": // the single, listed first
			w.Write([]byte(`{"title":"Around the World","year":1999,"artists":[{"name":"Red Hot Chili Peppers"}],"tracklist":[` + tracks(2) + `]}`))
		case "/masters/2":
			w.Write([]byte(`{"title":"Californication","year":1999,"artists":[{"name":"Red Hot Chili Peppers"}],"tracklist":[` + tracks(15) + `]}`))
		}
	}))
	defer srv.Close()
	c := New(srv.Client(), nil, "t", nil)
	c.BaseURL = srv.URL
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	r, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Red Hot Chili Peppers", Title: "Around the World", Release: "Around the World", DurationSec: 238})
	if err != nil || !ok || r.Release != "Californication" || r.IDs.Discogs != "master/2" {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestANamedCompilationDoesNotBeatTheArtistsAlbum(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/database/search":
			w.Write([]byte(`{"results":[{"id":1,"type":"master"},{"id":2,"type":"master"}]}`))
		case "/masters/1": // the compilation YouTube credited, listed first
			w.Write([]byte(`{"title":"Hot Party Winter 2018","year":2017,"artists":[{"name":"Various"}],
			 "tracklist":[{"position":"14","title":"Bad At Love","duration":"3:01","artists":[{"name":"Halsey"}]}]}`))
		case "/masters/2":
			w.Write([]byte(`{"title":"Hopeless Fountain Kingdom","year":2017,"artists":[{"name":"Halsey"}],
			 "tracklist":[{"position":"10","title":"Bad At Love","duration":"3:01"}]}`))
		}
	}))
	defer srv.Close()
	c := New(srv.Client(), nil, "t", nil)
	c.BaseURL = srv.URL
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	r, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Halsey", Title: "Bad At Love", Release: "Hot Party Winter 2018", DurationSec: 181})
	if err != nil || !ok || r.Release != "Hopeless Fountain Kingdom" || r.Artist != "Halsey" {
		t.Fatalf("%+v %v", r, err)
	}
}
