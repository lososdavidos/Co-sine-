// Package stats reads listening history (§6.13): top artists, albums and
// tracks by window, listening over time, first-played dates and the year by
// year view, plus the views Home tiles and smart playlists are built from
// (§6.4.1, §2.3). Every play is already a timestamped event (Q37), so all
// of it is read from data that exists; nothing here writes.
//
// It describes what someone did and never suggests what to do next (NG3).
package stats

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

type Service struct {
	DB *db.DB
	// Now is the clock; nil means time.Now. Days and months are counted
	// in the server's time zone.
	Now func() time.Time
}

// Clock is the time windows are measured from.
func (s *Service) Clock() time.Time { return s.now() }

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

// Window is a span of listening.
type Window struct {
	Key    string    `json:"key"`    // "30d", "year", "2025", "all"
	Label  string    `json:"label"`  // "Last 30 days"
	From   time.Time `json:"from"`   // zero: from the first play
	To     time.Time `json:"to"`     // exclusive
	Bucket string    `json:"bucket"` // how OverTime is divided: day, month, year
}

var ErrWindow = errors.New(`unknown window: use "30d", "year", a year such as "2025", or "all"`)

// ParseWindow reads a window key. "" is all time.
func ParseWindow(key string, now time.Time) (Window, error) {
	loc := now.Location()
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
	switch key {
	case "30d":
		return Window{Key: key, Label: "Last 30 days", From: today.AddDate(0, 0, -29), To: now, Bucket: "day"}, nil
	case "year":
		return Window{Key: key, Label: "This year", From: time.Date(now.Year(), 1, 1, 0, 0, 0, 0, loc), To: now, Bucket: "month"}, nil
	case "", "all":
		return Window{Key: "all", Label: "All time", To: now, Bucket: "year"}, nil
	}
	y, err := strconv.Atoi(key)
	if err != nil || y < 1970 || y > now.Year() {
		return Window{}, ErrWindow
	}
	from := time.Date(y, 1, 1, 0, 0, 0, 0, loc)
	return Window{Key: key, Label: key, From: from, To: from.AddDate(1, 0, 0), Bucket: "month"}, nil
}

// Scope is whose listening: one account, or 0 for everyone (the dashboard).
type Scope struct{ User int64 }

const (
	// duration is a Track's length: its longest file, 0 once forgotten.
	duration = "COALESCE((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = t.id), 0)"
	inScope  = "(?1 = 0 OR p.user_id = ?1)"
	inWindow = "p.played_at >= ?2 AND p.played_at < ?3"
)

func (w Window) bounds() (from, to int64) {
	if !w.From.IsZero() {
		from = w.From.UnixMilli()
	}
	return from, w.To.UnixMilli()
}

// Summary totals a window.
type Summary struct {
	Plays   int   `json:"plays"`
	Seconds int64 `json:"seconds"` // listening time, from the Tracks' lengths
	Tracks  int   `json:"tracks"`  // distinct
	Artists int   `json:"artists"` // distinct
}

// Entry is one artist, album or track in a ranking.
type Entry struct {
	ID          string    `json:"id"` // Subsonic id: ar-, al- or tr-
	Name        string    `json:"name"`
	Artist      string    `json:"artist,omitempty"` // for albums and tracks
	Plays       int       `json:"plays"`
	Seconds     int64     `json:"seconds"`
	FirstPlayed time.Time `json:"firstPlayed"` // ever, not just in the window
}

// Bucket is one day, month or year of listening.
type Bucket struct {
	Label   string    `json:"label"`
	Start   time.Time `json:"start"`
	Plays   int       `json:"plays"`
	Seconds int64     `json:"seconds"`
}

type Report struct {
	Window   Window   `json:"window"`
	Summary  Summary  `json:"summary"`
	Artists  []Entry  `json:"artists"`
	Albums   []Entry  `json:"albums"`
	Tracks   []Entry  `json:"tracks"`
	OverTime []Bucket `json:"overTime"`
}

