package resolve

import (
	"context"
	"errors"
	"math"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// Hint is what's known before asking a catalogue: usually the tier-4 guess.
type Hint struct {
	Artist, Title, Release string
	DurationSec            int
}

// Usable reports whether a hint is worth a lookup: a catalogue can't
// identify "Unknown artist - untitled rip".
func (h Hint) Usable() bool {
	return strings.TrimSpace(h.Title) != "" && strings.TrimSpace(h.Artist) != "" &&
		!strings.EqualFold(h.Artist, "Unknown artist")
}

// HintFrom turns an identity into a search hint. The title is cleaned
// again, so identities filed before a cleaner improvement still search well.
func HintFrom(r Result, durationSec int) Hint {
	return Hint{Artist: CleanArtist(r.ArtistOfTrack()), Title: CleanTitle(r.Title), Release: CleanTitle(r.Release), DurationSec: durationSec}
}

// ErrUnavailable means a catalogue could not be asked (offline, throttled,
// blocked, down). It is "no answer", never a failed ingest. Each catalogue
// has its own error that matches it.
var ErrUnavailable = errors.New("a catalogue is unavailable")

type unavailable struct{ name string }

func (e unavailable) Error() string        { return e.name + " is unavailable" }
func (e unavailable) Is(target error) bool { return target == ErrUnavailable }

// Unavailable is the error for one named catalogue.
func Unavailable(name string) error { return unavailable{name} }

// Matcher is a catalogue that can name the single best identity for a hint.
type Matcher interface {
	Match(ctx context.Context, h Hint) (Result, bool, error)
}

// Lookup makes a catalogue a link in the chain. It asks about whatever the
// file and its source say it is; the tier-4 reading supplies that hint.
type Lookup struct {
	Matcher Matcher
	// Timeout bounds one lookup, so a slow catalogue delays an ingest
	// rather than stalling it.
	Timeout time.Duration
}

func (l Lookup) Resolve(ctx context.Context, in Input) (Result, bool, error) {
	guess, ok, _ := SourceMetadata{}.Resolve(ctx, in)
	if !ok {
		return Result{}, false, nil
	}
	timeout := l.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return l.Matcher.Match(ctx, HintFrom(guess, in.DurationSec))
}

// Score is how well a catalogue entry fits a hint, 0..1: title and artist
// dominate, duration confirms. A confident guess from a filename is still
// only as good as this agreement. Every catalogue scores the same way, so
// their confidences compare.
func Score(h Hint, title string, artists []string, lengthSec int) float64 {
	t := Similarity(h.Title, title)
	a := ArtistSimilarity(h.Artist, artists...)
	dur, contradicted := DurationFit(h.DurationSec, lengthSec)
	s := 0.45*t + 0.35*a + 0.20*dur
	if contradicted {
		// Same name, very different length: a mix, an extended cut or a
		// different piece. Never confident enough to skip review.
		s = math.Min(s, 0.6)
	}
	return s
}

// DurationFit scores agreement in length. Unknown on either side neither
// confirms nor contradicts.
func DurationFit(hintSec, lengthSec int) (score float64, contradicted bool) {
	if hintSec <= 0 || lengthSec <= 0 {
		return 0.6, false
	}
	d := math.Abs(float64(hintSec - lengthSec))
	switch {
	case d <= 3:
		return 1, false
	case d <= 6: // a longer fade, a second of silence: the same take
		return 0.9, false
	case d <= 10:
		return 0.7, false
	case d <= 30:
		return 0.3, false
	default:
		return 0, true
	}
}

var featuring = regexp.MustCompile(`(?i)\s+(?:feat\.?|ft\.?|featuring|with|x|&|vs\.?)\s+|\s*,\s*`)

// LeadArtist is the first-named artist of a credit: "Skeler" in "Skeler ft. plenka".
func LeadArtist(s string) string { return strings.TrimSpace(featuring.Split(s, 2)[0]) }

// ArtistSimilarity compares a hinted artist with a catalogue's names for it.
// "Skeler ft. plenka" matches a catalogue's "Skeler" by its lead artist.
func ArtistSimilarity(hint string, names ...string) float64 {
	lead := LeadArtist(hint)
	best := 0.0
	for _, n := range names {
		best = math.Max(best, Similarity(hint, n))
		if lead != hint {
			best = math.Max(best, 0.95*Similarity(lead, n))
		}
	}
	return best
}

var bracketed = regexp.MustCompile(`\s*[\[(](?:feat\.?|ft\.?|with)[^\])]*[\])]`)

// WithoutFeatures drops "(feat. X)" from a title, for catalogues whose
// search wants the bare name.
func WithoutFeatures(s string) string { return strings.TrimSpace(bracketed.ReplaceAllString(s, "")) }

// Normalise lowercases, drops featured-artist brackets and punctuation, and
// collapses whitespace, so "Tides (feat. X)" and "tides" compare equal, as
// do "skeler." and "Skeler".
func Normalise(s string) string {
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

// Similarity is 1 for equal names, else the Dice overlap of their words.
func Similarity(a, b string) float64 {
	a, b = Normalise(a), Normalise(b)
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
