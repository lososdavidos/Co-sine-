// Package discogs is the resolver's second tier (§3.2): vinyl, bootlegs,
// white labels, reissues, and the digital singles of artists MusicBrainz
// never heard of. Searching needs no account; a personal access token
// (Discogs → Settings → Developers) raises the limit from 25 to 60 requests
// a minute.
package discogs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/webcache"
)

// ErrUnavailable means Discogs could not be asked.
var ErrUnavailable = resolve.Unavailable("Discogs")

// Each match is one search plus up to this many release lookups.
const maxLookups = 3

type Client struct {
	Web     *webcache.Client
	BaseURL string // default https://api.discogs.com
}

// New builds a client. token is read before every request, so setting one
// in the dashboard applies at once; "" searches anonymously.
func New(h *http.Client, d *db.DB, userAgent string, token func() string) *Client {
	if token == nil {
		token = func() string { return "" }
	}
	return &Client{Web: &webcache.Client{
		Name: "Discogs", HTTP: h, DB: d, UserAgent: userAgent,
		Pace: func() time.Duration {
			if token() != "" {
				return 1100 * time.Millisecond // 60 a minute
			}
			return 2500 * time.Millisecond // 25 a minute
		},
		Prepare: func(r *http.Request) {
			if t := token(); t != "" {
				r.Header.Set("Authorization", "Discogs token="+t)
			}
		},
		Valid: func(b []byte) bool { return len(b) > 0 && b[0] == '{' },
	}}
}

func (c *Client) base() string {
	if c.BaseURL != "" {
		return strings.TrimRight(c.BaseURL, "/")
	}
	return "https://api.discogs.com"
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
	q := url.Values{
		"artist":   {resolve.LeadArtist(h.Artist)},
		"track":    {resolve.WithoutFeatures(h.Title)},
		"per_page": {"15"},
	}
	var found searchResponse
	if err := c.get(ctx, "/database/search?"+q.Encode(), &found); err != nil {
		if errors.Is(err, webcache.ErrNotFound) {
			return nil, nil
		}
		return nil, err
	}

	var out []resolve.Placed
	for i, l := range pick(found.Results) {
		if i == maxLookups {
			break
		}
		var e entry
		if err := c.get(ctx, "/"+l.path, &e); err != nil {
			if errors.Is(err, webcache.ErrNotFound) {
				continue
			}
			if len(out) > 0 {
				break // keep what was found before Discogs stopped answering
			}
			return nil, err
		}
		if r, length, ok := identity(h, l, e); ok {
			home := home(h, e, l)
			out = append(out, resolve.Placed{Result: r, Home: home, LengthSec: length})
			if r.Confidence >= 0.9 && home.Kind <= rankAlbum && (home.Named || !wantAlbum(h)) {
				// Confident, on an album: the rest came out no earlier and
				// would only cost time.
				break
			}
		}
	}
	// Copies of the song go in the order MusicBrainz's do: the album the
	// file names, then the artist's own releases, the earliest first, and
	// compilations, a greatest hits years on among them, last.
	results := resolve.Order(out)
	return results[:min(n, len(results))], nil
}

const rankAlbum = -30

// home says how good a home a master or release is (resolve.Home), on the
// same scale as MusicBrainz's: the album the file names, else an album, an
// EP, a single, and a compilation last. A master carries no formats of its
// own; its pressings' in the search results say what it is, and failing
// those its length.
func home(h resolve.Hint, e entry, l lookup) resolve.Home {
	hm := resolve.Home{Named: named(h, e) && !e.compiled(l), Unofficial: e.unofficial(l), Compiled: e.compiled(l), Year: e.Year, Date: e.Released}
	if hm.Year == 0 {
		hm.Year = l.year
	}
	n := 0
	if named(h, e) && e.compiled(l) {
		n-- // ahead of other compilations, never of the artist's own (as MusicBrainz)
	}
	kind := ""
	for _, d := range e.descriptions(l) {
		switch d {
		case "Album", "LP", "Mini-Album":
			kind = "album"
		case "EP":
			if kind == "" || kind == "single" {
				kind = "ep"
			}
		case "Single", "Maxi-Single", "Single Sided":
			if kind == "" {
				kind = "single"
			}
		}
	}
	if kind == "" {
		switch tracks := e.tracks(); {
		case tracks >= 7:
			kind = "album"
		case tracks >= 4:
			kind = "ep"
		default:
			kind = "single"
		}
	}
	n += map[string]int{"album": rankAlbum, "ep": -20, "single": -10}[kind]
	if e.compiled(l) {
		n += 25
	}
	hm.Kind = n
	return hm
}

