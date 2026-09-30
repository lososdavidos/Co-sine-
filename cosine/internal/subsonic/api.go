// Package subsonic serves the Subsonic API (§5.4), so Sine and any
// Subsonic client — Symfonium, DSub, car head units — can browse and play
// one account's Library. Native-only concepts are simply absent here.
package subsonic

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

type API struct {
	DB      *db.DB
	Auth    *auth.Service
	DataDir string
	Version string
	Log     *slog.Logger
}

type request struct {
	*http.Request
	w    http.ResponseWriter
	user auth.User
	lib  library
	api  *API
}

type handler func(r *request) (*Response, error)

// apiError is a Subsonic-level failure: HTTP 200 with status="failed".
type apiError struct {
	code int
	msg  string
}

func (e apiError) Error() string { return e.msg }

func missing(param string) error {
	return apiError{ErrMissingParam, "Required parameter is missing: " + param}
}

var notFound = apiError{ErrNotFound, "Not found."}

// Handler serves /rest/{method}[.view].
func (a *API) Handler() http.Handler {
	methods := map[string]handler{
		"ping":            func(*request) (*Response, error) { return &Response{}, nil },
		"getLicense":      func(*request) (*Response, error) { return &Response{License: &License{Valid: true}}, nil },
		"getMusicFolders": getMusicFolders,
		"getUser":         getUser,
		"getArtists":      getArtists,
		"getIndexes":      getArtists,
		"getArtist":       getArtist,
		"getAlbumList2":   getAlbumList2,
		"getAlbumList":    getAlbumList2,
		"getAlbum":        getAlbum,
		"getSong":         getSong,
		"getRandomSongs":  getRandomSongs,
		"getStarred2":     getStarred2,
		"search3":         search3,
		"getPlaylists":    getPlaylists,
		"getPlaylist":     getPlaylist,
		"scrobble":        scrobble,
		"star":            star(true),
		"unstar":          star(false),
		"stream":          serveFile,
		"download":        serveFile,
		"getCoverArt":     getCoverArt,
	}
	return http.HandlerFunc(func(w http.ResponseWriter, hr *http.Request) {
		name := strings.TrimSuffix(path.Base(hr.URL.Path), ".view")
		r := &request{Request: hr, w: w, lib: library{a.DB}, api: a}
		h, ok := methods[name]
		if !ok {
			a.write(r, nil, apiError{ErrNotFound, "Unknown method " + name})
			return
		}
		user, err := a.authenticate(hr)
		if err != nil {
			a.write(r, nil, err)
			return
		}
		r.user = user
		resp, err := h(r)
		if resp == nil && err == nil {
			return // the handler wrote a file itself
		}
		a.write(r, resp, err)
	})
}

func (a *API) authenticate(r *http.Request) (auth.User, error) {
	name := r.FormValue("u")
	if name == "" {
		return auth.User{}, missing("u")
	}
	token, salt, pass := r.FormValue("t"), r.FormValue("s"), r.FormValue("p")
	if (token == "" || salt == "") && pass == "" {
		return auth.User{}, missing("t and s, or p")
	}
	u, err := a.Auth.CheckSubsonic(r.Context(), name, token, salt, pass)
	if errors.Is(err, auth.ErrBadCredentials) {
		return auth.User{}, apiError{ErrWrongCreds, "Wrong username or password."}
	}
	return u, err
}

func (a *API) write(r *request, resp *Response, err error) {
	if resp == nil {
		resp = &Response{}
	}
	resp.Status = "ok"
	if err != nil {
		var ae apiError
		if !errors.As(err, &ae) {
			a.Log.Error("subsonic", "method", r.URL.Path, "err", err)
			ae = apiError{ErrGeneric, "Server error."}
		}
		*resp = Response{Status: "failed", Error: &Error{Code: ae.code, Message: ae.msg}}
	}
	resp.Version, resp.Type, resp.ServerVersion, resp.OpenSubsonic = APIVersion, ServerType, a.Version, true

	if r.FormValue("f") == "json" {
		r.w.Header().Set("Content-Type", "application/json; charset=utf-8")
		json.NewEncoder(r.w).Encode(map[string]*Response{"subsonic-response": resp})
		return
	}
	r.w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	r.w.Write([]byte(xml.Header))
	xml.NewEncoder(r.w).Encode(resp)
}

func (r *request) ctx() context.Context { return r.Context() }

func (r *request) intParam(name string, def, max int) int {
	n, err := strconv.Atoi(r.FormValue(name))
	if err != nil || n < 0 {
		return def
	}
	if max > 0 && n > max {
		return max
	}
	return n
}

func (r *request) id(prefix string) (int64, error) {
	raw := r.FormValue("id")
	if raw == "" {
		return 0, missing("id")
	}
	n, ok := parseID(raw, prefix)
	if !ok {
		return 0, notFound
	}
	return n, nil
}

func wrapNotFound(err error) error {
	if errors.Is(err, errNotFound) {
		return notFound
	}
	return err
}

