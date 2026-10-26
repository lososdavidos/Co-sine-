package app.sine.core.model

/** Editing a playlist's songs as listed (§2.4), before sending the change. */
object PlaylistEdits {
    /** The list with the item at [from] moved to [to]. */
    fun <T> moved(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Positions of the chosen songs in the playlist as listed: every place each one is. */
    fun positionsOf(tracks: List<Track>, chosen: Set<String>): List<Int> =
        tracks.indices.filter { tracks[it].id in chosen }
}
