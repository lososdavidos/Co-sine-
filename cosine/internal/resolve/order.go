package resolve

import (
	"math"
	"sort"
)

// Home is how good a home a release is for the song a file holds (§3.2).
// Every catalogue fills it in alike, so identities from any of them order
// the same way.
type Home struct {
	Named      bool // the release the file names, the artist's own
	Unofficial bool // a bootleg, a promo, a pseudo-release
	// Kind is the sort of release, lower better: an album, an EP, a single,
	// and compilations, soundtracks and live albums after them.
	Kind int
	Year int // 0: not known
}

// firstYears is how long after a song first came out a release of it still
// counts as a first one: a lead single often comes the year before its album.
const firstYears = 1

// Before reports whether a is a better home than b for a song first out in
// first (0: not known). The release the file names comes first, then an
// official one. Then the song's first releases (its first year and the
// next) before later ones, and the later the worse: the first release a
// song came out on is the likeliest original, and a greatest hits or a
// reissue years after it the least. Among the first releases, the kind
// decides (the album over its single), then the earlier.
func (a Home) Before(b Home, first int) bool {
	if a.Named != b.Named {
		return a.Named
	}
	if a.Unofficial != b.Unofficial {
		return b.Unofficial
	}
	lateA, lateB := a.late(first), b.late(first)
	if lateA != lateB {
		return lateB
	}
	if lateA && a.year() != b.year() {
		return a.year() < b.year()
	}
	if a.Kind != b.Kind {
		return a.Kind < b.Kind
	}
	return a.year() < b.year()
}

func (h Home) year() int {
	if h.Year == 0 {
		return math.MaxInt
	}
	return h.Year
}

func (h Home) late(first int) bool { return first > 0 && h.year() > first+firstYears }

// First is the earliest known year among homes; 0 when none is known.
func First(homes ...Home) int {
	first := 0
	for _, h := range homes {
		if h.Year > 0 && (first == 0 || h.Year < first) {
			first = h.Year
		}
	}
	return first
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
		var homes []Home
		for j := i; j < len(cands); j++ {
			if !used[j] && copyOf(lead, cands[j]) {
				used[j] = true
				song = append(song, cands[j])
				homes = append(homes, cands[j].Home)
			}
		}
		first := First(homes...)
		sort.SliceStable(song, func(a, b int) bool {
			ha, hb := song[a].Home, song[b].Home
			if ha.Before(hb, first) || hb.Before(ha, first) {
				return ha.Before(hb, first)
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
