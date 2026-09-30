# Sine & Cosine — Spec Sheet

Single working document. Everything lives here; sections grow as decisions land.

**Status:** COMPLETE DRAFT · 126 resolved decisions · last updated 2026-08-29

**The system is greenfield.** Nothing in this document is built yet. The status
tags below exist for later, as construction starts; today everything is
`PLANNED` unless stated otherwise. That also means no decision here is
constrained by an existing implementation.

---

## 0. Conventions

| Tag | Meaning |
|---|---|
| `BUILT` | Works today, as described |
| `PARTIAL` | Exists but incomplete or wrong |
| `PLANNED` | Agreed, not written |
| `OPEN` | Not decided yet |

Components:

- **Sine** — the client. Android first, iOS later.
- **Cosine** — the server. Replaces Navidrome.

"Client" and "Server" are used interchangeably with these names throughout.

Nouns (used consistently throughout):

| Term | Meaning |
|---|---|
| **Store** | The one folder holding every physical audio file. Its location is set in the server dashboard and can be moved at any time. **The server is its sole writer.** |
| **Inbox** | A watched folder outside the Store. Anything dropped in is picked up and run through the ingest pipeline, exactly as a download would be. |
| **Track** | A resolved musical identity — what the tree shows and what a Pointer refers to. |
| **Object** | One physical audio file in the Store. A Track may have several (a 320 MP3 and a FLAC of the same song). |
| **Pointer** | A per-account, lightweight reference to a Track. Database row; carries that account's view — added-at date, play count, favourite, download state, optional version pin. |
| **Library** | One account's set of Pointers, presented as a folder tree. The tree mirrors the Store's canonical structure, filtered to what that account has. |
| **Playlist** | A per-Library ordered list of Pointers. Can be shared with other accounts. |

---

## 1. Goals

Ranked. When two goals conflict, the higher-numbered one loses.

**G1 — Playback never depends on the network.**
Anything downloaded plays with the server unreachable: queue, artwork, metadata,
seek, gapless, play-count recording. Opening the app offline is a normal state
with a full UI, not an error screen.

**G2 — Browsing feels local.**
The client holds a local mirror of library metadata and syncs deltas, rather
than querying per screen. No spinner between tapping an artist and seeing their
releases, on mobile data, cold app.

**G3 — The library model fits the actual library.**
Loose tracks, DJ mixes, remixes, aliases and untagged rips are first-class.
Nothing is forced into a fake album.

**G4 — Getting music in is part of the product.**
Ingest is a feature of the server with visible state — paste a link or search,
and it appears. Not a cron job you inspect over SSH.

**G5 — Client and server evolve as one.**
The native protocol is yours. A new feature that needs a new endpoint is an
afternoon, not a fork of somebody else's project.

**G6 — Cheap to run and cheap to fix.**
Idles near zero CPU on the P450. Scanning doesn't make the box unusable. State
is inspectable; the Store still makes sense as folders of music without the
server running.

**G7 — One design language, applied properly.**
Graphit v0.7. Black ground, four muted primaries used only as opaque planes,
solid document surfaces, floating glass chrome. See §6.

**G8 — Portable to iOS later.**
Nothing in the client design bets on Android-only capability; nothing on the
server assumes one client.

## 1.1 Non-goals

- **NG1** Not a public, multi-tenant service. Tailscale is the perimeter.
- **NG2** Not a general media server. No video, podcasts or audiobooks — Jellyfin keeps that job.
- **NG8** No migration from Navidrome. `RESOLVED` — the new system starts empty and the library is rebuilt by re-ingesting what actually gets listened to. The result is curated rather than accumulated, and it avoids a bulk import that would generate thousands of low-confidence matches on day one. The old library stays where it is; nothing is lost by starting clean.
- **NG3** No recommendation engine or algorithmic discovery. `OPEN`: radio / similar-artist is a plausible exception.
- **NG4** No social layer — no feeds, comments, or profiles. `REVISED`: shared playlists and **Jam** (§4C) are deliberate exceptions. Users can listen together and share collections; they cannot follow, comment on or broadcast to each other.
- **NG5** Not a tag editor of record. Tags are largely disregarded by design (see §3.2).
- **NG6** Not a full Subsonic implementation. Jukebox, chat, podcasts, bookmarks and video endpoints are out.
- **NG7** No transcoding. The server stores and serves originals, nothing else.
- **NG9** No terminal client. `RESOLVED` — Cosine is a service; the dashboard and Sine are how you talk to it. `graphit.sh` stays useful for other tools, but neither a CLI nor a TUI player is in scope.
- **NG10** Accessibility is not specified. `RESOLVED` — a deliberate omission for a personal app rather than an oversight. Graphit's contrast floors, focus rings and reduced-motion rule apply regardless because they are in the system, and its 48dp hit-area rule on 32px rows costs nothing to honour. Screen-reader support and large-font-scale layouts are out of scope unless that changes.

---

## 2. Core architecture

### 2.1 Two server modes

| Mode | Backend | Capability |
|---|---|---|
| **Compat** | Stock Subsonic server (Navidrome) | Standard Subsonic API only. Client behaves as a well-mannered Subsonic client. |
| **Native** | Your server | Extended protocol: per-user Libraries, ingest, upload, delta sync, multi-user visibility. |

The client carries a **capability model** — it asks what the server supports and
adapts the UI. Native-only features are not assumed.

`RESOLVED` **In compat mode, native-only features are hidden entirely.** No
ingest, no cross-user browsing, no review queue, no version pins — Sine simply
presents as a clean, ordinary Subsonic client with no dead ends and no disabled
buttons advertising what you can't have.

`RESOLVED` **Sine can hold several accounts and servers at once, switched
explicitly.** Each carries its own mirror, its own downloads and its own
settings. No merged view — two servers holding the same track would produce
nothing but confusion.

`NOTE` This is what makes the transition practical: Sine can sit against the
live Navidrome and a half-built Cosine at the same time, and you switch when
Cosine is actually better. It also means device storage is consumed per account,
which the storage caps need to account for.

`RESOLVED` Compat mode does not get the delta-sync experience (G2 degrades
against Navidrome). Accepted.

### 2.2 Store, Objects, Pointers, Libraries

- All audio lives in **one Store**, whose path is configured in the server
  dashboard and **can be relocated at any time**. Objects are addressed
  relative to the Store root, so moving the Store does not break Pointers.
- A track two users both have is **one Object, two Pointers** — stored once.
- Pointers are **lightweight** — negligible storage cost, so per-user Libraries
  are cheap however many users exist.
- Per-user state (play counts, favourites, position in the tree) lives on the
  Pointer, never on the Object.
- Deleting a user deletes Pointers, not Objects.

`RESOLVED` The Store is a single logical location, movable, not pinned to one
disk forever. This satisfies the internal → SD → NAS migration path without
needing multi-root support.

`RESOLVED` **Pointers are database rows.** The database is the source of truth.
The server can *materialise* a symlink tree for an account on demand, when
something outside the system needs to see a Library as folders (SMB, a desktop
tool, a backup job). Materialised trees are derived artefacts and never
authoritative.

`RESOLVED` **Store layout is `Artist / Album / Track`.** A track with no album
gets an album folder named after the track itself — a single is a release of one
song, which is also how MusicBrainz models it, so the tree stays uniform with no
special cases and no "loose files" area.

`RESOLVED` **A missing Object greys out rather than disappearing.** If a disk is
unplugged or a file is deleted outside the system, the Track stays listed and
marked unavailable, playlists keep their entry, and everything returns to normal
when the file does. The library never silently shrinks.

`NOTE` Natural extension, not decided: for anything that came from a URL, the
server already knows where it was fetched from, so an unavailable Track could
offer to re-download itself. Cheap to add once ingest exists.

`RESOLVED` **The canonical folder structure lives in the Store, shared.** One
Artist/Release tree on disk, built by the resolver (§3). Every Library is a
*view* onto that same structure, filtered to the Objects that account has. The
Store therefore stays human-readable and survives the server (G6).

Consequence worth stating plainly: because the structure is canonical and
shared, a Library is a **selection**, not a rearrangement.

`RESOLVED` **Two layers of organisation:**

1. **The tree** — `Artist / Release / Track`. Canonical, identical for everyone,
   generated by the resolver. This is what you browse.
2. **Playlists** — per-Library, user-made, freely ordered, and **shareable
   between accounts**. This is where personal organisation lives.

Nothing else. No per-user renaming, no alternative trees.

`RESOLVED` **A Track may have multiple Objects.** Different rips or qualities of
the same song are linked by the resolver as versions of one Track. The tree
shows one entry; the player picks the best available Object for the situation
(highest quality when downloading, whatever is cached when offline).

`RESOLVED` **Version selection is the data lever.** With no pin set, the client
takes the best-quality Object on WiFi and the smallest on mobile data. Since no
transcoding happens, choosing between Objects that already exist is the only way
to spend fewer bytes on data — which makes deliberately keeping a small version
of heavily-played Tracks a genuinely useful thing to do.

`RESOLVED` **Pointers reference Tracks, not Objects.** You have a song, not a
file; the server serves whichever Object suits the moment. A Pointer may carry
an optional **version pin** — "always give me the FLAC for this one" — which
overrides automatic selection.

`RESOLVED` **Version matching uses resolved identity only.** Two Objects are the
same Track when the resolver lands them on the same entity. No audio
fingerprinting.

⚠️ Limitation to accept knowingly: for material that only ever resolves at
tier 4 (source metadata), two independent rips of the same song — different
uploader, different title formatting — will not be recognised as versions and
will appear as two Tracks. Given the genre, this will happen. Fingerprinting
(AcoustID/Chromaprint) is the escape hatch if it becomes annoying; it slots in
as an extra matcher without changing the model.

`RESOLVED` **Object identity is the content hash.** Consequences, which are not
optional:

- Identity survives moves and re-resolution — a file can be refiled anywhere in
  the Store without breaking Pointers, playlists or play history.
- Dedup is free: an ingest whose hash already exists creates a Pointer, not an
  Object.
