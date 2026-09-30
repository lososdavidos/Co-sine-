package app.sine.core.play

import app.sine.core.download.DownloadedTrack

/** Where a track plays from right now. Local always wins: playback never depends on the network (G1). */
sealed interface PlaySource {
    data class Local(val location: String) : PlaySource
    data class Remote(val url: String) : PlaySource
    data object Unavailable : PlaySource

    companion object {
        fun resolve(downloaded: DownloadedTrack?, online: Boolean, streamUrl: () -> String): PlaySource = when {
            downloaded != null -> Local(downloaded.location)
            online -> Remote(streamUrl())
            else -> Unavailable
        }
    }
}
