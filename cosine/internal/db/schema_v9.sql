-- v9: Jam (§4C), the party mode: accounts listening together, anywhere,
-- kept within about a second. A host invites people; everyone in it adds
-- to one queue; only the host plays, pauses, skips and seeks. Where the
-- music is lives here as an anchor: song, position, the moment that was
-- true, and whether it's playing; every listener works out "now" from it.
CREATE TABLE jams (
    id          INTEGER PRIMARY KEY,
    host_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_at  INTEGER NOT NULL,
    ended_at    INTEGER,
    current     INTEGER NOT NULL DEFAULT 0,   -- index into jam_queue by pos order
    position_ms INTEGER NOT NULL DEFAULT 0,
    anchor_at   INTEGER NOT NULL,             -- when current and position_ms were true
    playing     INTEGER NOT NULL DEFAULT 0,
    version     INTEGER NOT NULL DEFAULT 1    -- bumped on every change, for listeners waiting on one
);

CREATE TABLE jam_members (
    jam_id     INTEGER NOT NULL REFERENCES jams(id) ON DELETE CASCADE,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_at INTEGER NOT NULL,
    joined_at  INTEGER,
    left_at    INTEGER,
    PRIMARY KEY (jam_id, user_id)
);
CREATE INDEX jam_members_user ON jam_members(user_id);

CREATE TABLE jam_queue (
    jam_id   INTEGER NOT NULL REFERENCES jams(id) ON DELETE CASCADE,
    pos      INTEGER NOT NULL,
    track_id INTEGER NOT NULL REFERENCES tracks(id),
    added_by INTEGER NOT NULL REFERENCES users(id),
    added_at INTEGER NOT NULL,
    PRIMARY KEY (jam_id, pos)
);
