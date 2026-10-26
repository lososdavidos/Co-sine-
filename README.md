# Sine & Cosine

A self-hosted music system in two parts:

- **Sine**: the client. Android first; desktop and iOS later.
- **Cosine**: the server. Go + SQLite, replacing Navidrome.

The full design is in [`docs/spec/SPEC.md`](docs/spec/SPEC.md), which is the single source of truth.

## Layout

| Path | What |
|---|---|
| `sine/core` | Pure Kotlin/JVM: Subsonic client, server probe, accounts, downloads index, play log, search ranking. No Android dependency, so it can move to Kotlin Multiplatform later (§5.2a). |
| `sine/app` | The Android app: Compose UI in Phase (`ui/phase`), Media3 playback, WorkManager downloads. |
| `cosine/` | The server: Go + SQLite, one static binary. See [`cosine/README.md`](cosine/README.md). |
| `deploy/` | `install.sh` for Proxmox LXC containers (privileged or unprivileged) and Debian/Ubuntu machines. See [`deploy/README.md`](deploy/README.md). |
| `docs/spec/` | The spec and the script that renders it to HTML (`python3 build.py`, needs `pip install markdown`). |
| `third_party/fonts/` | Instrument Sans and Martian Mono, Phase's typefaces, with their licences (SIL OFL 1.1). `scripts/fonts.py` cuts them into Sine's and the dashboard's fonts. |

## Status

The first slice is **Sine in compatibility mode**, against a stock Subsonic server (Navidrome):

- Server address → probe → "Subsonic server · compatibility mode" or "Cosine · full features", shown before login
- Token + salt login; the password is never stored
- Several accounts, switched explicitly
- Browse Artists / Albums / Playlists, search, artist and album pages; pull any list down to refresh it
- Streaming playback of the original files (`format=raw`, no transcoding), with the media notification, lockscreen, Bluetooth and headset controls from Media3
- Shuffle that plays every song once, from the start of each, and Repeat (the queue, or one song); Up next shows the order they'll play in
- Like a song with two taps on Add to playlist in Now playing (one tap picks a playlist); it's Subsonic's star, so other apps see it, and it puts the song in Liked songs
- Pinned downloads of a track, album, artist or playlist into a folder you choose (SAF). A `.nomedia` file is written there, the folders follow `Artist/Album/Track`, and downloads wait for an unmetered connection unless you allow mobile data. The download budget is soft: it warns but never refuses.
- Offline: downloaded music browses, plays and shows artwork with the server gone
- Plays are recorded locally as timestamped events and scrobbled with their real time when the server is reachable

**Cosine** has its core: first-run setup in a dashboard, Inbox ingest into a content-hashed Store, a tags/filename resolver, and the Subsonic API Sine uses. Sine can log in to it in place of Navidrome. Details in [`cosine/README.md`](cosine/README.md).

**Adding music from links:** paste a link or search in Sine's Add screen (the Library's top-right action on a Cosine account), share a link to Sine from any app, or use the dashboard. Cosine fetches it with yt-dlp and files it. Links shared while offline are kept and sent on reconnect.

**Identifying music:** Cosine cleans uploader noise out of titles ("[HD Upgrade]", "(Official Video)", "PREMIERE:"), then looks everything up in MusicBrainz, Discogs and Bandcamp, in that order, and files it under the first confident match's artist and release, with that catalogue's cover. Anything none of them is sure of goes to the review queue (Sine: Library → Review; also in the dashboard). There you pick a match or type the details yourself. Every correction is logged and can be reverted.

**Listening statistics:** Cosine's dashboard has a Listening page with top artists, albums and tracks, listening over time and a year by year view, for everyone or one account. The same data is in the native API, ready for Sine's statistics screen, which isn't built yet.

**Playlists:** make them in Sine (Library → Playlists → New playlist), add songs from any selection on a release, artist or playlist page ("Add to playlist"), and reorder, take songs out of, rename or delete them from the playlist's page. Any other Subsonic app can edit them too. On a Cosine account they're part of the phone's copy of the library: they open, play and download with no connection, and changes made offline show at once and reach Cosine when you're back. Cosine replays what you did (added this, moved that) rather than overwriting the list, so changes made on another device meanwhile stay. A playlist with changes Cosine doesn't have yet says how many on its page.

