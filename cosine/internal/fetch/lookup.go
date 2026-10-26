package fetch

import (
	"context"
	"errors"
	"slices"
	"strings"
	"sync"

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
	// For a collection: what it is (album, playlist or channel), who
	// released an album, and the URL to name when adding songs from it.
	Collection string `json:"collection,omitempty"`
	Artist     string `json:"artist,omitempty"`
	URL        string `json:"url,omitempty"`
	// Collections: for a search, the albums and playlists found, those
	// asked for first. Picking one looks its link up.
	Collections []Item `json:"collections,omitempty"`
	// Partial: what a search has found so far; more is coming.
	Partial bool `json:"partial,omitempty"`
}

const searchResults = 10

func (f *Fetcher) Lookup(ctx context.Context, userID int64, input string) (Lookup, error) {
	return f.LookupEach(ctx, userID, input, nil)
}

// LookupEach is Lookup, showing a search as it goes: update, when set, is
// given everything found so far (Partial) each time a source answers, one
// call at a time. The answer returned is the whole of it.
func (f *Fetcher) LookupEach(ctx context.Context, userID int64, input string, update func(Lookup)) (Lookup, error) {
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
			c := collectionOf(info, url)
			f.remember(c, url)
			l.Kind, l.Title, l.Collection, l.Artist, l.URL = KindCollection, c.Title, c.Kind, c.Artist, c.URL
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
				Preview: e.PreviewOnly() || search.LooksLikePreview(sourceName(e.Source()), e.Duration),
			}})
		}
		f.markLibrary(ctx, userID, l.Items)
		return l, nil
	}

	var (
		mu          sync.Mutex
		collections []search.Result
		songs       []search.Result
		released    *search.Result
	)
	// lookup puts together what has been found; mu is held.
	lookup := func(backend string) Lookup {
		l := Lookup{Kind: KindSearch, Backend: backend}
		for _, c := range collections {
			l.Collections = append(l.Collections, Item{Result: c})
		}
		// The song as released first; videos with intros, extended uploads
		// and previews (which can't be added) after.
		for _, r := range search.ByRelease(slices.Clone(songs), released) {
			l.Items = append(l.Items, Item{Result: r})
		}
		f.markLibrary(ctx, userID, l.Items)
		return l
	}
	show := func() {
		if update != nil {
			l := lookup("")
			l.Partial = true
			update(l)
		}
	}
	var wg sync.WaitGroup
	wg.Go(func() {
		c := f.Search.Collections(ctx, input, func(c []search.Result) {
			mu.Lock()
			defer mu.Unlock()
			collections = c
			show()
		})
		mu.Lock()
		collections = c
		mu.Unlock()
	})
	wg.Go(func() {
		if r, ok := f.Search.Release(ctx, input); ok {
			mu.Lock()
			defer mu.Unlock()
			released = &r
			if len(songs) > 0 {
				show()
			}
		}
	})
	res, backend, err := f.Search.Search(ctx, input, searchResults, func(r []search.Result) {
		mu.Lock()
		defer mu.Unlock()
		songs = r
		show()
	})
	wg.Wait()
	if err != nil && len(collections) == 0 {
		return Lookup{}, err
	}
	songs = res
	return lookup(backend), nil
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
