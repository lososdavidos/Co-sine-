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
| `docs/spec/` | The spec and the script that renders it to HTML (`python3 build.py`, needs `pip install markdown`). |

## Status

The first slice is **Sine in compatibility mode**, against a stock Subsonic server (Navidrome):

- Server address → probe → "Subsonic server · compatibility mode" or "Cosine · full features", shown before login
- Token + salt login; the password is never stored
- Several accounts, switched explicitly
- Browse Artists / Albums / Playlists, search, artist and album pages
- Streaming playback of the original files (`format=raw`, no transcoding), with the media notification, lockscreen, Bluetooth and headset controls from Media3
- Pinned downloads of a track, album, artist or playlist into a folder you choose (SAF). A `.nomedia` file is written there, the folders follow `Artist/Album/Track`, and downloads wait for an unmetered connection unless you allow mobile data. The download budget is soft: it warns but never refuses.
- Offline: downloaded music browses, plays and shows artwork with the server gone
- Plays are recorded locally as timestamped events and scrobbled with their real time when the server is reachable

**Cosine** has its core: first-run setup in a dashboard, Inbox ingest into a content-hashed Store, a tags/filename resolver, and the Subsonic API Sine uses. Sine can log in to it in place of Navidrome. Details in [`cosine/README.md`](cosine/README.md).

**Adding music from links:** paste a link or search in Sine's Add screen (the Library's top-right action on a Cosine account), share a link to Sine from any app, or use the dashboard. Cosine fetches it with yt-dlp and files it. Links shared while offline are kept and sent on reconnect.

**Not yet built:** the review queue UI, the local metadata mirror and delta sync (not possible in compat mode), the cache tier, Android Auto browsing, the Home tile canvas, remote control, Jam, and the desktop client.

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
