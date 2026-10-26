package app.sine.data

import android.content.Context
import android.net.Uri
import app.sine.core.account.Account
import app.sine.core.cosine.CosineClient
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadLayout
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.LastFmApproval
import app.sine.core.cosine.Scrobbling
import app.sine.core.cosine.SmartPlaylist
import app.sine.core.download.OfflineLibrary
import app.sine.core.home.HomeStore
import app.sine.core.model.SearchHistory
import app.sine.core.home.Tile
import app.sine.core.mirror.LibraryMirror
import app.sine.core.mirror.MirrorLibrary
import app.sine.core.mirror.PlaylistInvite
import app.sine.core.mirror.PlaylistOp
import app.sine.core.mirror.PlaylistOps
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
import java.util.UUID

/** A value and where it came from. [fromDownloads] means the server was not asked or did not answer. */
data class Sourced<T>(val value: T, val fromDownloads: Boolean)

class NotAvailableOffline : Exception("Not available offline.")
class PlaylistGone : Exception("This playlist isn't here any more.")
class PlaylistChanged : Exception("The playlist changed meanwhile. Try again.")

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
    /** Home's arrangement (§6.4): kept here, and on Cosine when it keeps one. */
    val home = HomeStore(File(dir, "home.json"))
    /** What was opened from search, to open again (§6.8). Kept on the phone only. */
    val searchHistory = SearchHistory(File(dir, "search-history.json"))
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
     * sync is under way, unless [wait]ing for it: after a change on the
     * server, so it shows.
     */
    suspend fun sync(wait: Boolean = false) {
        val m = mirror ?: return
        val c = cosine ?: return
        if (!can(Capability.DELTA_SYNC) || !network.state.value.connected || (!wait && syncing.isLocked)) return
        syncing.withLock {
            try {
                // Playlist edits made here go first, so this sync brings back what they did.
                val ops = m.pending
                if (ops.isNotEmpty() && can(Capability.PLAYLIST_SYNC)) {
                    val results = c.playlistOps(ops)
                    withContext(Dispatchers.IO) { m.sent(results) }
                }
                val changes = c.sync(m.cursor, m.smartTag)
                withContext(Dispatchers.IO) { m.apply(changes) }
                if (can(Capability.HOME_LAYOUT)) syncHome(c)
                _reachable.value = true
            } catch (e: IOException) {
                _reachable.value = false
                lastFailureAt = System.currentTimeMillis()
            } catch (e: CosineException) {
                // Signed out or refused: the copy stays as it is.
            }
        }
    }

    /** Home goes to Cosine when it was changed here, and comes from it otherwise: another phone's, or an earlier install's. */
    private suspend fun syncHome(c: CosineClient) {
        val local = home.layout.value
        if (home.unsent && local != null) {
            c.saveHome(HOME_DEVICE, local)
            withContext(Dispatchers.IO) { home.sent(local) }
        } else {
            val remote = c.home(HOME_DEVICE)
            withContext(Dispatchers.IO) { home.received(remote) }
        }
    }

    /** Rearranges Home, and sends it to Cosine as soon as it can. */
    fun editHome(tiles: List<Tile>) {
        home.edit(tiles)
        syncSoon()
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

    /** Every song in the library, when the mirror has them; null means only the server knows. */
    fun libraryTracks(): List<Track>? = library()?.tracks()

    /** The account's smart playlists as the mirror has them; none in compat mode. */
    fun mirroredSmart(): List<SmartPlaylist> = library()?.smartPlaylists().orEmpty()

    /** One song: from the mirror, else the server's. */
    suspend fun track(id: String): Sourced<Track> = library()?.track(id)?.let { Sourced(it, false) } ?: fetch(
        offline = { lib -> lib.all().firstOrNull { it.id == id } ?: throw NotAvailableOffline() },
        remote = { it.track(id) },
    )

    /** Searches the mirror, or null when there is none and the server must be asked. */
    fun searchMirror(query: String) = library()?.search(query)

    /** Playlists whose name matches, from the mirror (§6.8). */
    fun searchPlaylists(query: String): List<Playlist> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return playlistLibrary()?.playlists()?.filter { q in it.name.lowercase() }.orEmpty()
    }

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

    /** Playlists can be shared with other accounts on this server (§2.3). */
    val canShare: Boolean get() = can(Capability.SHARED_PLAYLISTS)

    private suspend fun <T> changing(block: suspend () -> T): T = block().also { _playlistsChanged.value++ }

    /**
     * Playlists from the mirror, offline too, with edits made here shown at
     * once and sent by operation (§5.2), when Cosine keeps them in its sync.
     * Otherwise, as in compat mode, they need the server.
     */
    private fun playlistLibrary(): MirrorLibrary? = library()?.takeIf { can(Capability.PLAYLIST_SYNC) }

    /** Whether playlists can be changed with no connection: they're edited here and sent later. */
    val editsPlaylistsOffline: Boolean get() = playlistLibrary() != null

    suspend fun playlists(): Sourced<List<Playlist>> =
        playlistLibrary()?.let { Sourced(it.playlists(), false) }
            ?: fetch(offline = { throw NotAvailableOffline() }, remote = { it.playlists() })

    suspend fun playlist(id: String): Sourced<PlaylistDetail> {
        playlistLibrary()?.let { lib -> return Sourced(lib.playlist(id) ?: throw PlaylistGone(), false) }
        return fetch(offline = { throw NotAvailableOffline() }, remote = { it.playlist(id) })
    }

    /** A playlist's songs as the mirror has them; null when it isn't there. */
    fun mirroredPlaylistTracks(id: String): List<Track>? = playlistLibrary()?.playlist(id)?.tracks

    /** Edits to playlist [id] made here that Cosine doesn't have yet (§5.2). */
    fun unsentEdits(id: String): Int {
        val m = mirror ?: return 0
        val resolved = playlistLibrary()?.resolve(id) ?: id
        return m.pending.count { PlaylistOps.touches(it, resolved) }
    }

    /** Playlists other accounts shared with this one, waiting for an answer. */
    fun invites(): List<PlaylistInvite> = playlistLibrary()?.invites().orEmpty()

    private fun opId() = UUID.randomUUID().toString()

    /** Keeps edits in the mirror, where they show at once, and sends them now if it can, else on reconnect. */
    private fun record(ops: List<PlaylistOp>) {
        mirror?.edit(ops)
        _playlistsChanged.value++
        syncSoon()
    }

    private fun on(id: String, op: String): PlaylistOp =
        PlaylistOps.on(playlistLibrary()?.resolve(id) ?: id, opId(), op)

    /** Makes a playlist; returns its id. */
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String? {
        if (playlistLibrary() != null) {
            val op = PlaylistOps.create(opId(), name, trackIds)
            record(listOf(op))
            return PlaylistOps.localId(op.id)
        }
        return changing { client.createPlaylist(name, trackIds) }
    }

    suspend fun addToPlaylist(id: String, trackIds: List<String>) {
        if (playlistLibrary() != null) return record(listOf(on(id, PlaylistOps.ADD).copy(tracks = trackIds)))
        changing { client.updatePlaylist(id, add = trackIds) }
    }

    /** Takes songs out of a playlist by their [positions] in it, as listed: [listed]. */
    suspend fun removeFromPlaylist(id: String, positions: List<Int>, listed: List<Track>) {
        val lib = playlistLibrary() ?: return changing { client.updatePlaylist(id, removeAt = positions) }
        val entries = lib.entries(id) ?: throw PlaylistGone()
        val shown = lib.shownEntries(id).orEmpty()
        // Changed by a sync since it was listed: the positions could name other songs.
        if (shown.map { entries[it] } != listed.map { it.id }) throw PlaylistChanged()
        record(PlaylistOps.remove(lib.resolve(id), entries, positions.mapNotNull { shown.getOrNull(it) }, ::opId))
    }

    /** Puts a playlist's songs, [tracks] as listed, in a new [order]: positions in [tracks]. */
    suspend fun reorderPlaylist(id: String, tracks: List<Track>, order: List<Int>) {
        val lib = playlistLibrary() ?: return changing { client.replacePlaylist(id, order.map { tracks[it].id }) }
        val entries = lib.entries(id) ?: throw PlaylistGone()
        val shown = lib.shownEntries(id).orEmpty()
        if (shown.map { entries[it] } != tracks.map { it.id }) throw PlaylistChanged()
        record(PlaylistOps.reorder(lib.resolve(id), entries, shown, order, ::opId))
    }

    suspend fun renamePlaylist(id: String, name: String) {
        if (playlistLibrary() != null) return record(listOf(on(id, PlaylistOps.RENAME).copy(name = name)))
        changing { client.updatePlaylist(id, name = name) }
    }

    /** Deletes a playlist; for one shared with you, leaves it. */
    suspend fun deletePlaylist(id: String) {
        if (playlistLibrary() != null && !id.startsWith("sp-")) return record(listOf(on(id, PlaylistOps.DELETE)))
        changing { client.deletePlaylist(id) }
        if (id.startsWith("sp-")) sync(wait = true)
    }

    suspend fun smartPlaylists(): List<SmartPlaylist> = cosine?.smartPlaylists() ?: emptyList()

    /** A smart playlist's rule: from the mirror when it's there. */
    suspend fun smartRule(id: String): SmartPlaylist? =
        playlistLibrary()?.smartRule(id) ?: smartPlaylists().firstOrNull { it.id == id }

    suspend fun saveSmart(playlist: SmartPlaylist): SmartPlaylist {
        val c = cosine ?: throw CosineException(400, "Smart playlists need a Cosine server.")
        return changing { c.saveSmart(playlist) }.also { sync(wait = true) }
    }

    // ------------------------------------------------------------ sharing (§2.3)

    private fun native(): CosineClient = cosine ?: throw CosineException(400, "Sharing needs a Cosine server.")

    /** The other accounts here, to share with. Needs the server. */
    suspend fun accounts(): List<String> = native().accounts()

    /** Shares a playlist; a playlist made here and not yet sent is sent first. */
    suspend fun share(id: String, with: String) {
        native().share(sentId(id), with)
        sync(wait = true)
    }

    suspend fun unshare(id: String, name: String) {
        native().unshare(sentId(id), name)
        sync(wait = true)
    }

    /** Takes up a shared playlist; returns how many songs went into your library. */
    suspend fun accept(id: String): Int = native().accept(id).also { sync(wait = true) }

    suspend fun decline(id: String) {
        native().decline(id)
        sync(wait = true)
    }

    /** Cosine's id for a playlist, sending edits first if it was made here. */
    private suspend fun sentId(id: String): String {
        if (!PlaylistOps.isLocal(id)) return id
        sync(wait = true)
        return playlistLibrary()?.resolve(id)?.takeUnless { PlaylistOps.isLocal(it) } ?: throw NotAvailableOffline()
    }

    // ------------------------------------------------------------ scrobbling (§4A)

    suspend fun scrobbling(): Scrobbling = native().scrobbling()
    suspend fun startLastFm(): LastFmApproval = native().startLastFm()
    suspend fun finishLastFm(token: String): String = native().finishLastFm(token)
    suspend fun connectListenBrainz(token: String): String = native().connectListenBrainz(token)
    suspend fun disconnectScrobbler(service: String) = native().disconnectScrobbler(service)

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
        /** Sine on Android keeps the phone's Home; the desktop will keep its own (§6.15). */
        private const val HOME_DEVICE = "phone"
    }
}
