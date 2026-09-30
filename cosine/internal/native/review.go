package native

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/musicbrainz"
	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
)

// CapReview: the review queue and corrections (§6.12).
const CapReview = "review"

// Candidate is one possible identity, labelled by origin so a person can
// see when two sources agree (§6.12). Choose it by its key.
type Candidate struct {
	Key string `json:"key"`
	resolve.Result
}

func (a *API) registerReview(mux *http.ServeMux) {
	mux.HandleFunc("GET /cosine/v1/review", a.authed(a.reviewQueue))
	mux.HandleFunc("GET /cosine/v1/review/{track}", a.authed(a.withTrack(a.reviewItem)))
	mux.HandleFunc("POST /cosine/v1/review/{track}/candidates", a.authed(a.withTrack(a.reviewCandidates)))
	mux.HandleFunc("POST /cosine/v1/review/{track}/choose", a.authed(a.withTrack(a.reviewChoose)))
	mux.HandleFunc("POST /cosine/v1/review/{track}/correct", a.authed(a.withTrack(a.reviewCorrect)))
	mux.HandleFunc("POST /cosine/v1/review/{track}/confirm", a.authed(a.withTrack(a.reviewConfirm)))
}

type trackHandler func(w http.ResponseWriter, r *http.Request, u auth.User, track int64)

func (a *API) withTrack(h trackHandler) handler {
	return func(w http.ResponseWriter, r *http.Request, u auth.User) {
		id, ok := review.ParseTrack(r.PathValue("track"))
		if !ok {
			writeError(w, http.StatusNotFound, "No such track.")
			return
		}
		h(w, r, u, id)
	}
}

func (a *API) reviewErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, review.ErrNotFound):
		writeError(w, http.StatusNotFound, "No such track in your library.")
	case errors.Is(err, musicbrainz.ErrUnavailable):
		writeError(w, http.StatusBadGateway, "MusicBrainz is unavailable. You can still type the details yourself.")
	default:
		writeError(w, http.StatusBadRequest, err.Error())
	}
}

func (a *API) reviewQueue(w http.ResponseWriter, r *http.Request, u auth.User) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	items, total, err := a.Review.Queue(r.Context(), u, limit, max(offset, 0))
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"total": total, "items": items})
}

func (a *API) reviewItem(w http.ResponseWriter, r *http.Request, u auth.User, track int64) {
	it, err := a.Review.Item(r.Context(), u, track)
	if err != nil {
		a.reviewErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}

func (a *API) reviewCandidates(w http.ResponseWriter, r *http.Request, u auth.User, track int64) {
	var q review.Query
	if r.ContentLength != 0 && !decode(w, r, &q) {
		return
	}
	res, err := a.Review.Candidates(r.Context(), u, track, q)
	if err != nil {
		a.reviewErr(w, err)
		return
	}
	out := make([]Candidate, 0, len(res))
	for _, c := range res {
		out = append(out, Candidate{Key: review.CandidateKey(c), Result: c})
	}
	writeJSON(w, http.StatusOK, map[string]any{"candidates": out})
}

func (a *API) reviewChoose(w http.ResponseWriter, r *http.Request, u auth.User, track int64) {
	var body struct {
		Query review.Query `json:"query"`
		Key   string       `json:"key"`
	}
	if !decode(w, r, &body) {
		return
	}
	it, err := a.Review.Choose(r.Context(), u, track, body.Query, body.Key)
	if err != nil {
		a.reviewErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}

func (a *API) reviewCorrect(w http.ResponseWriter, r *http.Request, u auth.User, track int64) {
	var body struct {
		Artist      string `json:"artist"`
		Release     string `json:"release"`
		Title       string `json:"title"`
		TrackArtist string `json:"trackArtist"`
		TrackNo     int    `json:"trackNo"`
		DiscNo      int    `json:"discNo"`
		Year        int    `json:"year"`
	}
	if !decode(w, r, &body) {
		return
	}
	it, err := a.Review.Correct(r.Context(), u, track, resolve.Result{
		Artist: body.Artist, Release: body.Release, Title: body.Title, TrackArtist: body.TrackArtist,
		TrackNo: body.TrackNo, DiscNo: body.DiscNo, Year: body.Year,
	})
	if err != nil {
		a.reviewErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}

func (a *API) reviewConfirm(w http.ResponseWriter, r *http.Request, u auth.User, track int64) {
	it, err := a.Review.Confirm(r.Context(), u, track)
	if err != nil {
		a.reviewErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}