- **The server must never rewrite an audio file in place.** No tag writing, no
  re-encoding a stored Object. Rewriting changes the hash and severs every
  Pointer to it. This is compatible with §3.3 (tags are disregarded anyway), but
  it has to be an explicit rule, not an accident.

### 2.3 Multi-user

- Multiple accounts, each with its own Library.
- A **server setting** controls whether accounts can see one another's Libraries.
- Per-user playback state throughout.

`RESOLVED` **Cross-user acquisition is a first-class feature.** Where visibility
is enabled, browsing another account's Library and adding a track creates a
Pointer in your own — no copy, no transfer, instant. This is the payoff of the
Object/Pointer split, and arguably the reason the model exists.

Follows automatically: if A adds a track from B and B later removes theirs, A
keeps it. The Object survives as long as any Pointer references it.

`RESOLVED` **Playlists can be shared between accounts.** Accepting a shared
playlist **auto-adds Pointers** for any Tracks the recipient doesn't already
have, so a shared playlist always plays in full.

Note the side effect: accepting a playlist grows your Library without an
explicit per-track decision. Worth an "added N tracks" confirmation rather than
silence.

`RESOLVED` **Visibility is one global server toggle.** Either every account can
browse every other, or none can. No per-user or per-pair rules — at this scale
they'd be state nobody ever changes.

`RESOLVED` **Accounts are created two ways:** the admin creates them directly
from the dashboard, or issues an **invite code** that the new user redeems in
the app and sets their own password against. No open registration.

`RESOLVED` **Deleting an account removes its Pointers, never its Objects.**
Music that entered the system stays in the Store regardless of who fetched it;
anyone else holding a Pointer is unaffected. Objects left with no Pointers
become orphans, handled by GC below.

`RESOLVED` **Orphan GC is manual by default, optionally automatic.** The
dashboard lists orphaned Objects with their sizes and lets you delete them.
Automatic collection after a configurable grace period can be switched on if
you'd rather not think about it. Manual is the default because an automatic
sweep will eventually delete something you meant to keep.

`NOTE` Because the Store is shared, library privacy is enforced only in the
server's query layer. Anyone with shell or file access to the box sees every
user's files. Acceptable at this scale, but stated rather than discovered.

### 2.4 Playlists

`RESOLVED` **Two kinds.**

| Kind | What it is |
|---|---|
| **Manual** | An ordered list of Pointers. Reordered by hand, synced by operation log (§5.2). |
| **Smart** | A saved rule evaluated against your own Library — added this month, never played, most played this year, not played in six months, downloaded only. |

No folders or nesting. That earns its keep past a hundred playlists and this
library will not have one for years.

`NOTE` Smart playlists and Home tiles are the same idea twice. Several tiles in
§6.4.1 — *forgotten*, *never played*, *most played* — are smart playlists that
cannot be saved. They should be **one feature**: a smart playlist is the object,
and a Home tile is one way to look at it. Building them separately would mean
writing the same rule engine twice with different syntax.

`RESOLVED` **Smart playlists are not shareable.** Their rules reference the
owner's own play history, so they mean something different for everyone —
"never played" evaluated in someone else's Library is a different list, often an
empty one. Sharing one means converting it to a manual playlist yourself first,
which is an honest one-step action rather than a feature that quietly does
something unexpected.

### 2.5 Caching and network policy

Two profiles, differing in **degree**, not in kind:

| | WiFi | Mobile data |
|---|---|---|
| Buffering | Aggressive — deep lookahead | Reduced lookahead, still buffers ahead |
| Gapless | Guaranteed | Preserved — lookahead never drops below what gapless requires |

`RESOLVED` X1 is not a real conflict. "Conservative" means *less* prefetch, not
*no* prefetch. The floor is whatever gapless needs; the WiFi profile goes well
beyond it.

Downloaded (explicitly kept) music is always local and needs no network.

`RESOLVED` **Offline availability is rule-driven, with a learning cache.**

- **Rules** decide what is pinned: keep favourites, keep anything added in the
  last N days, keep these playlists synced. Maintained automatically on WiFi
  while charging.
- **The cache** fills opportunistically and learns — what you actually replay
  stays, what you skipped goes first under pressure.

Manual pinning still exists on top; rules are a convenience, not the only way in.

`RESOLVED` **Download unit is any node or playlist** — a track, an album, a
playlist, or an artist's whole folder. No separate sync concept needed.

`RESOLVED` **Downloads live in a visible folder** on the device, not app-private
storage. They survive uninstall and can be copied out with a file manager.

⚠️ Three real costs, since this is the harder option:

- **Scoped storage.** Android 11+ makes writing to a user-visible folder
  awkward. The workable route is the Storage Access Framework — the user picks
  the folder once, the app holds a persisted URI permission. Plan for it rather
  than discovering it.
- **Every other media app will index it.** MediaStore scans visible folders, so
  your downloads appear in every other player, gallery-style app and car system
  on the phone. A `.nomedia` file in the root suppresses that, and should
  probably be written automatically.
- **Files are byte-identical to the Store's Objects,** which is the point — but
  it also means anyone with the phone has your library as plain files.

`NOTE` Recommendation: only **pinned** downloads need to be visible. The cache
tier is transient and internal, and putting it in app-private storage avoids
polluting the visible folder with files the user never asked for. That split
costs nothing and makes the visible folder mean something.

`RESOLVED` **Two storage tiers on the device:**

| Tier | Behaviour |
|---|---|
| **Pinned** | Explicit downloads. Never evicted. Own budget. Visible folder. Guarantees G1. |
| **Cache** | Filled by the WiFi prefetch policy. Evictable under pressure, own cap. |

`RESOLVED` **Metered detection follows Android's metered flag,** not "is it
WiFi". A tethered hotspot is correctly treated as expensive — which is exactly
the case where getting it wrong costs real money.

Predictability is the point — what you pinned is always there, regardless of how
much listening the cache has done since.

`RESOLVED` **No transcoding. Originals only.** The server never re-encodes;
client streams and downloads the file as stored. This follows from G6 (the P450
stays idle) and from §2.2 (Objects are never rewritten).

⚠️ Consequence for §2.4: with no transcoding, the mobile-data profile can only
control *how far ahead* it buffers, not *how large* each track is. A FLAC-heavy
library will be expensive on data no matter how conservative the prefetch. If
that becomes a problem, the escape hatch is a lower-quality **second Object**
per Track (now cheap, since Tracks support multiple Objects) — not on-the-fly
transcoding.

---

## 3. Ingest

The primary way music enters the system. `PARTIAL/PLANNED`

### 3.1 Flow

1. User supplies a **link**, or uses the **in-app yt-dlp search** to find a track.
2. Server runs yt-dlp and downloads the audio.
3. Server runs the **resolver** (§3.2) to identify the track.
4. Server derives a **folder path** from the resolved identity and files the
   Object into the Store's canonical tree.
5. A Pointer is created in the requesting user's Library.

This makes ingest a server-side, user-initiated operation with visible state —
the concrete form of G4, and the replacement for the yt-dlp/beets cron pipeline.

### 3.1a Sources, search and format

`RESOLVED` **Any source yt-dlp supports.** SoundCloud, YouTube, Bandcamp,
Mixcloud and the rest — yt-dlp maintains the extractors, the server doesn't
maintain a whitelist.

`RESOLVED` **Search has two switchable backends,** selected in the dashboard:

| Backend | Behaviour |
|---|---|
| **Source APIs** | Query SoundCloud / YouTube directly. Richer results — artwork, durations, play counts — so the picker can show what you're choosing between. Needs API keys and breaks when the APIs change. |
| **yt-dlp search** | Hand the query to yt-dlp. No keys, one dependency, results only as good as yt-dlp's own search. |

The chosen result is a URL either way; ingest from that point is identical.

`RESOLVED` **Best available stream, stored untouched.** The server takes the
highest-quality audio the source offers and writes it to the Store exactly as
downloaded — no re-encode, no remux, no container normalisation. The library
will hold mixed formats (Opus, M4A, MP3) and the client decodes all of them.

This is the only choice consistent with §2.2: the hash is the file you actually
received, and nothing ever rewrites it. Re-encoding an already-lossy source
would cost quality for nothing.

`RESOLVED` **Bulk ingest is offered, and you choose the mode** per operation:

- **Queue everything** — paste a set, album or channel URL and take the lot.
- **Review first** — expand the collection, show the track list, pick what to
  keep before anything downloads.

### 3.2 The resolver

`RESOLVED` MusicBrainz is **one source among several**, not the authority.

The resolver tries sources in order and stops at the first confident match:

| Order | Source | Good for |
|---|---|---|
| 1 | MusicBrainz | Properly released music, canonical artist/release identity |
| 2 | Discogs | Vinyl, bootlegs, white labels, reissues MB doesn't carry |
| 3 | Bandcamp | Independent and self-released — much of this genre lives here |
| 4 | Source metadata (SoundCloud / yt-dlp) | Everything else: exclusives, VIP edits, unreleased rips |
| 5 | Ask the user | Only when all of the above fail or conflict badly |

Every resolved track records **which source identified it** and a **confidence**,
so low-confidence entries are visible and correctable rather than silently
wrong. `OPEN` — how confidence is expressed in the UI.

`RESOLVED` **Corrections are global.** The Store is canonical, so fixing a bad
match refiles the Object for everyone. Because identity is the content hash,
this is a move, not a rebuild — nothing else breaks.

⚠️ Note the social edge: in a multi-user server, one user's correction changes
another user's view. At two users that's fine. It should at minimum be logged,
so a bad correction can be traced and reversed.

### 3.2a Artwork

`RESOLVED` Three sources, in order:

1. The resolved release's cover (Cover Art Archive, Discogs, Bandcamp), fetched
   at ingest and cached server-side.
2. Artwork embedded in the file, where the resolver found nothing.
3. A user-set image — which matters, because much of the unreleased material
   will have no art from any source.

`RESOLVED` **A user-set cover is personal to that account,** not global. Unlike
a match correction, artwork is taste rather than fact: two people can reasonably
disagree about the right cover for an untitled bootleg, and neither is wrong.

Consequences: artwork override is **Pointer state**, the chain above resolves
per account, and two people in a Jam may see different covers for the same
Track. That is acceptable — they are hearing the same audio, which is the part
that matters.

