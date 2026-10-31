package playlists

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
)

// Smart is a smart playlist (§2.4): a rule, one of the stats views, kept
// with a name. What it holds is worked out each time it is opened, from
// the owner's own Library and plays, so it is never shared.
type Smart struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	Rule   string `json:"rule"`
	Span   string `json:"span,omitempty"`   // most-played: 30d, year, all, or a year
	Months int    `json:"months,omitempty"` // forgotten: not played in this many months
	Days   int    `json:"days,omitempty"`   // added: in the last this many days
	Size   int    `json:"size"`             // at most this many songs
}

const (
	defaultSize = 100
	maxSize     = 500
)

var ErrRule = errors.New("unknown rule")

// defaults are what every account starts with: the Home tiles of §6.4.1
// that are rules over one's own listening. They came in generations; an
// account is given each one once (users.smart_seeded counts them), so one
// made before a generation gets it later, and a default it deleted
// doesn't come back.
var defaults = [][]Smart{
	{
		{Name: "Recently added", Rule: stats.ViewAdded, Days: 30},
		{Name: "Most played this year", Rule: stats.ViewMostPlayed, Span: "year"},
		{Name: "Recently played", Rule: stats.ViewRecent},
		{Name: "Forgotten", Rule: stats.ViewForgotten, Months: 6},
		{Name: "Never played", Rule: stats.ViewNeverPlayed},
		{Name: "This time last year", Rule: stats.ViewLastYear},
	},
	{
		// Current favourites: what you keep coming back to lately. (Liked
		// songs was here once; it's a playlist of its own now, §2.4.)
		{Name: "Favourites", Rule: stats.ViewFavourites, Size: 50},
	},
}

// Describe says what a rule picks, for people.
func (s Smart) Describe() string {
	switch s.Rule {
	case stats.ViewAdded:
		return fmt.Sprintf("Added in the last %d days", s.Days)
	case stats.ViewMostPlayed:
		switch s.Span {
		case "30d":
			return "Most played in the last 30 days"
		case "year":
			return "Most played this year"
		case "", "all":
			return "Most played of all time"
		}
		return "Most played in " + s.Span
	case stats.ViewRecent:
		return "Most recently played"
	case stats.ViewForgotten:
		if s.Months == 1 {
			return "Played before, not in the last month"
		}
		return fmt.Sprintf("Played before, not in the last %d months", s.Months)
	case stats.ViewNeverPlayed:
		return "Never played, newest first"
	case stats.ViewLastYear:
		return "Played around this day a year ago"
	case stats.ViewFavourites:
		return "What you've played most lately"
	}
	return s.Rule
}

// normalise checks a rule and fills in its defaults.
func (s *Service) normalise(sm Smart) (Smart, error) {
	name, err := cleanName(sm.Name)
	if err != nil {
		return sm, err
	}
	sm.Name = name
	if !slices.Contains(stats.Views, sm.Rule) {
		return sm, ErrRule
	}
	if sm.Size <= 0 {
		sm.Size = defaultSize
	}
	sm.Size = min(sm.Size, maxSize)
	// Only the rule's own setting is kept.
	span, months, days := sm.Span, sm.Months, sm.Days
	sm.Span, sm.Months, sm.Days = "", 0, 0
	switch sm.Rule {
	case stats.ViewMostPlayed:
		if span == "" {
			span = "all"
		}
		if _, err := stats.ParseWindow(span, s.Stats.Clock()); err != nil {
			return sm, err
		}
		sm.Span = span
	case stats.ViewForgotten:
		if months <= 0 {
			months = 6
		}
		sm.Months = min(months, 240)
	case stats.ViewAdded:
		if days <= 0 {
			days = 30
		}
		sm.Days = min(days, 3650)
	}
	return sm, nil
}

// SmartLists lists an account's smart playlists, giving it the default
// ones the first time.
func (s *Service) SmartLists(ctx context.Context, user int64) ([]Smart, error) {
	if s.Stats == nil {
		return nil, nil
	}
	if err := s.seed(ctx, user); err != nil {
		return nil, err
	}
	return s.smart(ctx, "WHERE owner_id = ? ORDER BY id", user)
}

// SmartList returns one of an account's smart playlists.
func (s *Service) SmartList(ctx context.Context, user, id int64) (Smart, error) {
	if s.Stats == nil {
		return Smart{}, ErrNotFound
	}
	found, err := s.smart(ctx, "WHERE owner_id = ? AND id = ?", user, id)
	if err != nil {
		return Smart{}, err
	}
	if len(found) == 0 {
		return Smart{}, ErrNotFound
	}
	return found[0], nil
}

