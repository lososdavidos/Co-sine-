// Package resolve identifies what a file is (§3.2). Sources are tried in
// tier order and the first confident answer wins. Only tier 4 (source
// metadata: tags and filenames) exists so far; MusicBrainz, Discogs and
// Bandcamp slot in ahead of it behind the same interface.
package resolve

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/dhowden/tag"
)

// Tiers, from §3.2.
const (
	TierMusicBrainz = 1
	TierDiscogs     = 2
	TierBandcamp    = 3
	TierSource      = 4
	TierUser        = 5
)

// Input is everything known about a file before resolution.
type Input struct {
	Path         string // the file on disk
	OriginalName string // the name it arrived with
	Tags         *Tags  // nil when the file has no readable tags
}

// Tags are the embedded metadata, read once.
type Tags struct {
	Title, Artist, AlbumArtist, Album string
	Track, Disc, Year                 int
	Picture                           *tag.Picture
}

// Result is a resolved identity.
type Result struct {
	Artist     string
	Release    string
	Title      string
	TrackNo    int
	DiscNo     int
	Year       int
	Source     string
	Tier       int
	Confidence float64 // 0..1
}

// Resolver is one source in the chain.
type Resolver interface {
	Resolve(ctx context.Context, in Input) (Result, bool, error)
}

// Chain tries resolvers in order and returns the first answer at or above
// minConfidence, else the best answer seen. It errors only if none answers.
type Chain struct {
	Resolvers     []Resolver
	MinConfidence float64
}

func (c Chain) Resolve(ctx context.Context, in Input) (Result, error) {
	var best Result
	found := false
	for _, r := range c.Resolvers {
		res, ok, err := r.Resolve(ctx, in)
		if err != nil || !ok {
			continue // a failing source is skipped, not fatal
		}
		if res.Confidence >= c.MinConfidence {
			return res, nil
		}
		if !found || res.Confidence > best.Confidence {
			best, found = res, true
		}
	}
	if !found {
		return Result{}, ErrUnresolved
	}
	return best, nil
}

type unresolved struct{}

func (unresolved) Error() string { return "no source could identify this file" }

var ErrUnresolved error = unresolved{}

// NeedsReview is the review-queue rule (§5.3): below the threshold, or
// anything from tier 4 regardless of score — a confident guess from a
// SoundCloud title is still a guess.
func NeedsReview(r Result, threshold float64) bool {
	return r.Tier >= TierSource || r.Confidence < threshold
}

// ReadTags reads embedded tags; nil if the file has none it can parse.
func ReadTags(path string) *Tags {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	m, err := tag.ReadFrom(f)
	if err != nil {
		return nil
	}
	t := &Tags{
		Title:       strings.TrimSpace(m.Title()),
		Artist:      strings.TrimSpace(m.Artist()),
		AlbumArtist: strings.TrimSpace(m.AlbumArtist()),
		Album:       strings.TrimSpace(m.Album()),
		Year:        m.Year(),
		Picture:     m.Picture(),
	}
	t.Track, _ = m.Track()
	t.Disc, _ = m.Disc()
	return t
}

// SourceMetadata is tier 4: embedded tags where present, the filename otherwise.
type SourceMetadata struct{}

func (SourceMetadata) Resolve(_ context.Context, in Input) (Result, bool, error) {
	fromName := ParseFilename(in.OriginalName)
	r := Result{Source: "tags", Tier: TierSource}
	t := in.Tags
	if t == nil {
		t = &Tags{}
	}

	r.Artist = firstNonEmpty(t.AlbumArtist, t.Artist)
	r.Title = t.Title
	r.Release = t.Album
	r.TrackNo, r.DiscNo, r.Year = t.Track, t.Disc, t.Year

	switch {
	case r.Artist != "" && r.Title != "":
		r.Confidence = 0.5
	case r.Artist != "" || r.Title != "":
		r.Confidence = 0.3
	default:
		r.Source = "filename"
		r.Confidence = 0.2
	}
	r.Artist = firstNonEmpty(r.Artist, fromName.Artist, "Unknown artist")
	r.Title = firstNonEmpty(r.Title, fromName.Title)
	if r.TrackNo == 0 {
		r.TrackNo = fromName.TrackNo
	}
	// A track with no album is a release of one song, named after itself (§2.2).
	r.Release = firstNonEmpty(r.Release, r.Title)
	return r, r.Title != "", nil
}

// NameParts is what a filename says about itself.
type NameParts struct {
	Artist, Title string
	TrackNo       int
}

var (
	leadingNumber = regexp.MustCompile(`^(\d{1,3})\s*[-._)\]]?\s+`)
	bracketNoise  = regexp.MustCompile(`(?i)\s*[\[(](official( music)? (video|audio)|audio|lyrics?|hq|hd|free download|visuali[sz]er)[\])]`)
	idSuffix      = regexp.MustCompile(`\s*\[[A-Za-z0-9_-]{8,16}\]$`) // yt-dlp's default " [videoid]"
)

// ParseFilename reads "NN Artist - Title.ext"-style names, the shape most rips take.
func ParseFilename(name string) NameParts {
	base := strings.TrimSuffix(filepath.Base(name), filepath.Ext(name))
	base = strings.ReplaceAll(base, "_", " ")
	base = idSuffix.ReplaceAllString(base, "")
	base = bracketNoise.ReplaceAllString(base, "")
	base = strings.TrimSpace(base)

	var p NameParts
	if m := leadingNumber.FindStringSubmatch(base); m != nil {
		p.TrackNo, _ = strconv.Atoi(m[1])
		base = strings.TrimSpace(base[len(m[0]):])
	}
	for _, sep := range []string{" - ", " – ", " — "} {
		if i := strings.Index(base, sep); i > 0 {
			p.Artist = strings.TrimSpace(base[:i])
			p.Title = strings.TrimSpace(base[i+len(sep):])
			return p
		}
	}
	p.Title = base
	return p
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
