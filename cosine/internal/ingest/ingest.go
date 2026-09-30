// Package ingest is the one path by which music enters the Store (§3, §4):
// hash, resolve, file into the canonical tree, create Pointers. The Inbox,
// uploads and (later) yt-dlp fetches all end here.
package ingest

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
)

var (
	ErrNotAudio = errors.New("not an audio file")
	ErrNoStore  = errors.New("the Store path is not set")
	errNoUsers  = errors.New("no accounts exist")
)

// Request describes one file to ingest.
type Request struct {
	Path         string
	OriginalName string // defaults to the base name of Path
	Source       string // "inbox", "upload", "url"
	SourceURL    string
	// ForUsers receive a Pointer. Nil means every account: an Inbox drop is
	// a broadcast (§4.1).
	ForUsers []int64

	// JobID continues an existing ingest_jobs row (a queued fetch) instead of starting one.
	JobID int64
	// SourceInfo is the source site's metadata, for the resolver.
	SourceInfo *resolve.SourceInfo
	// DurationSec is used when the file can't be probed.
	DurationSec int
	// Artwork is the source's cover, used when the file embeds none.
	Artwork    []byte
	ArtworkExt string
	// Explicit means ForUsers asked for this Track themselves, which lifts
	// their dismissed marker. Broadcasts (the Inbox) never do (§4.1).
	Explicit bool
}

type Outcome struct {
	TrackID   int64
	Hash      string
	RelPath   string
	Duplicate bool
}

type Ingester struct {
	DB              *db.DB
	Resolver        resolve.Chain
	DataDir         string
	ReviewThreshold float64
	Log             *slog.Logger
	// Probe returns duration in seconds; nil or failure leaves it 0.
	Probe func(path string) int

	mu sync.Mutex // one ingest at a time: cheap on the P450 (G6), no filing races
}

// Ingest files one audio file. On success the source file is gone (moved
// into the Store, or deleted as a duplicate).
func (g *Ingester) Ingest(ctx context.Context, req Request) (Outcome, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	if req.OriginalName == "" {
		req.OriginalName = filepath.Base(req.Path)
	}
	suffix := strings.TrimPrefix(strings.ToLower(filepath.Ext(req.OriginalName)), ".")
	contentType, ok := store.ContentType(suffix)
	if !ok {
		return Outcome{}, ErrNotAudio
	}
	root, err := g.DB.Setting(ctx, db.SettingStorePath)
	if err != nil {
		return Outcome{}, err
	}
	if root == "" {
		return Outcome{}, ErrNoStore
	}

	jobID := req.JobID
	if jobID == 0 {
		if jobID, err = g.startJob(ctx, req); err != nil {
			return Outcome{}, err
		}
	}
	out, err := g.ingest(ctx, req, root, suffix, contentType)
	g.finishJob(ctx, jobID, out, err)
	return out, err
}

