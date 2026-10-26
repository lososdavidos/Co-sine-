package native

import (
	"errors"
	"net/http"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/scrobble"
)

// CapScrobbling: plays are scrobbled to Last.fm and ListenBrainz by the
// server (§4A), and each account connects its own here.
const CapScrobbling = "scrobbling"

func (a *API) registerScrobbling(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/scrobbling", a.authed(a.scrobbling))
	mux.HandleFunc("POST /cosine/v1/scrobbling/lastfm/start", a.authed(a.lastFMStart))
	mux.HandleFunc("POST /cosine/v1/scrobbling/lastfm/finish", a.authed(a.lastFMFinish))
	mux.HandleFunc("POST /cosine/v1/scrobbling/listenbrainz", a.authed(a.listenBrainz))
	mux.HandleFunc("DELETE /cosine/v1/scrobbling/{service}", a.authed(a.disconnectScrobbler))
}

// scrobbling says where the account's plays go, and whether Last.fm can
// be connected (the server needs its own Last.fm API account for that).
func (a *API) scrobbling(w http.ResponseWriter, r *http.Request, u auth.User) {
	st, err := a.Scrobbler.Statuses(r.Context(), u.ID)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"connected": st, "lastfmAvailable": a.Scrobbler.LastFMReady(r.Context())})
}

func (a *API) lastFMStart(w http.ResponseWriter, r *http.Request, u auth.User) {
	approveAt, token, err := a.Scrobbler.LastFMStart(r.Context())
	if err != nil {
		a.scrobbleError(w, err, "Last.fm")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"url": approveAt, "token": token})
}

func (a *API) lastFMFinish(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Token string `json:"token"`
	}
	if !decode(w, r, &body) {
		return
	}
	name, err := a.Scrobbler.LastFMFinish(r.Context(), u.ID, body.Token)
	if err != nil {
		a.scrobbleError(w, err, "Last.fm")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"username": name})
}

func (a *API) listenBrainz(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Token string `json:"token"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Token == "" {
		writeError(w, http.StatusBadRequest, "Paste your ListenBrainz user token.")
		return
	}
	name, err := a.Scrobbler.ConnectListenBrainz(r.Context(), u.ID, body.Token)
	if err != nil {
		a.scrobbleError(w, err, "ListenBrainz")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"username": name})
}

func (a *API) disconnectScrobbler(w http.ResponseWriter, r *http.Request, u auth.User) {
	if err := a.Scrobbler.Disconnect(r.Context(), u.ID, r.PathValue("service")); err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (a *API) scrobbleError(w http.ResponseWriter, err error, service string) {
	switch {
	case errors.Is(err, scrobble.ErrNotSetUp):
		writeError(w, http.StatusConflict, "Last.fm isn't set up on this server yet. Whoever runs it adds a Last.fm API key in the dashboard's Settings.")
	case errors.Is(err, scrobble.ErrNotAuthorised):
		writeError(w, http.StatusConflict, "Last.fm hasn't been given access yet. Approve Cosine in the browser, then try again.")
	case errors.Is(err, scrobble.ErrRefused):
		writeError(w, http.StatusBadRequest, service+" didn't accept that token.")
	default:
		a.Log.Warn("connecting a scrobbler", "service", service, "err", err)
		writeError(w, http.StatusBadGateway, "Couldn't reach "+service+". Try again in a moment.")
	}
}
