package ytdlp

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
)

// TestRealYtDlp runs the real binary against a local file:// URL, which
// checks that every flag Cosine passes is accepted by the installed yt-dlp
// and that downloads arrive byte-for-byte. Skipped when yt-dlp is absent.
func TestRealYtDlp(t *testing.T) {
	bin, err := exec.LookPath("yt-dlp")
	if err != nil {
		t.Skip("yt-dlp not installed")
	}
	r := Runner{Bin: bin, ExtraArgs: []string{"--enable-file-urls"}}
	dir := t.TempDir()
	src := testutil.MP3{Title: "Tides", Artist: "Skeler", Body: string(make([]byte, 200_000))}.
		Write(t, filepath.Join(dir, "source.mp3"))
	url := "file://" + src

	info, err := r.Probe(context.Background(), url)
	if err != nil {
		t.Fatal("probe:", err)
	}
	if info.IsCollection() || info.Link() == "" {
		t.Fatalf("probe: %+v", info)
	}

	out := filepath.Join(dir, "out")
	os.MkdirAll(out, 0o755)
	var calls int
	got, err := r.Download(context.Background(), url, out, func(p float64) { calls++ })
	if err != nil {
		t.Fatal("download:", err)
	}
	a, _ := os.ReadFile(src)
	b, err := os.ReadFile(got)
	if err != nil || string(a) != string(b) {
		t.Fatalf("downloaded file differs from the source (%v)", err)
	}
	t.Logf("file %s, %d progress callbacks", filepath.Base(got), calls)

	if _, err := r.Probe(context.Background(), "file://"+filepath.Join(dir, "nope.mp3")); err == nil {
		t.Fatal("expected an error for a missing file")
	} else {
		t.Logf("error text: %v", err)
	}
}

// TestRealYtDlpStopsCleanly stops the real binary part way through: none
// of it is left running, and nothing it unpacked is left behind. The
// standalone build leaves an 83 MB copy of itself when only its launcher
// is killed.
func TestRealYtDlpStopsCleanly(t *testing.T) {
	bin, err := exec.LookPath("yt-dlp")
	if err != nil {
		t.Skip("yt-dlp not installed")
	}
	asked, gone := make(chan struct{}), make(chan struct{})
	never := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(asked)
		<-r.Context().Done() // yt-dlp hung up
		close(gone)
	}))
	defer never.Close()
	tmp := t.TempDir()
	r := Runner{Bin: bin, TempDir: tmp}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error)
	go func() {
		_, err := r.Probe(ctx, never.URL+"/song.mp3")
		done <- err
	}()
	select {
	case <-asked:
	case err := <-done:
		t.Fatal("yt-dlp never asked:", err)
	case <-time.After(30 * time.Second):
		t.Fatal("yt-dlp never asked")
	}
	cancel()
	select {
	case <-gone:
	case <-time.After(10 * time.Second):
		t.Fatal("yt-dlp kept running")
	}
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("still waiting on a stopped yt-dlp")
	}
	if left, _ := os.ReadDir(tmp); len(left) != 0 {
		t.Fatal("left behind:", left)
	}
}
