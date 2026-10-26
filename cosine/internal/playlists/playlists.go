// Package playlists is where personal organisation lives (§2.2, §2.4):
// playlists made by hand, freely ordered, and smart playlists — saved rules
// over one's own Library and plays, the same objects Home tiles look at.
//
// A manual playlist's entry whose Track is gone from the account (removed,
// or its file missing) keeps its place but is not shown (§4.1). Edits are
// made against what is shown, so those hidden entries are carried along
// beside the song they followed rather than lost.
package playlists

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
)

var (
	ErrNotFound = errors.New("no such playlist")
	// ErrNotInLibrary: a playlist holds songs of the account's own Library.
	ErrNotInLibrary = errors.New("a song to add is not in your library")
	ErrName         = errors.New("a playlist needs a name")
	ErrLongName     = fmt.Errorf("a playlist's name can be at most %d characters", maxName)
)

const maxName = 200

type Service struct {
	DB *db.DB
	// Stats evaluates smart playlists' rules; nil: no smart playlists.
	Stats *stats.Service
}

// Edit changes a manual playlist. Applied in order: the new name, then
// Tracks (a full replacement, how clients reorder), then RemoveAt (indexes
// into the songs as shown before this edit), then Add.
type Edit struct {
	Name     *string
	Tracks   []int64
	Replace  bool // Tracks replaces the songs, even when empty
	RemoveAt []int
	Add      []int64
}

// Create makes a playlist owned by user, holding tracks in order.
func (s *Service) Create(ctx context.Context, user int64, name string, tracks []int64) (int64, error) {
	name, err := cleanName(name)
	if err != nil {
		return 0, err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if err := inLibrary(ctx, tx, user, tracks); err != nil {
		return 0, err
	}
	now := db.Now()
	res, err := tx.ExecContext(ctx, "INSERT INTO playlists(owner_id, name, created_at, updated_at) VALUES(?, ?, ?, ?)",
		user, name, now, now)
	if err != nil {
		return 0, err
	}
	id, _ := res.LastInsertId()
	if err := write(ctx, tx, id, tracks); err != nil {
		return 0, err
	}
	if err := db.LogChange(ctx, tx, user, "playlist", strconv.FormatInt(id, 10), "add"); err != nil {
		return 0, err
	}
	return id, tx.Commit()
}

// Edit applies an Edit to one of user's playlists.
func (s *Service) Edit(ctx context.Context, user, id int64, e Edit) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := owned(ctx, tx, user, id); err != nil {
		return err
	}
	if e.Name != nil {
		name, err := cleanName(*e.Name)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE playlists SET name = ? WHERE id = ?", name, id); err != nil {
			return err
		}
	}
	if err := inLibrary(ctx, tx, user, append(append([]int64{}, e.Tracks...), e.Add...)); err != nil {
		return err
	}
	entries, err := load(ctx, tx, user, id)
	if err != nil {
		return err
	}
	if e.Replace || len(e.Tracks) > 0 {
		entries = replace(entries, e.Tracks)
	}
	if len(e.RemoveAt) > 0 {
		entries = removeAt(entries, e.RemoveAt)
	}
	for _, t := range e.Add {
		entries = append(entries, entry{track: t, shown: true})
	}
	tracks := make([]int64, len(entries))
	for i, en := range entries {
		tracks[i] = en.track
	}
	if err := write(ctx, tx, id, tracks); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE playlists SET updated_at = ? WHERE id = ?", db.Now(), id); err != nil {
		return err
	}
	if err := db.LogChange(ctx, tx, user, "playlist", strconv.FormatInt(id, 10), "update"); err != nil {
		return err
	}
	return tx.Commit()
}

// Delete removes one of user's playlists. Its songs stay in the Library.
func (s *Service) Delete(ctx context.Context, user, id int64) error {
	res, err := s.DB.ExecContext(ctx, "DELETE FROM playlists WHERE id = ? AND owner_id = ?", id, user)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return db.LogChange(ctx, s.DB, user, "playlist", strconv.FormatInt(id, 10), "remove")
}

