package scrobble

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

type env struct {
	s     *Service
	db    *db.DB
	alice int64
	track int64
}

func setup(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	res, _ := d.Exec("INSERT INTO users(name, password_enc, created_at) VALUES('alice', x'00', 0)")
	alice, _ := res.LastInsertId()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	g := &ingest.Ingester{DB: d, DataDir: dir, Log: log, Probe: func(string) int { return 201 },
		Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}
	os.MkdirAll(filepath.Join(dir, "drop"), 0o755)
	out, err := g.Ingest(ctx, ingest.Request{Path: testutil.MP3{Title: "Without Me", Artist: "Halsey", Album: "Manic", Body: "x"}.
		Write(t, filepath.Join(dir, "drop", "a.mp3")), Source: "url", ForUsers: []int64{alice}})
	if err != nil {
		t.Fatal(err)
	}
	return &env{&Service{DB: d, Log: log, Version: "t"}, d, alice, out.TrackID}
}

func (e *env) play(at time.Time) {
	e.db.Exec("INSERT INTO plays(user_id, track_id, played_at) VALUES(?, ?, ?)", e.alice, e.track, at.UnixMilli())
}

func (e *env) pending(t *testing.T) (int, string) {
	t.Helper()
	st, err := e.s.Statuses(context.Background(), e.alice)
	if err != nil || len(st) != 1 {
		t.Fatal(st, err)
	}
	return st[0].Pending, st[0].Error
}

func TestListenBrainz(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	var mu sync.Mutex
	var got []map[string]any
	status := http.StatusOK
	lb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Header.Get("Authorization") != "Token secret-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/1/validate-token":
			json.NewEncoder(w).Encode(map[string]any{"valid": true, "user_name": "alice_lb"})
		case "/1/submit-listens":
			w.WriteHeader(status)
			if status == http.StatusOK {
				var body map[string]any
				json.NewDecoder(r.Body).Decode(&body)
				got = append(got, body)
			}
		}
	}))
	defer lb.Close()
	e.s.ListenBrainzURL = lb.URL

	e.play(time.Now().Add(-time.Hour)) // before connecting: not sent
	if _, err := e.s.ConnectListenBrainz(ctx, e.alice, "wrong"); err != ErrRefused {
		t.Fatal(err)
	}
	name, err := e.s.ConnectListenBrainz(ctx, e.alice, "secret-token")
	if err != nil || name != "alice_lb" {
		t.Fatal(name, err)
	}
	if n, _ := e.pending(t); n != 0 {
		t.Fatal("plays from before connecting aren't sent:", n)
	}

	// Played offline yesterday, synced now: sent, with yesterday's time.
	yesterday := time.Now().Add(-24 * time.Hour).Truncate(time.Second)
	e.play(yesterday)
	if retry := e.s.SendAll(ctx); retry {
		t.Fatal("nothing to retry")
	}
	if len(got) != 1 || got[0]["listen_type"] != "single" {
		t.Fatal(got)
	}
	l := got[0]["payload"].([]any)[0].(map[string]any)
	meta := l["track_metadata"].(map[string]any)
	if int64(l["listened_at"].(float64)) != yesterday.Unix() || meta["artist_name"] != "Halsey" ||
		meta["track_name"] != "Without Me" || meta["release_name"] != "Manic" ||
		meta["additional_info"].(map[string]any)["duration_ms"] != float64(201000) {
		t.Fatal(l)
	}
	if n, _ := e.pending(t); n != 0 {
		t.Fatal(n)
	}

	// Down for a while: kept, and sent once it's back.
	status = http.StatusServiceUnavailable
	e.play(time.Now())
	if !e.s.SendAll(ctx) {
		t.Fatal("unreachable: try again later")
	}
	if n, errMsg := e.pending(t); n != 1 || errMsg != "" {
		t.Fatal(n, errMsg)
	}
	status = http.StatusOK
	e.s.SendAll(ctx)
	if n, _ := e.pending(t); n != 0 || len(got) != 2 {
		t.Fatal(n, got)
	}

	// The token revoked: stopped, kept, until connected again.
	e.db.Exec("UPDATE scrobblers SET token = 'revoked'")
	e.play(time.Now().Add(time.Second))
	e.s.SendAll(ctx)
	if n, errMsg := e.pending(t); n != 1 || errMsg == "" {
		t.Fatal(n, errMsg)
	}
	e.s.ConnectListenBrainz(ctx, e.alice, "secret-token")
	e.s.SendAll(ctx)
	if n, _ := e.pending(t); n != 0 || len(got) != 3 {
		t.Fatal("reconnected as the same account, what was missed goes:", n, len(got))
	}
}

