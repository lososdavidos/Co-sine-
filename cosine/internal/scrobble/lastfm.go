package scrobble

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

// Last.fm (last.fm/api). Its API account — key and secret — belongs to
// whoever runs the server, made once at last.fm/api/account/create and
// entered in the dashboard. Each account then connects its own Last.fm
// through the desktop flow: Cosine asks for a token, the person approves it
// in a browser, and Cosine trades it for a session key, which never expires.

const (
	lastFMAPI  = "https://ws.audioscrobbler.com/2.0/"
	lastFMAuth = "https://www.last.fm/api/auth/"
)

// Last.fm's error codes worth telling apart; any other is tried again later.
const (
	lfmAuthFailed     = 4
	lfmInvalidSession = 9
	lfmInvalidKey     = 10
	lfmUnauthorised   = 14 // the token wasn't approved
	lfmTokenExpired   = 15
	lfmSuspendedKey   = 26
)

type lastFMError struct {
	Code    int    `json:"error"`
	Message string `json:"message"`
}

func (e lastFMError) Error() string { return fmt.Sprintf("Last.fm: %s (%d)", e.Message, e.Code) }

// LastFMStart begins connecting Last.fm: the page to approve Cosine at,
// and the token to finish with once approved.
func (s *Service) LastFMStart(ctx context.Context) (approveAt, token string, err error) {
	key, secret := s.lastFMAccount(ctx)
	if key == "" {
		return "", "", ErrNotSetUp
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := s.lastFMCall(ctx, http.MethodGet, key, secret, url.Values{"method": {"auth.getToken"}}, &out); err != nil {
		return "", "", err
	}
	base := s.LastFMAuthURL
	if base == "" {
		base = lastFMAuth
	}
	return base + "?" + url.Values{"api_key": {key}, "token": {out.Token}}.Encode(), out.Token, nil
}

// LastFMFinish completes connecting: the approved token becomes the
// account's session key.
func (s *Service) LastFMFinish(ctx context.Context, user int64, token string) (username string, err error) {
	key, secret := s.lastFMAccount(ctx)
	if key == "" {
		return "", ErrNotSetUp
	}
	var out struct {
		Session struct {
			Name string `json:"name"`
			Key  string `json:"key"`
		} `json:"session"`
	}
	err = s.lastFMCall(ctx, http.MethodGet, key, secret, url.Values{"method": {"auth.getSession"}, "token": {token}}, &out)
	if e, ok := err.(lastFMError); ok && (e.Code == lfmUnauthorised || e.Code == lfmTokenExpired) {
		return "", ErrNotAuthorised
	}
	if err != nil {
		return "", err
	}
	if out.Session.Key == "" {
		return "", fmt.Errorf("Last.fm gave no session")
	}
	return out.Session.Name, s.connect(ctx, user, LastFM, out.Session.Key, out.Session.Name)
}

func (s *Service) lastFMScrobble(ctx context.Context, key, secret, session string, plays []Play) error {
	v := url.Values{"method": {"track.scrobble"}, "sk": {session}}
	for i, p := range plays {
		n := "[" + strconv.Itoa(i) + "]"
		v.Set("artist"+n, p.Artist)
		v.Set("track"+n, p.Title)
		v.Set("timestamp"+n, strconv.FormatInt(p.PlayedAt.Unix(), 10))
		if p.Album != "" {
			v.Set("album"+n, p.Album)
		}
		if p.AlbumArtist != "" && p.AlbumArtist != p.Artist {
			v.Set("albumArtist"+n, p.AlbumArtist)
		}
		if p.DurationSec > 0 {
			v.Set("duration"+n, strconv.Itoa(p.DurationSec))
		}
	}
	// Plays Last.fm ignores (too old, say) are answered, not refused: done.
	err := s.lastFMCall(ctx, http.MethodPost, key, secret, v, nil)
	if e, ok := err.(lastFMError); ok {
		switch e.Code {
		case lfmInvalidSession, lfmAuthFailed, lfmInvalidKey, lfmSuspendedKey:
			return fmt.Errorf("%w: %v", ErrRefused, e)
		}
		return transient{e}
	}
	return err
}

// lastFMCall makes a signed call: every parameter but format, sorted,
// name then value, then the secret, md5ed (last.fm/api/authspec).
func (s *Service) lastFMCall(ctx context.Context, method, key, secret string, v url.Values, into any) error {
	v.Set("api_key", key)
	names := make([]string, 0, len(v))
	for k := range v {
		names = append(names, k)
	}
	sort.Strings(names)
	var sig strings.Builder
	for _, k := range names {
		sig.WriteString(k)
		sig.WriteString(v.Get(k))
	}
	sig.WriteString(secret)
	sum := md5.Sum([]byte(sig.String()))
	v.Set("api_sig", hex.EncodeToString(sum[:]))
	v.Set("format", "json")

	base := s.LastFMURL
	if base == "" {
		base = lastFMAPI
	}
	var req *http.Request
	var err error
	if method == http.MethodPost {
		req, err = http.NewRequestWithContext(ctx, method, base, strings.NewReader(v.Encode()))
		if err == nil {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
	} else {
		req, err = http.NewRequestWithContext(ctx, method, base+"?"+v.Encode(), nil)
	}
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "Cosine/"+s.Version)
	res, err := s.client().Do(req)
	if err != nil {
		return transient{err}
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return transient{err}
	}
	var e lastFMError
	if json.Unmarshal(body, &e) == nil && e.Code != 0 {
		return e
	}
	if res.StatusCode != http.StatusOK {
		return transient{fmt.Errorf("Last.fm answered %s", res.Status)}
	}
	if into == nil {
		return nil
	}
	return json.Unmarshal(body, into)
}
