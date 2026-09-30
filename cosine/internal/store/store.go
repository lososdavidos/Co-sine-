// Package store manages the Store: the one folder holding every audio file.
// The server is its sole writer (§2.2); files are placed once and never
// rewritten, because an Object's identity is its content hash.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

// Hash returns the hex SHA-256 of the file at path.
func Hash(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// Layout returns the Store-relative path for a track: Artist/Release/NN Title.ext (§2.2).
func Layout(artist, release, title string, trackNo int, suffix string) string {
	name := title
	if trackNo > 0 {
		name = fmt.Sprintf("%02d %s", trackNo, title)
	}
	file := Sanitize(name)
	if suffix != "" {
		file += "." + Sanitize(strings.ToLower(suffix))
	}
	return filepath.Join(Sanitize(artist), Sanitize(release), file)
}

// Versioned returns the alternative path used when another version of the
// same Track already occupies the canonical one.
func Versioned(rel, hash string) string {
	ext := filepath.Ext(rel)
	return strings.TrimSuffix(rel, ext) + " [" + hash[:8] + "]" + ext
}

const maxNameBytes = 180

// Sanitize makes a name safe as a single path element on every common
// filesystem, including FAT/exFAT and SMB shares.
func Sanitize(name string) string {
	var b strings.Builder
	for _, r := range name {
		switch {
		case r < 0x20, strings.ContainsRune(`/\:*?"<>|`, r):
			b.WriteRune('_')
		default:
			b.WriteRune(r)
		}
	}
	s := strings.TrimRight(strings.TrimSpace(b.String()), ". ")
	if s == "" || s == "." || s == ".." {
		s = "_"
	}
	for len(s) > maxNameBytes {
		_, size := utf8.DecodeLastRuneInString(s)
		s = s[:len(s)-size]
	}
	return strings.TrimSpace(s)
}

// Place moves src into root/rel. A rename is tried first; across filesystems
// it falls back to copy, fsync, verify the hash, then remove the source — so a
// file is never lost and never stored under a hash it doesn't have.
func Place(root, rel, src, wantHash string) error {
	dst := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	if _, err := os.Stat(dst); err == nil {
		return fmt.Errorf("%s already exists", rel)
	}
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	tmp := dst + ".partial"
	if err := copyFile(src, tmp); err != nil {
		os.Remove(tmp)
		return err
	}
	got, _, err := Hash(tmp)
	if err != nil || got != wantHash {
		os.Remove(tmp)
		if err == nil {
			err = errors.New("hash changed while copying; source was modified")
		}
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Remove(src)
}

// Move relocates a file already in the Store to a new root (Store relocation).
func Move(oldRoot, newRoot, rel, hash string) error {
	return Place(newRoot, rel, filepath.Join(oldRoot, rel), hash)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// PruneEmptyDirs removes empty directories under root, bottom-up, leaving root itself.
func PruneEmptyDirs(root string) {
	var dirs []string
	filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err == nil && d.IsDir() && p != root {
			dirs = append(dirs, p)
		}
		return nil
	})
	for i := len(dirs) - 1; i >= 0; i-- {
		os.Remove(dirs[i]) // fails harmlessly when not empty
	}
}

// Audio formats Cosine accepts. Anything else in the Inbox is left alone.
var contentTypes = map[string]string{
	"mp3":  "audio/mpeg",
	"flac": "audio/flac",
	"ogg":  "audio/ogg",
	"oga":  "audio/ogg",
	"opus": "audio/ogg",
	"m4a":  "audio/mp4",
	"mp4":  "audio/mp4",
	"aac":  "audio/aac",
	"wav":  "audio/wav",
	"aif":  "audio/aiff",
	"aiff": "audio/aiff",
	"wma":  "audio/x-ms-wma",
	"webm": "audio/webm",
	"mka":  "audio/x-matroska",
}

// ContentType returns the MIME type for an audio suffix, and whether it is audio at all.
func ContentType(suffix string) (string, bool) {
	ct, ok := contentTypes[strings.ToLower(strings.TrimPrefix(suffix, "."))]
	return ct, ok
}
