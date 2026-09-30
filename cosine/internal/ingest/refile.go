package ingest

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
)

// Identity is a Track's resolved identity and whether a person has reviewed it.
type Identity struct {
	resolve.Result
	Reviewed bool `json:"reviewed"`
}

var ErrNoTrack = errors.New("no such track")

// Identity reads a Track's current identity: the snapshot a correction
// records as "before".
func (g *Ingester) Identity(ctx context.Context, trackID int64) (Identity, error) {
	return readIdentity(ctx, g.DB, trackID)
}

type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func readIdentity(ctx context.Context, q querier, trackID int64) (Identity, error) {
	var id Identity
	var trackArtist string
	var trackNo, discNo, year sql.NullInt64
	var rec, rel, rg, art sql.NullString
	err := q.QueryRowContext(ctx, `
		SELECT ra.name, r.title, t.title, ta.name, t.track_no, t.disc_no, r.year,
		       t.resolver, t.tier, t.confidence, t.reviewed, t.mbid, r.mbid, r.release_group_mbid, ra.mbid
		FROM tracks t JOIN releases r ON r.id = t.release_id
		JOIN artists ra ON ra.id = r.artist_id JOIN artists ta ON ta.id = t.artist_id
		WHERE t.id = ?`, trackID,
	).Scan(&id.Artist, &id.Release, &id.Title, &trackArtist, &trackNo, &discNo, &year,
		&id.Source, &id.Tier, &id.Confidence, &id.Reviewed, &rec, &rel, &rg, &art)
	if errors.Is(err, sql.ErrNoRows) {
		return Identity{}, ErrNoTrack
	}
	if trackArtist != id.Artist {
		id.TrackArtist = trackArtist
	}
	id.TrackNo, id.DiscNo, id.Year = int(trackNo.Int64), int(discNo.Int64), int(year.Int64)
	id.IDs = resolve.IDs{MBRecording: rec.String, MBRelease: rel.String, MBReleaseGroup: rg.String, MBArtist: art.String}
	return id, err
}

