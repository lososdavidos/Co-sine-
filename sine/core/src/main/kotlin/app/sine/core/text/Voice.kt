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

    const val EMPTY_PLAYLIST = "No songs in this playlist."
    const val NO_LIKED_SONGS = "Nothing liked yet. Tap a song twice to like it."
    const val TRACK_UNAVAILABLE = "Not downloaded. Reconnect to play."
    const val NO_SEARCH_RESULTS = "No matches in your library."
    const val NO_DOWNLOADS = "Nothing downloaded yet."
    const val NOTHING_TO_REVIEW = "Nothing to review."
    const val CHOOSE_DOWNLOAD_FOLDER = "Choose a folder for downloads first."

    /** A count of songs. People hear songs; "track" stays in the code. */
    fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"

    /** Taking songs out of your library (§4.1): asked first, then said. */
    fun removeQuestion(count: Int) =
        if (count == 1) "Remove this song from your library?" else "Remove $count songs from your library?"

    fun removeExplained(count: Int) =
        if (count == 1) "Its download on this phone goes too. Adding it again brings it back."
        else "Their downloads on this phone go too. Adding one again brings it back."

    fun removeAction(count: Int) = if (count == 1) "Remove 1 song" else "Remove $count songs"

    fun removed(count: Int) =
        if (count == 1) "Removed 1 song from your library." else "Removed $count songs from your library."

    /** Playlists (§2.4). */
    const val SMART_PLAYLIST_RULE = "A rule picks this playlist's songs. Save it as a playlist to choose them yourself."

    /** Liking a song (#34): two taps on Add to playlist. */
    fun liked(title: String, liked: Boolean) = if (liked) "Liked $title." else "No longer liked: $title."

    fun addedToPlaylist(count: Int, name: String) =
        if (count == 1) "Added 1 song to $name." else "Added $count songs to $name."

    fun removedFromPlaylist(count: Int) =
        if (count == 1) "Took 1 song out of this playlist." else "Took $count songs out of this playlist."

    fun deletePlaylistQuestion(name: String) = "Delete the playlist \"$name\"?"

    const val DELETE_PLAYLIST_EXPLAINED = "Its songs stay in your library."

    /** Edits made with no connection (§5.2). */
    const val PLAYLIST_SAVED_OFFLINE = "Saved on this phone. Cosine gets it when you're back online."

    /** Shared playlists (§2.3). */
    fun sharedBy(owner: String) = "Shared by $owner"

    fun sharedWith(names: List<String>) = "Shared with " + names.joinToString(", ")

    fun leavePlaylistQuestion(name: String) = "Leave the playlist \"$name\"?"

    fun leavePlaylistExplained(owner: String?) =
        (if (owner != null) "It stays $owner's. " else "") + "The songs it brought stay in your library."

    fun invitation(owner: String, songs: Int) = "From $owner · " + songs(songs)

    const val ACCEPT_EXPLAINED = "Accepting puts its songs in your library, so it plays in full. You can both change it."

    fun accepted(added: Int) = when (added) {
        0 -> "You had all its songs already."
        1 -> "Added 1 song to your library."
        else -> "Added $added songs to your library."
    }

    fun invited(name: String) = "Shared with $name. It shows for them once they accept."

    /** Scrobbling (§4A). */
    fun scrobblingAs(username: String) = "Scrobbling as $username"

    fun scrobblesWaiting(count: Int) =
        if (count == 1) "1 play waiting to be sent." else "$count plays waiting to be sent."

    const val SCROBBLING_STOPPED = "Stopped: the service no longer accepts Cosine's access. Connect again."
    const val LASTFM_NOT_SET_UP = "Last.fm needs an API key in Cosine's dashboard (Settings → Scrobbling) first."
    const val LASTFM_APPROVE = "Approve Cosine on the Last.fm page that opened, then come back and finish."
    const val LISTENBRAINZ_TOKEN = "Your user token is on listenbrainz.org, under Settings."
    const val SCROBBLING_EXPLAINED = "Cosine sends your plays, including those played offline, with the time you played them."

    /** Adding an album or playlist from a source (§3.1a). */
    const val PLAYLIST_FILED = "Each song is filed under its own artist and release, never under the playlist's name."

    fun downloadQueued(count: Int) = "Downloading ${songs(count)}."

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
