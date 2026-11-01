package app.sine.core.car

import app.sine.core.download.OfflineLibrary
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.SearchResults
import app.sine.core.model.Track

/**
 * Where the car's browse tree (§5.2a) gets its music: the library kept on
 * the phone first, since a car is where the network is worst. Each call
 * answers from the mirror or the downloads when it can, and asks the server
 * only when neither has it.
 */
interface CarSource {
    fun downloaded(): OfflineLibrary
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): List<Track>
    suspend fun artists(): List<Artist>
    suspend fun artist(id: String): List<Album>
    suspend fun albums(): List<Album>
    suspend fun album(id: String): List<Track>
    suspend fun track(id: String): Track?
    suspend fun search(query: String): SearchResults
}

/** One entry in the car's tree: a folder to open, or a song to play. */
sealed interface CarNode {
    val id: String
    val title: String
    val subtitle: String?
    val coverArt: String?

    data class Folder(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        override val coverArt: String? = null,
        val kind: Kind = Kind.Folder,
        /** Played whole from its tile, as well as opened. */
        val playable: Boolean = false,
    ) : CarNode

    data class Song(override val id: String, val track: Track) : CarNode {
        override val title get() = track.title
        override val subtitle get() = track.artist
        override val coverArt get() = track.coverArt
    }

    /** Lets the car pick a fitting layout for a folder's contents. */
    enum class Kind { Folder, Albums, Artists, Playlists, Album, Artist, Playlist }
}

/** What to play: the songs, and which of them first. */
data class CarPlay(val tracks: List<Track>, val start: Int = 0)

/**
 * The car's browse tree (§5.2a). Four tabs, the first the default: what's
 * on this phone, since that plays wherever the car is; then Playlists,
 * Artists and Albums, from the mirror. A song's id says where it was
 * picked, so playing it plays the album or playlist around it.
 */
class CarTree(private val source: CarSource) {
    fun root() = CarNode.Folder(ROOT, "Sine")

    /** The tabs: on this phone first, the default (§5.2a). */
    fun tabs() = listOf(
        CarNode.Folder(OFFLINE, "On this phone", kind = CarNode.Kind.Albums),
        CarNode.Folder(PLAYLISTS, "Playlists", kind = CarNode.Kind.Playlists),
        CarNode.Folder(ARTISTS, "Artists", kind = CarNode.Kind.Artists),
        CarNode.Folder(ALBUMS, "Albums", kind = CarNode.Kind.Albums),
    )

    /** What's in [id]; empty for a song or an id it doesn't know. */
    suspend fun children(id: String): List<CarNode> {
        if (id == ROOT) return tabs()
        if (id == OFFLINE) {
            val lib = source.downloaded()
            val albums = lib.albums()
            if (albums.isEmpty()) return emptyList()
            return listOf(CarNode.Folder(SHUFFLE_OFFLINE, "Shuffle all", "${lib.all().size} songs", playable = true)) +
                albums.map { folder(DL_ALBUM, it) }
        }
        if (id == PLAYLISTS) return source.playlists().map {
            CarNode.Folder(PLAYLIST + it.id, it.name, songs(it.trackCount), it.coverArt, CarNode.Kind.Playlist, playable = true)
        }
        if (id == ARTISTS) return source.artists().map {
            CarNode.Folder(ARTIST + it.id, it.name, null, it.coverArt, CarNode.Kind.Artist)
        }
        if (id == ALBUMS) return source.albums().map { folder(ALBUM, it) }
        val (prefix, rest) = split(id) ?: return emptyList()
        return when (prefix) {
            ARTIST -> source.artist(rest).map { folder(ALBUM, it) }
            ALBUM, DL_ALBUM, PLAYLIST -> tracks(prefix, rest).map { CarNode.Song(song(id, it.id), it) }
            else -> emptyList()
        }
    }

    /** One entry by its id, as the car asks for it again after a restart. */
    suspend fun item(id: String): CarNode? {
        if (id == ROOT) return root()
        tabs().firstOrNull { it.id == id }?.let { return it }
        if (id == SHUFFLE_OFFLINE) return CarNode.Folder(SHUFFLE_OFFLINE, "Shuffle all", playable = true)
        parseSong(id)?.let { (context, trackId) ->
            return tracks(context)?.firstOrNull { it.id == trackId }?.let { CarNode.Song(id, it) }
        }
        val (prefix, rest) = split(id) ?: return null
        return when (prefix) {
            ARTIST -> source.artists().firstOrNull { it.id == rest }?.let { CarNode.Folder(id, it.name, null, it.coverArt, CarNode.Kind.Artist) }
            PLAYLIST -> source.playlists().firstOrNull { it.id == rest }?.let { CarNode.Folder(id, it.name, songs(it.trackCount), it.coverArt, CarNode.Kind.Playlist, true) }
            ALBUM, DL_ALBUM -> CarNode.Folder(id, tracks(prefix, rest).firstOrNull()?.album ?: "", kind = CarNode.Kind.Album, playable = true)
            else -> null
        }
    }

