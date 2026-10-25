package app.sine.core.mirror

import app.sine.core.download.OfflineLibrary
import app.sine.core.model.Album
import app.sine.core.model.AlbumDetail
import app.sine.core.model.Artist
import app.sine.core.model.ArtistDetail
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
 * tracks to drop. Albums and artists are sent without counts.
 */
@Serializable
data class SyncChanges(
    val cursor: Long,
    val full: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val removedTracks: List<String> = emptyList(),
)

@Serializable
internal data class MirrorState(
    val synced: Boolean = false,
    val cursor: Long = 0,
    val artists: Map<String, Artist> = emptyMap(),
    val albums: Map<String, Album> = emptyMap(),
    val tracks: Map<String, Track> = emptyMap(),
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

    @Synchronized
    fun apply(c: SyncChanges) {
        val base = if (c.full) MirrorState() else _state.value
        val removed = c.removedTracks.toSet()
        val tracks = base.tracks.filterKeys { it !in removed } + c.tracks.associateBy { it.id }
        // An album with no tracks left is gone; so is an artist with no albums.
        val withTracks = tracks.values.mapNotNullTo(HashSet()) { it.albumId }
        val albums = (base.albums + c.albums.associateBy { it.id }).filterKeys { it in withTracks }
        val withAlbums = albums.values.mapNotNullTo(HashSet()) { it.artistId }
        val artists = (base.artists + c.artists.associateBy { it.id }).filterKeys { it in withAlbums }
        val next = MirrorState(synced = true, cursor = c.cursor, artists = artists, albums = albums, tracks = tracks)
        if (next == _state.value) return
        store.write(next)
        _state.value = next
        _library.value = MirrorLibrary.of(next)
    }

    @Synchronized
    fun clear() {
        store.write(MirrorState())
        _state.value = MirrorState()
        _library.value = MirrorLibrary.of(_state.value)
    }
}

/** A read-only view of the mirrored library, with counts worked out here. */
class MirrorLibrary private constructor(
    private val artistsById: Map<String, Artist>,
    private val albumsById: Map<String, Album>,
    private val tracks: List<Track>,
) {
    private val tracksByAlbum = tracks.groupBy { it.albumId }

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

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

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
        fun of(s: MirrorState) = MirrorLibrary(s.artists, s.albums, s.tracks.values.toList())
    }
}
