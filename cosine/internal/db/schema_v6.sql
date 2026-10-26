-- v6: playlists on the phone, shared playlists, and scrobbling (§2.3, §4A,
-- §5.2).

-- Playlist edits made on a phone, replayed here by operation (§5.2). Each
-- is applied once however often it is resent; a create keeps the playlist
-- it made, so later edits to the new playlist find it.
CREATE TABLE playlist_ops (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    op_id       TEXT NOT NULL,
    playlist_id INTEGER,
    applied_at  INTEGER NOT NULL,
    PRIMARY KEY (user_id, op_id)
);

-- A playlist shared with another account (§2.3). Until accepted it is an
-- invitation; once accepted both see and edit the same playlist, and its
-- songs are in both Libraries.
CREATE TABLE playlist_members (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_at  INTEGER NOT NULL,
    accepted_at INTEGER,
    PRIMARY KEY (playlist_id, user_id)
);
CREATE INDEX playlist_members_user ON playlist_members(user_id);

-- Scrobbling (§4A): an account's Last.fm or ListenBrainz, and the last play
-- sent there. Plays go in the order they reached Cosine, so one played
-- offline and synced later still goes, with the time it was played.
CREATE TABLE scrobblers (
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service      TEXT NOT NULL,     -- lastfm, listenbrainz
    token        TEXT NOT NULL,     -- Last.fm's session key, ListenBrainz's user token
    username     TEXT NOT NULL,     -- the account there
    sent_up_to   INTEGER NOT NULL,  -- plays.id: those after it are still to send
    error        TEXT,              -- set when the service refused the token: reconnect
    connected_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, service)
);
