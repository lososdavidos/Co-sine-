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
	// The artist is asked for as credited or by the lead alone: MusicBrainz
	// credits "Closer" to "The Chainsmokers ft. Halsey", and a file's "feat."
	// found only compilations credited that way. The title goes without its
	// "(feat. X)", which MusicBrainz keeps in the credit.
	artist := phrase(h.Artist)
	if lead := resolve.LeadArtist(h.Artist); lead != "" && lead != h.Artist {
		artist = "(" + artist + " OR " + phrase(lead) + ")"
	}
	base := "recording:" + phrase(resolve.WithoutFeatures(h.Title)) + " AND artist:" + artist
	official := base + " AND status:official AND video:false"
	var out []resolve.Placed
	seen := map[string]bool{}
	search := func(q string) (int, error) {
		recs, err := c.searchRecordings(ctx, q, page)
		for _, r := range recs {
			for _, c := range identities(h, r) {
				key := c.IDs.MBRecording + "/" + c.IDs.MBRelease
				if !seen[key] {
					seen[key] = true
					out = append(out, c)
				}
			}
		}
		return len(recs), err
	}
	found, err := search(official)
	if err != nil {
		return nil, err
	}
	if found == page {
		// A hit has more recordings than a page holds, most of them its
		// copies on compilations, and its own can be past the page:
		// "September" by Earth, Wind & Fire has well over fifty, and the
		// first fifty were all compilations, DJ mixes and live albums. The
		// recordings on a single are asked for too; a hit's own is.
		search(official + " AND primarytype:single") // what the first found stands if this fails
	}
	ranked := resolve.Order(out)
	if len(ranked) == 0 || ranked[0].Confidence < confident {
		search(base)
		ranked = resolve.Order(out)
	}
	return ranked[:min(n, len(ranked))], nil
}

// Match returns the single best identity, if any.
func (c *Client) Match(ctx context.Context, h Hint) (resolve.Result, bool, error) {
	cands, err := c.Candidates(ctx, h, 1)
	if err != nil || len(cands) == 0 {
		return resolve.Result{}, false, err
	}
	return cands[0], true, nil
}

// confident is the review threshold (§5.3): a match below it is a guess.
const confident = 0.8

// page is how many recordings a search asks for.
const page = 50

// identities turns one recording into candidate identities: the best
// release for it, plus up to two alternatives, so a correction can choose
// the album over the single or the reverse. resolve.Order puts them, and
// other recordings' copies of the song, in order.
func identities(h Hint, r recording) []resolve.Placed {
	base := score(h, r) - versionPenalty(h, r)
	length := (r.Length + 500) / 1000
	rels := rankReleases(h, r)
	if len(rels) == 0 {
		// A recording on no release: file it as a single of itself.
		return []resolve.Placed{{Result: finish(h, r, release{Title: r.Title}, base), LengthSec: length}}
	}
	var out []resolve.Placed
	for i, rel := range rels {
		if i == 3 {
			break
		}
		conf := base
		home := home(h, r, rel)
		if home.Named {
			conf += 0.05 // the album we were told about
		}
		out = append(out, resolve.Placed{Result: finish(h, r, rel, conf), Home: home, LengthSec: length})
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

// compiled reports whether a release gathers tracks from other releases: a
// compilation or DJ mix, or anything credited to Various Artists.
func compiled(rel release) bool {
	return compilation(rel) || strings.EqualFold(rel.ArtistCredit.String(), "Various Artists")
}

// named reports whether the file names this release as its album.
func named(h Hint, rel release) bool {
	return h.Release != "" && !strings.EqualFold(h.Release, h.Title) && similarity(h.Release, rel.Title) >= 0.8
}

// rankReleases orders a recording's releases by how good a home each is
// for this file (home, resolve.Home.Before).
func rankReleases(h Hint, r recording) []release {
	rels := append([]release(nil), r.Releases...)
	homes := make(map[string]resolve.Home, len(rels))
	for _, rel := range rels {
		homes[rel.ID] = home(h, r, rel)
	}
	sort.SliceStable(rels, func(i, j int) bool { return homes[rels[i].ID].Before(homes[rels[j].ID]) })
	return rels
}

// home says how good a home a release is for a track (resolve.Home). The
// album the file names comes first, and official releases before others.
// Then the artist's own releases, the earliest first, and between those
// out the same day the album, then an EP, then the single (the one named
// after the track, if there are several); and only then a compilation,
// soundtrack or live album.
//
// A compilation the file names is not "the album" in that sense: YouTube
// credits a video to whichever release claimed it, often a "Hot Party
// Winter 2018", and a ripped "NOW" CD names one too. It only goes ahead
// of other compilations; the artist's own release still wins.
func home(h Hint, r recording, rel release) resolve.Home {
	wantAlbum := h.Release != "" && !strings.EqualFold(h.Release, h.Title)
	hm := resolve.Home{Named: named(h, rel) && !compiled(rel), Unofficial: rel.Status != "Official", Date: rel.Date}
	if len(rel.Date) >= 4 {
		hm.Year, _ = strconv.Atoi(rel.Date[:4])
	}
	n := 0
	if named(h, rel) && compiled(rel) {
		n--
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
	// The original release before a compilation, a live album or a mix of
	// it. A release credited to Various Artists is a compilation whatever
	// its type says.
	if strings.EqualFold(rel.ArtistCredit.String(), "Various Artists") && !slices.Contains(rel.ReleaseGroup.SecondaryTypes, "Compilation") {
		hm.Compiled = true
		n += 25
	}
	for _, st := range rel.ReleaseGroup.SecondaryTypes {
		switch {
		case st == "Live" && asksFor(h, "live"):
		case st == "Live":
			hm.Compiled = true
			n += 35
		default: // Compilation, Soundtrack, DJ-mix, Remix, Demo…
			hm.Compiled = true
			n += 25
		}
	}
	hm.Kind = n
	return hm
}

func similarity(a, b string) float64 { return resolve.Similarity(a, b) }
