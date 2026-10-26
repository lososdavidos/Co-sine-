package bandcamp

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// The shapes Bandcamp's search and track pages have: a track on an EP, and
// a long mix of the same name by someone else.
func searchBody(base string) string {
	return `{"auto":{"results":[
  {"type":"b","name":"Skeler","band_name":"Skeler","item_url_path":"` + base + `"},
  {"type":"t","id":1,"name":"Tides","band_name":"Skeler","album_name":"Skeler - Tides EP","item_url_path":"` + base + `/track/tides","img":"https://f4.bcbits.com/img/a0123456789_3.jpg"},
  {"type":"t","id":2,"name":"Tides (1 Hour Loop)","band_name":"loop archive","album_name":"","item_url_path":"` + base + `/track/tides-loop","img":"https://f4.bcbits.com/img/a0999_3.jpg"},
  {"type":"a","id":3,"name":"Skeler - Tides EP","band_name":"Skeler","item_url_path":"` + base + `/album/tides-ep","img":"https://f4.bcbits.com/img/a0123456789_3.jpg"}
 ]}}`
}

const tidesPage = `<html><head>
<script type="application/ld+json">
{"@type":"MusicRecording","name":"Tides","duration":"P00H03M21S","datePublished":"03 Feb 2019 00:00:00 GMT",
 "image":"https://f4.bcbits.com/img/a0123456789_10.jpg",
 "byArtist":{"@type":"MusicGroup","name":"Skeler"},
 "inAlbum":{"@type":"MusicAlbum","name":"Tides EP","byArtist":{"@type":"MusicGroup","name":"Skeler"}}}
</script></head><body>
<script data-tralbum="{&quot;artist&quot;:&quot;Skeler&quot;,&quot;trackinfo&quot;:[{&quot;duration&quot;:201.4,&quot;track_num&quot;:2}]}"></script>
</body></html>`

type fake struct {
	srv      *httptest.Server
	searches atomic.Int32
	pages    atomic.Int32
	text     atomic.Value
	blocked  bool
}

func newClient(t *testing.T, blocked bool) (*Client, *fake) {
	f := &fake{blocked: blocked}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if f.blocked {
			w.Write([]byte("<!DOCTYPE html><title>Client Challenge</title>"))
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/search":
			f.searches.Add(1)
			var q map[string]any
			json.NewDecoder(r.Body).Decode(&q)
			f.text.Store(q["search_text"])
			w.Write([]byte(searchBody(f.srv.URL)))
		case r.URL.Path == "/track/tides":
			f.pages.Add(1)
			w.Write([]byte(tidesPage))
		default:
			f.pages.Add(1)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(f.srv.Close)
	c := New(f.srv.Client(), nil, "Cosine/test")
	c.SearchURL = f.srv.URL + "/search"
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	c.Live.Pace = c.Web.Pace
	return c, f
}

func TestAlbumSearch(t *testing.T) {
	c, f := newClient(t, false)
	for i := 0; i < 2; i++ {
		albums, err := c.Albums(context.Background(), "skeler tides", 5)
		if err != nil || len(albums) != 1 {
			t.Fatal(albums, err)
		}
		want := Album{Title: "Tides EP", Artist: "Skeler", URL: f.srv.URL + "/album/tides-ep", Cover: "https://f4.bcbits.com/img/a0123456789_10.jpg"}
		if albums[0] != want {
			t.Fatalf("%+v", albums[0])
		}
	}
	if f.searches.Load() != 2 {
		t.Fatal("Add's searches are asked afresh each time:", f.searches.Load())
	}
}

func TestTrackOnAnEP(t *testing.T) {
	c, f := newClient(t, false)
	r, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Skeler ft. plenka", Title: "Tides (feat. plenka)", DurationSec: 201})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	want := resolve.Result{Artist: "Skeler", Release: "Tides EP", Title: "Tides", TrackNo: 2, Year: 2019,
		Source: "bandcamp", Tier: resolve.TierBandcamp, Confidence: r.Confidence,
		IDs: resolve.IDs{Bandcamp: f.srv.URL + "/track/tides"}, Cover: "https://f4.bcbits.com/img/a0123456789_10.jpg"}
	if r != want || r.Confidence < 0.9 {
		t.Fatalf("got  %+v\nwant %+v", r, want)
	}
	if f.text.Load() != "Skeler Tides" {
		t.Fatalf("searched for %q", f.text.Load())
	}
}

func TestWrongLengthIsNeverConfident(t *testing.T) {
	c, _ := newClient(t, false)
	r, _, _ := c.Match(context.Background(), resolve.Hint{Artist: "Skeler", Title: "Tides", DurationSec: 3600})
	if r.Confidence > 0.6 {
		t.Fatalf("an hour-long file matched a 3-minute track at %v", r.Confidence)
	}
}

func TestBotCheckIsUnavailable(t *testing.T) {
	c, _ := newClient(t, true)
	_, ok, err := c.Match(context.Background(), resolve.Hint{Artist: "Skeler", Title: "Tides"})
	if ok || !errors.Is(err, ErrUnavailable) || !strings.Contains(err.Error(), "Bandcamp is unavailable") {
		t.Fatal(ok, err)
	}
}

func TestParsePage(t *testing.T) {
	p := parsePage(tidesPage)
	if p.DurationSec != 201 || p.TrackNo != 2 || p.Year != 2019 || p.Album != "Tides EP" || p.Artist != "Skeler" {
		t.Fatalf("%+v", p)
	}
	// Without JSON-LD, data-tralbum alone is enough for the length.
	p = parsePage(`<script data-tralbum="{&quot;trackinfo&quot;:[{&quot;duration&quot;:95.6,&quot;track_num&quot;:null}],&quot;album_release_date&quot;:&quot;01 Jan 2021 00:00:00 GMT&quot;}"></script>`)
	if p.DurationSec != 96 || p.TrackNo != 0 || p.Year != 2021 {
		t.Fatalf("%+v", p)
	}
	for in, want := range map[string]int{"P00H03M21S": 201, "PT3M21S": 201, "PT45.5S": 46, "": 0, "nonsense": 0} {
		if got := isoSeconds(in); got != want {
			t.Errorf("isoSeconds(%q) = %d", in, got)
		}
	}
}
