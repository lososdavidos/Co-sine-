package store

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLayout(t *testing.T) {
	got := Layout("AC/DC", "Live.", "What? / Why: VIP", 3, "MP3")
	want := filepath.Join("AC_DC", "Live", "03 What_ _ Why_ VIP.mp3")
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	if got := Layout("rxrrim", "loner", "loner", 0, "opus"); got != filepath.Join("rxrrim", "loner", "loner.opus") {
		t.Fatalf("single: %q", got)
	}
}

func TestSanitizeLimits(t *testing.T) {
	long := strings.Repeat("é", 200)
	s := Sanitize(long)
	if len(s) > maxNameBytes || !strings.HasPrefix(long, s) {
		t.Fatalf("bad truncation: %d bytes", len(s))
	}
	for _, in := range []string{"", ".", "..", "  "} {
		if Sanitize(in) != "_" {
			t.Fatalf("Sanitize(%q) = %q", in, Sanitize(in))
		}
	}
}

func TestVersioned(t *testing.T) {
	if got := Versioned("a/b/01 x.mp3", "deadbeefcafe"); got != "a/b/01 x [deadbeef].mp3" {
		t.Fatal(got)
	}
}

func TestPlaceMovesAndRefusesOverwrite(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "in.mp3")
	os.WriteFile(src, []byte("audio"), 0o644)
	h, n, err := Hash(src)
	if err != nil || n != 5 {
		t.Fatal(err, n)
	}
	root := filepath.Join(dir, "store")
	if err := Place(root, "A/B/x.mp3", src, h); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatal("source should be gone")
	}
	os.WriteFile(src, []byte("other"), 0o644)
	if err := Place(root, "A/B/x.mp3", src, h); err == nil {
		t.Fatal("expected refusal to overwrite")
	}
}

func TestContentType(t *testing.T) {
	if ct, ok := ContentType(".OPUS"); !ok || ct != "audio/ogg" {
		t.Fatal(ct, ok)
	}
	if _, ok := ContentType("jpg"); ok {
		t.Fatal("jpg is not audio")
	}
}
