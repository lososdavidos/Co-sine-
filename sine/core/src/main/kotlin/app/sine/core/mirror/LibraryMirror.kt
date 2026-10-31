package app.sine.core.mirror

import app.sine.core.cosine.SmartPlaylist
import app.sine.core.download.OfflineLibrary
import app.sine.core.model.Album
import app.sine.core.model.AlbumDetail
import app.sine.core.model.Artist
import app.sine.core.model.ArtistDetail
import app.sine.core.model.Playlist
import app.sine.core.model.PlaylistDetail
import app.sine.core.model.SearchResults
import app.sine.core.model.Track
import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One answer from Cosine's sync endpoint (§5.2): with [full], the whole
 * library, to replace what's here; otherwise what to add or update and the
 * tracks to drop. Albums and artists are sent without counts. Playlists
 * and invitations follow the same rule; smart playlists come whole, or
 * not at all ([smart] null) when the ones here, as of [smartTag], are current.
 */
@Serializable
data class SyncChanges(
    val cursor: Long,
    val full: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val removedTracks: List<String> = emptyList(),
    val playlists: List<MirrorPlaylist> = emptyList(),
    val invites: List<PlaylistInvite> = emptyList(),
    val removedPlaylists: List<String> = emptyList(),
    val smart: List<MirrorSmart>? = null,
    val smartTag: String? = null,
)

@Serializable
internal data class MirrorState(
    val synced: Boolean = false,
    val cursor: Long = 0,
    val artists: Map<String, Artist> = emptyMap(),
    val albums: Map<String, Album> = emptyMap(),
    val tracks: Map<String, Track> = emptyMap(),
    val playlists: Map<String, MirrorPlaylist> = emptyMap(),
    val invites: Map<String, PlaylistInvite> = emptyMap(),
    val smart: List<MirrorSmart> = emptyList(),
    val smartTag: String = "",
    /** Playlist edits made here that Cosine hasn't got yet, oldest first. */
    val pending: List<PlaylistOp> = emptyList(),
    /** Those of [pending] Cosine has taken: dropped at the next sync, which brings their result. */
    val sent: Set<String> = emptySet(),
    /** Playlists made here, by the id they went by, and the id Cosine gave them. */
    val aliases: Map<String, String> = emptyMap(),
)

/**
 * The account's library, kept on the phone (G2): browsing and search read it
 * instead of asking the server per screen, and it is all still there offline
 * (§6.14). One per Cosine account; saved as one file, written atomically.
 */
class LibraryMirror(file: File) {
    private val store = JsonFile(file, MirrorState.serializer()) { MirrorState() }
    private val _state = MutableStateFlow(store.read())
    private val _library = MutableStateFlow(MirrorLibrary.of(_state.value))

    /** The library as of the last sync; a new value each time one changes it. */
    val library: StateFlow<MirrorLibrary> = _library.asStateFlow()

    /** The server's change number this copy is current to; 0 asks for everything. */
    val cursor: Long get() = _state.value.cursor

    /** Whether a sync has ever filled it. */
    val synced: Boolean get() = _state.value.synced

    /** Which smart playlists this copy has, to tell Cosine on the next sync. */
    val smartTag: String get() = _state.value.smartTag

    /** Playlist edits made here and not yet sent to Cosine, oldest first. */
    val pending: List<PlaylistOp> get() = _state.value.let { s -> s.pending.filterNot { it.id in s.sent } }

