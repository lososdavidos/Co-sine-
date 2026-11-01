package devices

import (
	"context"
	"database/sql"
	"errors"
	"sync"
	"time"
)

// Remote control goes through Cosine (§4B): a Device listens here for
// commands, holding a request open until one comes; another of the
// account's Devices sends them. Commands are of the moment and kept in
// memory only: a restart loses the ones not yet taken, which is right.

// Command is something to do to a Device's playback.
type Command struct {
	// play, pause, toggle, next, previous, seek, skip, volume, queue
	Type       string   `json:"type"`
	PositionMs int64    `json:"positionMs,omitempty"` // seek, queue: where to start
	Index      int      `json:"index,omitempty"`      // skip, queue: which song in the queue
	Volume     float64  `json:"volume,omitempty"`     // volume: 0 to 1
	Queue      []string `json:"queue,omitempty"`      // queue: songs to play instead
	From       string   `json:"from,omitempty"`       // who sent it, by Device name
}

var commandTypes = map[string]bool{
	"play": true, "pause": true, "toggle": true, "next": true, "previous": true,
	"seek": true, "skip": true, "volume": true, "queue": true,
}

var (
	ErrCommand = errors.New("unknown command")
	// ErrAway: the Device isn't listening now, so nothing would reach it.
	ErrAway = errors.New("that device isn't listening now")
)

// Listening is how recently a Device must have asked for commands to count as there.
const Listening = 70 * time.Second

// commandTTL: a command not taken by then is dropped, rather than acted on late.
const commandTTL = 20 * time.Second

type mailbox struct {
	waiting  []queued
	wake     chan struct{}
	listened time.Time
}

type queued struct {
	cmd Command
	at  time.Time
}

// Hub holds the commands on their way, per Device.
type Hub struct {
	mu    sync.Mutex
	boxes map[int64]*mailbox
	now   func() time.Time
}

func (h *Hub) box(device int64) *mailbox {
	if h.boxes == nil {
		h.boxes = map[int64]*mailbox{}
	}
	b := h.boxes[device]
	if b == nil {
		b = &mailbox{wake: make(chan struct{})}
		h.boxes[device] = b
	}
	return b
}

func (h *Hub) clock() time.Time {
	if h.now != nil {
		return h.now()
	}
	return time.Now()
}

// listening says whether the Device asked for commands lately.
func (h *Hub) listening(device int64) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	b := h.boxes[device]
	return b != nil && h.clock().Sub(b.listened) < Listening
}

func (h *Hub) send(device int64, c Command) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	b := h.box(device)
	if h.clock().Sub(b.listened) >= Listening {
		return ErrAway
	}
	b.waiting = append(b.waiting, queued{c, h.clock()})
	close(b.wake)
	b.wake = make(chan struct{})
	return nil
}

// take returns the Device's commands, waiting up to wait for one to come.
func (h *Hub) take(ctx context.Context, device int64, wait time.Duration) []Command {
	deadline := time.NewTimer(wait)
	defer deadline.Stop()
	for {
		h.mu.Lock()
		b := h.box(device)
		b.listened = h.clock()
		var out []Command
		for _, q := range b.waiting {
			if h.clock().Sub(q.at) < commandTTL {
				out = append(out, q.cmd)
			}
		}
		b.waiting = nil
		wake := b.wake
		h.mu.Unlock()
		if len(out) > 0 {
			return out
		}
		select {
		case <-wake:
		case <-deadline.C:
			h.mu.Lock()
			h.box(device).listened = h.clock()
			h.mu.Unlock()
			return nil
		case <-ctx.Done():
			return nil
		}
	}
}

func (s *Service) hub() *Hub {
	s.once.Do(func() {
		if s.Hub == nil {
			s.Hub = &Hub{now: s.Now}
		}
	})
	return s.Hub
}

func (s *Service) rowID(ctx context.Context, user int64, clientID string) (int64, error) {
	var id int64
	err := s.DB.QueryRowContext(ctx, "SELECT id FROM devices WHERE user_id = ? AND client_id = ?", user, clientID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return id, err
}

// Send gives a command to one of the account's Devices; ErrAway when it
// isn't listening, so the sender can say so instead of waiting.
func (s *Service) Send(ctx context.Context, user int64, clientID string, c Command) error {
	if !commandTypes[c.Type] {
		return ErrCommand
	}
	id, err := s.rowID(ctx, user, clientID)
	if err != nil {
		return err
	}
	if len(c.Queue) > MaxQueue {
		c.Queue = c.Queue[:MaxQueue]
	}
	return s.hub().send(id, c)
}

// Listen waits up to wait for commands for the Device, and notes that it's
// there to be controlled.
func (s *Service) Listen(ctx context.Context, user int64, clientID string, wait time.Duration) ([]Command, error) {
	id, err := s.rowID(ctx, user, clientID)
	if err != nil {
		return nil, err
	}
	s.DB.ExecContext(ctx, "UPDATE devices SET last_seen = ? WHERE id = ?", s.now().UnixMilli(), id)
	out := s.hub().take(ctx, id, wait)
	if out == nil {
		out = []Command{}
	}
	return out, nil
}
