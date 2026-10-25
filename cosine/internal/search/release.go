package search

import (
	"fmt"
	"sort"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// ByRelease orders search results by how well each is the song as released
// (§6.10). A label's own upload of the song asked for (a YouTube Music
// song) gives the released length. Every result that is the same song is
// marked with it: those of that length come first, and those much longer
// or shorter (a music video's intro, a 12-minute extended upload) go below
// everything else. Previews, which can't be added, go last.
func ByRelease(query string, results []Result) []Result {
	if ref, ok := release(query, results); ok {
		for i := range results {
			if !results[i].Preview && sameSong(ref, results[i]) { // a preview says so already
				results[i].ReleaseSec = ref.DurationSec
			}
		}
	}
	rank := func(r Result) int {
		d := offBy(r)
		switch {
		case r.Preview:
			return 3
		case r.ReleaseSec == 0 || r.DurationSec == 0:
			return 1
		case d <= sameLength:
			return 0
		case d > 30:
			return 2
		default:
			return 1
		}
	}
	sort.SliceStable(results, func(i, j int) bool { return rank(results[i]) < rank(results[j]) })
	return results
}

// sameLength is how far apart two lengths can be and still be the same
// take: a longer fade, a second of silence.
const sameLength = 6

// release finds the label's upload of the song asked for.
func release(query string, results []Result) (Result, bool) {
	for _, r := range results {
		if r.Source == SourceYouTubeMusic && r.DurationSec > 0 && Asked(query, r) {
			return r, true
		}
	}
	return Result{}, false
}

// sameSong reports whether a result is the released song rather than a
// remix, a cover or another song of the same name. An upload that names
// an artist ("Halsey - Bad At Love (Official Music Video)") must name the
// same one; one that names none is taken at its title.
func sameSong(ref, r Result) bool {
	p := resolve.ParseTitle(r.Title, false)
	title := resolve.CleanTitle(p.Title)
	if resolve.Similarity(ref.Title, title) < 0.8 || resolve.AnotherVersion(resolve.Hint{Title: ref.Title}, title) {
		return false
	}
	return p.Artist == "" || resolve.ArtistSimilarity(ref.Uploader, resolve.CleanArtist(p.Artist)) >= 0.5
}

func offBy(r Result) int {
	d := r.DurationSec - r.ReleaseSec
	if d < 0 {
		return -d
	}
	return d
}

// LengthNote says how a result's length compares with the release, in the
// voice of §6.17; "" when there's nothing to compare with.
func LengthNote(r Result) string {
	if r.ReleaseSec == 0 || r.DurationSec == 0 {
		return ""
	}
	d := offBy(r)
	switch {
	case d <= sameLength:
		return "Same length as the release"
	case r.DurationSec > r.ReleaseSec:
		return clock(d) + " longer than the release"
	default:
		return clock(d) + " shorter than the release"
	}
}

func clock(sec int) string { return fmt.Sprintf("%d:%02d", sec/60, sec%60) }
