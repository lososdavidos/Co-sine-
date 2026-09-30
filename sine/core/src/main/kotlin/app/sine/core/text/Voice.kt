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
    const val CHOOSE_DOWNLOAD_FOLDER = "Choose a folder for downloads first."

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

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
