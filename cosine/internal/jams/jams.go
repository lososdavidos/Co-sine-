// Package jams is Jam's party mode (§4C): accounts listening to the same
// thing at the same time, anywhere, kept within about a second. Anyone
// starts one and invites who they want; everyone in it adds to one queue;
// only the host plays, pauses, skips and seeks. A listener without a song
// hears it anyway, and nothing enters their Library unless they add it.
//
// Where the music is is kept as an anchor: the song, the position, the
// moment that was true, and whether it's playing. Every listener works out
// "now" from it and the songs' lengths, so a song ending moves everyone on
// without anyone saying so.
package jams

import (
	"context"
	"database/sql"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

var (
	ErrNotFound = errors.New("no such jam")
	ErrNotIn    = errors.New("you're not in this jam")
	ErrNotHost  = errors.New("only the host can do that")
	ErrEnded    = errors.New("this jam has ended")
	ErrNobody   = errors.New("no such account")
	ErrCommand  = errors.New("unknown command")
)

// MaxQueue is the most songs a jam holds.
const MaxQueue = 2000

type Member struct {
	Name    string `json:"name"`
	Host    bool   `json:"host"`
	Joined  bool   `json:"joined"` // accepted; else only invited
	Present bool   `json:"present"`
}

// Song is one in the queue, as the person looking sees it.
type Song struct {
	Track       string `json:"track"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	CoverArt    string `json:"coverArt,omitempty"`
	DurationSec int    `json:"durationSec"`
	AddedBy     string `json:"addedBy"`
	// InLibrary: the person looking has it; else they hear it from the jam.
	InLibrary bool `json:"inLibrary"`
}

// Jam is one jam as its people see it: who, what, and where the music is now.
type Jam struct {
	ID      int64    `json:"id"`
	Host    string   `json:"host"`
	Members []Member `json:"members"`
	Queue   []Song   `json:"queue"`
	// Where the music is at ServerTime: the song and how far into it.
	Current    int    `json:"current"`
	PositionMs int64  `json:"positionMs"`
	Playing    bool   `json:"playing"`
	ServerTime int64  `json:"serverTime"` // Unix ms: listeners set their clocks by it
	Version    int64  `json:"version"`
	Ended      bool   `json:"ended"`
	You        Member `json:"you"`
}

// Invitation is a jam someone asked you to.
type Invitation struct {
	ID      int64  `json:"id"`
	Host    string `json:"host"`
	Members int    `json:"members"`
}

type Service struct {
	DB *db.DB
	// Now is the clock; nil is time.Now.
	Now func() time.Time

	mu      sync.Mutex
	changed map[int64]chan struct{}
}

func (s *Service) now() int64 {
	if s.Now != nil {
		return s.Now().UnixMilli()
	}
	return time.Now().UnixMilli()
}

// notify wakes everyone waiting on the jam.
func (s *Service) notify(jam int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c := s.changed[jam]; c != nil {
		close(c)
		delete(s.changed, jam)
	}
}

func (s *Service) waiter(jam int64) chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.changed == nil {
		s.changed = map[int64]chan struct{}{}
	}
	c := s.changed[jam]
	if c == nil {
		c = make(chan struct{})
		s.changed[jam] = c
	}
	return c
}

func (s *Service) userIDs(ctx context.Context, names []string) ([]int64, error) {
	var out []int64
	for _, n := range names {
		var id int64
		err := s.DB.QueryRowContext(ctx, "SELECT id FROM users WHERE name = ?", strings.TrimSpace(n)).Scan(&id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNobody
		}
		if err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, nil
}

// Start begins a jam hosted by host, inviting names; a jam the host was
// already in is left, and one they hosted ends.
func (s *Service) Start(ctx context.Context, host int64, names []string) (int64, error) {
	invite, err := s.userIDs(ctx, names)
	if err != nil {
		return 0, err
	}
	if err := s.leaveAll(ctx, host); err != nil {
		return 0, err
	}
	now := s.now()
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, "INSERT INTO jams(host_id, started_at, anchor_at) VALUES(?, ?, ?)", host, now, now)
	if err != nil {
		return 0, err
	}
	id, _ := res.LastInsertId()
	if _, err := tx.ExecContext(ctx, "INSERT INTO jam_members(jam_id, user_id, invited_at, joined_at) VALUES(?, ?, ?, ?)", id, host, now, now); err != nil {
		return 0, err
	}
	for _, u := range invite {
		if u == host {
			continue
		}
		if _, err := tx.ExecContext(ctx, "INSERT OR IGNORE INTO jam_members(jam_id, user_id, invited_at) VALUES(?, ?, ?)", id, u, now); err != nil {
			return 0, err
		}
	}
	return id, tx.Commit()
}

// leaveAll takes a user out of every jam they're in, ending the ones they host.
func (s *Service) leaveAll(ctx context.Context, user int64) error {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT j.id FROM jams j JOIN jam_members m ON m.jam_id = j.id
		WHERE m.user_id = ? AND m.joined_at IS NOT NULL AND m.left_at IS NULL AND j.ended_at IS NULL`, user)
	if err != nil {
		return err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	for _, id := range ids {
		if err := s.Leave(ctx, id, user); err != nil {
			return err
		}
	}
	return nil
}

type row struct {
	host             int64
	ended            bool
	current          int
	position, anchor int64
	playing          bool
	version          int64
}

func (s *Service) load(ctx context.Context, jam int64) (row, error) {
	var r row
	var ended sql.NullInt64
	err := s.DB.QueryRowContext(ctx, `SELECT host_id, ended_at, current, position_ms, anchor_at, playing, version FROM jams WHERE id = ?`, jam).
		Scan(&r.host, &ended, &r.current, &r.position, &r.anchor, &r.playing, &r.version)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	r.ended = ended.Valid
	return r, err
}

// membership: whether user is in the jam (joined, not left), and invited at all.
func (s *Service) membership(ctx context.Context, jam, user int64) (in, invited bool, err error) {
	var joined, left sql.NullInt64
	err = s.DB.QueryRowContext(ctx, "SELECT joined_at, left_at FROM jam_members WHERE jam_id = ? AND user_id = ?", jam, user).Scan(&joined, &left)
	if errors.Is(err, sql.ErrNoRows) {
		return false, false, nil
	}
	return joined.Valid && !left.Valid, true, err
}

func (s *Service) durations(ctx context.Context, jam int64) ([]int64, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT COALESCE((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = q.track_id), 0)
		FROM jam_queue q WHERE q.jam_id = ? ORDER BY q.pos`, jam)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var d int64
		rows.Scan(&d)
		out = append(out, d*1000)
	}
	return out, rows.Err()
}

// at works out where the music is at time now from the anchor: past the
// end of a song, on to the next; past the last, stopped at its end.
func at(r row, lengths []int64, now int64) (current int, position int64, playing bool) {
	current, position, playing = r.current, r.position, r.playing
	if playing {
		position += now - r.anchor
	}
	for playing && current < len(lengths) && lengths[current] > 0 && position >= lengths[current] {
		if current == len(lengths)-1 {
			return current, lengths[current], false
		}
		position -= lengths[current]
		current++
	}
	if len(lengths) == 0 {
		return 0, 0, false
	}
	if current >= len(lengths) {
		current = len(lengths) - 1
	}
	return current, max(position, 0), playing
}

func (s *Service) save(ctx context.Context, jam int64, current int, position int64, playing bool) error {
	_, err := s.DB.ExecContext(ctx,
		"UPDATE jams SET current = ?, position_ms = ?, anchor_at = ?, playing = ?, version = version + 1 WHERE id = ?",
		current, position, s.now(), playing, jam)
	if err == nil {
		s.notify(jam)
	}
	return err
}

func (s *Service) bump(ctx context.Context, jam int64) error {
	_, err := s.DB.ExecContext(ctx, "UPDATE jams SET version = version + 1 WHERE id = ?", jam)
	if err == nil {
		s.notify(jam)
	}
	return err
}

// Join accepts an invitation; another jam the user was in is left.
func (s *Service) Join(ctx context.Context, jam, user int64) error {
	r, err := s.load(ctx, jam)
	if err != nil {
		return err
	}
	if r.ended {
		return ErrEnded
	}
	in, invited, err := s.membership(ctx, jam, user)
	if err != nil || !invited {
		return errors.Join(err, ErrNotIn)
	}
	if in {
		return nil // already in: leaving first would end it if they host it
	}
	if err := s.leaveAll(ctx, user); err != nil {
		return err
	}
	if _, err := s.DB.ExecContext(ctx, "UPDATE jam_members SET joined_at = ?, left_at = NULL WHERE jam_id = ? AND user_id = ?", s.now(), jam, user); err != nil {
		return err
	}
	return s.bump(ctx, jam)
}

// Leave takes the user out; the host leaving ends it for everyone.
func (s *Service) Leave(ctx context.Context, jam, user int64) error {
	r, err := s.load(ctx, jam)
	if err != nil {
		return err
	}
	if r.host == user {
		return s.End(ctx, jam, user)
	}
	if _, err := s.DB.ExecContext(ctx, "UPDATE jam_members SET left_at = ? WHERE jam_id = ? AND user_id = ?", s.now(), jam, user); err != nil {
		return err
	}
	return s.bump(ctx, jam)
}

// End stops the jam for everyone; the host's to do.
func (s *Service) End(ctx context.Context, jam, user int64) error {
	r, err := s.load(ctx, jam)
	if err != nil {
		return err
	}
	if r.host != user {
		return ErrNotHost
	}
	if _, err := s.DB.ExecContext(ctx, "UPDATE jams SET ended_at = ?, playing = 0, version = version + 1 WHERE id = ? AND ended_at IS NULL", s.now(), jam); err != nil {
		return err
	}
	s.notify(jam)
	return nil
}

// Invite asks more people; anyone in the jam may.
func (s *Service) Invite(ctx context.Context, jam, user int64, names []string) error {
	if err := s.ready(ctx, jam, user); err != nil {
		return err
	}
	ids, err := s.userIDs(ctx, names)
	if err != nil {
		return err
	}
	for _, u := range ids {
		if _, err := s.DB.ExecContext(ctx, `
			INSERT INTO jam_members(jam_id, user_id, invited_at) VALUES(?, ?, ?)
			ON CONFLICT(jam_id, user_id) DO UPDATE SET joined_at = NULL, left_at = NULL, invited_at = excluded.invited_at`, jam, u, s.now()); err != nil {
			return err
		}
	}
	return s.bump(ctx, jam)
}

// ready: the jam is on and the user is in it.
func (s *Service) ready(ctx context.Context, jam, user int64) error {
	r, err := s.load(ctx, jam)
	if err != nil {
		return err
	}
	if r.ended {
		return ErrEnded
	}
	in, _, err := s.membership(ctx, jam, user)
	if err != nil {
		return err
	}
	if !in {
		return ErrNotIn
	}
	return nil
}

// Add puts songs at the end of the queue; anyone in the jam may. A song is
// one the adder has, or one already in this jam: a jam is heard, not
// browsed (§4C), so it never reaches songs nobody in it brought.
func (s *Service) Add(ctx context.Context, jam, user int64, tracks []string) error {
	if err := s.ready(ctx, jam, user); err != nil {
		return err
	}
	var n int
	if err := s.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM jam_queue WHERE jam_id = ?", jam).Scan(&n); err != nil {
		return err
	}
	was, _ := s.durations(ctx, jam)
	now := s.now()
	for _, t := range tracks {
		id, ok := trackID(t)
		if !ok || n >= MaxQueue {
			continue
		}
		var exists bool
		s.DB.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM pointers WHERE user_id = ? AND track_id = ?)
			OR EXISTS(SELECT 1 FROM jam_queue WHERE jam_id = ? AND track_id = ?)`, user, id, jam, id).Scan(&exists)
		if !exists {
			continue
		}
		// The slot is taken in the insert itself: two people adding at once both get one.
		if _, err := s.DB.ExecContext(ctx, `INSERT INTO jam_queue(jam_id, pos, track_id, added_by, added_at)
			VALUES(?1, (SELECT COALESCE(MAX(pos) + 1, 0) FROM jam_queue WHERE jam_id = ?1), ?2, ?3, ?4)`, jam, id, user, now); err != nil {
			return err
		}
		n++
	}
	// Nothing was playing yet, or it had run out: the first new song plays.
	r, _ := s.load(ctx, jam)
	cur, _, playing := at(r, was, now)
	if len(was) == 0 {
		return s.save(ctx, jam, 0, 0, true)
	}
	if !playing && r.playing && cur == len(was)-1 {
		return s.save(ctx, jam, len(was), 0, true)
	}
	return s.bump(ctx, jam)
}

