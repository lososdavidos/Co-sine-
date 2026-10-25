# Cosine

The server: one Go binary, one SQLite file, one Store. It replaces Navidrome. See `docs/spec/SPEC.md` §5.1.

## Run

On Proxmox or any Debian/Ubuntu machine, use the installer: it sets up Cosine, yt-dlp, Deno, ffmpeg, the services and nightly backups. See [`deploy/README.md`](../deploy/README.md).

By hand:

```sh
cd cosine
CGO_ENABLED=0 go build -o cosine ./cmd/cosine
./cosine --data /var/lib/cosine --listen :4534
```

Open `http://<host>:4534/` and the first-run setup asks for:

- **Store path**: the folder that holds every audio file. Only Cosine writes to it.
- **Inbox path**: drop files here (over SSH, SMB, rclone…) and they get filed into the Store.
- The **admin account**.

Then point Sine, or any Subsonic client, at `http://<host>:4534`.

| Flag | Env | Default | |
|---|---|---|---|
| `--data` | `COSINE_DATA` | `./data` | `cosine.db`, `secret.key`, cached artwork |
| `--listen` | `COSINE_LISTEN` | `:4534` | Next to Navidrome's 4533, so both can run during the switch |

Install **yt-dlp** to add music from links and searches; without it that feature is switched off and not advertised to Sine. `COSINE_YTDLP` points at a specific binary. Keep it updated (`yt-dlp -U`): sites change and yt-dlp follows them.

Install `ffprobe` (from ffmpeg) to get track durations for Inbox files. Durations for links come from yt-dlp.

**Back up `data/`.** The database is the only place accounts, libraries, playlists and play history exist, and `secret.key` decrypts the stored passwords. The whole backup is a copy of that folder (§5.1).

## What works

- **Ingest (§3, §4):** anything dropped in the Inbox is ingested once its size stops changing, so half-copied files are never picked up.
  - Each file is hashed, resolved, filed as `Artist/Release/NN Title.ext`, and never rewritten.
  - A known hash is deduplicated into a Pointer instead of a second file.
  - Different files that resolve to the same track become versions of one Track.
  - An Inbox drop gives every account a Pointer, except accounts that removed that track before.
  - Files that fail to ingest move to `Inbox/_failed/`.
- **Adding from links (§3.1):** Sine's Add screen or the dashboard takes a link or a search.
  - Any site yt-dlp supports. Short and share links are resolved on the server.
  - The best audio stream is stored exactly as downloaded: no conversion, no remux, no fixups.
  - A set, album or channel is either queued whole or opened as a list to pick from first.
  - A SoundCloud track that only offers a 30-second preview (Go+, which much popular music there is) is refused before downloading, with a message to look for it on YouTube instead. Search marks such previews and lists them after everything else.
  - A link fetched before is refused with "Already in your library." until you choose "Add anyway" (Q12). If another account fetched it, adding it just gives you a Pointer, with no second download.
  - Jobs survive restarts, run two at a time, report progress, and show yt-dlp's own reason when they fail, with a Retry.
  - The source's metadata feeds the resolver: yt-dlp's artist/track/album fields when the site has them, otherwise "Artist - Title" read from the title, otherwise the uploader. The source's cover becomes the artwork when the file has none.
- **Search:** two backends, switched in the dashboard. yt-dlp search needs no keys and searches SoundCloud and YouTube together. The source APIs use a SoundCloud client ID and/or a YouTube Data API key and show lengths and play counts. If the APIs fail or have no keys, search quietly falls back to yt-dlp, and the settings page says which backend is actually in use (Q73).
- **Native API** at `/cosine/v1/`: `capabilities`, `auth` (Subsonic token + salt in, session token out), `lookup`, `ingest`, `ingest/jobs`, `ingest/jobs/{id}/retry`, `review` with `review/{track}` and its `/candidates`, `/choose`, `/correct` and `/confirm`, and `stats` (`?window=30d|year|2025|all`), `stats/years`, `views` and `views/{name}`, always the caller's own listening. Additive only (§9.3).
- **Title cleaning:** before anything is looked up, what uploaders add is stripped: bracketed noise ("[HD Upgrade]", "(Official Music Video)", "[Free DL]", "(Out Now!)", "[Monstercat Release]"), "PREMIERE:" prefixes, "| Label" tails, hashtags, and YouTube's "Artist - Topic" channels. What names the piece stays: "(VIP)", "(Extended Mix)", "(feat. X)".
- **MusicBrainz (§3.2, tier 1):** every file is looked up by the artist and title its tags, filename or source page suggest, with the length as evidence.
  - A confident match files it under MusicBrainz's canonical artist, release and track number, and stores the MusicBrainz IDs.
  - A song that has ever been on an album is filed under the album, even when it arrived as a single; otherwise its EP, then its single. Compilations, soundtracks and live albums come last. An album the file itself names wins over all of these, unless it's a compilation: YouTube credits videos to whatever compilation claimed them, so a named compilation only beats other compilations, never the artist's own release. Discogs ranks the same way.
  - A compilation is filed under Various Artists and keeps each track's own artist (Q74).
  - A file with the right name but the wrong length (a mix, an extended cut) is never confident.
  - A remix, cover, live, acoustic or instrumental version only matches a title that asks for it, so "Bad at Love" never becomes "Bad at Love (Autograf remix)". This applies to all three catalogues.
  - The studio recording is preferred: official, non-video, non-live recordings are searched first, and a live version, music video or bootleg is scored below review unless the file's own title says "Live". A compilation, live album or DJ mix ranks below the original release, and between equals the earliest release wins.
  - Lookups are held to one per second and cached forever (Q15). If MusicBrainz can't be reached, Cosine backs off for five minutes and files things from their own metadata in the meantime.
