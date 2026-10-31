-- v7: Liked songs is a playlist of its own, not a smart one (§2.4): the
-- default made by v0.5.8, and any other rule over likes, go. The default
-- Favourites works out current favourites from plays instead of counting
-- the last 30 days.
DELETE FROM smart_playlists WHERE rule = 'liked';
UPDATE smart_playlists SET rule = 'favourites', span = NULL, size = 50
WHERE name = 'Favourites' AND rule = 'most-played' AND span = '30d';
