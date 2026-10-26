package search

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// ByRelease orders search results by how well each is the song as released
// (§6.10). The released length comes from a catalogue of released music
// (released; nil when none answered). Every result that is the same song
// is marked with it: those of that length come first, the artist's own
// uploads before others, and those much longer or shorter (a music video's
// intro, a 12-minute extended upload) go below everything else. Previews,
// which can't be added, go last.
func ByRelease(results []Result, released *Result) []Result {
	var ref Result
	if released != nil {
		ref = *released
		for i := range results {
			if !results[i].Preview && sameSong(ref, results[i]) { // a preview says so already
				results[i].ReleaseSec = ref.DurationSec
			}
		}
	}
	// Of the same song, the artist's own upload first.
	own := func(r Result) bool {
		return r.ReleaseSec > 0 && resolve.ArtistSimilarity(ref.Uploader, resolve.CleanArtist(r.Uploader)) >= 0.8
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
	sort.SliceStable(results, func(i, j int) bool {
		if a, b := rank(results[i]), rank(results[j]); a != b {
			return a < b
		}
		return own(results[i]) && !own(results[j])
	})
	return results
}

// Release looks the song a search asks for up in a catalogue of released
// music, for its length. YouTube Music no longer lists songs to anyone
// not signed in, so its uploads can't be relied on for this.
func (s *Service) Release(ctx context.Context, query string) (Result, bool) {
	if s.Released == nil {
		return Result{}, false
	}
	return s.Released(ctx, query)
}

// Released is a track as a catalogue lists it.
type Released struct {
	Title       string
	Version     string // "(Stripped)", "(Remix)": another version of the song
	Artist      string
	DurationSec int
}

// AskedRelease picks, from a catalogue's answers in its own order, the
// song a search asks for: named like the query, and not another version of
// it (stripped, a remix) unless the query names that version.
func AskedRelease(query string, found []Released) (Result, bool) {
	words := " " + resolve.Normalise(query) + " "
	for _, t := range found {
		r := Result{Title: t.Title, Uploader: t.Artist, DurationSec: t.DurationSec}
		if t.DurationSec <= 0 || !Asked(query, r) || resolve.AnotherVersion(resolve.Hint{Title: query}, t.Title) {
			continue
		}
		asked := true
		for _, w := range strings.Fields(resolve.Normalise(t.Version)) {
			if !strings.Contains(words, " "+w+" ") {
				asked = false
			}
		}
		if asked {
			return r, true
		}
	}
	return Result{}, false
}

// sameLength is how far apart two lengths can be and still be the same
// take: a longer fade, a second of silence.
const sameLength = 6

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
