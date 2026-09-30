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
	// Covers fetches a release's cover from the Cover Art Archive; nil disables it.
	Covers func(ctx context.Context, releaseMBID, releaseGroupMBID string) ([]byte, string)

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

	// Duration first: it is evidence for the online sources.
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

	tags := resolve.ReadTags(req.Path)
	res, err := g.Resolver.Resolve(ctx, resolve.Input{
		Path: req.Path, OriginalName: req.OriginalName, Tags: tags, Source: req.SourceInfo, DurationSec: duration,
	})
	if err != nil {
		return Outcome{}, err
	}
	reviewed := !resolve.NeedsReview(res, g.ReviewThreshold)

	tx, err := g.DB.BeginTx(ctx, nil)
	if err != nil {
		return Outcome{}, err
	}
	defer tx.Rollback()

	id, err := upsertIdentity(ctx, tx, res, res.Tier == resolve.TierMusicBrainz && reviewed)
	if err != nil {
		return Outcome{}, err
	}
	trackID, err := upsertID(ctx, tx,
		`INSERT INTO tracks(release_id, artist_id, title, track_no, disc_no, resolver, tier, confidence, reviewed, mbid, created_at)
		 VALUES(?, ?, ?, NULLIF(?, 0), NULLIF(?, 0), ?, ?, ?, ?, NULLIF(?, ''), ?) ON CONFLICT(release_id, title) DO NOTHING`,
		[]any{id.release, id.trackArtist, res.Title, res.TrackNo, res.DiscNo, res.Source, res.Tier, res.Confidence, reviewed, res.IDs.MBRecording, db.Now()},
		"SELECT id FROM tracks WHERE release_id = ? AND title = ?", id.release, res.Title)
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
	var embedded Art
	if tags != nil && tags.Picture != nil {
		embedded = Art{Source: ArtEmbedded, Ext: tags.Picture.Ext, Data: tags.Picture.Data}
	}
	g.offerArtwork(ctx, id.release, res.IDs,
		embedded, Art{Source: ArtSource, Ext: req.ArtworkExt, Data: req.Artwork})
	return Outcome{TrackID: trackID, Hash: hash, RelPath: rel}, nil
}

type identityIDs struct {
	artist, trackArtist, release int64
}

// upsertIdentity finds or creates the artist(s) and release for an
// identity, recording catalogue IDs the first time they're known. Names
// match regardless of case; an authoritative identity (a person's
// correction, a catalogue match) also fixes the stored spelling.
func upsertIdentity(ctx context.Context, tx *sql.Tx, res resolve.Result, authoritative bool) (identityIDs, error) {
	var id identityIDs
	artist := func(name, mbid string) (int64, error) {
		n, err := upsertID(ctx, tx,
			"INSERT INTO artists(name, mbid) VALUES(?, NULLIF(?, '')) ON CONFLICT(name) DO NOTHING", []any{name, mbid},
			"SELECT id FROM artists WHERE name = ?", name)
		if err == nil && mbid != "" {
			_, err = tx.ExecContext(ctx, "UPDATE artists SET mbid = ? WHERE id = ? AND mbid IS NULL", mbid, n)
		}
		if err == nil && authoritative {
			_, err = tx.ExecContext(ctx, "UPDATE artists SET name = ? WHERE id = ? AND name COLLATE BINARY != ?", name, n, name)
		}
		return n, err
	}
	var err error
	if id.artist, err = artist(res.Artist, res.IDs.MBArtist); err != nil {
		return id, err
	}
	id.trackArtist = id.artist
	if res.TrackArtist != "" && res.TrackArtist != res.Artist {
		if id.trackArtist, err = artist(res.TrackArtist, ""); err != nil {
			return id, err
		}
	}
	id.release, err = upsertID(ctx, tx,
		`INSERT INTO releases(artist_id, title, year, mbid, release_group_mbid, created_at)
		 VALUES(?, ?, NULLIF(?, 0), NULLIF(?, ''), NULLIF(?, ''), ?) ON CONFLICT(artist_id, title) DO NOTHING`,
		[]any{id.artist, res.Release, res.Year, res.IDs.MBRelease, res.IDs.MBReleaseGroup, db.Now()},
		"SELECT id FROM releases WHERE artist_id = ? AND title = ?", id.artist, res.Release)
	if err != nil {
		return id, err
	}
	if authoritative {
		if _, err := tx.ExecContext(ctx, "UPDATE releases SET title = ? WHERE id = ? AND title COLLATE BINARY != ?",
			res.Release, id.release, res.Release); err != nil {
			return id, err
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE releases SET
		mbid = COALESCE(mbid, NULLIF(?, '')),
		release_group_mbid = COALESCE(release_group_mbid, NULLIF(?, '')),
		year = COALESCE(year, NULLIF(?, 0))
		WHERE id = ?`, res.IDs.MBRelease, res.IDs.MBReleaseGroup, res.Year, id.release)
	return id, err
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

// Artwork sources, best first (§3.2a): the resolved release's cover, then
// art embedded in the file, then the source site's thumbnail. User-set
// covers are per account and sit above all of these.
const (
	ArtCAA      = "caa"
	ArtEmbedded = "embedded"
	ArtSource   = "source"
)

var artRank = map[string]int{ArtCAA: 3, ArtEmbedded: 2, ArtSource: 1}

type Art struct {
	Source, Ext string
	Data        []byte
}

// offerArtwork gives a release the best cover available, replacing one from
// a worse source. The Cover Art Archive is asked only when it could win.
func (g *Ingester) offerArtwork(ctx context.Context, releaseID int64, ids resolve.IDs, local ...Art) {
	var current sql.NullString
	if err := g.DB.QueryRowContext(ctx, "SELECT art_source FROM releases WHERE id = ?", releaseID).Scan(&current); err != nil {
		return
	}
	have := artRank[current.String]
	if have < artRank[ArtCAA] && g.Covers != nil && (ids.MBRelease != "" || ids.MBReleaseGroup != "") {
		if data, ext := g.Covers(ctx, ids.MBRelease, ids.MBReleaseGroup); data != nil {
			g.saveArtwork(ctx, releaseID, Art{Source: ArtCAA, Ext: ext, Data: data})
			return
		}
	}
	for _, a := range local {
		if len(a.Data) > 0 && artRank[a.Source] > have {
			g.saveArtwork(ctx, releaseID, a)
			return
		}
	}
}

func (g *Ingester) saveArtwork(ctx context.Context, releaseID int64, a Art) {
	ext := strings.ToLower(strings.TrimPrefix(a.Ext, "."))
	if ext == "" || ext == "jpeg" {
		ext = "jpg"
	}
	rel := filepath.Join("art", fmt.Sprintf("release-%d.%s", releaseID, ext))
	full := filepath.Join(g.DataDir, rel)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return
	}
	tmp := full + ".tmp"
	if err := os.WriteFile(tmp, a.Data, 0o644); err != nil || os.Rename(tmp, full) != nil {
		os.Remove(tmp)
		g.Log.Warn("could not save artwork", "release", releaseID)
		return
	}
	var old sql.NullString
	g.DB.QueryRowContext(ctx, "SELECT art_path FROM releases WHERE id = ?", releaseID).Scan(&old)
	if old.Valid && old.String != filepath.ToSlash(rel) {
		os.Remove(filepath.Join(g.DataDir, filepath.FromSlash(old.String)))
	}
	g.DB.ExecContext(ctx, "UPDATE releases SET art_path = ?, art_source = ? WHERE id = ?",
		filepath.ToSlash(rel), a.Source, releaseID)
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
