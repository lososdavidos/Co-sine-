package ytdlp

import (
	"context"
	"encoding/json"
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

	// From what the probe found, without asking again: the same bytes.
	again := filepath.Join(dir, "again")
	os.MkdirAll(again, 0o755)
	if len(info.raw) == 0 || len(info.Formats) == 0 {
		t.Fatalf("the probe kept nothing to download from: %d bytes, %d formats", len(info.raw), len(info.Formats))
	}
	got, err = r.DownloadInfo(context.Background(), info, again, nil)
	if err != nil {
		t.Fatal("download from the probe:", err)
	}
	if b, err := os.ReadFile(got); err != nil || string(a) != string(b) {
		t.Fatalf("downloaded file differs from the source (%v)", err)
	}
	if left, _ := os.ReadDir(again); len(left) != 1 {
		t.Fatalf("expected only the song in the folder, found %d files", len(left))
	}

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

// TestRealDownloadInfoChoosesTheAudio downloads from a probe's description
// of a YouTube-like video, its audio and its picture separate: the real
// yt-dlp chooses the formats again, the audio alone, not the probe's
// picture-and-sound choice. Skipped when yt-dlp is absent.
func TestRealDownloadInfoChoosesTheAudio(t *testing.T) {
	bin, err := exec.LookPath("yt-dlp")
	if err != nil {
		t.Skip("yt-dlp not installed")
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("bytes of " + r.URL.Path))
	}))
	defer srv.Close()
	raw := []byte(`{"id":"vid1","title":"Tides","extractor":"generic","extractor_key":"Generic","webpage_url":"` + srv.URL + `/watch",
	  "format_id":"137+251","formats":[
	    {"format_id":"251","url":"` + srv.URL + `/audio.webm","ext":"webm","acodec":"opus","vcodec":"none","abr":160},
	    {"format_id":"137","url":"` + srv.URL + `/video.mp4","ext":"mp4","acodec":"none","vcodec":"avc1","height":1080}],
	  "requested_formats":[{"format_id":"137","url":"` + srv.URL + `/video.mp4","ext":"mp4","acodec":"none","vcodec":"avc1"},
	    {"format_id":"251","url":"` + srv.URL + `/audio.webm","ext":"webm","acodec":"opus","vcodec":"none"}]}`)
	var info Info
	if err := json.Unmarshal(raw, &info); err != nil {
		t.Fatal(err)
	}
	info.raw = raw
	dir := t.TempDir()
	got, err := Runner{Bin: bin}.DownloadInfo(context.Background(), info, dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(got); string(b) != "bytes of /audio.webm" || filepath.Ext(got) != ".webm" {
		t.Fatalf("%s: %q", got, b)
	}
}
