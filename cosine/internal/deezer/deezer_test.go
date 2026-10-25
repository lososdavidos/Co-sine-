package deezer

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

func TestArtistLinksAndPictures(t *testing.T) {
	for link, want := range map[string]int64{
		"https://www.deezer.com/artist/5292512":    5292512,
		"https://www.deezer.com/fr/artist/27?x=1":  27,
		"http://deezer.com/artist/9":               9,
		"https://www.deezer.com/album/302127":      0,
		"https://open.spotify.com/artist/26VFTg2z": 0,
	} {
		if id, ok := ArtistID(link); id != want || ok != (want != 0) {
			t.Errorf("%s: %d %v", link, id, ok)
		}
	}
	if (Artist{PictureXL: "https://cdn-images.dzcdn.net/images/artist//1000x1000-000000-80-0-0.jpg"}).Picture() != "" {
		t.Error("Deezer's placeholder is not a picture")
	}
}

func TestErrors(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/artist/1":
			w.Write([]byte(`{"error":{"type":"DataException","message":"no data","code":800}}`))
		default:
			w.Write([]byte(`{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}`))
		}
	}))
	defer srv.Close()
	c := New(srv.Client(), "test")
	c.BaseURL = srv.URL
	c.Web.Pace = func() time.Duration { return time.Millisecond }
	if _, err := c.Artist(context.Background(), 1); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := c.SearchArtists(context.Background(), "x"); !errors.Is(err, resolve.ErrUnavailable) {
		t.Fatal(err)
	}
}
