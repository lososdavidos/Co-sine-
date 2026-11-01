package devices

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

func TestSessions(t *testing.T) {
	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES('alice', x'00', 0)")
	alice, _ := res.LastInsertId()
	at := time.UnixMilli(1000)
	s := &Service{DB: d, Now: func() time.Time { return at }}

	phone := Device{ID: "sine-a", Name: "Pixel 8", Kind: "phone"}
	if err := s.Save(ctx, alice, phone, Session{Queue: []string{"tr-1", "nope", "tr-2"}, Current: 1, PositionMs: 30000, Playing: true}); err != nil {
		t.Fatal(err)
	}
	got, err := s.Session(ctx, alice, "sine-a")
	if err != nil || len(got.Queue) != 2 || got.Current != 1 || got.PositionMs != 30000 || !got.Playing {
		t.Fatal(got, err)
	}
	// Each Device its own: a second doesn't touch the first's.
	at = time.UnixMilli(2000)
	s.Save(ctx, alice, Device{ID: "sine-b", Name: "Tablet", Kind: "phone"}, Session{Queue: []string{"tr-3"}})
	if again, _ := s.Session(ctx, alice, "sine-a"); len(again.Queue) != 2 {
		t.Fatal(again)
	}
	now, _ := s.Playing(ctx, alice)
	if len(now) != 2 || now[0].Device.Name != "Tablet" || now[1].Track != "tr-2" || now[1].Songs != 2 {
		t.Fatalf("%+v", now)
	}
	if _, d, _ := s.Latest(ctx, alice); d.Name != "Tablet" {
		t.Fatal(d)
	}
	// A current out of range is the start; a bad device is refused.
	s.Save(ctx, alice, phone, Session{Queue: []string{"tr-1"}, Current: 9})
	if got, _ := s.Session(ctx, alice, "sine-a"); got.Current != 0 {
		t.Fatal(got)
	}
	if err := s.Save(ctx, alice, Device{ID: "x", Name: "x", Kind: "toaster"}, Session{}); err != ErrDevice {
		t.Fatal(err)
	}
	// A volume is kept, held between 0 and 1, and outlives a save that doesn't say one.
	loud := 1.4
	s.Save(ctx, alice, phone, Session{Queue: []string{"tr-1"}, Volume: &loud})
	s.Save(ctx, alice, phone, Session{Queue: []string{"tr-2"}})
	if got, _ := s.Session(ctx, alice, "sine-a"); got.Volume == nil || *got.Volume != 1 {
		t.Fatal(got.Volume)
	}
	if now, _ := s.Playing(ctx, alice); now[0].Volume == nil || *now[0].Volume != 1 {
		t.Fatalf("%+v", now[0])
	}
	if err := s.Forget(ctx, alice, "sine-b"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Session(ctx, alice, "sine-b"); err != ErrNotFound {
		t.Fatal(err)
	}
}
