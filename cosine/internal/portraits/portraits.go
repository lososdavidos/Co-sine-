// Package portraits finds a picture for each artist (§6.9). Deezer's, found
// through MusicBrainz's link to the artist's Deezer page when MusicBrainz
// knows them, else by name, accepted only from a Deezer artist of exactly
// that name who has a track or release of the same name as one of theirs in
// the library: two artists can share a name, but rarely a song as well.
// Pictures are saved beside release covers and served as the artist's cover
// art. An artist without one is looked for again a month later.
package portraits

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/deezer"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// Recheck is how long an artist without a picture waits to be looked for again.
const Recheck = 30 * 24 * time.Hour

type Service struct {
	DB      *db.DB
	DataDir string
	Deezer  *deezer.Client
	// Links lists the pages MusicBrainz links an artist to; nil skips it.
	Links func(ctx context.Context, mbid string) ([]string, error)
	// Image fetches a picture: its bytes and file extension, nil if it can't.
	Image func(ctx context.Context, url string) ([]byte, string)
	Log   *slog.Logger
	Now   func() time.Time
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

type artist struct {
	id         int64
	name, mbid string
}

// Run looks for pictures for every artist that is due a look, one at a
// time at the catalogues' own pace. It returns how many it found; it stops
// early, without marking anyone looked at, when a catalogue isn't answering.
func (s *Service) Run(ctx context.Context) (found int, err error) {
	due, err := s.due(ctx)
	if err != nil {
		return 0, err
	}
	for _, a := range due {
		url, err := s.find(ctx, a)
		if err != nil {
			return found, err
		}
		if url != "" && s.save(ctx, a, url) {
			found++
			continue
		}
		s.DB.ExecContext(ctx, "UPDATE artists SET art_checked_at = ? WHERE id = ?", s.now().UnixMilli(), a.id)
	}
	return found, nil
}

// due lists artists in someone's library with no picture, not looked for
// within Recheck, never-looked-at first. "Various Artists" has no face.
func (s *Service) due(ctx context.Context) ([]artist, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT a.id, a.name, COALESCE(a.mbid, '') FROM artists a
		WHERE a.art_path IS NULL AND COALESCE(a.art_checked_at, 0) < ?
		  AND a.name NOT IN ('Various Artists', 'Unknown artist')
		  AND EXISTS (SELECT 1 FROM tracks t JOIN releases r ON r.id = t.release_id
		              WHERE (t.artist_id = a.id OR r.artist_id = a.id) AND `+db.Available+`)
		ORDER BY COALESCE(a.art_checked_at, 0), a.id`, s.now().Add(-Recheck).UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []artist
	for rows.Next() {
		var a artist
		if err := rows.Scan(&a.id, &a.name, &a.mbid); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// find returns the URL of the artist's picture, or "" if there is none.
func (s *Service) find(ctx context.Context, a artist) (string, error) {
	if a.mbid != "" && s.Links != nil {
		links, err := s.Links(ctx, a.mbid)
		if err != nil {
			return "", err
		}
		for _, l := range links {
			id, ok := deezer.ArtistID(l)
			if !ok {
				continue
			}
			da, err := s.Deezer.Artist(ctx, id)
			if errors.Is(err, deezer.ErrNotFound) {
				continue
			}
			if err != nil {
				return "", err
			}
			if da.Picture() != "" {
				return da.Picture(), nil
			}
		}
	}
	return s.byName(ctx, a)
}

// maxNamesakes is how many Deezer artists of the same name are checked.
const maxNamesakes = 3

func (s *Service) byName(ctx context.Context, a artist) (string, error) {
	found, err := s.Deezer.SearchArtists(ctx, a.name)
	if err != nil && !errors.Is(err, deezer.ErrNotFound) {
		return "", err
	}
	var ours []string
	checked := 0
	for _, da := range found {
		if resolve.Normalise(da.Name) != resolve.Normalise(a.name) || da.Picture() == "" {
			continue
		}
		if checked == maxNamesakes {
			break
		}
		checked++
		if ours == nil {
			if ours, err = s.titles(ctx, a.id); err != nil {
				return "", err
			}
		}
		theirs, err := s.Deezer.Titles(ctx, da.ID)
		if errors.Is(err, deezer.ErrNotFound) {
			continue
		}
		if err != nil {
			return "", err
		}
		if shared(ours, theirs) {
			return da.Picture(), nil
		}
	}
	return "", nil
}

// titles lists the names of the artist's tracks and releases in the library.
func (s *Service) titles(ctx context.Context, artistID int64) ([]string, error) {
	rows, err := s.DB.QueryContext(ctx, `
		SELECT t.title FROM tracks t WHERE t.artist_id = ?1
		UNION SELECT r.title FROM releases r WHERE r.artist_id = ?1`, artistID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func shared(ours, theirs []string) bool {
	for _, a := range ours {
		for _, b := range theirs {
			if resolve.Similarity(resolve.CleanTitle(a), resolve.CleanTitle(b)) >= 0.9 {
				return true
			}
		}
	}
	return false
}

// save stores the picture and offers it as the artist's cover art.
func (s *Service) save(ctx context.Context, a artist, url string) bool {
	if s.Image == nil {
		return false
	}
	data, ext := s.Image(ctx, url)
	if data == nil {
		return false
	}
	if ext == "" || ext == "jpeg" {
		ext = "jpg"
	}
	rel := filepath.Join("art", fmt.Sprintf("artist-%d.%s", a.id, ext))
	full := filepath.Join(s.DataDir, rel)
	tmp := full + ".tmp"
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return false
	}
	if err := os.WriteFile(tmp, data, 0o644); err != nil || os.Rename(tmp, full) != nil {
		os.Remove(tmp)
		s.Log.Warn("could not save an artist picture", "artist", a.name)
		return false
	}
	_, err := s.DB.ExecContext(ctx, "UPDATE artists SET art_path = ?, art_source = 'deezer', art_checked_at = ? WHERE id = ?",
		filepath.ToSlash(rel), s.now().UnixMilli(), a.id)
	if err != nil {
		return false
	}
	db.LogChange(ctx, s.DB, 0, "artist", strconv.FormatInt(a.id, 10), "upsert") // the mirror picks it up
	return true
}
