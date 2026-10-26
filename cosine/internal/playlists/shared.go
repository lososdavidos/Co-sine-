package playlists

import (
	"context"
	"database/sql"
	"errors"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Shared playlists (§2.3). The owner shares a playlist with another account,
// which sees it as an invitation. Accepting puts the playlist's songs in
// that account's Library — a shared playlist always plays in full — and
// from then on both see and edit the same playlist; songs either adds go
// into every member's Library too. A member can leave; the owner can stop
// sharing with anyone, or delete it for everyone. Smart playlists are never
// shared (§2.4): save one as a playlist first.

var (
	ErrNoAccount = errors.New("no such account")
	ErrSelf      = errors.New("that's your own account")
	ErrNotOwner  = errors.New("only the playlist's owner can share it")
	ErrNoInvite  = errors.New("no such invitation")
)

// Member is someone a playlist is shared with.
type Member struct {
	Name     string
	Accepted bool
}

// Share invites the account named to's to one of owner's playlists.
// Sharing again with someone already on it changes nothing.
func (s *Service) Share(ctx context.Context, owner, id int64, to string) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	isOwner, err := access(ctx, tx, owner, id)
	if err != nil {
		return err
	}
	if !isOwner {
		return ErrNotOwner
	}
	var uid int64
	if err := tx.QueryRowContext(ctx, "SELECT id FROM users WHERE name = ?", to).Scan(&uid); err == sql.ErrNoRows {
		return ErrNoAccount
	} else if err != nil {
		return err
	}
	if uid == owner {
		return ErrSelf
	}
	res, err := tx.ExecContext(ctx, `INSERT INTO playlist_members(playlist_id, user_id, invited_at) VALUES(?, ?, ?)
		ON CONFLICT DO NOTHING`, id, uid, db.Now())
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil
	}
	if err := db.LogPlaylist(ctx, tx, id, "update"); err != nil {
		return err
	}
	return tx.Commit()
}

// Unshare takes an account off one of owner's playlists, invited or not.
// The songs it got from the playlist stay in its Library.
func (s *Service) Unshare(ctx context.Context, owner, id int64, name string) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	isOwner, err := access(ctx, tx, owner, id)
	if err != nil {
		return err
	}
	if !isOwner {
		return ErrNotOwner
	}
	// Told first, while still on it, so the playlist leaves their phone.
	if err := db.LogPlaylist(ctx, tx, id, "update"); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM playlist_members WHERE playlist_id = ?
		AND user_id = (SELECT id FROM users WHERE name = ?)`, id, name); err != nil {
		return err
	}
	return tx.Commit()
}

// Accept takes up an invitation: the playlist's songs user doesn't have go
// into their Library (except any they removed from it themselves), and
// the playlist is theirs to see and edit. Returns how many songs were added.
func (s *Service) Accept(ctx context.Context, user, id int64) (added int, err error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE playlist_members SET accepted_at = ?
		WHERE playlist_id = ? AND user_id = ? AND accepted_at IS NULL`, db.Now(), id, user)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, ErrNoInvite
	}
	added, err = spread(ctx, tx, id, user)
	if err != nil {
		return 0, err
	}
	if err := db.LogPlaylist(ctx, tx, id, "update"); err != nil {
		return 0, err
	}
	return added, tx.Commit()
}

// Decline turns an invitation down.
func (s *Service) Decline(ctx context.Context, user, id int64) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM playlist_members
		WHERE playlist_id = ? AND user_id = ? AND accepted_at IS NULL`, id, user).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		return ErrNoInvite
	}
	if err := db.LogPlaylist(ctx, tx, id, "update"); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM playlist_members WHERE playlist_id = ? AND user_id = ?", id, user); err != nil {
		return err
	}
	return tx.Commit()
}

// Members lists who a playlist is shared with, in the order they were asked.
func (s *Service) Members(ctx context.Context, id int64) ([]Member, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT u.name, m.accepted_at IS NOT NULL FROM playlist_members m
		JOIN users u ON u.id = m.user_id WHERE m.playlist_id = ? ORDER BY m.invited_at, u.name`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Member
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.Name, &m.Accepted); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// spread puts a shared playlist's songs into the Libraries of its owner
// and members (or just into only's, when only isn't 0), skipping any song one of them
// removed from their Library themselves: nothing removed comes back on its
// own (§4.1). A playlist shared with nobody changes nothing. Returns how
// many songs were added to only's Library.
func spread(ctx context.Context, tx *sql.Tx, id, only int64) (int, error) {
	rows, err := tx.QueryContext(ctx, `
		SELECT u.uid, e.track_id FROM (
		    SELECT owner_id AS uid FROM playlists WHERE id = ?1
		    UNION SELECT user_id FROM playlist_members WHERE playlist_id = ?1 AND accepted_at IS NOT NULL) u
		JOIN (SELECT DISTINCT track_id FROM playlist_entries WHERE playlist_id = ?1) e
		WHERE EXISTS (SELECT 1 FROM playlist_members WHERE playlist_id = ?1 AND accepted_at IS NOT NULL)
		  AND (?2 = 0 OR u.uid = ?2)
		  AND NOT EXISTS (SELECT 1 FROM pointers p WHERE p.user_id = u.uid AND p.track_id = e.track_id)
		  AND NOT EXISTS (SELECT 1 FROM dismissed d WHERE d.user_id = u.uid AND d.track_id = e.track_id)`, id, only)
	if err != nil {
		return 0, err
	}
	type grant struct{ user, track int64 }
	var grants []grant
	for rows.Next() {
		var g grant
		if err := rows.Scan(&g.user, &g.track); err != nil {
			rows.Close()
			return 0, err
		}
		grants = append(grants, g)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	now, added := db.Now(), 0
	for _, g := range grants {
		if _, err := tx.ExecContext(ctx, "INSERT OR IGNORE INTO pointers(user_id, track_id, added_at) VALUES(?, ?, ?)",
			g.user, g.track, now); err != nil {
			return 0, err
		}
		if err := db.LogChange(ctx, tx, g.user, "pointer", strconv.FormatInt(g.track, 10), "add"); err != nil {
			return 0, err
		}
		if g.user == only {
			added++
		}
	}
	return added, nil
}
