package native

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/playlists"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
)

// CapPlaylists: playlists can be made and edited, through the Subsonic
// API's createPlaylist, updatePlaylist and deletePlaylist (§2.4).
const CapPlaylists = "playlists"

// CapSmart: smart playlists' rules can be read and edited here; their
// songs, like any playlist's, come from the Subsonic API as "sp-N".
const CapSmart = "smart-playlists"

func (a *API) registerSmart(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/smart", a.authed(a.smartList))
	mux.HandleFunc("POST /cosine/v1/smart", a.authed(a.smartCreate))
	mux.HandleFunc("PUT /cosine/v1/smart/{id}", a.authed(a.smartUpdate))
}

// smartWire is a smart playlist on the wire: its Subsonic id and a
// sentence saying what its rule picks.
type smartWire struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Rule        string `json:"rule"`
	Span        string `json:"span,omitempty"`
	Months      int    `json:"months,omitempty"`
	Days        int    `json:"days,omitempty"`
	Size        int    `json:"size"`
	Description string `json:"description"`
}

func toWire(sm playlists.Smart) smartWire {
	return smartWire{ID: "sp-" + strconv.FormatInt(sm.ID, 10), Name: sm.Name, Rule: sm.Rule,
		Span: sm.Span, Months: sm.Months, Days: sm.Days, Size: sm.Size, Description: sm.Describe()}
}

func (a *API) smartList(w http.ResponseWriter, r *http.Request, u auth.User) {
	lists, err := a.Playlists.SmartLists(r.Context(), u.ID)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]smartWire, 0, len(lists))
	for _, sm := range lists {
		out = append(out, toWire(sm))
	}
	writeJSON(w, http.StatusOK, map[string]any{"playlists": out, "rules": stats.Views})
}

func (a *API) smartCreate(w http.ResponseWriter, r *http.Request, u auth.User) {
	var req smartWire
	if !decode(w, r, &req) {
		return
	}
	sm, err := a.Playlists.CreateSmart(r.Context(), u.ID, fromWire(req))
	if err != nil {
		a.smartError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, toWire(sm))
}

func (a *API) smartUpdate(w http.ResponseWriter, r *http.Request, u auth.User) {
	id, err := strconv.ParseInt(strings.TrimPrefix(r.PathValue("id"), "sp-"), 10, 64)
	if err != nil {
		writeError(w, http.StatusNotFound, "No such smart playlist.")
		return
	}
	var req smartWire
	if !decode(w, r, &req) {
		return
	}
	sm := fromWire(req)
	sm.ID = id
	sm, err = a.Playlists.UpdateSmart(r.Context(), u.ID, sm)
	if err != nil {
		a.smartError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, toWire(sm))
}

func fromWire(s smartWire) playlists.Smart {
	return playlists.Smart{Name: s.Name, Rule: s.Rule, Span: s.Span, Months: s.Months, Days: s.Days, Size: s.Size}
}

func (a *API) smartError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, playlists.ErrNotFound):
		writeError(w, http.StatusNotFound, "No such smart playlist.")
	case errors.Is(err, playlists.ErrRule):
		writeError(w, http.StatusBadRequest, "Unknown rule.")
	case errors.Is(err, playlists.ErrName):
		writeError(w, http.StatusBadRequest, "A playlist needs a name.")
	case errors.Is(err, playlists.ErrLongName):
		writeError(w, http.StatusBadRequest, "That name is too long.")
	case errors.Is(err, stats.ErrWindow):
		writeError(w, http.StatusBadRequest, "Unknown period for most played.")
	default:
		a.fail(w, err)
	}
}
