package subsonic

import (
	"errors"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
)

// Playlists (§2.4). Manual playlists are "pl-N" and editable by any
// Subsonic client. Smart playlists are "sp-N", listed beside them as
// read-only (OpenSubsonic's readonly): a rule picks their songs, so only
// their name can change here; Sine edits the rule.

func smartID(id int64) string { return "sp-" + strconv.FormatInt(id, 10) }

func getPlaylists(r *request) (*Response, error) {
	lists, err := r.lib.playlists(r.ctx(), r.user.ID, 0)
	if err != nil {
		return nil, err
	}
	if lists == nil {
		lists = []Playlist{}
	}
	if pl := r.api.Playlists; pl != nil {
		smart, err := pl.SmartLists(r.ctx(), r.user.ID)
		if err != nil {
			return nil, err
		}
		for _, sm := range smart {
			p, err := r.smartPlaylist(sm, false)
			if err != nil {
				return nil, err
			}
			lists = append(lists, p)
		}
	}
	return &Response{Playlists: &Playlists{Playlist: lists}}, nil
}

func getPlaylist(r *request) (*Response, error) {
	raw := r.FormValue("id")
	if raw == "" {
		return nil, missing("id")
	}
	p, err := r.playlist(raw)
	if err != nil {
		return nil, err
	}
	return &Response{Playlist: &p}, nil
}

// playlist describes one playlist, songs and all.
func (r *request) playlist(raw string) (Playlist, error) {
	if id, ok := parseID(raw, "sp-"); ok && r.api.Playlists != nil {
		sm, err := r.api.Playlists.SmartList(r.ctx(), r.user.ID, id)
		if errors.Is(err, playlists.ErrNotFound) {
			return Playlist{}, notFound
		}
		if err != nil {
			return Playlist{}, err
		}
		return r.smartPlaylist(sm, true)
	}
	id, ok := parseID(raw, "pl-")
	if !ok {
		return Playlist{}, notFound
	}
	lists, err := r.lib.playlists(r.ctx(), r.user.ID, id)
	if err != nil {
		return Playlist{}, err
	}
	if len(lists) == 0 {
		return Playlist{}, notFound
	}
	p := lists[0]
	p.Entry, err = r.lib.playlistEntries(r.ctx(), r.user.ID, id)
	return p, err
}

// smartPlaylist evaluates a smart playlist now: what it holds changes as
// you listen and add.
func (r *request) smartPlaylist(sm playlists.Smart, entries bool) (Playlist, error) {
	ids, err := r.api.Playlists.Tracks(r.ctx(), r.user.ID, sm)
	if err != nil {
		return Playlist{}, err
	}
	songs, err := r.lib.songsInOrder(r.ctx(), r.user.ID, ids)
	if err != nil {
		return Playlist{}, err
	}
	p := Playlist{
		ID: smartID(sm.ID), Name: sm.Name, Comment: sm.Describe(), Owner: r.user.Name,
		SongCount: len(songs), Readonly: true,
	}
	for _, s := range songs {
		p.Duration += s.Duration
		if p.CoverArt == "" {
			p.CoverArt = s.CoverArt
		}
	}
	if entries {
		p.Entry = songs
	}
	return p, nil
}

// trackIDs reads repeated song id parameters ("tr-N").
func (r *request) trackIDs(name string) ([]int64, error) {
	r.ParseForm()
	var out []int64
	for _, raw := range r.Form[name] {
		id, ok := parseID(raw, "tr-")
		if !ok {
			return nil, notFound
		}
		out = append(out, id)
	}
	return out, nil
}

func (r *request) editable() (*playlists.Service, error) {
	if r.api.Playlists == nil {
		return nil, apiError{ErrNotAuthorized, "Playlists can't be changed on this server."}
	}
	return r.api.Playlists, nil
}

