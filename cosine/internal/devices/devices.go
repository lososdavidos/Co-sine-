// Package devices keeps each account's Devices and their playback sessions
// (§4A, §4B). A Device is a running client: an install of Sine, a Subsonic
// app. Each keeps its own queue and position here, so playback can be picked
// up on another, and so two playing at once never share one queue.
package devices

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Kinds of Device.
var Kinds = []string{"phone", "desktop", "web", "subsonic"}

// MaxQueue is the most songs a session keeps; a longer queue keeps its first.
const MaxQueue = 5000

var (
	ErrNotFound = errors.New("no such device")
	ErrDevice   = errors.New("a device needs an id of up to 100 characters, a name, and a kind: phone, desktop, web or subsonic")
)

type Device struct {
	ID       string    `json:"id"` // the client's own id for itself
	Name     string    `json:"name"`
	Kind     string    `json:"kind"`
	LastSeen time.Time `json:"lastSeen"`
}

// Session is what a Device is playing: its queue as track ids in the order
// they play, where it is in it, and how far into that song.
type Session struct {
	Queue      []string  `json:"queue"`
	Current    int       `json:"current"`
	PositionMs int64     `json:"positionMs"`
	Playing    bool      `json:"playing"`
	UpdatedAt  time.Time `json:"updatedAt,omitzero"`
}

// Now is a Device and what it last played: for picking up where it left off.
type Now struct {
	Device     Device    `json:"device"`
	Track      string    `json:"track,omitempty"` // the song it's at
	Title      string    `json:"title,omitempty"`
	Artist     string    `json:"artist,omitempty"`
	CoverArt   string    `json:"coverArt,omitempty"`
	PositionMs int64     `json:"positionMs"`
	Playing    bool      `json:"playing"`
	Songs      int       `json:"songs"` // in its queue
	UpdatedAt  time.Time `json:"updatedAt,omitzero"`
	// Listening: it's there now, and can be controlled (§4B).
	Listening bool `json:"listening"`
}

