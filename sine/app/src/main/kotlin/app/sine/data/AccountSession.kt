package app.sine.data

import android.content.Context
import android.net.Uri
import app.sine.core.account.Account
import app.sine.core.cosine.CosineClient
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadLayout
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.SmartPlaylist
import app.sine.core.download.OfflineLibrary
import app.sine.core.mirror.LibraryMirror
import app.sine.core.mirror.MirrorLibrary
import app.sine.core.model.Album
import app.sine.core.model.AlbumDetail
import app.sine.core.model.Artist
import app.sine.core.model.ArtistDetail
import app.sine.core.model.Playlist
import app.sine.core.model.PlaylistDetail
import app.sine.core.model.Track
import app.sine.core.play.PlayLog
import app.sine.core.play.PlaySource
import app.sine.core.server.Capability
import app.sine.core.subsonic.AlbumListType
import app.sine.core.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** A value and where it came from. [fromDownloads] means the server was not asked or did not answer. */
data class Sourced<T>(val value: T, val fromDownloads: Boolean)

class NotAvailableOffline : Exception("Not available offline.")

/**
 * Everything belonging to one account: its client, downloads, play log and
 * cached artwork. Each account is fully separate (§2.1).
 */
class AccountSession(
    context: Context,
    initial: Account,
    val http: OkHttpClient,
    private val network: NetworkMonitor,
) {
    private val _account = MutableStateFlow(initial)
    /** The account as currently known; its capabilities can change while the app runs. */
    val accountFlow: StateFlow<Account> = _account.asStateFlow()
    val account: Account get() = _account.value

    val dir = File(context.filesDir, "accounts/${initial.id}").apply { mkdirs() }
    val client = SubsonicClient(initial.serverUrl, initial.credentials, http)
    /** Cosine's native protocol; null for a plain Subsonic server (compat mode). */
    val cosine: CosineClient? =
        if (initial.isCosine) CosineClient(initial.serverUrl, initial.credentials, http) else null
    val downloads = DownloadIndex(File(dir, "downloads.json"))
    val plays = PlayLog(File(dir, "plays.json"))
    private val coversDir = File(dir, "covers")

    /**
     * Cosine's library kept on the phone (G2, §5.2). A plain Subsonic server
     * keeps no change log, so compat mode has none and asks per screen (§2.1).
     */
    private val mirror: LibraryMirror? = if (initial.isCosine) LibraryMirror(File(dir, "library.json")) else null
    private val syncing = Mutex()
    private val noMirror = MutableStateFlow<MirrorLibrary?>(null)

    /** The mirrored library while it's in use, else null; a new value whenever a sync changes it. */
    val mirrored: StateFlow<MirrorLibrary?> = mirror?.library ?: noMirror

    /** The mirror, once a sync has filled it, on a server that keeps one. */
    private fun library(): MirrorLibrary? =
        mirror?.takeIf { it.synced && can(Capability.DELTA_SYNC) }?.library?.value

    private val _reachable = MutableStateFlow(true)
    /** False after a request failed with the network up: the server itself is down. */
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()
    @Volatile private var lastFailureAt = 0L

    val isOnline: Boolean get() = network.state.value.connected && _reachable.value

    /** Native-only features are hidden entirely unless the server declares them (§2.1). */
    fun can(capability: String) = cosine != null && capability in account.kind.capabilities

    internal fun replaceAccount(updated: Account) {
        require(updated.id == account.id)
        _account.value = updated
    }
    fun offline() = OfflineLibrary(downloads.tracks.value.values)

    /**
     * Ask the server; fall back to downloads when it cannot be reached. While the
     * server has failed recently, go straight to downloads rather than waiting on
     * a timeout per screen.
     */
    suspend fun <T> fetch(offline: (OfflineLibrary) -> T, remote: suspend (SubsonicClient) -> T): Sourced<T> {
        val recentlyFailed = !_reachable.value && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_MS
        if (!network.state.value.connected || recentlyFailed) return Sourced(offline(offline()), true)
        return try {
            val v = remote(client)
            _reachable.value = true
            Sourced(v, false)
        } catch (e: IOException) {
            _reachable.value = false
            lastFailureAt = System.currentTimeMillis()
            Sourced(offline(offline()), true)
        }
    }

    fun forceOnlineRetry() {
        lastFailureAt = 0
    }

    /**
     * Brings the mirror up to date with what changed on the server. Quietly
     * does nothing offline, on a server without a mirror, or while another
     * sync is under way.
     */
    suspend fun sync() {
        val m = mirror ?: return
        val c = cosine ?: return
        if (!can(Capability.DELTA_SYNC) || !network.state.value.connected || syncing.isLocked) return
        syncing.withLock {
            try {
                val changes = c.sync(m.cursor)
                withContext(Dispatchers.IO) { m.apply(changes) }
                _reachable.value = true
            } catch (e: IOException) {
                _reachable.value = false
                lastFailureAt = System.currentTimeMillis()
            } catch (e: CosineException) {
                // Signed out or refused: the copy stays as it is.
            }
        }
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Syncs without waiting: after an add or a correction, so it shows at once. */
    fun syncSoon() {
        background.launch { sync() }
    }

    /** A pull to refresh: ask the server again now, and catch the mirror up. */
    suspend fun refresh() {
        forceOnlineRetry()
        sync()
    }

    /** The Library's artists: from the mirror when there is one (instant, and all of them offline). */
    suspend fun artists(): Sourced<List<Artist>> =
        library()?.let { Sourced(it.artists(), false) } ?: fetch(offline = { it.artists() }, remote = { it.artists() })

    /**
     * A release's tracks when they're known here without asking: the
     * mirror's, or offline the downloaded ones. Null means ask the server.
     */
    fun knownTracks(album: Album, fromDownloads: Boolean): List<Track>? =
        library()?.album(album.id)?.tracks ?: if (fromDownloads) offline().albumTracks(album.id) else null

    /**
     * Takes tracks out of this account's library (§4.1) and catches the
     * mirror up, so they're gone from every screen. Returns how many were in it.
     */
    suspend fun remove(trackIds: List<String>): Int {
        val c = cosine ?: throw CosineException(400, "Removing songs needs a Cosine server.")
        val n = c.remove(trackIds)
        sync()
        return n
    }

    /** Searches the mirror, or null when there is none and the server must be asked. */
    fun searchMirror(query: String) = library()?.search(query)

    suspend fun album(id: String): Sourced<AlbumDetail> = library()?.album(id)?.let { Sourced(it, false) } ?: fetch(
        offline = { lib ->
            val album = lib.albums().firstOrNull { it.id == id } ?: throw NotAvailableOffline()
            AlbumDetail(album, lib.albumTracks(id))
        },
        remote = { it.album(id) },
    )

    suspend fun artist(id: String): Sourced<ArtistDetail> = library()?.artist(id)?.let { Sourced(it, false) } ?: fetch(
        offline = { lib ->
            val artist = lib.artists().firstOrNull { it.id == id } ?: throw NotAvailableOffline()
            ArtistDetail(artist, lib.albums(id))
        },
        remote = { it.artist(id) },
    )

    suspend fun allAlbums(): Sourced<List<Album>> = library()?.let { Sourced(it.albums(), false) } ?: fetch(
        offline = { it.albums() },
        remote = { client ->
            val page = 500
            buildList {
                var offset = 0
                while (true) {
                    val batch = client.albums(AlbumListType.ALPHABETICAL_BY_NAME, page, offset)
                    addAll(batch)
                    if (batch.size < page) break
                    offset += page
                }
            }.distinctBy { it.id } // pages can overlap if the library changes mid-load; lists need unique keys
        },
    )

    // ------------------------------------------------------------ playlists (§2.4)

    private val _playlistsChanged = MutableStateFlow(0)

    /** Counts playlist changes made here, so the screens showing playlists ask again. */
    val playlistsChanged: StateFlow<Int> = _playlistsChanged.asStateFlow()

    /** Playlists can be made and edited: on any Subsonic server, on Cosine once it says so. */
    val canEditPlaylists: Boolean get() = cosine == null || can(Capability.PLAYLISTS)

    /** Smart playlists' rules can be made and edited. */
    val canEditSmart: Boolean get() = can(Capability.SMART_PLAYLISTS)

    private suspend fun <T> changing(block: suspend () -> T): T = block().also { _playlistsChanged.value++ }

    /** Playlists need the server: there is no copy of them here yet (§5.2). */
    suspend fun playlists(): Sourced<List<Playlist>> =
        fetch(offline = { throw NotAvailableOffline() }, remote = { it.playlists() })

    suspend fun playlist(id: String): Sourced<PlaylistDetail> =
        fetch(offline = { throw NotAvailableOffline() }, remote = { it.playlist(id) })

    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String? =
        changing { client.createPlaylist(name, trackIds) }

    suspend fun addToPlaylist(id: String, trackIds: List<String>) = changing { client.updatePlaylist(id, add = trackIds) }

    /** Takes songs out of a playlist by their positions in it, as listed. */
    suspend fun removeFromPlaylist(id: String, positions: List<Int>) =
        changing { client.updatePlaylist(id, removeAt = positions) }

    suspend fun reorderPlaylist(id: String, trackIds: List<String>) = changing { client.replacePlaylist(id, trackIds) }

    suspend fun renamePlaylist(id: String, name: String) = changing { client.updatePlaylist(id, name = name) }

    suspend fun deletePlaylist(id: String) = changing { client.deletePlaylist(id) }

    suspend fun smartPlaylists(): List<SmartPlaylist> = cosine?.smartPlaylists() ?: emptyList()

    suspend fun saveSmart(playlist: SmartPlaylist): SmartPlaylist {
        val c = cosine ?: throw CosineException(400, "Smart playlists need a Cosine server.")
        return changing { c.saveSmart(playlist) }
    }

    fun playSource(track: Track): PlaySource =
        PlaySource.resolve(downloads[track.id], isOnline) { client.streamUrl(track.id) }

    fun coverFile(coverArt: String) = File(coversDir, DownloadLayout.sanitize(coverArt) + ".img")

    /** Local cover first, so artwork works offline (G1). */
    fun artwork(coverArt: String?, size: Int = 600): Any? {
        if (coverArt == null) return null
        val local = coverFile(coverArt)
        if (local.exists()) return local
        return if (isOnline) client.coverArtUrl(coverArt, size) else null
    }

    fun artworkUri(coverArt: String?): Uri? = when (val a = artwork(coverArt)) {
        is File -> Uri.fromFile(a)
        is String -> Uri.parse(a)
        else -> null
    }

    suspend fun flushPlays() {
        if (!isOnline) return
        plays.flush { client.scrobble(it.trackId, it.playedAt) }
    }

    companion object {
        private const val RETRY_AFTER_MS = 30_000L
    }
}
