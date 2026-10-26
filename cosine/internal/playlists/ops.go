package playlists

import (
	"context"
	"database/sql"
	"slices"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Op is one playlist edit made on a phone, possibly offline, and replayed
// here (§5.2). It says what was done, not the list that resulted, so edits
// made elsewhere meanwhile are kept. Entries are named by their track and
// which of that track's entries it is (Nth, from 0), so a song in a
// playlist twice can be told apart; both are counted in the playlist as it
// is just before the op, hidden entries included. Sine's own copy applies
// the same rules (PlaylistOps), so what the phone shows is what results.
type Op struct {
	ID   string // unique per edit: one applied before is skipped
	Kind string // OpCreate, OpRename, OpAdd, OpRemove, OpMove, OpDelete
	// The playlist, or for one made offline, the ID of the create that made it.
	Playlist int64
	New      string
	Name     string  // create, rename
	Tracks   []int64 // create, add (to the end)
	Track    int64   // remove, move
	Nth      int
	After    int64 // move: to just after this entry; 0: to the top
	AfterNth int
}

const (
	OpCreate = "create"
	OpRename = "rename"
	OpAdd    = "add"
	OpRemove = "remove"
	OpMove   = "move"
	OpDelete = "delete"
)

// OpResult is what became of one Op. Skipped says why it changed nothing
// (the playlist or song was gone, say); that's an answer, not a failure,
// and the phone drops the op either way.
type OpResult struct {
	ID       string
	Playlist int64 // the playlist it was applied to; for a create, the one made
	Skipped  string
}

// Why an op changed nothing.
const (
	SkipGone    = "gone"      // the playlist was deleted, or you're no longer on it
	SkipMissing = "missing"   // the song, or the one to move it after, isn't there any more
	SkipName    = "bad-name"  // empty or too long
	SkipKind    = "bad-op"    // not an op this server knows
	SkipNoSongs = "not-yours" // none of the songs to add are in your Library
)

const opsKept = 365 * 24 * 60 * 60 * 1000

// Apply replays a phone's ops in order, each once. Only a database error
// fails it; the phone then sends them all again, and those already applied
// are skipped.
func (s *Service) Apply(ctx context.Context, user int64, ops []Op) ([]OpResult, error) {
	// Ops are remembered for a year: longer than any phone stays offline.
	if _, err := s.DB.ExecContext(ctx, "DELETE FROM playlist_ops WHERE applied_at < ?", db.Now()-opsKept); err != nil {
		return nil, err
	}
	out := make([]OpResult, 0, len(ops))
	for _, op := range ops {
		r, err := s.apply(ctx, user, op)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, nil
}

func (s *Service) apply(ctx context.Context, user int64, op Op) (OpResult, error) {
	res := OpResult{ID: op.ID}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return res, err
	}
	defer tx.Rollback()
	var before sql.NullInt64
	switch err := tx.QueryRowContext(ctx, "SELECT playlist_id FROM playlist_ops WHERE user_id = ? AND op_id = ?",
		user, op.ID).Scan(&before); err {
	case nil:
		res.Playlist = before.Int64
		return res, nil // applied before; its answer may not have reached the phone
	case sql.ErrNoRows:
	default:
		return res, err
	}
	res.Playlist, res.Skipped, err = s.run(ctx, tx, user, op)
	if err != nil {
		return res, err
	}
	var made any
	if res.Playlist != 0 {
		made = res.Playlist
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO playlist_ops(user_id, op_id, playlist_id, applied_at) VALUES(?, ?, ?, ?)",
		user, op.ID, made, db.Now()); err != nil {
		return res, err
	}
	return res, tx.Commit()
}

func (s *Service) run(ctx context.Context, tx *sql.Tx, user int64, op Op) (playlist int64, skipped string, err error) {
	if op.Kind == OpCreate {
		name, err := cleanName(op.Name)
		if err != nil {
			return 0, SkipName, nil
		}
		tracks, err := yours(ctx, tx, user, op.Tracks)
		if err != nil {
			return 0, "", err
		}
		now := db.Now()
		res, err := tx.ExecContext(ctx, "INSERT INTO playlists(owner_id, name, created_at, updated_at) VALUES(?, ?, ?, ?)",
			user, name, now, now)
		if err != nil {
			return 0, "", err
		}
		id, _ := res.LastInsertId()
		if err := write(ctx, tx, id, tracks); err != nil {
			return 0, "", err
		}
		return id, "", db.LogPlaylist(ctx, tx, id, "add")
	}

	id := op.Playlist
	if op.New != "" {
		var made sql.NullInt64
		err := tx.QueryRowContext(ctx, "SELECT playlist_id FROM playlist_ops WHERE user_id = ? AND op_id = ?",
			user, op.New).Scan(&made)
		if err == sql.ErrNoRows || !made.Valid {
			return 0, SkipGone, nil
		}
		if err != nil {
			return 0, "", err
		}
		id = made.Int64
	}
	owner, err := access(ctx, tx, user, id)
	if err == ErrNotFound {
		return 0, SkipGone, nil
	}
	if err != nil {
		return 0, "", err
	}

	switch op.Kind {
	case OpRename:
		name, err := cleanName(op.Name)
		if err != nil {
			return id, SkipName, nil
		}
		if _, err := tx.ExecContext(ctx, "UPDATE playlists SET name = ?, updated_at = ? WHERE id = ?", name, db.Now(), id); err != nil {
			return 0, "", err
		}
		return id, "", db.LogPlaylist(ctx, tx, id, "update")

	case OpDelete:
		// Deleting a playlist shared with you leaves it, as Delete does.
		change, q := "update", "DELETE FROM playlist_members WHERE playlist_id = ? AND user_id = ?"
		if owner {
			change, q = "remove", "DELETE FROM playlists WHERE id = ? AND owner_id = ?"
		}
		if err := db.LogPlaylist(ctx, tx, id, change); err != nil {
			return 0, "", err
		}
		_, err := tx.ExecContext(ctx, q, id, user)
		return id, "", err
	}

	entries, err := load(ctx, tx, user, id)
	if err != nil {
		return 0, "", err
	}
	tracks := tracksOf(entries)
	switch op.Kind {
	case OpAdd:
		add, err := yours(ctx, tx, user, op.Tracks)
		if err != nil {
			return 0, "", err
		}
		if len(add) == 0 {
			return id, SkipNoSongs, nil
		}
		tracks = append(tracks, add...)
	case OpRemove:
		i := nth(tracks, op.Track, op.Nth)
		if i < 0 {
			return id, SkipMissing, nil
		}
		tracks = slices.Delete(tracks, i, i+1)
	case OpMove:
		var ok bool
		if tracks, ok = Move(tracks, op.Track, op.Nth, op.After, op.AfterNth); !ok {
			return id, SkipMissing, nil
		}
	default:
		return id, SkipKind, nil
	}
	return id, "", save(ctx, tx, id, tracks)
}

// Move moves the Nth entry of track to just after the afterNth entry of
// after, or to the top when after is 0, both counted in tracks as given.
// False when either isn't there.
func Move(tracks []int64, track int64, n int, after int64, afterNth int) ([]int64, bool) {
	from := nth(tracks, track, n)
	if from < 0 {
		return tracks, false
	}
	to := 0
	if after != 0 {
		a := nth(tracks, after, afterNth)
		if a < 0 {
			return tracks, false
		}
		if a == from {
			return tracks, true
		}
		to = a + 1
		if a > from {
			to-- // counted before the entry left its place
		}
	}
	out := slices.Delete(slices.Clone(tracks), from, from+1)
	return slices.Insert(out, to, track), true
}

// nth finds the index of track's nth entry (from 0); -1 if there isn't one.
func nth(tracks []int64, track int64, n int) int {
	seen := 0
	for i, t := range tracks {
		if t == track {
			if seen == n {
				return i
			}
			seen++
		}
	}
	return -1
}

// yours keeps the tracks in user's Library with a file, in order.
func yours(ctx context.Context, tx *sql.Tx, user int64, tracks []int64) ([]int64, error) {
	out := make([]int64, 0, len(tracks))
	for _, t := range tracks {
		switch err := inLibrary(ctx, tx, user, []int64{t}); err {
		case nil:
			out = append(out, t)
		case ErrNotInLibrary:
		default:
			return nil, err
		}
	}
	return out, nil
}
