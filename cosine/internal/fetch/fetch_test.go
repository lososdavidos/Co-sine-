package fetch

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

type env struct {
	f       *Fetcher
	db      *db.DB
	alice   int64
	bob     int64
	counter string
	cancel  context.CancelFunc
}

func setup(t *testing.T, fixtures map[string]any) *env {
	t.Helper()
	dir := t.TempDir()
	script, counter := testutil.FakeYtDlp(t, fixtures)

	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	user := func(n string) int64 {
		res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", n)
		id, _ := res.LastInsertId()
		return id
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	runner := ytdlp.Runner{Bin: script}
	f := &Fetcher{
		DB: d, Runner: runner, WorkDir: filepath.Join(dir, "work"), Workers: 2, HTTP: http.DefaultClient, Log: log,
		Search: &search.Service{DB: d, YtDlp: search.YtDlpSearch{Runner: runner}, NewAPI: func(string, string) search.Backend { return nil }},
		Ingester: &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
			Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8}},
	}
	e := &env{f: f, db: d, alice: user("alice"), bob: user("bob"), counter: counter}
	runCtx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	go f.Run(runCtx)
	t.Cleanup(cancel)
	return e
}

func (e *env) wait(t *testing.T, id int64, statuses ...string) Job {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		jobs, _ := e.f.jobs(context.Background(), "WHERE j.id = ?", id)
		if len(jobs) == 1 {
			for _, s := range statuses {
				if jobs[0].Status == s {
					return jobs[0]
				}
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	jobs, _ := e.f.jobs(context.Background(), "WHERE j.id = ?", id)
	t.Fatalf("job %d never reached %v: %+v", id, statuses, jobs)
	return Job{}
}

func (e *env) downloads() int {
	b, _ := os.ReadFile(e.counter)
	return strings.Count(string(b), "\n")
}

func track(url, id, title, uploader, body string, extra map[string]any) map[string]any {
	m := map[string]any{"_type": "video", "id": id, "title": title, "uploader": uploader,
		"webpage_url": url, "duration": 201.4, "ext": "opus", "body": body, "extractor_key": "Soundcloud"}
	for k, v := range extra {
		m[k] = v
	}
	return m
}

func TestSingleURLEndToEnd(t *testing.T) {
	art := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write([]byte("cover"))
	}))
	defer art.Close()
	url := "https://soundcloud.com/wave/tides"
	e := setup(t, map[string]any{
		url: track(url, "111", "Skeler - Tides [Free Download]", "wave archive", "tides-bytes",
			map[string]any{"thumbnails": []map[string]any{{"url": art.URL + "/t500x500.jpg", "width": 500, "height": 500}}}),
	})
	ctx := context.Background()
	jobs, err := e.f.Submit(ctx, e.alice, []string{"Listen on SoundCloud " + url}, false)
	if err != nil || len(jobs) != 1 {
		t.Fatal(jobs, err)
	}
	j := e.wait(t, jobs[0].ID, StatusDone, StatusFailed)
	if j.Status != StatusDone || j.Progress != 1 || j.TrackID == "" || j.Title != "Skeler - Tides [Free Download]" {
		t.Fatalf("%+v", j)
	}

	var artist, title, source, rel string
	var dur int
	e.db.QueryRow(`SELECT a.name, t.title, o.source_url, o.duration_sec, o.rel_path FROM objects o
		JOIN tracks t ON t.id = o.track_id JOIN artists a ON a.id = t.artist_id`).Scan(&artist, &title, &source, &dur, &rel)
	if artist != "Skeler" || title != "Tides" || source != url || dur != 201 {
		t.Fatalf("resolved %q %q %q %d", artist, title, source, dur)
	}
	if !strings.HasSuffix(rel, ".opus") {
		t.Fatal("stored under the wrong extension:", rel)
	}
	var artPath string
	e.db.QueryRow("SELECT art_path FROM releases").Scan(&artPath)
	if b, _ := os.ReadFile(filepath.Join(e.f.Ingester.DataDir, artPath)); string(b) != "cover" {
		t.Fatal("source cover not saved")
	}
	var pointers int
	e.db.QueryRow("SELECT COUNT(*) FROM pointers").Scan(&pointers)
	if pointers != 1 {
		t.Fatal("a fetch belongs to the requester only, got", pointers)
	}
	if entries, _ := os.ReadDir(e.f.WorkDir); len(entries) != 0 {
		t.Fatal("work dir not cleaned up")
	}
}

