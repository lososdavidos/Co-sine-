// Package ytdlp drives the yt-dlp binary: the one hard external dependency
// (§5.1). yt-dlp maintains the extractors; Cosine keeps no site whitelist (§3.1a).
package ytdlp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Runner invokes yt-dlp. The zero value is unusable; see Find.
type Runner struct {
	Bin string
	// ExtraArgs are prepended to every invocation (tests, cookies, proxies).
	ExtraArgs []string
	// TempDir is where yt-dlp keeps its temporary files, and the
	// single-file build unpacks itself. Empty: the system's, which on
	// Debian 13 is memory.
	TempDir string

	// slots bounds how many yt-dlp processes run at once, across every
	// copy of the Runner; nil: no bound.
	slots chan struct{}
}

// Find locates yt-dlp: COSINE_YTDLP, then PATH. It runs at most
// COSINE_YTDLP_PROCESSES at once, or as many as the memory allows.
func Find() (Runner, error) {
	if p := os.Getenv("COSINE_YTDLP"); p != "" {
		return Runner{Bin: p}.Bounded(processes()), nil
	}
	p, err := exec.LookPath("yt-dlp")
	if err != nil {
		return Runner{}, errors.New("yt-dlp is not installed")
	}
	return Runner{Bin: p}.Bounded(processes()), nil
}

// Bounded returns r running at most n yt-dlp processes at once, across
// every copy of it; the rest wait their turn.
func (r Runner) Bounded(n int) Runner {
	r.slots = make(chan struct{}, n)
	return r
}

// processes is how many yt-dlp processes may run at once: one for each
// 256 MB of memory, from 4 to 12. Each is a Python, for YouTube with a
// Deno beside it, and one search in Add starts ten: unbounded, a few
// searches can run a small container out of memory. 4 leaves a search room
// beside the two downloads.
func processes() int {
	if n, err := strconv.Atoi(os.Getenv("COSINE_YTDLP_PROCESSES")); err == nil && n > 0 {
		return n
	}
	n := 4
	if b, err := os.ReadFile("/proc/meminfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			var kb int
			if _, err := fmt.Sscanf(line, "MemTotal: %d kB", &kb); err == nil {
				n = kb >> 18
				break
			}
		}
	}
	return min(max(n, 4), 12)
}

