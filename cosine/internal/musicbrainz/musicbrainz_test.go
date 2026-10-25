package musicbrainz

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// A recording that appears on its own single and on a various-artists
// compilation, the shape MusicBrainz returns for much of this library.
const tidesSearch = `{"count":2,"recordings":[
 {"id":"rec-tides","score":100,"title":"Tides","length":201000,
  "artist-credit":[{"name":"Skeler","joinphrase":"","artist":{"id":"art-skeler","name":"Skeler"}}],
  "releases":[
   {"id":"rel-comp","title":"Wave Vol. 1","status":"Official","date":"2018-05-01",
    "artist-credit":[{"name":"Various Artists","artist":{"id":"art-va","name":"Various Artists"}}],
    "release-group":{"id":"rg-comp","primary-type":"Album","secondary-types":["Compilation"]},
    "media":[{"position":1,"track":[{"number":"7","position":7}]}]},
   {"id":"rel-single","title":"Tides","status":"Official","date":"2019-02-03",
    "release-group":{"id":"rg-single","primary-type":"Single"},
    "media":[{"position":1,"track":[{"number":"1","position":1}]}]}
  ]},
 {"id":"rec-other","score":62,"title":"Tides (Remix)","length":260000,
  "artist-credit":[{"name":"Someone Else","joinphrase":"","artist":{"id":"art-x","name":"Someone Else"}}],
  "releases":[{"id":"rel-x","title":"Tides (Remix)","status":"Official","release-group":{"id":"rg-x","primary-type":"Single"}}]}
]}`

type fakeMB struct {
	srv       *httptest.Server
	calls     atomic.Int32
	status    int
	lastQuery atomic.Value
	lastUA    atomic.Value
}

func newFake(t *testing.T, body string) *fakeMB {
	f := &fakeMB{status: 200}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls.Add(1)
		f.lastQuery.Store(r.URL.Query().Get("query"))
		f.lastUA.Store(r.Header.Get("User-Agent"))
		w.WriteHeader(f.status)
		w.Write([]byte(body))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func client(t *testing.T, f *fakeMB, withCache bool) *Client {
	c := &Client{HTTP: f.srv.Client(), BaseURL: f.srv.URL, UserAgent: UserAgent("test"), Interval: time.Millisecond}
	if withCache {
		d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { d.Close() })
		c.DB = d
	}
	return c
}

func TestSingleIsFiledAsTheSingle(t *testing.T) {
	c := client(t, newFake(t, tidesSearch), false)
	// Arrived as a single: tier 4 named the release after the track.
	r, ok, err := c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides", Release: "Tides", DurationSec: 201})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	if r.Artist != "Skeler" || r.Release != "Tides" || r.TrackArtist != "" || r.Year != 2019 || r.TrackNo != 1 {
		t.Fatalf("%+v", r)
	}
	if r.IDs != (resolve.IDs{MBRecording: "rec-tides", MBRelease: "rel-single", MBReleaseGroup: "rg-single", MBArtist: "art-skeler"}) {
		t.Fatalf("%+v", r.IDs)
	}
	if r.Confidence < 0.95 || r.Tier != resolve.TierMusicBrainz {
		t.Fatalf("confidence %v", r.Confidence)
	}
}

func TestNamedAlbumWinsAndCompilationsKeepTheTrackArtist(t *testing.T) {
	c := client(t, newFake(t, tidesSearch), false)
	r, _, _ := c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides", Release: "Wave Vol 1"})
	if r.Release != "Wave Vol. 1" || r.Artist != "Various Artists" || r.TrackArtist != "Skeler" || r.TrackNo != 7 {
		t.Fatalf("%+v", r)
	}
}

func TestCandidatesAreRankedWithAlternatives(t *testing.T) {
	c := client(t, newFake(t, tidesSearch), false)
	cands, err := c.Candidates(context.Background(), Hint{Artist: "Skeler", Title: "Tides", Release: "Tides"}, 10)
	if err != nil || len(cands) != 3 {
		t.Fatal(len(cands), err)
	}
	if cands[0].IDs.MBRelease != "rel-single" || cands[1].IDs.MBRelease != "rel-comp" || cands[2].IDs.MBRecording != "rec-other" {
		t.Fatalf("%v / %v / %v", cands[0].IDs, cands[1].IDs, cands[2].IDs)
	}
	if cands[2].Confidence >= 0.8 {
		t.Fatal("a different artist's remix must not look confident:", cands[2].Confidence)
	}
}