### 3.3 Metadata precedence

`RESOLVED` **Folder structure wins. Embedded tags are largely disregarded** —
deliberately. The resolver-derived path is the truth about what a track is;
whatever a SoundCloud rip claims in its ID3 frames is not.

### 3.4 Why the resolver is not optional

The library is wave and underground — Skeler, barnacle boi, Deadcrow, plenka,
rxrrim — much of it SoundCloud-exclusive: bootlegs, VIP edits, unreleased rips
and long DJ mixes. A large share of it is not in MusicBrainz at all, and some of
what is will match the wrong release. A MusicBrainz-only pipeline would fail on
exactly the music you listen to most, so the fallback chain is the common path,
not the exception. It should be built and tested against the hard cases first,
not the easy ones.

---

## 4. Upload of local files

`PLANNED` Native mode only — the Subsonic API has no upload.

`RESOLVED` An upload is **just another ingest source**. It runs the full
resolver (§3.2) and is filed into the canonical tree exactly as a yt-dlp fetch
would be. One code path, no special-case storage area.

Per §2.2, the uploaded file itself is stored byte-for-byte as received — the
resolver decides *where* it goes, never *what it contains*.

### 4.1 The Inbox

`RESOLVED` Nothing writes into the Store but the server. Getting files in from
outside the app is handled by a **watched Inbox folder**: drop files there over
SSH, SMB, rclone, whatever, and the server picks them up, hashes them, resolves
them, files them into the canonical tree and creates a Pointer.

This gives the flexibility of dropping files on the box without any of the
reconciliation problems of letting outside tools edit the Store directly. The
Store's contents can never drift from the database, because only one process
ever writes there.

`RESOLVED` **The Inbox is watched by filesystem events** — a dropped file starts
ingesting within seconds rather than waiting for a scan.

`NOTE` Implementation requirement, not a decision: a file being copied in over
SMB or SFTP fires events long before it is complete. The watcher must wait until
a file's size has stopped changing before hashing it, or it will ingest
half-written audio and store a hash for a file that no longer exists a second
later.

`RESOLVED` **An Inbox drop gives every account a Pointer.** The Inbox is a
broadcast channel: anything dropped there lands in everyone's Library, and
anyone who doesn't want it removes it from their own. Opt-out, not opt-in.

It only makes sense at small scale. With two users it's a convenience; with
twenty it's spam. Fine, given NG1.

`RESOLVED` **Every removal writes a dismissed marker.** Removing a Track from
your Library records "this account does not want this Track", not just an
absence. One consistent rule, applied to manual removals, Inbox drops and shared
playlist adds alike, so nothing you have rejected can ever come back on its own.

Consequence to accept: a shared playlist that auto-adds Tracks (§2.3) will skip
anything you previously dismissed, so that playlist plays with a hole in it. The
alternative — letting a share silently override your removal — is worse. The
playlist should show the gap rather than hide it.

---

## 4A. Playback

`RESOLVED` **Queue is server-side state.** The current queue and position live on
the server, so playback resumes across devices — phone to browser to any
Subsonic client. The Subsonic API already carries a play-queue endpoint, so
third-party clients get this for free.

`RESOLVED` **Scrobbling is server-side.** Last.fm / ListenBrainz credentials
live on the server, which scrobbles when a play arrives. Offline plays that sync
up later are scrobbled correctly, and Subsonic clients scrobble without any
setup of their own. `OPEN` — how late-arriving offline plays are timestamped.

`RESOLVED` **Volume normalisation is computed at ingest and stored in the
database.** Loudness analysis runs once per Object; the value is metadata, never
written into the file. The client applies gain at playback. This is the only
approach compatible with §2.2 — and it means album-level gain is available too,
since the analysis knows what release the Object belongs to.

`RESOLVED` **True gapless where the format allows it.** Media3's concatenating
source gives decoder-level gapless for formats carrying the right encoder-delay
and padding information; everything else falls back to seamless-as-possible with
the next track preloaded. Gapless is a quality target, not an absolute
requirement — it is not worth constraining prefetch on mobile data to guarantee
it across a streamed boundary.

`RESOLVED` **A sleep timer** — stop after N minutes, or at the end of the
current track. The only player extra. No playback speed (this is not a podcast
player, NG2), no equaliser, no silence trimming — the last would fight the
gapless work directly.

`RESOLVED` **Lyrics are fetched at ingest and cached by Cosine.** They belong to
the Track, not the Pointer — the words do not differ per account.

`NOTE` The source that fits this system is **LRCLIB**: free, no authentication,
no rate-limit gymnastics, built for self-hosted players, and it serves
time-synced `.lrc` as well as plain text. Genius and Musixmatch both bring
scraping or licensing problems that do not belong in a personal server.

⚠️ For most of this library there will be no lyrics, and much of it is
instrumental. The resting state — a screen that says nothing is available —
will be the common case, so it should be a quiet absence rather than an error. No cue-sheet parsing, no
chapters, no special resume handling.

`NOTE` Partial resume arrives anyway, for free: because the queue and position
are server-side state, a long track picks up where it left off as long as the
queue hasn't moved on. What's missing is only per-track position memory *after*
you've played something else and come back. If that turns out to be the actual
annoyance, it's a small addition to the Pointer, not a redesign.

---

## 4B. Playback devices and remote control

`RESOLVED` **Sine also exists on the desktop.** A real desktop client, not a web
player — which the Kotlin decision (§5.2a) makes cheap, since Compose
Multiplatform reaches desktop from the same codebase and the model and sync
modules are shared unchanged.

⚠️ One real gap: **Media3 is Android-only.** The desktop client needs its own
playback backend — VLCJ, GStreamer or equivalent — behind the same interface.
UI, model, sync and library code are shared; the audio layer is not.

`RESOLVED` **Any device can control any other device on the same account.** The
phone drives what the desktop is playing: transport, queue, volume, seek.

`RESOLVED` **Control is direct, over the LAN.** Devices discover each other by
mDNS and talk peer-to-peer, without Cosine in the path, so remote control keeps
working when the server is down.

⚠️ Consequence: **remote control does not work off-LAN.** You cannot pause the
desktop from outside the house. Cosine already holds a WebSocket to every
connected client, so a server-relayed fallback would be nearly free if that turns
out to matter — noted as Q78, not built.

This makes **Device** a first-class concept the model did not previously have:

- Each running client registers as a Device with a name and capabilities.
- A Device owns a **playback session** — its own queue and position.
- §4A made the queue account-level server state. That has to become
  **per-Device sessions**, held by the server, or two devices playing at once
  will fight over one queue. "Resume where I left off" then means resuming a
  chosen Device's session, not the account's.

---

## 4C. Jam

`RESOLVED` Multiple accounts listening to the same thing at the same time, with a
shared queue. This is the deliberate exception to NG4.

### 4C.1 Two things are being asked for at once

Same-room tightness and multi-account listening are different problems, and the
spec should carry them as two modes rather than one feature:

| Mode | Participants | Sync target | Difficulty |
|---|---|---|---|
| **Party** | Several accounts, anywhere, over Tailscale | Within roughly a second, drift corrected | Moderate |
| **Multi-room** | Devices on one LAN, usually yours | Sub-100ms, no audible echo | Hard — the hardest thing in this spec |

Sub-100ms alignment is meaningless between two people in different cities; a
one-second offset is invisible there and unacceptable in one room. One mechanism
serving both would mean paying multi-room's cost everywhere.

### 4C.1a Rules

`RESOLVED` **Anyone can start a Jam and invites specific people.** No open
sessions, no discovery, no join requests — you invite who you want.

`RESOLVED` **Anyone in the session can queue; only the host controls transport.**
Everyone adds tracks; skip, pause and seek belong to the host. Avoids two people
fighting over the play button while keeping the queue genuinely shared.

`RESOLVED` **A participant without the Track hears it anyway.** The Store is
shared, so playback streams from it regardless of whether they hold a Pointer.
Nothing enters their Library automatically — a one-tap add is offered while it
plays.

Two consequences worth stating:

- This is deliberately *not* the shared-playlist rule (§2.3), which auto-adds. A
  jam can move through fifty tracks in an evening; auto-adding them would flood
  a Library with music nobody chose.
- **A Jam bypasses the library-visibility setting.** Participants hear Tracks
  from accounts they may not be allowed to browse. That is the feature working
  as intended, but it means visibility governs *browsing*, not *hearing*.

`RESOLVED` **Headless players are not built now, but the protocol must not
preclude them.** Multi-room today means your phone, desktop and laptop. The sync
design should allow a UI-less player — a Pi driving speakers — to join a room
later without a redesign.

### 4C.2 What sub-100ms actually requires

Not a warning — a list of the work:

1. **A shared clock.** Peers exchange timestamps to estimate offset and
   round-trip, NTP-style. Good to a few milliseconds on a LAN.
2. **Output latency compensation.** Every device's audio path adds its own delay
   — tens of milliseconds on Android, different again on Windows WASAPI, Linux
   PipeWire, macOS CoreAudio. Each device measures its own and schedules ahead of
   it. Android exposes this via `AudioTrack.getTimestamp()`, which ExoPlayer
   surfaces.
3. **Scheduled start.** Devices agree "begin position P at wall-clock time T"
   rather than "start now".
4. **Drift correction.** Independent crystals drift 10–50 ppm — several
   milliseconds per minute, audible as echo within a single track. Correcting it
   means micro-resampling (nudging playback rate by fractions of a percent);
   re-seeking is audible.

⚠️ **Bluetooth breaks it.** Bluetooth output adds 100–300ms of variable, often
unreported latency. A device on Bluetooth cannot be tightly synced with one that
isn't. Multi-room should detect Bluetooth output and either exclude that device
or drop the session to Party tolerance, rather than sounding broken.

### 4C.3 Recommended architecture

Two ways to build it:

- **Each device decodes its own copy** and plays it at the scheduled time. No
  extra bandwidth, but decoder start-up delay varies by codec and device, and
  every participant must already have the file.
