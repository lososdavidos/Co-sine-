package musicbrainz

import (
	"context"
	"math"
	"slices"
	"sort"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// Hint is what's known before asking: usually the tier-4 guess.
type Hint = resolve.Hint

// Candidates returns up to n identities for a hint, most likely first, each
// with a confidence. The correction screen shows these (§6.12).
func (c *Client) Candidates(ctx context.Context, h Hint, n int) ([]resolve.Result, error) {
	if !h.Usable() {
		return nil, nil
	}
	// A well-known song has dozens of recordings in MusicBrainz, mostly
	// live bootlegs and music videos, and its search scores them all 100:
	// "Californication" by Red Hot Chili Peppers has 71, and the studio one
	// came 14th. So official, non-video recordings are asked for first, and
	// everything only when that finds nothing confident. Live recordings
	// can't be left out the same way: "NOT secondarytype:live" drops any
	// recording on any live album, which a hit's studio recording often is
	// ("Bad at Love" vanished). versionPenalty sorts those out instead.
	base := "recording:" + phrase(h.Title) + " AND artist:" + phrase(h.Artist)
	queries := []string{base + " AND status:official AND video:false", base}
	var out []candidate
	seen := map[string]bool{}
	for _, q := range queries {
		recs, err := c.searchRecordings(ctx, q, 50)
		if err != nil {
			if len(out) > 0 {
				break // keep what the first search found
			}
			return nil, err
		}
		for _, r := range recs {
			for _, c := range identities(h, r) {
				key := c.IDs.MBRecording + "/" + c.IDs.MBRelease
				if !seen[key] {
					seen[key] = true
					out = append(out, c)
				}
			}
		}
		sort.SliceStable(out, func(i, j int) bool { return better(out[i], out[j]) })
		if len(out) > 0 && out[0].Confidence >= confident {
			break
		}
	}
	results := make([]resolve.Result, 0, min(n, len(out)))
	for _, c := range out[:min(n, len(out))] {
		results = append(results, c.Result)
	}
	return results, nil
}

// Match returns the single best identity, if any.
func (c *Client) Match(ctx context.Context, h Hint) (resolve.Result, bool, error) {
	cands, err := c.Candidates(ctx, h, 1)
	if err != nil || len(cands) == 0 {
		return resolve.Result{}, false, err
	}
	return cands[0], true, nil
}

// candidate is an identity with how canonical its release is (releaseRank).
type candidate struct {
	resolve.Result
	rank int
}

// better orders candidates: by confidence, and between equals the better
// release (an album over a single, either over a compilation, across
// recordings too: MusicBrainz often keeps the single's and the album's
// recordings apart), then the earliest, then one that says where the
// track sits. Without this a tie goes to whatever MusicBrainz listed
// first, such as a reissue a year later with no track numbers.
func better(a, b candidate) bool {
	if math.Abs(a.Confidence-b.Confidence) > 1e-9 {
		return a.Confidence > b.Confidence
	}
	if a.rank != b.rank {
		return a.rank < b.rank
	}
	year := func(r resolve.Result) int {
		if r.Year == 0 {
			return 9999
		}
		return r.Year
	}
	if year(a.Result) != year(b.Result) {
		return year(a.Result) < year(b.Result)
	}
	return a.TrackNo > 0 && b.TrackNo == 0
}

// confident is the review threshold (§5.3): a match below it is a guess.
const confident = 0.8

// identities turns one recording into candidate identities: the best
// release for it, plus up to two alternatives, so a correction can choose
// the album over the single or the reverse.
func identities(h Hint, r recording) []candidate {
	base := score(h, r) - versionPenalty(h, r)
	rels := rankReleases(h, r)
	if len(rels) == 0 {
		// A recording on no release: file it as a single of itself.
		return []candidate{{finish(h, r, release{Title: r.Title}, base), 0}}
	}
	var out []candidate
	for i, rel := range rels {
		if i == 3 {
			break
		}
		conf := base - 0.02*float64(i) // alternatives rank just below the preferred release
		if h.Release != "" && !strings.EqualFold(h.Release, h.Title) && similarity(h.Release, rel.Title) >= 0.8 {
			conf += 0.05 // the album we were told about
		}
		out = append(out, candidate{finish(h, r, rel, conf), releaseRank(h, r, rel)})
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

// score is how well a recording fits the hint, by the measure every
// catalogue shares (resolve.Score).
func score(h Hint, r recording) float64 {
	names := []string{r.ArtistCredit.String()}
	for _, c := range r.ArtistCredit {
		names = append(names, c.Name)
	}
	return resolve.Score(h, r.Title, names, (r.Length+500)/1000)
}

// versionPenalty marks a recording that is another performance or form of
// the song than the one a plain title means: a live version, a music video,
// a bootleg, an "unmastered" or "demo" take. Each still matches by name and
// often by length, so without this the first one MusicBrainz lists wins.
// A live or video version drops below the review threshold unless the
// file's own title asks for it, and then the studio take does.
func versionPenalty(h Hint, r recording) float64 {
	p := 0.0
	dis := strings.Fields(resolve.Normalise(r.Disambiguation))
	live := slices.Contains(dis, "live") || all(r.Releases, func(rel release) bool {
		return slices.Contains(rel.ReleaseGroup.SecondaryTypes, "Live")
	})
	wantLive := asksFor(h, "live")
	switch {
	case live != wantLive:
		p += 0.25 // the other performance: live for a plain title, or the studio take for a live one
	case !live && len(dis) > 0 && !asksFor(h, dis...):
		p += 0.05 // "unmastered", "demo", "instrumental", "radio edit"…
	}
	if r.Video {
		p += 0.25
	}
	switch {
	case all(r.Releases, func(rel release) bool { return rel.Status == "Bootleg" }):
		p += 0.1
	case all(r.Releases, compilation):
		// MusicBrainz often keeps a hit's compilation appearances as a
		// recording of their own; the album's should win even when the
		// compilation's length is a second closer.
		p += 0.1
	}
	return p
}

// asksFor reports whether the hinted title or album uses any of these words.
func asksFor(h Hint, words ...string) bool {
	have := strings.Fields(resolve.Normalise(h.Title + " " + h.Release))
	for _, w := range words {
		if len(w) >= 3 && slices.Contains(have, w) {
			return true
		}
	}
	return false
}

func all(rels []release, f func(release) bool) bool {
	for _, rel := range rels {
		if !f(rel) {
			return false
		}
	}
	return len(rels) > 0
}

func compilation(rel release) bool {
	st := rel.ReleaseGroup.SecondaryTypes
	return slices.Contains(st, "Compilation") || slices.Contains(st, "DJ-mix")
}

// rankReleases orders a recording's releases by how canonical they are for
// this file (releaseRank), earliest first between equals.
func rankReleases(h Hint, r recording) []release {
	rels := append([]release(nil), r.Releases...)
	sort.SliceStable(rels, func(i, j int) bool {
		ri, rj := releaseRank(h, r, rels[i]), releaseRank(h, r, rels[j])
		if ri != rj {
			return ri < rj
		}
		return dateKey(rels[i].Date) < dateKey(rels[j].Date)
	})
	return rels
}

// releaseRank says how good a home a release is for a track; lower is
// better. The album the file names comes first. Otherwise a song that has
// ever been on an album is filed under the album, then an EP, then its
// single (the one named after the track, if there are several), and only
// then a compilation, soundtrack or live album. Official before anything.
func releaseRank(h Hint, r recording, rel release) int {
	wantAlbum := h.Release != "" && !strings.EqualFold(h.Release, h.Title)
	n := 0
	if wantAlbum && similarity(h.Release, rel.Title) >= 0.8 {
		n -= 100
	}
	switch rel.ReleaseGroup.PrimaryType {
	case "Album":
		n -= 30
	case "EP":
		n -= 20
	case "Single":
		n -= 10
	}
	if !wantAlbum && similarity(rel.Title, r.Title) >= 0.9 {
		n -= 5
	}
	if rel.Status != "Official" {
		n += 40
	}
	// The original release before a compilation, a live album or a mix of
	// it. A release credited to Various Artists is a compilation whatever
	// its type says.
	if strings.EqualFold(rel.ArtistCredit.String(), "Various Artists") && !slices.Contains(rel.ReleaseGroup.SecondaryTypes, "Compilation") {
		n += 25
	}
	for _, st := range rel.ReleaseGroup.SecondaryTypes {
		switch {
		case st == "Live" && asksFor(h, "live"):
		case st == "Live":
			n += 35
		default: // Compilation, Soundtrack, DJ-mix, Remix, Demo…
			n += 25
		}
	}
	return n
}

func dateKey(d string) string {
	if d == "" {
		return "9999"
	}
	return d
}

func similarity(a, b string) float64 { return resolve.Similarity(a, b) }