func (s *Service) smart(ctx context.Context, where string, args ...any) ([]Smart, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT id, name, rule, COALESCE(span, ''), COALESCE(months, 0), COALESCE(days, 0), size
		FROM smart_playlists `+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Smart{}
	for rows.Next() {
		var sm Smart
		if err := rows.Scan(&sm.ID, &sm.Name, &sm.Rule, &sm.Span, &sm.Months, &sm.Days, &sm.Size); err != nil {
			return nil, err
		}
		out = append(out, sm)
	}
	return out, rows.Err()
}

func (s *Service) seed(ctx context.Context, user int64) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var given int
	if err := tx.QueryRowContext(ctx, "SELECT smart_seeded FROM users WHERE id = ?", user).Scan(&given); err != nil {
		return err
	}
	if given >= len(defaults) {
		return nil
	}
	res, err := tx.ExecContext(ctx, "UPDATE users SET smart_seeded = ? WHERE id = ? AND smart_seeded = ?", len(defaults), user, given)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil // given them at the same moment elsewhere
	}
	for _, generation := range defaults[given:] {
		for _, d := range generation {
			sm, err := s.normalise(d)
			if err != nil {
				return err
			}
			if _, err := insertSmart(ctx, tx, user, sm); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

func insertSmart(ctx context.Context, x db.Execer, user int64, sm Smart) (int64, error) {
	now := db.Now()
	res, err := x.ExecContext(ctx, `
		INSERT INTO smart_playlists(owner_id, name, rule, span, months, days, size, created_at, updated_at)
		VALUES(?, ?, ?, NULLIF(?, ''), NULLIF(?, 0), NULLIF(?, 0), ?, ?, ?)`,
		user, sm.Name, sm.Rule, sm.Span, sm.Months, sm.Days, sm.Size, now, now)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// CreateSmart saves a new smart playlist.
func (s *Service) CreateSmart(ctx context.Context, user int64, sm Smart) (Smart, error) {
	if s.Stats == nil {
		return Smart{}, ErrRule
	}
	sm, err := s.normalise(sm)
	if err != nil {
		return Smart{}, err
	}
	// Making one yourself first still gives you the defaults alongside.
	if err := s.seed(ctx, user); err != nil {
		return Smart{}, err
	}
	sm.ID, err = insertSmart(ctx, s.DB, user, sm)
	return sm, err
}

// UpdateSmart changes a smart playlist's name and rule.
func (s *Service) UpdateSmart(ctx context.Context, user int64, sm Smart) (Smart, error) {
	if s.Stats == nil {
		return Smart{}, ErrNotFound
	}
	sm, err := s.normalise(sm)
	if err != nil {
		return Smart{}, err
	}
	res, err := s.DB.ExecContext(ctx, `
		UPDATE smart_playlists SET name = ?, rule = ?, span = NULLIF(?, ''), months = NULLIF(?, 0),
		       days = NULLIF(?, 0), size = ?, updated_at = ?
		WHERE id = ? AND owner_id = ?`,
		sm.Name, sm.Rule, sm.Span, sm.Months, sm.Days, sm.Size, db.Now(), sm.ID, user)
	if err != nil {
		return Smart{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return Smart{}, ErrNotFound
	}
	return sm, nil
}

// RenameSmart is the one change other Subsonic clients can make.
func (s *Service) RenameSmart(ctx context.Context, user, id int64, name string) error {
	sm, err := s.SmartList(ctx, user, id)
	if err != nil {
		return err
	}
	sm.Name = name
	_, err = s.UpdateSmart(ctx, user, sm)
	return err
}

// DeleteSmart removes a smart playlist; a default one doesn't come back.
func (s *Service) DeleteSmart(ctx context.Context, user, id int64) error {
	res, err := s.DB.ExecContext(ctx, "DELETE FROM smart_playlists WHERE id = ? AND owner_id = ?", id, user)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// Tracks evaluates a smart playlist: its songs, in the rule's order.
func (s *Service) Tracks(ctx context.Context, user int64, sm Smart) ([]int64, error) {
	if s.Stats == nil {
		return nil, ErrNotFound
	}
	opt := stats.ViewOptions{Limit: sm.Size, Months: sm.Months, Days: sm.Days}
	if sm.Rule == stats.ViewMostPlayed && sm.Span != "all" {
		w, err := stats.ParseWindow(sm.Span, s.Stats.Clock())
		if err != nil {
			return nil, err
		}
		opt.Window = w
	}
	found, err := s.Stats.View(ctx, user, sm.Rule, opt)
	if err != nil {
		return nil, err
	}
	out := make([]int64, 0, len(found))
	for _, t := range found {
		if id, err := strconv.ParseInt(t.ID[len("tr-"):], 10, 64); err == nil {
			out = append(out, id)
		}
	}
	return out, nil
}
