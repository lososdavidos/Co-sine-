// Package musicbrainz is the resolver's first tier (§3.2): canonical
// artist, release and recording identity for properly released music, and
// release covers from the Cover Art Archive.
package musicbrainz

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Client talks to the MusicBrainz web service within its rules: a
// meaningful User-Agent, at most one request per second, and every answer
// cached forever so no question is asked twice (Q15).
type Client struct {
	HTTP      *http.Client
	DB        *db.DB // the cache; nil disables caching
	UserAgent string
	BaseURL   string        // default https://musicbrainz.org/ws/2
	Interval  time.Duration // default 1s

	mu   sync.Mutex
	last time.Time
	// After a network failure MusicBrainz is skipped for a while, so an
	// offline server files things at tier 4 without waiting on every lookup.
	downUntil time.Time
}

const backoff = 5 * time.Minute

// ErrUnavailable means MusicBrainz could not be asked (offline, throttled,
// down). The resolver treats it as "no answer", never as a failed ingest.
var ErrUnavailable = errors.New("MusicBrainz is unavailable")

func UserAgent(version string) string {
	return fmt.Sprintf("Cosine/%s ( https://github.com/lososdavidos/Co-sine- )", version)
}

func (c *Client) base() string {
	if c.BaseURL != "" {
		return strings.TrimRight(c.BaseURL, "/")
	}
	return "https://musicbrainz.org/ws/2"
}

// get fetches path?query as JSON into v, through the cache.
func (c *Client) get(ctx context.Context, path string, query url.Values, v any) error {
	query.Set("fmt", "json")
	u := c.base() + path + "?" + query.Encode()

	if body, ok := c.cached(ctx, u); ok {
		return json.Unmarshal(body, v)
	}
	body, status, err := c.fetch(ctx, u)
	if err != nil {
		return err
	}
	if status == http.StatusOK || status == http.StatusNotFound {
		c.store(ctx, u, status, body) // a "no such thing" is an answer too
	}
	if status == http.StatusNotFound {
		return errNotFound
	}
	return json.Unmarshal(body, v)
}

var errNotFound = errors.New("not found")

func (c *Client) fetch(ctx context.Context, u string) ([]byte, int, error) {
	c.mu.Lock()
	down := time.Now().Before(c.downUntil)
	c.mu.Unlock()
	if down {
		return nil, 0, fmt.Errorf("%w: backing off after a recent failure", ErrUnavailable)
	}
	body, status, err := c.attempt(ctx, u)
	if errors.Is(err, ErrUnavailable) {
		c.mu.Lock()
		c.downUntil = time.Now().Add(backoff)
		c.mu.Unlock()
	}
	return body, status, err
}

func (c *Client) attempt(ctx context.Context, u string) ([]byte, int, error) {
	for attempt := 0; ; attempt++ {
		if err := c.wait(ctx); err != nil {
			return nil, 0, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return nil, 0, err
		}
		req.Header.Set("User-Agent", c.UserAgent)
		req.Header.Set("Accept", "application/json")
		res, err := c.HTTP.Do(req)
		if err != nil {
			return nil, 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
		}
		body, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
		res.Body.Close()
		if err != nil {
			return nil, 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
		}
		switch {
		case res.StatusCode == http.StatusOK || res.StatusCode == http.StatusNotFound:
			return body, res.StatusCode, nil
		case (res.StatusCode == http.StatusServiceUnavailable || res.StatusCode == http.StatusTooManyRequests) && attempt < 2:
			continue // throttled: the limiter spaces the retry
		default:
			return nil, 0, fmt.Errorf("%w: HTTP %d", ErrUnavailable, res.StatusCode)
		}
	}
}

// wait enforces the request interval across every caller.
func (c *Client) wait(ctx context.Context) error {
	interval := c.Interval
	if interval == 0 {
		interval = time.Second
	}
	c.mu.Lock()
	next := c.last.Add(interval)
	now := time.Now()
	if next.Before(now) {
		next = now
	}
	c.last = next
	c.mu.Unlock()
	select {
	case <-time.After(time.Until(next)):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *Client) cached(ctx context.Context, key string) ([]byte, bool) {
	if c.DB == nil {
		return nil, false
	}
	var body []byte
	var status int
	err := c.DB.QueryRowContext(ctx, "SELECT status, body FROM lookup_cache WHERE key = ?", key).Scan(&status, &body)
	if errors.Is(err, sql.ErrNoRows) || err != nil {
		return nil, false
	}
	if status == http.StatusNotFound {
		return []byte("{}"), true
	}
	return body, true
}

func (c *Client) store(ctx context.Context, key string, status int, body []byte) {
	if c.DB == nil {
		return
	}
	c.DB.ExecContext(ctx,
		"INSERT INTO lookup_cache(key, status, body, fetched_at) VALUES(?, ?, ?, ?) ON CONFLICT(key) DO NOTHING",
		key, status, body, db.Now())
}

// ---------------------------------------------------------------- wire types

type artistCredit struct {
	Name       string `json:"name"`
	JoinPhrase string `json:"joinphrase"`
	Artist     struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	} `json:"artist"`
}

type credits []artistCredit

// String joins a credit the way MusicBrainz displays it: "A feat. B".
func (cs credits) String() string {
	var b strings.Builder
	for _, c := range cs {
		b.WriteString(c.Name)
		b.WriteString(c.JoinPhrase)
	}
	return strings.TrimSpace(b.String())
}

func (cs credits) firstID() string {
	if len(cs) == 0 {
		return ""
	}
	return cs[0].Artist.ID
}

type release struct {
	ID           string  `json:"id"`
	Title        string  `json:"title"`
	Status       string  `json:"status"`
	Date         string  `json:"date"`
	ArtistCredit credits `json:"artist-credit"`
	ReleaseGroup struct {
		ID          string `json:"id"`
		PrimaryType string `json:"primary-type"`
	} `json:"release-group"`
	Media []struct {
		Position int `json:"position"`
		Track    []struct {
			Number   string `json:"number"`
			Position int    `json:"position"`
		} `json:"track"`
	} `json:"media"`
}

type recording struct {
	ID           string    `json:"id"`
	Score        int       `json:"score"`
	Title        string    `json:"title"`
	Length       int       `json:"length"` // ms
	ArtistCredit credits   `json:"artist-credit"`
	Releases     []release `json:"releases"`
}

type recordingSearch struct {
	Recordings []recording `json:"recordings"`
}

// searchRecordings runs a recording search.
func (c *Client) searchRecordings(ctx context.Context, query string, limit int) ([]recording, error) {
	var out recordingSearch
	err := c.get(ctx, "/recording", url.Values{"query": {query}, "limit": {fmt.Sprint(limit)}}, &out)
	if errors.Is(err, errNotFound) {
		return nil, nil
	}
	return out.Recordings, err
}

// phrase quotes a value for a Lucene phrase query.
func phrase(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`)
	return `"` + r.Replace(s) + `"`
}