- **One device is the audio source**, streaming PCM to the others with
  presentation timestamps, everyone buffering ~500ms and playing to the shared
  clock. This is how Snapcast works, it reliably achieves single-digit
  milliseconds, and it sidesteps decoder variance entirely.

For **Multi-room**, the second approach is the one that actually works, and
Snapcast is worth reading as prior art rather than solving this from scratch. For
**Party**, the first is plenty — everyone plays their own copy, position is
broadcast periodically, and anyone more than a second out re-seeks.

## 5. Implementation shape

### 5.1 Server

`RESOLVED` **Go.** Single static binary, small idle footprint, straightforward
to run in an LXC on the P450 — the same reasoning that put Navidrome there. It
also shells out to yt-dlp comfortably, which is the one hard external dependency.

`RESOLVED` **SQLite.** One file you can copy, open and read. The change log is
just a table. No second service to run on a machine that is supposed to idle.
This is G6 in its most literal form.

`RESOLVED` **Casting is out of scope.** Sine plays audio on the phone; anything
else goes over Bluetooth. Nothing to build.

`NOTE` Cosine serves the full Subsonic API anyway (§5.4), so any Subsonic-capable
device or client on the network can already play directly from it. Casting is
covered sideways without a line of code.

`RESOLVED` **No push. Notifications are local.** Sine raises Android
notifications itself — ingest progress and completion, download progress — using
the WebSocket while it is running and a foreground service while work is in
flight. Anything that finished while the app was closed is simply waiting in the
app when you next open it.

This keeps the system entirely self-hosted: no FCM, no Google registration, no
ntfy sidecar, no external service in the path. The cost is that Cosine cannot
wake a sleeping phone — acceptable, since nothing here is time-critical.

`RESOLVED` **Per-user storage quotas exist but are off by default.** The
dashboard shows usage per account; enforcement is opt-in.

`RESOLVED` **No automatic backup.** Out of scope; whatever backs up the Proxmox
host is the backup.

⚠️ Worth being clear-eyed about what that exposes. The SQLite file is the *only*
place Pointers, playlists, play history, favourites, version pins and resolver
decisions exist. The Store is largely re-fetchable — the server knows the source
URL of most Objects — but the metadata is not. Losing that one file loses
everything the system knows and leaves you with a folder of music.

Since the whole backup is `cp` of a single file, "nothing automatic" should be a
deliberate choice rather than an oversight. A nightly copy alongside the host
backup costs nothing and is the difference between an inconvenience and starting
over.

### 5.2 Sync

`RESOLVED` **Monotonic change log with a client cursor, plus WebSocket push.**

- The server maintains an append-only change log over Tracks, Pointers,
  playlists and per-user state. Every entry has a sequence number.
- The client stores the last sequence number it has applied.
- On connect it asks for everything after its cursor and applies it in order.
- While connected, the server pushes new entries over a WebSocket so other
  devices and the dashboard reflect changes immediately.
- Losing the socket is not an error state — the client falls back to catching up
  by cursor on next connect. Push is an optimisation over a correct pull model,
  never a replacement for it.

This is what makes G2 work, and it is exactly what compat mode cannot offer.

`RESOLVED` **Offline edits merge by operation, not by result.** The client
records what you *did* — added track X, removed Y, moved Z to position 3 — and
replays those operations against the server on reconnect, rather than uploading
the resulting list. Two people editing the same playlist from different devices
both keep their changes.

`NOTE` This is pleasingly symmetric: the server keeps a change log going down,
the client keeps an operation log going up. Same idea in both directions, and
neither side ever has to guess what the other meant.

### 5.2a Client

`RESOLVED` **Kotlin + Jetpack Compose, Android-first.** Sine is built as a
native Android app, with a deliberate module boundary between the model/sync
layer and the UI so those modules can be lifted into Kotlin Multiplatform when
iOS becomes a priority. No cross-platform tax is paid before it is earned.

This follows from §5.4: because Cosine serves the full Subsonic API, iOS already
has usable clients on day one. iOS is a want, not a gap, and the main argument
for a cross-platform toolkit evaporates with it.

`RESOLVED` **Local search, against the client's mirror.** Sine already holds the
full library metadata, so search is instant and works offline. Cosine only
searches on behalf of non-native clients.

`NOTE` Local storage is Room over SQLite, mirroring Cosine's own choice.

#### Why native, and what it unlocks

| Option | Media integration | Design control | iOS | Verdict |
|---|---|---|---|---|
| **Kotlin + Compose** | Media3 directly; `MediaLibraryService` gives real Android Auto. SAF and WorkManager are first-party. | Full — Compose is a custom rendering toolkit | Separate project later | **Chosen** |
| **KMP + Compose Multiplatform** | Media3 on Android, AVFoundation on iOS behind `expect`/`actual` | Full, shared | Shared UI and logic | The upgrade path, not the starting point |
| **Flutter** | Plugins over ExoPlayer/AVPlayer; Android Auto support is thin | Full — draws every pixel | Nearly free | Rejected: weakest exactly where this spec is demanding |
| **React Native** | Worse audio story than Flutter | Fights the platform | Free | No |

Three commitments elsewhere in this spec made the decision:

1. **Android Auto is required** (see below). Native gives a real
   `MediaLibraryService` with a proper browsable hierarchy; every cross-platform
   route reaches Auto through a plugin with a thinner abstraction.
2. **Downloads live in a user-visible folder.** That means the Storage Access
   Framework, which is first-party in Kotlin and a plugin dependency everywhere
   else. The same applies to `.nomedia` handling and to WiFi-and-charging
   background downloads via WorkManager.
3. **Mixed formats and gapless.** Media3/ExoPlayer handles the Opus/M4A/MP3 mix
   yt-dlp produces, and driving it directly means full control over gapless
   behaviour and over applying the ReplayGain values Cosine computed at ingest.

Compose gives the same draw-every-pixel freedom a cross-platform toolkit would —
it is a custom rendering toolkit, not a wrapper over platform widgets — so
Graphit loses nothing. Backdrop blur for the glass chrome is supported and
performs acceptably; keep it on chrome, not on list items.

Language count for the whole system: Kotlin and Go.

#### Playback and controls

`RESOLVED` All three are required:

- **Media notification and lockscreen** — artwork, play/pause/skip, seek.
- **Bluetooth and headset buttons** — track metadata to the car display,
  media-button handling.
- **Android Auto** — a full browsable library on the car's screen.

Android Auto browsing has a consequence worth designing for rather than
discovering: the browse tree is served through `MediaLibraryService` and must
work from local state, since a car is exactly where the network is worst. The
local mirror (G2) and pinned downloads (G1) are what make this possible, so Auto
should browse the offline-available subset by default rather than the full
library.

### 5.3 Server dashboard

`RESOLVED` **Admin plus library correction.** Store path, accounts, ingest queue,
server settings — and a library view whose purpose is batch correction of bad
resolver matches. No playback.

`RESOLVED` **Corrections happen in both places.** The phone carries a **review
queue**: tracks the resolver was unsure about, surfaced as a list you can work
through — search, pick the right identity, done. The dashboard handles the same
job in bulk, where a keyboard and a large screen make it bearable.

This is a real feature, not a settings screen. Given the genre, the review queue
is something you will use regularly, and it should be designed as a first-class
part of the app rather than an admin afterthought.

`RESOLVED` **What enters the queue:** a confidence score below a configured
threshold, *plus* everything resolved at tier 4 (source metadata) regardless of
score — because a confident guess from a SoundCloud title is still a guess.

### 5.4 Subsonic surface

`RESOLVED` The native server **also serves the Subsonic API, full-featured** —
browsing, playback, playlists, stars, scrobbles — so Symfonium, DSub, car head
units and speakers can play from it. Third-party clients see one account's
Library as an ordinary Subsonic library.

Native-only concepts (ingest, cross-user adds, version pins, the change log)
have no Subsonic representation and are simply absent there.

`NOTE` This makes G8 stronger than expected: an iOS client is no longer strictly
necessary to have music on another platform, since any Subsonic client works.
The iOS app becomes a want, not a gap.

---

## 6. Interface

Sine is Graphit's flagship application. The design language is settled (v0.7);
this section is about what it forces, and what Sine must decide on top of it.

### 6.1 What Graphit already decides

These are not open. They arrive with the system and shape every screen below.

| Rule | Consequence for Sine |
|---|---|
| **Glass means detached** | Navigation, the now-playing bar, sheets and dialogs are glass. Lists, cards, the library, artwork panels are solid. There is no third option and no aesthetic exception. |
| **Colour is a plane** | The four primaries only ever appear as 100%-opaque filled blocks with black ink. No blue text, no coloured icons, no coloured waveform, no tinted progress line. Anything that needs to read as coloured must become a block. |
| **Navigation always floats** | A floating bar costs the scrolling body 84px of reserved padding (16 + 52 + 16). Every additional floating element costs another footprint. |
| **One filter per surface** | Never nested, never on a scrolling child. A backdrop blur per list row is out of the question — which rules out glassy track rows entirely. |
| **An overlay never opens another overlay** | See §6.2. This is the single hardest constraint for a music app. |
| **Lists separate with hairlines, not gaps** | No card-per-track. Three densities only: 32px (scanning), 40px (default, carries a state plane), 56px (only when a second line changes a decision). |
| **Selection is a blue plane** | The whole row fills blue and its text flips to black. That is what "selected" looks like — nothing else. |
| **Long-form prose uses `--txt-2`** | Pure white blooms at paragraph length on OLED. Track titles and labels are `--txt-1`; descriptions and notes are not. |
| **Four-pixel grid** | Every dimension is a multiple of 4. |
| **Android below API 31** | Floating chrome goes opaque, everything else is identical. Sine must look correct with no blur at all. |

Graphit's own open questions land directly on this app: **how far "floating"
goes** — a search field, a now-playing bar — because each one costs the body more
reserved padding. Sine is where that gets answered.

### 6.2 Navigation

`RESOLVED` **Three top-level destinations** in the floating bar: **Home**,
**Search**, **Library**.

`RESOLVED` **The Library root is a segmented control** — Artists / Albums /
Playlists — so playlists sit as an equal to the canonical tree rather than
bolted onto it. Downloaded and Recently added are filters within a segment, not
segments of their own.