func TestLastFM(t *testing.T) {
	e := setup(t)
	ctx := context.Background()
	if _, _, err := e.s.LastFMStart(ctx); err != ErrNotSetUp {
		t.Fatal(err)
	}
	e.db.SetSetting(ctx, db.SettingLastFMKey, "key1")
	e.db.SetSetting(ctx, db.SettingLastFMSecret, "shh")

	var mu sync.Mutex
	approved := false
	scrobbleError := 0
	var scrobbled []map[string]string
	fm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		r.ParseForm()
		v := map[string]string{}
		var names []string
		for k := range r.Form {
			v[k] = r.Form.Get(k)
			if k != "format" && k != "api_sig" {
				names = append(names, k)
			}
		}
		sort.Strings(names)
		var sig strings.Builder
		for _, k := range names {
			sig.WriteString(k + v[k])
		}
		sum := md5.Sum([]byte(sig.String() + "shh"))
		if v["api_sig"] != hex.EncodeToString(sum[:]) || v["api_key"] != "key1" || v["format"] != "json" {
			w.WriteHeader(http.StatusForbidden)
			json.NewEncoder(w).Encode(map[string]any{"error": 13, "message": "Invalid method signature"})
			return
		}
		switch v["method"] {
		case "auth.getToken":
			json.NewEncoder(w).Encode(map[string]any{"token": "tok1"})
		case "auth.getSession":
			if !approved {
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]any{"error": 14, "message": "Unauthorized Token"})
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"session": map[string]any{"name": "AliceFM", "key": "sk1"}})
		case "track.scrobble":
			if r.Method != http.MethodPost || v["sk"] != "sk1" {
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]any{"error": 9, "message": "Invalid session key"})
				return
			}
			if scrobbleError != 0 {
				w.WriteHeader(http.StatusServiceUnavailable)
				json.NewEncoder(w).Encode(map[string]any{"error": scrobbleError, "message": "Try later"})
				return
			}
			scrobbled = append(scrobbled, v)
			json.NewEncoder(w).Encode(map[string]any{"scrobbles": map[string]any{"@attr": map[string]any{"accepted": 1}}})
		}
	}))
	defer fm.Close()
	e.s.LastFMURL, e.s.LastFMAuthURL = fm.URL, "https://www.last.fm/api/auth/"

	approveAt, token, err := e.s.LastFMStart(ctx)
	if err != nil || token != "tok1" || approveAt != "https://www.last.fm/api/auth/?api_key=key1&token=tok1" {
		t.Fatal(approveAt, token, err)
	}
	if _, err := e.s.LastFMFinish(ctx, e.alice, token); err != ErrNotAuthorised {
		t.Fatal("not approved in the browser yet:", err)
	}
	approved = true
	name, err := e.s.LastFMFinish(ctx, e.alice, token)
	if err != nil || name != "AliceFM" {
		t.Fatal(name, err)
	}

	at := time.Now().Add(-time.Minute).Truncate(time.Second)
	e.play(at)
	scrobbleError = 16 // temporarily unavailable
	if !e.s.SendAll(ctx) {
		t.Fatal("try again later")
	}
	scrobbleError = 0
	e.s.SendAll(ctx)
	if len(scrobbled) != 1 {
		t.Fatal(scrobbled)
	}
	if s := scrobbled[0]; s["artist[0]"] != "Halsey" || s["track[0]"] != "Without Me" || s["album[0]"] != "Manic" ||
		s["duration[0]"] != "201" || s["timestamp[0]"] != strconv.FormatInt(at.Unix(), 10) {
		t.Fatal(s)
	}

	// The session revoked on Last.fm: stopped until connected again.
	e.db.Exec("UPDATE scrobblers SET token = 'gone'")
	e.play(time.Now())
	e.s.SendAll(ctx)
	if n, errMsg := e.pending(t); n != 1 || !strings.Contains(errMsg, "Invalid session key") {
		t.Fatal(n, errMsg)
	}
	if err := e.s.Disconnect(ctx, e.alice, LastFM); err != nil {
		t.Fatal(err)
	}
	if st, _ := e.s.Statuses(ctx, e.alice); len(st) != 0 {
		t.Fatal(st)
	}
}

func TestRunSendsWhenNudged(t *testing.T) {
	e := setup(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sent := make(chan struct{}, 10)
	lb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/1/validate-token" {
			json.NewEncoder(w).Encode(map[string]any{"valid": true, "user_name": "a"})
			return
		}
		sent <- struct{}{}
	}))
	defer lb.Close()
	e.s.ListenBrainzURL = lb.URL
	e.s.ConnectListenBrainz(ctx, e.alice, "t")
	go e.s.Run(ctx)
	time.Sleep(50 * time.Millisecond) // past the pass made at start
	e.play(time.Now())
	e.s.Nudge()
	select {
	case <-sent:
	case <-time.After(5 * time.Second):
		t.Fatal("a nudge sends the play")
	}
}
