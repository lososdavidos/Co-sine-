// Package fetch is URL ingest (§3.1): a persistent queue of links, worked by
// one or two yt-dlp downloads at a time (Q15), each finished file handed to
// the one ingest path. The queue is visible state (G4, Q13).
package fetch

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Job statuses. "known" is Q12's refusal: a URL already in the library.
const (
	StatusQueued    = "queued"
	StatusRunning   = "running"
	StatusDone      = "done"
	StatusDuplicate = "duplicate"
	StatusExpanded  = "expanded"
	StatusKnown     = "known"
	StatusFailed    = "failed"
)

const maxBatch = 1000

type Job struct {
	ID        int64   `json:"id"`
	URL       string  `json:"url"`
	Title     string  `json:"title"`
	Status    string  `json:"status"`
	Progress  float64 `json:"progress"`
	Error     string  `json:"error,omitempty"`
	TrackID   string  `json:"trackId,omitempty"` // Subsonic id, e.g. "tr-12"
	CreatedAt int64   `json:"createdAt"`
	UpdatedAt int64   `json:"updatedAt"`
}

type Fetcher struct {
	DB       *db.DB
	Ingester *ingest.Ingester
	Runner   ytdlp.Runner
	Search   *search.Service
	WorkDir  string
	Workers  int
	HTTP     *http.Client
	Log      *slog.Logger

	wake chan struct{}
}

func (f *Fetcher) init() {
	if f.wake == nil {
		f.wake = make(chan struct{}, 8)
	}
}

func (f *Fetcher) nudge() {
	f.init()
	select {
	case f.wake <- struct{}{}:
	default:
	}
}

// Submit queues URLs for a user. force skips Q12's "you already have this".
func (f *Fetcher) Submit(ctx context.Context, userID int64, urls []string, force bool) ([]Job, error) {
	if len(urls) == 0 {
		return nil, errors.New("no URLs given")
	}
	if len(urls) > maxBatch {
		return nil, fmt.Errorf("at most %d URLs at once", maxBatch)
	}
	var clean []string
	for _, raw := range urls {
		u, ok := search.LooksLikeURL(raw)
		if !ok {
			return nil, fmt.Errorf("not a link: %q", raw)
		}
		clean = append(clean, u)
	}
	ids, err := f.insert(ctx, userID, clean, force)
	if err != nil {
		return nil, err
	}
	for range ids {
		f.nudge()
	}
	return f.jobs(ctx, "WHERE j.id IN ("+placeholders(len(ids))+")", toAny(ids)...)
}

func (f *Fetcher) insert(ctx context.Context, userID int64, urls []string, force bool) ([]int64, error) {
	tx, err := f.DB.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	now := db.Now()
	var ids []int64
	seen := map[string]bool{}
	for _, u := range urls {
		if seen[u] {
			continue
		}
		seen[u] = true
		res, err := tx.ExecContext(ctx, `
			INSERT INTO ingest_jobs(source, input, status, user_id, url, title, force, created_at, updated_at)
			VALUES('url', ?, 'queued', ?, ?, ?, ?, ?, ?)`, u, userID, u, u, force, now, now)
		if err != nil {
			return nil, err
		}
		id, _ := res.LastInsertId()
		ids = append(ids, id)
	}
	return ids, tx.Commit()
}

