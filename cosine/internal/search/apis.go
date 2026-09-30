package search

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// SourceAPIs queries SoundCloud and YouTube directly. Either may be absent;
// results from those present are interleaved.
type SourceAPIs struct {
	SoundCloud *SoundCloudAPI
	YouTube    *YouTubeAPI
}

// NewSourceAPIs builds the API backend from stored keys, or nil when there are none.
func NewSourceAPIs(client *http.Client) func(scID, ytKey string) Backend {
	return func(scID, ytKey string) Backend {
		var s SourceAPIs
		if scID != "" {
			s.SoundCloud = &SoundCloudAPI{ClientID: scID, HTTP: client}
		}
		if ytKey != "" {
			s.YouTube = &YouTubeAPI{Key: ytKey, HTTP: client}
		}
		if s.SoundCloud == nil && s.YouTube == nil {
			return nil
		}
		return s
	}
}

func (s SourceAPIs) Search(ctx context.Context, q string, n int) ([]Result, error) {
	var sc, yt []Result
	var scErr, ytErr error
	var wg sync.WaitGroup
	if s.SoundCloud != nil {
		wg.Add(1)
		go func() { defer wg.Done(); sc, scErr = s.SoundCloud.Search(ctx, q, n) }()
	}
	if s.YouTube != nil {
		wg.Add(1)
		go func() { defer wg.Done(); yt, ytErr = s.YouTube.Search(ctx, q, n) }()
	}
	wg.Wait()
	scFailed := s.SoundCloud == nil || scErr != nil
	ytFailed := s.YouTube == nil || ytErr != nil
	if scFailed && ytFailed {
		return nil, errors.Join(scErr, ytErr)
	}
	return interleave(sc, yt), nil
}

// SoundCloudAPI uses api-v2 with a client ID.
type SoundCloudAPI struct {
	ClientID string
	HTTP     *http.Client
	BaseURL  string // tests
}

func (a *SoundCloudAPI) Search(ctx context.Context, q string, n int) ([]Result, error) {
	base := a.BaseURL
	if base == "" {
		base = "https://api-v2.soundcloud.com"
	}
	u := base + "/search/tracks?" + url.Values{
		"q": {q}, "limit": {strconv.Itoa(n)}, "client_id": {a.ClientID},
	}.Encode()
	var body struct {
		Collection []struct {
			Title         string `json:"title"`
			PermalinkURL  string `json:"permalink_url"`
			Duration      int64  `json:"duration"` // ms
			PlaybackCount int64  `json:"playback_count"`
			ArtworkURL    string `json:"artwork_url"`
			User          struct {
				Username  string `json:"username"`
				AvatarURL string `json:"avatar_url"`
			} `json:"user"`
		} `json:"collection"`
	}
	if err := getJSON(ctx, a.HTTP, u, &body); err != nil {
		return nil, fmt.Errorf("soundcloud: %w", err)
	}
	out := make([]Result, 0, len(body.Collection))
	for _, t := range body.Collection {
		art := t.ArtworkURL
		if art == "" {
			art = t.User.AvatarURL
		}
		out = append(out, Result{
			Title: t.Title, Uploader: t.User.Username, URL: t.PermalinkURL,
			Thumbnail: strings.Replace(art, "-large.", "-t500x500.", 1), Source: "soundcloud",
			DurationSec: int((t.Duration + 500) / 1000), Plays: t.PlaybackCount,
		})
	}
	return out, nil
}

// YouTubeAPI uses the Data API v3: a search, then one videos call for
// durations and view counts.
type YouTubeAPI struct {
	Key     string
	HTTP    *http.Client
	BaseURL string // tests
}

func (a *YouTubeAPI) Search(ctx context.Context, q string, n int) ([]Result, error) {
	base := a.BaseURL
	if base == "" {
		base = "https://www.googleapis.com/youtube/v3"
	}
	var found struct {
		Items []struct {
			ID struct {
				VideoID string `json:"videoId"`
			} `json:"id"`
			Snippet struct {
				Title        string `json:"title"`
				ChannelTitle string `json:"channelTitle"`
				Thumbnails   map[string]struct {
					URL string `json:"url"`
				} `json:"thumbnails"`
			} `json:"snippet"`
		} `json:"items"`
	}
	u := base + "/search?" + url.Values{
		"part": {"snippet"}, "type": {"video"}, "maxResults": {strconv.Itoa(n)}, "q": {q}, "key": {a.Key},
	}.Encode()
	if err := getJSON(ctx, a.HTTP, u, &found); err != nil {
		return nil, fmt.Errorf("youtube: %w", err)
	}
	ids := make([]string, 0, len(found.Items))
	for _, it := range found.Items {
		ids = append(ids, it.ID.VideoID)
	}
	var details struct {
		Items []struct {
			ID             string `json:"id"`
			ContentDetails struct {
				Duration string `json:"duration"`
			} `json:"contentDetails"`
			Statistics struct {
				ViewCount string `json:"viewCount"`
			} `json:"statistics"`
		} `json:"items"`
	}
	if len(ids) > 0 {
		u = base + "/videos?" + url.Values{
			"part": {"contentDetails,statistics"}, "id": {strings.Join(ids, ",")}, "key": {a.Key},
		}.Encode()
		// Details are a nicety: search results stand without them.
		_ = getJSON(ctx, a.HTTP, u, &details)
	}
	type extra struct {
		dur   int
		views int64
	}
	byID := map[string]extra{}
	for _, d := range details.Items {
		v, _ := strconv.ParseInt(d.Statistics.ViewCount, 10, 64)
		byID[d.ID] = extra{isoDuration(d.ContentDetails.Duration), v}
	}
	out := make([]Result, 0, len(found.Items))
	for _, it := range found.Items {
		thumb := ""
		for _, k := range []string{"high", "medium", "default"} {
			if t, ok := it.Snippet.Thumbnails[k]; ok {
				thumb = t.URL
				break
			}
		}
		e := byID[it.ID.VideoID]
		out = append(out, Result{
			Title: it.Snippet.Title, Uploader: it.Snippet.ChannelTitle,
			URL: "https://www.youtube.com/watch?v=" + it.ID.VideoID, Thumbnail: thumb,
			Source: "youtube", DurationSec: e.dur, Plays: e.views,
		})
	}
	return out, nil
}

var isoDur = regexp.MustCompile(`^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$`)

// isoDuration parses YouTube's "PT1H2M3S".
func isoDuration(s string) int {
	m := isoDur.FindStringSubmatch(s)
	if m == nil {
		return 0
	}
	h, _ := strconv.Atoi(m[1])
	min, _ := strconv.Atoi(m[2])
	sec, _ := strconv.Atoi(m[3])
	return h*3600 + min*60 + sec
}

func getJSON(ctx context.Context, client *http.Client, u string, into any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	res, err := client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", res.StatusCode)
	}
	return json.NewDecoder(res.Body).Decode(into)
}
