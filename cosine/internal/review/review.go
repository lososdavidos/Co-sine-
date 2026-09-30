// Package review is the review queue (§5.3, §6.12): Tracks the resolver was
// unsure of, corrected by people, with every correction logged and
// reversible (§3.2).
package review

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

var (
	ErrNotFound  = errors.New("no such track in your library")
	ErrForbidden = errors.New("only an admin or the person who made a correction can revert it")
)

type Service struct {
	DB        *db.DB
	Ingester  *ingest.Ingester
	MB        *musicbrainz.Client // nil: candidates unavailable, manual entry still works
	Threshold float64
	Log       *slog.Logger

	mu       sync.Mutex
	progress Progress
}

// File is one Object behind a Track, shown so a person can judge it.
type File struct {
	Path        string `json:"path"`
	Source      string `json:"source"`
	SourceURL   string `json:"sourceUrl,omitempty"`
	SizeBytes   int64  `json:"sizeBytes"`
	DurationSec int    `json:"durationSec"`
}

type Item struct {
	TrackID  string          `json:"trackId"`
	Identity ingest.Identity `json:"identity"`
	Why      string          `json:"why"`
	Files    []File          `json:"files"`
}

func trackRef(id int64) string { return "tr-" + strconv.FormatInt(id, 10) }

// ParseTrack reads "tr-12" or "12".
func ParseTrack(s string) (int64, bool) {
	n, err := strconv.ParseInt(strings.TrimPrefix(s, "tr-"), 10, 64)
	return n, err == nil && n > 0
}

// why says where a guess came from, in the voice of §6.17.
func why(id ingest.Identity) string {
	switch id.Source {
	case "yt-dlp":
		return "Guessed from the source's title and uploader."
	case "tags":
		return "Read from the file's tags."
	case "filename":
		return "Guessed from the file name."
	case "musicbrainz":
		return fmt.Sprintf("MusicBrainz match, %d%% sure.", int(id.Confidence*100))
	default:
		return "Unconfirmed."
	}
}

// visible reports whether a user may see and correct a Track: admins see
// everything, others what is in their own library.
func (s *Service) visible(ctx context.Context, u auth.User, trackID int64) bool {
	var one int
	q := "SELECT 1 FROM tracks WHERE id = ?"
	args := []any{trackID}
	if !u.IsAdmin {
		q = "SELECT 1 FROM pointers WHERE track_id = ? AND user_id = ?"
		args = append(args, u.ID)
	}
	return s.DB.QueryRowContext(ctx, q, args...).Scan(&one) == nil
}

// Queue lists unreviewed Tracks, oldest first so a backlog drains in order.
func (s *Service) Queue(ctx context.Context, u auth.User, limit, offset int) ([]Item, int, error) {
	scope, args := "", []any{}
	if !u.IsAdmin {
		scope, args = "AND EXISTS (SELECT 1 FROM pointers p WHERE p.track_id = t.id AND p.user_id = ?)", []any{u.ID}
	}
	var total int
	if err := s.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM tracks t WHERE t.reviewed = 0 "+scope, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.DB.QueryContext(ctx, "SELECT t.id FROM tracks t WHERE t.reviewed = 0 "+scope+" ORDER BY t.id LIMIT ? OFFSET ?",
		append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	items := make([]Item, 0, len(ids))
	for _, id := range ids {
		it, err := s.item(ctx, id)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, it)
	}
	return items, total, nil
}

// Item returns one Track as the correction screen shows it.
func (s *Service) Item(ctx context.Context, u auth.User, trackID int64) (Item, error) {
	if !s.visible(ctx, u, trackID) {
		return Item{}, ErrNotFound
	}
	return s.item(ctx, trackID)
}