`RESOLVED` **Add is not a tab.** Ingest lives as an action in the top right of
the Library screen only. It is something you do while looking at your library,
not a permanent destination competing with it.

`RESOLVED` **Jam lives inside the now-playing screen.** It is a property of what
is currently playing, not a place you navigate to.

Both of those keep the bar at three items, which matters: a 52px bar with 16px
padding does not hold five things without becoming a row of unlabelled icons.

### 6.3 The now-playing bar

`RESOLVED` **A separate bar above the navigation.** Sine carries two floating
elements at the bottom of the screen.

The cost is real and has to be reserved:

```
16  inset from the safe area
56  now-playing bar
 8  gap
52  navigation bar
16  inset
———
148px of reserved body padding
```

Every scrolling surface in the app reserves 148px at the bottom, not the 84px
Graphit's `FLOATING_BAR_FOOTPRINT_DP` assumes. That constant needs a Sine
override, and the last row of every list is unreachable if it is missed.

`RESOLVED` **One shelf that splits when playing.** Idle, it is a single 52px
navigation bar. When something starts playing, a player row grows out of it and
the shelf becomes two rows in one glass surface — **one backdrop filter, not
two**, which is the cost Graphit warns about avoided entirely.

⚠️ The reserved padding therefore changes at runtime: **84px idle, 148px
playing.** Two implementation requirements follow, and both are the kind of thing
that ships broken:

- The change must animate on `--t-move` (160ms) together with the shelf, not
  snap a frame later.
- A list scrolled to the bottom must not jump when padding grows. Anchor the
  scroll position to content, not to offset, or starting playback will visibly
  shove the list.

### 6.4 Home

`RESOLVED` **Home is a tile grid, in the Windows Phone / Metro sense** — live
tiles, flat blocks, no chrome, typography doing the work.

This is a much better fit with Graphit than it sounds. Metro was a black ground
covered in flat, fully-saturated colour blocks with no gradients, no bevels and
no shadows, where colour carried meaning and type carried everything else.
Graphit's second rule — colour appears only as a 100%-opaque filled plane —
describes a live tile almost exactly. Tiles are solid surfaces that scroll with
the page, so rule 1 puts them firmly on the solid side: **no glass on Home**.

Three places the two systems disagree, where Graphit wins:

| Metro did | Graphit requires |
|---|---|
| White text on the accent colour | **Black ink.** All four primaries take black ink, no exceptions. A tile is black-on-blue, not white-on-blue. |
| An accent colour per user, any hue | **Four primaries.** Tiles are blue, red, green, yellow, or a solid neutral surface. Nothing else. |
| Tiles bleed to the screen edge, gap-separated | **4px grid, hairlines elsewhere.** Tiles keep their gaps — a grid is not a list — but every dimension is a multiple of 4, and each coloured tile carries the 1px edge in its own ink that every plane carries. |

`RESOLVED` **Home is a canvas, not a designed screen.** The user pins, resizes
and reorders tiles freely. There is no fixed layout — a default arrangement on
first run, and everything after that is theirs.

`RESOLVED` **Tiles are live in content, not in motion.** Artwork cycles, counts
update, the current track changes. Tiles do not flip, slide or animate between
faces. A grid of animating blocks fights everything else in Graphit, and the
liveness people actually value is the content being current.

#### 6.4.1 Tile catalogue

Two families.

**Pinned items** — anything in the library, pinned from its own screen via the
same long-press selection used everywhere else:

- An artist, album or playlist. Artwork-filled; tapping opens it.
- A **shuffle** variant of any of the above — the tile plays rather than navigates.

**Generated tiles** — the system builds the content:

| Tile | What it shows |
|---|---|
| **Now playing** | Current track and artwork; absent when nothing is playing |
| **Resume** | The most recent session, and which Device it belongs to |
| **Shuffle all** | Action tile — plays the whole Library shuffled |
| **Recently added** | Cycles artwork of new Pointers, using the per-Pointer date |
| **Most played** | Windowed: 30 days, this year, all time |
| **This time last year** | What you were listening to a year ago |
| **Forgotten** | In your Library, not played in N months |
| **Never played** | Ingested and never once opened |
| **Downloaded** | What is available offline right now |
| **Favourites** | Starred Tracks |
| **Review queue** | Live count of Tracks awaiting a resolver correction; tapping opens it |
| **Ingest activity** | What Cosine is fetching, with progress and failures |
| **Jam** | Active session, or an invite to start one |

`NOTE` The retrospective tiles — *this time last year*, *forgotten*, *most played
this year* — are only possible because plays are stored as append-only
timestamped events rather than a counter (§8, Q37). That decision was made for
offline correctness; this is the second thing it bought. It does mean **play
history is never pruned**, which is fine: a decade of listening is a few megabytes
of rows.

`NOTE` None of these are recommendations. Every tile is a view of the user's own
history or library, so NG3 stands — no taste modelling, no algorithmic
discovery, nothing that suggests music from outside what they already have.

#### 6.4.2 Grid

`RESOLVED` **Four columns**, with three tile sizes: **1×1**, **2×2**, **4×2**.
Metro's actual proportions — small square, medium square, wide — which on a
phone puts the unit at roughly 80px, comfortably on the 4px grid. No 4×4.

#### 6.4.3 Tile appearance — artwork as ambience

`RESOLVED` **A tile's ground comes from its artwork.** Home is ambient: a tile
for an album, artist or playlist takes its character from the cover it
represents, blurred and pushed toward the ground behind the tile's own ink.

This needs one rule to stay inside Graphit, because as stated it appears to
break the second rule outright:

> **Artwork may be a ground. It may never become a palette.**

The distinction is exact and worth holding onto:

- **Allowed** — the cover itself, blurred and scrimmed, as the tile's backdrop.
  Artwork is *content*, in the same way a photograph in an article is content.
  Rule 2 governs the four primaries, not photography.
- **Not allowed** — sampling a dominant colour from the cover and painting a
  flat block in it. That introduces a fifth, sixth and hundredth colour as a
  plane, which is precisely what the rule exists to prevent, and the muted
  four-primary palette stops meaning anything the moment arbitrary hues sit
  beside it.

This also matches Graphit's own conclusion about its ambient field: the spec
page still paints blurred primaries behind everything and says an app *should
drop it entirely and let real content be the backdrop*. On Home, the artwork
is that real content.

⚠️ **Contrast has to be guaranteed, not hoped for.** Album art ranges from black
to blown-out white, so ink over it needs a fixed floor — a scrim
(`--scrim`, or a bottom-weighted gradient under the label) applied at constant
opacity regardless of the image. This is the same discipline Graphit applies to
glass alphas: the value is a contrast floor, not a style choice. Never sample the
image to decide whether to darken it; darken it always.

**Tiles with no artwork** — Shuffle all, Review queue, Ingest, Jam — sit on
neutral solid surfaces (`--bg-2`), and use the four primaries only where they
carry meaning: yellow for the review queue's attention, green for active ingest,
red for failures only. Red stays destructive-or-broken everywhere in Sine; it is
never decorative.

#### 6.4.4 Editing

`RESOLVED` **Long-press selects a tile and the shelf swaps to tile actions** —
Resize, Unpin, Move — with drag available while selected. Identical to the
gesture used everywhere else in the app; nothing new to learn, and no separate
"jiggle mode" with its own rules.

#### 6.4.5 Empty tiles

`RESOLVED` **A tile with nothing to show keeps its place and shows a resting
state.** The Jam tile offers to start one; Now Playing shows what you played
last; Review queue shows zero. The layout is the user's, and it must never
reflow underneath them because a background process finished.

### 6.5 The stacked-overlay problem

Graphit forbids an overlay opening another overlay. A conventional music app
violates this immediately:

> mini player → full now-playing (overlay) → queue (overlay) → track options
> (overlay) → add to playlist (overlay)

Four levels deep, all of them modal, with no working back gesture. Sine cannot
be built that way.

`RESOLVED` **Now playing is an overlay sheet,** dragged up from the bar.

`RESOLVED` **The queue is a pane inside it,** not a sheet on top of it.

Which settles the structure but sharpens the problem rather than removing it.
Because now-playing is itself an overlay, *nothing* can open on top of it — and a
music player needs somewhere for track options (go to artist, add to playlist,
pin a version, remove), and now also for Jam.

So the now-playing sheet is not one screen. It is a **multi-pane surface**:

| Pane | Contents |
|---|---|
| **Now playing** | Artwork, transport, position, the current track |
| **Queue** | What's next, reorderable, source of the queue |
| **Jam** | Session state, participants, invite — only when relevant |

Three panes, and the questionable fourth is gone.
`RESOLVED` **Context actions come from a long-press action bar.** Long-pressing a
row selects it — the whole row fills blue with black ink, exactly as Graphit
specifies — and the shelf's contents swap to the available actions for that
selection. No overlay is opened, so nothing conflicts, and multi-select comes
free: long-press one row, tap others, act on all of them.

This also means the shelf has three states, not two:

| State | Contents |
|---|---|
| **Idle** | Navigation only, 52px, 84px reserved |
| **Playing** | Player row + navigation, 108px, 148px reserved |
| **Selection** | Action row + count, replacing navigation for as long as a selection exists |

So the fourth "Options" pane in the now-playing sheet is not needed.

### 6.6 The now-playing sheet

`RESOLVED` **The sheet has two heights, not three panes.** Dragged up from the
shelf it shows now playing; dragged further, the queue rises from its bottom
edge. This is one surface at two heights, not an overlay opening an overlay, so
the rule holds.

`RESOLVED` **Jam is a strip inside the sheet.** When a session is active, a
participant strip sits above the transport — who is listening, who queued the
current track. Starting a jam is a long-press action on the shelf, like every
other action in the app. Jam stays attached to what is playing and costs no new
surface.

`RESOLVED` **Artwork is full-bleed behind a scrim.**

⚠️ This collides with rule 1 and needs a resolution written down. The sheet is an
overlay, so it is glass — `--glass-3` at .90 with `--blur-2`. A cover filling its
entire area makes the glass invisible: a blurred backdrop nobody can see is
wasted GPU and a broken promise about what the surface *is*.

