package app.sine.core.play

import app.sine.core.download.DownloadedTrack

/** Where a track plays from right now. Local always wins: playback never depends on the network (G1). */
sealed interface PlaySource {
    data class Local(val location: String) : PlaySource
    data class Remote(val url: String) : PlaySource
    data object Unavailable : PlaySource

    companion object {
        /**
         * A download first; else the stream, online, or offline when the
         * stream was kept whole on the phone as it played ([cached]): the
         * player then reads it from there and never reaches for the server.
         */
        fun resolve(downloaded: DownloadedTrack?, online: Boolean, cached: Boolean = false, streamUrl: () -> String): PlaySource = when {
            downloaded != null -> Local(downloaded.location)
            online || cached -> Remote(streamUrl())
            else -> Unavailable
        }
    }
}
