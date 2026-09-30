package resolve

import (
	"context"
	"testing"
)

func TestParseFilename(t *testing.T) {
	cases := []struct {
		in   string
		want NameParts
	}{
		{"Skeler - Tides.mp3", NameParts{Artist: "Skeler", Title: "Tides"}},
		{"03 barnacle boi - drift (Official Audio).opus", NameParts{Artist: "barnacle boi", Title: "drift", TrackNo: 3}},
		{"Deadcrow – Hollow VIP [dQw4w9WgXcQ].webm", NameParts{Artist: "Deadcrow", Title: "Hollow VIP"}},
		{"untitled_rip.m4a", NameParts{Title: "untitled rip"}},
		{"1. plenka - glass.flac", NameParts{Artist: "plenka", Title: "glass", TrackNo: 1}},
	}
	for _, c := range cases {
		if got := ParseFilename(c.in); got != c.want {
			t.Errorf("ParseFilename(%q) = %+v, want %+v", c.in, got, c.want)
		}
	}
}

func TestSourceMetadataPrefersTags(t *testing.T) {
	in := Input{
		OriginalName: "whatever - nope.mp3",
		Tags:         &Tags{Title: "Tides", Artist: "Skeler", Album: "Tides EP", Track: 2},
	}
	r, ok, _ := SourceMetadata{}.Resolve(context.Background(), in)
	if !ok || r.Artist != "Skeler" || r.Title != "Tides" || r.Release != "Tides EP" || r.TrackNo != 2 {
		t.Fatalf("%+v", r)
	}
	if r.Confidence != 0.5 || r.Tier != TierSource {
		t.Fatalf("confidence/tier: %+v", r)
	}
}

func TestSingleGetsReleaseNamedAfterItself(t *testing.T) {
	r, ok, _ := SourceMetadata{}.Resolve(context.Background(), Input{OriginalName: "rxrrim - loner.opus"})
	if !ok || r.Release != "loner" || r.Artist != "rxrrim" || r.Source != "filename" {
		t.Fatalf("%+v", r)
	}
}

func TestTier4AlwaysNeedsReview(t *testing.T) {
	if !NeedsReview(Result{Tier: TierSource, Confidence: 0.99}, 0.8) {
		t.Fatal("tier 4 must always be reviewed")
	}
	if NeedsReview(Result{Tier: TierMusicBrainz, Confidence: 0.95}, 0.8) {
		t.Fatal("confident tier 1 should not be")
	}
	if !NeedsReview(Result{Tier: TierMusicBrainz, Confidence: 0.5}, 0.8) {
		t.Fatal("low confidence should be")
	}
}

type fixed struct {
	r  Result
	ok bool
}

func (f fixed) Resolve(context.Context, Input) (Result, bool, error) { return f.r, f.ok, nil }

func TestChainStopsAtFirstConfident(t *testing.T) {
	c := Chain{MinConfidence: 0.8, Resolvers: []Resolver{
		fixed{Result{Source: "a", Confidence: 0.4}, true},
		fixed{Result{Source: "b"}, false},
		fixed{Result{Source: "c", Confidence: 0.9}, true},
		fixed{Result{Source: "d", Confidence: 1}, true},
	}}
	r, err := c.Resolve(context.Background(), Input{})
	if err != nil || r.Source != "c" {
		t.Fatal(r, err)
	}
	c.MinConfidence = 2
	r, _ = c.Resolve(context.Background(), Input{})
	if r.Source != "d" {
		t.Fatal("expected best-of when nothing is confident", r)
	}
}
