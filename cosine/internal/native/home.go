package native

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/homes"
)

// CapHome: Home's arrangement is kept here, one per account and kind of
// device (§6.4).
const CapHome = "home-layout"

func (a *API) registerHome(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/home/{device}", a.authed(a.home))
	mux.HandleFunc("PUT /cosine/v1/home/{device}", a.authed(a.saveHome))
}

// home answers with the arrangement as last sent: {"layout": null} when
// there is none yet, and Sine shows its defaults.
func (a *API) home(w http.ResponseWriter, r *http.Request, u auth.User) {
	layout, err := a.Homes.Layout(r.Context(), u.ID, r.PathValue("device"))
	if errors.Is(err, homes.ErrDevice) {
		writeError(w, http.StatusNotFound, "No such kind of device.")
		return
	}
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"layout": layout})
}

func (a *API) saveHome(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Layout json.RawMessage `json:"layout"`
	}
	if !decode(w, r, &body) {
		return
	}
	switch err := a.Homes.Save(r.Context(), u.ID, r.PathValue("device"), body.Layout); {
	case errors.Is(err, homes.ErrDevice):
		writeError(w, http.StatusNotFound, "No such kind of device.")
	case errors.Is(err, homes.ErrLayout):
		writeError(w, http.StatusBadRequest, "A layout is a JSON object of at most 64 KB.")
	case err != nil:
		a.fail(w, err)
	default:
		writeJSON(w, http.StatusOK, map[string]any{"layout": body.Layout})
	}
}
