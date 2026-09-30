package ingest

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
)

// These live beside Ingest because they also write the Store, and the
// Ingester's lock is what keeps it to one writer.

// Verify marks Objects whose files have gone missing, and clears the mark on
// those that came back. A missing file greys out rather than disappearing (§2.2).
func (g *Ingester) Verify(ctx context.Context) (missing int, err error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil || root == "" {
		return 0, err
	}
	type obj struct {
		hash, rel string
		was       bool
	}
	rows, err := g.DB.QueryContext(ctx, "SELECT hash, rel_path, missing FROM objects")
	if err != nil {
		return 0, err
	}
	var all []obj
	for rows.Next() {
		var o obj
		if err := rows.Scan(&o.hash, &o.rel, &o.was); err != nil {
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
		}
	}
	return missing, nil
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
