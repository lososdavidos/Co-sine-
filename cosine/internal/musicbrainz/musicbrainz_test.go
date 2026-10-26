package musicbrainz

import (
	"context"
	"errors"
	"fmt"
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

func TestANamedCompilationDoesNotBeatTheArtistsOwnRelease(t *testing.T) {
	c := client(t, newFake(t, tidesSearch), false)
	ctx := context.Background()
	// The file names the compilation, but the song has a release of its own.
	h := Hint{Artist: "Skeler", Title: "Tides", Release: "Wave Vol 1"}
	r, _, _ := c.Match(ctx, h)
	if r.IDs.MBRelease != "rel-single" || r.Artist != "Skeler" || r.Confidence < 0.8 {
		t.Fatalf("%+v", r)
	}
	// The compilation is still offered, under Various Artists with the
	// track's own credit (Q74).
	cands, _ := c.Candidates(ctx, h, 3)
	for _, cand := range cands {
		if cand.IDs.MBRelease == "rel-comp" {
			if cand.Artist != "Various Artists" || cand.TrackArtist != "Skeler" || cand.TrackNo != 7 {
				t.Fatalf("%+v", cand)
			}
			return
		}
	}
	t.Fatal("the compilation isn't offered")
}

func TestASongOnlyOnCompilationsGoesUnderTheNamedOne(t *testing.T) {
	comp := func(id, title string) string {
		return `{"id":"` + id + `","title":"` + title + `","status":"Official","date":"2018",
   "artist-credit":[{"name":"Various Artists","artist":{"id":"art-va","name":"Various Artists"}}],
   "release-group":{"id":"rg-` + id + `","primary-type":"Album","secondary-types":["Compilation"]}}`
	}
	body := `{"recordings":[{"id":"rec","score":100,"title":"Hollow","length":241000,
  "artist-credit":[{"name":"Deadcrow","artist":{"id":"art-d","name":"Deadcrow"}}],
  "releases":[` + comp("rel-a", "Wave Vol. 1") + `,` + comp("rel-b", "Wave Vol. 2") + `]}]}`
	c := client(t, newFake(t, body), false)
	r, ok, _ := c.Match(context.Background(), Hint{Artist: "Deadcrow", Title: "Hollow", Release: "Wave Vol. 2", DurationSec: 241})
	if !ok || r.IDs.MBRelease != "rel-b" || r.Artist != "Various Artists" || r.TrackArtist != "Deadcrow" {
		t.Fatalf("%+v", r)
	}
}

func TestAYouTubeCompilationCreditDoesNotWin(t *testing.T) {
	// YouTube credits the "Bad at Love" video to a compilation: the album
	// it was released on still wins.
	body := `{"recordings":[{"id":"rec","score":100,"title":"Bad at Love","length":181279,
  "artist-credit":[{"name":"Halsey","artist":{"id":"art-h","name":"Halsey"}}],
  "releases":[
   {"id":"rel-party","title":"Hot Party Winter 2018","status":"Official","date":"2017-11-17",
    "artist-credit":[{"name":"Various Artists","artist":{"id":"art-va","name":"Various Artists"}}],
    "release-group":{"id":"rg-p","primary-type":"Album","secondary-types":["Compilation"]}},
   {"id":"rel-hfk","title":"hopeless fountain kingdom","status":"Official","date":"2017-06-02",
    "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"10"}]}]}]}]}`
	c := client(t, newFake(t, body), false)
	for _, d := range []int{0, 181} {
		r, ok, _ := c.Match(context.Background(), Hint{Artist: "Halsey", Title: "Bad At Love", Release: "Hot Party Winter 2018", DurationSec: d})
		if !ok || r.Release != "hopeless fountain kingdom" || r.Artist != "Halsey" || r.TrackNo != 10 {
			t.Fatalf("%ds: %+v", d, r)
		}
	}
}

func TestARemixIsNotTheSong(t *testing.T) {
	// A music video runs longer than the song, and a remix of the same
	// length fit it better by length alone.
	body := `{"recordings":[
 {"id":"rec-remix","score":100,"title":"Bad at Love (Autograf remix)","length":205000,
  "artist-credit":[{"name":"Halsey","artist":{"id":"art-h","name":"Halsey"}}],
  "releases":[{"id":"rel-remixes","title":"Bad at Love (remixes)","status":"Official","date":"2017",
   "release-group":{"id":"rg-r","primary-type":"EP"}}]},
 {"id":"rec-album","score":97,"title":"Bad at Love","length":181000,
  "artist-credit":[{"name":"Halsey","artist":{"id":"art-h","name":"Halsey"}}],
  "releases":[{"id":"rel-hfk","title":"hopeless fountain kingdom","status":"Official","date":"2017-06-02",
   "release-group":{"id":"rg-a","primary-type":"Album"}}]}]}`
	c := client(t, newFake(t, body), false)
	ctx := context.Background()
	r, _, _ := c.Match(ctx, Hint{Artist: "Halsey", Title: "Bad At Love", Release: "Bad At Love", DurationSec: 205})
	if r.IDs.MBRecording != "rec-album" || r.Confidence < 0.8 {
		t.Fatalf("%+v", r)
	}
	// Asked for by name, the remix is the match.
	r, _, _ = c.Match(ctx, Hint{Artist: "Halsey", Title: "Bad At Love (Autograf Remix)", DurationSec: 205})
	if r.IDs.MBRecording != "rec-remix" || r.Confidence < 0.8 {
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

func TestTheSongsFirstReleaseWins(t *testing.T) {
	// "Around the World": the single's recording and the album's are
	// separate in MusicBrainz, and the single came out first. The first
	// release is the original, unless the file names the album.
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
   {"id":"rel-reissue","title":"Californication","status":"Official","date":"2000",
    "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"1"}]}]},
   {"id":"rel-album","title":"Californication","status":"Official","date":"1999-06-08",
    "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"1"}]}]}]}
]}`
	c := client(t, newFake(t, body), false)
	r, ok, _ := c.Match(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Around the World", DurationSec: 239})
	if !ok || r.IDs.MBRelease != "rel-single" || r.Confidence < 0.8 {
		t.Fatalf("%+v", r)
	}
	// Named by the file, the album is chosen, as it first came out.
	r, _, _ = c.Match(context.Background(), Hint{Artist: "Red Hot Chili Peppers", Title: "Around the World", Release: "Californication", DurationSec: 239})
	if r.IDs.MBRelease != "rel-album" || r.TrackNo != 1 || r.Year != 1999 {
		t.Fatalf("%+v", r)
	}
}

func TestACreditWrittenAnotherWayStillFindsTheSong(t *testing.T) {
	// MusicBrainz credits "Closer" to "The Chainsmokers ft. Halsey"; a file
	// saying "feat." found only compilations credited that way. The lead
	// artist is asked for too, and the title without its "(feat. X)".
	f := newFake(t, tidesSearch)
	c := client(t, f, false)
	c.Match(context.Background(), Hint{Artist: "The Chainsmokers feat. Halsey", Title: "Closer (feat. Halsey)"})
	if q := f.lastQuery.Load().(string); !strings.HasPrefix(q, `recording:"Closer" AND artist:("The Chainsmokers feat. Halsey" OR "The Chainsmokers")`) {
		t.Fatal(q)
	}
}

func TestAHitsOwnRecordingIsFoundPastTheCompilations(t *testing.T) {
	// "September": a full page of compilation copies, its own recording
	// not among them; the recordings on a single are asked for too.
	var comps []string
	for i := range page {
		comps = append(comps, fmt.Sprintf(`{"id":"rec-%d","score":100,"title":"September","length":212000,
  "artist-credit":[{"name":"Earth, Wind & Fire","artist":{"id":"art-ewf","name":"Earth, Wind & Fire"}}],
  "releases":[{"id":"rel-%d","title":"Disco Hits %d","status":"Official","date":"%d",
   "artist-credit":[{"name":"Various Artists","artist":{"id":"art-va","name":"Various Artists"}}],
   "release-group":{"id":"rg-%d","primary-type":"Album","secondary-types":["Compilation"]}}]}`, i, i, i, 1990+i%30, i))
	}
	own := `{"recordings":[{"id":"rec-own","score":100,"title":"September","length":215000,
  "artist-credit":[{"name":"Earth, Wind & Fire","artist":{"id":"art-ewf","name":"Earth, Wind & Fire"}}],
  "releases":[{"id":"rel-single","title":"September","status":"Official","date":"1978-11-18",
   "release-group":{"id":"rg-s","primary-type":"Single"},"media":[{"position":1,"track":[{"number":"A"}]}]}]}]}`
	var queries []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("query")
		queries = append(queries, q)
		if strings.HasSuffix(q, "AND primarytype:single") {
			w.Write([]byte(own))
			return
		}
		w.Write([]byte(`{"recordings":[` + strings.Join(comps, ",") + `]}`))
	}))
	defer srv.Close()
	c := &Client{HTTP: srv.Client(), BaseURL: srv.URL, UserAgent: UserAgent("test"), Interval: time.Millisecond}
	r, ok, err := c.Match(context.Background(), Hint{Artist: "Earth, Wind & Fire", Title: "September", DurationSec: 215})
	if err != nil || !ok || r.IDs.MBRelease != "rel-single" || r.Artist != "Earth, Wind & Fire" || r.Confidence < 0.8 {
		t.Fatalf("%v %+v", err, r)
	}
	if len(queries) != 2 {
		t.Fatal(queries)
	}
	// A page with room to spare had everything: no second search.
	f := newFake(t, tidesSearch)
	client(t, f, false).Match(context.Background(), Hint{Artist: "Skeler", Title: "Tides"})
	if n := f.calls.Load(); n != 1 {
		t.Fatal(n)
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

func TestALaterReleaseLosesToTheFirst(t *testing.T) {
	// "Timber": a greatest hits from 2017, entered as a plain album and a
	// recording of its own, whose length is closer to the file's than the
	// 2013 single's and album's. The first release is the likeliest
	// original: the single, out six weeks before the album.
	body := `{"recordings":[
 {"id":"rec-hits","score":100,"title":"Timber","length":204000,
  "artist-credit":[{"name":"Pitbull","artist":{"id":"art-p","name":"Pitbull"}}],
  "releases":[{"id":"rel-hits","title":"Greatest Hits","status":"Official","date":"2017-12-01",
   "release-group":{"id":"rg-h","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"2"}]}]}]},
 {"id":"rec-album","score":100,"title":"Timber","length":208000,
  "artist-credit":[{"name":"Pitbull","artist":{"id":"art-p","name":"Pitbull"}}],
  "releases":[
   {"id":"rel-single","title":"Timber","status":"Official","date":"2013-10-07",
    "release-group":{"id":"rg-s","primary-type":"Single"},"media":[{"position":1,"track":[{"number":"1"}]}]},
   {"id":"rel-album","title":"Global Warming: Meltdown","status":"Official","date":"2013-11-22",
    "release-group":{"id":"rg-a","primary-type":"Album"},"media":[{"position":1,"track":[{"number":"13"}]}]}]}
]}`
	c := client(t, newFake(t, body), false)
	got, err := c.Candidates(context.Background(), Hint{Artist: "Pitbull", Title: "Timber", DurationSec: 204}, 3)
	var order []string
	for _, r := range got {
		order = append(order, r.Release)
	}
	if err != nil || strings.Join(order, ", ") != "Timber, Global Warming: Meltdown, Greatest Hits" || got[0].Confidence < 0.8 {
		t.Fatalf("%v %+v", err, got)
	}
}
