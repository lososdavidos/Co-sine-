package subsonic

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

type env struct {
	srv   *httptest.Server
	db    *db.DB
	alice auth.User
	bob   auth.User
	g     *ingest.Ingester
	drop  string
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "cosine.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "secret.key"))
	as, _ := auth.New(d, key)
	alice, _ := as.CreateUser(ctx, "alice", "sesame", true)
	bob, _ := as.CreateUser(ctx, "bob", "hunter2", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8},
		Probe:    func(string) int { return 200 }}
	api := &API{DB: d, Auth: as, DataDir: dir, Version: "test", Log: log}
	mux := http.NewServeMux()
	mux.Handle("/rest/", api.Handler())
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	return &env{srv, d, alice, bob, g, drop}
}

func (e *env) add(t *testing.T, m testutil.MP3, users ...int64) ingest.Outcome {
	t.Helper()
	p := m.Write(t, filepath.Join(e.drop, m.Title+".mp3"))
	out, err := e.g.Ingest(context.Background(), ingest.Request{Path: p, Source: "upload", ForUsers: users})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func (e *env) url(user, pass, method string, params url.Values) string {
	if params == nil {
		params = url.Values{}
	}
	salt := "c19b2d"
	sum := md5.Sum([]byte(pass + salt))
	params.Set("u", user)
	params.Set("t", hex.EncodeToString(sum[:]))
	params.Set("s", salt)
	params.Set("v", "1.16.1")
	params.Set("c", "test")
	if params.Get("f") == "" {
		params.Set("f", "json")
	}
	return e.srv.URL + "/rest/" + method + ".view?" + params.Encode()
}

// get returns the inner subsonic-response object.
func (e *env) get(t *testing.T, user, pass, method string, params url.Values) map[string]any {
	t.Helper()
	res, err := http.Get(e.url(user, pass, method, params))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var body map[string]map[string]any
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	return body["subsonic-response"]
}

func ok(t *testing.T, r map[string]any) map[string]any {
	t.Helper()
	if r["status"] != "ok" {
		t.Fatalf("status %v: %v", r["status"], r["error"])
	}
	return r
}

func TestAuth(t *testing.T) {
	e := setup(t)
	r := ok(t, e.get(t, "alice", "sesame", "ping", nil))
	if r["type"] != "cosine" || r["openSubsonic"] != true {
		t.Fatalf("server identity: %v", r)
	}
	bad := e.get(t, "alice", "wrong", "ping", nil)
	if bad["status"] != "failed" || bad["error"].(map[string]any)["code"] != float64(40) {
		t.Fatalf("%v", bad)
	}
	// Legacy p=enc:hex also works.
	res, _ := http.Get(e.srv.URL + "/rest/ping?f=json&u=bob&p=enc:" + hex.EncodeToString([]byte("hunter2")))
	var body map[string]map[string]any
	json.NewDecoder(res.Body).Decode(&body)
	if body["subsonic-response"]["status"] != "ok" {
		t.Fatal("p=enc: auth failed")
	}
}

func TestUnauthenticatedPingStillIdentifiesTheServer(t *testing.T) {
	// Sine's probe relies on this: version and type without credentials.
	e := setup(t)
	res, _ := http.Get(e.srv.URL + "/rest/ping.view?f=json")
	var body map[string]map[string]any
	json.NewDecoder(res.Body).Decode(&body)
	r := body["subsonic-response"]
	if r["status"] != "failed" || r["version"] != APIVersion || r["type"] != "cosine" {
		t.Fatalf("%v", r)
	}
}

func TestBrowseIsScopedToTheAccountsLibrary(t *testing.T) {
	e := setup(t)
	e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Album: "Tides EP", Track: "1", Picture: []byte("img"), Body: "1"}, e.alice.ID)
	e.add(t, testutil.MP3{Title: "Surge", Artist: "Skeler", Album: "Tides EP", Track: "2", Body: "2"}, e.alice.ID, e.bob.ID)
	e.add(t, testutil.MP3{Title: "glass", Artist: "plenka", Body: "3"}, e.bob.ID)

	idx := ok(t, e.get(t, "alice", "sesame", "getArtists", nil))["artists"].(map[string]any)["index"].([]any)
	if len(idx) != 1 {
		t.Fatalf("alice should see only Skeler: %v", idx)
	}
	skeler := idx[0].(map[string]any)["artist"].([]any)[0].(map[string]any)
	if skeler["name"] != "Skeler" || skeler["coverArt"] == nil {
		t.Fatalf("%v", skeler)
	}

	artist := ok(t, e.get(t, "alice", "sesame", "getArtist", url.Values{"id": {skeler["id"].(string)}}))["artist"].(map[string]any)
	albumID := artist["album"].([]any)[0].(map[string]any)["id"].(string)

	album := ok(t, e.get(t, "alice", "sesame", "getAlbum", url.Values{"id": {albumID}}))["album"].(map[string]any)
	songs := album["song"].([]any)
	if len(songs) != 2 || songs[0].(map[string]any)["title"] != "Tides" {
		t.Fatalf("album songs: %v", songs)
	}
	s0 := songs[0].(map[string]any)
	for _, k := range []string{"id", "artist", "album", "albumId", "suffix", "size", "duration", "coverArt"} {
		if s0[k] == nil {
			t.Errorf("song missing %q (Sine reads it)", k)
		}
	}
	if s0["duration"] != float64(200) {
		t.Errorf("duration %v", s0["duration"])
	}

	bobAlbum := ok(t, e.get(t, "bob", "hunter2", "getAlbum", url.Values{"id": {albumID}}))["album"].(map[string]any)
	if n := len(bobAlbum["song"].([]any)); n != 1 {
		t.Fatalf("bob holds one track of that release, saw %d", n)
	}

	// Bob cannot stream Alice's track.
	res, _ := http.Get(e.url("bob", "hunter2", "stream", url.Values{"id": {s0["id"].(string)}}))
	var body map[string]map[string]any
	json.NewDecoder(res.Body).Decode(&body)
	if body["subsonic-response"]["status"] != "failed" {
		t.Fatal("bob streamed a track outside his library")
	}
}

