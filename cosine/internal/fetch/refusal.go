package fetch

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"
)

// A site that thinks an address asks too often refuses it for a while:
// YouTube answers HTTP 403 or 429, or wants a sign-in "to confirm you're
// not a bot". A large album or playlist runs into it part way (#28). It
// passes, so a refusal is not a failure: every download waits out a pause,
// longer with each refusal in a row, and the refused job goes back in the
// queue. Only after refusedTries refusals of the same job does it fail.
const refusedTries = 6

// firstPause is the pause after a first refusal; each one after it doubles
// it, to firstPause × 2^(refusedTries-2) (16 minutes) before the last try.
var firstPause = time.Minute

var refusalSigns = []string{
	"http error 403", "http error 429", "too many requests",
	"confirm you're not a bot", "confirm you’re not a bot", "rate-limited", "rate limit",
}

// refused reports whether err is a site refusing for now, not for good.
func refused(err error) bool {
	s := strings.ToLower(err.Error())
	for _, sign := range refusalSigns {
		if strings.Contains(s, sign) {
			return true
		}
	}
	return false
}

// pause is the wait every download keeps after a refusal.
type pause struct {
	mu    sync.Mutex
	until time.Time
	run   int // refusals in a row, across jobs
	tries map[int64]int
}

// refused records a refusal of job and returns how long downloads now
// wait, or false when the job has been refused too often to try again.
// Refusals of downloads that were already running when the pause began
// don't lengthen it.
func (p *pause) refused(job int64) (time.Duration, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.tries == nil {
		p.tries = map[int64]int{}
	}
	p.tries[job]++
	if p.tries[job] >= refusedTries {
		delete(p.tries, job)
		return 0, false
	}
	now := time.Now()
	if now.Before(p.until) {
		return p.until.Sub(now), true
	}
	wait := firstPause << min(p.run, refusedTries-2)
	p.run++
	p.until = now.Add(wait)
	return wait, true
}

// done records a job that wasn't refused: the site is answering again.
func (p *pause) done(job int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.tries, job)
	p.run = 0
}

// wait returns once no pause is in effect, or false if ctx ends first.
func (p *pause) wait(ctx context.Context) bool {
	for {
		p.mu.Lock()
		left := time.Until(p.until)
		p.mu.Unlock()
		if left <= 0 {
			return true
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(left):
		}
	}
}

// inAWhile says a wait as a person would: "in 2 minutes".
func inAWhile(d time.Duration) string {
	if d < time.Minute {
		return "in a moment"
	}
	if m := int(d.Round(time.Minute) / time.Minute); m > 1 {
		return fmt.Sprintf("in %d minutes", m)
	}
	return "in a minute"
}
