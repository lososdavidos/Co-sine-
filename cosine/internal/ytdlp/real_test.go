package ytdlp

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

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