// ---------------------------------------------------------------- handlers

func getMusicFolders(*request) (*Response, error) {
	return &Response{MusicFolders: &MusicFolders{MusicFolder: []MusicFolder{{ID: 1, Name: "Library"}}}}, nil
}

func getUser(r *request) (*Response, error) {
	return &Response{User: &User{
		Username: r.user.Name, AdminRole: r.user.IsAdmin, StreamRole: true, DownloadRole: true,
		PlaylistRole: true, CoverArtRole: true, ScrobblingEnabled: true,
	}}, nil
}

func getArtists(r *request) (*Response, error) {
	artists, err := r.lib.artists(r.ctx(), r.user.ID, "", -1, 0)
	if err != nil {
		return nil, err
	}
	return &Response{Artists: &Artists{Index: indexed(artists)}}, nil
}

func getArtist(r *request) (*Response, error) {
	id, err := r.id("ar-")
	if err != nil {
		return nil, err
	}
	found, err := r.lib.artists(r.ctx(), r.user.ID, "AND a.id = ?", 1, 0, id)
	if err != nil {
		return nil, err
	}
	if len(found) == 0 {
		return nil, notFound
	}
	artist := found[0]
	// Interleaved chronologically: one body of work, not albums above singles (§6.9).
	artist.Album, err = r.lib.albums(r.ctx(), r.user.ID, "AND a.id = ?", "year", -1, 0, id)
	if err != nil {
		return nil, err
	}
	sortByYear(artist.Album)
	return &Response{Artist: &artist}, nil
}

func sortByYear(albums []Album) {
	for i := 1; i < len(albums); i++ {
		for j := i; j > 0 && albums[j].Year < albums[j-1].Year; j-- {
			albums[j], albums[j-1] = albums[j-1], albums[j]
		}
	}
}

func getAlbumList2(r *request) (*Response, error) {
	t := r.FormValue("type")
	if t == "" {
		return nil, missing("type")
	}
	albums, err := r.lib.albums(r.ctx(), r.user.ID, "", albumSort(t),
		r.intParam("size", 10, 500), r.intParam("offset", 0, 0))
	if err != nil {
		return nil, err
	}
	return &Response{AlbumList2: &AlbumList{Album: albums}}, nil
}

func getAlbum(r *request) (*Response, error) {
	id, err := r.id("al-")
	if err != nil {
		return nil, err
	}
	found, err := r.lib.albums(r.ctx(), r.user.ID, "AND r.id = ?", sortName, 1, 0, id)
	if err != nil {
		return nil, err
	}
	if len(found) == 0 {
		return nil, notFound
	}
	album := found[0]
	album.Song, err = r.lib.songs(r.ctx(), r.user.ID, "AND r.id = ?", trackOrder, id)
	if err != nil {
		return nil, err
	}
	return &Response{Album: &album}, nil
}

func getSong(r *request) (*Response, error) {
	id, err := r.id("tr-")
	if err != nil {
		return nil, err
	}
	s, err := r.lib.song(r.ctx(), r.user.ID, id)
	if err != nil {
		return nil, wrapNotFound(err)
	}
	return &Response{Song: &s}, nil
}

func getRandomSongs(r *request) (*Response, error) {
	songs, err := r.lib.songs(r.ctx(), r.user.ID, "", "RANDOM() LIMIT "+strconv.Itoa(r.intParam("size", 10, 500)))
	if err != nil {
		return nil, err
	}
	return &Response{RandomSongs: &Songs{Song: songs}}, nil
}

func getStarred2(r *request) (*Response, error) {
	songs, err := r.lib.songs(r.ctx(), r.user.ID, "AND p.starred_at IS NOT NULL", "p.starred_at DESC")
	if err != nil {
		return nil, err
	}
	albums, err := r.lib.albums(r.ctx(), r.user.ID, "", sortStarred, 500, 0)
	if err != nil {
		return nil, err
	}
	return &Response{Starred2: &SearchResult3{Album: albums, Song: songs}}, nil
}

func search3(r *request) (*Response, error) {
	// An empty query lists everything: Symfonium and others sync this way.
	like := likePattern(strings.Trim(r.FormValue("query"), `"`))
	artists, err := r.lib.artists(r.ctx(), r.user.ID, `AND a.name LIKE ? ESCAPE '\'`,
		r.intParam("artistCount", 20, 500), r.intParam("artistOffset", 0, 0), like)
	if err != nil {
		return nil, err
	}
	albums, err := r.lib.albums(r.ctx(), r.user.ID, `AND r.title LIKE ? ESCAPE '\'`, sortName,
		r.intParam("albumCount", 20, 500), r.intParam("albumOffset", 0, 0), like)
	if err != nil {
		return nil, err
	}
	songLimit := r.intParam("songCount", 20, 500)
	songOffset := r.intParam("songOffset", 0, 0)
	songs, err := r.lib.songs(r.ctx(), r.user.ID, `AND t.title LIKE ? ESCAPE '\'`,
		"t.title LIMIT "+strconv.Itoa(songLimit)+" OFFSET "+strconv.Itoa(songOffset), like)
	if err != nil {
		return nil, err
	}
	return &Response{SearchResult3: &SearchResult3{Artist: artists, Album: albums, Song: songs}}, nil
}

