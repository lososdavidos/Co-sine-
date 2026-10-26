// Package homes keeps each account's Home arrangement (§6.4): the canvas of
// tiles Sine draws and the person arranges, one for each kind of device
// (§6.15). Cosine doesn't read it, only keeps it, so it survives a
// reinstall or a new phone.
package homes

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/lososdavidos/Co-sine-/cosine/internal/db"
)

// Devices are the kinds of device with a Home of their own.
var Devices = []string{"phone", "desktop"}

// MaxSize is the most an arrangement may take: a few hundred tiles.
const MaxSize = 64 << 10

var (
	ErrDevice = errors.New("no such kind of device")
	ErrLayout = errors.New("a layout is a JSON object of at most 64 KB")
)

type Service struct {
	DB *db.DB
}

// key names an account's arrangement for one kind of device. It carries
// when the account was made, so an account made later under a reused id
// starts afresh rather than with someone else's Home.
func (s *Service) key(ctx context.Context, user int64, device string) (string, error) {
	known := false
	for _, d := range Devices {
		known = known || d == device
	}
	if !known {
		return "", ErrDevice
	}
	var created int64
	if err := s.DB.QueryRowContext(ctx, "SELECT created_at FROM users WHERE id = ?", user).Scan(&created); err != nil {
		return "", err
	}
	return fmt.Sprintf("home:%d:%d:%s", user, created, device), nil
}

// Layout is the account's arrangement for device as Sine last sent it;
// nil when it has never sent one.
func (s *Service) Layout(ctx context.Context, user int64, device string) (json.RawMessage, error) {
	k, err := s.key(ctx, user, device)
	if err != nil {
		return nil, err
	}
	v, err := s.DB.Setting(ctx, k)
	if err != nil || v == "" {
		return nil, err
	}
	return json.RawMessage(v), nil
}

// Save keeps layout, replacing what was there.
func (s *Service) Save(ctx context.Context, user int64, device string, layout json.RawMessage) error {
	k, err := s.key(ctx, user, device)
	if err != nil {
		return err
	}
	var object map[string]json.RawMessage
	if len(layout) > MaxSize || json.Unmarshal(layout, &object) != nil || object == nil {
		return ErrLayout
	}
	return s.DB.SetSetting(ctx, k, string(layout))
}
