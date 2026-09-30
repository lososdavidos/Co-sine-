package subsonic

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Every query here is scoped to one account's Pointers: a Library is a
// filtered view of the shared canonical tree (§2.2), never the whole Store.

var errNotFound = errors.New("not found")

// IDs are prefixed so one namespace serves every entity type.
func artistID(id int64) string   { return "ar-" + strconv.FormatInt(id, 10) }
func albumID(id int64) string    { return "al-" + strconv.FormatInt(id, 10) }
func trackID(id int64) string    { return "tr-" + strconv.FormatInt(id, 10) }
func playlistID(id int64) string { return "pl-" + strconv.FormatInt(id, 10) }

func parseID(s, prefix string) (int64, bool) {
	if !strings.HasPrefix(s, prefix) {
		return 0, false
	}
	n, err := strconv.ParseInt(s[len(prefix):], 10, 64)
	return n, err == nil
}

func iso(ms int64) string {
	if ms == 0 {
		return ""
	}
	return time.UnixMilli(ms).UTC().Format(time.RFC3339)
}

type library struct{ db *db.DB }

// ---------------------------------------------------------------- tracks

// songSelect picks, per Track, the Object to describe and serve: the
// account's version pin, else the largest available file. A Track whose
// files are all missing still lists (it greys out rather than disappearing, §2.2).
const songSelect = `
SELECT t.id, t.title, COALESCE(t.track_no, 0), COALESCE(t.disc_no, 0),
       r.id, r.title, COALESCE(r.year, 0), r.art_path IS NOT NULL,
       ra.id, ta.name, t.created_at, COALESCE(p.starred_at, 0),
       COALESCE(o.size, 0), COALESCE(o.suffix, ''), COALESCE(o.content_type, ''),
       COALESCE(o.duration_sec, 0), COALESCE(o.bitrate, 0), COALESCE(o.rel_path, '')
FROM pointers p
JOIN tracks t ON t.id = p.track_id
JOIN releases r ON r.id = t.release_id
JOIN artists ra ON ra.id = r.artist_id
JOIN artists ta ON ta.id = t.artist_id
LEFT JOIN objects o ON o.hash = (
    SELECT o2.hash FROM objects o2 WHERE o2.track_id = t.id
    ORDER BY o2.missing, COALESCE(o2.hash = p.pin_hash, 0) DESC, o2.size DESC LIMIT 1)
WHERE p.user_id = ?`