The resolution is a gradient, not a compromise:

- Artwork fills the sheet's width and upper region, scrimmed at constant opacity.
- The scrim deepens downward and hands off to the sheet's own glass beneath the
  controls, so the transport, title and progress sit on real glass with the
  library visibly blurred behind them.
- The sheet keeps its 22px radius, its `--line-2` edge and its specular top line
  throughout, which is what identifies it as a floating surface regardless of
  what fills the middle.

`RESOLVED` **Progress is a growing plane.** A blue plane whose width grows across
the track — the second rule applied literally rather than worked around.
Scrubbing drags its leading edge.

`RESOLVED` **The timecode sits off the plane** — elapsed and remaining below it,
always `--txt-2`, never overlapping. No ink flip at the boundary, no legibility
edge cases, and the plane stays a pure geometric object rather than a container
for text.

One detail this still forces:

- **The plane carries its 1px edge in its own ink**, like every plane. At two
  seconds into a track that edge is most of the plane, so the plane needs a
  minimum rendered width — 6px, the plane radius — below which it is drawn
  without its edge.
- Growth is continuous, which Graphit permits: motion here is feedback about a
  real process, not atmosphere.

`RESOLVED` **The shelf's player row carries artwork, title, play/pause, skip, a
progress hairline along the shelf's bottom edge, and a Device indicator.** The
Device marker is not decoration — once remote control exists, playback may be on
the desktop, and without it you press play on the wrong machine.

`NOTE` There is one honest escape hatch worth remembering: Graphit forbids an
overlay opening *another overlay*, but inline expansion inside an existing
surface is not an overlay. A row that expands in place, or a pane that slides
sideways within the same sheet, breaks no rule.

---

### 6.7 Ambience beyond Home

`RESOLVED` **Any screen about one subject carries that subject's ambience** —
artist, album, playlist, now playing, Home tiles. Screens about many things —
search results, the review queue, settings — stay on flat ground.

Three rules keep this from going wrong, and all three are non-negotiable:

1. **Ambience is a header treatment, never a page background.** It occupies the
   top of the screen and fades out; content below sits on `--bg-0`. Long lists
   over a photograph are unreadable and Graphit's `--txt-2` prose rule assumes a
   known ground.
2. **The scrim is constant, never adaptive.** Same discipline as Home (§6.4.3):
   darken always, never sample the image to decide.
3. **Blur once, at load, not per frame.** The ambience is a pre-blurred,
   downscaled copy of the artwork drawn as a static image — not a runtime blur
   and emphatically not a backdrop filter, which Graphit forbids on scrolling
   children. Cache it beside the cover.

### 6.8 Library and search

`RESOLVED` **Track rows are quiet by default.** Title, artist, duration at the
40px density. A plane appears only when something is true of that row:

| State | Treatment |
|---|---|
| Unreviewed (low-confidence resolve) | Yellow plane |
| Unavailable (missing Object) | Red plane, row ink drops to `--txt-3` |
| Downloaded / pinned | A small mark, not a plane — it is the common case, and a column of planes down every list destroys the quiet |
| Selected | The whole row fills blue with black ink, per Graphit |

The principle: a plane means *pay attention to this row*. If most rows have one,
none of them do.

`RESOLVED` **Search is instant, local and returns one ranked list.** No network,
works offline, results as you type against the client mirror.

Mixing Artists, Albums, Tracks and Playlists in one list needs two things:

- **Type is a `t-micro` mono label**, not a plane — see the rule above.
- **A stated ranking order**, or the list will feel arbitrary. Proposed: exact
  title match, then prefix match, then contains; within each, Artists before
  Albums before Playlists before Tracks; ties broken by the user's own play
  count. Named here so it can be argued with rather than emerging from whatever
  the query planner does.

`RESOLVED` **Search is always global**, unaffected by which Library segment was
last open.

`RESOLVED` **Sort and filter options per screen, not remembered.** Artists by
name or by recently added; a release's tracks always in track order, because
that order is the release. Filters for downloaded and unreviewed. Choices reset
between visits — no preference state to sync, and no screen that quietly behaves
differently from the last time you looked at it.

### 6.9 Artist and release pages

`RESOLVED` **Artist page: ambient header, then releases.** The artist's name over
their ambience, then the list of releases — the canonical tree, one level down.
Tracks live inside releases, matching the Store's structure exactly.

⚠️ **This collides with the Store layout decision and needs a rule.** §2.2 files
a track with no album into an album named after the track itself. For a library
built largely from SoundCloud singles, an artist page will therefore be thirty
release rows, each containing exactly one track with the same name as its
folder. Structurally uniform; unusable.

Proposed rule: **a release containing exactly one track whose title matches the
release name renders as a track row, not a release row.** The Store keeps its
uniform structure — nothing changes on disk — and the artist page shows singles
as singles and albums as albums. The collapse is presentational only.

`RESOLVED` **Interleaved by date.** One chronological list of everything the
artist released — albums as release rows, singles as track rows, together. It
reads as a body of work, which is how this music is actually made and released;
sectioning albums above singles would impose a record-industry hierarchy the
library does not have.

`RESOLVED` **A Track with no artwork gets no ambience** — flat `--bg-0`, type
doing the work. No generated colour: §6.4.3 rules out decorative use of the
primaries, and a deterministic hue per artist would make red decorative
immediately.

`NOTE` A side effect worth having: artwork becomes visibly valuable. A release
with a cover looks richer than one without, which is quiet pressure to use the
user-set artwork override (§3.2a) on the unreleased material — the exact
material that needs it.

### 6.10 Adding music

`RESOLVED` **Add opens a sheet with a single field.** Paste a URL or type a
query into the same input — Cosine works out which it is. Results appear
beneath; picking one queues the ingest. One surface, no modes, no tabs.

`RESOLVED` **Sine registers as a share target.** Sharing a track from
SoundCloud, YouTube or anywhere else opens this same sheet with the URL already
in the field. This is the fastest path from hearing something to owning it, and
it is probably how most music will actually enter the library.

Consequences worth building for rather than discovering:

- **Short and share links must resolve server-side.** A shared SoundCloud link
  is often a redirect, and Sine should not be following redirects on a phone —
  it hands Cosine the string it was given.
- **Sharing while offline queues locally.** The URL is stored and submitted on
  reconnect, with the sheet saying so. Failing a share because the phone is on
  the underground is the exact moment this feature matters.
- **The share target is chosen by construction, not configured.** Compat
  accounts cannot ingest at all, so they are never candidates; among Cosine
  accounts the most recently added wins. No setting, and no picker in the way of
  a two-second action.
- Long-pressing Add offers uploading a local file — the rare path, kept out of
  the common one.

### 6.11 The account control

`RESOLVED` **A persistent control in the top corner, on every screen except
now-playing.** Tapping it opens a choice of **Account** or **General** settings.

Three things follow that are worth writing down:

- **It floats, so it is glass.** Rule 1 admits no exception: it stays put while
  content moves underneath, therefore `--glass-1`, a `--line-2` edge, a specular
  top line, 22px radius, inset 16px on top of the safe area. It is Sine's second
  floating surface, and the second backdrop filter on screen.
- **The menu it opens is `--glass-3`**, like every menu. Choosing Account or
  General then navigates to a **page**, not a dialog — a menu opening a dialog
  would be an overlay opening an overlay, which is exactly the rule that shaped
  the rest of this section.
- **Now-playing is the exception** because the sheet covers the screen and the
  control would sit on top of an overlay it cannot belong to.

Add and the review queue stay in the Library header. The account control is
global; those two are about the library specifically.

### 6.12 The review queue

`RESOLVED` **A list, tapped to correct.** Unreviewed Tracks with the resolver's
guess and where it came from; tapping opens a correction screen carrying
candidate matches and a search field. It scales to a backlog, which a
one-at-a-time flow does not — and after a bulk ingest there will be a backlog.

The queue is reachable from the Library header beside Add, and from its Home
tile. Its count is the yellow plane; zero is a resting state, not an empty
screen.

`RESOLVED` **The correction screen shows all sources at once, ranked, with each
candidate labelled by origin** — MusicBrainz, Discogs, Bandcamp — so you can see
when two sources describe the same release, and **a fall-through to manual
entry**. For unreleased material, typing the artist and release yourself is not
a fallback; it is the only honest answer, and it needs to be one tap away rather
than buried.

### 6.13 Statistics

`RESOLVED` **A proper statistics screen.** Top artists and tracks by window,
listening over time, first-played dates, most-played by year. Every play is
already a timestamped event (§8, Q37), so this reads data that exists rather
than requiring new collection.

This is not a recommendation engine and NG3 stands: it describes what you did,
it never suggests what to do next.

Reachable from the account control, and pinnable as a Home tile.

⚠️ **Graphit rules out most chart types, and the rule is unusually clarifying.**
Colour appears only as a filled, opaque plane — never as a line or a hairline.
So:

- **Bar charts and filled area charts are planes.** They are exactly what the
  system describes, and they work with no special pleading.
- **Line charts are impossible in colour.** A line is a stroke, and a coloured
  stroke is forbidden. A trend line can only be drawn in `--txt-2` as a
  neutral, or redrawn as a filled area.
- Axes and gridlines are `--line-1` hairlines; numbers are `.t-data` mono with
  tabular figures.

The result is a stats screen made of blocks, which suits both the design
language and the data.

### 6.14 Offline

`RESOLVED` **Everything stays visible; what cannot play is dimmed.** The whole
library remains browsable with no network. Tracks without a local copy drop to
`--txt-3` and carry the same red plane as a missing Object.

This is the honest option: the library is what you own, not what happens to be
on the phone this minute. It does mean seeing things you cannot hear — which is
correct, and a prompt to download them.

`NOTE` This makes the pinned/cache distinction visible in a way worth designing:
a cached Track can be evicted, so something playable this morning may be dimmed
this evening. Anything the user pinned must never do that — which is exactly why
the tiers exist.

### 6.15 Desktop

`RESOLVED` **Desktop Sine is a desktop app, not a scaled phone.** Destinations
in a left sidebar, a full-width player docked along the bottom, the queue as a
right-hand panel.

