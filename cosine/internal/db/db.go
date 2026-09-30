// Package db opens Cosine's single SQLite file and applies the schema.
package db

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaV1 string

// migrations are applied in order; the index+1 is the schema version.
// Additive only, like the wire protocol (§9.3).
var migrations = []string{schemaV1}

type DB struct {
	*sql.DB
}

// Open opens (creating if needed) the database at path and migrates it.
func Open(path string) (*DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)", path)
	sqldb, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// One writer keeps SQLite honest; reads are cheap enough at this scale.
	sqldb.SetMaxOpenConns(1)
	d := &DB{sqldb}
	if err := d.migrate(context.Background()); err != nil {
		sqldb.Close()
		return nil, err
	}
	return d, nil
}

func (d *DB) migrate(ctx context.Context) error {
	var version int
	if err := d.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	for i := version; i < len(migrations); i++ {
		tx, err := d.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// Now is the timestamp unit used throughout the schema: Unix milliseconds.
func Now() int64 { return time.Now().UnixMilli() }

// Setting keys.
const (
	SettingStorePath  = "store_path"
	SettingInboxPath  = "inbox_path"
	SettingVisibility = "cross_user_visibility"
)

func (d *DB) Setting(ctx context.Context, key string) (string, error) {
	var v string
	err := d.QueryRowContext(ctx, "SELECT value FROM settings WHERE key = ?", key).Scan(&v)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return v, err
}

func (d *DB) SetSetting(ctx context.Context, key, value string) error {
	_, err := d.ExecContext(ctx,
		"INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
		key, value)
	return err
}

// Execer is satisfied by *sql.DB and *sql.Tx.
type Execer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

// LogChange appends to the change log. userID 0 means "everyone".
func LogChange(ctx context.Context, x Execer, userID int64, entity, entityID, op string) error {
	var uid any
	if userID != 0 {
		uid = userID
	}
	_, err := x.ExecContext(ctx,
		"INSERT INTO changes(user_id, entity, entity_id, op, at) VALUES(?, ?, ?, ?, ?)",
		uid, entity, entityID, op, Now())
	return err
}
