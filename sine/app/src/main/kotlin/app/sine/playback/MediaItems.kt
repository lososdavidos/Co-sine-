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
    val uri = when (val source = if (jam == null) playSource(track) else jamSource(track, jam)) {
        is PlaySource.Local -> source.location
        is PlaySource.Remote -> source.url
        PlaySource.Unavailable -> return null
    }
    return MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uri)
        // Kept under the song's id, so a changed stream address still finds it (StreamCache).
        .setCustomCacheKey(track.id)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
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
