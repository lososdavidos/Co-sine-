// Package webcache is how Cosine asks outside catalogues (Discogs,
// Bandcamp) questions: politely spaced, every answer cached forever so no
// question is asked twice (Q15), and a catalogue that stops answering is
// left alone for a while instead of slowing every ingest.
package webcache

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// ErrNotFound is a 404: an answer, and cached like one.
var ErrNotFound = errors.New("not found")

const backoff = 5 * time.Minute

type Client struct {
	Name      string // "Discogs": names the catalogue in errors
	HTTP      *http.Client
	DB        *db.DB // the cache (lookup_cache); nil disables it
	UserAgent string
	// Pace is the gap between requests, read before each one so a setting
	// (a Discogs token raises its limit) applies at once. Default 1s.
	Pace func() time.Duration
	// Prepare adds per-request headers, such as credentials, that must not
	// be part of the cache key.
	Prepare func(*http.Request)
	// Valid rejects a 200 that isn't an answer, such as a bot-check page.
	Valid func(body []byte) bool

	mu        sync.Mutex
	last      time.Time
	downUntil time.Time
}

// Get fetches a URL through the cache.
func (c *Client) Get(ctx context.Context, url string) ([]byte, error) {
	return c.do(ctx, http.MethodGet, url, "", nil)
}

// Post sends a body through the cache: the same question gets the same answer.
func (c *Client) Post(ctx context.Context, url, contentType string, body []byte) ([]byte, error) {
	return c.do(ctx, http.MethodPost, url, contentType, body)
}

func (c *Client) unavailable(why string) error {
	return fmt.Errorf("%w: %s", resolve.Unavailable(c.Name), why)
}

func (c *Client) do(ctx context.Context, method, url, contentType string, body []byte) ([]byte, error) {
	key := url
	if method != http.MethodGet {
		key = method + " " + url + "\n" + string(body)
	}
	if b, status, ok := c.cached(ctx, key); ok {
		if status == http.StatusNotFound {
			return nil, ErrNotFound
		}
		return b, nil
	}
	c.mu.Lock()
	down := time.Now().Before(c.downUntil)
	c.mu.Unlock()
	if down {
		return nil, c.unavailable("backing off after a recent failure")
	}
	b, status, err := c.attempt(ctx, method, url, contentType, body)
	if err != nil {
		if errors.Is(err, resolve.ErrUnavailable) {
			c.mu.Lock()
			c.downUntil = time.Now().Add(backoff)
			c.mu.Unlock()
		}
		return nil, err
	}
	c.store(ctx, key, status, b)
	if status == http.StatusNotFound {
		return nil, ErrNotFound
	}
	return b, nil
}

func (c *Client) attempt(ctx context.Context, method, url, contentType string, body []byte) ([]byte, int, error) {
	for attempt := 0; ; attempt++ {
		if err := c.wait(ctx); err != nil {
			return nil, 0, err
		}
		req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(body))
		if err != nil {
			return nil, 0, err
		}
		req.Header.Set("User-Agent", c.UserAgent)
		if contentType != "" {
			req.Header.Set("Content-Type", contentType)
		}
		if c.Prepare != nil {
			c.Prepare(req)
		}
		res, err := c.HTTP.Do(req)
		if err != nil {
			if ctx.Err() != nil {
				return nil, 0, ctx.Err()
			}
			return nil, 0, c.unavailable(err.Error())
		}
		b, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
		res.Body.Close()
		if err != nil {
			return nil, 0, c.unavailable(err.Error())
		}
		switch {
		case res.StatusCode == http.StatusOK:
			if c.Valid != nil && !c.Valid(b) {
				return nil, 0, c.unavailable("it answered with something other than data (a bot check?)")
			}
			return b, res.StatusCode, nil
		case res.StatusCode == http.StatusNotFound:
			return b, res.StatusCode, nil
		case (res.StatusCode == http.StatusTooManyRequests || res.StatusCode == http.StatusServiceUnavailable) && attempt < 2:
			continue // throttled: the pace spaces the retry
		default:
			return nil, 0, c.unavailable(fmt.Sprintf("HTTP %d", res.StatusCode))
		}
	}
}

// wait enforces the pace across every caller.
func (c *Client) wait(ctx context.Context) error {
	pace := time.Second
	if c.Pace != nil {
		pace = c.Pace()
	}
	c.mu.Lock()
	next := c.last.Add(pace)
	if now := time.Now(); next.Before(now) {
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

func (c *Client) cached(ctx context.Context, key string) ([]byte, int, bool) {
	if c.DB == nil {
		return nil, 0, false
	}
	var body []byte
	var status int
	err := c.DB.QueryRowContext(ctx, "SELECT status, body FROM lookup_cache WHERE key = ?", key).Scan(&status, &body)
	if errors.Is(err, sql.ErrNoRows) || err != nil {
		return nil, 0, false
	}
	return body, status, true
}

func (c *Client) store(ctx context.Context, key string, status int, body []byte) {
	if c.DB == nil {
		return
	}
	c.DB.ExecContext(ctx,
		"INSERT INTO lookup_cache(key, status, body, fetched_at) VALUES(?, ?, ?, ?) ON CONFLICT(key) DO NOTHING",
		key, status, body, db.Now())
}
