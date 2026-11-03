package app.sine.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.sine.core.model.Track
import app.sine.core.play.PlaySource
import app.sine.data.AccountSession
import app.sine.data.artworkUri

/**
 * A song as the player takes it: from the phone when it's downloaded or
 * kept, else streamed; in a jam, through the jam (§4C). Null when it can't
 * play right now: offline, and not on the phone.
 */
fun AccountSession.mediaItem(track: Track, jam: Long? = null): MediaItem? {
    val uri = playUri(track.id, jam) ?: return null
    return MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uri)
        // Kept under the account and the song, so a changed stream address still finds it (StreamCache).
        .setCustomCacheKey(cacheKey(account.id, track.id))
        // Every app connected to the session sees this: the song, never its address, which carries the login.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(songUri(account.id, track.id, jam)).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(artworkUri(track.coverArt))
                .setDurationMs(track.durationSec * 1000L)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
}

/** Where a song plays from now, or null when it can't. */
fun AccountSession.playUri(trackId: String, jam: Long?): String? = when (val source = playSource(trackId, jam)) {
    is PlaySource.Local -> source.location
    is PlaySource.Remote -> source.url
    PlaySource.Unavailable -> null
}

/** What a kept song is kept under: track ids repeat across servers. */
fun cacheKey(accountId: String, trackId: String) = "$accountId:$trackId"

/** A song as the session's other side sees it: sine://song/<account>/<track>?jam=<id>. */
fun songUri(accountId: String, trackId: String, jam: Long?): Uri =
    Uri.Builder().scheme("sine").authority("song").appendPath(accountId).appendPath(trackId)
        .apply { if (jam != null) appendQueryParameter("jam", jam.toString()) }
        .build()

/** A [songUri] read back: account, track and jam, or null for anything else. */
fun parseSongUri(uri: Uri?): Triple<String, String, Long?>? {
    if (uri?.scheme != "sine" || uri.authority != "song") return null
    val (account, track) = uri.pathSegments.takeIf { it.size == 2 } ?: return null
    return Triple(account, track, uri.getQueryParameter("jam")?.toLongOrNull())
}
