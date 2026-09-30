package dashboard

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/fetch"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

type env struct {
	dash   *Dashboard
	srv    *httptest.Server
	client *http.Client
	db     *db.DB
	auth   *auth.Service
	dir    string
	inbox  string
}

func setup(t *testing.T) *env {
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "cosine.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e := &env{db: d, auth: as, dir: dir}
	dash := &Dashboard{DB: d, Auth: as, Log: log,
		Ingester:      &ingest.Ingester{DB: d, DataDir: dir, Log: log},
		OnInboxChange: func(p string) { e.inbox = p }}
	e.dash = dash
	e.srv = httptest.NewServer(dash.Handler())
	t.Cleanup(e.srv.Close)
	jar, _ := cookiejar.New(nil)
	e.client = &http.Client{Jar: jar}
	return e
}

func (e *env) post(t *testing.T, path string, form url.Values) (*http.Response, string) {
	t.Helper()
	res, err := e.client.PostForm(e.srv.URL+path, form)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	return res, string(b)
}

func (e *env) get(t *testing.T, path string) (*http.Response, string) {
	t.Helper()
	res, err := e.client.Get(e.srv.URL + path)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	return res, string(b)
}

func (e *env) setupForm(store, inbox string) url.Values {
	return url.Values{"store": {store}, "inbox": {inbox}, "username": {"admin"}, "password": {"pw"}, "password2": {"pw"}}
}

func TestFirstRunSetup(t *testing.T) {
	e := setup(t)
	res, body := e.get(t, "/")
	if res.Request.URL.Path != "/setup" || !strings.Contains(body, "Store path") {
		t.Fatal("fresh server should send everything to setup")
	}

	store, inbox := filepath.Join(e.dir, "store"), filepath.Join(e.dir, "inbox")
	_, body = e.post(t, "/setup", e.setupForm(store, filepath.Join(store, "inbox")))
	if !strings.Contains(body, "separate folders") {
		t.Fatal("an Inbox inside the Store must be refused")
	}
	_, body = e.post(t, "/setup", e.setupForm("relative/path", inbox))
	if !strings.Contains(body, "absolute path") {
		t.Fatal("relative paths must be refused")
	}

	res, body = e.post(t, "/setup", e.setupForm(store, inbox))
	if res.Request.URL.Path != "/" || !strings.Contains(body, "Cosine is set up") || !strings.Contains(body, store) {
		t.Fatalf("setup did not land on status: %s %s", res.Request.URL, body)
	}
	if e.inbox != inbox {
		t.Fatal("inbox watcher not started")
	}
	if got, _ := e.db.Setting(context.Background(), db.SettingStorePath); got != store {
		t.Fatal(got)
	}

	// Setup cannot be run twice.
	res, _ = e.post(t, "/setup", e.setupForm(store, inbox))
	if res.Request.URL.Path == "/setup" {
		t.Fatal("setup should be closed once an account exists")
	}
}

func TestAdminOnlyAndAccounts(t *testing.T) {
	e := setup(t)
	store, inbox := filepath.Join(e.dir, "s"), filepath.Join(e.dir, "i")
	e.post(t, "/setup", e.setupForm(store, inbox))

	_, body := e.post(t, "/users", url.Values{"username": {"bob"}, "password": {"x"}})
	if !strings.Contains(body, "Created account bob") {
		t.Fatal(body)
	}
	_, body = e.post(t, "/users", url.Values{"username": {"BOB"}, "password": {"x"}})
	if !strings.Contains(body, "taken") {
		t.Fatal("usernames are case-insensitive")
	}

	e.post(t, "/logout", nil)
	res, body := e.post(t, "/login", url.Values{"username": {"bob"}, "password": {"x"}})
	if !strings.Contains(body, "admin accounts") {
		t.Fatalf("a member reached the dashboard: %s", res.Request.URL)
	}

	e.post(t, "/login", url.Values{"username": {"admin"}, "password": {"pw"}})
	users, _ := e.auth.Users(context.Background())
	var bobID string
	for _, u := range users {
		if u.Name == "bob" {
			bobID = itoa(u.ID)
		}
	}
	_, body = e.post(t, "/users/"+bobID+"/delete", nil)
	if !strings.Contains(body, "Account deleted") {
		t.Fatal(body)
	}
}