    /**
     * What a tap plays: a song plays its album or playlist from it; an
     * album or playlist plays from the top; Shuffle all, every downloaded
     * song in a new order. Null when [id] isn't anything to play.
     */
    suspend fun play(id: String): CarPlay? {
        if (id == SHUFFLE_OFFLINE) return source.downloaded().all().shuffled().takeIf { it.isNotEmpty() }?.let { CarPlay(it) }
        parseSong(id)?.let { (context, trackId) ->
            val tracks = tracks(context) ?: return null
            val at = tracks.indexOfFirst { it.id == trackId }
            return if (at < 0) null else CarPlay(tracks, at)
        }
        return tracks(id)?.takeIf { it.isNotEmpty() }?.let { CarPlay(it) }
    }

    /**
     * A spoken "play …" (§5.2a): a song by that name plays with its album
     * around it; else an album, else an artist's songs; an empty query
     * plays what's on the phone, shuffled.
     */
    suspend fun search(query: String): CarPlay? {
        if (query.isBlank()) return play(SHUFFLE_OFFLINE)
        val found = source.search(query)
        found.tracks.firstOrNull()?.let { t ->
            val around = t.albumId?.let { runCatching { source.album(it) }.getOrNull() }.orEmpty()
            val at = around.indexOfFirst { it.id == t.id }
            return if (at >= 0) CarPlay(around, at) else CarPlay(found.tracks)
        }
        found.albums.firstOrNull()?.let { a -> return source.album(a.id).takeIf { it.isNotEmpty() }?.let { CarPlay(it) } }
        found.artists.firstOrNull()?.let { a ->
            val songs = source.artist(a.id).flatMap { source.album(it.id) }
            return songs.takeIf { it.isNotEmpty() }?.let { CarPlay(it.shuffled()) }
        }
        return null
    }

    /** Results as the car lists them: songs to play from where they are. */
    suspend fun searchResults(query: String): List<CarNode> {
        if (query.isBlank()) return emptyList()
        val found = source.search(query)
        return found.albums.map { folder(ALBUM, it) } +
            found.artists.map { CarNode.Folder(ARTIST + it.id, it.name, null, it.coverArt, CarNode.Kind.Artist) } +
            found.tracks.map { t -> CarNode.Song(song(t.albumId?.let { ALBUM + it } ?: (TRACK + t.id), t.id), t) }
    }

    private fun folder(prefix: String, a: Album) =
        CarNode.Folder(prefix + a.id, a.name, a.artist, a.coverArt, CarNode.Kind.Album, playable = true)

    private suspend fun tracks(context: String): List<Track>? {
        val (prefix, rest) = split(context) ?: return null
        return tracks(prefix, rest)
    }

    private suspend fun tracks(prefix: String, rest: String): List<Track> = when (prefix) {
        ALBUM -> source.album(rest)
        DL_ALBUM -> source.downloaded().albumTracks(rest)
        PLAYLIST -> source.playlist(rest)
        // A song found on its own, with no album to play it in.
        TRACK -> listOfNotNull(source.track(rest))
        else -> emptyList()
    }

    private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

    companion object {
        const val ROOT = "car"
        const val OFFLINE = "car/phone"
        const val PLAYLISTS = "car/playlists"
        const val ARTISTS = "car/artists"
        const val ALBUMS = "car/albums"
        const val SHUFFLE_OFFLINE = "car/shuffle"

        const val ALBUM = "album:"
        const val DL_ALBUM = "downloaded:"
        const val ARTIST = "artist:"
        const val PLAYLIST = "playlist:"
        const val TRACK = "track:"
        private const val SONG = "song|"

        /** A song picked in [context] (an album's, a playlist's id here). */
        fun song(context: String, trackId: String) = "$SONG$context|$trackId"

        /** A song's context and track, or null for anything else. */
        fun parseSong(id: String): Pair<String, String>? {
            if (!id.startsWith(SONG)) return null
            val rest = id.removePrefix(SONG)
            val bar = rest.lastIndexOf('|')
            if (bar <= 0 || bar == rest.lastIndex) return null
            return rest.substring(0, bar) to rest.substring(bar + 1)
        }

        private val PREFIXES = listOf(ALBUM, DL_ALBUM, ARTIST, PLAYLIST, TRACK)

        private fun split(id: String): Pair<String, String>? =
            PREFIXES.firstOrNull { id.startsWith(it) && id.length > it.length }?.let { it to id.removePrefix(it) }
    }
}
