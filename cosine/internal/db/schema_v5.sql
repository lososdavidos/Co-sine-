-- v5: playlists you make, smart playlists, and albums and playlists from
-- the sources (§2.4, §3.1a).

-- A smart playlist is a saved rule over the owner's own Library and plays
-- (§2.4); the rule is one of the stats views. Home tiles are views of the
-- same objects. Never shared.
CREATE TABLE smart_playlists (
    id         INTEGER PRIMARY KEY,
    owner_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    rule       TEXT NOT NULL,   -- most-played, recent, forgotten, never-played, this-time-last-year, added
    span       TEXT,            -- most-played: 30d, year or all
    months     INTEGER,         -- forgotten: not played in this many months
    days       INTEGER,         -- added: in the last this many days
    size       INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX smart_playlists_owner ON smart_playlists(owner_id);

-- Each account gets the default smart playlists once; deleting one is final.
ALTER TABLE users ADD COLUMN smart_seeded INTEGER NOT NULL DEFAULT 0;

-- An album or playlist added from a source. Its tracks are queued one job
-- each and resolved one by one; an album lends them its name, a playlist
-- never does, and becomes a playlist in the Library instead.
CREATE TABLE ingest_collections (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url         TEXT NOT NULL,
    kind        TEXT NOT NULL,  -- album, playlist, channel
    title       TEXT NOT NULL,
    artist      TEXT,           -- album only: who released it
    playlist_id INTEGER REFERENCES playlists(id) ON DELETE SET NULL,
    created_at  INTEGER NOT NULL
);
ALTER TABLE ingest_jobs ADD COLUMN collection_id INTEGER REFERENCES ingest_collections(id) ON DELETE SET NULL;
ALTER TABLE ingest_jobs ADD COLUMN position INTEGER;  -- 1-based place in the collection
ALTER TABLE ingest_jobs ADD COLUMN placed INTEGER NOT NULL DEFAULT 0;  -- put in the collection's playlist

-- Each song put in a collection's playlist, with its place in the source,
-- so songs that finish downloading out of order still land in order.
CREATE TABLE collection_places (
    collection_id INTEGER NOT NULL REFERENCES ingest_collections(id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    track_id      INTEGER NOT NULL
);
CREATE INDEX collection_places_collection ON collection_places(collection_id);

CREATE INDEX playlist_entries_track ON playlist_entries(track_id);
CREATE INDEX playlists_owner ON playlists(owner_id);
