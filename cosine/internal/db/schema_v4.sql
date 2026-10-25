-- v4: artist pictures (§6.9), found in the background.
ALTER TABLE artists ADD COLUMN art_path TEXT;          -- relative to the data dir
ALTER TABLE artists ADD COLUMN art_source TEXT;        -- deezer
ALTER TABLE artists ADD COLUMN art_checked_at INTEGER; -- the last look, whether or not it found one