**Shared playlists:** share one of your playlists with another account on the server (the playlist's page → Share). They see it under "Shared with you" in their playlists; accepting puts its songs in their library, so it plays in full, and from then on you both see and change the same playlist. They can leave it; you can stop sharing or delete it for everyone. Smart playlists aren't shared: save one as a playlist first.

**Smart playlists:** every account starts with Recently added, Most played this year, Recently played, Forgotten, Never played, This time last year, Liked songs and Favourites (what you've played most in the last 30 days), worked out from your own listening each time you open one. Accounts made before Liked songs and Favourites existed get them once. Make your own from the same rules ("New smart playlist"), change a rule, or save one as an ordinary playlist to choose its songs yourself. Other Subsonic apps list them as read-only playlists.

**Albums and playlists from the sources:** searching in Add also finds albums and playlists: YouTube playlists, Bandcamp albums, and SoundCloud albums and playlists when a SoundCloud client ID is set in the dashboard. Each source's results show as soon as it answers.

**The song as released:** search results that are the song you searched for say how their length compares with the release ("Same length as the release", "0:36 longer than the release"), using Deezer's listing of it, and those of the release's length come first, the artist's own upload before others. Pick one to see its songs, or paste its link. An album lends its songs its name and track order. A playlist is never filed as an album: each song goes under its own artist and release, and the playlist becomes a playlist in your library, in the source's order, including the songs you already had (untick "Keep it as a playlist" to just take the songs).

**Library mirror:** on a Cosine account, Sine keeps the whole library on the phone and syncs only what changed (on opening, every minute while open, on a pull, and right after you add or correct something). Library, artist and album pages and search read it: instant, searchable as you type, and all there offline, with what isn't downloaded dimmed. Compat mode (a plain Subsonic server) asks per screen as before.

**Removing songs:** select songs on a release or an artist page and choose "Remove from library", or remove a whole release from its page. They stay removed even if the same song lands in the Inbox again, though adding one again on purpose brings it back. Songs no account has any more show up in the dashboard under Unused files, where you choose to delete their files.

**Artist pages:** an artist's albums and singles are one list by date, and a single shows as the song itself, not as an album holding one track.

**Scrobbling:** Cosine sends your plays to Last.fm or ListenBrainz, whichever app played them, including plays made offline, with the time you played them. Connect in Sine (Settings → Scrobbling): ListenBrainz takes your user token; Last.fm needs an API key in Cosine's dashboard once (Settings → Scrobbling), then you approve Cosine on Last.fm.

**Home:** a canvas of tiles you arrange, in four columns: Now playing (or what played last, to pick up from there), Shuffle all, what's on the phone, the review queue, what Cosine is adding, your smart playlists, and any artist, album or playlist you pin from its page (Pin to Home). Long-press a tile to resize it, move it (or drag it), make it shuffle instead of open, or unpin it; Add tile (the + on Home) brings back the rest. Covers take turns on a tile and counts roll over as they change. The arrangement is kept on the phone and, on a Cosine account, on the server too, so it comes back on a new phone; the phone's and the desktop's are kept apart.

**Artist pictures:** Cosine finds a picture for each artist from Deezer, matched through MusicBrainz or by a shared song, and Sine shows it round.

**Not yet built:** audio fingerprinting, the mirror's live push, the cache tier, Android Auto browsing, the statistics screen in Sine, remote control, Jam, and the desktop client.

## Design

Sine and Cosine's dashboard use **Phase**, the design language that replaced Graphit v0.7 in 0.5.2 (spec §6):

- **Monochrome, with two blues.** Sine (blue) is you, now: what's playing, what's selected, the one action a screen is for. Cosine (deep blue, the same hue darker) is the server at work: fetching, matching, syncing, waiting for your review. Both are blues that stay blue, never leaning violet, and the greys share their faint cool tint. Red is for faults and deleting. Everything else is grey or artwork.
- **Type carries the names; icons carry the actions.** Names in Instrument Sans, condensed and large; lengths, counts and sources in Martian Mono. Navigation, transport and what you do to a thing (download, add to a playlist, pin, share, rename, reorder, remove) are icons. Where you are is named beside its icon, and holding an icon says its name in the shelf. Words stay for forms and questions, so anything that deletes still asks in words first.
- **Slightly 3D icons.** Each icon is lit from above: its face fades a shade darker toward the bottom, the same shape sits a little lower in a darker tone as its depth, and coloured ones catch a hairline of light along their top edges. No blur, no perspective. The dashboard draws them the same way.
- **Shape says what a thing is.** Square: something you read (a cover, a tag). Circle: a person. Pill: something you press. Slab: a surface that holds things.
- **No glass.** Surfaces step up in lightness from a near-black ground, so every Android version, the widget and the web look the same. Nothing rests blurred.
- **Things slide, with the finger.** A page opens from the right and goes back out to the right; swipe left to right anywhere on it to go back. Swipe sideways to move between Home, Search and Library. Now playing comes up from the bottom and is dragged back down. A gesture follows the finger, holds when it holds, and on release finishes or goes back. The page underneath blurs as it's covered and sharpens as it's uncovered: Phase's one blur, and only while something moves. Small forms are pages too; Sine has no pop-ups. On Home, the tiles stay put while what's inside them slides, spins and fades.
- **The shelf** at the bottom is navigation, the player, the actions for a selection, and where Sine talks back: a short message above navigation, or a question that waits for an answer.

Sine's implementation is `sine/app/src/main/kotlin/app/sine/ui/phase` (tokens, type, parts, the icons, the wave) and `ui/Shelf.kt`; the dashboard's is one stylesheet, `cosine/internal/dashboard/static/phase.css`, served from the binary with the fonts. Phase is dark only.

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
