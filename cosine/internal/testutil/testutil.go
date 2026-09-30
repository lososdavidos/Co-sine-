// Package testutil builds fixtures shared across Cosine's tests.
package testutil

import (
	"bytes"
	"encoding/binary"
	"os"
	"testing"
)

// MP3 describes a fake MP3: a real ID3v2.3 tag followed by arbitrary bytes.
type MP3 struct {
	Title, Artist, Album, Track string
	Picture                     []byte // JPEG bytes for an APIC frame
	Body                        string // makes the content hash unique
}

// Write writes the file and returns its path.
func (m MP3) Write(t testing.TB, path string) string {
	t.Helper()
	var frames bytes.Buffer
	text := func(id, v string) {
		if v == "" {
			return
		}
		frame(&frames, id, append([]byte{0}, v...))
	}
	text("TIT2", m.Title)
	text("TPE1", m.Artist)
	text("TALB", m.Album)
	text("TRCK", m.Track)
	if m.Picture != nil {
		var p bytes.Buffer
		p.WriteByte(0)
		p.WriteString("image/jpeg\x00")
		p.WriteByte(3) // front cover
		p.WriteByte(0) // empty description
		p.Write(m.Picture)
		frame(&frames, "APIC", p.Bytes())
	}
	var out bytes.Buffer
	out.WriteString("ID3\x03\x00\x00")
	out.Write(syncsafe(frames.Len()))
	out.Write(frames.Bytes())
	out.WriteString("\xff\xfb\x90\x00") // an MPEG frame header, then filler
	out.WriteString(m.Body)
	if err := os.WriteFile(path, out.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func frame(w *bytes.Buffer, id string, data []byte) {
	w.WriteString(id)
	binary.Write(w, binary.BigEndian, uint32(len(data)))
	w.Write([]byte{0, 0})
	w.Write(data)
}

func syncsafe(n int) []byte {
	return []byte{byte(n >> 21 & 0x7f), byte(n >> 14 & 0x7f), byte(n >> 7 & 0x7f), byte(n & 0x7f)}
}
