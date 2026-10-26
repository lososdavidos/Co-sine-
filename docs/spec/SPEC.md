# Sine & Cosine — Spec Sheet

Single working document. Everything lives here; sections grow as decisions land.

**Status:** COMPLETE DRAFT · 164 resolved decisions · last updated 2026-10-26 (Phase's two blues, §6.1; motion and gestures, §6.16; Home tiles, §6.4)

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
| **Pointer** | A per-account, lightweight reference to a Track. Database row; carries that account's view — added-at date, play history (counts are derived from it, Q37), favourite, download state, optional version pin. |
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
Phase. Monochrome: a near-black ground and greys, with two blues of one hue —
Sine (blue) for you, now, and Cosine (deep blue) for the server at work — and
red for faults; type doing the work; no glass. See §6.

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
- **NG10** Accessibility is not specified. `RESOLVED` — a deliberate omission for a personal app rather than an oversight. Phase's contrast floors (every text colour at least 4.5:1 on the surface it sits on), focus rings and reduced-motion rule apply regardless because they are in the system, and its 44dp minimum hit area costs nothing to honour. Screen-reader support and large-font-scale layouts are out of scope unless that changes.

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

`RESOLVED` **A missing Object is hidden until it returns, and forgotten only on
request.** If a file is deleted outside the system or a disk is unplugged, the
server notices (it checks the Store every few minutes, and at once when a play
finds the file gone) and stops offering a Track with no file left: it leaves
every Library, and playlists skip it. Nothing about it is lost: its plays,
stars and playlist places are kept, and everything returns when the file does.
The dashboard lists missing files and can **forget** them, which drops their
records for good while the Track's history waits, out of sight, for the same
song to be added again. Forgetting is refused when every file is missing:
that is an unmounted disk, not deleted music.

A deleted file is not "already in your library", whether or not the check has
run yet: Add offers it again, and fetching or dropping in the same file puts it
back where it was instead of discarding it as a duplicate.

Revised from "greys out rather than disappearing": a deliberate deletion should
look like one in the apps, and an unplugged disk is recovered by plugging it
back in, not by keeping dead rows on screen.

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

`NOTE` Built: the owner shares a playlist with an account by name; the
recipient sees an invitation ("Shared with you") and accepts or declines it.
Accepting adds the Pointers and says how many ("Added 12 songs to your
library"). A shared playlist is one playlist, not a copy: every member sees
and edits it, and a song any member adds is given to every member's Library
too, so it keeps playing in full. Songs an account removed from its Library
itself are never given back this way (§4.1); they stay hidden for it. A member
can leave; the owner can stop sharing with anyone or delete it for everyone;
the songs a playlist brought stay either way. Only the owner shares it on.
Sharing is an explicit act by the owner, so it does not depend on the
visibility toggle below.

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

`NOTE` Built (manual only): the dashboard's **Unused files** page, linked from
Status, lists the files whose song every account has removed, largest first,
with a Delete per file and a separate "Delete N unused files (size)". A file
someone has added back meanwhile is never deleted. The song's history stays, as
with a forgotten missing file.

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

`NOTE` Built: manual playlists are made, reordered, trimmed, renamed and
deleted through the Subsonic API's own `createPlaylist`, `updatePlaylist` and
`deletePlaylist`, so Sine and every other Subsonic app edit them the same way.
Sine, on a Cosine account, keeps them in its mirror and edits them by
operation (§5.2), offline too. A song gone from the account's library keeps
its place in a playlist, hidden, and an edit carries it along beside the
song it followed.

`NOTE` Built: a smart playlist is a saved rule — one of the statistics views
(most played in a period, recently played, not played in N months, never
played, this time last year, added in the last N days) with its one setting.
Every account starts with one of each and can make, change, rename or delete
them; a deleted one does not come back. Over the Subsonic API they are listed
beside manual playlists as read-only (OpenSubsonic's `readonly`), with the
rule in words as their comment, and their songs are worked out each time one
is opened. Sine's "Save as playlist" is the conversion to a manual playlist.
"Downloaded only" is the phone's to answer and is not a server rule.

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

`RESOLVED` **yt-dlp search also asks YouTube Music for songs.** Labels upload
every release track by track to an "Artist - Topic" channel: the album audio,
without a music video's intro or outro, at the release's length, carrying the
release's artist, title and album. The first few are described in full, and the
one that is the song asked for is listed first.

`NOTE` Since October 2026 YouTube Music lists songs and albums only to
listeners who are signed in: a signed-out search, which is what yt-dlp makes,
offers videos, artists, profiles and podcasts, in every region tried. Cosine
no longer asks: each ask was a yt-dlp run, about a second of a small
server's CPU, for nothing.

`RESOLVED` **A search shows as it goes.** Each source's results appear as it
answers, everything so far in the order the whole answer will have, so the
first songs show in a few seconds rather than when the slowest source is done
(capability `lookup-stream`: `POST /cosine/v1/lookup/stream` answers a line of
JSON each time, marked `partial` until the last, which is what `lookup` gives).
Leaving the search stops it on the server too.

`RESOLVED` **Results are ranked by the song as released.** The released length
comes from Deezer's catalogue (public, no key): its listing of the song asked
for, not one of its versions ("(Stripped)", a remix) unless the search names
it. Every result
that is the same song is labelled with how its length compares ("Same length as
the release", "0:44 longer than the release"); those of the release's length
come first, the artist's own uploads before others, those much longer or
shorter (a video's intro, a 12-minute extended upload) after everything else,
and previews last. With nothing to compare against, the sites' own order stands.

`NOTE` Audiomack was considered as a fourth source and is not possible for now:
yt-dlp's Audiomack support relies on an endpoint Audiomack retired, and its
current API needs a consumer key that Audiomack issues only by arrangement.

`RESOLVED` **Best available stream, stored untouched.** The server takes the
highest-quality audio the source offers and writes it to the Store exactly as
downloaded — no re-encode, no remux, no container normalisation. The library
will hold mixed formats (Opus, M4A, MP3) and the client decodes all of them.

This is the only choice consistent with §2.2: the hash is the file you actually
received, and nothing ever rewrites it. Re-encoding an already-lossy source
would cost quality for nothing.

`NOTE` HLS is the one exception. An HLS source (SoundCloud, now that it offers
nothing else) sends no file at all, only a playlist of ~10-second segments.
Concatenated as received they form a fragmented MP4 whose header says nothing
of its length, and players read it as one segment long. Those segments are
joined by ffmpeg into a single plain file instead: the audio is copied
untouched, only the container around it is written once rather than 22 times.

`RESOLVED` **Bulk ingest is offered, and you choose the mode** per operation:

- **Queue everything** — paste a set, album or channel URL and take the lot.
- **Review first** — expand the collection, show the track list, pick what to
  keep before anything downloads.

`RESOLVED` **A playlist is never an album.** Only a release is: a YouTube Music
album, a Bandcamp album, a SoundCloud set its uploader marked as an album, EP
or single. Its songs take its name and track order where they don't say their
own. Anything someone put together — a YouTube playlist, a SoundCloud set
marked as a playlist, a mix — is a playlist however album-like its name ("Halsey
- hopeless fountain kingdom (Full Album)"), and each song is resolved as itself;
the playlist's name is never used as an album, even when a site reports it as
one. Unsure is a playlist. A playlist added this way becomes a playlist in the
Library (§2.4) in the source's order, filled in as its songs finish, with the
songs the account already had; adding it again fills the same one, and it can be
declined to take only the songs. A channel's uploads are just songs.

`RESOLVED` **Albums and playlists can be searched for, on every source that
allows it.** Add's search also lists YouTube's playlists (through yt-dlp),
Bandcamp's albums (through Bandcamp's own search), SoundCloud's albums and
playlists (through its API, when a client ID is set; yt-dlp searches
SoundCloud's tracks only). YouTube Music's albums are not searched: signed
out, it lists none (see above). Picking one shows its songs, as pasting its
link would. Those named like the search come first.

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

`RESOLVED` **The artist's own release, not a compilation the source names.** A
song is filed under the album it has been on, else its EP, else its single;
compilations, soundtracks and live albums come last, and an album the file
itself names goes first. A named compilation is the exception: YouTube credits a
video to whichever release claimed it ("Hot Party Winter 2018") and a ripped
"NOW" CD names one too, so it only goes ahead of other compilations. A song that
was only ever on compilations is filed under Various Artists (Q74).

`RESOLVED` **A remix is another track.** A catalogue title that names a version
— remix, rework, VIP, cover, live, acoustic, instrumental, karaoke, nightcore,
slowed or sped up — only matches a file whose title or album asks for it. By
name and often by length it is otherwise as good a fit as the song.

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

`NOTE` Built: "Remove from library" in Sine, on a release's selected tracks
or the whole release (not in a playlist, where "remove" would mean the
playlist). It asks first, naming the count, and deletes the songs' downloads on
the phone too. Adding a removed song again on purpose (Add, "Add anyway") lifts
the marker; an Inbox drop does not.

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
setup of their own. Late-arriving offline plays carry the time they were played
(Q70).

`NOTE` Built: an account connects Last.fm or ListenBrainz in Sine, and
Cosine sends every play it records from then on, whichever client reported
it, in the order the plays reached it — so plays made offline go when they
sync, with the time they were played. ListenBrainz needs only the account's
user token. Last.fm needs an API account for the server, made once by
whoever runs it and entered in the dashboard; each account then approves
Cosine on Last.fm (its desktop flow, no password typed into Sine). A
service that can't be reached is retried, backing off up to an hour; one
that refuses the account's access stops until it is connected again, and
the plays wait. Last.fm's "now playing" is not sent.

`RESOLVED` **A play counts after half the track or four minutes, whichever
comes first** — the scrobbler convention. The client that heard it decides and
reports it with its time; Cosine records what it is told. Sine applies this
rule; other Subsonic clients apply their own, which is the same convention in
practice.

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

`NOTE` Built so far (capability `delta-sync`): `GET /cosine/v1/sync?after=<cursor>`
answers `after=0` with the account's whole library and otherwise with what
changed — tracks to add or update, tracks to remove, and their albums and
artists. A changed track brings its whole release and a changed artist
everything of theirs, so a rename or a new cover reaches every track it touches.
Counts are worked out on the client, so they can't go stale. A cursor the
server never issued (a restored database) or more than 2000 changes gets the
whole library again. Sine syncs on opening, on reconnecting, every minute while
on screen, on a pull, and straight after an add or a correction; Library,
artist and album pages and search read the mirror. Still to come: the WebSocket
push, and Home from the mirror.

`NOTE` Built (capability `playlist-sync`): the sync carries playlists, each
with every entry in order whether shown or not (the client hides those whose
track it doesn't have, as the server does), invitations to shared playlists
(§2.3), and the smart playlists as last worked out. What a smart playlist
holds changes with listening and with time, which the change log doesn't
record, so they are worked out on every sync and sent only when they differ
from the client's (`smart=<tag>`). The operation log going up is
`POST /cosine/v1/playlists/ops`: create, rename, add (to the end), remove,
move (after an entry, or to the top) and delete, in order. An entry is named
by its track and which of that track's entries it is, counted just before
the op, so a song in a playlist twice is told apart. Each op has an id and is
applied once however often it's resent (an answer lost on a bad connection);
a playlist made offline is named by its create's id until Cosine gives it
one. An op that no longer fits — its playlist deleted, its song already
taken out — is skipped, not refused. A reorder goes up as the fewest moves
that make it, so a concurrent edit elsewhere survives it. Sine replays its
unsent ops over the server's copy, so what the phone shows is what Cosine
will hold, and sends them before each sync.

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
`REVISED`: the mirror starts as one JSON file per account in `sine/core`, like
the downloads index, held in memory while the app runs. The library is curated
rather than accumulated (NG8), so it stays small; plain Kotlin keeps it
testable on the JVM and portable (G8). Room is the move if it outgrows memory.

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
the design language loses nothing. Phase uses no backdrop filter at all
(§6.1): its one blur, on a page while something slides over it, is a snapshot
drawn small and then large (§6.16), so no surface costs a filter.

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

Sine and Cosine's dashboard share one design language, **Phase**, introduced in
0.5.2. It replaces Graphit v0.7, which earlier drafts of this section were
written against; the decisions below that Graphit shaped keep their substance,
and where Phase changes the answer they say `REVISED`. This section is about
what Phase forces, and what Sine decides on top of it.

### 6.1 What Phase decides

These are not open. They arrive with the system and shape every screen below.

| Rule | Consequence for Sine |
|---|---|
| **Type carries the interface** | Names are set large in Instrument Sans at width 75, bold; data — lengths, counts, years, sources — small in Martian Mono. Navigation is words. Icons are kept for transport, where everyone knows them, and for marks too small for a word. |
| **Monochrome, with two blues** | **Sine** (blue, `#53A4FF`, oklch 0.71 0.155 253) is you, now: what's playing, what's selected, the one action a screen is for. **Cosine** (deep blue, `#1070C5`, oklch 0.54 0.155 252: the same wave, shifted, as the names say) is the server at work: fetching, matching, syncing, waiting for your review. Both sit at hue 252, short of the 260s where blue starts to read violet, and the greys carry a trace of the same hue rather than none. Sine takes black ink; Cosine, too deep for it, takes Text 1. `REVISED` (was: lime and sky, a quarter turn apart). |
| **Red is for faults** | `#F75E51`: broken or missing, a fetch that failed, and the button that removes or deletes. Never decoration. Everything that isn't a signal is grey or artwork. |
| **Artwork is the only colour that varies** | Covers are shown whole, and blurred into headers behind a scrim that never changes. Never sampled into a palette, so the signals mean the same beside any cover. |
| **Shape says what a thing is** | Square (3dp corners): something you read — a cover, a tag. Circle: a person — an artist's picture, an account. Pill: something you press, transport included, wider than tall so it never reads as a person. Slab (16dp; 24dp when floating): a surface that holds things. |
| **Depth by light, not glass** | No glass, and nothing rests blurred. Ground `#0A0B0C` — not pure black, because fully-off pixels smear when a list scrolls on OLED — then Raised `#151618`, Float `#1F2123` and Lift `#2C2F32`. A floating surface is Float with a hairline edge. The one blur is movement: a page blurs as something slides over it and comes back into focus as it's uncovered (§6.16). `REVISED` (was: no blur anywhere). Android below API 31, the widget and the web look identical: one code path. |
| **An overlay never opens another overlay** | See §6.5. This is the single hardest constraint for a music app. |
| **Selection is Sine** | The whole row fills blue and its text turns black. Where you are — the current destination, segment or filter — is the light pill instead: location, not selection, so never a signal. |
| **A tag or a dot** | A tag is one filled word on a row and asks you to look: UNSURE in Cosine, MISSING in red; an outlined tag only describes. A dot starts a sentence of status: Cosine for work in progress or waiting on the server, red for something that has stopped. |
| **One Sine pill per screen** | The primary action is blue; everything else you press is Lift or quiet text. A destructive pill is red, names the verb and the count, and never sits beside the primary. |
| **Long-form prose uses Text 2** | `#A4A7AA`: pure white blooms at paragraph length on OLED. Names and titles are Text 1 (`#F1F3F5`); labels, and what can't play right now, are Text 3 (`#888B8F`, 5.8:1 on the ground, 4.7:1 on Float). |
| **Numbers keep their column** | A list's row numbers (a release's track numbers, a playlist's or the queue's positions) are mono, at least two digits, in a column as wide as the list's widest number, so a hundredth row neither clips nor wraps and every title stays in line. |
| **Four-pixel grid** | Every dimension is a multiple of 4. Rows are 52dp for one line and 64dp with art; hit areas are never under 44dp. |

Phase has no glass, so floating costs no filter; it still costs reserved space
at the bottom of every list, which §6.3 accounts for. The fonts are bundled
(SIL Open Font License; `third_party/fonts`, built by `scripts/fonts.py`), so
nothing is fetched to draw a screen.

### 6.2 Navigation

`RESOLVED` **Three top-level destinations** in the floating bar: **Home**,
**Search**, **Library**.

`RESOLVED` **The Library root is a segmented control** — Artists / Albums /
Playlists — so playlists sit as an equal to the canonical tree rather than
bolted onto it. Downloaded and Recently added are filters within a segment, not
segments of their own.

`RESOLVED` **Swipe sideways between the three.** Home, Search and Library sit in
a row in the bar's order. Swiping right to left moves to the next one, left to
right to the previous, and the page follows the finger (§6.16). They stack in
that order: going right, the next page slides in from the right over the
current one, which blurs underneath; going left, the current page slides away
to the right and the one beneath comes back into focus. That is the same
movement as opening a page and going back, so one gesture means one thing
everywhere. Tapping a word in the bar runs the same slide in the same
direction, skipping the page between when there is one, and Android's back
from Search or Library goes to Home the same way. Past either end, the page
doesn't move.

`RESOLVED` **On these three, sideways always means the destinations.**
Library's segments are tapped, not swiped, so a swipe never has two meanings.
Anything inside a page that scrolls sideways itself takes the drag first.

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

Every scrolling surface in the app reserves that space at the bottom; the last
row of every list is unreachable if it is missed.

`RESOLVED` **One shelf that splits when playing.** Idle, it is a single 52px
navigation bar. When something starts playing, a player row grows out of it and
the shelf becomes two rows in one floating surface, not two.

⚠️ The reserved padding therefore changes at runtime: **84px idle, 148px
playing.** Two implementation requirements follow, and both are the kind of thing
that ships broken:

- The change must animate on the 160ms move (§6.16) together with the shelf,
  not snap a frame later.
- A list scrolled to the bottom must not jump when padding grows. Anchor the
  scroll position to content, not to offset, or starting playback will visibly
  shove the list.

`NOTE` Built (0.5.2): the shelf measures itself and every list reserves what it
covers. Navigation is three words; where you are is the light pill. The shelf
also talks back. After an action, a message sits above navigation for four
seconds — with Cosine's dot when it's about the server's work, red when
something failed — and tapping it puts it away sooner; navigation stays
reachable throughout. A question that waits for an answer (remove, leave,
delete, log out) takes the shelf's place until it's answered or dismissed.
Neither opens on top of anything.

### 6.4 Home

`RESOLVED` **Home is a tile grid, in the Windows Phone / Metro sense** — live
tiles, flat blocks, no chrome, typography doing the work.

This is a much better fit with Phase than it sounds. Metro was a black ground
covered in flat blocks with no gradients, no bevels and no shadows, where colour
carried meaning and type carried everything else — Phase's first two rules,
nearly word for word. Tiles are slabs that scroll with the page: **no glass on
Home**, as nowhere else.

Three places the two systems disagree, where Phase wins:

| Metro did | Phase requires |
|---|---|
| White text on the accent colour | **Ink by lightness.** Sine and red take black ink; Cosine, the deep blue, takes Text 1. No other pairings. `REVISED` (was: black ink on all three). |
| An accent colour per user, any hue | **Signals only where they mean something.** A tile is artwork, a Raised slab, or a signal: Cosine for the review queue's count, Sine only for what's playing. Nothing else. |
| Tiles bleed to the screen edge, gap-separated | **4px grid, 16dp slabs.** Tiles keep their gaps — a grid is not a list — and every dimension is a multiple of 4. |

`RESOLVED` **Home is a canvas, not a designed screen.** The user pins, resizes
and reorders tiles freely. There is no fixed layout — a default arrangement on
first run, and everything after that is theirs.

`RESOLVED` **The layout is kept on Cosine,** per account and per kind of
device: phone and desktop each have their own (§6.15). It survives a reinstall
or a new phone. Changes made offline stay on the phone and go with the next
sync.

`REVISED` **Tiles stay put; what's inside them moves.** (Was: live in content,
not in motion — no flipping, sliding or animating between faces.) Artwork
cycles, counts update, the current track changes, and the parts inside a tile
may slide, spin, fade or move any other way while they do: a cover sliding in
over the last one, a count rolling over to its new number, a title fading
through. The tile itself — its place in the grid, its size, its edges — moves
only when you move it (§6.4.4). Home is the one place motion is for liveliness
rather than feedback. Phase's other rules hold inside a tile all the same:
artwork stays scrimmed, colour stays a signal, ink stays black on the signals.

`RESOLVED` **How often.** A tile that cycles shows its next cover every 8 to 10
seconds, staggered so neighbours don't move together. Tiles move only while
Home is on screen, and not at all with Android's animations removed: their
content still changes, at once.

#### 6.4.1 Tile catalogue

Two families.

**Pinned items** — anything in the library, pinned from its own screen via the
same long-press selection used everywhere else:

- An artist, album or playlist. Artwork-filled; tapping opens it.
- A **shuffle** variant of any of the above — the tile plays rather than navigates.

**Generated tiles** — the system builds the content:

| Tile | What it shows |
|---|---|
| **Now playing** | Current track and artwork; when nothing is playing, what played last, and tapping it picks up from there |
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

`REVISED` **Now playing never leaves Home.** (Was: absent when nothing is
playing, which contradicted §6.4.5: a tile keeps its place.)

`RESOLVED` **A list tile opens its list.** Recently added, Most played, This
time last year, Forgotten, Never played, Downloaded and Favourites open the way
a playlist does; a tile's shuffle variant plays instead. Shuffle all, Now
playing and Resume play. Review queue, Ingest activity and Jam open what they
summarise.

`RESOLVED` **A list tile is a smart playlist** (§2.4), and its rule is the
smart playlist's. Most played's window — 30 days, this year or all time — is
the one its smart playlist has; for another window, make that smart playlist
and pin it too. The six every account starts with (Recently added, Most played
this year, Recently played, Forgotten, Never played, This time last year) are
pinnable as they are.

`RESOLVED` **Generated tiles are added from Home.** An Add tile action lists
them alongside your smart playlists. Pinned items still come from their own
screens.

`RESOLVED` **Resume, Jam and Favourites wait for their features.** Resume
needs per-Device sessions (§4B), Jam needs Jam (§4C), and Favourites needs a
way to star a song in Sine. Until then those tiles aren't offered.

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

`RESOLVED` **What each size shows.** 1×1: the cover, or the count. 2×2 adds the
name. 4×2 adds a second line: the artist, the window, or what's fetching.

#### 6.4.3 Tile appearance — artwork as ambience

`RESOLVED` **A tile's ground comes from its artwork.** Home is ambient: a tile
for an album, artist or playlist takes its character from the cover it
represents, blurred and pushed toward the ground behind the tile's own ink.

This needs one rule to stay inside Phase, because as stated it appears to
break the rule that colour is a signal:

> **Artwork may be a ground. It may never become a palette.**

The distinction is exact and worth holding onto:

- **Allowed** — the cover itself, blurred and scrimmed, as the tile's backdrop.
  Artwork is *content*, in the same way a photograph in an article is content.
  The signal rule governs Sine, Cosine and red, not photography.
- **Not allowed** — sampling a dominant colour from the cover and painting a
  flat block in it. That introduces a fifth, sixth and hundredth colour as a
  plane, which is precisely what the rule exists to prevent, and the signals
  stop meaning anything the moment arbitrary hues sit beside them.

Phase has no ambient field of its own: real content — the artwork — is the
only backdrop it ever has.

⚠️ **Contrast has to be guaranteed, not hoped for.** Album art ranges from black
to blown-out white, so ink over it needs a fixed floor — a scrim
(the ground at 64%, or a bottom-weighted gradient under the label) applied at
constant opacity regardless of the image. The value is a contrast floor, not a
style choice. Never sample the image to decide whether to darken it; darken it
always.

**Tiles with no artwork** — Shuffle all, Review queue, Ingest, Jam — sit on
Raised slabs, and use a signal only where it carries meaning: Cosine for the
review queue's count and for active ingest, red for failures only. Red stays destructive-or-broken everywhere in Sine; it is
never decorative.

#### 6.4.4 Editing

`RESOLVED` **Long-press selects a tile and the shelf swaps to tile actions** —
Resize, Unpin, Move (one place earlier or later) — with drag available while
selected. Identical to the
gesture used everywhere else in the app; nothing new to learn, and no separate
"jiggle mode" with its own rules.

#### 6.4.5 Empty tiles

`RESOLVED` **A tile with nothing to show keeps its place and shows a resting
state.** The Jam tile offers to start one; Now Playing shows what you played
last; Review queue shows zero. The layout is the user's, and it must never
reflow underneath them because a background process finished.

`NOTE` Built: the canvas, in four columns, each tile placed in order in the
first spot from the top where it fits, squares on a multiple of their width.
- **Tiles:** Now playing; Shuffle all; On this phone, which opens a list of
  what's downloaded; Review queue; Adding, which shows what Cosine is fetching;
  and any artist, album or playlist, smart ones included.
- **Pinning:** Pin to Home sits in the header of an artist's, album's or
  playlist's page. Add tile on Home lists the rest.
- **Selected, the shelf offers:** Resize; Earlier and Later, which move the
  tile one place, while dragging it moves it anywhere; Shuffle on tap or Open
  on tap, for a pinned tile; and Unpin.
- **Where the layout lives:** on the phone, and on Cosine with capability
  `home-layout` (`GET` and `PUT /cosine/v1/home/{device}`, `phone` or
  `desktop`). Cosine keeps it as sent, in its settings table, under a key that
  carries when the account was made, so a later account under a reused id
  starts afresh.
- **Not yet:** the statistics tile, as Sine has no statistics screen yet.

### 6.5 The stacked-overlay problem

Phase, like Graphit before it, forbids an overlay opening another overlay. A
conventional music app
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
row selects it — the whole row fills Sine with black ink (§6.1) — and the
shelf's contents swap to the available actions for that
selection. No overlay is opened, so nothing conflicts, and multi-select comes
free: long-press one row, tap others, act on all of them.

This also means the shelf has three states, not two:

| State | Contents |
|---|---|
| **Idle** | Navigation only, 52px, 84px reserved |
| **Playing** | Player row + navigation, 108px, 148px reserved |
| **Selection** | Action row + count, replacing navigation for as long as a selection exists |

So the fourth "Options" pane in the now-playing sheet is not needed.

`RESOLVED` **No pop-ups.** Small forms — naming a playlist, sharing it, adding
to one, a smart playlist's rules — are pages. They slide in from the right like
any other page, and swiping back cancels. A dialog would be an overlay, and
opened from the now-playing sheet it would be an overlay on an overlay. A page
opened from the sheet slides in over it like over any page, and back returns
to the sheet.

### 6.6 The now-playing sheet

`RESOLVED` **The sheet has two heights, not three panes.** Dragged up from the
shelf it shows now playing; dragged further, the queue rises from its bottom
edge. This is one surface at two heights, not an overlay opening an overlay, so
the rule holds.

`REVISED` **The sheet follows the finger.** (Was: it opened on a drag, then
played its own 220ms.) Dragging the player row up brings the sheet up from the
bottom under the finger; dragging further brings the queue up; dragging down
takes them back. The page beneath blurs as the sheet covers it and comes back
into focus as it goes (§6.16). Tapping the player row slides the sheet up on
its own, and Close or back slides it down. Open, it covers the whole screen. A
downward drag closes it only when its content is scrolled to the top, and a
drag on the wave always seeks.

`RESOLVED` **Jam is a strip inside the sheet.** When a session is active, a
participant strip sits above the transport — who is listening, who queued the
current track. Starting a jam is a long-press action on the shelf, like every
other action in the app. Jam stays attached to what is playing and costs no new
surface.

`REVISED` **The cover's ambience fills the sheet's top; the cover sits on it,
whole.** (Was: full-bleed artwork behind a scrim, handing off to glass beneath
the controls — a resolution of Graphit's glass rule that Phase no longer needs.)
The sheet is a Float surface with 28dp top corners and a hairline edge. The
cover, blurred and scrimmed, fills its upper region and fades to Float under the
title; the cover itself sits on that ambience, square and whole. Title,
position and transport sit on plain Float, where their contrast is known.

`REVISED` **Progress is the wave.** (Was: a growing blue plane.) A sine wave
across the sheet: Sine up to the position, grey after it, with a Sine knob.
Drag it or tap it to seek. This is the one place the wave appears — the
product's name, where it belongs — and everywhere else progress is a straight
line: the shelf's seam in Sine, a fetch's bar in Cosine.

`RESOLVED` **The timecode sits off the wave** — elapsed and remaining below it,
in mono, always Text 2, never overlapping.

- Growth is continuous: motion here is feedback about a real process, not
  atmosphere.

`RESOLVED` **The shelf's player row carries artwork, title, play/pause, skip, a
progress line along the seam beneath it, and a Device indicator.** The
Device marker is not decoration — once remote control exists, playback may be on
the desktop, and without it you press play on the wrong machine.

`NOTE` There is one honest escape hatch worth remembering: Phase forbids an
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
   top of the screen and fades out; content below sits on the ground. Long lists
   over a photograph are unreadable, and Phase's Text 2 prose rule assumes a
   known ground.
2. **The scrim is constant, never adaptive.** Same discipline as Home (§6.4.3):
   darken always, never sample the image to decide.
3. **Blur once, at load, not per frame.** The ambience is a pre-blurred,
   downscaled copy of the artwork drawn as a static image — not a runtime blur
   and emphatically not a backdrop filter, which Phase has none of. Cache it
   beside the cover.

`NOTE` Built (0.5.2): the ambience is a 24px copy of the cover drawn large, which
is the blur: it costs nothing per frame and looks the same on every Android
version. From API 31 a blur smooths it further.

### 6.8 Library and search

`RESOLVED` **Track rows are quiet by default.** Title, artist, duration at the
40px density. A plane appears only when something is true of that row:

| State | Treatment |
|---|---|
| Unreviewed (low-confidence resolve) | A Cosine tag: UNSURE |
| Unavailable (missing Object) | A red tag: MISSING, and the row's ink drops to Text 3 |
| Downloaded / pinned | A small mark, not a tag — it is the common case, and a column of tags down every list destroys the quiet |
| Playing | Three Sine bars in place of the track number |
| Selected | The whole row fills Sine with black ink (§6.1) |

The principle: a tag means *pay attention to this row*. If most rows have one,
none of them do.

`RESOLVED` **Search is instant, local and returns one ranked list.** No network,
works offline, results as you type against the client mirror.

Mixing Artists, Albums, Tracks and Playlists in one list needs two things:

- **Type is a mono label**, not a tag — see the rule above.
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

`RESOLVED` **Pulling a list down refreshes it.** Home, the Library segments,
search results, artist, release and playlist pages, and the review queue all
reload when pulled down, including while they show a message in place of the
list ("No albums.", "Cosine unreachable…"). What's on screen stays until the
answer arrives, and is then replaced with exactly what a fresh visit would show.
A pull always asks the server, even if it failed a moment ago: it is how you
retry once the server is back. With the local mirror (G2), a pull runs a delta
sync instead of reloading the screen.

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

`NOTE` Built with the mirror: oldest first by year, releases without a year
last. A single row plays that song and the artist's singles after it, and a long
press selects it as on a release. Without the mirror (compat mode) the tracks of
each release aren't known up front, so every release shows as a release.

`RESOLVED` **An artist has a picture, shown round.** It comes from Deezer, whose
artist pictures are square press photos: through MusicBrainz's link to the
artist's Deezer page when MusicBrainz knows them, else by name, taken only from
a Deezer artist of that name who has a track or release named like one of
theirs in the library — two artists can share a name, but rarely a song as well.
Cosine looks in the background and saves the picture beside release covers; an
artist without one shows one of their covers until a picture is found, and is
looked for again a month later. "Various Artists" has none.

`RESOLVED` **A Track with no artwork gets no ambience** — flat ground, type
doing the work, and a Raised square where the cover would be. No generated
colour: §6.4.3 rules out decorative use of the signals, and a deterministic hue
per artist would make them decorative immediately.

`NOTE` A side effect worth having: artwork becomes visibly valuable. A release
with a cover looks richer than one without, which is quiet pressure to use the
user-set artwork override (§3.2a) on the unreleased material — the exact
material that needs it.

### 6.10 Adding music

`REVISED` **Add opens a page with a single field.** (Was: a sheet. Like every
form, it slides in from the right; §6.5, §6.16.) Paste a URL or type a
query into the same input — Cosine works out which it is. Results appear
beneath; picking one queues the ingest. One surface, no modes, no tabs.

`RESOLVED` **Sine registers as a share target.** Sharing a track from
SoundCloud, YouTube or anywhere else opens this same page with the URL already
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

`NOTE` Built: a search shows "Albums and playlists" beside the songs, first
when one is named like the search. Opening one shows its songs to tick, says
whether it is an album or a playlist, and for a playlist offers "Keep it as a
playlist in my library" (on by default), with "Make the playlist" when every
song is already there. "Back to search results" returns to the search.

### 6.11 The account control

`RESOLVED` **A persistent control in the top corner, on every screen except
now-playing.** Tapping it opens a choice of **Account** or **General** settings.

Three things follow that are worth writing down:

- `REVISED` **It's a person, so it's a circle.** (Was: a glass control.) Your
  initial on Lift — on Float over artwork — beside the screen's title or the
  back pill, 20dp in from the edge. No filter.
- **The menu it opens is Float**, like every menu. Choosing Account or
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
Most recently added first, so a backlog never hides what you just added; the
rest pages in behind it.

The queue is reachable from the Library header beside Add, and from its Home
tile. Its count is a Cosine pill; zero is a resting state, a plain one, not an empty
screen.

`RESOLVED` **Anything filed can be corrected, not only what is queued.** A
confident match never enters the queue, so a wrong one would have no way back:
every finished item in Add's list of recent adds opens its correction screen.

`RESOLVED` **The correction screen shows all sources at once, ranked, with each
candidate labelled by origin** — MusicBrainz, Discogs, Bandcamp — so you can see
when two sources describe the same release, and **a fall-through to manual
entry**. For unreleased material, typing the artist and release yourself is not
a fallback; it is the only honest answer, and it needs to be one tap away rather
than buried.

`NOTE` Built (0.5.2): picking a candidate selects it — the row fills Sine — and
the shelf offers "Use this match", saying that it moves the file for every
account.

### 6.13 Statistics

`RESOLVED` **A proper statistics screen.** Top artists and tracks by window,
listening over time, first-played dates, most-played by year. Every play is
already a timestamped event (§8, Q37), so this reads data that exists rather
than requiring new collection.

This is not a recommendation engine and NG3 stands: it describes what you did,
it never suggests what to do next.

`RESOLVED` **How the numbers are counted.** Artists are counted by each track's
own credit, so a compilation counts for the artists on it, not for Various
Artists (Q74). Listening time is the track's length for every play, not the
seconds actually heard. *First played* is the first play ever, whatever window
is shown. Days and months follow the server's time zone. Tracks whose files
are gone still count: history is never pruned.

Reachable from the account control, and pinnable as a Home tile.

⚠️ **Phase rules out most chart types, and the rule is unusually clarifying.**
Colour is a signal, and a signal is a fill or a mark — never a line. So:

- **Bar charts and filled area charts are fills.** Bars are grey; the period
  you are in now is the one Sine bar.
- **Line charts are impossible in colour.** A line is a stroke, and a coloured
  stroke is forbidden. A trend line can only be drawn in Text 2 as a
  neutral, or redrawn as a filled area.
- Axes and gridlines are hairlines; numbers are mono with tabular figures.

The result is a stats screen made of blocks, which suits both the design
language and the data.

### 6.14 Offline

`RESOLVED` **Everything stays visible; what cannot play is dimmed.** The whole
library remains browsable with no network. Tracks without a local copy drop to
Text 3. `REVISED`: they no longer carry red. Not having a copy isn't broken,
and red stays for a missing Object.

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
- Both the sidebar and the docked player stay put while content scrolls, so
  both are Float surfaces with a hairline edge, as the shelf is on the phone.
- The model, sync and library code are shared with Android unchanged; what
  differs is layout and the audio backend (§5.2a).

`RESOLVED` **Tiles keep their unit size and the grid gains columns** on a wide
window. Four columns stretched across 1600px would be absurd.

The consequence is that **a phone arrangement does not transfer**: desktop keeps
its own Home layout. That is two Home screens to maintain per account, which is
the honest cost of a canvas rather than a designed screen — and probably correct,
since what you want at a desk is not what you want on a phone.

### 6.16 Motion

`REVISED` **Things slide, and they move with the finger.** (Was: strictly
Phase's three durations, pages changing by a 160ms fade, and the sheet playing
a timed 220ms however it was dragged.) Motion says where a page is. A page that
opens comes in from the right over the one you were on; closing it sends it
back out the way it came. Now playing comes up from the bottom and goes back
down.

| Movement | By hand | By tap |
|---|---|---|
| Opening a page (artist, album, playlist, settings, review, a form) | — | Slides in from the right over the current page |
| Going back | Swipe left to right, starting anywhere on the page | Back slides the page out to the right |
| Home · Search · Library | Swipe sideways (§6.2) | Tap a word in the bar |
| The now-playing sheet | Drag the player row up, or the sheet down (§6.6) | Tap the player row; Close or back |
| The queue | Drag the sheet further up, or back down | — |

`RESOLVED` **A gesture moves with the finger and stops with it.** The page is
wherever the finger puts it, one to one. It holds when the finger holds and
reverses when the finger does. On release it finishes the move if it's past
halfway or was flicked that way, and otherwise goes back, carrying on at the
finger's speed rather than starting a new animation. Nothing overshoots.

`RESOLVED` **The page underneath comes from a blur.** As a page or the sheet
slides over another, the page being covered blurs and dims slightly as it goes
under; as it's uncovered it comes back into focus. How blurred it is follows
the slide's progress, whether a finger or a tap drives it. Nothing rests
blurred: a covered page isn't drawn, and a finished slide leaves every surface
sharp. This is the one blur in Phase (§6.1).

`RESOLVED` **The blur is a snapshot, not a filter.** When a slide starts, the
page underneath is captured once and drawn small, then large, which is the
ambience's trick (§6.7). The slide fades between the sharp capture and the
blurred one. Nothing is blurred per frame, so it costs the same and looks the
same from Android 8 up, with no separate path below API 31.

`RESOLVED` **Who gets the drag.**
- Something under the finger that moves sideways by itself takes a sideways
  drag first: a row that scrolls sideways, or the wave, which always seeks. The
  page moves only from places that don't.
- A drag is sideways or up-and-down from its first few pixels, and stays that
  way, so scrolling a list never turns into a swipe.
- While something is selected, sideways swipes are off, and back clears the
  selection first. That includes a selected tile being dragged on Home.
- A swipe that starts at the screen's very edge is Android's back gesture. From
  Android 14, predictive back drives the same slide, so it follows the finger
  too; before that, back plays the tapped slide.

| Change | Token |
|---|---|
| Selection, hover, press, fills | State: 100ms |
| Shelf splitting and collapsing, padding reflow, tile resize, shelf messages | Move: 160ms |
| A page, a destination or the sheet, by tap | Slide: 300ms, easing out |
| The same, let go mid-gesture | From the finger's speed to rest, no overshoot |

`RESOLVED` **Inside a tile, anything moves** (§6.4): slide, spin, fade. The
tile doesn't, and none of it follows these tokens; it only has to stay
staggered and stop when Home is off screen.

`RESOLVED` **With Android's animations removed,** a tap changes the page at
once. A page moved by hand still follows the finger, because that is the
person moving it rather than an animation, and on release it jumps to where it
was going. Tiles change their content without moving (§6.4).

Two consequences accepted deliberately:

- **Still no shared-element transition.** The shelf's artwork does not travel
  into the sheet; the sheet slides up carrying its own cover. That's one fewer
  bespoke animation, and motion stays about where things are.
- **Desktop has no swipes.** Clicks run the same slides. Its panels (§6.15)
  don't slide, because they don't cover anything. The dashboard (§6.18) is a
  set of web pages and keeps ordinary page loads.

`NOTE` Built: Sine keeps its own page stack, one per destination, in place
of Navigation Compose.
- **What the finger does:** a sideways drag anywhere on a page goes back;
  on a destination's first page it goes along the bar instead. The player
  row drags now playing up, and the sheet comes down from wherever its list
  can't scroll further.
- **Android's back gesture:** it runs the same slides, and from Android 14
  they follow it.
- **The snapshot:** Compose's own layer capture, drawn small and then large.
  Where a device can't make one, the page beneath is dimmed only.
- **The wave:** any drag that starts on it seeks.

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

`RESOLVED` **Cosine's dashboard uses Phase.** Same tokens, fonts and rules as
Sine, in the browser: one stylesheet, `phase.css`, served from the binary with
the fonts, so the dashboard looks the same with no network at all.

Practical notes:

- Admin work is tabular, so the dashboard leans on tables, mono labels and
  tabular figures rather than the ambient surfaces Sine uses.
- **No ambience.** The dashboard is about many things at once, and §6.7 restricts
  ambience to single-subject screens.
- Destructive actions follow Phase's rule exactly: a red pill with an explicit
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
- **The widget is simply Phase.** Widgets cannot blur what is behind them,
  and Phase never asks them to: a Float slab, the cover square, a Sine play
  pill. The widget needs no fallback, because there is nothing to fall back
  from.

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
| — | What counts as a play | Half the track or four minutes, decided by the client that heard it |
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
| — | Playlists from sources | Never an album; each song filed as itself, the playlist kept as one |
| — | Album and playlist search | Every source that allows it, beside songs |
| — | Visibility | One global server toggle |
| — | Account creation | Admin-created, or invite code |
| — | Account deletion | Pointers removed, Objects kept |
| — | Orphan GC | Manual by default, optional automatic with grace period |
| — | Store layout | `Artist / Album / Track`; singles get an album named after the song |
| — | Missing files | Hidden until the file returns; forgotten from the dashboard on request |
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
| — | Shelf | One floating surface; splits into player + nav when playing; three states |
| — | Context actions | Long-press selection + action row in the shelf; multi-select free |
| — | Home | Metro-style tile grid, solid slabs, the signals' own ink |
| — | Home layout | Fully user-arranged: pin, resize, reorder. No fixed design |
| — | Tile liveness | Content cycles; the tile stays put, the parts inside it may slide, spin or fade |
| — | Play history | Never pruned — retrospective tiles depend on it |
| — | Segments | Remembered per visit; Search is always global |
| — | Tile grid | 4 columns; 1×1, 2×2, 4×2 |
| — | Tile ground | Artwork as ambience, always scrimmed; never sampled into a palette |
| — | Tile editing | Long-press selection + shelf actions, same as everywhere |
| — | Empty tiles | Keep their place, show a resting state |
| — | Now-playing sheet | One surface, two heights; queue rises from the bottom edge; follows the finger up and down |
| — | NP artwork | Ambience across the sheet's top, the cover whole on it; controls on plain Float |
| — | Progress | The wave on now playing, Sine up to the position; a straight line everywhere else |
| — | Player row | Artwork, title, play/pause, skip, progress hairline, Device indicator |
| — | Jam | A participant strip inside the now-playing sheet |
| — | Ambience | Any single-subject screen; header only, constant scrim, pre-blurred |
| — | Track rows | Quiet by default; a tag means exception, not status |
| — | Search | Instant, local, one ranked list |
| — | Add | Single-field page; Sine is a share target for URLs |
| — | Review queue | List, tap to correct; count as a Cosine pill |
| — | Offline | Everything visible, unplayable dimmed |
| — | First run | Type the address; mDNS suggests; mode shown explicitly |
| — | Account control | Persistent circle, top corner, all screens but now-playing |
| — | Correction screen | All sources ranked and labelled, plus manual entry |
| — | Share target | Native accounts only, most recently added wins |
| — | Desktop | Sidebar, docked player, queue panel; panels not overlays |
| — | Artist page | Ambient header, then releases; single-track releases collapse to track rows |
| — | Timecode | Sits off the wave, mono, always Text 2 |
| — | No artwork | Flat ground, no ambience, no generated colour |
| — | Desktop Home | Same unit size, more columns; separate layout from the phone |
| — | Artist ordering | Interleaved chronologically, albums and singles together |
| — | Motion | Things slide and follow the finger: pages from the right, now playing from the bottom; a tapped slide is 300ms; still no shared-element transition |
| — | Voice | Plain and factual; state what is true and what happens next |
| — | Dashboard | Phase's stylesheet and fonts, served from the binary; tabular, no ambience |
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
| — | Accessibility | Not specified; Phase's own floors still apply |
| — | Library root | Segmented control: Artists / Albums / Playlists |
| — | Shared playlists | One playlist every member sees and edits; songs any member adds reach every member's Library, except songs one removed itself |
| — | Sharing and visibility | Sharing is the owner's explicit act; it doesn't depend on the visibility toggle |
| — | Playlist ops | Entries named by track and occurrence; each op applied once by id; ops that no longer fit are skipped; a reorder is the fewest moves |
| — | Smart playlists offline | Sent with the sync as last worked out, again only when they change |
| — | Last.fm account | One API account per server, entered by its admin; each account approves Cosine on Last.fm |
| — | Scrobbling start | Plays from the moment an account connects; earlier history isn't sent |
| — | Design language | Phase replaces Graphit v0.7 (0.5.2): two signals, red for faults, type doing the work, no glass |
| — | Signals | `REVISED` Monochrome with two blues of one hue (oklch 252, never toward violet): Sine `#53A4FF` with black ink, Cosine `#1070C5` with light ink (was: lime and sky) |
| — | Ground | `#0A0B0C`, not pure black; depth by light: Raised, Float, Lift; greys tinted faintly toward the blues, not violet |
| — | Row numbers | At least two digits, in a column as wide as the list's widest number |
| — | Type | Instrument Sans (widths 75 to 100) and Martian Mono, bundled under the OFL |
| — | Shape | Square is read, circle is a person, pill is pressed, slab holds |
| — | Status marks | A tag asks you to look; a dot starts a sentence of status |
| — | Shelf messages | Above navigation for four seconds; a question takes the shelf until answered |
| — | Offline, no copy | Dimmed only, never red: red stays for a missing Object |
| — | Swipe back | Left to right, starting anywhere on a page; Android's edge back gesture runs the same slide |
| — | Destination swipe | Home · Search · Library side to side in the bar's order; the next page slides over, the previous is uncovered |
| — | Library segments | Tapped, never swiped |
| — | Blur in motion | The page underneath blurs as it's covered and sharpens as it's uncovered; nothing rests blurred |
| — | How it blurs | A snapshot drawn small then large; no per-frame filter, one code path from Android 8 |
| — | Letting go | Finishes past halfway or on a flick, otherwise goes back; carries on at the finger's speed, no overshoot |
| — | No pop-ups | Small forms are pages that slide in from the right; swiping back cancels |
| — | Now playing tile | Never leaves Home; shows what played last when nothing is playing |
| — | Tile tap | A list tile opens its list; its shuffle variant plays |
| — | Tile rules | A list tile is a smart playlist; Most played's window is its smart playlist's |
| — | Home layout storage | On Cosine, per account and per kind of device; offline changes go with the next sync |
| — | Adding tiles | Generated tiles and smart playlists from Home's Add tile action |
| — | Tile sizes | 1×1 the cover or count; 2×2 adds the name; 4×2 a second line |
| — | Tiles not yet possible | Resume, Jam and Favourites aren't offered until their features exist |
| — | Tile cycling | Every 8 to 10 seconds, staggered, only while Home is on screen; with animations removed, content changes without moving |
| — | Tile motion | Home is the one place motion is for liveliness; Phase's colour and scrim rules still hold inside a tile |

---

## 8. Proposed defaults for the remaining questions

Rather than another six rounds of questions, here are proposed answers for
everything still open. Each is what I'd choose and why. Override any of them and
I'll fold the correction in; silence means they go into the body of the spec as
decided.

### Ingest

| # | Question | Proposal |
|---|---|---|
| Q12 | Same URL fetched twice | Cosine records the source URL of every Object. A second fetch of a known URL is refused with "you already have this", unless you explicitly ask for another version, or its file has been deleted from the Store. Stops accidental duplicates without blocking deliberate ones. |
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
in §8 stands except the cover-art scope, which was overturned in §3.2a. The
last one asked, whether a tile's cover changes by a fade or a cut, was answered
wider than asked: anything inside a tile may move (§6.4).

