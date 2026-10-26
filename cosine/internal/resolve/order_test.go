package resolve

import (
	"strings"
	"testing"
)

func releases(rs []Result) string {
	var out []string
	for _, r := range rs {
		out = append(out, r.Release)
	}
	return strings.Join(out, ", ")
}

func TestTheFirstReleaseIsTheOriginal(t *testing.T) {
	// "Timber" as Discogs had it: a greatest hits four years on, which
	// looks like an album and gives the track's length, ahead of the EP
	// and the single it first came out on, which don't.
	song := func(release string, year, kind, length int, conf float64) Placed {
		return Placed{Result: Result{Title: "Timber", Release: release, Year: year, Confidence: conf},
			Home: Home{Kind: kind, Year: year}, LengthSec: length}
	}
	hits := song("Greatest Hits", 2017, -30, 204, 1)
	hits.Home.Compiled = true
	got := Order([]Placed{
		hits,
		song("Meltdown EP", 2013, -20, 0, 0.92),
		song("Timber", 2013, -10, 0, 0.92),
	})
	if releases(got) != "Meltdown EP, Timber, Greatest Hits" {
		t.Fatal(releases(got))
	}
	// Each carries the song's best score, a little less a place down.
	if got[0].Confidence != 1 || got[1].Confidence != 0.98 || got[2].Confidence != 0.96 {
		t.Fatalf("%+v", got)
	}

	// A lead single out before its album is the song's first release, and
	// wins ("Closer" by the Chainsmokers); a reissue goes last.
	got = Order([]Placed{
		song("Global Warming: Meltdown", 2013, -30, 204, 1),
		song("Timber", 2012, -15, 204, 1),
		song("Global Warming: Meltdown (2019 Reissue)", 2019, -30, 204, 1),
	})
	if releases(got) != "Timber, Global Warming: Meltdown, Global Warming: Meltdown (2019 Reissue)" {
		t.Fatal(releases(got))
	}
	// Known to the day, the earlier goes first even in one year; out the
	// same day, or known only by the year, the album beats the single.
	dated := func(release, date string, kind int) Placed {
		p := song(release, 2013, kind, 204, 1)
		p.Home.Date = date
		return p
	}
	got = Order([]Placed{dated("Meltdown", "2013-11-22", -30), dated("Timber", "2013-10-07", -15), dated("Meltdown (Deluxe)", "2013", -30)})
	if releases(got) != "Timber, Meltdown, Meltdown (Deluxe)" {
		t.Fatal(releases(got))
	}
	got = Order([]Placed{dated("bad guy", "2019-03-29", -15), dated("When We All Fall Asleep", "2019-03-29", -30)})
	if releases(got) != "When We All Fall Asleep, bad guy" {
		t.Fatal(releases(got))
	}
	// The artist's own release beats a compilation, however much earlier:
	// a compilation only says the song was out by then.
	comp := song("Cruisin' Classics Vol. V", 1990, -5, 212, 1)
	comp.Home.Compiled = true
	got = Order([]Placed{comp, song("September", 1978, -15, 215, 1), song("Now, Then & Forever", 2013, -30, 217, 1)})
	if releases(got) != "September, Now, Then & Forever, Cruisin' Classics Vol. V" {
		t.Fatal(releases(got))
	}
	got = Order([]Placed{comp, song("Now, Then & Forever", 2013, -30, 217, 1)})
	if releases(got) != "Now, Then & Forever, Cruisin' Classics Vol. V" {
		t.Fatal(releases(got))
	}

	// The album the file names comes first whenever it came out; an
	// official release before a bootleg however early.
	named := song("Greatest Hits", 2017, -30, 204, 1)
	named.Home.Named = true
	bootleg := song("Live in Miami", 2011, -30, 204, 1)
	bootleg.Home.Unofficial = true
	got = Order([]Placed{song("Global Warming: Meltdown", 2013, -30, 204, 1), bootleg, named})
	if releases(got) != "Greatest Hits, Global Warming: Meltdown, Live in Miami" {
		t.Fatal(releases(got))
	}
}

func TestAnotherSongOfTheNameKeepsItsScore(t *testing.T) {
	// Two songs called "Intro": a known difference in length tells them
	// apart, so the earlier one doesn't take the better match's place.
	got := Order([]Placed{
		{Result: Result{Title: "Intro", Release: "Second Album", Year: 2015, Confidence: 1}, Home: Home{Kind: -30, Year: 2015}, LengthSec: 95},
		{Result: Result{Title: "Intro", Release: "First Album", Year: 2010, Confidence: 0.94}, Home: Home{Kind: -30, Year: 2010}, LengthSec: 62},
	})
	if releases(got) != "Second Album, First Album" || got[1].Confidence != 0.94 {
		t.Fatalf("%+v", got)
	}
	// Nor does a weaker match of the same title.
	got = Order([]Placed{
		{Result: Result{Title: "Tides", Release: "Tides", Year: 2019, Confidence: 0.95}, Home: Home{Kind: -10, Year: 2019}},
		{Result: Result{Title: "Tides", Release: "Waves", Year: 2012, Confidence: 0.7}, Home: Home{Kind: -30, Year: 2012}},
	})
	if releases(got) != "Tides, Waves" {
		t.Fatal(releases(got))
	}
}
