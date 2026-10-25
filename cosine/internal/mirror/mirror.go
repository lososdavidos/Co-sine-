// Package mirror feeds the client's local copy of the library (§5.2, G2):
// the whole library the first time, then only what changed since the
// client's cursor, read from the change log. The client works out counts
// (albums per artist, tracks per album) itself, so a change never leaves
// them stale.
package mirror

import (
	"context"
	"database/sql"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Shapes match the Subsonic ones Sine already reads (ids, cover art ids),
// so a mirrored track streams and shows its cover the same way.
type Artist struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	CoverArt string `json:"coverArt,omitempty"`
}

type Album struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Artist   string `json:"artist"`
	ArtistID string `json:"artistId"`
	Year     int    `json:"year,omitempty"`
	CoverArt string `json:"coverArt,omitempty"`
	Created  string `json:"created,omitempty"` // when this account first had something on it
}

type Track struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`   // the track's own credit
	ArtistID    string `json:"artistId"` // the release's artist, as in Subsonic
	Album       string `json:"album"`
	AlbumID     string `json:"albumId"`
	TrackNumber int    `json:"trackNumber,omitempty"`
	DiscNumber  int    `json:"discNumber,omitempty"`
	Year        int    `json:"year,omitempty"`
	DurationSec int    `json:"durationSec"`
	SizeBytes   int64  `json:"sizeBytes"`
	Suffix      string `json:"suffix"`
	BitRate     int    `json:"bitRate,omitempty"`
	CoverArt    string `json:"coverArt,omitempty"`
}

// Changes is one answer: with Full, the whole library, to replace the
// client's; otherwise what to add or update, and the tracks to remove.
// Albums and artists left with no tracks are the client's to drop.
type Changes struct {
	Cursor  int64    `json:"cursor"`
	Full    bool     `json:"full"`
	Artists []Artist `json:"artists"`
	Albums  []Album  `json:"albums"`
	Tracks  []Track  `json:"tracks"`
	Removed []string `json:"removedTracks"`
}

// fullAbove: past this many changes, the whole library is the smaller answer.
const fullAbove = 2000

type Service struct{ DB *db.DB }

// Since answers a client whose copy is current up to change after (0: none).
func (s *Service) Since(ctx context.Context, user, after int64) (Changes, error) {
	// The cursor is read first: a change made while this answer is built is
	// either in it or after the cursor, never lost.
	var cursor int64
	if err := s.DB.QueryRowContext(ctx, "SELECT COALESCE(MAX(seq), 0) FROM changes").Scan(&cursor); err != nil {
		return Changes{}, err
	}
	out := Changes{Cursor: cursor, Artists: []Artist{}, Albums: []Album{}, Tracks: []Track{}, Removed: []string{}}
	if after == cursor && after > 0 {
		return out, nil
	}
	var n int
	if after > 0 && after < cursor {
		if err := s.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM changes WHERE seq > ? AND seq <= ?", after, cursor).Scan(&n); err != nil {
			return Changes{}, err
		}
	}
	// No cursor, a cursor from a database that has since been replaced, or
	// too much to say change by change: the whole library.
	if after <= 0 || after > cursor || n > fullAbove {
		out.Full = true
		return out, s.fill(ctx, &out, user, "", nil)
	}

	tracks, releases, artists := map[int64]bool{}, map[int64]bool{}, map[int64]bool{}
	rows, err := s.DB.QueryContext(ctx, `SELECT entity, entity_id FROM changes
		WHERE seq > ? AND seq <= ? AND (user_id IS NULL OR user_id = ?)`, after, cursor, user)
	if err != nil {
		return Changes{}, err
	}
	for rows.Next() {
		var entity, raw string
		if err := rows.Scan(&entity, &raw); err != nil {
			rows.Close()
			return Changes{}, err
		}
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			continue
		}
		switch entity {
		case "track", "pointer":
			tracks[id] = true
		case "album":
			releases[id] = true
		case "artist":
			artists[id] = true
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return Changes{}, err
	}
	// A changed track brings its whole release, so a renamed release or a
	// new cover reaches every track on it; a changed artist brings
	// everything of theirs, so a rename does.
	for id := range tracks {
		var rid int64
		if s.DB.QueryRowContext(ctx, "SELECT release_id FROM tracks WHERE id = ?", id).Scan(&rid) == nil {
			releases[rid] = true
		}
	}
	where := "AND (t.id IN (" + list(tracks) + ") OR t.release_id IN (" + list(releases) + ")" +
		" OR t.artist_id IN (" + list(artists) + ") OR r.artist_id IN (" + list(artists) + "))"
	if err := s.fill(ctx, &out, user, where, artists); err != nil {
		return Changes{}, err
	}
	present := map[string]bool{}
	for _, t := range out.Tracks {
		present[t.ID] = true
	}
	for id := range tracks {
		ref := trackID(id)
		if present[ref] {
			continue
		}
		// Gone (merged into another), or this account's but with no file
		// left. Another account's track was never on this client.
		var exists, ours bool
		if err := s.DB.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tracks WHERE id = ?1),
			EXISTS(SELECT 1 FROM pointers WHERE track_id = ?1 AND user_id = ?2)`, id, user).Scan(&exists, &ours); err != nil {
			return Changes{}, err
		}
		if !exists || ours {
			out.Removed = append(out.Removed, ref)
		}
	}
	return out, nil
}

