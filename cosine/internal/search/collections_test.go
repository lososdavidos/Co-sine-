package search

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

func TestAlbumsAndPlaylistsFromEverySource(t *testing.T) {
	q := "halsey hopeless fountain kingdom"
	bin, _ := testutil.FakeYtDlp(t, map[string]any{
		YouTubePlaylistsURL(q): map[string]any{"_type": "playlist", "entries": []map[string]any{
			{"_type": "url", "url": "https://www.youtube.com/playlist?list=PLgym", "title": "gym mix", "uploader": "someone"},
			{"_type": "url", "url": "https://www.youtube.com/playlist?list=PLfan", "title": "Halsey - hopeless fountain kingdom (Full Album)", "uploader": "a fan"},
		}},
	})
	sc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/search/albums":
			w.Write([]byte(`{"collection":[{"title":"hopeless fountain kingdom","permalink_url":"https://soundcloud.com/halseymusic/sets/hfk","track_count":13,"set_type":"album","artwork_url":"https://i1.sndcdn.com/x-large.jpg","user":{"username":"Halsey"}}]}`))
		case "/search/playlists_without_albums":
			w.Write([]byte(`{"collection":[{"title":"sad girl autumn","permalink_url":"https://soundcloud.com/x/sets/sad","track_count":40,"set_type":"","user":{"username":"x"}}]}`))
		default:
			w.WriteHeader(404)
		}
	}))
	defer sc.Close()

	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	ctx := context.Background()
	s := &Service{DB: d, YtDlp: YtDlpSearch{Runner: ytdlp.Runner{Bin: bin}},
		NewAPI: func(id, _ string) Backend {
			return SourceAPIs{SoundCloud: &SoundCloudAPI{ClientID: id, HTTP: sc.Client(), BaseURL: sc.URL}}
		},
		Bandcamp: func(context.Context, string, int) ([]Result, error) {
			return []Result{{Kind: KindAlbum, Title: "Tides EP", Uploader: "Skeler", URL: "https://skeler.bandcamp.com/album/tides-ep", Source: "bandcamp"}}, nil
		},
	}

	titles := func(rs []Result) (out []string) {
		for _, r := range rs {
			out = append(out, r.Kind+":"+r.Title)
		}
		return out
	}
	// Without a SoundCloud client ID, SoundCloud's sets aren't searched.
	var shown [][]Result
	got := s.Collections(ctx, q, func(r []Result) { shown = append(shown, r) })
	want := []string{"playlist:Halsey - hopeless fountain kingdom (Full Album)", "album:Tides EP", "playlist:gym mix"}
	if strings.Join(titles(got), ", ") != strings.Join(want, ", ") {
		t.Fatal(titles(got))
	}
	if !got[0].Asked || got[1].Asked || got[2].Asked {
		t.Fatalf("%+v", got)
	}
	// Each source shows as it answers, the last showing is all of it.
	if len(shown) != 2 || len(shown[0]) == 3 || strings.Join(titles(shown[1]), ", ") != strings.Join(want, ", ") {
		t.Fatal(shown)
	}

	d.SetSetting(ctx, db.SettingSoundCloudID, "cid")
	got = s.Collections(ctx, q, nil)
	if len(got) != 5 || got[0].Source != "soundcloud" || got[0].Kind != KindAlbum || got[0].Tracks != 13 || got[4].Title != "sad girl autumn" {
		t.Fatal(titles(got))
	}
}
