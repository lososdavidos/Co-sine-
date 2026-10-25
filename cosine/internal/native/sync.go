package native

import (
	"net/http"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
)

// CapSync: the library mirror (§5.2, G2). A client with it keeps the
// library locally and asks only for what changed.
const CapSync = "sync"

func (a *API) registerSync(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/sync", a.authed(a.sync))
}

// sync answers ?after=<cursor> with what changed since, or the whole
// library for after=0 (and for a cursor this server never issued).
func (a *API) sync(w http.ResponseWriter, r *http.Request, u auth.User) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	changes, err := a.Mirror.Since(r.Context(), u.ID, max(after, 0))
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, changes)
}
