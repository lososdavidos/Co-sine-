package ytdlp

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// fake writes a shell script standing in for yt-dlp.
func fake(t *testing.T, script string) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "yt-dlp")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\n"+script), 0o755); err != nil {
		t.Fatal(err)
	}
	return bin
}

func TestProcessesAreBounded(t *testing.T) {
	running, log := t.TempDir(), filepath.Join(t.TempDir(), "log")
	t.Setenv("RUNNING", running)
	t.Setenv("LOG", log)
	// Each counts the processes running beside it, itself included.
	r := Runner{Bin: fake(t, `touch "$RUNNING/$$"; ls "$RUNNING" | wc -l >> "$LOG"; sleep 0.2; rm "$RUNNING/$$"
echo '{"id": "x"}'`)}.Bounded(2)
	var wg sync.WaitGroup
	for range 6 {
		wg.Go(func() {
			if _, err := r.Probe(context.Background(), "https://example.com"); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	b, _ := os.ReadFile(log)
	counts := strings.Fields(string(b))
	if len(counts) != 6 {
		t.Fatal(counts)
	}
	for _, c := range counts {
		if n, _ := strconv.Atoi(c); n > 2 {
			t.Fatal("more than 2 at once:", counts)
		}
	}

	// One waiting for its turn gives up with its caller.
	r = Runner{Bin: fake(t, "sleep 5")}.Bounded(1)
	first, stop := context.WithCancel(context.Background())
	wg.Go(func() { r.Probe(first, "https://example.com") })
	defer wg.Wait()
	defer stop()
	time.Sleep(100 * time.Millisecond)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, err := r.Probe(ctx, "https://example.com"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}

// alive reports whether pid is running: not gone, and not a zombie.
func alive(pid int) bool {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return false
	}
	f := strings.Fields(string(b[strings.LastIndexByte(string(b), ')')+1:]))
	return len(f) > 0 && f[0] != "Z"
}

func TestCancellingStopsEveryProcess(t *testing.T) {
	if _, err := os.Stat("/proc/self/stat"); err != nil {
		t.Skip("needs /proc")
	}
	defer func(d time.Duration) { killAfter = d }(killAfter)
	killAfter = 300 * time.Millisecond
	for name, script := range map[string]string{
		// As the standalone yt-dlp does: a launcher running the real thing.
		"stops when asked": `sleep 60 & echo $! > "$CHILD"; echo "$TMPDIR" > "$CHILD.tmp"; wait`,
		// Deaf to being asked: killed.
		"ignores it": `trap '' TERM; sleep 60 & echo $! > "$CHILD"; wait`,
	} {
		t.Run(name, func(t *testing.T) {
			child := filepath.Join(t.TempDir(), "child")
			t.Setenv("CHILD", child)
			r := Runner{Bin: fake(t, script), TempDir: "/srv/tmp"}
			ctx, cancel := context.WithCancel(context.Background())
			done := make(chan error)
			go func() {
				_, err := r.Probe(ctx, "https://example.com")
				done <- err
			}()
			var pid int
			for deadline := time.Now().Add(5 * time.Second); pid == 0 && time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
				b, _ := os.ReadFile(child)
				pid, _ = strconv.Atoi(strings.TrimSpace(string(b)))
			}
			if !alive(pid) {
				t.Fatal("the fake didn't start its child", pid)
			}
			cancel()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("a stopped run is not a result")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("still waiting on a stopped yt-dlp")
			}
			for deadline := time.Now().Add(2 * time.Second); alive(pid) && time.Now().Before(deadline); {
				time.Sleep(10 * time.Millisecond)
			}
			if alive(pid) {
				t.Fatal("yt-dlp's child outlived it")
			}
			if b, err := os.ReadFile(child + ".tmp"); err == nil && strings.TrimSpace(string(b)) != "/srv/tmp" {
				t.Fatal("TMPDIR:", string(b))
			}
		})
	}
}

func TestParseProgress(t *testing.T) {
	cases := map[string]float64{"50 100 NA": 0.5, "30 NA 60": 0.5, "10 NA NA": -1, "200 100 NA": 1, "garbage": -1}
	for in, want := range cases {
		got, ok := parseProgress(in)
		if want < 0 {
			if ok {
				t.Errorf("%q: expected no value", in)
			}
			continue
		}
		if !ok || got != want {
			t.Errorf("%q = %v", in, got)
		}
	}
}

func TestFailureKeepsTheReason(t *testing.T) {
	stderr := "WARNING: something\nERROR: [soundcloud] 12345: Unable to download JSON metadata: HTTP Error 404: Not Found\n"
	if got := failure(errors.New("exit 1"), stderr).Error(); got != "Unable to download JSON metadata: HTTP Error 404: Not Found" {
		t.Fatal(got)
	}
	if got := failure(errors.New("exit 1"), "ERROR: Unsupported URL: https://x\n").Error(); got != "Unsupported URL: https://x" {
		t.Fatal(got)
	}
	long := "ERROR: [soundcloud:search] x: Unable to download webpage: ('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden')) (caused by ProxyError(\"x\")); please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template."
	if got := failure(errors.New("exit 1"), long).Error(); got != "Unable to download webpage: ('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))" {
		t.Fatal(got)
	}
}

func TestCoverURLPrefersLargestJPEG(t *testing.T) {
	i := Info{Thumbnail: "fallback.webp", Thumbnails: []Thumbnail{
		{URL: "a.jpg", Width: 100, Height: 100},
		{URL: "b.webp", Width: 1000, Height: 1000},
		{URL: "c.jpg?x=1", Width: 500, Height: 500},
	}}
	if got := i.CoverURL(); got != "c.jpg?x=1" {
		t.Fatal(got)
	}
	if got := (Info{Thumbnail: "t.jpg"}).CoverURL(); got != "t.jpg" {
		t.Fatal(got)
	}
}

func TestOnlyAReleaseIsAnAlbum(t *testing.T) {
	cases := []struct {
		info Info
		want string
	}{
		{Info{ExtractorKey: "YoutubeTab", ID: "OLAK5uy_l1m0thk3g31NmIIz_vMIbWtyv7eZixlH0"}, KindAlbum},
		{Info{ExtractorKey: "BandcampAlbum", ID: "we-are-the-plague"}, KindAlbum},
		{Info{ExtractorKey: "SoundcloudSet", AlbumType: "ep"}, KindAlbum},
		{Info{ExtractorKey: "SoundcloudSet", AlbumType: "album"}, KindAlbum},
		// Put together by someone, however album-like its name.
		{Info{ExtractorKey: "SoundcloudSet", AlbumType: "playlist", Title: "Halsey - hopeless fountain kingdom"}, KindPlaylist},
		{Info{ExtractorKey: "SoundcloudSet"}, KindPlaylist},
		{Info{ExtractorKey: "YoutubeTab", ID: "PLXkYU9X6yyt3_6dTtVXg7VwuGJwpn8sI6", Title: "Halsey - hopeless fountain kingdom (Full Album)"}, KindPlaylist},
		{Info{ExtractorKey: "Mixcloud", ID: "x"}, KindPlaylist},
		{Info{ExtractorKey: "YoutubeTab", ID: "UCm3FgJ2Hqm7tb70T-GfwXVA"}, KindChannel},
		{Info{ExtractorKey: "SoundcloudUser", ID: "1"}, KindChannel},
		{Info{ExtractorKey: "BandcampUser", ID: "x"}, KindChannel},
	}
	for _, c := range cases {
		if got := c.info.Kind(); got != c.want {
			t.Errorf("%+v: %s, want %s", c.info, got, c.want)
		}
	}
	album := Info{ID: "OLAK5uy_x", Title: "Album - hopeless fountain kingdom (Deluxe)", Channel: "Halsey"}
	if album.CollectionTitle() != "hopeless fountain kingdom (Deluxe)" || album.CollectionArtist() != "Halsey" {
		t.Fatal(album.CollectionTitle(), album.CollectionArtist())
	}
	set := Info{ID: "1", Title: "Album - a playlist named so", AlbumArtists: []string{"Levi Ryan"}, Uploader: "a fan"}
	if set.CollectionTitle() != "Album - a playlist named so" || set.CollectionArtist() != "Levi Ryan" {
		t.Fatal(set.CollectionTitle(), set.CollectionArtist())
	}
}
