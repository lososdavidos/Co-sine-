# Sine & Cosine

A self-hosted music system in two parts:

- **Sine**: the client. Android first; desktop and iOS later.
- **Cosine**: the server. Go + SQLite, replacing Navidrome.

The full design is in [`docs/spec/SPEC.md`](docs/spec/SPEC.md), which is the single source of truth.

## Layout

| Path | What |
|---|---|
| `sine/core` | Pure Kotlin/JVM: Subsonic client, server probe, accounts, downloads index, play log, search ranking. No Android dependency, so it can move to Kotlin Multiplatform later (§5.2a). |
| `sine/app` | The Android app: Compose UI, Media3 playback, WorkManager downloads. |
| `cosine/` | The server: Go + SQLite, one static binary. See [`cosine/README.md`](cosine/README.md). |
| `deploy/` | `install.sh` for Proxmox LXC containers (privileged or unprivileged) and Debian/Ubuntu machines. See [`deploy/README.md`](deploy/README.md). |
| `docs/spec/` | The spec and the script that renders it to HTML (`python3 build.py`, needs `pip install markdown`). |

## Status

The first slice is **Sine in compatibility mode**, against a stock Subsonic server (Navidrome):

- Server address → probe → "Subsonic server · compatibility mode" or "Cosine · full features", shown before login
- Token + salt login; the password is never stored
- Several accounts, switched explicitly
- Browse Artists / Albums / Playlists, search, artist and album pages; pull any list down to refresh it
- Streaming playback of the original files (`format=raw`, no transcoding), with the media notification, lockscreen, Bluetooth and headset controls from Media3
- Pinned downloads of a track, album, artist or playlist into a folder you choose (SAF). A `.nomedia` file is written there, the folders follow `Artist/Album/Track`, and downloads wait for an unmetered connection unless you allow mobile data. The download budget is soft: it warns but never refuses.
- Offline: downloaded music browses, plays and shows artwork with the server gone
- Plays are recorded locally as timestamped events and scrobbled with their real time when the server is reachable

**Cosine** has its core: first-run setup in a dashboard, Inbox ingest into a content-hashed Store, a tags/filename resolver, and the Subsonic API Sine uses. Sine can log in to it in place of Navidrome. Details in [`cosine/README.md`](cosine/README.md).

**Adding music from links:** paste a link or search in Sine's Add screen (the Library's top-right action on a Cosine account), share a link to Sine from any app, or use the dashboard. Cosine fetches it with yt-dlp and files it. Links shared while offline are kept and sent on reconnect.

**Identifying music:** Cosine cleans uploader noise out of titles ("[HD Upgrade]", "(Official Video)", "PREMIERE:"), then looks everything up in MusicBrainz, Discogs and Bandcamp, in that order, and files it under the first confident match's artist and release, with that catalogue's cover. Anything none of them is sure of goes to the review queue (Sine: Library → Review; also in the dashboard). There you pick a match or type the details yourself. Every correction is logged and can be reverted.

**Listening statistics:** Cosine's dashboard has a Listening page with top artists, albums and tracks, listening over time and a year by year view, for everyone or one account. The same data is in the native API, ready for Sine's statistics screen and Home tiles, which aren't built yet.

**Playlists:** make them in Sine (Library → Playlists → New playlist), add songs from any selection on a release, artist or playlist page ("Add to playlist"), and reorder, take songs out of, rename or delete them from the playlist's page. Any other Subsonic app can edit them too. Playlists need the server for now; they aren't in the phone's copy of the library yet.

**Smart playlists:** every account starts with Recently added, Most played this year, Recently played, Forgotten, Never played and This time last year, worked out from your own listening each time you open one. Make your own from the same rules ("New smart playlist"), change a rule, or save one as an ordinary playlist to choose its songs yourself. Other Subsonic apps list them as read-only playlists.

**Albums and playlists from the sources:** searching in Add also finds albums and playlists: YouTube playlists, Bandcamp albums, and SoundCloud albums and playlists when a SoundCloud client ID is set in the dashboard. (YouTube Music albums too, when YouTube Music lists them: it currently shows albums and songs only to signed-in listeners.)

**The song as released:** search results that are the song you searched for say how their length compares with the release ("Same length as the release", "0:36 longer than the release"), using Deezer's listing of it, and those of the release's length come first, the artist's own upload before others. Pick one to see its songs, or paste its link. An album lends its songs its name and track order. A playlist is never filed as an album: each song goes under its own artist and release, and the playlist becomes a playlist in your library, in the source's order, including the songs you already had (untick "Keep it as a playlist" to just take the songs).

**Library mirror:** on a Cosine account, Sine keeps the whole library on the phone and syncs only what changed (on opening, every minute while open, on a pull, and right after you add or correct something). Library, artist and album pages and search read it: instant, searchable as you type, and all there offline, with what isn't downloaded dimmed. Compat mode (a plain Subsonic server) asks per screen as before.

**Removing songs:** select songs on a release or an artist page and choose "Remove from library", or remove a whole release from its page. They stay removed even if the same song lands in the Inbox again, though adding one again on purpose brings it back. Songs no account has any more show up in the dashboard under Unused files, where you choose to delete their files.

**Artist pages:** an artist's albums and singles are one list by date, and a single shows as the song itself, not as an album holding one track.

**Artist pictures:** Cosine finds a picture for each artist from Deezer, matched through MusicBrainz or by a shared song, and Sine shows it round.

**Not yet built:** audio fingerprinting, the mirror's live push and offline playlist edits, shared playlists, the cache tier, Android Auto browsing, the Home tile canvas and the statistics screen in Sine, Last.fm/ListenBrainz scrobbling, remote control, Jam, and the desktop client.

## Design

The UI is **deliberately unstyled**: Material 3 defaults, with no visual system encoded in components. The design language in the spec (Graphit v0.7) will be replaced, so styling waits until that decision is made.

## Building

```sh
cd sine
./gradlew :core:test          # pure JVM, no Android SDK needed
./gradlew :app:assembleDebug  # needs the Android SDK
```

```sh
cd cosine
go test ./...                 # needs Go 1.26 (see go.mod)
```

CI builds and tests both on every push. It uploads the debug APK and static Cosine binaries for linux amd64/arm64 as artifacts.