// Report is a window's listening: totals, the top n of each kind, and
// listening over time. Artists are the Tracks' own credits, so a
// compilation counts for the artists on it, not for Various Artists.
func (s *Service) Report(ctx context.Context, sc Scope, w Window, n int) (Report, error) {
	from, to := w.bounds()
	r := Report{Window: w, Artists: []Entry{}, Albums: []Entry{}, Tracks: []Entry{}}
	err := s.DB.QueryRowContext(ctx, `
		SELECT COUNT(*), COALESCE(SUM(`+duration+`), 0), COUNT(DISTINCT t.id), COUNT(DISTINCT t.artist_id)
		FROM plays p JOIN tracks t ON t.id = p.track_id
		WHERE `+inScope+` AND `+inWindow, sc.User, from, to,
	).Scan(&r.Summary.Plays, &r.Summary.Seconds, &r.Summary.Tracks, &r.Summary.Artists)
	if err != nil {
		return r, err
	}
	rank := func(prefix, group, name, artist, first string) ([]Entry, error) {
		return s.rank(ctx, prefix, group, name, artist, first, sc, from, to, n)
	}
	if r.Artists, err = rank("ar-", "t.artist_id", "ta.name", "''",
		"SELECT MIN(p2.played_at) FROM plays p2 JOIN tracks t2 ON t2.id = p2.track_id WHERE t2.artist_id = t.artist_id"); err != nil {
		return r, err
	}
	if r.Albums, err = rank("al-", "t.release_id", "r.title", "ra.name",
		"SELECT MIN(p2.played_at) FROM plays p2 JOIN tracks t2 ON t2.id = p2.track_id WHERE t2.release_id = t.release_id"); err != nil {
		return r, err
	}
	if r.Tracks, err = rank("tr-", "t.id", "t.title", "ta.name",
		"SELECT MIN(p2.played_at) FROM plays p2 WHERE p2.track_id = t.id"); err != nil {
		return r, err
	}
	r.OverTime, err = s.overTime(ctx, sc, w)
	return r, err
}

func (s *Service) rank(ctx context.Context, prefix, group, name, artist, first string, sc Scope, from, to int64, n int) ([]Entry, error) {
	q := fmt.Sprintf(`
		SELECT %[1]s, %[2]s, %[3]s, COUNT(*), COALESCE(SUM(%[4]s), 0),
		       (%[5]s AND (?1 = 0 OR p2.user_id = ?1))
		FROM plays p
		JOIN tracks t ON t.id = p.track_id
		JOIN artists ta ON ta.id = t.artist_id
		JOIN releases r ON r.id = t.release_id
		JOIN artists ra ON ra.id = r.artist_id
		WHERE %[6]s AND %[7]s
		GROUP BY %[1]s
		ORDER BY COUNT(*) DESC, SUM(%[4]s) DESC, %[2]s
		LIMIT ?4`, group, name, artist, duration, first, inScope, inWindow)
	rows, err := s.DB.QueryContext(ctx, q, sc.User, from, to, n)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Entry{}
	for rows.Next() {
		var e Entry
		var id, firstAt int64
		if err := rows.Scan(&id, &e.Name, &e.Artist, &e.Plays, &e.Seconds, &firstAt); err != nil {
			return nil, err
		}
		e.ID, e.FirstPlayed = prefix+strconv.FormatInt(id, 10), time.UnixMilli(firstAt)
		out = append(out, e)
	}
	return out, rows.Err()
}

