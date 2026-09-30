package musicbrainz

import (
	"context"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/resolve"
)

// Resolver is tier 1 of the chain. It asks MusicBrainz about whatever the
// file and its source say it is; the tier-4 reading supplies that hint.
type Resolver struct {
	Client *Client
	// Timeout bounds one lookup, so a slow MusicBrainz delays an ingest
	// rather than stalling it.
	Timeout time.Duration
}

func (r Resolver) Resolve(ctx context.Context, in resolve.Input) (resolve.Result, bool, error) {
	guess, ok, _ := resolve.SourceMetadata{}.Resolve(ctx, in)
	if !ok {
		return resolve.Result{}, false, nil
	}
	timeout := r.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return r.Client.Match(ctx, HintFrom(guess, in.DurationSec))
}

// HintFrom turns an identity into a search hint.
func HintFrom(r resolve.Result, durationSec int) Hint {
	return Hint{Artist: r.ArtistOfTrack(), Title: r.Title, Release: r.Release, DurationSec: durationSec}
}
