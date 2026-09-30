package review

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

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

// MusicBrainz knows "Tides" by Skeler; nothing else.
const mbTides = `{"recordings":[{"id":"rec-tides","score":100,"title":"Tides","length":201000,
 "artist-credit":[{"name":"Skeler","artist":{"id":"art-skeler","name":"Skeler"}}],
 "releases":[{"id":"rel-tides","title":"Tides","status":"Official","date":"2019-02-03",
   "release-group":{"id":"rg-tides","primary-type":"Single"},"media":[{"position":1,"track":[{"number":"1"}]}]}]}]}`

type env struct {
	s            *Service
	db           *db.DB
	root, drop   string
	admin, alice auth.User
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	root := filepath.Join(dir, "store")
	d.SetSetting(ctx, db.SettingStorePath, root)
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	admin, _ := as.CreateUser(ctx, "admin", "x", true)
	alice, _ := as.CreateUser(ctx, "alice", "x", false)

	mb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(strings.ToLower(r.URL.Query().Get("query")), `"tides"`) {
			w.Write([]byte(mbTides))
			return
		}
		w.Write([]byte(`{"recordings":[]}`))
	}))
	t.Cleanup(mb.Close)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: log,
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}, MinConfidence: 0.8}}
	s := &Service{DB: d, Ingester: g, Threshold: 0.8, Log: log,
		MB: &musicbrainz.Client{HTTP: mb.Client(), BaseURL: mb.URL, UserAgent: "test", Interval: time.Millisecond}}
	drop := filepath.Join(dir, "drop")
	os.MkdirAll(drop, 0o755)
	return &env{s, d, root, drop, admin, alice}
}

func (e *env) add(t *testing.T, name, artist, title string, users ...int64) int64 {
	t.Helper()
	out, err := e.s.Ingester.Ingest(context.Background(), ingest.Request{
		Path: testutil.MP3{Title: title, Artist: artist, Body: name}.Write(t, filepath.Join(e.drop, name+".mp3")), Source: "inbox", ForUsers: users})
	if err != nil {
		t.Fatal(err)
	}
	return out.TrackID
}

func TestQueueIsScopedAndExplained(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	e.add(t, "a", "skeler", "tides", e.alice.ID)
	e.add(t, "b", "plenka", "glass", e.admin.ID)

	items, total, err := e.s.Queue(ctx, e.alice, 50, 0)
	if err != nil || total != 1 || len(items) != 1 || items[0].Identity.Title != "tides" {
		t.Fatal(items, total, err)
	}
	if items[0].Why != "Read from the file's tags." || len(items[0].Files) != 1 {
		t.Fatalf("%+v", items[0])
	}
	if _, total, _ := e.s.Queue(ctx, e.admin, 50, 0); total != 2 {
		t.Fatal("admin sees the whole queue")
	}
	if _, err := e.s.Item(ctx, e.alice, 2); err != ErrNotFound {
		t.Fatal("alice can't see a track outside her library")
	}
}

