package stats

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

var now = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)

func day(y int, m time.Month, d int) int64 {
	return time.Date(y, m, d, 20, 0, 0, 0, time.UTC).UnixMilli()
}

type env struct {
	s                    *Service
	db                   *db.DB
	alice, bob           int64
	tides, hollow, glass int64
	old                  int64
	root                 string
	glassPath            string
}

// alice: Tides 3× this month and once a year ago; Hollow twice in
// February and once in 2024; Old twice in January 2025; glass never.
// bob: Hollow once this month.
func setup(t *testing.T) *env {
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	root := filepath.Join(dir, "store")
	d.SetSetting(ctx, db.SettingStorePath, root)
	user := func(n string) int64 {
		res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", n)
		id, _ := res.LastInsertId()
		return id
	}
	e := &env{db: d, alice: user("alice"), bob: user("bob"), root: root,
		s: &Service{DB: d, Now: func() time.Time { return now }}}
	g := &ingest.Ingester{DB: d, DataDir: dir, ReviewThreshold: 0.8, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}, Probe: func(string) int { return 200 }}
	add := func(title, artist string) (int64, string) {
		out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: title, Artist: artist, Body: title}.
			Write(t, filepath.Join(dir, title+".mp3")), Source: "inbox"})
		if err != nil {
			t.Fatal(err)
		}
		return out.TrackID, out.RelPath
	}
	e.tides, _ = add("Tides", "Skeler")
	e.hollow, _ = add("Hollow", "Deadcrow")
	e.old, _ = add("Old", "rxrrim")
	e.glass, e.glassPath = add("glass", "plenka")
	play := func(u, track, at int64) {
		if _, err := d.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, ?)", u, track, at); err != nil {
			t.Fatal(err)
		}
	}
	for _, at := range []int64{day(2026, 10, 3), day(2026, 10, 20), day(2026, 10, 24), day(2025, 10, 27)} {
		play(e.alice, e.tides, at)
	}
	for _, at := range []int64{day(2026, 2, 10), day(2026, 2, 11), day(2024, 6, 1)} {
		play(e.alice, e.hollow, at)
	}
	play(e.alice, e.old, day(2025, 1, 5))
	play(e.alice, e.old, day(2025, 1, 6))
	play(e.bob, e.hollow, day(2026, 10, 22))
	return e
}

func window(t *testing.T, key string) Window {
	w, err := ParseWindow(key, now)
	if err != nil {
		t.Fatal(err)
	}
	return w
}

func TestReport(t *testing.T) {
	e := setup(t)
	ctx := context.Background()

	r, err := e.s.Report(ctx, Scope{e.alice}, window(t, "30d"), 10)
	if err != nil {
		t.Fatal(err)
	}
	if r.Summary != (Summary{Plays: 3, Seconds: 600, Tracks: 1, Artists: 1}) {
		t.Fatalf("%+v", r.Summary)
	}
	if len(r.Tracks) != 1 || r.Tracks[0].Name != "Tides" || r.Tracks[0].Artist != "Skeler" || r.Tracks[0].Plays != 3 ||
		!r.Tracks[0].FirstPlayed.Equal(time.UnixMilli(day(2025, 10, 27))) {
		t.Fatalf("first played is ever, not in the window: %+v", r.Tracks)
	}
	if len(r.OverTime) != 30 || r.OverTime[0].Label != "Sep 26" || r.OverTime[29].Label != "Oct 25" {
		t.Fatalf("a bucket per day, empty ones included: %d, %+v", len(r.OverTime), r.OverTime[0])
	}
	sum := 0
	for _, b := range r.OverTime {
		sum += b.Plays
	}
	if sum != 3 {
		t.Fatal(sum)
	}

	// Everyone: bob's play counts too.
	r, _ = e.s.Report(ctx, Scope{}, window(t, "30d"), 10)
	if r.Summary.Plays != 4 || len(r.Tracks) != 2 || r.Tracks[1].Name != "Hollow" {
		t.Fatalf("%+v", r)
	}

	r, _ = e.s.Report(ctx, Scope{e.alice}, window(t, "year"), 10)
	if r.Summary.Plays != 5 || len(r.OverTime) != 10 || r.OverTime[1].Plays != 2 || r.OverTime[1].Label != "Feb 2026" {
		t.Fatalf("%+v", r)
	}
	r, _ = e.s.Report(ctx, Scope{e.alice}, window(t, "all"), 10)
	if r.Summary.Plays != 9 || len(r.OverTime) != 3 || r.OverTime[0].Label != "2024" || len(r.Artists) != 3 || len(r.Albums) != 3 {
		t.Fatalf("%+v", r)
	}
	if r.Artists[0].Name != "Skeler" || r.Artists[0].ID != "ar-"+itoa(artistOf(t, e, e.tides)) {
		t.Fatalf("%+v", r.Artists)
	}
}

