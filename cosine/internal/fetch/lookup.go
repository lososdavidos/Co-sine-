package fetch

import (
	"context"
	"errors"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/search"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

// Lookup kinds.
const (
	KindSingle     = "single"
	KindCollection = "collection"
	KindSearch     = "search"
)

// Item is one thing that can be added.
type Item struct {
	search.Result
	InLibrary bool `json:"inLibrary"`
}

// Lookup is what Add's single field resolves to (§6.10): a link to one
// track, a collection to queue whole or pick from, or search results.
type Lookup struct {
	Kind    string `json:"kind"`
	Title   string `json:"title,omitempty"`
	Backend string `json:"backend,omitempty"` // for searches: which backend answered
	Items   []Item `json:"items"`
}

const searchResults = 10

func (f *Fetcher) Lookup(ctx context.Context, userID int64, input string) (Lookup, error) {
	if !f.Runner.Available() {
		return Lookup{}, errors.New("yt-dlp is not installed on the server")
	}
	if url, ok := search.LooksLikeURL(input); ok {
		info, err := f.Runner.Probe(ctx, url)
		if err != nil {
			return Lookup{}, err
		}
		l := Lookup{Kind: KindSingle, Title: info.Title}
		if info.IsCollection() {
			l.Kind = KindCollection
		}
		seen := map[string]bool{}
		for _, e := range collectionOrSelf(info) {
			// A playlist can hold the same track twice; offer it once.
			if seen[e.Link()] {
				continue
			}
			seen[e.Link()] = true
			l.Items = append(l.Items, Item{Result: search.Result{
				Title: firstNonEmpty(e.Title, e.Link()), Uploader: e.Poster(), URL: e.Link(),
				Thumbnail: e.CoverURL(), Source: sourceName(e.Source()),
				DurationSec: int(e.Duration + 0.5), Plays: e.ViewCount,
			}})
		}
		f.markLibrary(ctx, userID, l.Items)
		return l, nil
	}

	res, backend, err := f.Search.Search(ctx, input, searchResults)
	if err != nil {
		return Lookup{}, err
	}
	l := Lookup{Kind: KindSearch, Backend: backend}
	for _, r := range res {
		l.Items = append(l.Items, Item{Result: r})
	}
	f.markLibrary(ctx, userID, l.Items)
	return l, nil
}

func (f *Fetcher) markLibrary(ctx context.Context, userID int64, items []Item) {
	links := make([]string, len(items))
	for i, it := range items {
		links[i] = it.URL
	}
	have := f.InLibrary(ctx, userID, links)
	for i := range items {
		items[i].InLibrary = have[items[i].URL]
	}
}

func collectionOrSelf(info ytdlp.Info) []ytdlp.Info {
	if info.IsCollection() {
		return info.Entries
	}
	return []ytdlp.Info{info}
}

// sourceName turns yt-dlp's extractor key ("Soundcloud", "Youtube") into a label.
func sourceName(extractor string) string {
	return strings.ToLower(strings.TrimSuffix(extractor, "Tab"))
}
