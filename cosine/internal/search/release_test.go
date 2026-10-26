package search

import (
	"context"
	"strings"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/testutil"
	"github.com/lososdavidos/Co-sine-/cosine/internal/ytdlp"
)

func TestTheSongAsReleasedComesFirst(t *testing.T) {
	results := []Result{
		{Title: "Halsey - Bad At Love (Official Music Video)", Uploader: "HalseyVEVO", Source: "youtube", DurationSec: 225, URL: "video"},
		{Title: "Bad At Love", Uploader: "some fan", Source: "soundcloud", DurationSec: 720, URL: "extended"},
		{Title: "Halsey - Bad At Love", Uploader: "halsey", Source: "soundcloud", DurationSec: 30, URL: "preview", Preview: true},
		{Title: "Halsey - Bad at Love (Autograf Remix)", Uploader: "Autograf", Source: "youtube", DurationSec: 205, URL: "remix"},
		{Title: "Bad at Love", Uploader: "Halsey", Source: SourceYouTubeMusic, DurationSec: 181, URL: "song"},
		{Title: "Halsey - Without Me", Uploader: "HalseyVEVO", Source: "youtube", DurationSec: 201, URL: "other"},
		{Title: "Halsey - Bad at Love (Audio)", Uploader: "HalseyVEVO", Source: "youtube", DurationSec: 183, URL: "audio"},
	}
	got := ByRelease("halsey bad at love", results, nil)
	var order []string
	for _, r := range got {
		order = append(order, r.URL)
	}
	if strings.Join(order, " ") != "song audio remix other video extended preview" {
		t.Fatal(order)
	}
	notes := map[string]string{}
	for _, r := range got {
		notes[r.URL] = LengthNote(r)
	}
	if notes["song"] != "Same length as the release" || notes["audio"] != "Same length as the release" ||
		notes["video"] != "0:44 longer than the release" || notes["extended"] != "8:59 longer than the release" || notes["preview"] != "" ||
		notes["remix"] != "" || notes["other"] != "" {
		t.Fatal(notes)
	}
}

// What signed-out searches see now: YouTube Music lists no songs, so the
// released length comes from a catalogue (Deezer), as it answered for
// "halsey without me".
func TestTheCatalogueGivesTheReleasedLength(t *testing.T) {
	deezer := []Released{
		{Title: "Without Me", Version: "(Stripped)", Artist: "Halsey", DurationSec: 182},
		{Title: "Without Me", Artist: "Halsey", DurationSec: 201},
		{Title: "Without Me", Version: "(ILLENIUM Remix)", Artist: "Halsey", DurationSec: 249},
		{Title: "the other girl (with Halsey)", Artist: "Kelsea Ballerini", DurationSec: 201},
	}
	ref, ok := AskedRelease("halsey without me", deezer)
	if !ok || ref.DurationSec != 201 || ref.Title != "Without Me" || ref.Uploader != "Halsey" {
		t.Fatal(ref, ok)
	}
	if ref, _ := AskedRelease("halsey without me stripped", deezer); ref.DurationSec != 182 {
		t.Fatal("the version asked for:", ref)
	}
	if _, ok := AskedRelease("skeler tides", deezer); ok {
		t.Fatal("not what was asked for")
	}

	results := []Result{
		{Title: "Halsey - Without Me", Uploader: "Halsey", Source: "youtube", DurationSec: 237, URL: "video"},
		{Title: "Halsey - Without Me (Lyrics)", Uploader: "Dan Music", Source: "youtube", DurationSec: 203, URL: "lyrics"},
		{Title: "Without Me", Uploader: "some fan", Source: "soundcloud", DurationSec: 30, URL: "preview", Preview: true},
		{Title: "Halsey - Without Me (Official Audio)", Uploader: "Halsey", Source: "youtube", DurationSec: 204, URL: "audio"},
		{Title: "Halsey - Without Me (ILLENIUM Remix/Audio)", Uploader: "Halsey", Source: "youtube", DurationSec: 248, URL: "remix"},
	}
	got := ByRelease("halsey without me", results, &ref)
	var order []string
	for _, r := range got {
		order = append(order, r.URL+":"+LengthNote(r))
	}
	want := "audio:Same length as the release lyrics:Same length as the release remix: video:0:36 longer than the release preview:"
	if strings.Join(order, " ") != want {
		t.Fatal(order)
	}
}

func TestNothingToCompareWithKeepsTheOrder(t *testing.T) {
	results := []Result{{Title: "Tides", Source: "soundcloud", DurationSec: 200, URL: "a"},
		{Title: "Tides", Source: "soundcloud", DurationSec: 30, URL: "b", Preview: true},
		{Title: "Tides (VIP)", Source: "youtube", DurationSec: 260, URL: "c"},
		// A YouTube Music song that isn't what was asked for sets no length.
		{Title: "Tides", Uploader: "Ed Sheeran", Source: SourceYouTubeMusic, DurationSec: 194, URL: "d"}}
	got := ByRelease("skeler tides", results, nil)
	if got[0].URL != "a" || got[1].URL != "c" || got[2].URL != "d" || got[3].URL != "b" || got[0].ReleaseSec != 0 {
		t.Fatalf("%+v", got)
	}
}

func TestYouTubeMusicSongsJoinTheSearch(t *testing.T) {
	song := "https://music.youtube.com/watch?v=song1"
	other := "https://music.youtube.com/watch?v=song2"
	bin, _ := testutil.FakeYtDlp(t, map[string]any{
		"scsearch": map[string]any{"_type": "playlist", "entries": []map[string]any{
			{"url": "https://soundcloud.com/fan/bad-at-love", "title": "Bad At Love", "uploader": "fan", "duration": 720}}},
		"ytsearch": map[string]any{"_type": "playlist", "entries": []map[string]any{
			{"url": "https://www.youtube.com/watch?v=video", "title": "Halsey - Bad At Love (Official Music Video)", "channel": "HalseyVEVO", "duration": 225}}},
		MusicSearchURL("halsey bad at love"): map[string]any{"_type": "playlist", "entries": []map[string]any{
			{"_type": "url", "url": song, "title": "Bad at Love"},
			{"_type": "url", "url": other, "title": "Without Me"},
			{"_type": "url", "url": "https://music.youtube.com/watch?v=gone", "title": "Gone"}}},
		song: map[string]any{"id": "song1", "title": "Bad at Love", "artist": "Halsey", "track": "Bad at Love",
			"album": "hopeless fountain kingdom (Deluxe)", "channel": "Halsey - Topic", "duration": 181,
			"webpage_url": "https://www.youtube.com/watch?v=song1"},
		other: map[string]any{"id": "song2", "title": "Without Me", "channel": "Halsey - Topic", "duration": 201,
			"webpage_url": "https://www.youtube.com/watch?v=song2"},
		// "gone" has no fixture: yt-dlp fails on it, and it isn't offered.
	})
	res, err := YtDlpSearch{Runner: ytdlp.Runner{Bin: bin}}.Search(context.Background(), "halsey bad at love", 5)
	if err != nil || len(res) != 4 {
		t.Fatal(res, err)
	}
	first := res[0]
	if first.Source != SourceYouTubeMusic || first.Title != "Bad at Love" || first.Uploader != "Halsey" ||
		first.DurationSec != 181 || first.URL != "https://www.youtube.com/watch?v=song1" {
		t.Fatalf("%+v", first)
	}
	// A song that isn't the one asked for goes after the other sites';
	// without an artist field, the channel names it.
	if last := res[3]; last.Title != "Without Me" || last.Uploader != "Halsey" {
		t.Fatalf("%+v", last)
	}
}