func TestKnownURLIsRefusedThenForced(t *testing.T) {
	url := "https://soundcloud.com/deadcrow/hollow"
	e := setup(t, map[string]any{url: track(url, "222", "Hollow", "Deadcrow", "hollow", nil)})
	ctx := context.Background()
	first, _ := e.f.Submit(ctx, e.alice, []string{url}, false)
	e.wait(t, first[0].ID, StatusDone)

	again, _ := e.f.Submit(ctx, e.alice, []string{url}, false)
	j := e.wait(t, again[0].ID, StatusKnown)
	if j.Error != "Already in your library." || e.downloads() != 1 {
		t.Fatalf("%+v, downloads %d", j, e.downloads())
	}

	// Someone else asking for it gets a Pointer, not a second download.
	bobs, _ := e.f.Submit(ctx, e.bob, []string{url}, false)
	if j := e.wait(t, bobs[0].ID, StatusDuplicate); j.TrackID == "" || e.downloads() != 1 {
		t.Fatalf("%+v, downloads %d", j, e.downloads())
	}

	// "Add anyway" downloads again; identical bytes dedupe into the same Object.
	if _, err := e.f.Retry(ctx, e.alice, again[0].ID, true); err != nil {
		t.Fatal(err)
	}
	e.wait(t, again[0].ID, StatusDuplicate)
	if e.downloads() != 2 {
		t.Fatal("forced retry should download")
	}
	var objects int
	e.db.QueryRow("SELECT COUNT(*) FROM objects").Scan(&objects)
	if objects != 1 {
		t.Fatal(objects)
	}
}

func TestCollectionQueuesEverything(t *testing.T) {
	set := "https://soundcloud.com/skeler/sets/tides"
	a, b := "https://soundcloud.com/skeler/one", "https://soundcloud.com/skeler/two"
	e := setup(t, map[string]any{
		set: map[string]any{"_type": "playlist", "title": "Tides", "entries": []map[string]any{
			{"_type": "url", "url": a, "title": "One"}, {"_type": "url", "url": b}}},
		a: track(a, "1", "One", "Skeler", "one", nil),
		b: track(b, "2", "Two", "Skeler", "two", nil),
	})
	ctx := context.Background()
	jobs, _ := e.f.Submit(ctx, e.alice, []string{set}, false)
	j := e.wait(t, jobs[0].ID, StatusExpanded)
	if !strings.Contains(j.Title, "2 tracks queued") {
		t.Fatal(j.Title)
	}
	deadline := time.Now().Add(10 * time.Second)
	for e.downloads() < 2 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	all, _ := e.f.Jobs(ctx, e.alice, 10)
	for _, x := range all[:2] {
		e.wait(t, x.ID, StatusDone)
	}
}

func TestFailureCarriesYtDlpsReasonAndRetries(t *testing.T) {
	url := "https://soundcloud.com/gone/track"
	e := setup(t, map[string]any{url: map[string]any{"error": "HTTP Error 404: Not Found"}})
	ctx := context.Background()
	jobs, _ := e.f.Submit(ctx, e.alice, []string{url}, false)
	j := e.wait(t, jobs[0].ID, StatusFailed)
	if j.Error != "HTTP Error 404: Not Found" {
		t.Fatalf("%q", j.Error)
	}
	if _, err := e.f.Retry(ctx, e.bob, j.ID, false); err == nil {
		t.Fatal("one account cannot retry another's job")
	}
	if _, err := e.f.Retry(ctx, e.alice, j.ID, false); err != nil {
		t.Fatal(err)
	}
	e.wait(t, j.ID, StatusFailed)
}

func TestSubmitRejectsNonLinks(t *testing.T) {
	e := setup(t, nil)
	if _, err := e.f.Submit(context.Background(), e.alice, []string{"skeler tides"}, false); err == nil {
		t.Fatal("a query is not a link")
	}
}

func TestLookup(t *testing.T) {
	one := "https://soundcloud.com/skeler/one"
	set := "https://soundcloud.com/skeler/sets/tides"
	e := setup(t, map[string]any{
		one: track(one, "1", "One", "Skeler", "one", nil),
		set: map[string]any{"_type": "playlist", "title": "Tides", "entries": []map[string]any{
			{"_type": "url", "url": one, "title": "One", "ie_key": "Soundcloud"},
			{"_type": "url", "url": "https://soundcloud.com/skeler/two"}}},
		"scsearch": map[string]any{"_type": "playlist", "entries": []map[string]any{{"url": one, "title": "One", "uploader": "Skeler", "duration": 200}}},
		"ytsearch": map[string]any{"_type": "playlist", "entries": []map[string]any{{"url": "https://www.youtube.com/watch?v=x", "title": "One (YT)"}}},
	})
	ctx := context.Background()
	jobs, _ := e.f.Submit(ctx, e.alice, []string{one}, false)
	e.wait(t, jobs[0].ID, StatusDone)

	l, err := e.f.Lookup(ctx, e.alice, set)
	if err != nil || l.Kind != KindCollection || l.Title != "Tides" || len(l.Items) != 2 {
		t.Fatal(l, err)
	}
	if !l.Items[0].InLibrary || l.Items[1].InLibrary || l.Items[1].Title != "https://soundcloud.com/skeler/two" {
		t.Fatalf("%+v", l.Items)
	}

	l, err = e.f.Lookup(ctx, e.alice, "skeler one")
	if err != nil || l.Kind != KindSearch || l.Backend != search.BackendYtDlp || len(l.Items) != 2 {
		t.Fatal(l, err)
	}
	if l.Items[0].Source != "soundcloud" || l.Items[1].Source != "youtube" || !l.Items[0].InLibrary {
		t.Fatalf("%+v", l.Items)
	}

	l, err = e.f.Lookup(ctx, e.bob, one)
	if err != nil || l.Kind != KindSingle || l.Items[0].InLibrary {
		t.Fatal(l, err)
	}
}