func (s *Service) item(ctx context.Context, trackID int64) (Item, error) {
	id, err := s.Ingester.Identity(ctx, trackID)
	if err != nil {
		return Item{}, err
	}
	it := Item{TrackID: trackRef(trackID), Identity: id, Why: why(id), Files: []File{}}
	rows, err := s.DB.QueryContext(ctx,
		"SELECT rel_path, source, COALESCE(source_url, ''), size, duration_sec FROM objects WHERE track_id = ? ORDER BY size DESC", trackID)
	if err != nil {
		return Item{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var f File
		if err := rows.Scan(&f.Path, &f.Source, &f.SourceURL, &f.SizeBytes, &f.DurationSec); err != nil {
			return Item{}, err
		}
		it.Files = append(it.Files, f)
	}
	return it, rows.Err()
}

// Query overrides the search the correction screen runs. Empty fields fall
// back to the Track's current identity.
type Query struct {
	Artist string `json:"artist"`
	Title  string `json:"title"`
	Album  string `json:"album"`
}

// Candidates searches every source for what a Track might be, ranked and
// labelled by origin (§6.12). Only MusicBrainz exists so far.
func (s *Service) Candidates(ctx context.Context, u auth.User, trackID int64, q Query) ([]resolve.Result, error) {
	it, err := s.Item(ctx, u, trackID)
	if err != nil {
		return nil, err
	}
	if s.MB == nil {
		return nil, musicbrainz.ErrUnavailable
	}
	hint := musicbrainz.HintFrom(it.Identity.Result, longest(it.Files))
	if q.Artist != "" {
		hint.Artist = q.Artist
	}
	if q.Title != "" {
		hint.Title = q.Title
	}
	if q.Album != "" {
		hint.Release = q.Album
	}
	c, err := s.MB.Candidates(ctx, hint, 10)
	if c == nil {
		c = []resolve.Result{}
	}
	return c, err
}

func longest(files []File) int {
	d := 0
	for _, f := range files {
		d = max(d, f.DurationSec)
	}
	return d
}

// CandidateKey identifies a candidate within a search's results.
func CandidateKey(r resolve.Result) string { return r.IDs.MBRecording + "/" + r.IDs.MBRelease }

// Choose applies one of the candidates for q, named by its key. The server
// runs the search again (a cache hit) rather than trusting an identity sent
// by the client: catalogue IDs only ever come from the catalogue.
func (s *Service) Choose(ctx context.Context, u auth.User, trackID int64, q Query, key string) (Item, error) {
	cands, err := s.Candidates(ctx, u, trackID, q)
	if err != nil {
		return Item{}, err
	}
	for _, c := range cands {
		if CandidateKey(c) == key {
			c.Confidence = 1 // a person confirmed it
			final, _, err := s.apply(ctx, &u, trackID, "correct", ingest.Identity{Result: c, Reviewed: true})
			if err != nil {
				return Item{}, err
			}
			return s.item(ctx, final)
		}
	}
	return Item{}, errors.New("that match is no longer offered; search again")
}

// Correct gives a Track an identity typed by hand, which for unreleased
// material is the only honest answer (§6.12).
func (s *Service) Correct(ctx context.Context, u auth.User, trackID int64, to resolve.Result) (Item, error) {
	if !s.visible(ctx, u, trackID) {
		return Item{}, ErrNotFound
	}
	to.Source, to.Tier, to.IDs, to.Confidence = "manual", resolve.TierUser, resolve.IDs{}, 1
	final, _, err := s.apply(ctx, &u, trackID, "correct", ingest.Identity{Result: to, Reviewed: true})
	if err != nil {
		return Item{}, err
	}
	return s.item(ctx, final)
}

// Confirm marks a guess as right without changing it.
func (s *Service) Confirm(ctx context.Context, u auth.User, trackID int64) (Item, error) {
	if !s.visible(ctx, u, trackID) {
		return Item{}, ErrNotFound
	}
	before, err := s.Ingester.Identity(ctx, trackID)
	if err != nil {
		return Item{}, err
	}
	after := before
	after.Reviewed = true
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Item{}, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "UPDATE tracks SET reviewed = 1 WHERE id = ?", trackID); err != nil {
		return Item{}, err
	}
	if _, err := s.record(ctx, tx, &u, trackID, "confirm", before, after, 0); err != nil {
		return Item{}, err
	}
	db.LogChange(ctx, tx, 0, "track", strconv.FormatInt(trackID, 10), "upsert")
	if err := tx.Commit(); err != nil {
		return Item{}, err
	}
	return s.item(ctx, trackID)
}

