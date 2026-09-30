package musicbrainz

import (
	"context"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// Hint is what's known before asking: usually the tier-4 guess.
type Hint struct {
	Artist, Title, Release string
	DurationSec            int
}

func (h Hint) usable() bool {
	return strings.TrimSpace(h.Title) != "" && strings.TrimSpace(h.Artist) != "" &&
		!strings.EqualFold(h.Artist, "Unknown artist")
}

// Candidates returns up to n identities for a hint, most likely first, each
// with a confidence. The correction screen shows these (§6.12).
func (c *Client) Candidates(ctx context.Context, h Hint, n int) ([]resolve.Result, error) {
	if !h.usable() {
		return nil, nil
	}
	q := "recording:" + phrase(h.Title) + " AND artist:" + phrase(h.Artist)
	recs, err := c.searchRecordings(ctx, q, 15)
	if err != nil {
		return nil, err
	}
	var out []resolve.Result
	seen := map[string]bool{}
	for _, r := range recs {
		for _, res := range identities(h, r) {
			key := res.IDs.MBRecording + "/" + res.IDs.MBRelease
			if !seen[key] {
				seen[key] = true
				out = append(out, res)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Confidence > out[j].Confidence })
	if len(out) > n {
		out = out[:n]
	}
	return out, nil
}

// Match returns the single best identity, if any.
func (c *Client) Match(ctx context.Context, h Hint) (resolve.Result, bool, error) {
	cands, err := c.Candidates(ctx, h, 1)
	if err != nil || len(cands) == 0 {
		return resolve.Result{}, false, err
	}
	return cands[0], true, nil
}

// identities turns one recording into candidate identities: the best
// release for it, plus up to two alternatives, so a correction can choose
// the album over the single or the reverse.
func identities(h Hint, r recording) []resolve.Result {
	base := score(h, r)
	rels := rankReleases(h, r)
	if len(rels) == 0 {
		// A recording on no release: file it as a single of itself.
		return []resolve.Result{finish(h, r, release{Title: r.Title}, base)}
	}
	var out []resolve.Result
	for i, rel := range rels {
		if i == 3 {
			break
		}
		conf := base - 0.02*float64(i) // alternatives rank just below the preferred release
		if h.Release != "" && !strings.EqualFold(h.Release, h.Title) && similarity(h.Release, rel.Title) >= 0.8 {
			conf += 0.05 // the album we were told about
		}
		out = append(out, finish(h, r, rel, conf))
	}
	return out
}

func finish(h Hint, r recording, rel release, conf float64) resolve.Result {
	res := resolve.Result{
		Title:      r.Title,
		Release:    rel.Title,
		Source:     "musicbrainz",
		Tier:       resolve.TierMusicBrainz,
		Confidence: math.Max(0, math.Min(1, conf)),
		IDs: resolve.IDs{
			MBRecording:    r.ID,
			MBRelease:      rel.ID,
			MBReleaseGroup: rel.ReleaseGroup.ID,
		},
	}
	trackArtist := r.ArtistCredit.String()
	releaseArtist := rel.ArtistCredit.String()
	if releaseArtist == "" {
		releaseArtist = trackArtist
	}
	res.Artist = releaseArtist
	res.IDs.MBArtist = rel.ArtistCredit.firstID()
	if res.IDs.MBArtist == "" {
		res.IDs.MBArtist = r.ArtistCredit.firstID()
	}
	if trackArtist != releaseArtist {
		res.TrackArtist = trackArtist // e.g. a compilation under Various Artists (Q74)
	}
	if len(rel.Date) >= 4 {
		res.Year, _ = strconv.Atoi(rel.Date[:4])
	}
	for _, m := range rel.Media {
		if len(m.Track) > 0 {
			res.DiscNo = m.Position
			if n, err := strconv.Atoi(m.Track[0].Number); err == nil {
				res.TrackNo = n
			} else {
				res.TrackNo = m.Track[0].Position
			}
			break
		}
	}
	if res.DiscNo == 1 {
		res.DiscNo = 0 // single-disc releases carry no disc number
	}
	return res
}

// score is how well a recording fits the hint, 0..1: title and artist
// dominate, duration confirms. A confident guess from a filename is still
// only as good as this agreement.
func score(h Hint, r recording) float64 {
	title := similarity(h.Title, r.Title)
	artist := similarity(h.Artist, r.ArtistCredit.String())
	for _, c := range r.ArtistCredit {
		artist = math.Max(artist, similarity(h.Artist, c.Name))
	}
	dur := 0.6 // unknown: neither confirms nor contradicts
	contradicted := false
	if h.DurationSec > 0 && r.Length > 0 {
		d := math.Abs(float64(h.DurationSec) - float64(r.Length)/1000)
		switch {
		case d <= 3:
			dur = 1
		case d <= 10:
			dur = 0.7
		case d <= 30:
			dur = 0.3
		default:
			dur, contradicted = 0, true
		}
	}
	s := 0.45*title + 0.35*artist + 0.20*dur
	if contradicted {
		// Same name, very different length: a mix, an extended cut or a
		// different piece. Never confident enough to skip review.
		s = math.Min(s, 0.6)
	}
	return s
}

// rankReleases orders a recording's releases by how canonical they are for
// this file: the named album first; then, for something that arrived as a
// single, a release named after the track; then official before anything
// else, albums and singles before compilations, earliest first.
func rankReleases(h Hint, r recording) []release {
	rels := append([]release(nil), r.Releases...)
	wantAlbum := h.Release != "" && !strings.EqualFold(h.Release, h.Title)
	rank := func(rel release) int {
		n := 0
		if wantAlbum && similarity(h.Release, rel.Title) >= 0.8 {
			n -= 100
		}
		if !wantAlbum && similarity(rel.Title, r.Title) >= 0.9 {
			n -= 50
		}
		if rel.Status != "Official" {
			n += 20
		}
		switch rel.ReleaseGroup.PrimaryType {
		case "Album", "Single", "EP":
		default:
			n += 10
		}
		return n
	}
	sort.SliceStable(rels, func(i, j int) bool {
		ri, rj := rank(rels[i]), rank(rels[j])
		if ri != rj {
			return ri < rj
		}
		return dateKey(rels[i].Date) < dateKey(rels[j].Date)
	})
	return rels
}

func dateKey(d string) string {
	if d == "" {
		return "9999"
	}
	return d
}

var bracketed = regexp.MustCompile(`\s*[\[(](?:feat\.?|ft\.?|with)[^\])]*[\])]`)

// normalise lowercases, drops featured-artist brackets and punctuation, and
// collapses whitespace, so "Tides (feat. X)" and "tides" compare equal.
func normalise(s string) string {
	s = bracketed.ReplaceAllString(strings.ToLower(s), "")
	var b strings.Builder
	space := false
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			b.WriteRune(r)
			space = false
		} else if !space {
			b.WriteByte(' ')
			space = true
		}
	}
	return strings.TrimSpace(b.String())
}

// similarity is 1 for equal names, else the Dice overlap of their words.
func similarity(a, b string) float64 {
	a, b = normalise(a), normalise(b)
	if a == "" || b == "" {
		return 0
	}
	if a == b {
		return 1
	}
	wa, wb := strings.Fields(a), strings.Fields(b)
	set := map[string]int{}
	for _, w := range wa {
		set[w]++
	}
	shared := 0
	for _, w := range wb {
		if set[w] > 0 {
			set[w]--
			shared++
		}
	}
	return 2 * float64(shared) / float64(len(wa)+len(wb))
}
