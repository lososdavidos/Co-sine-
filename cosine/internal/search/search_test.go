package search

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

func TestLooksLikeURL(t *testing.T) {
	cases := map[string]string{
		"https://soundcloud.com/skeler/tides":                                      "https://soundcloud.com/skeler/tides",
		"Listen to Tides by Skeler on #SoundCloud https://on.soundcloud.com/AbC12": "https://on.soundcloud.com/AbC12",
		"soundcloud.com/skeler/tides":                                              "https://soundcloud.com/skeler/tides",
		"skeler tides":                                                             "",
		"v1.2":                                                                     "",
	}
	for in, want := range cases {
		got, ok := LooksLikeURL(in)
		if got != want || ok != (want != "") {
			t.Errorf("%q → %q %v", in, got, ok)
		}
	}
}

func TestSoundCloudAndYouTubeAPIs(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/search/tracks":
			if r.URL.Query().Get("client_id") != "cid" {
				w.WriteHeader(401)
				return
			}
			w.Write([]byte(`{"collection":[{"title":"Tides","permalink_url":"https://soundcloud.com/skeler/tides","duration":201400,"playback_count":5000,"artwork_url":"https://i1.sndcdn.com/a-large.jpg","user":{"username":"Skeler"}}]}`))
		case r.URL.Path == "/search":
			w.Write([]byte(`{"items":[{"id":{"videoId":"abc"},"snippet":{"title":"Skeler - Tides","channelTitle":"wave","thumbnails":{"high":{"url":"https://i.ytimg.com/hq.jpg"}}}}]}`))
		case r.URL.Path == "/videos":
			w.Write([]byte(`{"items":[{"id":"abc","contentDetails":{"duration":"PT3M21S"},"statistics":{"viewCount":"12345"}}]}`))
		default:
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	api := SourceAPIs{
		SoundCloud: &SoundCloudAPI{ClientID: "cid", HTTP: srv.Client(), BaseURL: srv.URL},
		YouTube:    &YouTubeAPI{Key: "k", HTTP: srv.Client(), BaseURL: srv.URL},
	}
	res, err := api.Search(context.Background(), "tides", 5)
	if err != nil || len(res) != 2 {
		t.Fatal(res, err)
	}
	sc, yt := res[0], res[1]
	if sc.Source != "soundcloud" || sc.DurationSec != 201 || sc.Plays != 5000 || !strings.Contains(sc.Thumbnail, "t500x500") {
		t.Errorf("%+v", sc)
	}
	if yt.URL != "https://www.youtube.com/watch?v=abc" || yt.DurationSec != 201 || yt.Plays != 12345 {
		t.Errorf("%+v", yt)
	}

	// One side failing still returns the other.
	api.SoundCloud.ClientID = "wrong"
	res, err = api.Search(context.Background(), "tides", 5)
	if err != nil || len(res) != 1 || res[0].Source != "youtube" {
		t.Fatal(res, err)
	}
}

type fake struct {
	res []Result
	err error
}

func (f fake) Search(context.Context, string, int) ([]Result, error) { return f.res, f.err }

func TestServiceFallsBackSilently(t *testing.T) {
	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	ctx := context.Background()
	ytdlp := fake{res: []Result{{Title: "from yt-dlp"}}}
	var apiErr error
	s := &Service{DB: d, YtDlp: ytdlp, NewAPI: func(sc, yt string) Backend {
		if sc == "" && yt == "" {
			return nil
		}
		return fake{res: []Result{{Title: "from api"}}, err: apiErr}
	}}

	_, backend, _ := s.Search(ctx, "q", 5)
	if backend != BackendYtDlp {
		t.Fatal("default backend is yt-dlp")
	}

	d.SetSetting(ctx, db.SettingSearchBackend, BackendAPI)
	_, backend, _ = s.Search(ctx, "q", 5)
	if backend != BackendYtDlp || !strings.Contains(s.Status(), "no keys") {
		t.Fatal("no keys should fall back and say so:", s.Status())
	}

	d.SetSetting(ctx, db.SettingSoundCloudID, "cid")
	res, backend, _ := s.Search(ctx, "q", 5)
	if backend != BackendAPI || res[0].Title != "from api" {
		t.Fatal(backend, res)
	}

	apiErr = errors.New("HTTP 403")
	res, backend, err = s.Search(ctx, "q", 5)
	if err != nil || backend != BackendYtDlp || res[0].Title != "from yt-dlp" || !strings.Contains(s.Status(), "HTTP 403") {
		t.Fatal("API failure must degrade to yt-dlp:", backend, s.Status())
	}
}

func TestISODuration(t *testing.T) {
	for in, want := range map[string]int{"PT3M21S": 201, "PT1H": 3600, "PT45S": 45, "P1D": 0} {
		if got := isoDuration(in); got != want {
			t.Errorf("%s = %d", in, got)
		}
	}
}
