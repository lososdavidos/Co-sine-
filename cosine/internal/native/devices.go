package native

import (
	"errors"
	"net/http"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/devices"
)

// CapSessions: each running client is a Device with its own queue and
// position, kept here, so playback picks up on another (§4A, §4B).
const CapSessions = "sessions"

func (a *API) registerDevices(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/devices", a.authed(a.devicesPlaying))
	mux.HandleFunc("PUT /cosine/v1/devices/{id}", a.authed(a.deviceSeen))
	mux.HandleFunc("DELETE /cosine/v1/devices/{id}", a.authed(a.deviceForget))
	mux.HandleFunc("GET /cosine/v1/devices/{id}/session", a.authed(a.deviceSession))
	mux.HandleFunc("PUT /cosine/v1/devices/{id}/session", a.authed(a.deviceSave))
}

type deviceWire struct {
	Name string `json:"name"`
	Kind string `json:"kind"`
}

// devicesPlaying: the account's Devices with a session, the most recently
// changed first, each with the song it's at.
func (a *API) devicesPlaying(w http.ResponseWriter, r *http.Request, u auth.User) {
	now, err := a.Devices.Playing(r.Context(), u.ID)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": now})
}

func (a *API) deviceSeen(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body deviceWire
	if !decode(w, r, &body) {
		return
	}
	if _, err := a.Devices.Seen(r.Context(), u.ID, devices.Device{ID: r.PathValue("id"), Name: body.Name, Kind: body.Kind}); err != nil {
		a.deviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) deviceForget(w http.ResponseWriter, r *http.Request, u auth.User) {
	if err := a.Devices.Forget(r.Context(), u.ID, r.PathValue("id")); err != nil {
		a.deviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) deviceSession(w http.ResponseWriter, r *http.Request, u auth.User) {
	s, err := a.Devices.Session(r.Context(), u.ID, r.PathValue("id"))
	if err != nil {
		a.deviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s)
}

// deviceSave keeps what a Device is playing; the Device is named in the
// body as well, so its first save registers it.
func (a *API) deviceSave(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		deviceWire
		devices.Session
	}
	if !decode(w, r, &body) {
		return
	}
	d := devices.Device{ID: r.PathValue("id"), Name: body.Name, Kind: body.Kind}
	if err := a.Devices.Save(r.Context(), u.ID, d, body.Session); err != nil {
		a.deviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) deviceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, devices.ErrNotFound):
		writeError(w, http.StatusNotFound, "No such device, or nothing played on it.")
	case errors.Is(err, devices.ErrDevice):
		writeError(w, http.StatusBadRequest, err.Error())
	default:
		a.fail(w, err)
	}
}
