// Package auth handles accounts, Subsonic authentication and dashboard sessions.
package auth

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

var (
	ErrBadCredentials = errors.New("wrong username or password")
	ErrUserExists     = errors.New("that username is taken")
)

type User struct {
	ID      int64
	Name    string
	IsAdmin bool
}

// Service owns the key that encrypts stored passwords. Subsonic token auth
// (md5(password + salt)) means the server must be able to recover the
// password, so it is encrypted at rest rather than hashed — the same trade
// Navidrome makes.
type Service struct {
	db  *db.DB
	gcm cipher.AEAD
}

// LoadOrCreateKey reads the 32-byte key at path, creating it (0600) if absent.
func LoadOrCreateKey(path string) ([]byte, error) {
	key, err := os.ReadFile(path)
	if err == nil {
		if len(key) != 32 {
			return nil, fmt.Errorf("%s: expected 32 bytes, found %d", path, len(key))
		}
		return key, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	key = make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, key, 0o600); err != nil {
		return nil, err
	}
	return key, nil
}

func New(d *db.DB, key []byte) (*Service, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Service{db: d, gcm: gcm}, nil
}

func (s *Service) encrypt(plain string) ([]byte, error) {
	nonce := make([]byte, s.gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return s.gcm.Seal(nonce, nonce, []byte(plain), nil), nil
}

func (s *Service) decrypt(blob []byte) (string, error) {
	n := s.gcm.NonceSize()
	if len(blob) < n {
		return "", errors.New("corrupt password record")
	}
	plain, err := s.gcm.Open(nil, blob[:n], blob[n:], nil)
	return string(plain), err
}

func (s *Service) HasUsers(ctx context.Context) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM users").Scan(&n)
	return n > 0, err
}

func (s *Service) CreateUser(ctx context.Context, name, password string, admin bool) (User, error) {
	name = strings.TrimSpace(name)
	if name == "" || password == "" {
		return User{}, errors.New("username and password are required")
	}
	enc, err := s.encrypt(password)
	if err != nil {
		return User{}, err
	}
	res, err := s.db.ExecContext(ctx,
		"INSERT INTO users(name, password_enc, is_admin, created_at) VALUES(?, ?, ?, ?)",
		name, enc, admin, db.Now())
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			return User{}, ErrUserExists
		}
		return User{}, err
	}
	id, _ := res.LastInsertId()
	return User{ID: id, Name: name, IsAdmin: admin}, nil
}

// DeleteUser removes an account's Pointers, never its Objects (§2.3).
// Pointers, sessions, plays and playlists cascade.
func (s *Service) DeleteUser(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM users WHERE id = ?", id)
	return err
}

func (s *Service) Users(ctx context.Context) ([]User, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT id, name, is_admin FROM users ORDER BY name")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Name, &u.IsAdmin); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Service) lookup(ctx context.Context, name string) (User, string, error) {
	var u User
	var enc []byte
	err := s.db.QueryRowContext(ctx,
		"SELECT id, name, is_admin, password_enc FROM users WHERE name = ?", name,
	).Scan(&u.ID, &u.Name, &u.IsAdmin, &enc)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, "", ErrBadCredentials
	}
	if err != nil {
		return User{}, "", err
	}
	pw, err := s.decrypt(enc)
	return u, pw, err
}

// CheckPassword verifies a plain password.
func (s *Service) CheckPassword(ctx context.Context, name, password string) (User, error) {
	u, pw, err := s.lookup(ctx, name)
	if err != nil {
		return User{}, err
	}
	if subtle.ConstantTimeCompare([]byte(pw), []byte(password)) != 1 {
		return User{}, ErrBadCredentials
	}
	return u, nil
}

// CheckSubsonic verifies Subsonic credentials: token+salt (preferred), or the
// legacy p= parameter, plain or "enc:"-hex-encoded.
func (s *Service) CheckSubsonic(ctx context.Context, name, token, salt, password string) (User, error) {
	u, pw, err := s.lookup(ctx, name)
	if err != nil {
		return User{}, err
	}
	switch {
	case token != "" && salt != "":
		sum := md5.Sum([]byte(pw + salt))
		if subtle.ConstantTimeCompare([]byte(hex.EncodeToString(sum[:])), []byte(strings.ToLower(token))) == 1 {
			return u, nil
		}
	case password != "":
		if strings.HasPrefix(password, "enc:") {
			b, err := hex.DecodeString(password[4:])
			if err != nil {
				return User{}, ErrBadCredentials
			}
			password = string(b)
		}
		if subtle.ConstantTimeCompare([]byte(pw), []byte(password)) == 1 {
			return u, nil
		}
	}
	return User{}, ErrBadCredentials
}

const sessionTTL = 30 * 24 * time.Hour

// NewSession creates a dashboard session and returns its token.
func (s *Service) NewSession(ctx context.Context, userID int64) (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	_, err := s.db.ExecContext(ctx, "INSERT INTO sessions(token, user_id, expires_at) VALUES(?, ?, ?)",
		token, userID, time.Now().Add(sessionTTL).UnixMilli())
	return token, err
}

func (s *Service) Session(ctx context.Context, token string) (User, error) {
	var u User
	err := s.db.QueryRowContext(ctx, `
		SELECT u.id, u.name, u.is_admin FROM sessions s JOIN users u ON u.id = s.user_id
		WHERE s.token = ? AND s.expires_at > ?`, token, db.Now(),
	).Scan(&u.ID, &u.Name, &u.IsAdmin)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrBadCredentials
	}
	return u, err
}

func (s *Service) EndSession(ctx context.Context, token string) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM sessions WHERE token = ?", token)
	return err
}