// Transport is the host's: play, pause, seek (to positionMs), next,
// previous, or skip (to index in the queue).
func (s *Service) Transport(ctx context.Context, jam, user int64, op string, positionMs int64, index int) error {
	r, err := s.load(ctx, jam)
	if err != nil {
		return err
	}
	if r.ended {
		return ErrEnded
	}
	if r.host != user {
		return ErrNotHost
	}
	lengths, err := s.durations(ctx, jam)
	if err != nil {
		return err
	}
	cur, pos, playing := at(r, lengths, s.now())
	last := len(lengths) - 1
	switch op {
	case "play":
		playing = len(lengths) > 0
	case "pause":
		playing = false
	case "toggle":
		playing = !playing && len(lengths) > 0
	case "seek":
		pos = max(positionMs, 0)
	case "next":
		if cur < last {
			cur, pos = cur+1, 0
		}
	case "previous":
		// Into a song, back to its start; at its start, the one before.
		if pos < 3000 && cur > 0 {
			cur--
		}
		pos = 0
	case "skip":
		if index >= 0 && index <= last {
			cur, pos, playing = index, 0, true
		}
	default:
		return ErrCommand
	}
	return s.save(ctx, jam, cur, pos, playing)
}

// State is the jam as user sees it; waits up to wait for a version newer
// than since, when since is given, so a listener hears a change at once.
func (s *Service) State(ctx context.Context, jam, user int64, since int64, wait time.Duration) (Jam, error) {
	if since > 0 {
		r, err := s.load(ctx, jam)
		if err != nil {
			return Jam{}, err
		}
		if r.version <= since && !r.ended {
			c := s.waiter(jam)
			t := time.NewTimer(wait)
			select {
			case <-c:
			case <-t.C:
			case <-ctx.Done():
			}
			t.Stop()
		}
	}
	return s.state(ctx, jam, user)
}

