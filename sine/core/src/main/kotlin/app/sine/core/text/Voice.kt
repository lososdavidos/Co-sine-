package app.sine.core.text

import java.util.Locale

/**
 * User-facing copy (§6.17): plain and factual. State what is true and what
 * happens next. No apologies, no exclamation marks.
 */
object Voice {
    fun serverUnreachable(isCosine: Boolean) =
        if (isCosine) "Cosine unreachable. Showing downloaded music."
        else "Server unreachable. Showing downloaded music."

    const val EMPTY_PLAYLIST = "No tracks in this playlist."
    const val TRACK_UNAVAILABLE = "Not downloaded. Reconnect to play."
    const val NO_SEARCH_RESULTS = "No matches in your library."
    const val NO_DOWNLOADS = "Nothing downloaded yet."
    const val NOTHING_TO_REVIEW = "Nothing to review."
    const val CHOOSE_DOWNLOAD_FOLDER = "Choose a folder for downloads first."

    /** Taking songs out of your library (§4.1): asked first, then said. */
    fun removeQuestion(count: Int) =
        if (count == 1) "Remove this song from your library?" else "Remove $count songs from your library?"

    fun removeExplained(count: Int) =
        if (count == 1) "Its download on this phone goes too. Adding it again brings it back."
        else "Their downloads on this phone go too. Adding one again brings it back."

    fun removeAction(count: Int) = if (count == 1) "Remove 1 song" else "Remove $count songs"

    fun removed(count: Int) =
        if (count == 1) "Removed 1 song from your library." else "Removed $count songs from your library."

    fun downloadQueued(count: Int) =
        if (count == 1) "Downloading 1 track." else "Downloading $count tracks."

    fun overBudget(overBytes: Long) =
        "Downloads are ${formatBytes(overBytes)} over the pinned budget."

    fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", v, units[i])
    }

    /** How a resolver source is named to people (§3.2). */
    fun sourceName(source: String): String = when (source) {
        "musicbrainz" -> "MusicBrainz"
        "discogs" -> "Discogs"
        "bandcamp" -> "Bandcamp"
        else -> source
    }

    /** How a search result's length compares with the release (§6.10), as Cosine's dashboard says it. */
    fun lengthNote(durationSec: Int, releaseSec: Int): String? {
        if (releaseSec <= 0 || durationSec <= 0) return null
        val off = kotlin.math.abs(durationSec - releaseSec)
        return when {
            off <= 6 -> "Same length as the release"
            durationSec > releaseSec -> "${formatDuration(off)} longer than the release"
            else -> "${formatDuration(off)} shorter than the release"
        }
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