func TestCorrectFromCandidateThenRevert(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	id := e.add(t, "a", "skeler", "tides", e.alice.ID)

	cands, err := e.s.Candidates(ctx, e.alice, id, Query{})
	if err != nil || len(cands) == 0 || cands[0].IDs.MBRecording != "rec-tides" {
		t.Fatal(cands, err)
	}
	if _, err := e.s.Choose(ctx, e.alice, id, Query{}, "forged/key"); err == nil {
		t.Fatal("an unknown candidate key must be refused")
	}
	item, err := e.s.Choose(ctx, e.alice, id, Query{}, CandidateKey(cands[0]))
	if err != nil || !item.Identity.Reviewed || item.Identity.Artist != "Skeler" || item.Identity.Source != "musicbrainz" {
		t.Fatal(item, err)
	}
	if _, err := os.Stat(filepath.Join(e.root, "Skeler", "Tides", "01 Tides.mp3")); err != nil {
		t.Fatal(err)
	}
	if _, total, _ := e.s.Queue(ctx, e.alice, 50, 0); total != 0 {
		t.Fatal("a corrected track leaves the queue")
	}

	hist, _ := e.s.History(ctx, 10)
	if len(hist) != 1 || hist[0].Who != "alice" || hist[0].Before.Artist != "skeler" || !hist[0].Revertible {
		t.Fatalf("%+v", hist)
	}
	if err := e.s.Revert(ctx, e.admin, hist[0].ID); err != nil {
		t.Fatal(err)
	}
	back, _ := e.s.Ingester.Identity(ctx, id)
	if back.Artist != "skeler" || back.Reviewed {
		t.Fatalf("%+v", back)
	}
	if _, err := os.Stat(filepath.Join(e.root, "skeler", "tides", "tides.mp3")); err != nil {
		t.Fatal("file not moved back:", err)
	}
	hist, err = e.s.History(ctx, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(hist) != 2 || hist[0].Kind != "revert" || !hist[1].Reverted || hist[1].Revertible || hist[0].Revertible {
		t.Fatalf("%+v", hist)
	}
	if err := e.s.Revert(ctx, e.admin, hist[1].ID); err == nil {
		t.Fatal("reverting twice must fail")
	}
}

func TestManualEntryAndRevertOrder(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	id := e.add(t, "a", "x", "untitled rip", e.alice.ID)

	first, err := e.s.Correct(ctx, e.alice, id, resolve.Result{Artist: "barnacle boi", Title: "drift", Release: "",
		Source: "musicbrainz", IDs: resolve.IDs{MBRecording: "forged"}})
	if err != nil {
		t.Fatal(err)
	}
	// Typed by hand is manual, whatever the client claims; a single names its release after itself.
	if first.Identity.Source != "manual" || first.Identity.Tier != resolve.TierUser || first.Identity.IDs.MBRecording != "" ||
		first.Identity.Release != "drift" {
		t.Fatalf("%+v", first.Identity)
	}
	e.s.Correct(ctx, e.alice, id, resolve.Result{Artist: "barnacle boi", Title: "drift (VIP)"})
	hist, _ := e.s.History(ctx, 10)
	if err := e.s.Revert(ctx, e.admin, hist[1].ID); err == nil || !strings.Contains(err.Error(), "later change") {
		t.Fatal("history must be undone in order:", err)
	}
	if err := e.s.Revert(ctx, e.admin, hist[0].ID); err != nil {
		t.Fatal(err)
	}
}

func TestRevertPermissionsAndMerges(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	a := e.add(t, "a", "Deadcrow", "Hollow", e.admin.ID, e.alice.ID)
	b := e.add(t, "b", "deadcrow", "hollow rip", e.alice.ID)

	e.s.Correct(ctx, e.admin, a, resolve.Result{Artist: "Deadcrow", Title: "Hollow"})
	hist, _ := e.s.History(ctx, 10)
	if err := e.s.Revert(ctx, e.alice, hist[0].ID); err != ErrForbidden {
		t.Fatal("alice can't revert the admin's correction:", err)
	}

	merged, err := e.s.Correct(ctx, e.alice, b, resolve.Result{Artist: "Deadcrow", Title: "Hollow"})
	if err != nil || merged.TrackID != "tr-1" || len(merged.Files) != 2 {
		t.Fatal(merged, err)
	}
	hist, _ = e.s.History(ctx, 10)
	if !hist[0].Merged || hist[0].Revertible {
		t.Fatalf("%+v", hist[0])
	}
	if err := e.s.Revert(ctx, e.admin, hist[0].ID); err == nil || !strings.Contains(err.Error(), "merged") {
		t.Fatal(err)
	}
}

func TestConfirm(t *testing.T) {
	e := setup(t)
	id := e.add(t, "a", "Skeler", "Tides", e.alice.ID)
	item, err := e.s.Confirm(context.Background(), e.alice, id)
	if err != nil || !item.Identity.Reviewed || item.Identity.Artist != "Skeler" {
		t.Fatal(item, err)
	}
	hist, _ := e.s.History(context.Background(), 10)
	if hist[0].Kind != "confirm" || !hist[0].Revertible {
		t.Fatalf("%+v", hist[0])
	}
}

func TestReresolveAppliesOnlyConfidentMatches(t *testing.T) {
	e := setup(t)
	known := e.add(t, "a", "Skeler", "Tides", e.alice.ID)
	unknown := e.add(t, "b", "plenka", "glass", e.alice.ID)
	if err := e.s.Reresolve(nil); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for e.s.Progress().Running && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	p := e.s.Progress()
	if p.Total != 2 || p.Done != 2 || p.Improved != 1 || p.Error != "" {
		t.Fatalf("%+v", p)
	}
	ctx := context.Background()
	k, _ := e.s.Ingester.Identity(ctx, known)
	u, _ := e.s.Ingester.Identity(ctx, unknown)
	if k.IDs.MBRecording != "rec-tides" || !k.Reviewed || u.Reviewed {
		t.Fatalf("%+v / %+v", k, u)
	}
	hist, _ := e.s.History(ctx, 10)
	if len(hist) != 1 || hist[0].Kind != "reresolve" || hist[0].Who != "Cosine" || !hist[0].Revertible {
		t.Fatalf("%+v", hist)
	}
}