func (s *Service) state(ctx context.Context, jam, user int64) (Jam, error) {
	r, err := s.load(ctx, jam)
	if err != nil {
		return Jam{}, err
	}
	_, invited, err := s.membership(ctx, jam, user)
	if err != nil {
		return Jam{}, err
	}
	if !invited {
		return Jam{}, ErrNotIn
	}
	j := Jam{ID: jam, Version: r.version, Ended: r.ended, ServerTime: s.now()}
	rows, err := s.DB.QueryContext(ctx, `
		SELECT u.id, u.name, m.joined_at IS NOT NULL, m.left_at IS NULL FROM jam_members m JOIN users u ON u.id = m.user_id
		WHERE m.jam_id = ? ORDER BY m.joined_at IS NULL, m.joined_at, u.name`, jam)
	if err != nil {
		return Jam{}, err
	}
	for rows.Next() {
		var id int64
		var m Member
		var notLeft bool
		rows.Scan(&id, &m.Name, &m.Joined, &notLeft)
		m.Host = id == r.host
		m.Present = notLeft && m.Joined
		if m.Host {
			j.Host = m.Name
		}
		if id == user {
			j.You = m
		}
		if m.Present || (!m.Joined && notLeft) { // a declined invitation is gone
			j.Members = append(j.Members, m)
		}
	}
	rows.Close()
	q, err := s.DB.QueryContext(ctx, `
		SELECT q.track_id, t.title, a.name, r.title, r.id, r.art_path IS NOT NULL,
		       COALESCE((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = q.track_id), 0),
		       u.name, EXISTS(SELECT 1 FROM pointers p WHERE p.user_id = ? AND p.track_id = q.track_id)
		FROM jam_queue q JOIN tracks t ON t.id = q.track_id JOIN artists a ON a.id = t.artist_id
		JOIN releases r ON r.id = t.release_id JOIN users u ON u.id = q.added_by
		WHERE q.jam_id = ? ORDER BY q.pos`, user, jam)
	if err != nil {
		return Jam{}, err
	}
	var lengths []int64
	for q.Next() {
		var song Song
		var tid, rid int64
		var art bool
		if err := q.Scan(&tid, &song.Title, &song.Artist, &song.Album, &rid, &art, &song.DurationSec, &song.AddedBy, &song.InLibrary); err != nil {
			q.Close()
			return Jam{}, err
		}
		song.Track = "tr-" + strconv.FormatInt(tid, 10)
		if art {
			song.CoverArt = "al-" + strconv.FormatInt(rid, 10)
		}
		j.Queue = append(j.Queue, song)
		lengths = append(lengths, int64(song.DurationSec)*1000)
	}
	q.Close()
	if j.Queue == nil {
		j.Queue = []Song{}
	}
	j.Current, j.PositionMs, j.Playing = at(r, lengths, j.ServerTime)
	if r.ended {
		j.Playing = false
	}
	return j, nil
}