func getPlaylists(r *request) (*Response, error) {
	lists, err := r.lib.playlists(r.ctx(), r.user.ID, 0)
	if err != nil {
		return nil, err
	}
	return &Response{Playlists: &Playlists{Playlist: lists}}, nil
}

func getPlaylist(r *request) (*Response, error) {
	id, err := r.id("pl-")
	if err != nil {
		return nil, err
	}
	lists, err := r.lib.playlists(r.ctx(), r.user.ID, id)
	if err != nil {
		return nil, err
	}
	if len(lists) == 0 {
		return nil, notFound
	}
	p := lists[0]
	p.Entry, err = r.lib.playlistEntries(r.ctx(), r.user.ID, id)
	if err != nil {
		return nil, err
	}
	return &Response{Playlist: &p}, nil
}

// scrobble records a play as an append-only event (Q37). The client's
// timestamp is trusted (Q70), and a resent play is a no-op. Only
// submissions count; "now playing" notifications are accepted and ignored.
func scrobble(r *request) (*Response, error) {
	if r.FormValue("submission") == "false" {
		return &Response{}, nil
	}
	r.ParseForm()
	ids := r.Form["id"]
	if len(ids) == 0 {
		return nil, missing("id")
	}
	times := r.Form["time"]
	for i, raw := range ids {
		id, ok := parseID(raw, "tr-")
		if !ok {
			continue
		}
		at := time.Now().UnixMilli()
		if i < len(times) {
			if t, err := strconv.ParseInt(times[i], 10, 64); err == nil && t > 0 {
				at = t
			}
		}
		// A play attaches to the account's Pointer; without one there is
		// nothing to record (Q82).
		if _, err := r.api.DB.ExecContext(r.ctx(), `
			INSERT INTO plays(user_id, track_id, played_at)
			SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM pointers WHERE user_id = ? AND track_id = ?)
			ON CONFLICT DO NOTHING`, r.user.ID, id, at, r.user.ID, id); err != nil {
			return nil, err
		}
	}
	return &Response{}, nil
}

func star(on bool) handler {
	return func(r *request) (*Response, error) {
		r.ParseForm()
		var value any
		if on {
			value = db.Now()
		}
		for _, raw := range r.Form["id"] {
			id, ok := parseID(raw, "tr-")
			if !ok {
				continue // starring albums and artists: not modelled yet
			}
			if _, err := r.api.DB.ExecContext(r.ctx(),
				"UPDATE pointers SET starred_at = ? WHERE user_id = ? AND track_id = ?", value, r.user.ID, id); err != nil {
				return nil, err
			}
			db.LogChange(r.ctx(), r.api.DB, r.user.ID, "pointer", strconv.FormatInt(id, 10), "update")
		}
		return &Response{}, nil
	}
}

// serveFile streams the original bytes, always: there is no transcoding (NG7),
// so format and maxBitRate are ignored. Range requests are honoured for seeking.
func serveFile(r *request) (*Response, error) {
	id, err := r.id("tr-")
	if err != nil {
		return nil, err
	}
	rel, contentType, isMissing, err := r.lib.object(r.ctx(), r.user.ID, id)
	if err != nil {
		return nil, wrapNotFound(err)
	}
	root, err := r.api.DB.Setting(r.ctx(), db.SettingStorePath)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(filepath.Join(root, filepath.FromSlash(rel)))
	if isMissing || errors.Is(err, os.ErrNotExist) {
		return nil, apiError{ErrNotFound, "File missing from the Store."}
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	r.w.Header().Set("Content-Type", contentType)
	if strings.HasSuffix(r.URL.Path, "download") || strings.HasSuffix(r.URL.Path, "download.view") {
		r.w.Header().Set("Content-Disposition", `attachment; filename="`+strings.ReplaceAll(filepath.Base(rel), `"`, "")+`"`)
	}
	http.ServeContent(r.w, r.Request, filepath.Base(rel), fi.ModTime(), f)
	return nil, nil
}

// getCoverArt serves the stored cover. The size parameter is ignored for
// now: covers are served as stored and clients scale them.
func getCoverArt(r *request) (*Response, error) {
	id := r.FormValue("id")
	if id == "" {
		return nil, missing("id")
	}
	rel, err := r.lib.releaseArt(r.ctx(), id)
	if err != nil {
		return nil, wrapNotFound(err)
	}
	f, err := os.Open(filepath.Join(r.api.DataDir, filepath.FromSlash(rel)))
	if err != nil {
		return nil, notFound
	}
	defer f.Close()
	fi, _ := f.Stat()
	r.w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeContent(r.w, r.Request, filepath.Base(rel), fi.ModTime(), f)
	return nil, nil
}
