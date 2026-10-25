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
	return resolve.Lookup{Matcher: r.Client, Timeout: r.Timeout}.Resolve(ctx, in)
}

// HintFrom turns an identity into a search hint.
func HintFrom(r resolve.Result, durationSec int) Hint { return resolve.HintFrom(r, durationSec) }