func scanSongs(rows *sql.Rows) ([]Child, error) {
	defer rows.Close()
	var out []Child
	for rows.Next() {
		var (
			c                       Child
			tid, rid, raid, created int64
			starred                 int64
			hasArt                  bool
		)
		if err := rows.Scan(&tid, &c.Title, &c.Track, &c.DiscNumber, &rid, &c.Album, &c.Year, &hasArt,
			&raid, &c.Artist, &created, &starred, &c.Size, &c.Suffix, &c.ContentType,
			&c.Duration, &c.BitRate, &c.Path); err != nil {
			return nil, err
		}
		c.ID, c.Parent, c.AlbumID, c.ArtistID = trackID(tid), albumID(rid), albumID(rid), artistID(raid)
		c.Type, c.Created, c.Starred = "music", iso(created), iso(starred)
		if hasArt {
			c.CoverArt = albumID(rid)
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (l library) songs(ctx context.Context, user int64, where, order string, args ...any) ([]Child, error) {
	q := songSelect + " " + where + " ORDER BY " + order
	rows, err := l.db.QueryContext(ctx, q, append([]any{user}, args...)...)
	if err != nil {
		return nil, err
	}
	return scanSongs(rows)
}

func (l library) song(ctx context.Context, user, id int64) (Child, error) {
	s, err := l.songs(ctx, user, "AND t.id = ?", "t.id", id)
	if err != nil {
		return Child{}, err
	}
	if len(s) == 0 {
		return Child{}, errNotFound
	}
	return s[0], nil
}

const trackOrder = "COALESCE(t.disc_no, 1), COALESCE(t.track_no, 9999), t.title"

// ---------------------------------------------------------------- artists

func (l library) artists(ctx context.Context, user int64, where string, limit, offset int, args ...any) ([]Artist, error) {
	q := `
SELECT a.id, a.name, COUNT(DISTINCT r.id),
       (SELECT r2.id FROM releases r2 WHERE r2.artist_id = a.id AND r2.art_path IS NOT NULL LIMIT 1)
FROM pointers p
JOIN tracks t ON t.id = p.track_id
JOIN releases r ON r.id = t.release_id
JOIN artists a ON a.id = r.artist_id
WHERE p.user_id = ? ` + where + `
GROUP BY a.id ORDER BY a.name LIMIT ? OFFSET ?`
	rows, err := l.db.QueryContext(ctx, q, append(append([]any{user}, args...), limit, offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Artist
	for rows.Next() {
		var (
			a      Artist
			id     int64
			artRel sql.NullInt64
		)
		if err := rows.Scan(&id, &a.Name, &a.AlbumCount, &artRel); err != nil {
			return nil, err
		}
		a.ID = artistID(id)
		if artRel.Valid {
			a.CoverArt = albumID(artRel.Int64)
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// indexed groups artists under their initial, "#" for anything not a letter.
func indexed(artists []Artist) []Index {
	var out []Index
	for _, a := range artists {
		key := "#"
		if r := []rune(strings.ToUpper(a.Name)); len(r) > 0 && unicode.IsLetter(r[0]) {
			key = string(r[0])
		}
		if len(out) == 0 || out[len(out)-1].Name != key {
			out = append(out, Index{Name: key})
		}
		out[len(out)-1].Artist = append(out[len(out)-1].Artist, a)
	}
	return out
}

// ---------------------------------------------------------------- albums

type albumSort string

const (
	sortNewest   albumSort = "newest"
	sortName     albumSort = "alphabeticalByName"
	sortArtist   albumSort = "alphabeticalByArtist"
	sortRecent   albumSort = "recent"
	sortFrequent albumSort = "frequent"
	sortRandom   albumSort = "random"
	sortStarred  albumSort = "starred"
)

func (l library) albums(ctx context.Context, user int64, where string, sort albumSort, limit, offset int, args ...any) ([]Album, error) {
	var order, having string
	switch sort {
	case sortNewest:
		order = "added DESC" // the account's own added-at: your recents are yours
	case sortArtist:
		order = "a.name, r.title"
	case sortRecent:
		order, having = "last_played DESC", "HAVING last_played > 0"
	case sortFrequent:
		order, having = "play_count DESC, r.title", "HAVING play_count > 0"
	case sortRandom:
		order = "RANDOM()"
	case sortStarred:
		order, having = "r.title", "HAVING MAX(COALESCE(p.starred_at, 0)) > 0"
	default:
		order = "r.title"
	}
	q := fmt.Sprintf(`
SELECT r.id, r.title, COALESCE(r.year, 0), r.art_path IS NOT NULL, a.id, a.name,
       COUNT(*), COALESCE(SUM((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = t.id)), 0),
       MIN(p.added_at) AS added,
       COALESCE((SELECT MAX(pl.played_at) FROM plays pl JOIN tracks t2 ON t2.id = pl.track_id
                 WHERE pl.user_id = p.user_id AND t2.release_id = r.id), 0) AS last_played,
       (SELECT COUNT(*) FROM plays pl JOIN tracks t2 ON t2.id = pl.track_id
        WHERE pl.user_id = p.user_id AND t2.release_id = r.id) AS play_count
FROM pointers p
JOIN tracks t ON t.id = p.track_id
JOIN releases r ON r.id = t.release_id
JOIN artists a ON a.id = r.artist_id
WHERE p.user_id = ? %s
GROUP BY r.id %s
ORDER BY %s LIMIT ? OFFSET ?`, where, having, order)
	rows, err := l.db.QueryContext(ctx, q, append(append([]any{user}, args...), limit, offset)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Album
	for rows.Next() {
		var (
			al                Album
			rid, aid, added   int64
			lastPlayed, plays int64
			hasArt            bool
		)
		if err := rows.Scan(&rid, &al.Name, &al.Year, &hasArt, &aid, &al.Artist,
			&al.SongCount, &al.Duration, &added, &lastPlayed, &plays); err != nil {
			return nil, err
		}
		al.ID, al.ArtistID, al.Created = albumID(rid), artistID(aid), iso(added)
		if hasArt {
			al.CoverArt = albumID(rid)
		}
		out = append(out, al)
	}
	return out, rows.Err()
}

// ---------------------------------------------------------------- search

func likePattern(q string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return "%" + r.Replace(strings.TrimSpace(q)) + "%"
}

// ---------------------------------------------------------------- playlists

func (l library) playlists(ctx context.Context, user int64, id int64) ([]Playlist, error) {
	q := `
SELECT pl.id, pl.name, u.name, pl.created_at, pl.updated_at,
       (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = pl.id),
       COALESCE((SELECT SUM((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = e.track_id))
                 FROM playlist_entries e WHERE e.playlist_id = pl.id), 0)
FROM playlists pl JOIN users u ON u.id = pl.owner_id
WHERE pl.owner_id = ? AND (? = 0 OR pl.id = ?)
ORDER BY pl.name`
	rows, err := l.db.QueryContext(ctx, q, user, id, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Playlist
	for rows.Next() {
		var (
			p                 Playlist
			pid, created, upd int64
		)
		if err := rows.Scan(&pid, &p.Name, &p.Owner, &created, &upd, &p.SongCount, &p.Duration); err != nil {
			return nil, err
		}
		p.ID, p.Created, p.Changed = playlistID(pid), iso(created), iso(upd)
		out = append(out, p)
	}
	return out, rows.Err()
}

func (l library) playlistEntries(ctx context.Context, user, id int64) ([]Child, error) {
	// Entries are listed in playlist order; songSelect's pointer scope means an
	// entry for a Track this account dismissed shows as a gap (§4.1), not an error.
	q := songSelect + ` AND t.id IN (SELECT track_id FROM playlist_entries WHERE playlist_id = ?)`
	rows, err := l.db.QueryContext(ctx, q, user, id)
	if err != nil {
		return nil, err
	}
	songs, err := scanSongs(rows)
	if err != nil {
		return nil, err
	}
	byID := map[string]Child{}
	for _, s := range songs {
		byID[s.ID] = s
	}
	order, err := l.db.QueryContext(ctx, "SELECT track_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id)
	if err != nil {
		return nil, err
	}
	defer order.Close()
	var out []Child
	for order.Next() {
		var tid int64
		if err := order.Scan(&tid); err != nil {
			return nil, err
		}
		if s, ok := byID[trackID(tid)]; ok {
			out = append(out, s)
		}
	}
	return out, order.Err()
}

// ---------------------------------------------------------------- files

// object returns the Store-relative path and content type to serve for a Track.
func (l library) object(ctx context.Context, user, id int64) (rel, contentType string, missing bool, err error) {
	err = l.db.QueryRowContext(ctx, `
SELECT o.rel_path, o.content_type, o.missing
FROM pointers p JOIN objects o ON o.track_id = p.track_id
WHERE p.user_id = ? AND p.track_id = ?
ORDER BY o.missing, COALESCE(o.hash = p.pin_hash, 0) DESC, o.size DESC LIMIT 1`, user, id,
	).Scan(&rel, &contentType, &missing)
	if errors.Is(err, sql.ErrNoRows) {
		err = errNotFound
	}
	return
}

func (l library) releaseArt(ctx context.Context, id string) (string, error) {
	var art sql.NullString
	var err error
	switch {
	case strings.HasPrefix(id, "al-"):
		n, _ := parseID(id, "al-")
		err = l.db.QueryRowContext(ctx, "SELECT art_path FROM releases WHERE id = ?", n).Scan(&art)
	case strings.HasPrefix(id, "tr-"):
		n, _ := parseID(id, "tr-")
		err = l.db.QueryRowContext(ctx,
			"SELECT r.art_path FROM tracks t JOIN releases r ON r.id = t.release_id WHERE t.id = ?", n).Scan(&art)
	case strings.HasPrefix(id, "ar-"):
		n, _ := parseID(id, "ar-")
		err = l.db.QueryRowContext(ctx,
			"SELECT art_path FROM releases WHERE artist_id = ? AND art_path IS NOT NULL LIMIT 1", n).Scan(&art)
	default:
		return "", errNotFound
	}
	if errors.Is(err, sql.ErrNoRows) || (err == nil && !art.Valid) {
		return "", errNotFound
	}
	return art.String, err
}
