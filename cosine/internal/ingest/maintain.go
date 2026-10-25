package ingest

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
)

// These live beside Ingest because they also write the Store, and the
// Ingester's lock is what keeps it to one writer.

// Verify marks Objects whose files have gone missing, and clears the mark on
// those that came back. A Track with no file left isn't offered, and comes
// back when a file does (§2.2). The server runs it every few minutes.
func (g *Ingester) Verify(ctx context.Context) (missing int, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.verify(ctx)
}

func (g *Ingester) verify(ctx context.Context) (missing int, err error) {
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil || root == "" {
		return 0, err
	}
	type obj struct {
		hash, rel string
		track     int64
		was       bool
	}
	rows, err := g.DB.QueryContext(ctx, "SELECT hash, rel_path, track_id, missing FROM objects")
	if err != nil {
		return 0, err
	}
	var all []obj
	for rows.Next() {
		var o obj
		if err := rows.Scan(&o.hash, &o.rel, &o.track, &o.was); err != nil {
			rows.Close()
			return 0, err
		}
		all = append(all, o)
	}
	rows.Close()
	for _, o := range all {
		gone := !store.Present(root, o.rel)
		if gone {
			missing++
		}
		if gone != o.was {
			if _, err := g.DB.ExecContext(ctx, "UPDATE objects SET missing = ? WHERE hash = ?", gone, o.hash); err != nil {
				return missing, err
			}
			// The Track left or rejoined every library it is in.
			db.LogChange(ctx, g.DB, 0, "track", strconv.FormatInt(o.track, 10), "upsert")
		}
	}
	return missing, nil
}

// ErrStoreGone refuses to forget when the Store can't be read or every file
// in it is missing: that is an unmounted disk, not deleted music.
var ErrStoreGone = errors.New("every file in the Store is missing, so its disk is probably not mounted; nothing was forgotten")

// Forget drops the records of files that are gone from the Store, after
// checking again. Their Tracks keep their plays, stars and playlist places
// out of sight, and get them back if the same song is ever added again.
func (g *Ingester) Forget(ctx context.Context) (forgotten int, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	missing, err := g.verify(ctx)
	if err != nil || missing == 0 {
		return 0, err
	}
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil {
		return 0, err
	}
	var total int
	if err := g.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM objects").Scan(&total); err != nil {
		return 0, err
	}
	if fi, statErr := os.Stat(root); statErr != nil || !fi.IsDir() || missing == total {
		return 0, ErrStoreGone
	}

	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, "SELECT hash, track_id FROM objects WHERE missing = 1")
	if err != nil {
		return 0, err
	}
	type obj struct {
		hash  string
		track int64
	}
	var gone []obj
	for rows.Next() {
		var o obj
		if err := rows.Scan(&o.hash, &o.track); err != nil {
			rows.Close()
			return 0, err
		}
		gone = append(gone, o)
	}
	rows.Close()
	for _, o := range gone {
		for _, q := range []string{
			"UPDATE pointers SET pin_hash = NULL WHERE pin_hash = ?", // a version pin on the forgotten file
			"DELETE FROM objects WHERE hash = ?",
		} {
			if _, err := tx.ExecContext(ctx, q, o.hash); err != nil {
				return 0, err
			}
		}
		if err := db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(o.track, 10), "upsert"); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	store.PruneEmptyDirs(root) // the folders the deleted files left
	return len(gone), nil
}

// Relocate moves every Object to a new Store root and switches the setting.
// Objects are addressed relative to the root, so nothing else changes (§2.2).
// Files already moved stay moved if it fails part-way; the setting only
// switches once all have arrived, and a retry picks up where it stopped.
func (g *Ingester) Relocate(ctx context.Context, newRoot string) (moved int, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	oldRoot, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil {
		return 0, err
	}
	newRoot = filepath.Clean(newRoot)
	if oldRoot == "" || filepath.Clean(oldRoot) == newRoot {
		return 0, g.DB.SetSetting(ctx, db.SettingStorePath, newRoot)
	}
	if err := os.MkdirAll(newRoot, 0o755); err != nil {
		return 0, err
	}
	rows, err := g.DB.QueryContext(ctx, "SELECT hash, rel_path FROM objects")
	if err != nil {
		return 0, err
	}
	type obj struct{ hash, rel string }
	var all []obj
	for rows.Next() {
		var o obj
		if err := rows.Scan(&o.hash, &o.rel); err != nil {
			rows.Close()
			return 0, err
		}
		all = append(all, o)
	}
	rows.Close()
	for _, o := range all {
		rel := filepath.FromSlash(o.rel)
		if _, err := os.Stat(filepath.Join(newRoot, rel)); err == nil {
			continue // arrived on an earlier attempt
		}
		if _, err := os.Stat(filepath.Join(oldRoot, rel)); errors.Is(err, os.ErrNotExist) {
			continue // already missing; Verify reports it
		}
		if err := store.Move(oldRoot, newRoot, rel, o.hash); err != nil {
			return moved, fmt.Errorf("moving %s: %w", o.rel, err)
		}
		moved++
	}
	if err := g.DB.SetSetting(ctx, db.SettingStorePath, newRoot); err != nil {
		return moved, err
	}
	store.PruneEmptyDirs(oldRoot)
	return moved, nil
}

// Orphan is a file in the Store whose Track is in no account's Library
// (§2.3): everyone who had it removed it.
type Orphan struct {
	Hash, RelPath, Artist, Title string
	Size                         int64
}

// Orphans lists the Store's orphaned files, largest first.
func (g *Ingester) Orphans(ctx context.Context) ([]Orphan, error) {
	rows, err := g.DB.QueryContext(ctx, `
		SELECT o.hash, o.rel_path, o.size, a.name, t.title
		FROM objects o JOIN tracks t ON t.id = o.track_id JOIN artists a ON a.id = t.artist_id
		WHERE NOT EXISTS (SELECT 1 FROM pointers p WHERE p.track_id = o.track_id)
		ORDER BY o.size DESC, o.rel_path`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Orphan
	for rows.Next() {
		var o Orphan
		if err := rows.Scan(&o.Hash, &o.RelPath, &o.Size, &o.Artist, &o.Title); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

// DeleteOrphans deletes orphaned files from the Store: each of hashes that
// is still an orphan (nobody has added it back meanwhile). The Track and its
// history stay, out of sight, for the same song added again, as with a
// forgotten file. A file that can't be deleted keeps its record.
func (g *Ingester) DeleteOrphans(ctx context.Context, hashes []string) (deleted int, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil || root == "" {
		return 0, err
	}
	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	for _, h := range hashes {
		var rel string
		var track int64
		err := tx.QueryRowContext(ctx, `SELECT o.rel_path, o.track_id FROM objects o WHERE o.hash = ?
			AND NOT EXISTS (SELECT 1 FROM pointers p WHERE p.track_id = o.track_id)`, h).Scan(&rel, &track)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return deleted, err
		}
		// The file goes first: the Store never holds a file the database
		// doesn't know about, and a record left without its file is only
		// a missing file, which the next check notices.
		if err := os.Remove(filepath.Join(root, filepath.FromSlash(rel))); err != nil && !errors.Is(err, os.ErrNotExist) {
			g.Log.Warn("could not delete an orphaned file", "path", rel, "err", err)
			continue
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM objects WHERE hash = ?", h); err != nil {
			return deleted, err
		}
		if err := db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(track, 10), "upsert"); err != nil {
			return deleted, err
		}
		deleted++
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	store.PruneEmptyDirs(root)
	return deleted, nil
}