- **Discogs (tier 2):** asked when MusicBrainz isn't confident. Searches masters and releases by artist and track, reads the tracklist for the track's length and position (vinyl sides too), and keeps each track's own artist on a Various compilation. Works without an account at 25 lookups a minute; a personal access token in Settings raises that to 60.
- **Bandcamp (tier 3):** asked when neither is confident, through the search Bandcamp's own site uses, then the best hits' track pages for length, album and track number. Bandcamp has no public API, so this can break when Bandcamp changes; it then reads as "Bandcamp is unavailable" and nothing else is affected.
- All three are scored the same way (title, artist, length), so their confidences compare. Every answer is cached forever, and a catalogue that stops answering is skipped for five minutes.
- **Covers:** in the order of §3.2a. The matched release's cover (Cover Art Archive, Discogs or Bandcamp) comes first, then art embedded in the file, then the source site's thumbnail. A better source replaces a worse one.
- **Review queue (§5.3, §6.12):** anything not confidently matched, which includes everything identified only from its own metadata.
  - In Sine it's under Library → Review. In the dashboard it's under Review, with bulk confirm. Most recently added first; older items page in behind.
  - Anything already filed, confidently or not, can be corrected from where it was added: "Correct" on a finished item in Sine's Add screen, or on the dashboard's Recent ingests.
  - The correction screen shows candidates from all three catalogues at once, ranked together and labelled by source (you can search again with other terms), "It's right as it is", and typing the details yourself.
  - A correction applies for everyone and moves the files. If the corrected identity already exists, the two become versions of one Track, and plays, stars and playlists follow.
  - Every change is logged under Corrections, and the latest change to a track can be reverted with one click. A merge can't be undone automatically.
  - Re-resolve (per track or for the whole queue) asks the catalogues again in the background and applies only confident matches. A track none of them knows has its guessed title cleaned again, so music filed before a cleaner improvement catches up.
- **Resolver order:** MusicBrainz, Discogs, Bandcamp, then the file's own metadata (tier 4), meaning the source page, embedded tags, then the filename (`NN Artist - Title`, with yt-dlp's `[id]` suffixes stripped). Tier-4 results always go to the review queue (§5.3).
- **Subsonic API** (JSON and XML), scoped to each account's own library:
  - ping, getLicense, getMusicFolders, getUser
  - getArtists / getIndexes, getArtist, getAlbumList2 (newest, alphabetical, recent, frequent, random, starred), getAlbum, getSong, getRandomSongs
  - search3, getStarred2, star / unstar, getPlaylists, getPlaylist
  - scrobble: plays are stored as timestamped events, and a resent play is ignored
  - stream / download: always the original bytes, with range requests for seeking
  - getCoverArt: embedded artwork, extracted when the file is ingested
- **Listening statistics (§6.13):** the dashboard's Listening page shows everyone's listening or one account's, for the last 30 days, this year, any earlier year or all time: totals and listening time, top artists, albums and tracks with first-played dates, listening over time, and the year by year. The same numbers, and the views Home tiles and smart playlists will be built from (most played, recently played, forgotten, never played, this time last year), are in the native API for Sine to use later.
- **Dashboard:** first-run setup, admin-only login, status with recent ingests, accounts (create and delete; deleting keeps the music), moving the Store (resumable if interrupted), and changing the Inbox.
- **Missing files (§2.2):** the Store is checked at startup and every five minutes, and a play that finds its file gone marks it at once. A track with no file left isn't offered to Sine or any app (it leaves artists, albums, search and playlists) and returns by itself when the file does, with its plays, stars and playlist places. The dashboard's Missing page lists them and can forget them for good; forgetting is refused when every file is missing, which means an unmounted disk.

## Not yet

Push notifications for ingest progress (Sine polls while Add is open), uploads from the phone, user-set covers, audio fingerprinting, playlist editing, invite codes, the cross-user visibility toggle, delta sync (the change log is already being written), loudness analysis, lyrics, orphan cleanup and quotas.

## Layout

| Package | |
|---|---|
| `internal/db` | Schema and migrations (additive only) |
| `internal/store` | Hashing, canonical paths, placing and moving files |
| `internal/resolve` | The resolver chain, title cleaning, shared scoring, and the tier-4 source |
| `internal/musicbrainz` | Tier-1 resolver, the lookup cache, the Cover Art Archive |
| `internal/discogs` | Tier-2 resolver |
| `internal/bandcamp` | Tier-3 resolver |
| `internal/webcache` | Paced, cached HTTP for the catalogues |
| `internal/stats` | Listening statistics and the views tiles and smart playlists share |
| `internal/review` | The review queue, corrections, the correction log and revert, re-resolve |
| `internal/ingest` | The single path into the Store, plus relocation and the missing-file check |
| `internal/inbox` | Filesystem watcher with the settle check |
| `internal/ytdlp` | Drives the yt-dlp binary |
| `internal/search` | yt-dlp and source-API search, with fallback |
| `internal/fetch` | The URL job queue and Add's lookup |
| `internal/native` | Cosine's own API for Sine |
| `internal/auth` | Encrypted passwords (needed for Subsonic token auth), dashboard sessions |
| `internal/subsonic` | The Subsonic API |
| `internal/dashboard` | Admin web UI, deliberately unstyled |
| `internal/server` | Wiring, plus `/cosine/v1/capabilities` for Sine |
