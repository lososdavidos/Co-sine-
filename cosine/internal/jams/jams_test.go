package jams

import (
	"context"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

type env struct {
	s                 *Service
	db                *db.DB
	alice, bob, carol int64
	t1, t2, t3        string
	clock             *int64
}

func setup(t *testing.T) *env {
	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	user := func(n string) int64 {
		res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES(?, x'00', 0)", n)
		id, _ := res.LastInsertId()
		return id
	}
	e := &env{db: d, alice: user("alice"), bob: user("bob"), carol: user("carol")}
	d.Exec("INSERT INTO artists(id, name) VALUES(1, 'Skeler')")
	d.Exec("INSERT INTO releases(id, artist_id, title, created_at) VALUES(1, 1, 'Tides', 0)")
	track := func(id int64, title string, sec int) string {
		if _, err := d.Exec("INSERT INTO tracks(id, release_id, artist_id, title, resolver, tier, confidence, created_at) VALUES(?, 1, 1, ?, 'tags', 1, 1, 0)", id, title); err != nil {
			t.Fatal(err)
		}
		if _, err := d.Exec("INSERT INTO objects(hash, track_id, rel_path, size, suffix, content_type, duration_sec, source, created_at) VALUES(?, ?, ?, 1, 'mp3', 'audio/mpeg', ?, 'upload', 0)",
			title, id, title+".mp3", sec); err != nil {
			t.Fatal(err)
		}
		return "tr-" + itoa(id)
	}
	e.t1, e.t2, e.t3 = track(1, "One", 100), track(2, "Two", 200), track(3, "Three", 50)
	// Alice and Bob have every song; Carol has none.
	d.Exec("INSERT INTO pointers(user_id, track_id, added_at) SELECT u.id, t.id, 0 FROM users u, tracks t WHERE u.id IN (?, ?)", e.alice, e.bob)
	now := int64(1_000_000)
	e.clock = &now
	e.s = &Service{DB: d, Now: func() time.Time { return time.UnixMilli(*e.clock) }}
	return e
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func (e *env) after(ms int64) { *e.clock += ms }

func TestAJamFromStartToEnd(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, err := e.s.Start(ctx, e.alice, []string{"bob"})
	if err != nil {
		t.Fatal(err)
	}
	// Bob's asked, not in yet: he sees the invitation, can't add.
	_, invites, _ := e.s.Mine(ctx, e.bob)
	if len(invites) != 1 || invites[0].Host != "alice" {
		t.Fatal(invites)
	}
	if err := e.s.Add(ctx, jam, e.bob, []string{e.t1}); err != ErrNotIn {
		t.Fatal(err)
	}
	if err := e.s.Join(ctx, jam, e.bob); err != nil {
		t.Fatal(err)
	}
	// Anyone adds; the first song starts.
	e.s.Add(ctx, jam, e.bob, []string{e.t1, e.t2})
	j, _ := e.s.State(ctx, jam, e.alice, 0, 0)
	if len(j.Queue) != 2 || j.Queue[0].AddedBy != "bob" || !j.Playing || j.Current != 0 || j.Host != "alice" {
		t.Fatalf("%+v", j)
	}
	// Time goes by: 130 s in is 30 s into the second song, for everyone.
	e.after(130_000)
	j, _ = e.s.State(ctx, jam, e.bob, 0, 0)
	if j.Current != 1 || j.PositionMs != 30_000 {
		t.Fatal(j.Current, j.PositionMs)
	}
	// Only the host plays, pauses and seeks.
	if err := e.s.Transport(ctx, jam, e.bob, "pause", 0, 0); err != ErrNotHost {
		t.Fatal(err)
	}
	e.s.Transport(ctx, jam, e.alice, "pause", 0, 0)
	e.after(60_000)
	j, _ = e.s.State(ctx, jam, e.bob, 0, 0)
	if j.Playing || j.Current != 1 || j.PositionMs != 30_000 {
		t.Fatal("paused stays put:", j.Current, j.PositionMs, j.Playing)
	}
	e.s.Transport(ctx, jam, e.alice, "skip", 0, 0)
	j, _ = e.s.State(ctx, jam, e.bob, 0, 0)
	if j.Current != 0 || !j.Playing || j.PositionMs != 0 {
		t.Fatal(j)
	}
	// Past the last song it stops at its end; a song added then plays.
	e.after(400_000)
	j, _ = e.s.State(ctx, jam, e.bob, 0, 0)
	if j.Playing || j.Current != 1 {
		t.Fatal("ran out:", j.Current, j.Playing)
	}
	e.s.Add(ctx, jam, e.alice, []string{e.t3})
	j, _ = e.s.State(ctx, jam, e.bob, 0, 0)
	if !j.Playing || j.Current != 2 || j.PositionMs != 0 {
		t.Fatal("the new song plays:", j.Current, j.PositionMs, j.Playing)
	}
	// Someone in it hears its songs; someone not in it doesn't.
	if !e.s.Hears(ctx, jam, e.bob, 3) || e.s.Hears(ctx, jam, e.carol, 3) || e.s.Hears(ctx, jam, e.bob, 99) {
		t.Fatal("hearing")
	}
	// The host leaving ends it for everyone.
	e.s.Leave(ctx, jam, e.alice)
	if cur, _, _ := e.s.Mine(ctx, e.bob); cur != nil {
		t.Fatal("still in an ended jam")
	}
	if e.s.Hears(ctx, jam, e.bob, 3) {
		t.Fatal("an ended jam still plays")
	}
}

func TestAJamOnlyTakesSongsSomeoneInItBrought(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.carol, []string{"alice"})
	// Carol has no songs: guessing ids gets her nothing.
	e.s.Add(ctx, jam, e.carol, []string{e.t1, e.t2, e.t3})
	if j, _ := e.s.State(ctx, jam, e.carol, 0, 0); len(j.Queue) != 0 || e.s.InMine(ctx, e.carol, 1) {
		t.Fatalf("carol added songs she doesn't have: %+v", j.Queue)
	}
	// Alice brings one; Carol may add it again, and keep it.
	e.s.Join(ctx, jam, e.alice)
	e.s.Add(ctx, jam, e.alice, []string{e.t1})
	e.s.Add(ctx, jam, e.carol, []string{e.t1, e.t2})
	if j, _ := e.s.State(ctx, jam, e.carol, 0, 0); len(j.Queue) != 2 || j.Queue[1].AddedBy != "carol" {
		t.Fatalf("%+v", j.Queue)
	}
	if !e.s.InMine(ctx, e.carol, 1) || e.s.InMine(ctx, e.carol, 2) {
		t.Fatal("keep it: only a song in her jam")
	}
	e.s.Leave(ctx, jam, e.carol)
	if e.s.InMine(ctx, e.carol, 1) {
		t.Fatal("keep it after the jam ended")
	}
}

func TestAWaitingListenerHearsAChangeAtOnce(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.alice, nil)
	j, _ := e.s.State(ctx, jam, e.alice, 0, 0)
	got := make(chan Jam)
	go func() {
		next, _ := e.s.State(ctx, jam, e.alice, j.Version, 5*time.Second)
		got <- next
	}()
	time.Sleep(50 * time.Millisecond)
	e.s.Add(ctx, jam, e.alice, []string{e.t1})
	select {
	case next := <-got:
		if next.Version <= j.Version || len(next.Queue) != 1 {
			t.Fatalf("%+v", next)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("waited it out")
	}
}

func TestDeletingAnAccountThatAddedToAJam(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.alice, []string{"bob"})
	e.s.Join(ctx, jam, e.bob)
	e.s.Add(ctx, jam, e.bob, []string{e.t1})
	e.s.End(ctx, jam, e.alice)
	as, err := auth.New(e.db, make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	if err := as.DeleteUser(ctx, e.bob); err != nil {
		t.Fatal("an account that once added to a jam can't be deleted:", err)
	}
}

func TestTheHostJoiningAgainKeepsTheJam(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.alice, []string{"bob"})
	if err := e.s.Join(ctx, jam, e.alice); err != nil {
		t.Fatal(err)
	}
	if cur, _, _ := e.s.Mine(ctx, e.alice); cur == nil {
		t.Fatal("joining her own jam again ended it")
	}
}

