package native

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func reviewServer(t *testing.T) (*httptest.Server, int64) {
	t.Helper()
	dir := t.TempDir()
	d, _ := db.Open(filepath.Join(dir, "c.db"))
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "sesame", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	mb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"recordings":[{"id":"rec","score":100,"title":"Tides","length":201000,
			"artist-credit":[{"name":"Skeler","artist":{"id":"a"}}],
			"releases":[{"id":"rel","title":"Tides","status":"Official","date":"2019","release-group":{"id":"rg","primary-type":"Single"}}]}]}`))
	}))
	t.Cleanup(mb.Close)
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: "tides", Artist: "skeler", Body: "x"}.
		Write(t, filepath.Join(drop, "a.mp3")), Source: "inbox", ForUsers: []int64{alice.ID}})
	if err != nil {
		t.Fatal(err)
	}
	api := &API{Auth: as, Version: "t", Log: log, Review: &review.Service{DB: d, Ingester: g, Threshold: 0.8, Log: log,
		MB: &musicbrainz.Client{HTTP: mb.Client(), BaseURL: mb.URL, UserAgent: "t", Interval: time.Millisecond}}}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, out.TrackID
}

func TestReviewAPI(t *testing.T) {
	srv, _ := reviewServer(t)
	_, caps := call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if c := caps["capabilities"].([]any); len(c) != 1 || c[0] != "review" {
		t.Fatal(caps)
	}
	tok := login(t, srv)

	code, q := call(t, srv, "GET", "/cosine/v1/review", tok, nil)
	items := q["items"].([]any)
	if code != 200 || q["total"] != float64(1) || len(items) != 1 {
		t.Fatal(code, q)
	}
	item := items[0].(map[string]any)
	track := item["trackId"].(string)
	if item["identity"].(map[string]any)["title"] != "tides" || item["why"] == "" {
		t.Fatal(item)
	}

	code, c := call(t, srv, "POST", "/cosine/v1/review/"+track+"/candidates", tok, nil)
	cands := c["candidates"].([]any)
	if code != 200 || len(cands) != 1 {
		t.Fatal(code, c)
	}
	cand := cands[0].(map[string]any)
	if cand["key"] != "rec/rel" || cand["source"] != "musicbrainz" || cand["artist"] != "Skeler" {
		t.Fatal(cand)
	}

	code, chosen := call(t, srv, "POST", "/cosine/v1/review/"+track+"/choose", tok, map[string]any{"key": "rec/rel"})
	if code != 200 || chosen["identity"].(map[string]any)["reviewed"] != true {
		t.Fatal(code, chosen)
	}
	_, q = call(t, srv, "GET", "/cosine/v1/review", tok, nil)
	if q["total"] != float64(0) {
		t.Fatal("chosen track should leave the queue")
	}

	code, fixed := call(t, srv, "POST", "/cosine/v1/review/"+track+"/correct", tok,
		map[string]any{"artist": "Skeler", "title": "Tides (VIP)", "year": 2020})
	if code != 200 || fixed["identity"].(map[string]any)["source"] != "manual" {
		t.Fatal(code, fixed)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/review/tr-999/confirm", tok, nil); code != 404 {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/review", "", nil); code != 401 {
		t.Fatal(code)
	}
}
