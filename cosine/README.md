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
  - A link fetched before is refused with "Already in your library." until you choose "Add anyway" (Q12). If another account fetched it, adding it just gives you a Pointer, with no second download.
  - Jobs survive restarts, run two at a time, report progress, and show yt-dlp's own reason when they fail, with a Retry.
  - The source's metadata feeds the resolver: yt-dlp's artist/track/album fields when the site has them, otherwise "Artist - Title" read from the title, otherwise the uploader. The source's cover becomes the artwork when the file has none.
- **Search:** two backends, switched in the dashboard. yt-dlp search needs no keys and searches SoundCloud and YouTube together. The source APIs use a SoundCloud client ID and/or a YouTube Data API key and show lengths and play counts. If the APIs fail or have no keys, search quietly falls back to yt-dlp, and the settings page says which backend is actually in use (Q73).
- **Native API** at `/cosine/v1/`: `capabilities`, `auth` (Subsonic token + salt in, session token out), `lookup`, `ingest`, `ingest/jobs`, `ingest/jobs/{id}/retry`. Additive only (§9.3).
- **Resolver:** only tier 4 so far, meaning embedded tags, then the filename (`NN Artist - Title`, with yt-dlp's `[id]` suffixes stripped). Every result goes to the review queue, as §5.3 requires for tier 4. MusicBrainz, Discogs and Bandcamp go in front of it through the same interface.
- **Subsonic API** (JSON and XML), scoped to each account's own library:
  - ping, getLicense, getMusicFolders, getUser
  - getArtists / getIndexes, getArtist, getAlbumList2 (newest, alphabetical, recent, frequent, random, starred), getAlbum, getSong, getRandomSongs
  - search3, getStarred2, star / unstar, getPlaylists, getPlaylist
  - scrobble: plays are stored as timestamped events, and a resent play is ignored
  - stream / download: always the original bytes, with range requests for seeking
  - getCoverArt: embedded artwork, extracted when the file is ingested
- **Dashboard:** first-run setup, admin-only login, status with recent ingests, accounts (create and delete; deleting keeps the music), moving the Store (resumable if interrupted), and changing the Inbox.
- **Startup check:** files that went missing from the Store are flagged, not removed, and the flag clears when they come back.

## Not yet

Push notifications for ingest progress (Sine polls while Add is open), uploads from the phone, MusicBrainz/Discogs/Bandcamp resolvers, the review/correction UI, playlist editing, invite codes, the cross-user visibility toggle, delta sync (the change log is already being written), loudness analysis, lyrics, orphan cleanup and quotas.

## Layout

| Package | |
|---|---|
| `internal/db` | Schema and migrations (additive only) |
| `internal/store` | Hashing, canonical paths, placing and moving files |
| `internal/resolve` | The resolver chain and its tier-4 source |
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
