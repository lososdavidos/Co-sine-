package subsonic

import (
	"context"
	"net/url"
	"strconv"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/devices"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestPlayQueueIsTheLatestSession(t *testing.T) {
	// A Subsonic app's saved queue is its Device's session; getPlayQueue
	// picks up whichever was changed last, Sine's included (§4A).
	e := setup(t)
	e.api.Devices = &devices.Service{DB: e.db}
	a := e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "a"}, e.alice.ID).TrackID
	b := e.add(t, testutil.MP3{Title: "Night", Artist: "Skeler", Body: "b"}, e.alice.ID).TrackID
	tr := func(id int64) string { return "tr-" + strconv.FormatInt(id, 10) }

	if r := ok(t, e.get(t, "alice", "open sesame", "getPlayQueue", nil)); r["playQueue"] != nil {
		t.Fatal("nothing saved yet:", r)
	}
	ok(t, e.get(t, "alice", "open sesame", "savePlayQueue", url.Values{"id": {tr(a), tr(b)}, "current": {tr(b)}, "position": {"61000"}}))
	q := ok(t, e.get(t, "alice", "open sesame", "getPlayQueue", nil))["playQueue"].(map[string]any)
	if q["current"] != tr(b) || q["position"] != float64(61000) || q["changedBy"] != "test" || len(q["entry"].([]any)) != 2 {
		t.Fatal(q)
	}
	// Sine plays something else later: that's what an app picks up now.
	e.api.Devices.Save(context.Background(), e.alice.ID, devices.Device{ID: "sine-1", Name: "Pixel 8", Kind: "phone"},
		devices.Session{Queue: []string{tr(a)}, PositionMs: 5000})
	q = ok(t, e.get(t, "alice", "open sesame", "getPlayQueue", nil))["playQueue"].(map[string]any)
	if q["current"] != tr(a) || q["changedBy"] != "Pixel 8" {
		t.Fatal(q)
	}
	// Bob's are his own.
	if r := ok(t, e.get(t, "bob", "hunter22", "getPlayQueue", nil)); r["playQueue"] != nil {
		t.Fatal(r)
	}
}
