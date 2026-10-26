package scrobble

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// ListenBrainz (listenbrainz.readthedocs.io). Each account connects with
// its own user token, from listenbrainz.org/settings; nothing to set up on
// the server.

const listenBrainzAPI = "https://api.listenbrainz.org"

// ConnectListenBrainz checks a user token and, if ListenBrainz knows it,
// connects the account with it.
func (s *Service) ConnectListenBrainz(ctx context.Context, user int64, token string) (username string, err error) {
	var out struct {
		Valid    bool   `json:"valid"`
		UserName string `json:"user_name"`
	}
	if err := s.listenBrainz(ctx, http.MethodGet, "/1/validate-token", token, nil, &out); err != nil {
		return "", err
	}
	if !out.Valid {
		return "", ErrRefused
	}
	return out.UserName, s.connect(ctx, user, ListenBrainz, token, out.UserName)
}

func (s *Service) listenBrainzSubmit(ctx context.Context, token string, plays []Play) error {
	type meta struct {
		Artist     string         `json:"artist_name"`
		Track      string         `json:"track_name"`
		Release    string         `json:"release_name,omitempty"`
		Additional map[string]any `json:"additional_info"`
	}
	type listen struct {
		At   int64 `json:"listened_at"`
		Meta meta  `json:"track_metadata"`
	}
	body := struct {
		Type    string   `json:"listen_type"`
		Payload []listen `json:"payload"`
	}{Type: "import"}
	if len(plays) == 1 {
		body.Type = "single"
	}
	for _, p := range plays {
		extra := map[string]any{"submission_client": "Cosine", "submission_client_version": s.Version}
		if p.DurationSec > 0 {
			extra["duration_ms"] = p.DurationSec * 1000
		}
		body.Payload = append(body.Payload, listen{At: p.PlayedAt.Unix(),
			Meta: meta{Artist: p.Artist, Track: p.Title, Release: p.Album, Additional: extra}})
	}
	return s.listenBrainz(ctx, http.MethodPost, "/1/submit-listens", token, body, nil)
}

func (s *Service) listenBrainz(ctx context.Context, method, path, token string, send, into any) error {
	base := s.ListenBrainzURL
	if base == "" {
		base = listenBrainzAPI
	}
	var buf bytes.Buffer
	if send != nil {
		if err := json.NewEncoder(&buf).Encode(send); err != nil {
			return err
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, &buf)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Token "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Cosine/"+s.Version)
	res, err := s.client().Do(req)
	if err != nil {
		return transient{err}
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	switch {
	case res.StatusCode == http.StatusUnauthorized:
		return ErrRefused
	case res.StatusCode == http.StatusTooManyRequests || res.StatusCode >= 500:
		return transient{fmt.Errorf("ListenBrainz answered %s", res.Status)}
	case res.StatusCode == http.StatusBadRequest:
		// Plays it won't take would block everything after them; they're
		// dropped, said in the log.
		s.Log.Warn("ListenBrainz refused plays", "body", string(data))
		return nil
	case res.StatusCode != http.StatusOK:
		return transient{fmt.Errorf("ListenBrainz answered %s", res.Status)}
	}
	if into == nil {
		return nil
	}
	return json.Unmarshal(data, into)
}
