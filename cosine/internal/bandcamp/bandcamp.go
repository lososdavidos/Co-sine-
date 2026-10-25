// Package bandcamp is the resolver's third tier (§3.2): independent and
// self-released music, where much of this library lives. Bandcamp has no
// public API; this uses the search its own site calls, and the structured
// data (schema.org JSON-LD and data-tralbum) every track page carries.
// Either can change without notice, and Bandcamp sometimes answers with a
// bot check instead; both read as "Bandcamp is unavailable", never as a
// failed ingest.
package bandcamp

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/webcache"
)

var ErrUnavailable = resolve.Unavailable("Bandcamp")

// Track pages are fetched for the best few results, for their length.
const maxPages = 2

type Client struct {
	Web *webcache.Client
	// SearchURL defaults to Bandcamp's own autocomplete endpoint.
	SearchURL string
}

func New(h *http.Client, d *db.DB, userAgent string) *Client {
	return &Client{Web: &webcache.Client{
		Name: "Bandcamp", HTTP: h, DB: d, UserAgent: userAgent,
		Pace: func() time.Duration { return 1500 * time.Millisecond },
		Valid: func(b []byte) bool {
			s := strings.TrimSpace(string(b))
			return strings.HasPrefix(s, "{") || strings.Contains(s, "application/ld+json") || strings.Contains(s, "data-tralbum")
		},
	}}
}

func (c *Client) searchURL() string {
	if c.SearchURL != "" {
		return c.SearchURL
	}
	return "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic"
}

// Match returns the single best identity, if any.
func (c *Client) Match(ctx context.Context, h resolve.Hint) (resolve.Result, bool, error) {
	cands, err := c.Candidates(ctx, h, 1)
	if err != nil || len(cands) == 0 {
		return resolve.Result{}, false, err
	}
	return cands[0], true, nil
}