func TestInvitationsCanBeDeclinedAndRepeated(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.alice, []string{"bob"})
	// Bob turns it down: it's gone, for him and from the member list.
	e.s.Leave(ctx, jam, e.bob)
	if _, invites, _ := e.s.Mine(ctx, e.bob); len(invites) != 0 {
		t.Fatal("a declined invitation is still there:", invites)
	}
	if j, _ := e.s.State(ctx, jam, e.alice, 0, 0); len(j.Members) != 1 {
		t.Fatalf("declined, still listed: %+v", j.Members)
	}
	// He joins after all, leaves, and is asked again: an invitation, not back in.
	e.s.Invite(ctx, jam, e.alice, []string{"bob"})
	e.s.Join(ctx, jam, e.bob)
	e.s.Leave(ctx, jam, e.bob)
	e.s.Invite(ctx, jam, e.alice, []string{"bob"})
	if cur, invites, _ := e.s.Mine(ctx, e.bob); cur != nil || len(invites) != 1 {
		t.Fatal("asked again should be an invitation:", cur, invites)
	}
}

func TestAddingAtTheSameMomentLosesNothing(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	jam, _ := e.s.Start(ctx, e.alice, []string{"bob"})
	e.s.Join(ctx, jam, e.bob)
	errs := make(chan error, 2)
	for _, u := range []int64{e.alice, e.bob} {
		go func(u int64) { errs <- e.s.Add(ctx, jam, u, []string{e.t1, e.t2, e.t3}) }(u)
	}
	for range 2 {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	if j, _ := e.s.State(ctx, jam, e.alice, 0, 0); len(j.Queue) != 6 {
		t.Fatal("songs lost:", len(j.Queue))
	}
}