func TestAlbumListsSearchAndSong(t *testing.T) {
	e := setup(t)
	out := e.add(t, testutil.MP3{Title: "Hollow", Artist: "Deadcrow", Body: "1"}, e.alice.ID)
	e.add(t, testutil.MP3{Title: "drift", Artist: "barnacle boi", Body: "2"}, e.alice.ID)

	for _, typ := range []string{"newest", "alphabeticalByName", "alphabeticalByArtist", "random"} {
		l := ok(t, e.get(t, "alice", "sesame", "getAlbumList2", url.Values{"type": {typ}, "size": {"10"}}))
		if n := len(l["albumList2"].(map[string]any)["album"].([]any)); n != 2 {
			t.Errorf("%s: %d albums", typ, n)
		}
	}
	res := ok(t, e.get(t, "alice", "sesame", "search3", url.Values{"query": {"holl"}}))["searchResult3"].(map[string]any)
	if n := len(res["song"].([]any)); n != 1 {
		t.Fatalf("search songs: %v", res)
	}
	all := ok(t, e.get(t, "alice", "sesame", "search3", url.Values{"query": {""}, "songCount": {"50"}}))["searchResult3"].(map[string]any)
	if n := len(all["song"].([]any)); n != 2 {
		t.Fatalf("empty query should list everything, got %d", n)
	}
	song := ok(t, e.get(t, "alice", "sesame", "getSong", url.Values{"id": {"tr-" + itoa(out.TrackID)}}))["song"].(map[string]any)
	if song["title"] != "Hollow" {
		t.Fatal(song)
	}
	nf := e.get(t, "alice", "sesame", "getSong", url.Values{"id": {"tr-9999"}})
	if nf["error"].(map[string]any)["code"] != float64(70) {
		t.Fatal(nf)
	}
}

func TestStreamServesOriginalBytesWithRanges(t *testing.T) {
	e := setup(t)
	out := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "0123456789"}, e.alice.ID)
	req, _ := http.NewRequest("GET", e.url("alice", "sesame", "stream", url.Values{"id": {"tr-" + itoa(out.TrackID)}, "format": {"raw"}}), nil)
	req.Header.Set("Range", "bytes=-4")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusPartialContent || string(b) != "6789" {
		t.Fatalf("%d %q", res.StatusCode, b)
	}
	if res.Header.Get("Content-Type") != "audio/mpeg" {
		t.Fatal(res.Header.Get("Content-Type"))
	}
}

func TestScrobbleIsIdempotentAndFeedsRecent(t *testing.T) {
	e := setup(t)
	out := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "x"}, e.alice.ID)
	params := url.Values{"id": {"tr-" + itoa(out.TrackID)}, "time": {"1700000000000"}, "submission": {"true"}}
	ok(t, e.get(t, "alice", "sesame", "scrobble", params))
	ok(t, e.get(t, "alice", "sesame", "scrobble", params)) // an offline resend
	ok(t, e.get(t, "bob", "hunter2", "scrobble", params))  // no Pointer: nothing recorded

	var n int
	e.db.QueryRow("SELECT COUNT(*) FROM plays").Scan(&n)
	if n != 1 {
		t.Fatalf("plays = %d", n)
	}
	recent := ok(t, e.get(t, "alice", "sesame", "getAlbumList2", url.Values{"type": {"recent"}}))
	if len(recent["albumList2"].(map[string]any)["album"].([]any)) != 1 {
		t.Fatal("recent should include the played album")
	}
	frequent := ok(t, e.get(t, "bob", "hunter2", "getAlbumList2", url.Values{"type": {"frequent"}}))
	if a, _ := frequent["albumList2"].(map[string]any)["album"].([]any); len(a) != 0 {
		t.Fatal("bob has no plays")
	}
}

func TestStarAndCoverArt(t *testing.T) {
	e := setup(t)
	out := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Album: "EP", Picture: []byte("jpegbytes"), Body: "x"}, e.alice.ID)
	ok(t, e.get(t, "alice", "sesame", "star", url.Values{"id": {"tr-" + itoa(out.TrackID)}}))
	starred := ok(t, e.get(t, "alice", "sesame", "getStarred2", nil))["starred2"].(map[string]any)
	if len(starred["song"].([]any)) != 1 {
		t.Fatal(starred)
	}
	song := ok(t, e.get(t, "alice", "sesame", "getSong", url.Values{"id": {"tr-" + itoa(out.TrackID)}}))["song"].(map[string]any)
	res, _ := http.Get(e.url("alice", "sesame", "getCoverArt", url.Values{"id": {song["coverArt"].(string)}}))
	b, _ := io.ReadAll(res.Body)
	if string(b) != "jpegbytes" {
		t.Fatalf("%q", b)
	}
}

func TestXMLFormat(t *testing.T) {
	e := setup(t)
	e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "x"}, e.alice.ID)
	res, _ := http.Get(e.url("alice", "sesame", "getArtists", url.Values{"f": {"xml"}}))
	b, _ := io.ReadAll(res.Body)
	s := string(b)
	if !strings.Contains(s, `<subsonic-response xmlns="http://subsonic.org/restapi" status="ok"`) ||
		!strings.Contains(s, `name="Skeler"`) {
		t.Fatal(s)
	}
}

func itoa(n int64) string { return strings.TrimPrefix(trackID(n), "tr-") }
