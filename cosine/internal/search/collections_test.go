package search

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

func TestAlbumsAndPlaylistsFromEverySource(t *testing.T) {
	q := "halsey hopeless fountain kingdom"
	album := "https://music.youtube.com/browse/MPREb_hfk"
	bin, _ := testutil.FakeYtDlp(t, map[string]any{
		MusicAlbumsURL(q): map[string]any{"_type": "playlist", "entries": []map[string]any{{"_type": "url", "url": album}}},
		album: map[string]any{"_type": "playlist", "id": "OLAK5uy_hfk", "title": "Album - hopeless fountain kingdom",
			"channel": "Halsey - Topic", "webpage_url": "https://www.youtube.com/playlist?list=OLAK5uy_hfk", "extractor_key": "YoutubeTab",
			"entries": []map[string]any{{"url": "a"}, {"url": "b"}, {"url": "c"}}},
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
	got := s.Collections(ctx, q)
	want := []string{"album:hopeless fountain kingdom", "playlist:Halsey - hopeless fountain kingdom (Full Album)", "album:Tides EP", "playlist:gym mix"}
	if len(got) != len(want) {
		t.Fatal(titles(got))
	}
	for i := range want {
		if titles(got)[i] != want[i] {
			t.Fatal(titles(got))
		}
	}
	first := got[0]
	if first.Uploader != "Halsey" || first.Tracks != 3 || first.Source != SourceYouTubeMusic || !first.Asked ||
		first.URL != "https://www.youtube.com/playlist?list=OLAK5uy_hfk" || got[3].Asked {
		t.Fatalf("%+v", got)
	}

	d.SetSetting(ctx, db.SettingSoundCloudID, "cid")
	got = s.Collections(ctx, q)
	if len(got) != 6 || got[1].Source != "soundcloud" || got[1].Kind != KindAlbum || got[1].Tracks != 13 || got[5].Title != "sad girl autumn" {
		t.Fatal(titles(got))
	}
}
