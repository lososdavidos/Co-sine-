package ytdlp

import (
	"errors"
	"testing"
)

func TestParseProgress(t *testing.T) {
	cases := map[string]float64{"50 100 NA": 0.5, "30 NA 60": 0.5, "10 NA NA": -1, "200 100 NA": 1, "garbage": -1}
	for in, want := range cases {
		got, ok := parseProgress(in)
		if want < 0 {
			if ok {
				t.Errorf("%q: expected no value", in)
			}
			continue
		}
		if !ok || got != want {
			t.Errorf("%q = %v", in, got)
		}
	}
}

func TestFailureKeepsTheReason(t *testing.T) {
	stderr := "WARNING: something\nERROR: [soundcloud] 12345: Unable to download JSON metadata: HTTP Error 404: Not Found\n"
	if got := failure(errors.New("exit 1"), stderr).Error(); got != "Unable to download JSON metadata: HTTP Error 404: Not Found" {
		t.Fatal(got)
	}
	if got := failure(errors.New("exit 1"), "ERROR: Unsupported URL: https://x\n").Error(); got != "Unsupported URL: https://x" {
		t.Fatal(got)
	}
	long := "ERROR: [soundcloud:search] x: Unable to download webpage: ('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden')) (caused by ProxyError(\"x\")); please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template."
	if got := failure(errors.New("exit 1"), long).Error(); got != "Unable to download webpage: ('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))" {
		t.Fatal(got)
	}
}

func TestCoverURLPrefersLargestJPEG(t *testing.T) {
	i := Info{Thumbnail: "fallback.webp", Thumbnails: []Thumbnail{
		{URL: "a.jpg", Width: 100, Height: 100},
		{URL: "b.webp", Width: 1000, Height: 1000},
		{URL: "c.jpg?x=1", Width: 500, Height: 500},
	}}
	if got := i.CoverURL(); got != "c.jpg?x=1" {
		t.Fatal(got)
	}
	if got := (Info{Thumbnail: "t.jpg"}).CoverURL(); got != "t.jpg" {
		t.Fatal(got)
	}
}
