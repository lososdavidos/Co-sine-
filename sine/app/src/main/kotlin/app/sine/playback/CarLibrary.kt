package app.sine.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.sine.core.car.CarNode
import app.sine.core.car.CarPlay
import app.sine.core.car.CarSource
import app.sine.core.car.CarTree
import app.sine.core.model.Track
import app.sine.data.AccountSession
import app.sine.graph
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The car's tree over an account: the mirror and the downloads first (§5.2a). */
private class SessionSource(private val s: AccountSession) : CarSource {
    override fun downloaded() = s.offline()
    override suspend fun playlists() = s.playlists().value
    override suspend fun playlist(id: String) = s.playlist(id).value.tracks
    override suspend fun artists() = s.artists().value
    override suspend fun artist(id: String) = s.artist(id).value.albums
    override suspend fun albums() = s.allAlbums().value
    override suspend fun album(id: String) = s.album(id).value.tracks
    override suspend fun track(id: String) = runCatching { s.track(id).value }.getOrNull()
    override suspend fun search(query: String) =
        s.searchMirror(query) ?: s.fetch(offline = { it.search(query) }, remote = { it.search(query) }).value
}

/**
 * The player's session, as the car and everything else sees it: Sine's
 * own screens restore their songs' addresses here; the car browses the
 * library (§5.2a), plays what's picked with its album or playlist around
 * it, and asks by voice; and a headset or the car picks up what played
 * last, after the app was gone.
 */
class CarLibrary(private val context: Context, private val scope: CoroutineScope) : MediaLibrarySession.Callback {
    private val graph get() = context.graph

