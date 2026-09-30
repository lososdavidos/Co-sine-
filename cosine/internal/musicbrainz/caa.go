package musicbrainz

import (
	"context"
	"io"
	"mime"
	"net/http"
	"strings"
)

// CoverArt fetches release covers from the Cover Art Archive, the first
// source in the artwork chain (§3.2a).
type CoverArt struct {
	HTTP      *http.Client
	UserAgent string
	BaseURL   string // default https://coverartarchive.org
}

const maxCover = 10 << 20

// Front returns the release's front cover (500px), falling back to its
// release group's. ext is "jpg" or "png"; nil data means no cover.
func (c CoverArt) Front(ctx context.Context, releaseID, releaseGroupID string) ([]byte, string) {
	base := c.BaseURL
	if base == "" {
		base = "https://coverartarchive.org"
	}
	base = strings.TrimRight(base, "/")
	for _, path := range []string{"/release/" + releaseID, "/release-group/" + releaseGroupID} {
		if strings.HasSuffix(path, "/") {
			continue
		}
		if data, ext := c.get(ctx, base+path+"/front-500"); data != nil {
			return data, ext
		}
	}
	return nil, ""
}

func (c CoverArt) get(ctx context.Context, u string) ([]byte, string) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, ""
	}
	req.Header.Set("User-Agent", c.UserAgent)
	res, err := c.HTTP.Do(req)
	if err != nil {
		return nil, ""
	}
	defer res.Body.Close()
	ct, _, _ := mime.ParseMediaType(res.Header.Get("Content-Type"))
	ext := map[string]string{"image/jpeg": "jpg", "image/png": "png"}[ct]
	if res.StatusCode != http.StatusOK || ext == "" {
		return nil, ""
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxCover+1))
	if err != nil || len(data) > maxCover {
		return nil, ""
	}
	return data, ext
}