This is worth doing rather than reusing the phone layout, and it costs less than
it sounds:

- The **no-stacking rule stops biting**. Panels are not overlays, so the queue,
  now-playing and Jam can all be on screen at once — the constraint that shaped
  the entire phone design is a phone constraint.
- Both the sidebar and the docked player stay put while content scrolls, so by
  rule 1 **both are glass**. Two filters, as on the phone.
- The model, sync and library code are shared with Android unchanged; what
  differs is layout and the audio backend (§5.2a).

`RESOLVED` **Tiles keep their unit size and the grid gains columns** on a wide
window. Four columns stretched across 1600px would be absurd.

The consequence is that **a phone arrangement does not transfer**: desktop keeps
its own Home layout. That is two Home screens to maintain per account, which is
the honest cost of a canvas rather than a designed screen — and probably correct,
since what you want at a desk is not what you want on a phone.

### 6.16 Motion

`RESOLVED` **Strictly by Graphit's tokens.** No custom curves, no signature
transition.

| Change | Token |
|---|---|
| Selection, hover, press, plane fills | `--t-state` 100ms |
| Shelf splitting and collapsing, padding reflow, tile resize | `--t-move` 160ms |
| Sheet and menu entry | `--t-overlay` 220ms; leaving in 160ms, opacity only |

Two consequences accepted deliberately:

- **No shared-element transition.** The shelf's artwork does not travel into the
  sheet — it cross-fades. Less of a moment, one fewer bespoke animation, and
  consistent with Graphit's line that motion is feedback rather than atmosphere.
- **The sheet is timed, not finger-tracked.** It opens on a drag but plays its
  own 220ms rather than following the gesture. Cheaper, less tactile; revisit
  only if it feels wrong in the hand.

### 6.17 Voice

`RESOLVED` **Plain and factual.** State what is true and what happens next. No
apologies, no personality, no exclamation marks.

| Situation | Text |
|---|---|
| Server unreachable | "Cosine unreachable. Showing downloaded music." |
| Empty playlist | "No tracks in this playlist." |
| Track unavailable | "File missing from the Store." |
| Ingest failed | "Couldn't fetch — <reason from yt-dlp>." |
| Review queue empty | "Nothing to review." |
| No search results | "No matches in your library." |

The last one matters: it says *in your library*, because Search is local by
design (§6.8) and the honest message is that the thing isn't here — not that it
doesn't exist.

### 6.18 The dashboard

`RESOLVED` **Cosine's dashboard uses `graphit.css` directly.** Same tokens, same
palette, same rules, in the browser. This is the point of Graphit spanning
Android, web and terminal, and the dashboard is where that gets proven.

Practical notes:

- Admin work is tabular, so the dashboard leans on Graphit's `table`, `.list-compact`
  (32px rows) and `.t-data` mono numerals rather than the ambient surfaces Sine uses.
- **No ambience.** The dashboard is about many things at once, and §6.7 restricts
  ambience to single-subject screens.
- Destructive actions follow Graphit's rule exactly: a red plane with an explicit
  verb and count — "Delete 4 orphaned objects", never "Confirm" — and never
  adjacent to the primary action. Orphan GC (§2.3) is precisely where this
  matters.

### 6.19 The Android widget

`RESOLVED` **A now-playing widget** — artwork, title, transport. Nothing else;
notification and lockscreen controls already cover the rest.

Two practical notes:

- Widgets are a separate rendering surface. **Jetpack Glance** gives a
  Compose-flavoured API, but it is not Compose — layouts are rebuilt, not
  shared. Budget it as its own small screen.
- **No glass.** Widgets cannot blur what is behind them, so the widget uses
  Graphit's opaque fallback — the same one Android below API 31 already gets.
  That path has to exist anyway, which is the second time restricting glass to
  floating chrome has paid off.

### 6.20 First run

`RESOLVED` **Typing the address is the primary path.** It works identically on
the LAN and over Tailscale, and it is a URL entered once.

`RESOLVED` **mDNS discovery suggests, never decides.** If Cosine is found on the
network it is offered as a suggestion above the field. The same discovery
remote control already needs (§4B), reused.

`RESOLVED` **The mode is explicit, never silent.** Sine probes the address and
tells you what it found — "Cosine · full features" or "Subsonic server ·
compatibility mode" — before you log in. §2.1 hides native-only features in
compat mode, and a user should know why they are missing rather than concluding
the app is broken.

Each account belongs to one server, so choosing a mode is choosing an account.
Switching between the live Navidrome and a half-built Cosine is switching
accounts, not changing a setting.

`RESOLVED` **Logging out asks what to do with downloads, defaulting to keep.**
Files stay in the visible folder and are re-associated by hash on the next
login, with nothing re-downloaded — the same property that makes a resync safe
(§9.2). Deleting is offered because an account you are leaving for good should
not silently keep gigabytes.

---

## 7. Decision log

| # | Decision | Outcome |
|---|---|---|
| D1 | Why own server, not Navidrome | Implicit: per-user Libraries, integrated ingest, upload, delta sync — none possible on stock Navidrome |
| D2 | Subsonic strictness | Two modes: compat (stock Subsonic) + native (own protocol) |
| D3 | Web UI | A **server dashboard** exists (Store path is set there). Scope beyond admin settings still `OPEN` |
| D4 | beets | Superseded — the server's MusicBrainz step takes the organising job |
| D5 | Name | `OPEN` |
| X1 | Gapless vs. conservative data | Not a conflict; conservative = shallower prefetch, floor is gapless |
| X3 | Folder vs. tags | Folder structure wins; tags disregarded |
| X5 | Compat mode delta sync | Accepted degradation |
| X6 | One fixed Store | Single movable location, configured in dashboard |
| — | Pointer storage | DB rows; symlink trees materialised on demand |
| — | Canonical structure | Lives in the Store, shared; Libraries are filtered views |
| — | Cross-user adds | Yes — adding from another Library creates a Pointer |
| — | Metadata authority | Multi-source resolver, MusicBrainz first of several |
| — | Personal organisation | Canonical tree + shareable per-library playlists; nothing else |
| — | Object identity | Content hash — implies files are never rewritten in place |
| — | Bad match corrections | Global; refiles for everyone |
| — | Local uploads | Full resolver, same path as ingest |
| — | Shared playlists | Auto-add Pointers for missing Tracks on accept |
| — | Multiple qualities | Linked as versions of one Track |
| — | Device storage | Two tiers: pinned (never evicted) + cache (evictable) |
| — | Transcoding | None. Originals only |
| — | Pointer target | Track, with optional version pin |
| — | Version matching | Resolved identity only; no fingerprinting |
| — | Auth | Session tokens natively; legacy token+salt on the Subsonic layer |
| — | Baseline | Greenfield — everything is PLANNED |
| — | Server language | Go |
| — | Sync | Change log + cursor, WebSocket push over a correct pull model |
| — | Dashboard | Admin only |
| — | Subsonic out | Full-featured, served by the native server |
| — | Migration | None — start clean, re-ingest deliberately |
| — | Corrections | Review queue on phone + bulk view in dashboard |
| — | Offline | Rule-driven pinning + learning cache |
| — | Store writers | Server only; outside files arrive via a watched Inbox |
| — | Queue | Server-side, resumes across devices |
| — | Scrobbling | Server-side |
| — | Normalisation | Analysed at ingest, stored in DB, applied at playback |
| — | Long mixes | No special handling |
| — | Inbox drops | Pointer for every account; removal is opt-out and remembered |
| — | Review queue | Confidence threshold + always-review tier 4 |
| — | Version pick | Best on WiFi, smallest on data |
| — | Artwork | Resolver → embedded → user-set |
| — | Ingest sources | Anything yt-dlp supports |
| — | Search backend | Source APIs or yt-dlp search, switchable in dashboard |
| — | Ingest format | Best available, stored untouched |
| — | Bulk ingest | Both modes — queue everything, or review first |
| — | Visibility | One global server toggle |
| — | Account creation | Admin-created, or invite code |
| — | Account deletion | Pointers removed, Objects kept |
| — | Orphan GC | Manual by default, optional automatic with grace period |
| — | Store layout | `Artist / Album / Track`; singles get an album named after the song |
| — | Missing files | Greyed out, still listed |
| — | Inbox watching | Filesystem events, with a settle check before ingest |
| — | Added-at | Per-Pointer — your recents are yours |
| — | Device storage | Visible folder for pinned downloads; cache stays internal |
| — | Download unit | Track, album, playlist or artist |
| — | Offline conflicts | Merge by operation, replayed on reconnect |
| — | Metered detection | Android's metered flag |
| — | Client stack | Kotlin + Compose, Android-first; KMP later. Room locally |
| — | Server database | SQLite |
| — | Search | Local, against the client mirror |
| — | Backup | Nothing automatic (see the caveat in §5.1) |
| — | Casting | Out of scope; covered sideways by the Subsonic layer |
| — | Notifications | Local only, no push service |
| — | Name | **Sine** (client) and **Cosine** (server) |
| — | Car and controls | Notification, lockscreen, Bluetooth, headset buttons, Android Auto — all required |
| — | Quotas | Optional, off by default |
| — | Compat mode UI | Native-only features hidden entirely |
| — | Multiple servers | Supported, switched explicitly; no merged view |
| — | Gapless | True gapless where the format allows; not an absolute |
| — | Removals | Dismissed marker on every removal |
| — | Crossfade | Offered, off by default; alternative to gapless, not layered |
| — | Desktop client | Real desktop Sine via Compose Multiplatform; separate audio backend |
| — | Remote control | Direct peer-to-peer over LAN, mDNS discovery, no server in path |
| — | Jam | Multiple accounts, synced playback — two modes: Party and Multi-room |
| — | Jam access | Invite-only; anyone can queue, host controls transport |
| — | Jam and missing tracks | Streamed from the Store; nothing auto-added |
| — | Headless players | Not built; protocol must not preclude them |
| — | Navigation | Home · Search · Library; Add is a Library action, Jam lives in now-playing |
| — | Now-playing bar | Separate floating bar above the nav; 148px reserved |
| — | Now-playing screen | Overlay sheet with the queue as an internal pane |
| — | Shelf | One glass surface; splits into player + nav when playing; three states |
| — | Context actions | Long-press selection + action row in the shelf; multi-select free |
| — | Home | Metro-style tile grid, solid surfaces, black ink on the four primaries |
| — | Home layout | Fully user-arranged: pin, resize, reorder. No fixed design |
| — | Tile liveness | Content cycles; no flip or slide animations |
| — | Play history | Never pruned — retrospective tiles depend on it |
| — | Segments | Remembered per visit; Search is always global |
| — | Tile grid | 4 columns; 1×1, 2×2, 4×2 |
| — | Tile ground | Artwork as ambience, always scrimmed; never sampled into a palette |
| — | Tile editing | Long-press selection + shelf actions, same as everywhere |
| — | Empty tiles | Keep their place, show a resting state |
| — | Now-playing sheet | One surface, two heights; queue rises from the bottom edge |
| — | NP artwork | Full-bleed, scrimmed, handing off to real glass under the controls |
| — | Progress | A growing blue plane; black ink over the filled region |
| — | Player row | Artwork, title, play/pause, skip, progress hairline, Device indicator |
| — | Jam | A participant strip inside the now-playing sheet |
| — | Ambience | Any single-subject screen; header only, constant scrim, pre-blurred |
| — | Track rows | Quiet by default; a plane means exception, not status |
| — | Search | Instant, local, one ranked list |
| — | Add | Single-field sheet; Sine is a share target for URLs |
| — | Review queue | List, tap to correct; count as a yellow plane |
| — | Offline | Everything visible, unplayable dimmed |
| — | First run | Type the address; mDNS suggests; mode shown explicitly |
| — | Account control | Persistent glass control, top corner, all screens but now-playing |
| — | Correction screen | All sources ranked and labelled, plus manual entry |
| — | Share target | Native accounts only, most recently added wins |
| — | Desktop | Sidebar, docked player, queue panel; panels not overlays |
| — | Artist page | Ambient header, then releases; single-track releases collapse to track rows |
| — | Timecode | Sits off the progress plane, always `--txt-2` |
| — | No artwork | Flat ground, no ambience, no generated colour |
| — | Desktop Home | Same unit size, more columns; separate layout from the phone |
| — | Artist ordering | Interleaved chronologically, albums and singles together |
| — | Motion | Graphit tokens only; no shared-element transition, sheet is timed |
| — | Voice | Plain and factual; state what is true and what happens next |
| — | Dashboard | `graphit.css` directly; tabular, no ambience |
| — | Search ranking | As proposed in §6.8 |
| — | Performance | 16ms warm, 100ms cold; no spinner in the browse path |
| — | Sync failure | Full resync, unmetered only, never touching downloaded audio |
| — | Versioning | Server declares, client adapts — same mechanism as compat mode |
| — | Playlists | Manual and smart; no folders; smart playlists unify with Home tiles |
| — | Player extras | Sleep timer only |
| — | Lyrics | Fetched at ingest, cached, attached to the Track |
| — | Sorting | Per screen, not remembered |
| — | Smart playlist sharing | Not shareable; convert to manual first |
| — | Logout | Asks; defaults to keeping downloads, re-associated by hash |
| — | Widget | Now-playing only, opaque, built in Glance |
| — | Statistics | A real screen; blocks not lines, since colour cannot be a stroke |
| — | Min client version | None. Protocol is additive-only, forever |
| — | Cover art scope | Personal to the account (overturns the §8 proposal) |
| — | Terminal client | None |
| — | Accessibility | Not specified; Graphit's own floors still apply |
| — | Library root | Segmented control: Artists / Albums / Playlists |

