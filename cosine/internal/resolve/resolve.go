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
	Path         string      // the file on disk
	OriginalName string      // the name it arrived with
	Tags         *Tags       // nil when the file has no readable tags
	Source       *SourceInfo // nil unless fetched from a URL
	DurationSec  int         // 0 when unknown; matching evidence for online sources
}

// SourceInfo is what the site a file came from says about it (yt-dlp's
// metadata). Structured fields exist on YouTube Music, Bandcamp and some
// SoundCloud uploads; otherwise there is only an uploader and a title.
type SourceInfo struct {
	Title, Uploader      string
	Artist, Track, Album string
	TrackNo, Year        int
}

// Tags are the embedded metadata, read once.
type Tags struct {
	Title, Artist, AlbumArtist, Album string
	Track, Disc, Year                 int
	Picture                           *tag.Picture
}

// Result is a resolved identity.
type Result struct {
	Artist  string `json:"artist"`  // the release's artist: the folder the Track is filed under
	Release string `json:"release"` // a single is a release named after its one track (§2.2)
	Title   string `json:"title"`
	// TrackArtist is the track's own credit when it differs from the
	// release's, as on a compilation filed under Various Artists (Q74).
	TrackArtist string  `json:"trackArtist,omitempty"`
	TrackNo     int     `json:"trackNo,omitempty"`
	DiscNo      int     `json:"discNo,omitempty"`
	Year        int     `json:"year,omitempty"`
	Source      string  `json:"source"` // which source identified it
	Tier        int     `json:"tier"`
	Confidence  float64 `json:"confidence"` // 0..1
	IDs         IDs     `json:"ids,omitzero"`
}

// IDs are identifiers in outside catalogues. Empty unless the source has them.
type IDs struct {
	MBRecording    string `json:"mbRecording,omitempty"`
	MBRelease      string `json:"mbRelease,omitempty"`
	MBReleaseGroup string `json:"mbReleaseGroup,omitempty"`
	MBArtist       string `json:"mbArtist,omitempty"`
}

// ArtistOfTrack is the credit shown on the track itself.
func (r Result) ArtistOfTrack() string {
	if r.TrackArtist != "" {
		return r.TrackArtist
	}
	return r.Artist
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

// SourceMetadata is tier 4: what the source site says, else embedded tags,
// else the filename.
type SourceMetadata struct{}

func (SourceMetadata) Resolve(_ context.Context, in Input) (Result, bool, error) {
	if in.Source != nil {
		r := fromSource(in.Source)
		if in.Tags != nil && r.Year == 0 {
			r.Year = in.Tags.Year
		}
		return r, r.Title != "", nil
	}
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

func fromSource(s *SourceInfo) Result {
	r := Result{Source: "yt-dlp", Tier: TierSource, TrackNo: s.TrackNo, Year: s.Year}
	switch {
	case s.Artist != "" && s.Track != "":
		// The site gave structured metadata.
		r.Artist, r.Title, r.Release, r.Confidence = s.Artist, s.Track, s.Album, 0.5
	default:
		// Most uploads are "Artist - Title" by whoever posted them; a title
		// without that shape belongs to the uploader.
		p := ParseTitle(s.Title, false)
		if p.Artist != "" {
			r.Artist, r.Title, r.Confidence = p.Artist, p.Title, 0.3
		} else {
			r.Artist, r.Title, r.Confidence = s.Uploader, p.Title, 0.2
		}
		r.Release = s.Album
	}
	r.Artist = firstNonEmpty(r.Artist, s.Uploader, "Unknown artist")
	r.Title = firstNonEmpty(r.Title, s.Title)
	r.Release = firstNonEmpty(r.Release, r.Title)
	return r
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
	return ParseTitle(strings.ReplaceAll(base, "_", " "), true)
}

// ParseTitle reads "Artist - Title" from a free-text title. Leading track
// numbers are only taken from filenames: in a title, "1998 - Song" is not
// track 1998.
func ParseTitle(base string, trackNumbers bool) NameParts {
	base = idSuffix.ReplaceAllString(base, "")
	base = bracketNoise.ReplaceAllString(base, "")
	base = strings.TrimSpace(base)

	var p NameParts
	if m := leadingNumber.FindStringSubmatch(base); trackNumbers && m != nil {
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