// Mine is the jam the user is in, if any, and the ones they're asked to.
func (s *Service) Mine(ctx context.Context, user int64) (*Jam, []Invitation, error) {
	var current *Jam
	var id int64
	err := s.DB.QueryRowContext(ctx, `
		SELECT j.id FROM jams j JOIN jam_members m ON m.jam_id = j.id
		WHERE m.user_id = ? AND m.joined_at IS NOT NULL AND m.left_at IS NULL AND j.ended_at IS NULL
		ORDER BY j.started_at DESC LIMIT 1`, user).Scan(&id)
	if err == nil {
		j, err := s.state(ctx, id, user)
		if err != nil {
			return nil, nil, err
		}
		current = &j
	} else if !errors.Is(err, sql.ErrNoRows) {
		return nil, nil, err
	}
	rows, err := s.DB.QueryContext(ctx, `
		SELECT j.id, h.name, (SELECT COUNT(*) FROM jam_members x WHERE x.jam_id = j.id AND x.joined_at IS NOT NULL AND x.left_at IS NULL)
		FROM jams j JOIN jam_members m ON m.jam_id = j.id JOIN users h ON h.id = j.host_id
		WHERE m.user_id = ? AND m.joined_at IS NULL AND m.left_at IS NULL AND j.ended_at IS NULL ORDER BY j.started_at DESC`, user)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	invites := []Invitation{}
	for rows.Next() {
		var inv Invitation
		rows.Scan(&inv.ID, &inv.Host, &inv.Members)
		invites = append(invites, inv)
	}
	return current, invites, rows.Err()
}