---

## 8. Proposed defaults for the remaining questions

Rather than another six rounds of questions, here are proposed answers for
everything still open. Each is what I'd choose and why. Override any of them and
I'll fold the correction in; silence means they go into the body of the spec as
decided.

### Ingest

| # | Question | Proposal |
|---|---|---|
| Q12 | Same URL fetched twice | Cosine records the source URL of every Object. A second fetch of a known URL is refused with "you already have this", unless you explicitly ask for another version. Stops accidental duplicates without blocking deliberate ones. |
| Q13 | Ingest queue visible from the phone? | Yes — queue, progress, failure reason and retry, in Sine. G4 says ingest has visible state; a queue you can only see over SSH fails that. |
| Q15 | Rate limiting | One or two concurrent ingest workers, MusicBrainz held to its documented 1 req/sec, and every lookup cached locally forever. Cheap, and it keeps you off the wrong end of an API ban. |
| Q73 | Source-API search with no key | Silent fallback to yt-dlp search, with a note in the dashboard saying which backend is actually running. Search should degrade, never fail. |
| Q61 | Resolver confidence in the UI | No numbers. Low-confidence Tracks carry a small marker in the library and appear in the review queue. A percentage tells you nothing you can act on. |

### Library and Store

| # | Question | Proposal |
|---|---|---|
| Q74 | Various-artists releases | A `Various Artists` pseudo-artist folder, which is what every other tool does and what the resolver can already detect. |
| Q71 | User-set cover art scope | ~~Global~~ — **overturned**: personal to the account. See §3.2a. |
| Q27 | Two Pointers to one Track in one Library | No. One Pointer per Track per account; playlists handle repetition. |
| Q22 | A shared Library everyone gets | No. Inbox broadcast already covers "everyone should have this", and a second always-shared collection would only duplicate it. |
| Q17 | Admin role | A single admin flag. Manages accounts and invites, Store path, GC, quotas, the visibility toggle, the search backend, and bulk corrections. Dashboard only — no admin surface in Sine. |
| Q58 | First run | Guided, in the dashboard: set the Store path, create the admin account, choose a search backend. No config file editing. |

### Devices, remote control and Jam

| # | Question | Proposal |
|---|---|---|
| Q78 | Server-relayed remote control off-LAN | Not built. Direct LAN as decided; the relay stays a known cheap addition if controlling the desktop from elsewhere turns out to matter. |
| Q82 | Do Jam plays count for everyone? | A play is recorded for every participant who holds a Pointer for that Track. Someone hearing it without having it records nothing — there is nothing to attach it to. If they add it during the jam, it counts from then on. |
| Q84 | Desktop playback backend | VLCJ. libVLC decodes everything yt-dlp can produce, it's a single dependency, and it saves writing format handling twice. GStreamer is the alternative if VLCJ's licensing or footprint becomes a problem. |
| Q85 | Which session does "resume" pick? | The current device's own session by default. When another Device has a more recent session, offer "continue from <device>" rather than asking every time. |

### Client

| # | Question | Proposal |
|---|---|---|
| Q33 | Storage caps | Separate per tier, per account. Cache gets a hard cap it evicts against; pinned gets a soft budget that warns rather than refuses — you asked for those files. |
| Q75 | SD card downloads | Yes. The visible download folder is chosen through SAF, so an SD card is just another location the picker offers. No extra mechanism. |
| Q37 | Merging plays across devices | Plays are append-only events with timestamps, not a counter that gets overwritten. Merging is a union; the play count is derived. This also makes offline plays trivially correct. |
| Q70 | Timestamps on late offline plays | Sine records the real time of play and sends it with the event. Cosine trusts it. Clock skew on your own phone is not worth defending against. |
| Q69 | Crossfade | Offered, off by default, with a configurable duration. Gapless remains the behaviour when crossfade is off — they are alternatives, not layers. |

---

## 9. Non-functional

### 9.1 Performance

`RESOLVED` **Two targets, and they are G2's acceptance test:**

| Case | Budget |
|---|---|
| A screen visited recently | **16ms** — one frame. It is already in memory; it paints. |
| A cold screen, first visit since launch | **100ms** — room for a query against the local mirror. |

No loading state exists anywhere in the browse path. If a library screen cannot
render inside these budgets from local data, the mirror is incomplete and that
is a bug in sync, not a case for a spinner.

`NOTE` These are local-data budgets. Streaming a Track that isn't cached is a
network operation and shows honest progress; browsing never is.

### 9.2 Failure and recovery

`RESOLVED` **A broken sync resyncs from scratch.** If Sine detects a corrupt
change log, a half-applied update, or state it cannot reconcile, it discards the
local mirror and rebuilds from cursor zero. Self-healing, and it avoids the
worse failure of running on quietly wrong data.

Two rules make that safe rather than expensive:

- **A rebuild waits for an unmetered connection** unless the user forces it.
  Metadata for a whole library is not something to pull down on mobile data
  because a change log hiccuped. This follows the metered-flag decision
  in §2.4.
- **A rebuild never touches downloaded audio.** Objects are keyed by content
  hash, so pinned and cached files survive a metadata rebuild untouched and are
  simply re-associated. Losing your offline library to a sync error would be
  unforgivable, and the hash-identity decision (§2.2) is what prevents it.

While rebuilding, Sine keeps serving what it already has — the library does not
go blank.

### 9.3 Versioning

`RESOLVED` **Cosine declares, Sine adapts.** The server states its version and
capabilities; the client hides what it cannot use.

This is the same capability model compat mode already relies on (§2.1), which
means there is **one mechanism, not two**: "this server is Navidrome" and "this
Cosine is older than this Sine" are the same question with the same answer. An
out-of-date server loses features, never function, and nobody is updating a
server from their phone in a kitchen to make the app open.

`RESOLVED` **Cosine never refuses a client.** There is no minimum version. An
old Sine connects, ignores capabilities it has not heard of, and loses features
rather than function.

⚠️ This is not a free choice — it is a permanent constraint on the protocol, and
it should be written on the wall:

> **The wire protocol is additive only. An existing field never changes meaning,
> and an existing endpoint never changes behaviour.**

Anything that would break an old client has to ship as a *new* capability
alongside the old one, with the old path kept working. That is real discipline
over a system's lifetime, and it is the price of never having to update a server
from a phone to make the app open. Worth it here, but only if it is followed
from the first endpoint rather than remembered at version four.

---

## 10. Open questions

Nothing is open. Every question in this document is answered, and every proposal
in §8 stands except the cover-art scope, which was overturned in §3.2a.

