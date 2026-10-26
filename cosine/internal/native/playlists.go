package native

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
)

// CapShared: playlists can be shared with other accounts (§2.3).
const CapShared = "shared-playlists"

func (a *API) registerPlaylists(mux *http.ServeMux) {
	if a.Mirror != nil {
		mux.HandleFunc("POST /cosine/v1/playlists/ops", a.authed(a.playlistOps))
	}
	mux.HandleFunc("GET /cosine/v1/accounts", a.authed(a.accounts))
	mux.HandleFunc("POST /cosine/v1/playlists/{id}/share", a.authed(a.share))
	mux.HandleFunc("DELETE /cosine/v1/playlists/{id}/share/{name}", a.authed(a.unshare))
	mux.HandleFunc("POST /cosine/v1/playlists/{id}/accept", a.authed(a.accept))
	mux.HandleFunc("POST /cosine/v1/playlists/{id}/decline", a.authed(a.decline))
}

// opWire is one playlist edit on the wire (playlists.Op). Ids are the
// Subsonic ones; a playlist made offline is named by its create's id, in
// "new".
type opWire struct {
	ID       string   `json:"id"`
	Op       string   `json:"op"`
	Playlist string   `json:"playlist,omitempty"`
	New      string   `json:"new,omitempty"`
	Name     string   `json:"name,omitempty"`
	Tracks   []string `json:"tracks,omitempty"`
	Track    string   `json:"track,omitempty"`
	Nth      int      `json:"nth,omitempty"`
	After    string   `json:"after,omitempty"`
	AfterNth int      `json:"afterNth,omitempty"`
}

type opResultWire struct {
	ID       string `json:"id"`
	Playlist string `json:"playlist,omitempty"`
	Skipped  string `json:"skipped,omitempty"`
}

// playlistOps replays edits a phone made, maybe offline, in order (§5.2).
// Each is applied once however often it's sent. One that can't be applied
// any more (its playlist deleted, say) is answered as skipped, never
// refused, so the phone can let it go.
func (a *API) playlistOps(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Ops []opWire `json:"ops"`
	}
	if !decode(w, r, &body) {
		return
	}
	ops := make([]playlists.Op, 0, len(body.Ops))
	for _, o := range body.Ops {
		if o.ID == "" {
			writeError(w, http.StatusBadRequest, "Each op needs an id.")
			return
		}
		op := playlists.Op{
			ID: o.ID, Kind: o.Op, New: o.New, Name: o.Name, Nth: o.Nth, AfterNth: o.AfterNth,
			Playlist: idOf(o.Playlist, "pl-"), Track: idOf(o.Track, "tr-"), After: idOf(o.After, "tr-"),
		}
		for _, t := range o.Tracks {
			if id := idOf(t, "tr-"); id != 0 {
				op.Tracks = append(op.Tracks, id)
			}
		}
		ops = append(ops, op)
	}
	results, err := a.Playlists.Apply(r.Context(), u.ID, ops)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]opResultWire, len(results))
	for i, res := range results {
		out[i] = opResultWire{ID: res.ID, Skipped: res.Skipped}
		if res.Playlist != 0 {
			out[i].Playlist = "pl-" + strconv.FormatInt(res.Playlist, 10)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": out})
}

// idOf reads a Subsonic id ("tr-12"); 0 when it isn't one.
func idOf(raw, prefix string) int64 {
	n, err := strconv.ParseInt(strings.TrimPrefix(raw, prefix), 10, 64)
	if err != nil || !strings.HasPrefix(raw, prefix) || n <= 0 {
		return 0
	}
	return n
}

// accounts lists the other accounts on this server, to share with.
func (a *API) accounts(w http.ResponseWriter, r *http.Request, u auth.User) {
	users, err := a.Auth.Users(r.Context())
	if err != nil {
		a.fail(w, err)
		return
	}
	names := []string{}
	for _, other := range users {
		if other.ID != u.ID {
			names = append(names, other.Name)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"accounts": names})
}

func (a *API) share(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		With string `json:"with"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Playlists.Share(r.Context(), u.ID, idOf(r.PathValue("id"), "pl-"), body.With); err != nil {
		a.shareError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *API) unshare(w http.ResponseWriter, r *http.Request, u auth.User) {
	if err := a.Playlists.Unshare(r.Context(), u.ID, idOf(r.PathValue("id"), "pl-"), r.PathValue("name")); err != nil {
		a.shareError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// accept answers with how many songs went into the account's Library, for
// the "added N songs" the spec asks for (§2.3).
func (a *API) accept(w http.ResponseWriter, r *http.Request, u auth.User) {
	added, err := a.Playlists.Accept(r.Context(), u.ID, idOf(r.PathValue("id"), "pl-"))
	if err != nil {
		a.shareError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"added": added})
}

func (a *API) decline(w http.ResponseWriter, r *http.Request, u auth.User) {
	if err := a.Playlists.Decline(r.Context(), u.ID, idOf(r.PathValue("id"), "pl-")); err != nil {
		a.shareError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *API) shareError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, playlists.ErrNotFound):
		writeError(w, http.StatusNotFound, "No such playlist.")
	case errors.Is(err, playlists.ErrNoInvite):
		writeError(w, http.StatusNotFound, "That invitation isn't open any more.")
	case errors.Is(err, playlists.ErrNotOwner):
		writeError(w, http.StatusForbidden, "Only the playlist's owner can share it.")
	case errors.Is(err, playlists.ErrNoAccount):
		writeError(w, http.StatusNotFound, "There's no account by that name.")
	case errors.Is(err, playlists.ErrSelf):
		writeError(w, http.StatusBadRequest, "That's your own account.")
	default:
		a.fail(w, err)
	}
}