func TestEvidenceMovesConfidence(t *testing.T) {
	c := client(t, newFake(t, tidesSearch), false)
	ctx := context.Background()
	right, _, _ := c.Match(ctx, Hint{Artist: "Skeler", Title: "Tides", DurationSec: 200})
	unknown, _, _ := c.Match(ctx, Hint{Artist: "Skeler", Title: "Tides"})
	wrong, _, _ := c.Match(ctx, Hint{Artist: "Skeler", Title: "Tides", DurationSec: 3600}) // an hour-long mix
	wrongArtist, _, _ := c.Match(ctx, Hint{Artist: "plenka", Title: "Tides"})
	if !(right.Confidence > unknown.Confidence && unknown.Confidence > wrong.Confidence) {
		t.Fatal(right.Confidence, unknown.Confidence, wrong.Confidence)
	}
	if wrong.Confidence >= 0.8 || wrongArtist.Confidence >= 0.8 {
		t.Fatal("contradicting evidence must fall below the review threshold", wrong.Confidence, wrongArtist.Confidence)
	}
}

func TestQueryUserAgentAndUnusableHints(t *testing.T) {
	f := newFake(t, tidesSearch)
	c := client(t, f, false)
	c.Match(context.Background(), Hint{Artist: `AC"DC`, Title: "Back in Black"})
	if q := f.lastQuery.Load().(string); q != `recording:"Back in Black" AND artist:"AC\"DC"` {
		t.Fatal(q)
	}
	if ua := f.lastUA.Load().(string); !strings.HasPrefix(ua, "Cosine/test ( https://") {
		t.Fatal(ua)
	}
	n := f.calls.Load()
	c.Match(context.Background(), Hint{Artist: "Unknown artist", Title: "untitled rip"})
	if f.calls.Load() != n {
		t.Fatal("an unknown artist is not worth a lookup")
	}
}

func TestCachedForeverAndRateLimited(t *testing.T) {
	f := newFake(t, tidesSearch)
	c := client(t, f, true)
	c.Interval = 150 * time.Millisecond
	ctx := context.Background()
	start := time.Now()
	c.Match(ctx, Hint{Artist: "Skeler", Title: "Tides"})
	c.Match(ctx, Hint{Artist: "Skeler", Title: "Tides"})
	c.Match(ctx, Hint{Artist: "Skeler", Title: "Other"})
	// Tides: one search, confident at once; again: cached; Other: the
	// filtered search finds nothing confident, so the full one runs too.
	if n := f.calls.Load(); n != 3 {
		t.Fatalf("expected 3 requests (one lookup cached), got %d", n)
	}
	if time.Since(start) < 150*time.Millisecond {
		t.Fatal("requests were not spaced")
	}
}

func TestUnavailableIsAnErrorNotAMatch(t *testing.T) {
	f := newFake(t, `busy`)
	f.status = 503
	c := client(t, f, true)
	_, ok, err := c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides"})
	if ok || !errors.Is(err, ErrUnavailable) || f.calls.Load() != 3 {
		t.Fatal(ok, err, f.calls.Load())
	}
	// Straight after a failure, MusicBrainz is skipped without a request.
	calls := f.calls.Load()
	if _, _, err := c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides"}); !errors.Is(err, ErrUnavailable) || f.calls.Load() != calls {
		t.Fatal("expected a back-off without a request", err)
	}
	// Failures are not cached: once the back-off ends, it asks again.
	c.downUntil = time.Time{}
	f.status = 200
	c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides"})
	if f.calls.Load() != calls+1 {
		t.Fatal("expected a fresh request after the back-off")
	}
}

func TestResolverUsesTheTier4ReadingAsItsHint(t *testing.T) {
	f := newFake(t, tidesSearch)
	r := Resolver{Client: client(t, f, false)}
	res, ok, err := r.Resolve(context.Background(), resolve.Input{
		OriginalName: "x.opus", DurationSec: 201,
		Source: &resolve.SourceInfo{Title: "Skeler - Tides [Free Download]", Uploader: "wave archive"},
	})
	if err != nil || !ok || res.IDs.MBRecording != "rec-tides" {
		t.Fatal(res, ok, err)
	}
	if q := f.lastQuery.Load().(string); q != `recording:"Tides" AND artist:"Skeler" AND status:official AND video:false` {
		t.Fatal(q)
	}
}

