// Package db opens Cosine's single SQLite file and applies the schema.
package db

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"strconv"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaV1 string

//go:embed schema_v2.sql
var schemaV2 string

//go:embed schema_v3.sql
var schemaV3 string

//go:embed schema_v4.sql
var schemaV4 string

//go:embed schema_v5.sql
var schemaV5 string

//go:embed schema_v6.sql
var schemaV6 string

// migrations are applied in order; the index+1 is the schema version.
// Additive only, like the wire protocol (§9.3).
var migrations = []string{schemaV1, schemaV2, schemaV3, schemaV4, schemaV5, schemaV6}

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
	SettingStorePath     = "store_path"
	SettingInboxPath     = "inbox_path"
	SettingVisibility    = "cross_user_visibility"
	SettingSearchBackend = "search_backend" // "ytdlp" or "api"
	SettingSoundCloudID  = "soundcloud_client_id"
	SettingYouTubeKey    = "youtube_api_key"
	SettingDiscogsToken  = "discogs_token" // optional: raises Discogs's limit from 25 to 60 a minute
	// Last.fm's API account, made by whoever runs the server; without it
	// nobody can connect Last.fm (ListenBrainz needs nothing).
	SettingLastFMKey    = "lastfm_api_key"
	SettingLastFMSecret = "lastfm_secret"
)

// Available is true for the Track aliased t when at least one of its files
// is in the Store. Only those are offered (§2.2); one whose files are all
// missing, or were forgotten, comes back with its history when a file does.
const Available = "EXISTS (SELECT 1 FROM objects ox WHERE ox.track_id = t.id AND ox.missing = 0)"

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

// Querier is satisfied by *sql.DB and *sql.Tx.
type Querier interface {
	Execer
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

// LogPlaylist logs a change to a playlist for everyone who has it: its
// owner and those it is shared with, accepted or invited. Log before
// taking anyone off it, so they hear it's gone.
func LogPlaylist(ctx context.Context, x Querier, id int64, op string) error {
	rows, err := x.QueryContext(ctx, `
		SELECT owner_id FROM playlists WHERE id = ?1
		UNION SELECT user_id FROM playlist_members WHERE playlist_id = ?1`, id)
	if err != nil {
		return err
	}
	var users []int64
	for rows.Next() {
		var u int64
		if err := rows.Scan(&u); err != nil {
			rows.Close()
			return err
		}
		users = append(users, u)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, u := range users {
		if err := LogChange(ctx, x, u, "playlist", strconv.FormatInt(id, 10), op); err != nil {
			return err
		}
	}
	return nil
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