// apply refiles a Track and logs the change, returning the surviving
// Track and the log entry.
func (s *Service) apply(ctx context.Context, u *auth.User, trackID int64, kind string, to ingest.Identity) (int64, int64, error) {
	before, err := s.Ingester.Identity(ctx, trackID)
	if err != nil {
		return 0, 0, err
	}
	final, merged, err := s.Ingester.Refile(ctx, trackID, to)
	if err != nil {
		return 0, 0, err
	}
	after, _ := s.Ingester.Identity(ctx, final)
	var mergedInto int64
	if merged {
		mergedInto = final
	}
	logID, err := s.record(ctx, s.DB, u, trackID, kind, before, after, mergedInto)
	if err != nil {
		return 0, 0, err
	}
	s.Log.Info("track corrected", "kind", kind, "track", trackID, "by", who(u),
		"from", before.Artist+" / "+before.Title, "to", after.Artist+" / "+after.Title)
	return final, logID, nil
}

func who(u *auth.User) string {
	if u == nil {
		return "cosine"
	}
	return u.Name
}

func (s *Service) record(ctx context.Context, x db.Execer, u *auth.User, trackID int64, kind string, before, after ingest.Identity, mergedInto int64) (int64, error) {
	b, _ := json.Marshal(before)
	a, _ := json.Marshal(after)
	var uid, merged any
	if u != nil {
		uid = u.ID
	}
	if mergedInto != 0 {
		merged = mergedInto
	}
	res, err := x.ExecContext(ctx,
		"INSERT INTO corrections(track_id, user_id, kind, before, after, merged_into, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)",
		trackID, uid, kind, string(b), string(a), merged, db.Now())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// ---------------------------------------------------------------- the log

type Correction struct {
	ID         int64           `json:"id"`
	TrackID    string          `json:"trackId"`
	Who        string          `json:"who"`
	Kind       string          `json:"kind"`
	Before     ingest.Identity `json:"before"`
	After      ingest.Identity `json:"after"`
	Merged     bool            `json:"merged"`
	Reverted   bool            `json:"reverted"`
	Revertible bool            `json:"revertible"`
	At         time.Time       `json:"at"`
}

// History lists corrections, newest first.
func (s *Service) History(ctx context.Context, limit int) ([]Correction, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT c.id, c.track_id, COALESCE(u.name, 'Cosine'), c.kind, c.before, c.after,
		       c.merged_into IS NOT NULL, c.reverted_by IS NOT NULL, c.created_at,
		       c.id = COALESCE((SELECT MAX(c2.id) FROM corrections c2 WHERE c2.track_id = c.track_id
		               AND c2.reverted_by IS NULL AND c2.kind != 'revert'), 0)
		FROM corrections c LEFT JOIN users u ON u.id = c.user_id
		ORDER BY c.id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Correction
	for rows.Next() {
		var c Correction
		var track, at int64
		var before, after string
		var latest bool
		if err := rows.Scan(&c.ID, &track, &c.Who, &c.Kind, &before, &after, &c.Merged, &c.Reverted, &at, &latest); err != nil {
			return nil, err
		}
		json.Unmarshal([]byte(before), &c.Before)
		json.Unmarshal([]byte(after), &c.After)
		c.TrackID, c.At = trackRef(track), time.UnixMilli(at)
		c.Revertible = latest && !c.Merged && !c.Reverted && c.Kind != "revert"
		out = append(out, c)
	}
	return out, rows.Err()
}

// Revert restores a correction's "before". Only the latest change to a
// Track can be reverted, so history is undone in order; a merge can't be
// undone automatically, because the Tracks are one now.
func (s *Service) Revert(ctx context.Context, u auth.User, correctionID int64) error {
	var (
		track    int64
		author   sql.NullInt64
		kind     string
		before   string
		merged   bool
		reverted bool
		latest   bool
	)
	err := s.DB.QueryRowContext(ctx, `
		SELECT c.track_id, c.user_id, c.kind, c.before, c.merged_into IS NOT NULL, c.reverted_by IS NOT NULL,
		       c.id = COALESCE((SELECT MAX(c2.id) FROM corrections c2 WHERE c2.track_id = c.track_id
		               AND c2.reverted_by IS NULL AND c2.kind != 'revert'), 0)
		FROM corrections c WHERE c.id = ?`, correctionID,
	).Scan(&track, &author, &kind, &before, &merged, &reverted, &latest)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return errors.New("no such correction")
	case err != nil:
		return err
	case !u.IsAdmin && (!author.Valid || author.Int64 != u.ID):
		return ErrForbidden
	case reverted || kind == "revert":
		return errors.New("already reverted")
	case merged:
		return errors.New("this correction merged two tracks into one; correct it again by hand instead")
	case !latest:
		return errors.New("a later change to this track exists; revert that first")
	}
	var id ingest.Identity
	if err := json.Unmarshal([]byte(before), &id); err != nil {
		return err
	}
	_, revertID, err := s.apply(ctx, &u, track, "revert", id)
	if err != nil {
		return err
	}
	_, err = s.DB.ExecContext(ctx, "UPDATE corrections SET reverted_by = ? WHERE id = ?", revertID, correctionID)
	return err
}

