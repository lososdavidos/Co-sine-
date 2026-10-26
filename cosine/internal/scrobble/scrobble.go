// Package scrobble sends each account's plays to Last.fm and ListenBrainz
// (§4A). Scrobbling is the server's: the credentials live here, and every
// play Cosine records goes out, whichever app heard it — Sine, or any
// Subsonic client. Plays go in the order they reached Cosine, so one
// played offline and synced later is still sent, with the time it was
// played (Q70). Last.fm takes plays up to two weeks old; ListenBrainz any.
//
// A failure to reach a service is retried later, backing off; a service
// refusing the account's token stops that account's scrobbling there until
// it is connected again. Plays from before an account connected are not sent.
package scrobble

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

const (
	LastFM       = "lastfm"
	ListenBrainz = "listenbrainz"
)

// batch: Last.fm takes at most 50 plays a request.
const batch = 50

var (
	// ErrRefused: the service refused the credentials; connect again.
	ErrRefused = errors.New("refused")
	// ErrNotSetUp: Last.fm needs an API account on this server first.
	ErrNotSetUp = errors.New("Last.fm isn't set up on this server: add an API key and secret in the dashboard's Settings")
	// ErrNotAuthorised: the Last.fm token wasn't approved in the browser.
	ErrNotAuthorised = errors.New("Last.fm hasn't been given access yet: approve it in the browser, then try again")
)

// Play is one play to send.
type Play struct {
	ID          int64
	Title       string
	Artist      string // the track's own credit
	Album       string
	AlbumArtist string
	DurationSec int
	PlayedAt    time.Time
}

// transient is a failure worth trying again later.
type transient struct{ error }

type Service struct {
	DB   *db.DB
	HTTP *http.Client
	Log  *slog.Logger
	// The services' addresses; empty: the real ones.
	LastFMURL, LastFMAuthURL, ListenBrainzURL string
	Version                                   string

	nudge chan struct{}
}

// Status is one account's connection to one service.
type Status struct {
	Service  string `json:"service"`
	Username string `json:"username"`
	Error    string `json:"error,omitempty"` // set: stopped; connect again
	Pending  int    `json:"pending"`         // plays not sent yet
}

func (s *Service) init() {
	if s.nudge == nil {
		s.nudge = make(chan struct{}, 1)
	}
}

// Nudge says a play arrived: send soon. Never blocks.
func (s *Service) Nudge() {
	s.init()
	select {
	case s.nudge <- struct{}{}:
	default:
	}
}

// Run sends plays until ctx ends: when nudged, once an hour in case a
// nudge was missed, and after a failure to reach a service, backing off
// from a minute to an hour.
func (s *Service) Run(ctx context.Context) {
	s.init()
	wait, backoff := time.Duration(0), time.Minute
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.nudge:
		case <-time.After(wait):
		}
		if s.SendAll(ctx) {
			wait, backoff = backoff, min(backoff*2, time.Hour)
		} else {
			wait, backoff = time.Hour, time.Minute
		}
	}
}

// SendAll sends every account's waiting plays, reporting whether any
// service couldn't be reached and is worth trying again.
func (s *Service) SendAll(ctx context.Context) (retry bool) {
	type conn struct {
		user           int64
		service, token string
		upTo           int64
	}
	rows, err := s.DB.QueryContext(ctx, "SELECT user_id, service, token, sent_up_to FROM scrobblers WHERE error IS NULL")
	if err != nil {
		s.Log.Error("scrobbling", "err", err)
		return true
	}
	var conns []conn
	for rows.Next() {
		var c conn
		if rows.Scan(&c.user, &c.service, &c.token, &c.upTo) == nil {
			conns = append(conns, c)
		}
	}
	rows.Close()
	for _, c := range conns {
		for {
			plays, err := s.waiting(ctx, c.user, c.upTo)
			if err != nil {
				s.Log.Error("scrobbling", "err", err)
				return true
			}
			if len(plays) == 0 {
				break
			}
			err = s.send(ctx, c.service, c.token, plays)
			var t transient
			switch {
			case errors.As(err, &t):
				s.Log.Warn("scrobbling: will try again", "service", c.service, "user", c.user, "err", err)
				retry = true
			case err != nil:
				s.Log.Warn("scrobbling stopped: connect again", "service", c.service, "user", c.user, "err", err)
				s.DB.ExecContext(ctx, "UPDATE scrobblers SET error = ? WHERE user_id = ? AND service = ?",
					err.Error(), c.user, c.service)
			}
			if err != nil {
				break
			}
			c.upTo = plays[len(plays)-1].ID
			if _, err := s.DB.ExecContext(ctx, "UPDATE scrobblers SET sent_up_to = ? WHERE user_id = ? AND service = ?",
				c.upTo, c.user, c.service); err != nil {
				s.Log.Error("scrobbling", "err", err)
				return true
			}
		}
	}
	return retry
}