// slot waits for a process slot; done frees it.
func (r Runner) slot(ctx context.Context) (done func(), err error) {
	if r.slots == nil {
		return func() {}, nil
	}
	select {
	case r.slots <- struct{}{}:
		return func() { <-r.slots }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (r Runner) Available() bool { return r.Bin != "" }

func (r Runner) Version(ctx context.Context) string {
	done, err := r.slot(ctx)
	if err != nil {
		return ""
	}
	defer done()
	out, err := r.command(ctx, "--version").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// Info is the subset of yt-dlp's JSON Cosine uses. For a collection (a
// SoundCloud set, a Bandcamp album, a channel) Type is "playlist" and
// Entries lists its items.
type Info struct {
	Type         string   `json:"_type"`
	ID           string   `json:"id"`
	Title        string   `json:"title"`
	Uploader     string   `json:"uploader"`
	Channel      string   `json:"channel"`
	Artist       string   `json:"artist"`
	Track        string   `json:"track"`
	Album        string   `json:"album"`
	AlbumArtist  string   `json:"album_artist"`
	AlbumArtists []string `json:"album_artists"`
	// AlbumType is what a SoundCloud set says it is: album, ep, single,
	// compilation, or playlist.
	AlbumType    string      `json:"album_type"`
	TrackNumber  int         `json:"track_number"`
	ReleaseYear  int         `json:"release_year"`
	Duration     float64     `json:"duration"`
	ViewCount    int64       `json:"view_count"`
	WebpageURL   string      `json:"webpage_url"`
	URL          string      `json:"url"`
	Thumbnail    string      `json:"thumbnail"`
	Thumbnails   []Thumbnail `json:"thumbnails"`
	ExtractorKey string      `json:"extractor_key"`
	IEKey        string      `json:"ie_key"`
	Entries      []Info      `json:"entries"`
	Formats      []struct {
		FormatID string `json:"format_id"`
	} `json:"formats"` // only when a single item is described in full

}

type Thumbnail struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

func (i Info) IsCollection() bool { return i.Type == "playlist" }

// Collection kinds.
const (
	KindAlbum    = "album"
	KindPlaylist = "playlist"
	KindChannel  = "channel"
)

// Kind says what a collection is. Only a release its artist put out is an
// album: a YouTube Music album, a Bandcamp album, a SoundCloud set marked
// as an album, EP or single. Anything someone put together — a YouTube
// playlist, a SoundCloud set marked as a playlist, a mix — is a playlist,
// however much it looks like an album, and an account's uploads are a
// channel. Unsure is a playlist: each song is then filed as itself.
func (i Info) Kind() string {
	switch key := strings.ToLower(i.Source()); {
	case strings.HasPrefix(i.ID, "OLAK5uy_"): // YouTube Music's own album playlists
		return KindAlbum
	case key == "bandcampalbum":
		return KindAlbum
	case key == "soundcloudset" || key == "soundcloudplaylist":
		switch strings.ToLower(i.AlbumType) {
		case "album", "ep", "single", "compilation":
			return KindAlbum
		}
		return KindPlaylist
	case key == "bandcampuser", strings.HasPrefix(key, "soundclouduser"), key == "soundcloudtrackstation", key == "soundcloudrelated":
		return KindChannel
	case key == "youtubetab" && (strings.HasPrefix(i.ID, "UC") || strings.HasPrefix(i.ID, "UU")):
		return KindChannel
	}
	return KindPlaylist
}

// albumPrefix is how YouTube Music titles a release's playlist.
var albumPrefix = []string{"Album - ", "EP - ", "Single - "}

// CollectionTitle is a collection's name as people know it.
func (i Info) CollectionTitle() string {
	t := strings.TrimSpace(i.Title)
	if strings.HasPrefix(i.ID, "OLAK5uy_") {
		for _, p := range albumPrefix {
			t = strings.TrimPrefix(t, p)
		}
	}
	return t
}

// CollectionArtist is who released an album, when the source says.
func (i Info) CollectionArtist() string {
	if i.AlbumArtist != "" {
		return i.AlbumArtist
	}
	if len(i.AlbumArtists) > 0 {
		return strings.Join(i.AlbumArtists, ", ")
	}
	return i.Poster()
}

// Link is the canonical page URL for an item, which is what Q12 compares.
func (i Info) Link() string {
	if i.WebpageURL != "" {
		return i.WebpageURL
	}
	return i.URL
}

// PreviewOnly reports a track the source only lets yt-dlp have a preview
// of: SoundCloud serves 30 seconds of Go+ tracks, which much popular music
// on it is. yt-dlp labels every such format "preview".
func (i Info) PreviewOnly() bool {
	if len(i.Formats) == 0 {
		return false
	}
	for _, f := range i.Formats {
		if !strings.Contains(f.FormatID, "preview") {
			return false
		}
	}
	return true
}

// Poster is who uploaded it: the fallback artist.
func (i Info) Poster() string {
	if i.Uploader != "" {
		return i.Uploader
	}
	return i.Channel
}

func (i Info) Source() string {
	if i.ExtractorKey != "" {
		return i.ExtractorKey
	}
	return i.IEKey
}

// CoverURL picks the largest JPEG or PNG thumbnail: WebP covers are not
// universally supported by Subsonic clients.
func (i Info) CoverURL() string {
	best, bestArea := "", -1
	for _, t := range i.Thumbnails {
		u := strings.ToLower(strings.SplitN(t.URL, "?", 2)[0])
		if !(strings.HasSuffix(u, ".jpg") || strings.HasSuffix(u, ".jpeg") || strings.HasSuffix(u, ".png")) {
			continue
		}
		if a := t.Width * t.Height; a >= bestArea {
			best, bestArea = t.URL, a
		}
	}
	if best == "" {
		return i.Thumbnail
	}
	return best
}

// killAfter is how long yt-dlp has to stop, once asked, before it is killed.
var killAfter = 5 * time.Second

func (r Runner) command(ctx context.Context, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, r.Bin, append(append([]string{}, r.ExtraArgs...), args...)...)
	cmd.Env = append(os.Environ(), "PYTHONUNBUFFERED=1")
	if r.TempDir != "" {
		cmd.Env = append(cmd.Env, "TMPDIR="+r.TempDir)
	}
	// yt-dlp is several processes: the standalone build is a launcher that
	// unpacks Python and runs it, and Python runs Deno and ffmpeg. Killing
	// the launcher alone, as CommandContext does, leaves the rest running
	// and the unpacked copy behind. So they get a process group of their
	// own and are stopped together: asked first, which lets the launcher
	// tidy up, then killed if still there killAfter later.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		group := -cmd.Process.Pid
		time.AfterFunc(killAfter, func() { syscall.Kill(group, syscall.SIGKILL) })
		err := syscall.Kill(group, syscall.SIGTERM)
		if errors.Is(err, syscall.ESRCH) {
			return os.ErrProcessDone
		}
		return err
	}
	cmd.WaitDelay = killAfter
	return cmd
}

// Probe describes a URL without downloading. A collection's entries are
// listed flat (fast even for a channel of thousands). Short and share links
// are resolved here, on the server (§6.10).
func (r Runner) Probe(ctx context.Context, url string) (Info, error) {
	return r.json(ctx, "--dump-single-json", "--flat-playlist", "--no-playlist", "--no-warnings", "--", url)
}

// SearchURL lists the first n results of a site's own search page, such as
// YouTube Music's, without describing each in full.
func (r Runner) SearchURL(ctx context.Context, url string, n int) ([]Info, error) {
	info, err := r.json(ctx, "--dump-single-json", "--flat-playlist", "--playlist-end", strconv.Itoa(n), "--no-warnings", "--", url)
	return info.Entries, err
}

// Search runs a yt-dlp search, e.g. prefix "scsearch" or "ytsearch".
func (r Runner) Search(ctx context.Context, prefix, query string, n int) ([]Info, error) {
	info, err := r.json(ctx, "--dump-single-json", "--flat-playlist", "--no-warnings", "--",
		fmt.Sprintf("%s%d:%s", prefix, n, query))
	return info.Entries, err
}

func (r Runner) json(ctx context.Context, args ...string) (Info, error) {
	done, err := r.slot(ctx)
	if err != nil {
		return Info{}, err
	}
	defer done()
	var stdout, stderr bytes.Buffer
	cmd := r.command(ctx, args...)
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return Info{}, failure(err, stderr.String())
	}
	var info Info
	if err := json.Unmarshal(stdout.Bytes(), &info); err != nil {
		return Info{}, fmt.Errorf("unreadable yt-dlp output: %w", err)
	}
	return info, nil
}

