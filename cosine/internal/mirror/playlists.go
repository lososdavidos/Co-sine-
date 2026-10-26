package mirror

import (
	"context"
	"database/sql"
	"encoding/json"
	"hash/fnv"
	"strconv"
)

// Playlist is a manual playlist (§2.4) as the phone keeps it: every entry
// in order, shown or not. The phone shows those whose track it has, as
// Cosine does, so a song removed and added back reappears in its place;
// edits made offline count entries the same way (§5.2).
type Playlist struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Owner   string   `json:"owner"`
	Mine    bool     `json:"mine"`
	Shared  []string `json:"sharedWith"` // the accounts it's shared with that accepted
	Invited []string `json:"invited"`    // asked, not yet accepted; only its owner is told
	Tracks  []string `json:"tracks"`
	Created string   `json:"created,omitempty"`
	Changed string   `json:"changed,omitempty"`
}

// Invite is a playlist someone shared with this account, waiting for an
// answer (§2.3).
type Invite struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Owner string `json:"owner"`
	Songs int    `json:"songs"`
}

// Smart is a smart playlist as last worked out: its rule and its songs.
type Smart struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Rule        string   `json:"rule"`
	Span        string   `json:"span,omitempty"`
	Months      int      `json:"months,omitempty"`
	Days        int      `json:"days,omitempty"`
	Size        int      `json:"size"`
	Description string   `json:"description"`
	Tracks      []string `json:"tracks"`
}

func playlistID(id int64) string { return "pl-" + strconv.FormatInt(id, 10) }

// allPlaylists puts every playlist the account has, and every invitation,
// in a full answer.
func (s *Service) allPlaylists(ctx context.Context, out *Changes, user int64) error {
	rows, err := s.DB.QueryContext(ctx, `SELECT id FROM playlists WHERE owner_id = ?1
		UNION SELECT playlist_id FROM playlist_members WHERE user_id = ?1 ORDER BY 1`, user)
	if err != nil {
		return err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, id := range ids {
		if err := s.playlist(ctx, out, user, id); err != nil {
			return err
		}
	}
	return nil
}

// playlist says what one playlist is to the account now: one it has, an
// invitation, or gone from its phone (deleted, left, or no longer shared).
func (s *Service) playlist(ctx context.Context, out *Changes, user, id int64) error {
	var (
		p                Playlist
		ownerID          int64
		created, changed int64
		member           sql.NullBool // null: not on it; false: invited; true: accepted
	)
	err := s.DB.QueryRowContext(ctx, `
		SELECT pl.name, pl.owner_id, u.name, pl.created_at, pl.updated_at,
		       (SELECT m.accepted_at IS NOT NULL FROM playlist_members m WHERE m.playlist_id = pl.id AND m.user_id = ?2)
		FROM playlists pl JOIN users u ON u.id = pl.owner_id WHERE pl.id = ?1`, id, user).
		Scan(&p.Name, &ownerID, &p.Owner, &created, &changed, &member)
	if err == sql.ErrNoRows || (err == nil && ownerID != user && !member.Valid) {
		out.RemovedPlaylists = append(out.RemovedPlaylists, playlistID(id))
		return nil
	}
	if err != nil {
		return err
	}
	tracks, err := s.entries(ctx, id)
	if err != nil {
		return err
	}
	if ownerID != user && !member.Bool {
		out.Invites = append(out.Invites, Invite{ID: playlistID(id), Name: p.Name, Owner: p.Owner, Songs: len(tracks)})
		return nil
	}
	p.ID, p.Mine, p.Tracks = playlistID(id), ownerID == user, tracks
	p.Created, p.Changed = iso(created), iso(changed)
	p.Shared, p.Invited = []string{}, []string{}
	if s.Playlists != nil {
		members, err := s.Playlists.Members(ctx, id)
		if err != nil {
			return err
		}
		for _, m := range members {
			switch {
			case m.Accepted:
				p.Shared = append(p.Shared, m.Name)
			case p.Mine:
				p.Invited = append(p.Invited, m.Name)
			}
		}
	}
	out.Playlists = append(out.Playlists, p)
	return nil
}

func (s *Service) entries(ctx context.Context, id int64) ([]string, error) {
	rows, err := s.DB.QueryContext(ctx, "SELECT track_id FROM playlist_entries WHERE playlist_id = ? ORDER BY position", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var t int64
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		out = append(out, trackID(t))
	}
	return out, rows.Err()
}

// smart works out the account's smart playlists. What they hold changes
// with listening and with time, which the change log doesn't record, so
// they are worked out on every sync and sent only when they differ from
// what the phone has (tag, from the last answer).
func (s *Service) smart(ctx context.Context, user int64, tag string) (*[]Smart, string, error) {
	if s.Playlists == nil || s.Playlists.Stats == nil {
		return nil, "", nil
	}
	lists, err := s.Playlists.SmartLists(ctx, user)
	if err != nil {
		return nil, "", err
	}
	out := make([]Smart, 0, len(lists))
	for _, sm := range lists {
		ids, err := s.Playlists.Tracks(ctx, user, sm)
		if err != nil {
			return nil, "", err
		}
		tracks := make([]string, len(ids))
		for i, t := range ids {
			tracks[i] = trackID(t)
		}
		out = append(out, Smart{
			ID: "sp-" + strconv.FormatInt(sm.ID, 10), Name: sm.Name, Rule: sm.Rule, Span: sm.Span,
			Months: sm.Months, Days: sm.Days, Size: sm.Size, Description: sm.Describe(), Tracks: tracks,
		})
	}
	b, err := json.Marshal(out)
	if err != nil {
		return nil, "", err
	}
	h := fnv.New64a()
	h.Write(b)
	now := strconv.FormatUint(h.Sum64(), 36)
	if now == tag {
		return nil, now, nil
	}
	return &out, now, nil
}
