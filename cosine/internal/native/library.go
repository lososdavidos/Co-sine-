package native

import (
	"net/http"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
)

// CapRemove: taking tracks out of your Library (§4.1).
const CapRemove = "remove"

func (a *API) registerLibrary(mux *http.ServeMux) {
	mux.HandleFunc("POST /cosine/v1/library/remove", a.authed(a.remove))
}

type removeRequest struct {
	Tracks []string `json:"tracks"`
}

// remove takes tracks out of the caller's Library. They stay removed: the
// Inbox won't hand them back, though adding one again on purpose will.
func (a *API) remove(w http.ResponseWriter, r *http.Request, u auth.User) {
	var req removeRequest
	if !decode(w, r, &req) {
		return
	}
	var ids []int64
	for _, t := range req.Tracks {
		if id, ok := review.ParseTrack(t); ok {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		writeError(w, http.StatusBadRequest, "No tracks to remove.")
		return
	}
	n, err := a.Ingester.Dismiss(r.Context(), u.ID, ids)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"removed": n})
}
