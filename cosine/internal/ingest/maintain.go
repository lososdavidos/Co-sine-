package ingest

import (
	"context"
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
		_, statErr := os.Stat(filepath.Join(root, filepath.FromSlash(o.rel)))
		gone := errors.Is(statErr, os.ErrNotExist)
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