const (
	progressTag = "COSINE-PROGRESS "
	fileTag     = "COSINE-FILE "
)

// Download fetches the best audio stream into dir and returns the file's
// path. The stream is stored exactly as received: no extraction, no
// re-encode, no remux, no fixups (§3.1a) — the hash is the file the source
// actually sent.
//
// The exception is HLS (all SoundCloud offers now), where the source sends
// no file, only ~10 s segments. Joined natively they make a fragmented MP4
// with a zero duration and a sidx per segment, which players read as 10 s
// long. ffmpeg joins them into one plain MP4 instead, copying the audio.
func (r Runner) Download(ctx context.Context, url, dir string, progress func(float64)) (string, error) {
	done, err := r.slot(ctx)
	if err != nil {
		return "", err
	}
	defer done()
	cmd := r.command(ctx,
		"--format", "bestaudio/best",
		"--no-playlist",
		"--fixup", "never",
		"--downloader", "m3u8:ffmpeg",
		"--no-mtime",
		"--no-warnings",
		"--newline",
		"--progress",
		"--progress-template", "download:"+progressTag+"%(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s",
		"--print", "after_move:"+fileTag+"%(filepath)s",
		"--output", filepath.Join(dir, "%(id)s.%(ext)s"),
		"--", url)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return "", err
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return "", err
	}
	var file string
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, fileTag):
			file = strings.TrimPrefix(line, fileTag)
		case strings.HasPrefix(line, progressTag) && progress != nil:
			if f, ok := parseProgress(strings.TrimPrefix(line, progressTag)); ok {
				progress(f)
			}
		}
	}
	if err := cmd.Wait(); err != nil {
		return "", failure(err, stderr.String())
	}
	if file == "" {
		return "", errors.New("yt-dlp finished without reporting a file")
	}
	return file, nil
}

// parseProgress reads "downloaded total estimate"; either total may be "NA".
func parseProgress(s string) (float64, bool) {
	f := strings.Fields(s)
	if len(f) != 3 {
		return 0, false
	}
	done, err := strconv.ParseFloat(f[0], 64)
	if err != nil {
		return 0, false
	}
	total, err := strconv.ParseFloat(f[1], 64)
	if err != nil || total <= 0 {
		if total, err = strconv.ParseFloat(f[2], 64); err != nil || total <= 0 {
			return 0, false
		}
	}
	p := done / total
	if p > 1 {
		p = 1
	}
	return p, true
}

// failure turns a yt-dlp exit into its own last ERROR line, which is what the
// user sees: "Couldn't fetch — <reason from yt-dlp>" (§6.17).
func failure(err error, stderr string) error {
	var last string
	for _, line := range strings.Split(stderr, "\n") {
		if l := strings.TrimSpace(line); strings.HasPrefix(l, "ERROR:") {
			last = strings.TrimSpace(strings.TrimPrefix(l, "ERROR:"))
		}
	}
	if last == "" {
		return fmt.Errorf("yt-dlp failed: %v", err)
	}
	// Drop yt-dlp's bug-report boilerplate and exception chains: the user
	// sees the reason, not a traceback summary.
	for _, cut := range []string{"; please report this issue", " (caused by ", "; please report"} {
		if i := strings.Index(last, cut); i > 0 {
			last = last[:i]
		}
	}
	// Drop yt-dlp's "[extractor] id: " prefix; keep the reason.
	if i := strings.Index(last, "]"); strings.HasPrefix(last, "[") && i > 0 {
		last = strings.TrimSpace(last[i+1:])
		if j := strings.Index(last, ": "); j > 0 && !strings.Contains(last[:j], " ") {
			last = last[j+2:]
		}
	}
	return errors.New(last)
}
