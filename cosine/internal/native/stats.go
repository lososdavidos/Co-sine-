package native

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/stats"
)

// CapStats: listening statistics and the views Home tiles and smart
// playlists are built from (§6.13, §6.4.1). Always one's own listening.
const CapStats = "stats"

func (a *API) registerStats(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/stats", a.authed(a.statsReport))
	mux.HandleFunc("GET /cosine/v1/stats/years", a.authed(a.statsYears))
	mux.HandleFunc("GET /cosine/v1/views", a.authed(a.viewList))
	mux.HandleFunc("GET /cosine/v1/views/{name}", a.authed(a.view))
}

// statsReport: ?window=30d|year|2025|all (default 30d), ?limit=1..100 (default 10).
func (a *API) statsReport(w http.ResponseWriter, r *http.Request, u auth.User) {
	key := r.URL.Query().Get("window")
	if key == "" {
		key = "30d"
	}
	win, err := stats.ParseWindow(key, a.Stats.Clock())
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 10
	}
	sc := stats.Scope{User: u.ID}
	rep, err := a.Stats.Report(r.Context(), sc, win, limit)
	if err != nil {
		a.fail(w, err)
		return
	}
	windows, err := a.Stats.Windows(r.Context(), sc)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"report": rep, "windows": windows})
}

func (a *API) statsYears(w http.ResponseWriter, r *http.Request, u auth.User) {
	years, err := a.Stats.Years(r.Context(), stats.Scope{User: u.ID})
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"years": years})
}

func (a *API) viewList(w http.ResponseWriter, r *http.Request, u auth.User) {
	writeJSON(w, http.StatusOK, map[string]any{"views": stats.Views})
}

// view: ?limit=1..500, ?window= for most-played, ?months= for forgotten.
func (a *API) view(w http.ResponseWriter, r *http.Request, u auth.User) {
	q := r.URL.Query()
	opt := stats.ViewOptions{}
	opt.Limit, _ = strconv.Atoi(q.Get("limit"))
	opt.Months, _ = strconv.Atoi(q.Get("months"))
	if key := q.Get("window"); key != "" {
		win, err := stats.ParseWindow(key, a.Stats.Clock())
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		opt.Window = win
	}
	tracks, err := a.Stats.View(r.Context(), u.ID, r.PathValue("name"), opt)
	if errors.Is(err, stats.ErrView) {
		writeError(w, http.StatusNotFound, "No such view.")
		return
	}
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tracks": tracks})
}