// Candidates returns up to n identities for a hint, most likely first.
func (c *Client) Candidates(ctx context.Context, h resolve.Hint, n int) ([]resolve.Result, error) {
	if !h.Usable() {
		return nil, nil
	}
	body, _ := json.Marshal(map[string]any{
		"search_text":   resolve.LeadArtist(h.Artist) + " " + resolve.WithoutFeatures(h.Title),
		"search_filter": "t", // tracks
		"full_page":     false,
		"fan_id":        nil,
	})
	b, err := c.Web.Post(ctx, c.searchURL(), "application/json", body)
	if errors.Is(err, webcache.ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var found searchResponse
	if err := json.Unmarshal(b, &found); err != nil {
		return nil, fmt.Errorf("%w: unreadable search answer", ErrUnavailable)
	}

	type scored struct {
		item
		resolve.Result
	}
	var hits []scored
	for _, it := range found.Auto.Results {
		if it.Type != "t" || it.Name == "" || it.BandName == "" {
			continue
		}
		hits = append(hits, scored{it, identity(h, it, page{})})
	}
	byConfidence := func() {
		sort.SliceStable(hits, func(i, j int) bool { return hits[i].Confidence > hits[j].Confidence })
	}
	byConfidence()

	// The search has no lengths; the best few track pages do, and length is
	// what tells the single from the hour-long mix of the same name. A hit
	// whose artist doesn't fit (title alone scores about 0.57) isn't worth
	// a page.
	for i := range hits {
		if i == maxPages || hits[i].Confidence < 0.65 {
			break
		}
		p, err := c.page(ctx, hits[i].URL)
		if err != nil {
			if errors.Is(err, resolve.ErrUnavailable) {
				break // keep what the search said
			}
			continue
		}
		hits[i].Result = identity(h, hits[i].item, p)
	}
	byConfidence()
	out := make([]resolve.Result, 0, min(n, len(hits)))
	for _, hit := range hits[:min(n, len(hits))] {
		out = append(out, hit.Result)
	}
	return out, nil
}

// ---------------------------------------------------------------- search

type searchResponse struct {
	Auto struct {
		Results []item `json:"results"`
	} `json:"auto"`
}

type item struct {
	Type      string `json:"type"` // t: track, a: album, b: band
	Name      string `json:"name"`
	BandName  string `json:"band_name"`
	AlbumName string `json:"album_name"`
	URL       string `json:"item_url_path"`
	Img       string `json:"img"`
}

var imageSize = regexp.MustCompile(`_\d+\.(jpg|png)$`)

// identity scores a search result, refined by its track page when fetched.
func identity(h resolve.Hint, it item, p page) resolve.Result {
	r := resolve.Result{
		Artist:  it.BandName,
		Release: it.AlbumName,
		Title:   it.Name,
		Source:  "bandcamp",
		Tier:    resolve.TierBandcamp,
		IDs:     resolve.IDs{Bandcamp: it.URL},
		Cover:   imageSize.ReplaceAllString(it.Img, "_10.$1"), // _10 is the 1200px original
	}
	if p.Artist != "" {
		r.Artist = p.Artist
	}
	if p.Album != "" {
		r.Release = p.Album
	}
	if p.Cover != "" {
		r.Cover = p.Cover
	}
	r.TrackNo, r.Year = p.TrackNo, p.Year
	// A label's page names things "Artist - Album" and "Artist - Title".
	r.Release = withoutArtist(r.Release, r.Artist)
	r.Title = withoutArtist(r.Title, r.Artist)
	if r.Release == "" {
		r.Release = r.Title // a track on no album is a single of itself (§2.2)
	}
	conf := resolve.Score(h, r.Title, []string{r.Artist}, p.DurationSec)
	wantAlbum := h.Release != "" && !strings.EqualFold(h.Release, h.Title)
	if wantAlbum && resolve.Similarity(h.Release, r.Release) >= 0.8 {
		conf += 0.05
	}
	if _, contradicted := resolve.DurationFit(h.DurationSec, p.DurationSec); contradicted {
		conf = min(conf, 0.6)
	}
	r.Confidence = min(conf, 1)
	return r
}

func withoutArtist(name, artist string) string {
	for _, sep := range []string{" - ", " – ", " — "} {
		if rest, ok := strings.CutPrefix(name, artist+sep); ok && rest != "" {
			return rest
		}
	}
	return name
}

// ---------------------------------------------------------------- track pages

// page is what a track page says about itself.
type page struct {
	Artist, Album, Cover string
	DurationSec          int
	TrackNo, Year        int
}

var (
	ldJSON  = regexp.MustCompile(`(?s)<script[^>]+type="application/ld\+json"[^>]*>(.*?)</script>`)
	tralbum = regexp.MustCompile(`data-tralbum="([^"]*)"`)
	isoDur  = regexp.MustCompile(`^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$`)
	yearIn  = regexp.MustCompile(`\b(19|20)\d\d\b`)
)

func (c *Client) page(ctx context.Context, url string) (page, error) {
	if !(strings.HasPrefix(url, "https://") || strings.HasPrefix(url, "http://")) || !strings.Contains(url, "/track/") {
		return page{}, errors.New("not a track page")
	}
	b, err := c.Web.Get(ctx, url)
	if err != nil {
		return page{}, err
	}
	return parsePage(string(b)), nil
}

func parsePage(doc string) page {
	var p page
	if m := ldJSON.FindStringSubmatch(doc); m != nil {
		var ld struct {
			Duration      string          `json:"duration"`
			DatePublished string          `json:"datePublished"`
			Image         json.RawMessage `json:"image"`
			ByArtist      struct {
				Name string `json:"name"`
			} `json:"byArtist"`
			InAlbum struct {
				Name     string `json:"name"`
				ByArtist struct {
					Name string `json:"name"`
				} `json:"byArtist"`
			} `json:"inAlbum"`
		}
		if json.Unmarshal([]byte(m[1]), &ld) == nil {
			p.DurationSec = isoSeconds(ld.Duration)
			p.Artist = firstNonEmpty(ld.InAlbum.ByArtist.Name, ld.ByArtist.Name)
			p.Album = ld.InAlbum.Name
			p.Year = year(ld.DatePublished)
			var img string
			if json.Unmarshal(ld.Image, &img) == nil {
				p.Cover = img
			} else {
				var imgs []string
				if json.Unmarshal(ld.Image, &imgs) == nil && len(imgs) > 0 {
					p.Cover = imgs[0]
				}
			}
		}
	}
	if m := tralbum.FindStringSubmatch(doc); m != nil {
		var t struct {
			Artist           string `json:"artist"`
			AlbumReleaseDate string `json:"album_release_date"`
			Current          struct {
				ReleaseDate string `json:"release_date"`
			} `json:"current"`
			Trackinfo []struct {
				Duration float64 `json:"duration"`
				TrackNum int     `json:"track_num"`
			} `json:"trackinfo"`
		}
		if json.Unmarshal([]byte(html.UnescapeString(m[1])), &t) == nil {
			if len(t.Trackinfo) > 0 {
				if p.DurationSec == 0 {
					p.DurationSec = int(t.Trackinfo[0].Duration + 0.5)
				}
				p.TrackNo = t.Trackinfo[0].TrackNum
			}
			if p.Artist == "" {
				p.Artist = t.Artist
			}
			if p.Year == 0 {
				p.Year = year(firstNonEmpty(t.AlbumReleaseDate, t.Current.ReleaseDate))
			}
		}
	}
	return p
}

// isoSeconds reads schema.org durations: "P00H03M21S", "PT3M21S".
func isoSeconds(s string) int {
	m := isoDur.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil || s == "" || s == "P" {
		return 0
	}
	n := func(i int) float64 { f, _ := strconv.ParseFloat(m[i], 64); return f }
	return int(n(1)*86400 + n(2)*3600 + n(3)*60 + n(4) + 0.5)
}

func year(s string) int {
	n, _ := strconv.Atoi(yearIn.FindString(s))
	return n
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