func TestYearsAndWindows(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	ys, err := e.s.Years(ctx, Scope{e.alice})
	if err != nil || len(ys) != 3 {
		t.Fatal(ys, err)
	}
	if ys[0].Year != 2026 || ys[1].Year != 2025 || ys[1].TopArtist.Name != "rxrrim" || ys[2].TopTrack.Name != "Hollow" {
		t.Fatalf("%+v", ys)
	}
	ws, _ := e.s.Windows(ctx, Scope{e.alice})
	var keys []string
	for _, w := range ws {
		keys = append(keys, w.Key)
	}
	if want := "30d year 2025 2024 all"; join(keys) != want {
		t.Fatal(keys)
	}
	if ys, _ := e.s.Years(ctx, Scope{User: 999}); len(ys) != 0 {
		t.Fatal("no plays, no years")
	}
	for _, bad := range []string{"2030", "abc", "1900"} {
		if _, err := ParseWindow(bad, now); err != ErrWindow {
			t.Error(bad, err)
		}
	}
}

func TestViews(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	names := func(name string, opt ViewOptions) string {
		ts, err := e.s.View(ctx, e.alice, name, opt)
		if err != nil {
			t.Fatal(name, err)
		}
		var out []string
		for _, tr := range ts {
			out = append(out, tr.Title)
		}
		return join(out)
	}
	if got := names(ViewMostPlayed, ViewOptions{}); got != "Tides Hollow Old" {
		t.Error("most played:", got)
	}
	if got := names(ViewMostPlayed, ViewOptions{Window: window(t, "2025")}); got != "Old Tides" {
		t.Error("most played in 2025:", got)
	}
	if got := names(ViewRecent, ViewOptions{Limit: 2}); got != "Tides Hollow" {
		t.Error("recent:", got)
	}
	if got := names(ViewForgotten, ViewOptions{}); got != "Hollow Old" {
		t.Error("forgotten:", got)
	}
	if got := names(ViewForgotten, ViewOptions{Months: 12}); got != "Old" {
		t.Error("forgotten for a year:", got)
	}
	if got := names(ViewNeverPlayed, ViewOptions{}); got != "glass" {
		t.Error("never played:", got)
	}
	if got := names(ViewLastYear, ViewOptions{}); got != "Tides" {
		t.Error("this time last year:", got)
	}
	if _, err := e.s.View(ctx, e.alice, "nope", ViewOptions{}); err != ErrView {
		t.Fatal(err)
	}
	// A Track with no file left isn't offered (§2.2).
	os.Remove(filepath.Join(e.root, e.glassPath))
	e.db.Exec("UPDATE objects SET missing = 1 WHERE track_id = ?", e.glass)
	if got := names(ViewNeverPlayed, ViewOptions{}); got != "" {
		t.Error("a missing track was offered:", got)
	}
	// Views are one account's own: bob never played Tides.
	ts, _ := e.s.View(ctx, e.bob, ViewMostPlayed, ViewOptions{})
	if len(ts) != 1 || ts[0].Title != "Hollow" || ts[0].Plays != 1 {
		t.Fatalf("%+v", ts)
	}
}

func artistOf(t *testing.T, e *env, track int64) int64 {
	var a int64
	if err := e.db.QueryRow("SELECT artist_id FROM tracks WHERE id = ?", track).Scan(&a); err != nil {
		t.Fatal(err)
	}
	return a
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func join(s []string) string { return strings.Join(s, " ") }
