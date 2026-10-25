package webcache

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

func newClient(t *testing.T, h http.HandlerFunc) (*Client, *atomic.Int32, string) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		h(w, r)
	}))
	t.Cleanup(srv.Close)
	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	c := &Client{Name: "Test", HTTP: srv.Client(), DB: d, UserAgent: "ua",
		Pace: func() time.Duration { return time.Millisecond }}
	return c, &calls, srv.URL
}

func TestAnswersAreCachedButCredentialsAreNot(t *testing.T) {
	c, calls, url := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "secret" || r.Header.Get("User-Agent") != "ua" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/missing" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Write([]byte(`{"ok":true}`))
	})
	c.Prepare = func(r *http.Request) { r.Header.Set("Authorization", "secret") }
	ctx := context.Background()
	srvURL := func(p string) string { return url + p }
	for range 2 {
		if b, err := c.Get(ctx, srvURL("/a")); err != nil || string(b) != `{"ok":true}` {
			t.Fatal(string(b), err)
		}
		if _, err := c.Get(ctx, srvURL("/missing")); !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		if _, err := c.Post(ctx, srvURL("/a"), "application/json", []byte(`{"q":1}`)); err != nil {
			t.Fatal(err)
		}
	}
	if calls.Load() != 3 {
		t.Fatalf("%d calls; repeated questions should come from the cache", calls.Load())
	}
	var leaked int
	c.DB.QueryRow("SELECT COUNT(*) FROM lookup_cache WHERE key LIKE '%secret%' OR body LIKE '%secret%'").Scan(&leaked)
	if leaked != 0 {
		t.Fatal("a credential reached the cache")
	}
}

func TestFailureBacksOffAndBotChecksAreNotAnswers(t *testing.T) {
	c, calls, url := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("<html>Client Challenge</html>"))
	})
	c.Valid = func(b []byte) bool { return len(b) > 0 && b[0] == '{' }
	ctx := context.Background()
	u := url + "/search"
	_, err := c.Get(ctx, u)
	if !errors.Is(err, resolve.ErrUnavailable) || err.Error() == "" {
		t.Fatal(err)
	}
	if _, err := c.Get(ctx, u); !errors.Is(err, resolve.ErrUnavailable) || calls.Load() != 1 {
		t.Fatalf("expected a backoff without asking again: %v, %d calls", err, calls.Load())
	}
	var cached int
	c.DB.QueryRow("SELECT COUNT(*) FROM lookup_cache").Scan(&cached)
	if cached != 0 {
		t.Fatal("a bot check was cached as an answer")
	}
}
