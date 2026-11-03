package native

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/auth"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/review"
)

// CapRemove: taking tracks out of your Library (§4.1).
const CapRemove = "remove"

// CapUpload: a file from the phone, filed like any other (§4).
const CapUpload = "upload"

// maxUpload: a long live set in lossless is well under this.
const maxUpload = 2 << 30

func (a *API) registerLibrary(mux *http.ServeMux) {
	mux.HandleFunc("POST /cosine/v1/library/remove", a.authed(a.remove))
	mux.HandleFunc("POST /cosine/v1/library/upload", a.authed(a.upload))
	mux.HandleFunc("POST /cosine/v1/library/add", a.authed(a.addToLibrary))
}

// addToLibrary puts songs already in the Store into the caller's Library:
// one heard in a jam, say (§4C). Asked for, so a removal marker goes.
func (a *API) addToLibrary(w http.ResponseWriter, r *http.Request, u auth.User) {
	var req removeRequest
	if !decode(w, r, &req) {
		return
	}
	n := 0
	for _, t := range req.Tracks {
		id, ok := review.ParseTrack(t)
		if !ok || a.Jams == nil || !a.Jams.InMine(r.Context(), u.ID, id) {
			continue // only a song heard in your jam: never any id in the Store
		}
		if err := a.Ingester.Undismiss(r.Context(), id, []int64{u.ID}); err != nil {
			a.fail(w, err)
			return
		}
		if err := a.Ingester.GrantPointers(r.Context(), id, []int64{u.ID}); err != nil {
			a.fail(w, err)
			return
		}
		n++
	}
	writeJSON(w, http.StatusOK, map[string]int{"added": n})
}

// upload takes one audio file, its bytes as the body and its name in
// ?name=, and ingests it like any other source (§4): resolved, filed into
// the canonical tree byte for byte, and put in the uploader's Library only,
// not everyone's as an Inbox drop is. It finishes even if the phone stops
// listening, and answers with the song's id.
func (a *API) upload(w http.ResponseWriter, r *http.Request, u auth.User) {
	name := filepath.Base(strings.TrimSpace(r.URL.Query().Get("name")))
	if name == "" || name == "." || name == string(filepath.Separator) {
		writeError(w, http.StatusBadRequest, "An upload needs its file's name.")
		return
	}
	dir := filepath.Join(a.Ingester.DataDir, "uploads")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		a.fail(w, err)
		return
	}
	f, err := os.CreateTemp(dir, "upload-*"+filepath.Ext(name))
	if err != nil {
		a.fail(w, err)
		return
	}
	// Filed, the file has moved into the Store; anything left is a failure's.
	defer os.Remove(f.Name())
	_, err = io.Copy(f, http.MaxBytesReader(w, r.Body, maxUpload))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, "The upload didn't arrive whole.")
		return
	}
	out, err := a.Ingester.Ingest(context.WithoutCancel(r.Context()), ingest.Request{
		Path: f.Name(), OriginalName: name, Source: "upload", ForUsers: []int64{u.ID}, Explicit: true,
	})
	if errors.Is(err, ingest.ErrNotAudio) {
		writeError(w, http.StatusUnsupportedMediaType, "Not an audio file Cosine can keep.")
		return
	}
	if err != nil {
		a.fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"track": "tr-" + strconv.FormatInt(out.TrackID, 10), "duplicate": out.Duplicate})
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