// wantAlbum reports whether the file names an album of its own.
func wantAlbum(h resolve.Hint) bool {
	return h.Release != "" && !strings.EqualFold(h.Release, h.Title)
}

// named reports whether the file names this master or release as its album.
func named(h resolve.Hint, e entry) bool {
	return h.Release != "" && !strings.EqualFold(h.Release, h.Title) && resolve.Similarity(h.Release, e.Title) >= 0.8
}

// compiled reports whether an entry gathers other releases' tracks: it is
// credited to Various Artists, or it says Compilation (a master: each of its
// pressings in the search results does).
func (e entry) compiled(l lookup) bool {
	return credit(e.Artists) == "Various Artists" || e.says(l, "Compilation")
}

// unofficial reports a bootleg: Discogs's "Unofficial Release", on the
// entry, or on every pressing of a master the search listed.
func (e entry) unofficial(l lookup) bool { return e.says(l, "Unofficial Release") }

// says reports whether the entry's own formats name a description, or, for
// a master, which has none, whether every pressing of it listed does.
func (e entry) says(l lookup, d string) bool {
	if len(e.Formats) > 0 {
		for _, f := range e.Formats {
			if slices.Contains(f.Descriptions, d) {
				return true
			}
		}
		return false
	}
	for _, p := range l.pressings {
		if !slices.Contains(p, d) {
			return false
		}
	}
	return len(l.pressings) > 0
}

// descriptions are the entry's format descriptions: its own, or for a
// master its pressings'.
func (e entry) descriptions(l lookup) []string {
	var out []string
	for _, f := range e.Formats {
		out = append(out, f.Descriptions...)
	}
	if len(out) > 0 {
		return out
	}
	for _, p := range l.pressings {
		out = append(out, p...)
	}
	return out
}

func (e entry) tracks() int {
	n := 0
	for _, t := range e.Tracklist {
		if t.Type == "" || t.Type == "track" {
			n++
		}
	}
	return n
}