func TestCoverArtFallsBackToReleaseGroup(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if strings.HasPrefix(r.URL.Path, "/release-group/") {
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write([]byte("cover"))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()
	caa := CoverArt{HTTP: srv.Client(), BaseURL: srv.URL}
	data, ext := caa.Front(context.Background(), "rel-single", "rg-single")
	if string(data) != "cover" || ext != "jpg" {
		t.Fatal(string(data), ext)
	}
	if paths[0] != "/release/rel-single/front-500" || paths[1] != "/release-group/rg-single/front-500" {
		t.Fatal(paths)
	}
	if d, _ := caa.Front(context.Background(), "", ""); d != nil {
		t.Fatal("no IDs, no request")
	}
}

func TestSimilarity(t *testing.T) {
	cases := []struct {
		a, b string
		min  float64
		max  float64
	}{
		{"Tides", "tides", 1, 1},
		{"Tides (feat. Someone)", "Tides", 1, 1},
		{"Hollow VIP", "Hollow (VIP)", 1, 1},
		{"Tides", "Tides Remix", 0.6, 0.7},
		{"Tides", "Glass", 0, 0},
	}
	for _, c := range cases {
		if s := similarity(c.a, c.b); s < c.min || s > c.max {
			t.Errorf("similarity(%q, %q) = %v", c.a, c.b, s)
		}
	}
}

// The shape MusicBrainz returns for a well-known song, trimmed from
// "Californication" by Red Hot Chili Peppers: a live bootleg of the same
// length first, a music video, a compilation, and the studio recording on
// the album last.
const californication = `{"recordings":[
 {"id":"rec-woodstock","score":100,"title":"Californication","length":320000,"disambiguation":"live, 1999-07-25: Woodstock '99",
  "artist-credit":[{"name":"Red Hot Chili Peppers","artist":{"id":"art-rhcp","name":"Red Hot Chili Peppers"}}],
  "releases":[{"id":"rel-woodstock","title":"1999-07-25: Live in New York","status":"Bootleg",
   "release-group":{"id":"rg-w","primary-type":"Album","secondary-types":["Live"]}}]},
 {"id":"rec-video","score":100,"title":"Californication","length":320000,"video":true,
  "artist-credit":[{"name":"Red Hot Chili Peppers","artist":{"id":"art-rhcp","name":"Red Hot Chili Peppers"}}],
  "releases":[{"id":"rel-videos","title":"Greatest Hits and Videos","status":"Official","date":"2003",
   "release-group":{"id":"rg-v","primary-type":"Album","secondary-types":["Compilation"]}}]},
 {"id":"rec-studio","score":100,"title":"Californication","length":321000,
  "artist-credit":[{"name":"Red Hot Chili Peppers","artist":{"id":"art-rhcp","name":"Red Hot Chili Peppers"}}],
  "releases":[
   {"id":"rel-hits","title":"Greatest Hits","status":"Official","date":"2003-11-17",
    "release-group":{"id":"rg-h","primary-type":"Album","secondary-types":["Compilation"]}},
   {"id":"rel-album","title":"Californication","status":"Official","date":"1999-06-08",
    "release-group":{"id":"rg-album","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"6"}]}]}]}
]}`

func TestStudioRecordingOnItsAlbumBeatsLiveVideoAndCompilations(t *testing.T) {
	f := newFake(t, californication)
	c := client(t, f, false)
	for _, h := range []Hint{
		{Artist: "Red Hot Chili Peppers", Title: "Californication", Release: "Californication", DurationSec: 321},
		{Artist: "Red Hot Chili Peppers", Title: "Californication", Release: "Californication"}, // length unknown
	} {
		r, ok, err := c.Match(context.Background(), h)
		if err != nil || !ok || r.IDs.MBRecording != "rec-studio" || r.Release != "Californication" || r.TrackNo != 6 || r.Confidence < 0.8 {
			t.Fatalf("%+v → %+v", h, r)
		}
	}
	// Even named after the album, the compilation ranks below the original.
	cands, _ := c.Candidates(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Californication", DurationSec: 321}, 10)
	for _, cand := range cands {
		if cand.IDs.MBRecording == "rec-woodstock" || cand.IDs.MBRecording == "rec-video" {
			if cand.Confidence >= 0.8 {
				t.Fatalf("a live or video version is confident: %+v", cand)
			}
		}
	}
}

func TestLiveIsChosenWhenTheFileSaysSo(t *testing.T) {
	f := newFake(t, californication)
	c := client(t, f, false)
	r, ok, _ := c.Match(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Californication (Live)", DurationSec: 320})
	if !ok || r.IDs.MBRecording != "rec-woodstock" {
		t.Fatalf("%+v", r)
	}
	if q := f.lastQuery.Load().(string); strings.Contains(q, "secondarytype") {
		t.Fatal("asked to leave out live recordings for a live file:", q)
	}
}

func TestOnlyALiveVersionKnownIsAGuess(t *testing.T) {
	only := `{"recordings":[{"id":"rec-live","score":100,"title":"Tides","length":201000,"disambiguation":"live",
	 "artist-credit":[{"name":"Skeler","artist":{"id":"art-skeler","name":"Skeler"}}],
	 "releases":[{"id":"rel","title":"Live","status":"Official","release-group":{"id":"rg","primary-type":"Album","secondary-types":["Live"]}}]}]}`
	f := newFake(t, only)
	c := client(t, f, false)
	r, ok, _ := c.Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides", DurationSec: 201})
	if !ok || r.Confidence >= 0.8 {
		t.Fatalf("filed a live version of a plain title without review: %+v", r)
	}
}

func TestAnAlbumOutranksTheSingle(t *testing.T) {
	// "Around the World": the single's recording and the album's are
	// separate in MusicBrainz, the single is a year older than the reissue
	// the search returns, and the album must still win.
	body := `{"recordings":[
 {"id":"rec-single","score":100,"title":"Around the World","length":239000,
  "artist-credit":[{"name":"Red Hot Chili Peppers","artist":{"id":"art-rhcp","name":"Red Hot Chili Peppers"}}],
  "releases":[
   {"id":"rel-single","title":"Around the World","status":"Official","date":"1999-05-01",
    "release-group":{"id":"rg-s","primary-type":"Single"},"media":[{"position":1,"track":[{"number":"1"}]}]},
   {"id":"rel-hits","title":"Greatest Hits","status":"Official","date":"2003",
    "release-group":{"id":"rg-h","primary-type":"Album","secondary-types":["Compilation"]}}]},
 {"id":"rec-album","score":100,"title":"Around the World","length":239000,
  "artist-credit":[{"name":"Red Hot Chili Peppers","artist":{"id":"art-rhcp","name":"Red Hot Chili Peppers"}}],
  "releases":[
   {"id":"rel-ep","title":"Around the World EP","status":"Official","date":"1999",
    "release-group":{"id":"rg-e","primary-type":"EP"}},
   {"id":"rel-album","title":"Californication","status":"Official","date":"2000",
    "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"1"}]}]}]}
]}`
	c := client(t, newFake(t, body), false)
	r, ok, _ := c.Match(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Around the World", Release: "Around the World", DurationSec: 239})
	if !ok || r.Release != "Californication" || r.TrackNo != 1 || r.Confidence < 0.8 {
		t.Fatalf("%+v", r)
	}
	// Named by the file, the single is chosen after all.
	r, _, _ = c.Match(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Around the World", Release: "Around the World (Single)", DurationSec: 239})
	if r.IDs.MBRelease != "rel-single" {
		t.Fatalf("%+v", r)
	}
}

func TestTheAlbumsRecordingBeatsACompilationCopy(t *testing.T) {
	// "Bad at Love": MusicBrainz keeps the compilations' copy as its own
	// recording, a second closer to a YouTube upload than the album's.
	body := `{"recordings":[
 {"id":"rec-comp","score":100,"title":"Bad at Love","length":182000,
  "artist-credit":[{"name":"Halsey","artist":{"id":"art-h","name":"Halsey"}}],
  "releases":[{"id":"rel-hits","title":"Just the Hits: Love Songs","status":"Official","date":"2019",
   "artist-credit":[{"name":"Various Artists","artist":{"id":"art-va","name":"Various Artists"}}],
   "release-group":{"id":"rg-c","primary-type":"Album","secondary-types":["Compilation"]}}]},
 {"id":"rec-album","score":97,"title":"Bad at Love","length":181000,
  "artist-credit":[{"name":"Halsey","artist":{"id":"art-h","name":"Halsey"}}],
  "releases":[{"id":"rel-hfk","title":"hopeless fountain kingdom","status":"Official","date":"2017-06-02",
   "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"5"}]}]}]}
]}`
	c := client(t, newFake(t, body), false)
	for _, d := range []int{182, 184, 186} {
		r, ok, _ := c.Match(context.Background(), Hint{Artist: "Halsey", Title: "Bad At Love", Release: "Bad At Love", DurationSec: d})
		if !ok || r.Release != "hopeless fountain kingdom" || r.Artist != "Halsey" || r.Confidence < 0.8 {
			t.Fatalf("%ds: %+v", d, r)
		}
	}
}