// Place puts track into a playlist being filled from a source, keeping the
// source's order however the downloads finish: before the first song that
// comes later in the source (places gives each placed song's position
// there), else at the end. Songs added by hand meanwhile have no position
// and are left be. A song already in the playlist isn't added twice.
func (s *Service) Place(ctx context.Context, user, id, track int64, at int, places map[int64]int) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := owned(ctx, tx, user, id); err != nil {
		return err
	}
	entries, err := load(ctx, tx, user, id)
	if err != nil {
		return err
	}
	i := len(entries)
	for j, en := range entries {
		if en.track == track {
			return nil
		}
		if p, ok := places[en.track]; ok && p > at && i == len(entries) {
			i = j
		}
	}
	entries = append(entries[:i], append([]entry{{track: track, shown: true}}, entries[i:]...)...)
	tracks := make([]int64, len(entries))
	for k, en := range entries {
		tracks[k] = en.track
	}
	if err := write(ctx, tx, id, tracks); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE playlists SET updated_at = ? WHERE id = ?", db.Now(), id); err != nil {
		return err
	}
	if err := db.LogChange(ctx, tx, user, "playlist", strconv.FormatInt(id, 10), "update"); err != nil {
		return err
	}
	return tx.Commit()
}

// ---------------------------------------------------------------- entries

type entry struct {
	track int64
	shown bool
}

// load lists a playlist's entries in order, marking those the owner can see.
func load(ctx context.Context, tx *sql.Tx, user, id int64) ([]entry, error) {
	rows, err := tx.QueryContext(ctx, `
		SELECT e.track_id,
		       EXISTS (SELECT 1 FROM pointers p WHERE p.user_id = ? AND p.track_id = e.track_id)
		       AND EXISTS (SELECT 1 FROM objects ox WHERE ox.track_id = e.track_id AND ox.missing = 0)
		FROM playlist_entries e WHERE e.playlist_id = ? ORDER BY e.position`, user, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []entry
	for rows.Next() {
		var en entry
		if err := rows.Scan(&en.track, &en.shown); err != nil {
			return nil, err
		}
		out = append(out, en)
	}
	return out, rows.Err()
}

// replace sets the shown songs to tracks. Each hidden entry stays after the
// shown song it followed, when that song is still there, else at the end.
func replace(entries []entry, tracks []int64) []entry {
	type hidden struct {
		after int64 // the shown track it followed; 0: none
		entry
	}
	var hiddens []hidden
	var after int64
	for _, en := range entries {
		if en.shown {
			after = en.track
		} else {
			hiddens = append(hiddens, hidden{after, en})
		}
	}
	placed := map[int64]bool{}
	follow := func(out []entry, t int64) []entry {
		placed[t] = true
		for _, h := range hiddens {
			if h.after == t {
				out = append(out, h.entry)
			}
		}
		return out
	}
	out := follow(nil, 0)
	for _, t := range tracks {
		out = append(out, entry{track: t, shown: true})
		if !placed[t] {
			out = follow(out, t)
		}
	}
	for _, h := range hiddens {
		if !placed[h.after] {
			out = append(out, h.entry)
		}
	}
	return out
}

// removeAt drops shown songs by their index among the shown songs.
func removeAt(entries []entry, indexes []int) []entry {
	drop := map[int]bool{}
	for _, i := range indexes {
		drop[i] = true
	}
	out := entries[:0:0]
	shown := 0
	for _, en := range entries {
		if en.shown {
			shown++
			if drop[shown-1] {
				continue
			}
		}
		out = append(out, en)
	}
	return out
}

func write(ctx context.Context, tx *sql.Tx, id int64, tracks []int64) error {
	if _, err := tx.ExecContext(ctx, "DELETE FROM playlist_entries WHERE playlist_id = ?", id); err != nil {
		return err
	}
	for i, t := range tracks {
		if _, err := tx.ExecContext(ctx, "INSERT INTO playlist_entries(playlist_id, position, track_id) VALUES(?, ?, ?)",
			id, i, t); err != nil {
			return err
		}
	}
	return nil
}

func owned(ctx context.Context, tx *sql.Tx, user, id int64) error {
	var n int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM playlists WHERE id = ? AND owner_id = ?", id, user).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// inLibrary checks every track is one the account has, with a file.
func inLibrary(ctx context.Context, tx *sql.Tx, user int64, tracks []int64) error {
	seen := map[int64]bool{}
	for _, t := range tracks {
		if seen[t] {
			continue
		}
		seen[t] = true
		var ok bool
		err := tx.QueryRowContext(ctx, `
			SELECT EXISTS (SELECT 1 FROM pointers p JOIN tracks t ON t.id = p.track_id
			               WHERE p.user_id = ? AND p.track_id = ? AND `+db.Available+`)`, user, t).Scan(&ok)
		if err != nil {
			return err
		}
		if !ok {
			return ErrNotInLibrary
		}
	}
	return nil
}

func cleanName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", ErrName
	}
	if len(name) > maxName {
		return "", ErrLongName
	}
	return name, nil
}