// fill adds the account's tracks matching where, with their albums and
// their albums' artists, and any of the given artists still in the library.
func (s *Service) fill(ctx context.Context, out *Changes, user int64, where string, artists map[int64]bool) error {
	rows, err := s.DB.QueryContext(ctx, `
SELECT t.id, t.title, COALESCE(t.track_no, 0), COALESCE(t.disc_no, 0),
       r.id, r.title, COALESCE(r.year, 0), r.art_path IS NOT NULL,
       ra.id, ra.name, ta.name, p.added_at,
       COALESCE(o.size, 0), COALESCE(o.suffix, ''), COALESCE(o.duration_sec, 0), COALESCE(o.bitrate, 0)
FROM pointers p
JOIN tracks t ON t.id = p.track_id
JOIN releases r ON r.id = t.release_id
JOIN artists ra ON ra.id = r.artist_id
JOIN artists ta ON ta.id = t.artist_id
LEFT JOIN objects o ON o.hash = (
    SELECT o2.hash FROM objects o2 WHERE o2.track_id = t.id
    ORDER BY o2.missing, COALESCE(o2.hash = p.pin_hash, 0) DESC, o2.size DESC LIMIT 1)
WHERE p.user_id = ? AND `+db.Available+` `+where+`
ORDER BY t.id`, user)
	if err != nil {
		return err
	}
	albums := map[int64]*Album{}
	var albumOrder []int64
	releaseArtists := map[int64]bool{}
	for rows.Next() {
		var (
			t              Track
			tid, rid, raid int64
			hasArt         bool
			raName         string
			added          int64
		)
		if err := rows.Scan(&tid, &t.Title, &t.TrackNumber, &t.DiscNumber, &rid, &t.Album, &t.Year, &hasArt,
			&raid, &raName, &t.Artist, &added, &t.SizeBytes, &t.Suffix, &t.DurationSec, &t.BitRate); err != nil {
			rows.Close()
			return err
		}
		t.ID, t.AlbumID, t.ArtistID = trackID(tid), albumID(rid), artistID(raid)
		if hasArt {
			t.CoverArt = albumID(rid)
		}
		out.Tracks = append(out.Tracks, t)
		a := albums[rid]
		if a == nil {
			a = &Album{ID: albumID(rid), Name: t.Album, Artist: raName, ArtistID: artistID(raid), Year: t.Year, CoverArt: t.CoverArt}
			albums[rid] = a
			albumOrder = append(albumOrder, rid)
		}
		if c := iso(added); a.Created == "" || c < a.Created {
			a.Created = c
		}
		releaseArtists[raid] = true
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, rid := range albumOrder {
		out.Albums = append(out.Albums, *albums[rid])
	}
	for id := range artists {
		releaseArtists[id] = true // a changed artist (a new picture) is sent even with no track changes
	}
	if len(releaseArtists) == 0 {
		return nil
	}
	// Only artists someone files releases under are listed (as in Subsonic's
	// getArtists); one with no releases left is dropped by the client.
	arows, err := s.DB.QueryContext(ctx, `
SELECT a.id, a.name, a.art_path IS NOT NULL,
       (SELECT r2.id FROM releases r2 WHERE r2.artist_id = a.id AND r2.art_path IS NOT NULL LIMIT 1)
FROM artists a WHERE a.id IN (`+list(releaseArtists)+`) ORDER BY a.id`)
	if err != nil {
		return err
	}
	defer arows.Close()
	for arows.Next() {
		var (
			a       Artist
			id      int64
			picture bool
			artRel  sql.NullInt64
		)
		if err := arows.Scan(&id, &a.Name, &picture, &artRel); err != nil {
			return err
		}
		a.ID = artistID(id)
		switch {
		case picture:
			a.CoverArt = artistID(id)
		case artRel.Valid:
			a.CoverArt = albumID(artRel.Int64)
		}
		out.Artists = append(out.Artists, a)
	}
	return arows.Err()
}

// list renders ids for an IN clause; they are integers, so this is safe.
func list(ids map[int64]bool) string {
	if len(ids) == 0 {
		return "NULL"
	}
	parts := make([]string, 0, len(ids))
	for id := range ids {
		parts = append(parts, strconv.FormatInt(id, 10))
	}
	return strings.Join(parts, ",")
}

func artistID(id int64) string { return "ar-" + strconv.FormatInt(id, 10) }
func albumID(id int64) string  { return "al-" + strconv.FormatInt(id, 10) }
func trackID(id int64) string  { return "tr-" + strconv.FormatInt(id, 10) }

func iso(ms int64) string {
	if ms == 0 {
		return ""
	}
	return time.UnixMilli(ms).UTC().Format(time.RFC3339)
}
