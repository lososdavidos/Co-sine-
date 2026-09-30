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
)

// Runner invokes yt-dlp. The zero value is unusable; see Find.
type Runner struct {
	Bin string
	// ExtraArgs are prepended to every invocation (tests, cookies, proxies).
	ExtraArgs []string
}

// Find locates yt-dlp: COSINE_YTDLP, then PATH.
func Find() (Runner, error) {
	if p := os.Getenv("COSINE_YTDLP"); p != "" {
		return Runner{Bin: p}, nil
	}
	p, err := exec.LookPath("yt-dlp")
	if err != nil {
		return Runner{}, errors.New("yt-dlp is not installed")
	}
	return Runner{Bin: p}, nil
}

func (r Runner) Available() bool { return r.Bin != "" }

func (r Runner) Version(ctx context.Context) string {
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
	Type         string      `json:"_type"`
	ID           string      `json:"id"`
	Title        string      `json:"title"`
	Uploader     string      `json:"uploader"`
	Channel      string      `json:"channel"`
	Artist       string      `json:"artist"`
	Track        string      `json:"track"`
	Album        string      `json:"album"`
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
}

type Thumbnail struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

func (i Info) IsCollection() bool { return i.Type == "playlist" }

// Link is the canonical page URL for an item, which is what Q12 compares.
func (i Info) Link() string {
	if i.WebpageURL != "" {
		return i.WebpageURL
	}
	return i.URL
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

func (r Runner) command(ctx context.Context, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, r.Bin, append(append([]string{}, r.ExtraArgs...), args...)...)
	cmd.Env = append(os.Environ(), "PYTHONUNBUFFERED=1")
	return cmd
}

// Probe describes a URL without downloading. A collection's entries are
// listed flat (fast even for a channel of thousands). Short and share links
// are resolved here, on the server (§6.10).
func (r Runner) Probe(ctx context.Context, url string) (Info, error) {
	return r.json(ctx, "--dump-single-json", "--flat-playlist", "--no-playlist", "--no-warnings", "--", url)
}

// Search runs a yt-dlp search, e.g. prefix "scsearch" or "ytsearch".
func (r Runner) Search(ctx context.Context, prefix, query string, n int) ([]Info, error) {
	info, err := r.json(ctx, "--dump-single-json", "--flat-playlist", "--no-warnings", "--",
		fmt.Sprintf("%s%d:%s", prefix, n, query))
	return info.Entries, err
}

func (r Runner) json(ctx context.Context, args ...string) (Info, error) {
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
func (r Runner) Download(ctx context.Context, url, dir string, progress func(float64)) (string, error) {
	cmd := r.command(ctx,
		"--format", "bestaudio/best",
		"--no-playlist",
		"--fixup", "never",
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
