package musicbrainz

import (
	"context"
	"errors"
	"net/url"
)

// ArtistLinks lists the pages MusicBrainz links an artist to: their site,
// Wikidata, their Deezer and Spotify pages. An artist MusicBrainz doesn't
// know has none.
func (c *Client) ArtistLinks(ctx context.Context, mbid string) ([]string, error) {
	var a struct {
		Relations []struct {
			URL struct {
				Resource string `json:"resource"`
			} `json:"url"`
		} `json:"relations"`
	}
	err := c.get(ctx, "/artist/"+url.PathEscape(mbid), url.Values{"inc": {"url-rels"}}, &a)
	if errors.Is(err, errNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var links []string
	for _, r := range a.Relations {
		if r.URL.Resource != "" {
			links = append(links, r.URL.Resource)
		}
	}
	return links, nil
}
