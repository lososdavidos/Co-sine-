package resolve

import (
	"fmt"
	"math"
	"sort"
	"strings"
)

// Home is how good a home a release is for the song a file holds (§3.2).
// Every catalogue fills it in alike, so identities from any of them order
// the same way.
type Home struct {
	Named      bool // the release the file names, the artist's own
	Unofficial bool // a bootleg, a promo, a pseudo-release
	// Compiled is a compilation, a soundtrack, a live album or a mix: not
	// the artist's own release of the song, however early.
	Compiled bool
	// Kind is the sort of release, lower better: an album, an EP, a single.
	Kind int
	Year int    // 0: not known
	Date string // the day it came out ("2016-07-29"), where the catalogue says
}

// Before reports whether a is a better home than b. The release the file
// names comes first, then an official one, then the artist's own release
// before a compilation or a live album of it. Then the earlier: the first
// release a song came out on is the likeliest original ("Closer" was a
// single nine months before its album), a reissue or a greatest hits years
// on the least. Between releases out the same day, or the same year where
// the day isn't known, the album, then the EP, then the single.
func (a Home) Before(b Home) bool {
	if a.Named != b.Named {
		return a.Named
	}
	if a.Unofficial != b.Unofficial {
		return b.Unofficial
	}
	if a.Compiled != b.Compiled {
		return b.Compiled
	}
	if wa, wb := a.when(), b.when(); wa != wb {
		return wa < wb
	}
	return a.Kind < b.Kind
}

// when is a key that sorts homes by when they came out: by year, and then
// by the day, a release known only by its year after those that year
// known to the day, and one of no known year last.
func (h Home) when() string {
	if h.Year <= 0 {
		return "~"
	}
	year := fmt.Sprintf("%04d", h.Year)
	if strings.HasPrefix(h.Date, year) {
		return h.Date + "~"
	}
	return year + "~"
}

// Placed is an identity with its release's Home and the track's length
// there (0: not known).
type Placed struct {
	Result
	Home      Home
	LengthSec int
}

// sameSong is how far below the best a copy of the song on another release
// may score and still be taken for that song: a length the catalogue
// doesn't give costs 0.08, a compilation's own recording 0.1.
const sameSong = 0.1

// Order puts identities best first. The most confident first, except
// among copies of one song on different releases, which go in the order
// of their homes (Home.Before), each then carrying the best score among
// them less 0.02 a place (no more than sameSong), so that confidence alone
// keeps the order, across catalogues too. Copies of one song have the same
// title, scores within sameSong, and lengths that agree or aren't known:
// a known difference in length is another recording.
func Order(cands []Placed) []Result {
	cands = append([]Placed(nil), cands...)
	sort.SliceStable(cands, func(i, j int) bool { return cands[i].Confidence > cands[j].Confidence })
	used := make([]bool, len(cands))
	var out []Result
	for i, lead := range cands {
		if used[i] {
			continue
		}
		var song []Placed
		for j := i; j < len(cands); j++ {
			if !used[j] && copyOf(lead, cands[j]) {
				used[j] = true
				song = append(song, cands[j])
			}
		}
		sort.SliceStable(song, func(a, b int) bool {
			ha, hb := song[a].Home, song[b].Home
			if ha.Before(hb) || hb.Before(ha) {
				return ha.Before(hb)
			}
			return song[a].TrackNo > 0 && song[b].TrackNo == 0 // one that says where the track sits
		})
		for k, c := range song {
			c.Confidence = lead.Confidence - math.Min(0.02*float64(k), sameSong)
			out = append(out, c.Result)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Confidence > out[j].Confidence })
	return out
}

// copyOf reports whether c is the lead's song on another release.
func copyOf(lead, c Placed) bool {
	if lead.Confidence-c.Confidence > sameSong+1e-9 {
		return false
	}
	if Normalise(WithoutFeatures(lead.Title)) != Normalise(WithoutFeatures(c.Title)) {
		return false
	}
	return lead.LengthSec == 0 || c.LengthSec == 0 || math.Abs(float64(lead.LengthSec-c.LengthSec)) <= 6
}
