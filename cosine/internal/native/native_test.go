package native

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/fetch"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

const trackURL = "https://soundcloud.com/skeler/tides"

func server(t *testing.T, withYtDlp bool) *httptest.Server {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	ctx := context.Background()
	d.SetSetting(ctx, db.SettingStorePath, filepath.Join(dir, "store"))
	key, _ := auth.LoadOrCreateKey(filepath.Join(dir, "k"))
	as, _ := auth.New(d, key)
	as.CreateUser(ctx, "alice", "sesame", false)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	api := &API{Auth: as, Version: "test", Log: log}
	if withYtDlp {
		bin, _ := testutil.FakeYtDlp(t, map[string]any{
			trackURL: map[string]any{"_type": "video", "id": "1", "title": "Tides", "uploader": "Skeler",
				"webpage_url": trackURL, "ext": "opus", "body": "x", "extractor_key": "Soundcloud"},
		})
		runner := ytdlp.Runner{Bin: bin}
		f := &fetch.Fetcher{DB: d, Runner: runner, WorkDir: filepath.Join(dir, "work"), Workers: 1, Log: log,
			Search: &search.Service{DB: d, YtDlp: search.YtDlpSearch{Runner: runner}, NewAPI: func(string, string) search.Backend { return nil }},
			Ingester: &ingest.Ingester{DB: d, DataDir: dir, Log: log,
				Resolver: resolve.Chain{Resolvers: []resolve.Resolver{resolve.SourceMetadata{}}}}}
		runCtx, cancel := context.WithCancel(context.Background())
		t.Cleanup(cancel)
		go f.Run(runCtx)
		api.Fetcher = f
	}
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func call(t *testing.T, srv *httptest.Server, method, path, token string, body any) (int, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, srv.URL+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func login(t *testing.T, srv *httptest.Server) string {
	sum := md5.Sum([]byte("sesame" + "salt1"))
	code, out := call(t, srv, "POST", "/cosine/v1/auth", "", map[string]string{
		"username": "alice", "token": hex.EncodeToString(sum[:]), "salt": "salt1"})
	if code != 200 {
		t.Fatal(code, out)
	}
	return out["token"].(string)
}

func TestCapabilitiesFollowYtDlp(t *testing.T) {
	_, out := call(t, server(t, true), "GET", "/cosine/v1/capabilities", "", nil)
	if caps := out["capabilities"].([]any); len(caps) != 1 || caps[0] != "ingest" {
		t.Fatal(out)
	}
	srv := server(t, false)
	_, out = call(t, srv, "GET", "/cosine/v1/capabilities", "", nil)
	if caps := out["capabilities"].([]any); len(caps) != 0 {
		t.Fatal("without yt-dlp, ingest must not be advertised:", out)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/lookup", login(t, srv), map[string]string{"input": "x"}); code != http.StatusNotImplemented {
		t.Fatal(code)
	}
}

func TestAuthRequired(t *testing.T) {
	srv := server(t, true)
	if code, _ := call(t, srv, "GET", "/cosine/v1/ingest/jobs", "", nil); code != 401 {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "GET", "/cosine/v1/ingest/jobs", "forged", nil); code != 401 {
		t.Fatal(code)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/auth", "", map[string]string{"username": "alice", "token": "bad", "salt": "s"}); code != 401 {
		t.Fatal(code)
	}
}

func TestLookupSubmitJobsRetry(t *testing.T) {
	srv := server(t, true)
	tok := login(t, srv)

	code, look := call(t, srv, "POST", "/cosine/v1/lookup", tok, map[string]string{"input": trackURL})
	if code != 200 || look["kind"] != "single" || len(look["items"].([]any)) != 1 {
		t.Fatal(code, look)
	}
	code, sub := call(t, srv, "POST", "/cosine/v1/ingest", tok, map[string]any{"urls": []string{trackURL}})
	if code != http.StatusAccepted {
		t.Fatal(code, sub)
	}
	id := int(sub["jobs"].([]any)[0].(map[string]any)["id"].(float64))

	var job map[string]any
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		_, out := call(t, srv, "GET", "/cosine/v1/ingest/jobs", tok, nil)
		job = out["jobs"].([]any)[0].(map[string]any)
		if job["status"] == "done" {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if job["status"] != "done" || job["trackId"] == nil || job["title"] != "Tides" {
		t.Fatal(job)
	}
	if code, _ := call(t, srv, "POST", "/cosine/v1/ingest/jobs/"+itoa(id)+"/retry", tok, nil); code != http.StatusConflict {
		t.Fatal("a finished job cannot be retried:", code)
	}

	if code, out := call(t, srv, "POST", "/cosine/v1/ingest", tok, map[string]any{"urls": []string{"just words"}}); code != 400 {
		t.Fatal(code, out)
	}
}

func itoa(n int) string { b, _ := json.Marshal(n); return string(b) }