// Refile gives a Track a new identity (§3.2: corrections are global) and
// moves its files to match. Identity is the content hash, so this is a
// move, not a rebuild: Pointers, playlists and play history are untouched.
//
// If another Track already has the new identity, the two are the same
// song, and this one is merged into it: its files become versions of that
// Track and every account's state moves across. The surviving Track's id
// is returned with merged=true.
func (g *Ingester) Refile(ctx context.Context, trackID int64, to Identity) (finalID int64, merged bool, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if strings.TrimSpace(to.Artist) == "" || strings.TrimSpace(to.Title) == "" {
		return 0, false, errors.New("an artist and a title are required")
	}
	if strings.TrimSpace(to.Release) == "" {
		to.Release = to.Title // a single is a release of one song (§2.2)
	}
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil {
		return 0, false, err
	}

	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, false, err
	}
	defer tx.Rollback()

	var oldRelease int64
	if err := tx.QueryRowContext(ctx, "SELECT release_id FROM tracks WHERE id = ?", trackID).Scan(&oldRelease); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, false, ErrNoTrack
		}
		return 0, false, err
	}
	ids, err := upsertIdentity(ctx, tx, to.Result, true)
	if err != nil {
		return 0, false, err
	}

	finalID = trackID
	var existing int64
	err = tx.QueryRowContext(ctx, "SELECT id FROM tracks WHERE release_id = ? AND title = ? AND id != ?",
		ids.release, to.Title, trackID).Scan(&existing)
	switch {
	case err == nil:
		if err := merge(ctx, tx, trackID, existing); err != nil {
			return 0, false, err
		}
		finalID, merged = existing, true
	case !errors.Is(err, sql.ErrNoRows):
		return 0, false, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE tracks SET release_id = ?, artist_id = ?, title = ?, track_no = NULLIF(?, 0), disc_no = NULLIF(?, 0),
		       resolver = ?, tier = ?, confidence = ?, reviewed = ?, mbid = NULLIF(?, '')
		WHERE id = ?`,
		ids.release, ids.trackArtist, to.Title, to.TrackNo, to.DiscNo,
		to.Source, to.Tier, to.Confidence, to.Reviewed, to.IDs.MBRecording, finalID); err != nil {
		return 0, false, err
	}

	// A release left behind hands its cover to the new one if that has none.
	if oldRelease != ids.release {
		if _, err := tx.ExecContext(ctx, `
			UPDATE releases SET art_path = (SELECT art_path FROM releases WHERE id = ?),
			                    art_source = (SELECT art_source FROM releases WHERE id = ?)
			WHERE id = ? AND art_path IS NULL
			  AND NOT EXISTS (SELECT 1 FROM tracks WHERE release_id = ?)`,
			oldRelease, oldRelease, ids.release, oldRelease); err != nil {
			return 0, false, err
		}
	}

	moves, err := g.moveFiles(ctx, tx, root, finalID, to.Result)
	if err != nil {
		return 0, false, err
	}
	orphanArt, err := removeOrphans(ctx, tx)
	if err != nil {
		undoMoves(root, moves)
		return 0, false, err
	}
	db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(finalID, 10), "upsert")
	if merged {
		db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(trackID, 10), "delete")
	}
	if err := tx.Commit(); err != nil {
		undoMoves(root, moves)
		return 0, false, err
	}

	for _, m := range moves {
		pruneUp(root, filepath.Dir(m.from))
	}
	for _, a := range orphanArt {
		os.Remove(filepath.Join(g.DataDir, filepath.FromSlash(a)))
	}
	g.offerArtwork(ctx, ids.release, to.IDs)
	return finalID, merged, nil
}

type move struct{ from, to, hash string }

// moveFiles puts every file of a Track at the path its identity implies.
func (g *Ingester) moveFiles(ctx context.Context, tx *sql.Tx, root string, trackID int64, to resolve.Result) ([]move, error) {
	rows, err := tx.QueryContext(ctx, "SELECT hash, rel_path, suffix, missing FROM objects WHERE track_id = ?", trackID)
	if err != nil {
		return nil, err
	}
	type obj struct {
		hash, rel, suffix string
		missing           bool
	}
	var objs []obj
	for rows.Next() {
		var o obj
		if err := rows.Scan(&o.hash, &o.rel, &o.suffix, &o.missing); err != nil {
			rows.Close()
			return nil, err
		}
		objs = append(objs, o)
	}
	rows.Close()

	var moves []move
	for _, o := range objs {
		want := store.Layout(to.Artist, to.Release, to.Title, to.TrackNo, o.suffix)
		if filepath.ToSlash(want) == o.rel {
			continue
		}
		if taken(ctx, tx, root, want) {
			want = store.Versioned(want, o.hash)
			if filepath.ToSlash(want) == o.rel {
				continue
			}
		}
		if !o.missing {
			if err := store.Place(root, want, filepath.Join(root, filepath.FromSlash(o.rel)), o.hash); err != nil {
				undoMoves(root, moves)
				return nil, err
			}
			moves = append(moves, move{from: filepath.FromSlash(o.rel), to: want, hash: o.hash})
		}
		// A missing file's path changes too, so it is found where it belongs if it returns.
		if _, err := tx.ExecContext(ctx, "UPDATE objects SET rel_path = ? WHERE hash = ?", filepath.ToSlash(want), o.hash); err != nil {
			undoMoves(root, moves)
			return nil, err
		}
	}
	return moves, nil
}

func undoMoves(root string, moves []move) {
	for i := len(moves) - 1; i >= 0; i-- {
		m := moves[i]
		store.Place(root, m.from, filepath.Join(root, m.to), m.hash)
	}
}

// merge folds Track from into Track to: files become versions of it, and
// each account's Pointer, plays, playlists and dismissals follow.
func merge(ctx context.Context, tx *sql.Tx, from, to int64) error {
	for _, q := range []string{
		"UPDATE objects SET track_id = ?2 WHERE track_id = ?1",
		`INSERT OR IGNORE INTO pointers(user_id, track_id, added_at, starred_at, pin_hash)
		 SELECT user_id, ?2, added_at, starred_at, pin_hash FROM pointers WHERE track_id = ?1`,
		"DELETE FROM pointers WHERE track_id = ?1",
		`INSERT OR IGNORE INTO dismissed(user_id, track_id, dismissed_at)
		 SELECT user_id, ?2, dismissed_at FROM dismissed d WHERE track_id = ?1
		 AND NOT EXISTS (SELECT 1 FROM pointers p WHERE p.user_id = d.user_id AND p.track_id = ?2)`,
		"DELETE FROM dismissed WHERE track_id = ?1",
		`INSERT OR IGNORE INTO plays(user_id, track_id, played_at)
		 SELECT user_id, ?2, played_at FROM plays WHERE track_id = ?1`,
		"DELETE FROM plays WHERE track_id = ?1",
		"UPDATE playlist_entries SET track_id = ?2 WHERE track_id = ?1",
		"UPDATE ingest_jobs SET track_id = ?2 WHERE track_id = ?1",
		"UPDATE corrections SET track_id = ?2 WHERE track_id = ?1",
		"DELETE FROM tracks WHERE id = ?1",
	} {
		if _, err := tx.ExecContext(ctx, q, from, to); err != nil {
			return err
		}
	}
	return nil
}

// removeOrphans deletes releases with no tracks and artists with nothing
// left, returning cover files to delete once the change is committed.
func removeOrphans(ctx context.Context, tx *sql.Tx) ([]string, error) {
	rows, err := tx.QueryContext(ctx, `
		SELECT art_path FROM releases r WHERE art_path IS NOT NULL
		AND NOT EXISTS (SELECT 1 FROM tracks t WHERE t.release_id = r.id)
		AND NOT EXISTS (SELECT 1 FROM releases o WHERE o.art_path = r.art_path AND o.id != r.id
		                AND EXISTS (SELECT 1 FROM tracks t2 WHERE t2.release_id = o.id))`)
	if err != nil {
		return nil, err
	}
	var art []string
	for rows.Next() {
		var p string
		rows.Scan(&p)
		art = append(art, p)
	}
	rows.Close()
	for _, q := range []string{
		"DELETE FROM releases WHERE NOT EXISTS (SELECT 1 FROM tracks t WHERE t.release_id = releases.id)",
		`DELETE FROM artists WHERE NOT EXISTS (SELECT 1 FROM releases r WHERE r.artist_id = artists.id)
		 AND NOT EXISTS (SELECT 1 FROM tracks t WHERE t.artist_id = artists.id)`,
	} {
		if _, err := tx.ExecContext(ctx, q); err != nil {
			return nil, err
		}
	}
	return art, nil
}

// pruneUp removes directories emptied by a move, up to the Store root.
func pruneUp(root, rel string) {
	for rel != "." && rel != "" && rel != string(filepath.Separator) {
		if os.Remove(filepath.Join(root, rel)) != nil {
			return
		}
		rel = filepath.Dir(rel)
	}
}