// ---------------------------------------------------------------- re-resolve

// Progress of a background re-resolve.
type Progress struct {
	Running  bool   `json:"running"`
	Done     int    `json:"done"`
	Total    int    `json:"total"`
	Improved int    `json:"improved"`
	Error    string `json:"error,omitempty"`
}

func (s *Service) Progress() Progress {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.progress
}

// Reresolve runs Tracks back through MusicBrainz in the background, at its
// 1 req/s. A confident match is applied and logged (revertible like any
// correction); anything else stays in the queue. nil means every
// unreviewed Track.
func (s *Service) Reresolve(trackIDs []int64) error {
	if s.MB == nil {
		return musicbrainz.ErrUnavailable
	}
	s.mu.Lock()
	if s.progress.Running {
		s.mu.Unlock()
		return errors.New("a re-resolve is already running")
	}
	ctx := context.Background()
	if trackIDs == nil {
		rows, err := s.DB.QueryContext(ctx, "SELECT id FROM tracks WHERE reviewed = 0 ORDER BY id")
		if err != nil {
			s.mu.Unlock()
			return err
		}
		for rows.Next() {
			var id int64
			rows.Scan(&id)
			trackIDs = append(trackIDs, id)
		}
		rows.Close()
	}
	s.progress = Progress{Running: true, Total: len(trackIDs)}
	s.mu.Unlock()

	go func() {
		defer func() {
			s.mu.Lock()
			s.progress.Running = false
			s.mu.Unlock()
		}()
		for _, id := range trackIDs {
			improved, err := s.reresolveOne(ctx, id)
			s.mu.Lock()
			s.progress.Done++
			if improved {
				s.progress.Improved++
			}
			if errors.Is(err, musicbrainz.ErrUnavailable) {
				s.progress.Error = "MusicBrainz is unavailable; stopped. Run again to continue."
				s.mu.Unlock()
				return
			}
			s.mu.Unlock()
			if err != nil && !errors.Is(err, ingest.ErrNoTrack) {
				s.Log.Warn("re-resolve", "track", id, "err", err)
			}
		}
	}()
	return nil
}

func (s *Service) reresolveOne(ctx context.Context, trackID int64) (bool, error) {
	it, err := s.item(ctx, trackID)
	if err != nil {
		return false, err
	}
	if it.Identity.Reviewed {
		return false, nil // someone got there first
	}
	res, ok, err := s.MB.Match(ctx, musicbrainz.HintFrom(it.Identity.Result, longest(it.Files)))
	if err != nil || !ok || res.Confidence < s.Threshold {
		return false, err
	}
	_, _, err = s.apply(ctx, nil, trackID, "reresolve", ingest.Identity{Result: res, Reviewed: !resolve.NeedsReview(res, s.Threshold)})
	return err == nil, err
}
