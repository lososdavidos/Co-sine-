-- Cosine schema. The database is the source of truth for everything except
-- audio bytes (§2.2). Losing this file loses Pointers, playlists and history;
-- the Store alone is just a folder of music (§5.1).

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE users (
    id           INTEGER PRIMARY KEY,
    name         TEXT NOT NULL UNIQUE COLLATE NOCASE,
    -- Encrypted, not hashed: Subsonic token auth needs md5(password + salt)
    -- on the server side, so the password must be recoverable.
    password_enc BLOB NOT NULL,
    is_admin     INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL
);

CREATE TABLE sessions (
    token      TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL
);

CREATE TABLE artists (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE
);

-- A release. A single is a release of one song (§2.2), so there is no
-- "loose tracks" case.
CREATE TABLE releases (
    id        INTEGER PRIMARY KEY,
    artist_id INTEGER NOT NULL REFERENCES artists(id),
    title     TEXT NOT NULL COLLATE NOCASE,
    year      INTEGER,
    art_path  TEXT,            -- relative to the data dir
    created_at INTEGER NOT NULL,
    UNIQUE (artist_id, title)
);

-- A resolved musical identity. Two Objects are versions of one Track when
-- the resolver lands them here (§2.2): same release, same title.
CREATE TABLE tracks (
    id         INTEGER PRIMARY KEY,
    release_id INTEGER NOT NULL REFERENCES releases(id),
    artist_id  INTEGER NOT NULL REFERENCES artists(id),
    title      TEXT NOT NULL COLLATE NOCASE,
    track_no   INTEGER,
    disc_no    INTEGER,
    resolver   TEXT NOT NULL,  -- which source identified it
    tier       INTEGER NOT NULL,
    confidence REAL NOT NULL,
    reviewed   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    UNIQUE (release_id, title)
);

-- One physical file in the Store. Identity is the content hash; the file is
-- never rewritten in place (§2.2).
CREATE TABLE objects (
    hash         TEXT PRIMARY KEY,
    track_id     INTEGER NOT NULL REFERENCES tracks(id),
    rel_path     TEXT NOT NULL UNIQUE,
    size         INTEGER NOT NULL,
    suffix       TEXT NOT NULL,
    content_type TEXT NOT NULL,
    duration_sec INTEGER NOT NULL DEFAULT 0,
    bitrate      INTEGER NOT NULL DEFAULT 0,
    source       TEXT NOT NULL,  -- inbox, upload, url
    source_url   TEXT,
    missing      INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL
);
CREATE INDEX objects_track ON objects(track_id);

-- Per-account view of a Track (§2.2). Per-user state lives here, never on the Object.
CREATE TABLE pointers (
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id     INTEGER NOT NULL REFERENCES tracks(id),
    added_at     INTEGER NOT NULL,
    starred_at   INTEGER,
    pin_hash     TEXT REFERENCES objects(hash),
    PRIMARY KEY (user_id, track_id)
);
CREATE INDEX pointers_track ON pointers(track_id);

-- "This account does not want this Track" (§4.1). Nothing dismissed comes back on its own.
CREATE TABLE dismissed (
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id     INTEGER NOT NULL REFERENCES tracks(id),
    dismissed_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, track_id)
);

-- Append-only play events (Q37); never pruned. The unique key makes a
-- resent offline play idempotent.
CREATE TABLE plays (
    id        INTEGER PRIMARY KEY,
    user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id  INTEGER NOT NULL REFERENCES tracks(id),
    played_at INTEGER NOT NULL,
    UNIQUE (user_id, track_id, played_at)
);
CREATE INDEX plays_user_time ON plays(user_id, played_at);

CREATE TABLE playlists (
    id         INTEGER PRIMARY KEY,
    owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE playlist_entries (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    track_id    INTEGER NOT NULL REFERENCES tracks(id),
    PRIMARY KEY (playlist_id, position)
);

-- Monotonic change log (§5.2). Written now so delta sync has history to
-- serve when it lands. user_id NULL = relevant to everyone.
CREATE TABLE changes (
    seq       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id   INTEGER,
    entity    TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    op        TEXT NOT NULL,
    at        INTEGER NOT NULL
);

-- Visible ingest state (G4).
CREATE TABLE ingest_jobs (
    id         INTEGER PRIMARY KEY,
    source     TEXT NOT NULL,
    input      TEXT NOT NULL,
    status     TEXT NOT NULL,  -- running, done, duplicate, failed
    error      TEXT,
    track_id   INTEGER REFERENCES tracks(id),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
