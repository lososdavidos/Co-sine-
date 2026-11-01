package native

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/jams"
)

// CapJam: Jam's party mode (§4C), accounts listening together.
const CapJam = "jam"

func (a *API) registerJams(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/jams", a.authed(a.jamMine))
	mux.HandleFunc("POST /cosine/v1/jams", a.authed(a.jamStart))
	mux.HandleFunc("GET /cosine/v1/jams/{id}", a.authed(a.jamState))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/join", a.authed(a.jamDo(a.Jams.Join)))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/leave", a.authed(a.jamDo(a.Jams.Leave)))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/end", a.authed(a.jamDo(a.Jams.End)))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/invite", a.authed(a.jamInvite))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/queue", a.authed(a.jamAdd))
	mux.HandleFunc("POST /cosine/v1/jams/{id}/transport", a.authed(a.jamTransport))
}

func jamID(r *http.Request) int64 {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	return id
}

// jamMine: the jam you're in (null when none) and those you're asked to.
func (a *API) jamMine(w http.ResponseWriter, r *http.Request, u auth.User) {
	current, invites, err := a.Jams.Mine(r.Context(), u.ID)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"jam": current, "invitations": invites})
}

func (a *API) jamStart(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Invite []string `json:"invite"`
	}
	if !decode(w, r, &body) {
		return
	}
	id, err := a.Jams.Start(r.Context(), u.ID, body.Invite)
	if err != nil {
		a.jamError(w, err)
		return
	}
	j, err := a.Jams.State(r.Context(), id, u.ID, 0, 0)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, j)
}

// jamState: the jam now; with ?since=VERSION it waits (up to ?wait=, 25 s
// by default) for a newer one, so a listener hears a change at once.
func (a *API) jamState(w http.ResponseWriter, r *http.Request, u auth.User) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	wait, _ := strconv.Atoi(r.URL.Query().Get("wait"))
	if wait <= 0 || wait > 55 {
		wait = 25
	}
	j, err := a.Jams.State(r.Context(), jamID(r), u.ID, since, time.Duration(wait)*time.Second)
	if err != nil {
		a.jamError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, j)
}

func (a *API) jamDo(do func(ctx context.Context, jam, user int64) error) handler {
	return func(w http.ResponseWriter, r *http.Request, u auth.User) {
		if err := do(r.Context(), jamID(r), u.ID); err != nil {
			a.jamError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}
}

func (a *API) jamInvite(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Names []string `json:"names"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Jams.Invite(r.Context(), jamID(r), u.ID, body.Names); err != nil {
		a.jamError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) jamAdd(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Tracks []string `json:"tracks"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Jams.Add(r.Context(), jamID(r), u.ID, body.Tracks); err != nil {
		a.jamError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) jamTransport(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Type       string `json:"type"`
		PositionMs int64  `json:"positionMs"`
		Index      int    `json:"index"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := a.Jams.Transport(r.Context(), jamID(r), u.ID, body.Type, body.PositionMs, body.Index); err != nil {
		a.jamError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) jamError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, jams.ErrNotFound), errors.Is(err, jams.ErrNotIn):
		writeError(w, http.StatusNotFound, "No such jam, or you're not in it.")
	case errors.Is(err, jams.ErrNotHost):
		writeError(w, http.StatusForbidden, "Only the host can do that.")
	case errors.Is(err, jams.ErrEnded):
		writeError(w, http.StatusGone, "This jam has ended.")
	case errors.Is(err, jams.ErrNobody):
		writeError(w, http.StatusBadRequest, "There's no account by that name.")
	case errors.Is(err, jams.ErrCommand):
		writeError(w, http.StatusBadRequest, "Unknown command.")
	default:
		a.fail(w, err)
	}
}