// overTime divides a window into its buckets, empty ones included, so a
// quiet month shows as one.
func (s *Service) overTime(ctx context.Context, sc Scope, w Window) ([]Bucket, error) {
	from, to := w.bounds()
	rows, err := s.DB.QueryContext(ctx, `
		SELECT p.played_at, `+duration+`
		FROM plays p JOIN tracks t ON t.id = p.track_id
		WHERE `+inScope+` AND `+inWindow+` ORDER BY p.played_at`, sc.User, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type play struct {
		at  time.Time
		sec int64
	}
	var plays []play
	for rows.Next() {
		var at, sec int64
		if err := rows.Scan(&at, &sec); err != nil {
			return nil, err
		}
		plays = append(plays, play{time.UnixMilli(at).In(w.To.Location()), sec})
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	start := w.From
	if start.IsZero() {
		if len(plays) == 0 {
			return []Bucket{}, nil
		}
		start = plays[0].at
	}
	loc := w.To.Location()
	floor := func(t time.Time) time.Time {
		switch w.Bucket {
		case "day":
			return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, loc)
		case "month":
			return time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, loc)
		}
		return time.Date(t.Year(), 1, 1, 0, 0, 0, 0, loc)
	}
	next := func(t time.Time) time.Time {
		switch w.Bucket {
		case "day":
			return t.AddDate(0, 0, 1)
		case "month":
			return t.AddDate(0, 1, 0)
		}
		return t.AddDate(1, 0, 0)
	}
	label := map[string]string{"day": "Jan 2", "month": "Jan 2006", "year": "2006"}[w.Bucket]
	out := []Bucket{}
	i := 0
	for b := floor(start); b.Before(w.To); b = next(b) {
		bucket := Bucket{Label: b.Format(label), Start: b}
		for end := next(b); i < len(plays) && plays[i].at.Before(end); i++ {
			bucket.Plays++
			bucket.Seconds += plays[i].sec
		}
		out = append(out, bucket)
	}
	return out, nil
}

// Year is one calendar year at a glance: "most played by year" (§6.13).
type Year struct {
	Year      int     `json:"year"`
	Summary   Summary `json:"summary"`
	TopArtist *Entry  `json:"topArtist,omitempty"`
	TopTrack  *Entry  `json:"topTrack,omitempty"`
}

// Years lists every year with plays, newest first.
func (s *Service) Years(ctx context.Context, sc Scope) ([]Year, error) {
	var first, last sql.NullInt64
	if err := s.DB.QueryRowContext(ctx, "SELECT MIN(p.played_at), MAX(p.played_at) FROM plays p WHERE "+inScope, sc.User).
		Scan(&first, &last); err != nil {
		return nil, err
	}
	out := []Year{}
	if !first.Valid {
		return out, nil
	}
	now := s.now()
	for y := time.UnixMilli(last.Int64).In(now.Location()).Year(); y >= time.UnixMilli(first.Int64).In(now.Location()).Year(); y-- {
		w, err := ParseWindow(strconv.Itoa(y), now)
		if err != nil {
			continue
		}
		r, err := s.Report(ctx, sc, w, 1)
		if err != nil {
			return nil, err
		}
		year := Year{Year: y, Summary: r.Summary}
		if len(r.Artists) > 0 {
			year.TopArtist = &r.Artists[0]
		}
		if len(r.Tracks) > 0 {
			year.TopTrack = &r.Tracks[0]
		}
		out = append(out, year)
	}
	return out, nil
}

// Windows lists the windows worth offering: the last 30 days, this year,
// each earlier year with plays, and all time.
func (s *Service) Windows(ctx context.Context, sc Scope) ([]Window, error) {
	now := s.now()
	ws := []Window{}
	for _, k := range []string{"30d", "year"} {
		w, _ := ParseWindow(k, now)
		ws = append(ws, w)
	}
	var first sql.NullInt64
	if err := s.DB.QueryRowContext(ctx, "SELECT MIN(p.played_at) FROM plays p WHERE "+inScope, sc.User).Scan(&first); err != nil {
		return nil, err
	}
	if first.Valid {
		for y := now.Year() - 1; y >= time.UnixMilli(first.Int64).In(now.Location()).Year(); y-- {
			w, _ := ParseWindow(strconv.Itoa(y), now)
			ws = append(ws, w)
		}
	}
	all, _ := ParseWindow("all", now)
	return append(ws, all), nil
}
