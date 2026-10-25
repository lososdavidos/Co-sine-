package resolve

import "testing"

func TestCleanTitle(t *testing.T) {
	cases := []struct{ in, want string }{
		{"Tides [HD Upgrade]", "Tides"},
		{"Skeler - Tides (Official Music Video)", "Skeler - Tides"},
		{"Hollow (VIP) [Free Download]", "Hollow (VIP)"},
		{"Hollow (VIP) [FREE DL]", "Hollow (VIP)"},
		{"glass (Out Now!)", "glass"},
		{"drift [Monstercat Release]", "drift"},
		{"drift [NCS Release]", "drift"},
		{"PREMIERE: Deadcrow - Hollow", "Deadcrow - Hollow"},
		{"Premiere | Deadcrow - Hollow", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow | Free Download", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow | Some Label", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow FREE DOWNLOAD", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow *FREE DL*", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow 【HD】", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow [FREE DOWNLOAD IN DESCRIPTION]", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow (OUT NOW ON WAVE MUSIC)", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow [HD 2019]", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow #wave #phonk", "Deadcrow - Hollow"},
		{"Deadcrow - Hollow (Lyric Video) [4K]", "Deadcrow - Hollow"},
		// What names the piece stays.
		{"Tides (feat. plenka)", "Tides (feat. plenka)"},
		{"Tides (Extended Mix)", "Tides (Extended Mix)"},
		{"Tides (Skeler Remix) [HD]", "Tides (Skeler Remix)"},
		{"Tides (VIP)", "Tides (VIP)"},
		{"nostalgia (1998)", "nostalgia (1998)"},
		{"Tides | Skeler", "Tides | Skeler"}, // no dash: the bar may be the only separator
		{"Free", "Free"},
		{"HD", "HD"},
		{"  spaced   out  ", "spaced out"},
	}
	for _, c := range cases {
		if got := CleanTitle(c.in); got != c.want {
			t.Errorf("CleanTitle(%q) = %q, want %q", c.in, got, c.want)
		}
		if got := CleanTitle(CleanTitle(c.in)); got != c.want {
			t.Errorf("CleanTitle is not idempotent on %q: %q", c.in, got)
		}
	}
}

func TestCleanArtist(t *testing.T) {
	cases := []struct{ in, want string }{
		{"Skeler - Topic", "Skeler"},
		{"SkelerVEVO", "Skeler"},
		{"wave archive", "wave archive"},
		{"VEVO", "VEVO"},
	}
	for _, c := range cases {
		if got := CleanArtist(c.in); got != c.want {
			t.Errorf("CleanArtist(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestSourceTitlesAreCleaned(t *testing.T) {
	in := SourceInfo{Title: "PREMIERE: Skeler - Tides [HD Upgrade]", Uploader: "wave archive"}
	r, _, _ := SourceMetadata{}.Resolve(t.Context(), Input{OriginalName: "x.m4a", Source: &in})
	if r.Artist != "Skeler" || r.Title != "Tides" || r.Release != "Tides" {
		t.Fatalf("%+v", r)
	}
	in = SourceInfo{Title: "Tides (Official Audio)", Uploader: "Skeler - Topic"}
	r, _, _ = SourceMetadata{}.Resolve(t.Context(), Input{OriginalName: "x.m4a", Source: &in})
	if r.Artist != "Skeler" || r.Title != "Tides" {
		t.Fatalf("%+v", r)
	}
}