// Retry requeues a failed or refused job. force=true is "add it anyway" after Q12.
func (f *Fetcher) Retry(ctx context.Context, userID, jobID int64, force bool) (Job, error) {
	res, err := f.DB.ExecContext(ctx, `
		UPDATE ingest_jobs SET status = 'queued', error = NULL, progress = 0, force = ?, updated_at = ?
		WHERE id = ? AND user_id = ? AND url IS NOT NULL AND status IN ('failed', 'known')`,
		force, db.Now(), jobID, userID)
	if err != nil {
		return Job{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return Job{}, errors.New("only failed or refused jobs can be retried")
	}
	f.nudge()
	jobs, err := f.jobs(ctx, "WHERE j.id = ?", jobID)
	if err != nil || len(jobs) == 0 {
		return Job{}, err
	}
	return jobs[0], nil
}

// Jobs returns a user's most recent URL jobs, newest first.
func (f *Fetcher) Jobs(ctx context.Context, userID int64, limit int) ([]Job, error) {
	return f.jobs(ctx, "WHERE j.user_id = ? AND j.url IS NOT NULL ORDER BY j.id DESC LIMIT ?", userID, limit)
}

func (f *Fetcher) jobs(ctx context.Context, where string, args ...any) ([]Job, error) {
	rows, err := f.DB.QueryContext(ctx, `
		SELECT j.id, COALESCE(j.url, j.input), COALESCE(j.title, j.input), j.status, j.progress,
		       COALESCE(j.error, ''), COALESCE(j.track_id, 0), j.created_at, j.updated_at
		FROM ingest_jobs j `+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Job
	for rows.Next() {
		var j Job
		var track int64
		if err := rows.Scan(&j.ID, &j.URL, &j.Title, &j.Status, &j.Progress, &j.Error, &track, &j.CreatedAt, &j.UpdatedAt); err != nil {
			return nil, err
		}
		if track != 0 {
			j.TrackID = "tr-" + strconv.FormatInt(track, 10)
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

// Run works the queue until ctx is cancelled. Jobs interrupted by a restart
// are picked up again.
func (f *Fetcher) Run(ctx context.Context) {
	f.init()
	if _, err := f.DB.ExecContext(ctx,
		"UPDATE ingest_jobs SET status = 'queued', progress = 0 WHERE status = 'running' AND url IS NOT NULL"); err != nil {
		f.Log.Error("requeueing interrupted jobs", "err", err)
	}
	workers := f.Workers
	if workers < 1 {
		workers = 1
	}
	done := make(chan struct{})
	for i := 0; i < workers; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			f.work(ctx)
		}()
	}
	for i := 0; i < workers; i++ {
		<-done
	}
}

type claimed struct {
	id    int64
	user  int64
	url   string
	force bool
}

func (f *Fetcher) work(ctx context.Context) {
	for {
		job, err := f.claim(ctx)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			f.Log.Error("claiming a job", "err", err)
		}
		if err == nil {
			f.process(ctx, job)
			continue
		}
		select {
		case <-ctx.Done():
			return
		case <-f.wake:
		case <-time.After(30 * time.Second):
		}
	}
}

func (f *Fetcher) claim(ctx context.Context) (claimed, error) {
	var c claimed
	err := f.DB.QueryRowContext(ctx, `
		UPDATE ingest_jobs SET status = 'running', updated_at = ?
		WHERE id = (SELECT id FROM ingest_jobs WHERE status = 'queued' AND url IS NOT NULL ORDER BY id LIMIT 1)
		RETURNING id, user_id, url, force`, db.Now()).Scan(&c.id, &c.user, &c.url, &c.force)
	return c, err
}

func (f *Fetcher) process(parent context.Context, job claimed) {
	ctx, cancel := context.WithTimeout(parent, 2*time.Hour) // a long DJ mix is allowed to take a while
	defer cancel()
	err := f.fetch(ctx, job)
	if err != nil {
		if parent.Err() != nil {
			return // shutting down: the job is requeued on next start
		}
		f.Log.Warn("fetch failed", "url", job.url, "err", err)
		f.set(job.id, "status = 'failed', error = ?, progress = 0", err.Error())
	}
}

func (f *Fetcher) fetch(ctx context.Context, job claimed) error {
	info, err := f.Runner.Probe(ctx, job.url)
	if err != nil {
		return err
	}

	if info.IsCollection() {
		// Queue everything (§3.1a): each item becomes its own job. "Review
		// first" never gets here — the client submits the items it picked.
		var urls []string
		for _, e := range info.Entries {
			if l := e.Link(); l != "" {
				urls = append(urls, l)
			}
		}
		ids, err := f.insert(ctx, job.user, urls, job.force)
		if err != nil {
			return err
		}
		for range ids {
			f.nudge()
		}
		f.set(job.id, "status = 'expanded', title = ?, progress = 1",
			fmt.Sprintf("%s (%d tracks queued)", firstNonEmpty(info.Title, job.url), len(ids)))
		return nil
	}

	link := info.Link()
	f.set(job.id, "title = ?", firstNonEmpty(info.Title, job.url))

	// Q12: a URL fetched before is recognised before anything downloads.
	if !job.force {
		track, has, err := f.known(ctx, job.user, link)
		if err != nil {
			return err
		}
		switch {
		case track != 0 && has:
			f.set(job.id, "status = 'known', error = ?, track_id = ?, progress = 0",
				"Already in your library.", track)
			return nil
		case track != 0:
			// Someone else fetched it: adding it is a Pointer, not a download.
			if err := f.Ingester.Undismiss(ctx, track, []int64{job.user}); err != nil {
				return err
			}
			if err := f.Ingester.GrantPointers(ctx, track, []int64{job.user}); err != nil {
				return err
			}
			f.set(job.id, "status = 'duplicate', track_id = ?, progress = 1", track)
			return nil
		}
	}

	dir := filepath.Join(f.WorkDir, "job-"+strconv.FormatInt(job.id, 10))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	defer os.RemoveAll(dir)

	var last time.Time
	path, err := f.Runner.Download(ctx, link, dir, func(p float64) {
		if time.Since(last) > 500*time.Millisecond {
			last = time.Now()
			f.set(job.id, "progress = ?", p*0.95) // the last 5% is filing
		}
	})
	if err != nil {
		return err
	}

	art, artExt := f.artwork(ctx, info.CoverURL())
	_, err = f.Ingester.Ingest(ctx, ingest.Request{
		Path:         path,
		OriginalName: store.Sanitize(firstNonEmpty(info.Title, info.ID)) + filepath.Ext(path),
		Source:       "url",
		SourceURL:    link,
		ForUsers:     []int64{job.user},
		JobID:        job.id,
		Explicit:     true,
		DurationSec:  int(info.Duration + 0.5),
		Artwork:      art,
		ArtworkExt:   artExt,
		SourceInfo: &resolve.SourceInfo{
			Title: info.Title, Uploader: info.Poster(),
			Artist: info.Artist, Track: info.Track, Album: info.Album,
			TrackNo: info.TrackNumber, Year: info.ReleaseYear,
		},
	})
	if errors.Is(err, ingest.ErrNotAudio) {
		return fmt.Errorf("the source sent a %s file, which is not audio", filepath.Ext(path))
	}
	if errors.Is(err, ingest.ErrNoStore) {
		return err
	}
	// Other outcomes, success or failure, were written to the job by Ingest.
	return nil
}

// known reports whether a source URL is already in the Store, and whether
// the user already has it.
func (f *Fetcher) known(ctx context.Context, userID int64, link string) (track int64, has bool, err error) {
	err = f.DB.QueryRowContext(ctx, `
		SELECT o.track_id, EXISTS(SELECT 1 FROM pointers p WHERE p.user_id = ? AND p.track_id = o.track_id)
		FROM objects o WHERE o.source_url = ? LIMIT 1`, userID, link).Scan(&track, &has)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	return
}

// InLibrary marks which links a user already has, for the Add picker.
func (f *Fetcher) InLibrary(ctx context.Context, userID int64, links []string) map[string]bool {
	out := map[string]bool{}
	for _, l := range links {
		if _, has, err := f.known(ctx, userID, l); err == nil && has {
			out[l] = true
		}
	}
	return out
}

const maxArtwork = 10 << 20

// artwork fetches the source's cover. Failure is not an error: the Track
// simply has no cover until one is set.
func (f *Fetcher) artwork(ctx context.Context, url string) ([]byte, string) {
	if url == "" || f.HTTP == nil {
		return nil, ""
	}
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, ""
	}
	res, err := f.HTTP.Do(req)
	if err != nil {
		return nil, ""
	}
	defer res.Body.Close()
	ct, _, _ := mime.ParseMediaType(res.Header.Get("Content-Type"))
	ext := map[string]string{"image/jpeg": "jpg", "image/png": "png"}[ct]
	if res.StatusCode != http.StatusOK || ext == "" {
		return nil, ""
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, maxArtwork+1))
	if err != nil || len(b) > maxArtwork {
		return nil, ""
	}
	return b, ext
}

func (f *Fetcher) set(id int64, assignments string, args ...any) {
	q := "UPDATE ingest_jobs SET " + assignments + ", updated_at = ? WHERE id = ?"
	if _, err := f.DB.ExecContext(context.Background(), q, append(args, db.Now(), id)...); err != nil {
		f.Log.Error("updating job", "id", id, "err", err)
	}
}

func placeholders(n int) string { return strings.TrimSuffix(strings.Repeat("?, ", n), ", ") }

func toAny(ids []int64) []any {
	out := make([]any, len(ids))
	for i, v := range ids {
		out[i] = v
	}
	return out
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}
