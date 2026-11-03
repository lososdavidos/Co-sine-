-- v11: two songs on one release may share a title (a "Skit" at track 4 and
-- at track 11). UNIQUE (release_id, title) goes; which Track a file joins is
-- decided by the title and numbers that don't disagree (ingest). The table is
-- rebuilt, as SQLite drops no constraint in place; migrate pauses foreign
-- keys for it, and every id is kept.
CREATE TABLE tracks_v11 (
    id         INTEGER PRIMARY KEY,
    release_id INTEGER NOT NULL REFERENCES releases(id),
    artist_id  INTEGER NOT NULL REFERENCES artists(id),
    title      TEXT NOT NULL COLLATE NOCASE,
    track_no   INTEGER,
    disc_no    INTEGER,
    resolver   TEXT NOT NULL,
    tier       INTEGER NOT NULL,
    confidence REAL NOT NULL,
    reviewed   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    mbid       TEXT
);
INSERT INTO tracks_v11(id, release_id, artist_id, title, track_no, disc_no, resolver, tier, confidence, reviewed, created_at, mbid)
SELECT id, release_id, artist_id, title, track_no, disc_no, resolver, tier, confidence, reviewed, created_at, mbid FROM tracks;
DROP TABLE tracks;
ALTER TABLE tracks_v11 RENAME TO tracks;
CREATE INDEX tracks_review ON tracks(reviewed, id);
CREATE UNIQUE INDEX tracks_identity ON tracks(release_id, title, COALESCE(disc_no, 0), COALESCE(track_no, 0));
