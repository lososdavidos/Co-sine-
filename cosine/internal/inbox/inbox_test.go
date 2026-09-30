package inbox

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

type recorder struct {
	mu    sync.Mutex
	paths []string
	fail  bool
}

func (r *recorder) ingest(_ context.Context, path string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.paths = append(r.paths, filepath.Base(path))
	if r.fail {
		return errors.New("boom")
	}
	return os.Remove(path)
}

func (r *recorder) got() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.paths...)
}

func start(t *testing.T, dir string, r *recorder) {
	w := &Watcher{Dir: dir, Ingest: r.ingest, Settle: 200 * time.Millisecond,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go w.Run(ctx)
	time.Sleep(50 * time.Millisecond)
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatal("timed out")
}

func TestPicksUpExistingAndNewFilesInSubfolders(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "early.mp3"), []byte("x"), 0o644)
	r := &recorder{}
	start(t, dir, r)

	os.MkdirAll(filepath.Join(dir, "album"), 0o755)
	time.Sleep(100 * time.Millisecond)
	os.WriteFile(filepath.Join(dir, "album", "late.flac"), []byte("y"), 0o644)
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("z"), 0o644)
	os.WriteFile(filepath.Join(dir, ".hidden.mp3"), []byte("z"), 0o644)

	waitFor(t, func() bool { return len(r.got()) == 2 })
	time.Sleep(300 * time.Millisecond)
	if got := r.got(); len(got) != 2 {
		t.Fatalf("ingested %v; want only the two audio files", got)
	}
}

func TestWaitsForAGrowingFileToSettle(t *testing.T) {
	dir := t.TempDir()
	r := &recorder{}
	start(t, dir, r)

	p := filepath.Join(dir, "copying.mp3")
	f, _ := os.Create(p)
	for i := 0; i < 8; i++ {
		f.Write([]byte("chunk"))
		time.Sleep(80 * time.Millisecond)
		if len(r.got()) != 0 {
			t.Fatal("ingested while still being written")
		}
	}
	f.Close()
	waitFor(t, func() bool { return len(r.got()) == 1 })
}

func TestFailuresMoveAside(t *testing.T) {
	dir := t.TempDir()
	r := &recorder{fail: true}
	start(t, dir, r)
	os.WriteFile(filepath.Join(dir, "bad.mp3"), []byte("x"), 0o644)
	waitFor(t, func() bool {
		_, err := os.Stat(filepath.Join(dir, FailedDir, "bad.mp3"))
		return err == nil
	})
	time.Sleep(400 * time.Millisecond)
	if n := len(r.got()); n != 1 {
		t.Fatalf("failed file retried %d times", n)
	}
}
