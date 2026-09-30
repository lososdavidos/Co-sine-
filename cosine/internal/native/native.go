// Package native is Cosine's own protocol, beside the Subsonic one (§2.1).
// Clients authenticate once with their Subsonic credentials and receive a
// session token (decision log: "session tokens natively").
//
// The protocol is additive only (§9.3): an existing field never changes
// meaning and an existing endpoint never changes behaviour.
package native

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/fetch"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
)

// Capability names. Sine hides whatever a server does not list.
const CapIngest = "ingest"

type API struct {
	Auth    *auth.Service
	Fetcher *fetch.Fetcher // nil when yt-dlp is unavailable
	Review  *review.Service
	Version string
	Log     *slog.Logger
}

func (a *API) capabilities() []string {
	caps := []string{}
	if a.Fetcher != nil && a.Fetcher.Runner.Available() {
		caps = append(caps, CapIngest)
	}
	if a.Review != nil {
		caps = append(caps, CapReview)
	}
	return caps
}

// Register mounts the API under /cosine/v1/.
func (a *API) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/capabilities", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"version": a.Version, "capabilities": a.capabilities()})
	})
	mux.HandleFunc("POST /cosine/v1/auth", a.login)
	mux.HandleFunc("POST /cosine/v1/lookup", a.authed(a.ingest(a.lookup)))
	mux.HandleFunc("POST /cosine/v1/ingest", a.authed(a.ingest(a.submit)))
	mux.HandleFunc("GET /cosine/v1/ingest/jobs", a.authed(a.ingest(a.jobs)))
	mux.HandleFunc("POST /cosine/v1/ingest/jobs/{id}/retry", a.authed(a.ingest(a.retry)))
	if a.Review != nil {
		a.registerReview(mux)
	}
}

type handler func(w http.ResponseWriter, r *http.Request, u auth.User)

func (a *API) authed(h handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			writeError(w, http.StatusUnauthorized, "Missing bearer token.")
			return
		}
		u, err := a.Auth.Session(r.Context(), token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "Session expired. Authenticate again.")
			return
		}
		h(w, r, u)
	}
}

func (a *API) ingest(h handler) handler {
	return func(w http.ResponseWriter, r *http.Request, u auth.User) {
		if a.Fetcher == nil || !a.Fetcher.Runner.Available() {
			writeError(w, http.StatusNotImplemented, "Ingest is unavailable: yt-dlp is not installed on the server.")
			return
		}
		h(w, r, u)
	}
}

// login exchanges Subsonic credentials for a session token.
func (a *API) login(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Username string `json:"username"`
		Token    string `json:"token"`
		Salt     string `json:"salt"`
	}
	if !decode(w, r, &body) {
		return
	}
	u, err := a.Auth.CheckSubsonic(r.Context(), body.Username, body.Token, body.Salt, "")
	if err != nil {
		writeError(w, http.StatusUnauthorized, "Wrong username or password.")
		return
	}
	token, err := a.Auth.NewSession(r.Context(), u.ID)
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": token})
}

func (a *API) lookup(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		Input string `json:"input"`
	}
	if !decode(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.Input) == "" {
		writeError(w, http.StatusBadRequest, "Paste a link or type a search.")
		return
	}
	res, err := a.Fetcher.Lookup(r.Context(), u.ID, body.Input)
	if err != nil {
		writeError(w, http.StatusBadGateway, "Couldn't fetch — "+err.Error())
		return
	}
	if res.Items == nil {
		res.Items = []fetch.Item{}
	}
	writeJSON(w, http.StatusOK, res)
}

func (a *API) submit(w http.ResponseWriter, r *http.Request, u auth.User) {
	var body struct {
		URLs  []string `json:"urls"`
		Force bool     `json:"force"`
	}
	if !decode(w, r, &body) {
		return
	}
	jobs, err := a.Fetcher.Submit(r.Context(), u.ID, body.URLs, body.Force)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"jobs": jobs})
}

func (a *API) jobs(w http.ResponseWriter, r *http.Request, u auth.User) {
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 || limit > 200 {
		limit = 50
	}
	jobs, err := a.Fetcher.Jobs(r.Context(), u.ID, limit)
	if err != nil {
		a.fail(w, err)
		return
	}
	if jobs == nil {
		jobs = []fetch.Job{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": jobs})
}

func (a *API) retry(w http.ResponseWriter, r *http.Request, u auth.User) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeError(w, http.StatusNotFound, "No such job.")
		return
	}
	var body struct {
		Force bool `json:"force"`
	}
	if r.ContentLength != 0 && !decode(w, r, &body) {
		return
	}
	job, err := a.Fetcher.Retry(r.Context(), u.ID, id, body.Force)
	if err != nil {
		writeError(w, http.StatusConflict, err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"job": job})
}

func (a *API) fail(w http.ResponseWriter, err error) {
	a.Log.Error("native api", "err", err)
	writeError(w, http.StatusInternalServerError, "Server error.")
}

func decode(w http.ResponseWriter, r *http.Request, into any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	if err := dec.Decode(into); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "Request too large.")
		} else {
			writeError(w, http.StatusBadRequest, "Malformed JSON.")
		}
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
