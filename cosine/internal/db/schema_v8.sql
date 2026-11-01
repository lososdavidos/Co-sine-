-- v8: devices and their playback sessions (§4A, §4B). Each running client
-- is a Device of its account, and each Device keeps its own queue and
-- position, so two playing at once don't fight over one, and any of them
-- can be picked up from another.
CREATE TABLE devices (
    id        INTEGER PRIMARY KEY,
    user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,           -- the client's own id for itself: an install of Sine, a Subsonic app
    name      TEXT NOT NULL,           -- for people: "Pixel 8", "Feishin"
    kind      TEXT NOT NULL,           -- phone, desktop, web, subsonic
    last_seen INTEGER NOT NULL,
    UNIQUE (user_id, client_id)
);

CREATE TABLE play_sessions (
    device_id   INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    queue       TEXT NOT NULL,         -- JSON array of track ids, in the order they play
    current     INTEGER NOT NULL,      -- index into queue
    position_ms INTEGER NOT NULL,
    playing     INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