// playlistError turns the playlist service's refusals into Subsonic errors.
func playlistError(err error) error {
	switch {
	case errors.Is(err, playlists.ErrNotFound):
		return notFound
	case errors.Is(err, playlists.ErrNotInLibrary):
		return apiError{ErrNotFound, "A song to add is not in your library."}
	case errors.Is(err, playlists.ErrName):
		return apiError{ErrMissingParam, "A playlist needs a name."}
	case errors.Is(err, playlists.ErrLongName):
		return apiError{ErrGeneric, "That name is too long."}
	}
	return err
}

var smartSongs = apiError{ErrNotAuthorized, "A smart playlist's songs are picked by its rule. Save it as a playlist to choose them yourself."}

// createPlaylist makes a playlist from name and songId, or with playlistId
// replaces an existing one's songs (and name, when given): how Subsonic
// clients reorder. Answers with the playlist (API 1.14).
func createPlaylist(r *request) (*Response, error) {
	pl, err := r.editable()
	if err != nil {
		return nil, err
	}
	songs, err := r.trackIDs("songId")
	if err != nil {
		return nil, err
	}
	name := r.FormValue("name")
	var raw string
	if existing := r.FormValue("playlistId"); existing != "" {
		if _, ok := parseID(existing, "sp-"); ok {
			return nil, smartSongs
		}
		id, ok := parseID(existing, "pl-")
		if !ok {
			return nil, notFound
		}
		edit := playlists.Edit{Tracks: songs, Replace: true}
		if name != "" {
			edit.Name = &name
		}
		if err := pl.Edit(r.ctx(), r.user.ID, id, edit); err != nil {
			return nil, playlistError(err)
		}
		raw = existing
	} else {
		if name == "" {
			return nil, missing("name or playlistId")
		}
		id, err := pl.Create(r.ctx(), r.user.ID, name, songs)
		if err != nil {
			return nil, playlistError(err)
		}
		raw = playlistID(id)
	}
	p, err := r.playlist(raw)
	if err != nil {
		return nil, err
	}
	return &Response{Playlist: &p}, nil
}

// updatePlaylist renames, adds songIdToAdd at the end and removes
// songIndexToRemove (positions in the playlist as last listed).
func updatePlaylist(r *request) (*Response, error) {
	pl, err := r.editable()
	if err != nil {
		return nil, err
	}
	raw := r.FormValue("playlistId")
	if raw == "" {
		return nil, missing("playlistId")
	}
	add, err := r.trackIDs("songIdToAdd")
	if err != nil {
		return nil, err
	}
	r.ParseForm()
	var remove []int
	for _, v := range r.Form["songIndexToRemove"] {
		i, err := strconv.Atoi(v)
		if err != nil || i < 0 {
			return nil, apiError{ErrGeneric, "songIndexToRemove must be a position in the playlist."}
		}
		remove = append(remove, i)
	}
	_, hasName := r.Form["name"]
	name := r.FormValue("name")

	if id, ok := parseID(raw, "sp-"); ok {
		if len(add) > 0 || len(remove) > 0 {
			return nil, smartSongs
		}
		if hasName {
			if err := pl.RenameSmart(r.ctx(), r.user.ID, id, name); err != nil {
				return nil, playlistError(err)
			}
		}
		return &Response{}, nil
	}
	id, ok := parseID(raw, "pl-")
	if !ok {
		return nil, notFound
	}
	edit := playlists.Edit{RemoveAt: remove, Add: add}
	if hasName {
		edit.Name = &name
	}
	if err := pl.Edit(r.ctx(), r.user.ID, id, edit); err != nil {
		return nil, playlistError(err)
	}
	return &Response{}, nil
}

// deletePlaylist deletes a playlist. Its songs stay in the Library.
func deletePlaylist(r *request) (*Response, error) {
	pl, err := r.editable()
	if err != nil {
		return nil, err
	}
	raw := r.FormValue("id")
	if raw == "" {
		return nil, missing("id")
	}
	if id, ok := parseID(raw, "sp-"); ok {
		return &Response{}, playlistError(pl.DeleteSmart(r.ctx(), r.user.ID, id))
	}
	id, ok := parseID(raw, "pl-")
	if !ok {
		return nil, notFound
	}
	return &Response{}, playlistError(pl.Delete(r.ctx(), r.user.ID, id))
}