// Hears says whether user may stream track for the jam: they're in it, and
// it's in its queue. It's how a song they don't have reaches them (§4C).
func (s *Service) Hears(ctx context.Context, jam, user, track int64) bool {
	if s.ready(ctx, jam, user) != nil {
		return false
	}
	var ok bool
	s.DB.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM jam_queue WHERE jam_id = ? AND track_id = ?)", jam, track).Scan(&ok)
	return ok
}

// InMine reports whether track is in the queue of the jam user is in now:
// what Keep it may put in their library (§4C).
func (s *Service) InMine(ctx context.Context, user, track int64) bool {
	var ok bool
	s.DB.QueryRowContext(ctx, `
		SELECT EXISTS(SELECT 1 FROM jam_queue q JOIN jams j ON j.id = q.jam_id
		JOIN jam_members m ON m.jam_id = q.jam_id AND m.user_id = ?
		WHERE q.track_id = ? AND j.ended_at IS NULL AND m.joined_at IS NOT NULL AND m.left_at IS NULL)`,
		user, track).Scan(&ok)
	return ok
}

func trackID(s string) (int64, bool) {
	n, err := strconv.ParseInt(strings.TrimPrefix(s, "tr-"), 10, 64)
	return n, err == nil && strings.HasPrefix(s, "tr-") && n > 0
}
