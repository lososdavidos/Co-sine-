// Package search finds things to ingest (§3.1a). Two backends, chosen in
// the dashboard: the source sites' own APIs (richer results, need keys) or
// yt-dlp's search (no keys). Either way the result is a URL, and ingest from
// there is identical. Search degrades, never fails (Q73).
package search

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Result is one candidate. Plays and duration let the picker show what
// you're choosing between.
type Result struct {
	Title       string `json:"title"`
	Uploader    string `json:"uploader"`
	URL         string `json:"url"`
	Thumbnail   string `json:"thumbnail,omitempty"`
	Source      string `json:"source"` // "soundcloud", "youtube"
	DurationSec int    `json:"durationSec"`
	Plays       int64  `json:"plays"`
}

type Backend interface {
	Search(ctx context.Context, query string, n int) ([]Result, error)
}

const (
	BackendYtDlp = "ytdlp"
	BackendAPI   = "api"
)

// Service picks the configured backend and falls back to yt-dlp search
// silently, recording why so the dashboard can say which is actually running.
type Service struct {
	DB    *db.DB
	YtDlp Backend
	// NewAPI builds the source-API backend from the stored keys; nil if none are set.
	NewAPI func(soundCloudID, youTubeKey string) Backend

	mu     sync.Mutex
	status string
}

// Search returns results and the name of the backend that produced them.
func (s *Service) Search(ctx context.Context, query string, n int) ([]Result, string, error) {
	want, _ := s.DB.Setting(ctx, db.SettingSearchBackend)
	if want == BackendAPI {
		sc, _ := s.DB.Setting(ctx, db.SettingSoundCloudID)
		yt, _ := s.DB.Setting(ctx, db.SettingYouTubeKey)
		switch api := s.NewAPI(sc, yt); {
		case api == nil:
			s.setStatus("Source APIs selected but no keys are set; using yt-dlp search.")
		default:
			res, err := api.Search(ctx, query, n)
			if err == nil {
				s.setStatus("Using the source APIs.")
				return res, BackendAPI, nil
			}
			s.setStatus(fmt.Sprintf("Source APIs failed (%v); using yt-dlp search.", err))
		}
	} else {
		s.setStatus("Using yt-dlp search.")
	}
	if s.YtDlp == nil {
		return nil, "", errors.New("yt-dlp is not installed")
	}
	res, err := s.YtDlp.Search(ctx, query, n)
	return res, BackendYtDlp, err
}

// Status says which backend the last search actually used, and why.
func (s *Service) Status() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.status == "" {
		return "No searches yet."
	}
	return s.status
}

func (s *Service) setStatus(v string) {
	s.mu.Lock()
	s.status = v
	s.mu.Unlock()
}

// YtDlpSearch queries SoundCloud and YouTube through yt-dlp at once and
// interleaves the answers. SoundCloud first: much of this library lives there.
type YtDlpSearch struct{ Runner ytdlp.Runner }

func (y YtDlpSearch) Search(ctx context.Context, query string, n int) ([]Result, error) {
	type part struct {
		res []Result
		err error
	}
	run := func(prefix, source string) part {
		entries, err := y.Runner.Search(ctx, prefix, query, n)
		out := make([]Result, 0, len(entries))
		for _, e := range entries {
			out = append(out, Result{
				Title: e.Title, Uploader: e.Poster(), URL: e.Link(), Thumbnail: e.CoverURL(),
				Source: source, DurationSec: int(e.Duration + 0.5), Plays: e.ViewCount,
			})
		}
		return part{out, err}
	}
	var sc, yt part
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); sc = run("scsearch", "soundcloud") }()
	go func() { defer wg.Done(); yt = run("ytsearch", "youtube") }()
	wg.Wait()
	if sc.err != nil && yt.err != nil {
		return nil, sc.err
	}
	return interleave(sc.res, yt.res), nil
}

func interleave(a, b []Result) []Result {
	out := make([]Result, 0, len(a)+len(b))
	for i := 0; i < len(a) || i < len(b); i++ {
		if i < len(a) {
			out = append(out, a[i])
		}
		if i < len(b) {
			out = append(out, b[i])
		}
	}
	return out
}

// LooksLikeURL decides whether Add's single field holds a link or a query
// (§6.10). Share sheets send text like "Listen to X on SoundCloud
// https://on.soundcloud.com/abc", so the first link in the text wins.
func LooksLikeURL(input string) (string, bool) {
	for _, f := range strings.Fields(input) {
		f = strings.Trim(f, "<>()[]\"'")
		lower := strings.ToLower(f)
		if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") {
			return f, true
		}
	}
	t := strings.TrimSpace(input)
	// A bare "soundcloud.com/artist/track": one token, a dot before a slash.
	if !strings.ContainsAny(t, " \t\n") {
		if dot, slash := strings.Index(t, "."), strings.Index(t, "/"); dot > 0 && slash > dot {
			return "https://" + t, true
		}
	}
	return "", false
}
