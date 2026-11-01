-- v10: a Device's volume in its session (§4B), 0 to 1, so another device
-- controlling it shows where it is. NULL: not said, as from a Subsonic app.
ALTER TABLE play_sessions ADD COLUMN volume REAL;