func (s *Service) send(ctx context.Context, service, token string, plays []Play) error {
	switch service {
	case LastFM:
		key, secret := s.lastFMAccount(ctx)
		if key == "" {
			return transient{ErrNotSetUp} // kept until the dashboard has a key again
		}
		return s.lastFMScrobble(ctx, key, secret, token, plays)
	case ListenBrainz:
		return s.listenBrainzSubmit(ctx, token, plays)
	}
	return fmt.Errorf("unknown service %q", service)
}

// waiting reads the next plays for user after the play upTo.
func (s *Service) waiting(ctx context.Context, user, upTo int64) ([]Play, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT p.id, t.title, ta.name, r.title, ra.name, p.played_at,
		       COALESCE((SELECT MAX(o.duration_sec) FROM objects o WHERE o.track_id = t.id), 0)
		FROM plays p JOIN tracks t ON t.id = p.track_id
		JOIN artists ta ON ta.id = t.artist_id
		JOIN releases r ON r.id = t.release_id JOIN artists ra ON ra.id = r.artist_id
		WHERE p.user_id = ? AND p.id > ? ORDER BY p.id LIMIT ?`, user, upTo, batch)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Play
	for rows.Next() {
		var p Play
		var at int64
		if err := rows.Scan(&p.ID, &p.Title, &p.Artist, &p.Album, &p.AlbumArtist, &at, &p.DurationSec); err != nil {
			return nil, err
		}
		p.PlayedAt = time.UnixMilli(at)
		out = append(out, p)
	}
	return out, rows.Err()
}

// Statuses lists the services user scrobbles to.
func (s *Service) Statuses(ctx context.Context, user int64) ([]Status, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT c.service, c.username, COALESCE(c.error, ''),
		       (SELECT COUNT(*) FROM plays p WHERE p.user_id = c.user_id AND p.id > c.sent_up_to)
		FROM scrobblers c WHERE c.user_id = ? ORDER BY c.service`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Status{}
	for rows.Next() {
		var st Status
		if err := rows.Scan(&st.Service, &st.Username, &st.Error, &st.Pending); err != nil {
			return nil, err
		}
		out = append(out, st)
	}
	return out, rows.Err()
}

// connect stores an account's credentials for a service. Plays from now
// on are sent; those before are not.
func (s *Service) connect(ctx context.Context, user int64, service, token, username string) error {
	var last int64
	if err := s.DB.QueryRowContext(ctx, "SELECT COALESCE(MAX(id), 0) FROM plays WHERE user_id = ?", user).Scan(&last); err != nil {
		return err
	}
	_, err := s.DB.ExecContext(ctx, `
		INSERT INTO scrobblers(user_id, service, token, username, sent_up_to, connected_at) VALUES(?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id, service) DO UPDATE SET token = excluded.token, username = excluded.username,
		    error = NULL, connected_at = excluded.connected_at,
		    sent_up_to = CASE WHEN scrobblers.username = excluded.username THEN scrobblers.sent_up_to
		                      ELSE excluded.sent_up_to END`,
		user, service, token, username, last, db.Now())
	return err
}

// Disconnect stops scrobbling user's plays to a service.
func (s *Service) Disconnect(ctx context.Context, user int64, service string) error {
	_, err := s.DB.ExecContext(ctx, "DELETE FROM scrobblers WHERE user_id = ? AND service = ?", user, service)
	return err
}

func (s *Service) lastFMAccount(ctx context.Context) (key, secret string) {
	key, _ = s.DB.Setting(ctx, db.SettingLastFMKey)
	secret, _ = s.DB.Setting(ctx, db.SettingLastFMSecret)
	if secret == "" {
		key = ""
	}
	return key, secret
}

// LastFMReady says whether Last.fm can be connected: the server has an API account.
func (s *Service) LastFMReady(ctx context.Context) bool {
	key, _ := s.lastFMAccount(ctx)
	return key != ""
}

func (s *Service) client() *http.Client {
	if s.HTTP != nil {
		return s.HTTP
	}
	return http.DefaultClient
}
