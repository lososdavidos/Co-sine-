package search

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Result kinds besides a single song (Kind ""): picking one shows its
// songs to add, as pasting its link would.
const (
	KindAlbum    = ytdlp.KindAlbum
	KindPlaylist = ytdlp.KindPlaylist
)

// How many albums and playlists each source offers.
const (
	musicAlbums      = 2
	youTubePlaylists = 3
	bandcampAlbums   = 3
	soundCloudSets   = 3
)

// Collections finds albums and playlists for a query on every source that
// can be searched for them, side by side: YouTube Music's albums and
// YouTube's playlists through yt-dlp, Bandcamp's albums through its own
// search, and SoundCloud's albums and playlists through its API when a
// client ID is set (yt-dlp searches SoundCloud for tracks only). Those
// that are what was asked for come first, albums before playlists.
// Degrades, never fails (Q73).
func (s *Service) Collections(ctx context.Context, query string) []Result {
	var parts [4][]Result
	var wg sync.WaitGroup
	if y, ok := s.YtDlp.(YtDlpSearch); ok {
		wg.Go(func() { parts[0] = y.albums(ctx, query, musicAlbums) })
		wg.Go(func() { parts[1] = y.playlists(ctx, query, youTubePlaylists) })
	}
	if s.Bandcamp != nil {
		wg.Go(func() { parts[2], _ = s.Bandcamp(ctx, query, bandcampAlbums) })
	}
	if id, _ := s.DB.Setting(ctx, db.SettingSoundCloudID); id != "" && s.NewAPI != nil {
		if api, ok := s.NewAPI(id, "").(SourceAPIs); ok && api.SoundCloud != nil {
			wg.Go(func() { parts[3], _ = api.SoundCloud.Sets(ctx, query, soundCloudSets) })
		}
	}
	wg.Wait()
	var all []Result
	for _, p := range parts {
		all = append(all, p...)
	}
	rank := func(r Result) int {
		n := 0
		// A playlist's title often names the artist itself; whoever made
		// it is not the artist.
		if !Asked(query, r) && resolve.Similarity(query, r.Title) < 0.7 {
			n += 2
		}
		if r.Kind != KindAlbum {
			n++
		}
		return n
	}
	out := make([]Result, 0, len(all))
	for want := 0; want < 4; want++ {
		for _, r := range all {
			if rank(r) == want {
				r.Asked = want < 2
				out = append(out, r)
			}
		}
	}
	return out
}

// MusicAlbumsURL is YouTube Music's search, Albums section.
func MusicAlbumsURL(query string) string {
	return "https://music.youtube.com/search?q=" + url.QueryEscape(query) + "#albums"
}

// YouTubePlaylistsURL is YouTube's search filtered to playlists.
func YouTubePlaylistsURL(query string) string {
	return "https://www.youtube.com/results?search_query=" + url.QueryEscape(query) + "&sp=EgIQAw%253D%253D"
}

// albums searches YouTube Music's Albums: a label's own upload of each
// release, track by track. The search names only an id, so each is
// described in full, side by side.
func (y YtDlpSearch) albums(ctx context.Context, query string, n int) []Result {
	found, err := y.Runner.SearchURL(ctx, MusicAlbumsURL(query), n)
	if err != nil {
		return nil
	}
	described := make([]Result, len(found))
	var wg sync.WaitGroup
	for i, e := range found {
		wg.Go(func() {
			info, err := y.Runner.Probe(ctx, e.Link())
			if err != nil || len(info.Entries) == 0 {
				return
			}
			described[i] = Result{
				Kind: info.Kind(), Title: info.CollectionTitle(), Uploader: resolve.CleanArtist(info.CollectionArtist()),
				URL: info.Link(), Thumbnail: info.CoverURL(), Source: SourceYouTubeMusic, Tracks: len(info.Entries),
			}
		})
	}
	wg.Wait()
	var out []Result
	for _, r := range described {
		if r.URL != "" && r.Kind == KindAlbum {
			out = append(out, r)
		}
	}
	return out
}

// playlists searches YouTube for playlists. Many are someone's copy of an
// album; they are offered as what they are, playlists.
func (y YtDlpSearch) playlists(ctx context.Context, query string, n int) []Result {
	found, err := y.Runner.SearchURL(ctx, YouTubePlaylistsURL(query), n)
	if err != nil {
		return nil
	}
	var out []Result
	for _, e := range found {
		if e.Link() == "" || e.Title == "" {
			continue
		}
		out = append(out, Result{
			Kind: KindPlaylist, Title: e.Title, Uploader: e.Poster(), URL: e.Link(),
			Thumbnail: e.CoverURL(), Source: "youtube",
		})
	}
	return out
}

// Sets searches SoundCloud's albums and playlists. A set marked as an
// album, EP or single is an album; any other is a playlist.
func (a *SoundCloudAPI) Sets(ctx context.Context, q string, n int) ([]Result, error) {
	base := a.BaseURL
	if base == "" {
		base = "https://api-v2.soundcloud.com"
	}
	var parts [2][]Result
	var errs [2]error
	var wg sync.WaitGroup
	for i, path := range []string{"/search/albums", "/search/playlists_without_albums"} {
		wg.Go(func() {
			u := base + path + "?" + url.Values{
				"q": {q}, "limit": {strconv.Itoa(n)}, "client_id": {a.ClientID},
			}.Encode()
			var body struct {
				Collection []struct {
					Title        string `json:"title"`
					PermalinkURL string `json:"permalink_url"`
					TrackCount   int    `json:"track_count"`
					ArtworkURL   string `json:"artwork_url"`
					SetType      string `json:"set_type"`
					User         struct {
						Username string `json:"username"`
					} `json:"user"`
				} `json:"collection"`
			}
			if errs[i] = getJSON(ctx, a.HTTP, u, &body); errs[i] != nil {
				return
			}
			for _, s := range body.Collection {
				kind := KindPlaylist
				if t := strings.ToLower(s.SetType); t == "album" || t == "ep" || t == "single" || t == "compilation" {
					kind = KindAlbum
				}
				parts[i] = append(parts[i], Result{
					Kind: kind, Title: s.Title, Uploader: s.User.Username, URL: s.PermalinkURL,
					Thumbnail: strings.Replace(s.ArtworkURL, "-large.", "-t500x500.", 1), Source: "soundcloud",
					Tracks: s.TrackCount,
				})
			}
		})
	}
	wg.Wait()
	if errs[0] != nil && errs[1] != nil {
		return nil, errs[0]
	}
	return append(parts[0], parts[1]...), nil
}
