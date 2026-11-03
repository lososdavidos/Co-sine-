package auth

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

func TestShortPasswordsAndGuessingAreRefused(t *testing.T) {
	d, err := db.Open(filepath.Join(t.TempDir(), "c.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	s, _ := New(d, make([]byte, 32))
	ctx := context.Background()
	if _, err := s.CreateUser(ctx, "bob", "short", false); !errors.Is(err, ErrShortPassword) {
		t.Fatal("a 5-letter password was taken:", err)
	}
	bob, err := s.CreateUser(ctx, "bob", "long enough", false)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetPassword(ctx, bob.ID, "tiny", ""); !errors.Is(err, ErrShortPassword) {
		t.Fatal(err)
	}
	// A right password clears the count; ten wrong ones lock the account for a while.
	for range maxTries - 1 {
		s.CheckPassword(ctx, "bob", "guess")
	}
	if _, err := s.CheckPassword(ctx, "bob", "long enough"); err != nil {
		t.Fatal(err)
	}
	for range maxTries {
		s.CheckPassword(ctx, "bob", "guess")
	}
	if _, err := s.CheckSubsonic(ctx, "bob", "", "", "long enough"); !errors.Is(err, ErrTooManyTries) {
		t.Fatal("still guessing after ten wrong passwords:", err)
	}
	if _, err := s.CheckPassword(ctx, "alice", "anything"); !errors.Is(err, ErrBadCredentials) {
		t.Fatal("another account is not locked:", err)
	}
}