func TestCrossSitePostRefused(t *testing.T) {
	e := setup(t)
	req, _ := http.NewRequest("POST", e.srv.URL+"/setup", strings.NewReader("x=1"))
	req.Header.Set("Origin", "https://evil.example")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != http.StatusForbidden {
		t.Fatal(res.StatusCode)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func TestAddMusicFromALink(t *testing.T) {
	e := setup(t)
	const link = "https://soundcloud.com/skeler/sets/tides"
	bin, _ := testutil.FakeYtDlp(t, map[string]any{
		link: map[string]any{"_type": "playlist", "title": "Tides", "entries": []map[string]any{
			{"_type": "url", "url": "https://soundcloud.com/skeler/one", "title": "One", "duration": 125},
			{"_type": "url", "url": "https://soundcloud.com/skeler/two", "title": "Two"}}},
	})
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	runner := ytdlp.Runner{Bin: bin}
	e.dash.Fetcher = &fetch.Fetcher{DB: e.db, Runner: runner, Log: log, WorkDir: t.TempDir(),
		Search: &search.Service{DB: e.db, YtDlp: search.YtDlpSearch{Runner: runner}, NewAPI: func(string, string) search.Backend { return nil }}}
	e.post(t, "/setup", e.setupForm(filepath.Join(e.dir, "s"), filepath.Join(e.dir, "i")))

	_, body := e.get(t, "/add?q="+url.QueryEscape(link))
	if !strings.Contains(body, "Tides: 2 tracks") || !strings.Contains(body, "2:05") || strings.Count(body, "checked") != 2 {
		t.Fatal(body)
	}
	_, body = e.post(t, "/add", url.Values{"url": {"https://soundcloud.com/skeler/two"}})
	if !strings.Contains(body, "Queued 1 link") || !strings.Contains(body, "soundcloud.com/skeler/two") {
		t.Fatal(body)
	}
}

func TestReviewAndRevertInTheDashboard(t *testing.T) {
	e := setup(t)
	mb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"recordings":[{"id":"rec","score":100,"title":"Tides","artist-credit":[{"name":"Skeler","artist":{"id":"a"}}],
			"releases":[{"id":"rel","title":"Tides","status":"Official","release-group":{"id":"rg","primary-type":"Single"}}]}]}`))
	}))
	defer mb.Close()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: e.db, DataDir: e.dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	e.dash.Ingester = g
	e.dash.Review = &review.Service{DB: e.db, Ingester: g, Threshold: 0.8, Log: log,
		MB: &musicbrainz.Client{HTTP: mb.Client(), BaseURL: mb.URL, UserAgent: "t", Interval: time.Millisecond}}
	e.srv.Config.Handler = e.dash.Handler()
	store := filepath.Join(e.dir, "s")
	e.post(t, "/setup", e.setupForm(store, filepath.Join(e.dir, "i")))
	drop := t.TempDir()
	out, err := g.Ingest(context.Background(), ingest.Request{Path: testutil.MP3{Title: "tides", Artist: "skeler", Body: "x"}.
		Write(t, filepath.Join(drop, "a.mp3")), Source: "inbox"})
	if err != nil {
		t.Fatal(err)
	}
	track := "tr-" + itoa(out.TrackID)

	_, body := e.get(t, "/review")
	if !strings.Contains(body, "1 track to review") || !strings.Contains(body, "Read from the file") {
		t.Fatal(body)
	}
	_, body = e.get(t, "/review/"+track)
	if !strings.Contains(body, "Use this") || !strings.Contains(body, "92%") {
		t.Fatal(body)
	}
	_, body = e.post(t, "/review/"+track+"/choose", url.Values{"key": {"rec/rel"}, "artist": {"skeler"}, "title": {"tides"}})
	if !strings.Contains(body, "Corrected: Skeler / Tides") {
		t.Fatal(body)
	}
	_, body = e.get(t, "/corrections")
	if !strings.Contains(body, "skeler / tides / tides") || !strings.Contains(body, "Revert") {
		t.Fatal(body)
	}
	_, body = e.post(t, "/corrections/1/revert", nil)
	if !strings.Contains(body, "Reverted.") || strings.Count(body, ">Revert<") != 0 {
		t.Fatal(body)
	}
}
