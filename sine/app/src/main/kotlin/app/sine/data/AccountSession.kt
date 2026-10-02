package app.sine.data

import android.content.Context
import android.net.Uri
import app.sine.core.account.Account
import app.sine.core.cosine.CosineClient
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadLayout
import app.sine.core.download.OfflineLibrary
import app.sine.core.model.AlbumDetail
import app.sine.core.model.ArtistDetail
import app.sine.core.model.Track
import app.sine.core.play.PlayLog
import app.sine.core.play.PlaySource
import app.sine.core.subsonic.AlbumListType
import app.sine.core.subsonic.SubsonicClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    suspend fun album(id: String): Sourced<AlbumDetail> = fetch(
        offline = { lib ->
            val album = lib.albums().firstOrNull { it.id == id } ?: throw NotAvailableOffline()
            AlbumDetail(album, lib.albumTracks(id))
        },
        remote = { it.album(id) },
    )

    suspend fun artist(id: String): Sourced<ArtistDetail> = fetch(
        offline = { lib ->
            val artist = lib.artists().firstOrNull { it.id == id } ?: throw NotAvailableOffline()
            ArtistDetail(artist, lib.albums(id))
        },
        remote = { it.artist(id) },
    )

    suspend fun allAlbums() = fetch(
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