func (c *Client) get(ctx context.Context, path string, v any) error {
	b, err := c.Web.Get(ctx, c.base()+path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, v); err != nil {
		return fmt.Errorf("unreadable Discogs answer: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------- wire types

type searchResponse struct {
	Results []searchResult `json:"results"`
}

type searchResult struct {
	ID       int64    `json:"id"`
	Type     string   `json:"type"` // master, release
	MasterID int64    `json:"master_id"`
	Year     string   `json:"year"`
	Format   []string `json:"format"` // "CD", "Compilation"…: name and descriptions together
}

type artist struct {
	Name string `json:"name"`
	ANV  string `json:"anv"` // the name as credited on this release
	Join string `json:"join"`
}

type track struct {
	Position string   `json:"position"`
	Type     string   `json:"type_"` // track, heading, index
	Title    string   `json:"title"`
	Duration string   `json:"duration"`
	Artists  []artist `json:"artists"`
}

// entry is a master or a release: the fields both have.
type entry struct {
	Title     string   `json:"title"`
	Year      int      `json:"year"`
	Released  string   `json:"released"` // "2013-10-07"; releases only
	Artists   []artist `json:"artists"`
	Tracklist []track  `json:"tracklist"`
	Formats   []struct {
		Name         string   `json:"name"`
		Descriptions []string `json:"descriptions"`
	} `json:"formats"` // releases only
	Images []struct {
		Type string `json:"type"`
		URI  string `json:"uri"`
	} `json:"images"`
}

// lookup is a master or release to look up, with what the search said
// of it: its earliest year, and each listed pressing's formats.
type lookup struct {
	path      string
	year      int
	pressings [][]string
}

// pick orders what to look up: masters (one per release, across every
// pressing) and releases that belong to no master in the results, the
// earliest first, in the search's order within a year. A release whose
// master wasn't returned is looked up as that master.
func pick(results []searchResult) []lookup {
	var out []*lookup
	byPath := map[string]*lookup{}
	for _, r := range results {
		var path string
		switch {
		case r.Type == "master":
			path = "masters/" + strconv.FormatInt(r.ID, 10)
		case r.Type == "release" && r.MasterID != 0:
			path = "masters/" + strconv.FormatInt(r.MasterID, 10)
		case r.Type == "release":
			path = "releases/" + strconv.FormatInt(r.ID, 10)
		default:
			continue
		}
		l := byPath[path]
		if l == nil {
			l = &lookup{path: path}
			byPath[path] = l
			out = append(out, l)
		}
		if y, err := strconv.Atoi(r.Year); err == nil && y > 0 && (l.year == 0 || y < l.year) {
			l.year = y
		}
		if r.Type == "release" && len(r.Format) > 0 {
			l.pressings = append(l.pressings, r.Format)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		yi, yj := out[i].year, out[j].year
		return yi != 0 && (yj == 0 || yi < yj)
	})
	looks := make([]lookup, len(out))
	for i, l := range out {
		looks[i] = *l
	}
	return looks
}

// identity finds the hinted track on a master or release and scores it.
// It returns the track's length there too (0: Discogs doesn't say).
func identity(h resolve.Hint, l lookup, e entry) (resolve.Result, int, bool) {
	releaseArtist := credit(e.Artists)
	if releaseArtist == "" {
		return resolve.Result{}, 0, false
	}
	best, bestScore, number := -1, 0.0, 0
	numbers := make([]int, len(e.Tracklist))
	for i, t := range e.Tracklist {
		if t.Type != "" && t.Type != "track" {
			continue // a heading or an index, not a track
		}
		number++
		numbers[i] = number
		names := []string{releaseArtist}
		if a := credit(t.Artists); a != "" {
			names = []string{a, resolve.LeadArtist(a)}
		}
		if s := resolve.Score(h, t.Title, names, seconds(t.Duration)); s > bestScore {
			best, bestScore = i, s
		}
	}
	if best < 0 {
		return resolve.Result{}, 0, false
	}
	t := e.Tracklist[best]
	conf := bestScore
	if named(h, e) && !e.compiled(l) {
		conf += 0.05 // the album we were told about
	}
	if _, contradicted := resolve.DurationFit(h.DurationSec, seconds(t.Duration)); contradicted {
		conf = min(conf, 0.6) // the bonus never lifts a wrong length past review
	}
	conf = min(conf, 1)

	r := resolve.Result{
		Artist:     releaseArtist,
		Release:    e.Title,
		Title:      t.Title,
		Year:       e.Year,
		Source:     "discogs",
		Tier:       resolve.TierDiscogs,
		Confidence: conf,
		IDs:        resolve.IDs{Discogs: strings.Replace(l.path, "s/", "/", 1)}, // "masters/1" → "master/1", as on discogs.com
	}
	if a := credit(t.Artists); a != "" && a != releaseArtist {
		r.TrackArtist = a // a compilation filed under Various Artists keeps each track's credit (Q74)
	}
	r.DiscNo, r.TrackNo = position(t.Position, numbers[best])
	for _, img := range e.Images {
		if r.Cover == "" || img.Type == "primary" {
			r.Cover = img.URI
		}
		if img.Type == "primary" {
			break
		}
	}
	return r, seconds(t.Duration), true
}

var disambiguation = regexp.MustCompile(`\s+\(\d+\)$`)

// credit joins a Discogs credit the way it's printed: "A & B", "A Feat. B".
// Discogs numbers artists who share a name ("Skeler (2)"); that is its
// bookkeeping, not the name.
func credit(as []artist) string {
	var b strings.Builder
	for i, a := range as {
		name := a.ANV
		if name == "" {
			name = disambiguation.ReplaceAllString(a.Name, "")
		}
		if name == "Various" {
			name = "Various Artists"
		}
		b.WriteString(name)
		if i < len(as)-1 {
			switch j := strings.TrimSpace(a.Join); j {
			case "", ",":
				b.WriteString(", ")
			default:
				b.WriteString(" " + j + " ")
			}
		}
	}
	return strings.TrimSpace(b.String())
}

// seconds reads "3:14" or "1:02:03"; 0 when absent.
func seconds(d string) int {
	total := 0
	for _, part := range strings.Split(strings.TrimSpace(d), ":") {
		n, err := strconv.Atoi(part)
		if err != nil {
			return 0
		}
		total = total*60 + n
	}
	return total
}

var discTrack = regexp.MustCompile(`^(\d+)[-.](\d+)$`)

// position reads a track's place. "3" is track 3; "2-5" is disc 2, track 5;
// vinyl sides ("A1", "B") have no number, so the running count is used.
func position(p string, running int) (disc, track int) {
	p = strings.TrimSpace(p)
	if m := discTrack.FindStringSubmatch(p); m != nil {
		disc, _ = strconv.Atoi(m[1])
		track, _ = strconv.Atoi(m[2])
		if disc == 1 {
			disc = 0 // single-disc releases carry no disc number
		}
		return disc, track
	}
	if n, err := strconv.Atoi(p); err == nil && n > 0 {
		return 0, n
	}
	return 0, running
}