    private fun session(): AccountSession? = graph.activeSession.value

    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch(Dispatchers.IO) {
            try {
                f.set(block())
            } catch (e: CancellationException) {
                f.cancel(false)
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        return f
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = folder(CarNode.Folder(CarTree.ROOT, "Sine"), null, browser)
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
            putBoolean(SEARCH_SUPPORTED, true)
        }
        return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
        val s = session() ?: return@later LibraryResult.ofItemList(ImmutableList.of(), params)
        val nodes = runCatching { CarTree(SessionSource(s)).children(parentId) }.getOrElse { emptyList() }
        LibraryResult.ofItemList(paged(nodes, page, pageSize).map { item(it, s, browser) }, params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = later {
        val s = session() ?: return@later LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val node = runCatching { CarTree(SessionSource(s)).item(mediaId) }.getOrNull()
        if (node == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item(node, s, browser), null)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = later {
        val s = session()
        val n = if (s == null) 0 else runCatching { CarTree(SessionSource(s)).searchResults(query).size }.getOrDefault(0)
        session.notifySearchResultChanged(browser, query, n, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
        val s = session() ?: return@later LibraryResult.ofItemList(ImmutableList.of(), params)
        val nodes = runCatching { CarTree(SessionSource(s)).searchResults(query) }.getOrElse { emptyList() }
        LibraryResult.ofItemList(paged(nodes, page, pageSize).map { item(it, s, browser) }, params)
    }

    /**
     * Sine's own screens send each song as a sine:// reference, since the
     * session boundary strips its address; anything else (the car, a voice ask)
     * sends an id from the tree, or words to look for.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        if (mediaItems.all { it.requestMetadata.mediaUri != null }) return Futures.immediateFuture(mediaItems.mapNotNull(::restored).toMutableList())
        return later {
            mediaItems.flatMap { item ->
                if (item.requestMetadata.mediaUri != null) listOfNotNull(restored(item))
                else resolve(item)?.let { (s, play) -> items(s, play.tracks) }.orEmpty()
            }.toMutableList()
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (mediaItems.all { it.requestMetadata.mediaUri != null }) {
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems.mapNotNull(::restored), startIndex, startPositionMs))
        }
        // From the car: one id, or words; what it stands for plays, from the song picked.
        return later {
            val (s, play) = mediaItems.singleOrNull()?.let { resolve(it) } ?: throw UnsupportedOperationException("Nothing to play.")
            val start = play.tracks.getOrNull(play.start)?.id
            val items = items(s, play.tracks)
            if (items.isEmpty()) throw UnsupportedOperationException("Not on this phone.")
            graph.player.playingFrom(s.account.id)
            MediaSession.MediaItemsWithStartPosition(
                items, items.indexOfFirst { it.mediaId == start }.coerceAtLeast(0),
                if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
            )
        }
    }

    /**
     * A headset's play button, or the car starting, with Sine not running:
     * what played last picks up where it got to.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = later {
        val last = graph.player.last.value ?: throw UnsupportedOperationException("Nothing played yet.")
        val s = graph.sessionById(last.account) ?: throw UnsupportedOperationException("That account is gone.")
        val track = s.track(last.trackId).value
        val item = s.mediaItem(track) ?: throw UnsupportedOperationException("Not on this phone.")
        graph.player.playingFrom(s.account.id)
        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, last.positionMs)
    }

    private suspend fun resolve(item: MediaItem): Pair<AccountSession, CarPlay>? {
        val s = session() ?: return null
        val tree = CarTree(SessionSource(s))
        val query = item.requestMetadata.searchQuery
        val play = if (query != null) tree.search(query) else tree.play(item.mediaId)
        return play?.let { s to it }
    }

    private fun items(s: AccountSession, tracks: List<Track>) = tracks.mapNotNull { s.mediaItem(it) }

    /** A song from Sine's screens, played from where it is now; anything else (a made-up address) is refused. */
    private fun restored(item: MediaItem): MediaItem? {
        val (account, track, jam) = parseSongUri(item.requestMetadata.mediaUri) ?: return null
        val uri = graph.sessionById(account)?.playUri(track, jam) ?: return null
        return item.buildUpon().setUri(uri).setCustomCacheKey(cacheKey(account, track)).build()
    }

    private fun <T> paged(all: List<T>, page: Int, size: Int): List<T> {
        if (size <= 0 || size == Int.MAX_VALUE) return all
        val from = page.toLong() * size
        if (from >= all.size) return emptyList()
        return all.subList(from.toInt(), minOf(all.size, (from + size).toInt()))
    }

    private fun item(node: CarNode, s: AccountSession, browser: MediaSession.ControllerInfo): MediaItem = when (node) {
        is CarNode.Folder -> folder(node, s, browser)
        is CarNode.Song -> MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(node.track.title)
                    .setArtist(node.track.artist)
                    .setAlbumTitle(node.track.album)
                    .setDurationMs(node.track.durationSec * 1000L)
                    .setArtworkUri(cover(node.coverArt, s, browser))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    private fun folder(node: CarNode.Folder, s: AccountSession?, browser: MediaSession.ControllerInfo): MediaItem {
        val type = when (node.kind) {
            CarNode.Kind.Albums -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            CarNode.Kind.Artists -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            CarNode.Kind.Playlists -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            CarNode.Kind.Album -> MediaMetadata.MEDIA_TYPE_ALBUM
            CarNode.Kind.Artist -> MediaMetadata.MEDIA_TYPE_ARTIST
            CarNode.Kind.Playlist -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            CarNode.Kind.Folder -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        // Lists of things to open show as a grid of covers; what's in one, as a list.
        val extras = Bundle().apply {
            when (node.kind) {
                CarNode.Kind.Playlists -> putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
                CarNode.Kind.Album, CarNode.Kind.Playlist -> putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
                else -> {}
            }
        }
        return MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(node.title)
                    .setSubtitle(node.subtitle)
                    .setArtist(node.subtitle)
                    .setArtworkUri(s?.let { cover(node.coverArt, it, browser) })
                    // Shuffle all only plays; an album or a playlist opens, and plays whole from its tile.
                    .setIsBrowsable(node.id != CarTree.SHUFFLE_OFFLINE)
                    .setIsPlayable(node.playable)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** A cover's content address, which the car asking is let read (ArtworkProvider). */
    private fun cover(coverArt: String?, s: AccountSession, browser: MediaSession.ControllerInfo): android.net.Uri? {
        if (coverArt == null) return null
        val uri = ArtworkProvider.uri(context, s.account.id, coverArt)
        runCatching { context.grantUriPermission(browser.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return uri
    }

    private companion object {
        // The car's layout hints, as Android Auto reads them.
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val STYLE_LIST = 1
        const val STYLE_GRID = 2
    }
}