type Service struct {
	DB *db.DB
	// Now is the clock; nil is time.Now.
	Now func() time.Time
	// Hub carries remote control's commands; made when first needed.
	Hub  *Hub
	once sync.Once
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func valid(d Device) bool {
	if d.ID == "" || len(d.ID) > 100 || strings.TrimSpace(d.Name) == "" || len(d.Name) > 100 {
		return false
	}
	for _, k := range Kinds {
		if k == d.Kind {
			return true
		}
	}
	return false
}

// Seen registers a Device, or notes that it's still about and what it's
// called now; it returns its row id.
func (s *Service) Seen(ctx context.Context, user int64, d Device) (int64, error) {
	if !valid(d) {
		return 0, ErrDevice
	}
	now := s.now().UnixMilli()
	var id int64
	err := s.DB.QueryRowContext(ctx, `
		INSERT INTO devices(user_id, client_id, name, kind, last_seen) VALUES(?, ?, ?, ?, ?)
		ON CONFLICT(user_id, client_id) DO UPDATE SET name = excluded.name, kind = excluded.kind, last_seen = excluded.last_seen
		RETURNING id`, user, d.ID, strings.TrimSpace(d.Name), d.Kind, now).Scan(&id)
	return id, err
}

// Save keeps a Device's session, registering the Device if it's new.
func (s *Service) Save(ctx context.Context, user int64, d Device, sess Session) error {
	id, err := s.Seen(ctx, user, d)
	if err != nil {
		return err
	}
	var ids []int64
	for _, t := range sess.Queue {
		if n, ok := trackID(t); ok {
			ids = append(ids, n)
		}
		if len(ids) == MaxQueue {
			break
		}
	}
	if ids == nil {
		ids = []int64{}
	}
	cur := sess.Current
	if cur < 0 || cur >= len(ids) {
		cur = 0
	}
	queue, _ := json.Marshal(ids)
	_, err = s.DB.ExecContext(ctx, `
		INSERT INTO play_sessions(device_id, queue, current, position_ms, playing, updated_at) VALUES(?, ?, ?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET queue = excluded.queue, current = excluded.current,
			position_ms = excluded.position_ms, playing = excluded.playing, updated_at = excluded.updated_at`,
		id, string(queue), cur, max(sess.PositionMs, 0), sess.Playing, s.now().UnixMilli())
	return err
}

// Session is one Device's session; ErrNotFound when it has none.
func (s *Service) Session(ctx context.Context, user int64, clientID string) (Session, error) {
	return s.session(ctx, "d.user_id = ? AND d.client_id = ?", user, clientID)
}

// Latest is the account's most recently changed session, on whichever
// Device: what a Subsonic app's getPlayQueue picks up.
func (s *Service) Latest(ctx context.Context, user int64) (Session, Device, error) {
	sess, err := s.session(ctx, "d.user_id = ? ORDER BY p.updated_at DESC LIMIT 1", user)
	if err != nil {
		return Session{}, Device{}, err
	}
	var d Device
	var seen int64
	err = s.DB.QueryRowContext(ctx, `
		SELECT d.client_id, d.name, d.kind, d.last_seen FROM devices d JOIN play_sessions p ON p.device_id = d.id
		WHERE d.user_id = ? ORDER BY p.updated_at DESC LIMIT 1`, user).Scan(&d.ID, &d.Name, &d.Kind, &seen)
	d.LastSeen = time.UnixMilli(seen)
	return sess, d, err
}

func (s *Service) session(ctx context.Context, where string, args ...any) (Session, error) {
	var queue string
	var sess Session
	var updated int64
	err := s.DB.QueryRowContext(ctx, `
		SELECT p.queue, p.current, p.position_ms, p.playing, p.updated_at
		FROM play_sessions p JOIN devices d ON d.id = p.device_id WHERE `+where, args...).
		Scan(&queue, &sess.Current, &sess.PositionMs, &sess.Playing, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrNotFound
	}
	if err != nil {
		return Session{}, err
	}
	var ids []int64
	if err := json.Unmarshal([]byte(queue), &ids); err != nil {
		return Session{}, err
	}
	sess.Queue = make([]string, len(ids))
	for i, id := range ids {
		sess.Queue[i] = "tr-" + strconv.FormatInt(id, 10)
	}
	sess.UpdatedAt = time.UnixMilli(updated)
	return sess, nil
}

// Playing lists the account's Devices that have played something or are
// there to be controlled now, the most recently changed first, each with
// the song it's at.
func (s *Service) Playing(ctx context.Context, user int64) ([]Now, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT d.id, d.client_id, d.name, d.kind, d.last_seen,
		       COALESCE(p.queue, '[]'), COALESCE(p.current, 0), COALESCE(p.position_ms, 0), COALESCE(p.playing, 0),
		       COALESCE(p.updated_at, 0), p.device_id IS NOT NULL
		FROM devices d LEFT JOIN play_sessions p ON p.device_id = d.id
		WHERE d.user_id = ? ORDER BY COALESCE(p.updated_at, d.last_seen) DESC`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Now{}
	for rows.Next() {
		var n Now
		var row, seen, updated int64
		var queue string
		var cur int
		var played bool
		if err := rows.Scan(&row, &n.Device.ID, &n.Device.Name, &n.Device.Kind, &seen, &queue, &cur, &n.PositionMs, &n.Playing, &updated, &played); err != nil {
			return nil, err
		}
		n.Listening = s.hub().listening(row)
		if !played && !n.Listening {
			continue
		}
		n.Device.LastSeen = time.UnixMilli(seen)
		if played {
			n.UpdatedAt = time.UnixMilli(updated)
		}
		var ids []int64
		json.Unmarshal([]byte(queue), &ids)
		n.Songs = len(ids)
		if cur >= 0 && cur < len(ids) {
			n.Track = "tr-" + strconv.FormatInt(ids[cur], 10)
		}
		out = append(out, n)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// The song each is at, as the library names it.
	for i := range out {
		if out[i].Track == "" {
			continue
		}
		id, _ := trackID(out[i].Track)
		var art sql.NullBool
		var rid int64
		s.DB.QueryRowContext(ctx, `
			SELECT t.title, a.name, r.id, r.art_path IS NOT NULL FROM tracks t
			JOIN artists a ON a.id = t.artist_id JOIN releases r ON r.id = t.release_id WHERE t.id = ?`, id).
			Scan(&out[i].Title, &out[i].Artist, &rid, &art)
		if art.Bool {
			out[i].CoverArt = "al-" + strconv.FormatInt(rid, 10)
		}
	}
	return out, nil
}

// Forget removes a Device and its session.
func (s *Service) Forget(ctx context.Context, user int64, clientID string) error {
	res, err := s.DB.ExecContext(ctx, "DELETE FROM devices WHERE user_id = ? AND client_id = ?", user, clientID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func trackID(s string) (int64, bool) {
	n, err := strconv.ParseInt(strings.TrimPrefix(s, "tr-"), 10, 64)
	return n, err == nil && strings.HasPrefix(s, "tr-") && n > 0
}