func (g *Ingester) ingest(ctx context.Context, req Request, root, suffix, contentType string) (Outcome, error) {
	hash, size, err := store.Hash(req.Path)
	if err != nil {
		return Outcome{}, err
	}

	// Dedup is free: a known hash creates Pointers, never a second Object (§2.2).
	var existingTrack int64
	err = g.DB.QueryRowContext(ctx, "SELECT track_id FROM objects WHERE hash = ?", hash).Scan(&existingTrack)
	if err == nil {
		if req.Explicit {
			if err := g.Undismiss(ctx, existingTrack, req.ForUsers); err != nil {
				return Outcome{}, err
			}
		}
		if err := g.GrantPointers(ctx, existingTrack, req.ForUsers); err != nil {
			return Outcome{}, err
		}
		os.Remove(req.Path)
		return Outcome{TrackID: existingTrack, Hash: hash, Duplicate: true}, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Outcome{}, err
	}

	tags := resolve.ReadTags(req.Path)
	res, err := g.Resolver.Resolve(ctx, resolve.Input{
		Path: req.Path, OriginalName: req.OriginalName, Tags: tags, Source: req.SourceInfo,
	})
	if err != nil {
		return Outcome{}, err
	}
	duration := 0
	if g.Probe != nil {
		duration = g.Probe(req.Path)
	}
	if duration == 0 {
		duration = req.DurationSec
	}
	bitrate := 0
	if duration > 0 {
		bitrate = int(size * 8 / int64(duration) / 1000)
	}

	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return Outcome{}, err
	}
	defer tx.Rollback()

	artistID, err := upsertID(ctx, tx,
		"INSERT INTO artists(name) VALUES(?) ON CONFLICT(name) DO NOTHING", []any{res.Artist},
		"SELECT id FROM artists WHERE name = ?", res.Artist)
	if err != nil {
		return Outcome{}, err
	}
	releaseID, err := upsertID(ctx, tx,
		"INSERT INTO releases(artist_id, title, year, created_at) VALUES(?, ?, NULLIF(?, 0), ?) ON CONFLICT(artist_id, title) DO NOTHING",
		[]any{artistID, res.Release, res.Year, db.Now()},
		"SELECT id FROM releases WHERE artist_id = ? AND title = ?", artistID, res.Release)
	if err != nil {
		return Outcome{}, err
	}
	reviewed := !resolve.NeedsReview(res, g.ReviewThreshold)
	trackID, err := upsertID(ctx, tx,
		`INSERT INTO tracks(release_id, artist_id, title, track_no, disc_no, resolver, tier, confidence, reviewed, created_at)
		 VALUES(?, ?, ?, NULLIF(?, 0), NULLIF(?, 0), ?, ?, ?, ?, ?) ON CONFLICT(release_id, title) DO NOTHING`,
		[]any{releaseID, artistID, res.Title, res.TrackNo, res.DiscNo, res.Source, res.Tier, res.Confidence, reviewed, db.Now()},
		"SELECT id FROM tracks WHERE release_id = ? AND title = ?", releaseID, res.Title)
	if err != nil {
		return Outcome{}, err
	}

	// A second version of an existing Track gets a hash-suffixed name beside it.
	rel := store.Layout(res.Artist, res.Release, res.Title, res.TrackNo, suffix)
	if taken(ctx, tx, root, rel) {
		rel = store.Versioned(rel, hash)
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO objects(hash, track_id, rel_path, size, suffix, content_type, duration_sec, bitrate, source, source_url, created_at)
		 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?)`,
		hash, trackID, filepath.ToSlash(rel), size, suffix, contentType, duration, bitrate, req.Source, req.SourceURL, db.Now(),
	); err != nil {
		return Outcome{}, err
	}
	if err := db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(trackID, 10), "upsert"); err != nil {
		return Outcome{}, err
	}

	// The file moves last, and moves back if the commit fails: the Store
	// never holds a file the database doesn't know about.
	if err := store.Place(root, rel, req.Path, hash); err != nil {
		return Outcome{}, err
	}
	if err := tx.Commit(); err != nil {
		if os.Rename(filepath.Join(root, rel), req.Path) != nil {
			g.Log.Error("file placed but commit failed; left in Store", "path", rel, "err", err)
		}
		return Outcome{}, err
	}

	if err := g.GrantPointers(ctx, trackID, req.ForUsers); err != nil {
		return Outcome{}, err
	}
	switch {
	case tags != nil && tags.Picture != nil:
		g.saveArtwork(ctx, releaseID, tags.Picture.Ext, tags.Picture.Data)
	case len(req.Artwork) > 0:
		g.saveArtwork(ctx, releaseID, req.ArtworkExt, req.Artwork)
	}
	return Outcome{TrackID: trackID, Hash: hash, RelPath: rel}, nil
}

// Undismiss lifts the dismissed marker for users who explicitly asked for a Track again.
func (g *Ingester) Undismiss(ctx context.Context, trackID int64, users []int64) error {
	for _, uid := range users {
		if _, err := g.DB.ExecContext(ctx, "DELETE FROM dismissed WHERE user_id = ? AND track_id = ?", uid, trackID); err != nil {
			return err
		}
	}
	return nil
}

// GrantPointers gives each account a Pointer, skipping any account that
// dismissed this Track: nothing rejected comes back on its own (§4.1).
func (g *Ingester) GrantPointers(ctx context.Context, trackID int64, users []int64) error {
	if users == nil {
		rows, err := g.DB.QueryContext(ctx, "SELECT id FROM users")
		if err != nil {
			return err
		}
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			users = append(users, id)
		}
		rows.Close()
		if len(users) == 0 {
			return errNoUsers
		}
	}
	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := db.Now()
	for _, uid := range users {
		res, err := tx.ExecContext(ctx, `
			INSERT INTO pointers(user_id, track_id, added_at)
			SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM dismissed WHERE user_id = ? AND track_id = ?)
			ON CONFLICT DO NOTHING`, uid, trackID, now, uid, trackID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n > 0 {
			if err := db.LogChange(ctx, tx, uid, "pointer", strconv.FormatInt(trackID, 10), "add"); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

func (g *Ingester) saveArtwork(ctx context.Context, releaseID int64, ext string, data []byte) {
	var existing sql.NullString
	if err := g.DB.QueryRowContext(ctx, "SELECT art_path FROM releases WHERE id = ?", releaseID).Scan(&existing); err != nil || existing.Valid {
		return
	}
	ext = strings.ToLower(strings.TrimPrefix(ext, "."))
	if ext == "" || ext == "jpeg" {
		ext = "jpg"
	}
	rel := filepath.Join("art", fmt.Sprintf("release-%d.%s", releaseID, ext))
	full := filepath.Join(g.DataDir, rel)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return
	}
	if err := os.WriteFile(full, data, 0o644); err != nil {
		g.Log.Warn("could not save artwork", "err", err)
		return
	}
	g.DB.ExecContext(ctx, "UPDATE releases SET art_path = ? WHERE id = ?", filepath.ToSlash(rel), releaseID)
}

func (g *Ingester) startJob(ctx context.Context, req Request) (int64, error) {
	res, err := g.DB.ExecContext(ctx,
		"INSERT INTO ingest_jobs(source, input, status, created_at, updated_at) VALUES(?, ?, 'running', ?, ?)",
		req.Source, firstNonEmpty(req.SourceURL, req.OriginalName), db.Now(), db.Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (g *Ingester) finishJob(ctx context.Context, id int64, out Outcome, err error) {
	status, msg := "done", ""
	switch {
	case err != nil:
		status, msg = "failed", err.Error()
	case out.Duplicate:
		status = "duplicate"
	}
	var track any
	if out.TrackID != 0 {
		track = out.TrackID
	}
	progress := 0.0
	if err == nil {
		progress = 1
	}
	g.DB.ExecContext(ctx, "UPDATE ingest_jobs SET status = ?, error = NULLIF(?, ''), track_id = ?, progress = ?, updated_at = ? WHERE id = ?",
		status, msg, track, progress, db.Now(), id)
}

// upsertID inserts a row unless it exists, then returns its id either way.
func upsertID(ctx context.Context, tx *sql.Tx, insert string, insertArgs []any, sel string, selArgs ...any) (int64, error) {
	if _, err := tx.ExecContext(ctx, insert, insertArgs...); err != nil {
		return 0, err
	}
	var id int64
	err := tx.QueryRowContext(ctx, sel, selArgs...).Scan(&id)
	return id, err
}

func taken(ctx context.Context, tx *sql.Tx, root, rel string) bool {
	var one int
	if tx.QueryRowContext(ctx, "SELECT 1 FROM objects WHERE rel_path = ?", filepath.ToSlash(rel)).Scan(&one) == nil {
		return true
	}
	_, err := os.Stat(filepath.Join(root, rel))
	return err == nil
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

// FFProbe returns a Probe backed by ffprobe, or nil if it isn't installed.
// Duration is the only thing Cosine can't read itself yet.
func FFProbe() func(string) int {
	bin, err := exec.LookPath("ffprobe")
	if err != nil {
		return nil
	}
	return func(path string) int {
		out, err := exec.Command(bin, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path).Output()
		if err != nil {
			return 0
		}
		f, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
		if err != nil {
			return 0
		}
		return int(f + 0.5)
	}
}