    /**
     * Applies a sync. Edits Cosine has taken are dropped now: this sync
     * started after it took them, so it brings what they did.
     */
    @Synchronized
    fun apply(c: SyncChanges) {
        val now = _state.value
        val base = if (c.full) MirrorState() else now
        val removed = c.removedTracks.toSet()
        val tracks = base.tracks.filterKeys { it !in removed } + c.tracks.associateBy { it.id }
        // An album with no tracks left is gone; so is an artist with no albums.
        val withTracks = tracks.values.mapNotNullTo(HashSet()) { it.albumId }
        val albums = (base.albums + c.albums.associateBy { it.id }).filterKeys { it in withTracks }
        val withAlbums = albums.values.mapNotNullTo(HashSet()) { it.artistId }
        val artists = (base.artists + c.artists.associateBy { it.id }).filterKeys { it in withAlbums }
        val gone = c.removedPlaylists.toSet()
        val playlists = base.playlists.filterKeys { it !in gone } + c.playlists.associateBy { it.id }
        val invites = (base.invites.filterKeys { it !in gone && it !in playlists } + c.invites.associateBy { it.id })
        val next = MirrorState(
            synced = true, cursor = c.cursor, artists = artists, albums = albums, tracks = tracks,
            playlists = playlists, invites = invites,
            smart = c.smart ?: now.smart, smartTag = c.smartTag ?: now.smartTag,
            // Edits still to send to a playlist made here name it by Cosine's id now.
            pending = now.pending.filterNot { it.id in now.sent }.map { op ->
                op.new?.let { now.aliases[PlaylistOps.localId(it)] }?.let { op.copy(new = null, playlist = it) } ?: op
            },
            aliases = now.aliases.filterValues { it in playlists },
        )
        save(next)
    }

    /** Records a playlist edit made here, shown at once and sent on the next sync. */
    @Synchronized
    fun edit(ops: List<PlaylistOp>) {
        if (ops.isEmpty()) return
        val s = _state.value
        save(s.copy(pending = s.pending + ops))
    }

    /** Cosine took these edits; a playlist made here now has its own id. */
    @Synchronized
    fun sent(results: List<PlaylistOpResult>) {
        val s = _state.value
        val made = s.pending.filter { it.op == PlaylistOps.CREATE }.map { it.id }.toSet()
        val aliases = results.filter { it.id in made && it.playlist != null }
            .associate { PlaylistOps.localId(it.id) to it.playlist!! }
        save(s.copy(sent = s.sent + results.map { it.id }, aliases = s.aliases + aliases))
    }

    @Synchronized
    fun clear() = save(MirrorState())

    private fun save(next: MirrorState) {
        if (next == _state.value) return
        store.write(next)
        _state.value = next
        _library.value = MirrorLibrary.of(next)
    }
}

