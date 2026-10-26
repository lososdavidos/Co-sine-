// Package deezer is where artist pictures come from (§6.9), and the length
// of a song as released (§3.1a). Deezer's public API needs no key, knows
// most released music, and its pictures are square press photos, which
// suit a round avatar. Answers aren't cached: an artist
// without a picture is asked about again a month later, and may have one by
// then.
package deezer

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
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/webcache"
)

// ErrUnavailable means Deezer could not be asked.
var ErrUnavailable = resolve.Unavailable("Deezer")

// ErrNotFound is Deezer saying it has no such thing.
var ErrNotFound = errors.New("not on Deezer")

type Client struct {
	Web     *webcache.Client
	BaseURL string // default https://api.deezer.com
}

func New(h *http.Client, userAgent string) *Client {
	return &Client{Web: &webcache.Client{
		Name: "Deezer", HTTP: h, UserAgent: userAgent,
		Pace:  func() time.Duration { return 250 * time.Millisecond }, // its limit is 50 in 5 seconds
		Valid: func(b []byte) bool { return len(b) > 0 && b[0] == '{' },
	}}
}

// Artist is a Deezer artist.
type Artist struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	PictureXL string `json:"picture_xl"`
	Fans      int    `json:"nb_fan"`
}

// Picture is the 1000px picture, or "" when Deezer has none: it then gives
// a placeholder URL with an empty image hash ("/images/artist//1000x1000…").
func (a Artist) Picture() string {
	if a.PictureXL == "" || strings.Contains(a.PictureXL, "/artist//") {
		return ""
	}
	return a.PictureXL
}

var artistPage = regexp.MustCompile(`^https?://(?:www\.)?deezer\.com/(?:[a-z]{2}/)?artist/(\d+)`)

// ArtistID reads a Deezer artist page link, as MusicBrainz records them.
func ArtistID(link string) (int64, bool) {
	m := artistPage.FindStringSubmatch(link)
	if m == nil {
		return 0, false
	}
	id, err := strconv.ParseInt(m[1], 10, 64)
	return id, err == nil
}

func (c *Client) base() string {
	if c.BaseURL != "" {
		return strings.TrimRight(c.BaseURL, "/")
	}
	return "https://api.deezer.com"
}

// Artist looks an artist up by id.
func (c *Client) Artist(ctx context.Context, id int64) (Artist, error) {
	var a Artist
	err := c.get(ctx, "/artist/"+strconv.FormatInt(id, 10), &a)
	return a, err
}

// SearchArtists finds artists by name, Deezer's best guess first.
func (c *Client) SearchArtists(ctx context.Context, name string) ([]Artist, error) {
	var found struct {
		Data []Artist `json:"data"`
	}
	err := c.get(ctx, "/search/artist?"+url.Values{"q": {name}, "limit": {"10"}}.Encode(), &found)
	return found.Data, err
}

// Track is a released track as Deezer lists it.
type Track struct {
	Title        string `json:"title"`
	TitleShort   string `json:"title_short"`
	TitleVersion string `json:"title_version"` // "(Stripped)", "(ILLENIUM Remix)"; empty for the song itself
	DurationSec  int    `json:"duration"`
	Artist       struct {
		Name string `json:"name"`
	} `json:"artist"`
	Album struct {
		Title string `json:"title"`
	} `json:"album"`
}

// SearchTracks finds released tracks, Deezer's best guess first.
func (c *Client) SearchTracks(ctx context.Context, query string, n int) ([]Track, error) {
	var found struct {
		Data []Track `json:"data"`
	}
	err := c.get(ctx, "/search/track?"+url.Values{"q": {query}, "limit": {strconv.Itoa(n)}}.Encode(), &found)
	return found.Data, err
}

// Titles lists the names of an artist's best-known tracks and of their
// albums, singles and EPs: what tells two artists of the same name apart.
func (c *Client) Titles(ctx context.Context, id int64) ([]string, error) {
	var list struct {
		Data []struct {
			Title string `json:"title"`
		} `json:"data"`
	}
	var out []string
	for _, path := range []string{"/top?limit=50", "/albums?limit=100"} {
		if err := c.get(ctx, "/artist/"+strconv.FormatInt(id, 10)+path, &list); err != nil {
			return nil, err
		}
		for _, d := range list.Data {
			out = append(out, d.Title)
		}
	}
	return out, nil
}

func (c *Client) get(ctx context.Context, path string, v any) error {
	b, err := c.Web.Get(ctx, c.base()+path)
	if errors.Is(err, webcache.ErrNotFound) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	// Deezer reports errors with a 200: {"error":{"type":…,"code":800,…}}.
	var e struct {
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if json.Unmarshal(b, &e) == nil && e.Error != nil {
		if e.Error.Code == 800 { // "no data"
			return ErrNotFound
		}
		return fmt.Errorf("%w: %s", ErrUnavailable, e.Error.Message)
	}
	if err := json.Unmarshal(b, v); err != nil {
		return fmt.Errorf("%w: unreadable answer", ErrUnavailable)
	}
	return nil
}
