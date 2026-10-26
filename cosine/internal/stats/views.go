package stats

import (
	"context"
	"errors"
	"strconv"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// The views are what Home tiles and smart playlists are made of (§2.3,
// §6.4.1): rules over one account's own Library and its own plays. One
// implementation serves both, so the rule engine is written once.
const (
	ViewMostPlayed  = "most-played"         // by plays in a window
	ViewRecent      = "recent"              // most recently played first
	ViewForgotten   = "forgotten"           // played before, not in the last N months
	ViewNeverPlayed = "never-played"        // in the Library, never played, newest first
	ViewLastYear    = "this-time-last-year" // played in the fortnight around this day a year ago
	ViewAdded       = "added"               // added in the last N days, newest first
	ViewLiked       = "liked"               // starred, the latest first
)

// Views lists the view names, for clients to discover.
var Views = []string{ViewMostPlayed, ViewRecent, ViewForgotten, ViewNeverPlayed, ViewLastYear, ViewAdded, ViewLiked}

var ErrView = errors.New("unknown view")

// ViewOptions tunes a view. Zero values take the defaults.
type ViewOptions struct {
	Limit  int    // default 50, at most 500
	Window Window // most-played only; default all time
	Months int    // forgotten only; default 6
	Days   int    // added only; default 30
}

// Track is one Track in a view, with the account's own history of it.
type Track struct {
	ID         string    `json:"id"` // Subsonic id, playable as is
	Title      string    `json:"title"`
	Artist     string    `json:"artist"`
	Album      string    `json:"album"`
	Plays      int       `json:"plays"` // in the view's window
	LastPlayed time.Time `json:"lastPlayed,omitzero"`
	Added      time.Time `json:"added"`
}

// View evaluates a view for one account. Only Tracks in that account's
// Library with a file in the Store are offered (§2.2).
func (s *Service) View(ctx context.Context, user int64, name string, opt ViewOptions) ([]Track, error) {
	if opt.Limit <= 0 || opt.Limit > 500 {
		opt.Limit = 50
	}
	now := s.now()
	// to is exclusive: a play recorded this very millisecond counts.
	from, to := int64(0), now.UnixMilli()+1
	var where, order string
	switch name {
	case ViewMostPlayed:
		if !opt.Window.To.IsZero() {
			from, to = opt.Window.bounds()
		}
		where, order = "plays > 0", "plays DESC, last_played DESC"
	case ViewRecent:
		where, order = "plays > 0", "last_played DESC"
	case ViewForgotten:
		months := opt.Months
		if months <= 0 {
			months = 6
		}
		cutoff := now.AddDate(0, -months, 0).UnixMilli()
		where, order = "plays > 0 AND last_played < "+strconv.FormatInt(cutoff, 10), "plays DESC, last_played"
	case ViewNeverPlayed:
		where, order = "plays = 0", "added DESC"
	case ViewAdded:
		days := opt.Days
		if days <= 0 {
			days = 30
		}
		cutoff := now.AddDate(0, 0, -days).UnixMilli()
		where, order = "added >= "+strconv.FormatInt(cutoff, 10), "added DESC"
	case ViewLiked:
		where, order = "liked > 0", "liked DESC"
	case ViewLastYear:
		ago := now.AddDate(-1, 0, 0)
		from, to = ago.AddDate(0, 0, -7).UnixMilli(), ago.AddDate(0, 0, 7).UnixMilli()
		where, order = "plays > 0", "plays DESC, last_played DESC"
	default:
		return nil, ErrView
	}
	rows, err := s.DB.QueryContext(ctx, `
		SELECT * FROM (
			SELECT t.id, t.title, ta.name, r.title, p.added_at AS added, COALESCE(p.starred_at, 0) AS liked,
			       (SELECT COUNT(*) FROM plays pl WHERE pl.user_id = p.user_id AND pl.track_id = t.id
			          AND pl.played_at >= ?2 AND pl.played_at < ?3) AS plays,
			       COALESCE((SELECT MAX(pl.played_at) FROM plays pl WHERE pl.user_id = p.user_id AND pl.track_id = t.id), 0) AS last_played
			FROM pointers p
			JOIN tracks t ON t.id = p.track_id
			JOIN artists ta ON ta.id = t.artist_id
			JOIN releases r ON r.id = t.release_id
			WHERE p.user_id = ?1 AND `+db.Available+`
		) WHERE `+where+` ORDER BY `+order+` LIMIT ?4`, user, from, to, opt.Limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Track{}
	for rows.Next() {
		var t Track
		var id, added, liked, last int64
		if err := rows.Scan(&id, &t.Title, &t.Artist, &t.Album, &added, &liked, &t.Plays, &last); err != nil {
			return nil, err
		}
		t.ID, t.Added = "tr-"+strconv.FormatInt(id, 10), time.UnixMilli(added)
		if last > 0 {
			t.LastPlayed = time.UnixMilli(last)
		}
		out = append(out, t)
	}
	return out, rows.Err()
}