/** A read-only view of the mirrored library, with counts worked out here. */
class MirrorLibrary private constructor(
    private val artistsById: Map<String, Artist>,
    private val albumsById: Map<String, Album>,
    private val tracks: List<Track>,
    private val manual: Map<String, MirrorPlaylist>,
    private val smart: List<MirrorSmart>,
    private val invites: List<PlaylistInvite>,
    private val aliases: Map<String, String>,
) {
    private val tracksByAlbum = tracks.groupBy { it.albumId }
    private val tracksById = tracks.associateBy { it.id }

    private fun Album.counted(): Album {
        val ts = tracksByAlbum[id].orEmpty()
        return copy(trackCount = ts.size, durationSec = ts.sumOf { it.durationSec })
    }

    private val albumList: List<Album> = albumsById.values.map { it.counted() }
    private val albumsByArtist = albumList.groupBy { it.artistId }

    val isEmpty: Boolean get() = tracks.isEmpty()

    fun artists(): List<Artist> =
        artistsById.values
            .map { it.copy(albumCount = albumsByArtist[it.id].orEmpty().size) }
            .sortedBy { it.name.lowercase() }

    /** By name, as the Library lists them. */
    fun albums(): List<Album> = albumList.sortedWith(compareBy({ it.name.lowercase() }, { it.artist.lowercase() }))

    /** Most recently added to this account first. */
    fun recentlyAdded(limit: Int): List<Album> = albumList.sortedByDescending { it.created.orEmpty() }.take(limit)

    /** An artist and their releases, oldest first: a body of work in order (§6.9). */
    fun artist(id: String): ArtistDetail? {
        val artist = artistsById[id] ?: return null
        val albums = albumsByArtist[id].orEmpty().sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.name.lowercase() }))
        return ArtistDetail(artist.copy(albumCount = albums.size), albums)
    }

    fun album(id: String): AlbumDetail? {
        val album = albumsById[id] ?: return null
        return AlbumDetail(album.counted(), tracksByAlbum[id].orEmpty().sortedWith(OfflineLibrary.trackOrder))
    }

    fun track(id: String): Track? = tracksById[id]

    /** Every song, in no order: for shuffling the lot. */
    fun tracks(): List<Track> = tracks

    // ------------------------------------------------------------ playlists (§2.4)

    /** A playlist's id now: one made here goes by Cosine's once the sync has brought it. */
    fun resolve(id: String): String = aliases[id]?.takeIf { it in manual } ?: id

    /** The songs of [ids] this library has, in order: the shown ones (§4.1). */
    private fun shown(ids: List<String>) = ids.mapNotNull { tracksById[it] }

    private fun describe(p: MirrorPlaylist): Playlist {
        val ts = shown(p.tracks)
        return Playlist(
            id = p.id, name = p.name, trackCount = ts.size, durationSec = ts.sumOf { it.durationSec },
            coverArt = ts.firstNotNullOfOrNull { it.coverArt }, owner = p.owner.ifEmpty { null },
            mine = p.mine, sharedWith = p.sharedWith, invited = p.invited,
        )
    }

    private fun describe(s: MirrorSmart): Playlist {
        val ts = shown(s.tracks)
        return Playlist(
            id = s.id, name = s.name, trackCount = ts.size, durationSec = ts.sumOf { it.durationSec },
            coverArt = ts.firstNotNullOfOrNull { it.coverArt }, comment = s.description, readonly = true,
        )
    }

    /** The songs you liked, the latest first: Liked songs (§2.4). */
    fun liked(): List<Track> = tracks.filter { it.liked }.sortedWith(Playlist.likedOrder)

    /** Liked songs, then your playlists and those shared with you, by name, then the smart ones. */
    fun playlists(): List<Playlist> =
        listOf(Playlist.liked(liked())) +
            manual.values.map { describe(it) }.sortedBy { it.name.lowercase() } + smart.map { describe(it) }

    fun playlist(id: String): PlaylistDetail? {
        if (id == Playlist.LIKED) return liked().let { PlaylistDetail(Playlist.liked(it), it) }
        val key = resolve(id)
        manual[key]?.let { return PlaylistDetail(describe(it), shown(it.tracks)) }
        return smart.firstOrNull { it.id == key }?.let { PlaylistDetail(describe(it), shown(it.tracks)) }
    }

    /** Every entry of a manual playlist, shown or not, as edits count them. */
    fun entries(id: String): List<String>? = manual[resolve(id)]?.tracks

    /** Where each shown song of a manual playlist is among all its entries. */
    fun shownEntries(id: String): List<Int>? =
        entries(id)?.let { all -> all.indices.filter { all[it] in tracksById } }

    /** Playlists shared with you, waiting for an answer (§2.3). */
    fun invites(): List<PlaylistInvite> = invites

    /** A smart playlist's rule, to edit. */
    fun smartRule(id: String): SmartPlaylist? = smart.firstOrNull { it.id == id }?.toRule()

    /** The account's smart playlists' rules, in Cosine's order. */
    fun smartPlaylists(): List<SmartPlaylist> = smart.map { it.toRule() }

    /**
     * Search as you type (§6.8): names first, and a track or album also when
     * its artist matches. SearchRanking orders the hits.
     */
    fun search(query: String): SearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        return SearchResults(
            artists().filter { q in it.name.lowercase() },
            albums().filter { q in it.name.lowercase() || q in it.artist.lowercase() },
            tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() }
                .sortedWith(compareBy<Track>({ it.artist.lowercase() }, { it.album.lowercase() }).then(OfflineLibrary.trackOrder)),
        )
    }

    internal companion object {
        /** The library, and its playlists with the edits made here replayed on Cosine's. */
        fun of(s: MirrorState) = MirrorLibrary(
            s.artists, s.albums, s.tracks.values.toList(),
            s.pending.fold(s.playlists) { lists, op -> PlaylistOps.apply(lists, op) },
            s.smart, s.invites.values.sortedBy { it.name.lowercase() }, s.aliases,
        )
    }
}
