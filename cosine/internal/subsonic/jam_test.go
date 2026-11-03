package subsonic

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/jams"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

func TestAJamLetsItsPeopleHearWhatTheyDontHave(t *testing.T) {
	// Bob doesn't have alice's song; in her jam he hears it anyway, and
	// only through the jam (§4C).
	e := setup(t)
	e.api.Jams = &jams.Service{DB: e.db}
	ctx := context.Background()
	song := "tr-" + strconv.FormatInt(e.add(t, testutil.MP3{Title: "Tides", Artist: "Skeler", Body: "a"}, e.alice.ID).TrackID, 10)
	status := func(params url.Values) int {
		res, err := http.Get(e.url("bob", "hunter22", "stream", params))
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		// A Subsonic error is a 200 with an error in the body: not the file.
		if ct := res.Header.Get("Content-Type"); strings.HasPrefix(ct, "application/json") || strings.HasPrefix(ct, "text/xml") {
			return http.StatusNotFound
		}
		return res.StatusCode
	}
	if status(url.Values{"id": {song}}) == http.StatusOK {
		t.Fatal("bob streamed a song he doesn't have")
	}
	jam, _ := e.api.Jams.Start(ctx, e.alice.ID, []string{"bob"})
	e.api.Jams.Add(ctx, jam, e.alice.ID, []string{song})
	j := strconv.FormatInt(jam, 10)
	if status(url.Values{"id": {song}, "jam": {j}}) == http.StatusOK {
		t.Fatal("asked, not joined, yet he heard it")
	}
	e.api.Jams.Join(ctx, jam, e.bob.ID)
	if got := status(url.Values{"id": {song}, "jam": {j}}); got != http.StatusOK {
		t.Fatal("in the jam he hears it:", got)
	}
}
