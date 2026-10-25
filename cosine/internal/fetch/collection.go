package fetch

import (
	"context"
	"database/sql"
	"sync"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Collection is an album, playlist or channel being added (§3.1a). Its
// songs are queued one job each and each is resolved as itself. An album
// lends them its name and track numbers; a playlist never does — a
// playlist is not a release, however album-like its name — and becomes a
// playlist in the Library instead, in the source's order.
type Collection struct {
	URL    string
	Kind   string // ytdlp.KindAlbum, KindPlaylist or KindChannel
	Title  string
	Artist string   // an album's artist, when the source says
	Links  []string // its songs, in order, each once
}

// collectionOf describes a collection yt-dlp listed from url.
func collectionOf(info ytdlp.Info, url string) Collection {
	c := Collection{URL: firstNonEmpty(info.Link(), url), Kind: info.Kind()}
	c.Title = firstNonEmpty(info.CollectionTitle(), c.URL)
	if c.Kind == ytdlp.KindAlbum {
		c.Artist = resolve.CleanArtist(info.CollectionArtist())
	}
	seen := map[string]bool{}
	for _, e := range info.Entries {
		if l := e.Link(); l != "" && !seen[l] {
			seen[l] = true
			c.Links = append(c.Links, l)
		}
	}
	return c
}

// position is a link's 1-based place in the collection; 0 if not in it.
func (c Collection) position(link string) int {
	for i, l := range c.Links {
		if l == link {
			return i + 1
		}
	}
	return 0
}

// From is where links handed to Submit were picked from.
type From struct {
	URL string // the album or playlist, as Lookup gave it
	// Playlist: a playlist also becomes one in the Library.
	Playlist bool
}

// recentCollections remembers the collections Add has just shown, so
// picking songs from one doesn't need it described again.
type recentCollections struct {
	mu sync.Mutex
	m  map[string]remembered
}

type remembered struct {
	Collection
	at time.Time
}

const rememberFor = time.Hour

func (f *Fetcher) remember(c Collection, aliases ...string) {
	f.recent.mu.Lock()
	defer f.recent.mu.Unlock()
	if f.recent.m == nil {
		f.recent.m = map[string]remembered{}
	}
	for k, v := range f.recent.m {
		if time.Since(v.at) > rememberFor {
			delete(f.recent.m, k)
		}
	}
	for _, k := range append(aliases, c.URL) {
		f.recent.m[k] = remembered{c, time.Now()}
	}
}

// collection describes a collection by its URL: from memory when Add just
// showed it, else from the source.
func (f *Fetcher) collection(ctx context.Context, url string) (Collection, error) {
	f.recent.mu.Lock()
	r, ok := f.recent.m[url]
	f.recent.mu.Unlock()
	if ok && time.Since(r.at) < rememberFor {
		return r.Collection, nil
	}
	info, err := f.Runner.Probe(ctx, url)
	if err != nil {
		return Collection{}, err
	}
	c := collectionOf(info, url)
	f.remember(c, url)
	return c, nil
}

// started is a collection being added for one account.
type started struct {
	id       int64
	playlist int64 // 0: none
	Collection
}

// start records an album or playlist being added. A playlist the account
// wants kept gets a playlist named after it — the one made last time, if
// the same playlist is added again and that one still exists.
func (f *Fetcher) start(ctx context.Context, user int64, c Collection, keepPlaylist bool) (*started, error) {
	s := &started{Collection: c}
	if c.Kind == ytdlp.KindPlaylist && keepPlaylist && f.Playlists != nil {
		err := f.DB.QueryRowContext(ctx, `
			SELECT c.playlist_id FROM ingest_collections c JOIN playlists p ON p.id = c.playlist_id
			WHERE c.user_id = ? AND c.url = ? AND p.owner_id = c.user_id ORDER BY c.id DESC LIMIT 1`,
			user, c.URL).Scan(&s.playlist)
		if err == sql.ErrNoRows {
			s.playlist, err = f.Playlists.Create(ctx, user, c.Title, nil)
		}
		if err != nil {
			return nil, err
		}
	}
	var artist, playlist any
	if c.Artist != "" {
		artist = c.Artist
	}
	if s.playlist != 0 {
		playlist = s.playlist
	}
	res, err := f.DB.ExecContext(ctx, `
		INSERT INTO ingest_collections(user_id, url, kind, title, artist, playlist_id, created_at)
		VALUES(?, ?, ?, ?, ?, ?, ?)`, user, c.URL, c.Kind, c.Title, artist, playlist, db.Now())
	if err != nil {
		return nil, err
	}
	s.id, _ = res.LastInsertId()
	return s, nil
}

// placeKnown puts the songs of a playlist the account already has into its
// playlist: Add leaves them unticked, but they belong in it all the same.
func (f *Fetcher) placeKnown(ctx context.Context, user int64, s *started, submitted []string) {
	picked := map[string]bool{}
	for _, u := range submitted {
		picked[u] = true
	}
	for i, link := range s.Links {
		if picked[link] {
			continue
		}
		if track, has, err := f.known(ctx, user, link); err == nil && has {
			f.placeTrack(ctx, user, s.id, s.playlist, i+1, track)
		}
	}
}

// place puts a finished job's song into its collection's playlist, once.
func (f *Fetcher) place(ctx context.Context, job claimed, track int64) {
	if job.collection == 0 || track == 0 || f.Playlists == nil {
		return
	}
	var playlist sql.NullInt64
	if err := f.DB.QueryRowContext(ctx, "SELECT playlist_id FROM ingest_collections WHERE id = ?", job.collection).
		Scan(&playlist); err != nil || !playlist.Valid {
		return
	}
	res, err := f.DB.ExecContext(ctx, "UPDATE ingest_jobs SET placed = 1 WHERE id = ? AND placed = 0", job.id)
	if err != nil {
		f.Log.Error("placing a song in its playlist", "job", job.id, "err", err)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return // placed before, by an earlier try
	}
	f.placeTrack(ctx, job.user, job.collection, playlist.Int64, job.position, track)
}

func (f *Fetcher) placeTrack(ctx context.Context, user, collection, playlist int64, position int, track int64) {
	if _, err := f.DB.ExecContext(ctx, "INSERT INTO collection_places(collection_id, position, track_id) VALUES(?, ?, ?)",
		collection, position, track); err != nil {
		f.Log.Error("placing a song in its playlist", "err", err)
		return
	}
	// Positions of every song placed by any adding of this playlist.
	rows, err := f.DB.QueryContext(ctx, `
		SELECT track_id, MIN(position) FROM collection_places
		WHERE collection_id IN (SELECT id FROM ingest_collections WHERE playlist_id = ?) GROUP BY track_id`, playlist)
	if err != nil {
		f.Log.Error("placing a song in its playlist", "err", err)
		return
	}
	places := map[int64]int{}
	for rows.Next() {
		var t int64
		var p int
		if rows.Scan(&t, &p) == nil {
			places[t] = p
		}
	}
	rows.Close()
	if err := f.Playlists.Place(ctx, user, playlist, track, position, places); err != nil {
		f.Log.Warn("placing a song in its playlist", "playlist", playlist, "err", err) // deleted meanwhile
	}
}

// lend applies a collection to what the source says about one of its
// songs: an album's name and track number fill in what the song doesn't
// say; a playlist's name is never taken for an album.
func lend(src *resolve.SourceInfo, kind, title string, position int) {
	switch kind {
	case ytdlp.KindAlbum:
		if src.Album == "" {
			src.Album = title
		}
		if src.TrackNo == 0 {
			src.TrackNo = position
		}
	case ytdlp.KindPlaylist:
		if src.Album != "" && resolve.Normalise(src.Album) == resolve.Normalise(title) {
			src.Album = ""
		}
	}
}
