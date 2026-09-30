-- v3: MusicBrainz identity, the correction log, and the lookup cache.

-- Catalogue identifiers, when a source provided them.
ALTER TABLE artists ADD COLUMN mbid TEXT;
ALTER TABLE releases ADD COLUMN mbid TEXT;
ALTER TABLE releases ADD COLUMN release_group_mbid TEXT;
ALTER TABLE tracks ADD COLUMN mbid TEXT;

-- Where a release's cover came from, so a better source can replace a
-- worse one (§3.2a): caa > embedded > source. User-set covers are per
-- account and live elsewhere.
ALTER TABLE releases ADD COLUMN art_source TEXT;
UPDATE releases SET art_source = 'embedded' WHERE art_path IS NOT NULL;

-- Every lookup cached forever (Q15): MusicBrainz is 1 req/s, and the same
-- question should never be asked twice.
CREATE TABLE lookup_cache (
    key        TEXT PRIMARY KEY,
    status     INTEGER NOT NULL,
    body       BLOB NOT NULL,
    fetched_at INTEGER NOT NULL
);

-- Corrections are global and change everyone's view, so each is logged and
-- can be traced and reversed (§3.2). before/after are identity snapshots.
CREATE TABLE corrections (
    id          INTEGER PRIMARY KEY,
    track_id    INTEGER NOT NULL,
    user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,  -- NULL: Cosine itself (re-resolve)
    kind        TEXT NOT NULL,     -- correct, confirm, reresolve, revert
    before      TEXT NOT NULL,
    after       TEXT NOT NULL,
    merged_into INTEGER,           -- set when the change folded this Track into another
    reverted_by INTEGER REFERENCES corrections(id),
    created_at  INTEGER NOT NULL
);
CREATE INDEX corrections_track ON corrections(track_id, id);
CREATE INDEX tracks_review ON tracks(reviewed, id);
