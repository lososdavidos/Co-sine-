package app.sine.core.model

/** One entry in the single ranked search list. */
sealed interface SearchHit {
    val name: String

    data class OfArtist(val artist: Artist) : SearchHit { override val name get() = artist.name }
    data class OfAlbum(val album: Album) : SearchHit { override val name get() = album.name }
    data class OfPlaylist(val playlist: Playlist) : SearchHit { override val name get() = playlist.name }
    data class OfTrack(val track: Track) : SearchHit { override val name get() = track.title }
}

/**
 * The stated ranking order from §6.8, so the list never feels arbitrary:
 * exact title match, then prefix, then contains; within each, Artists before
 * Albums before Playlists before Tracks. Anything else keeps server order.
 */
object SearchRanking {
    fun rank(query: String, results: SearchResults, playlists: List<Playlist> = emptyList()): List<SearchHit> {
        val q = query.trim().lowercase()
        val hits = results.artists.map { SearchHit.OfArtist(it) } +
            results.albums.map { SearchHit.OfAlbum(it) } +
            playlists.map { SearchHit.OfPlaylist(it) } +
            results.tracks.map { SearchHit.OfTrack(it) }
        return hits.withIndex()
            .sortedWith(compareBy({ matchTier(q, it.value.name) }, { typeTier(it.value) }, { it.index }))
            .map { it.value }
    }

    private fun matchTier(q: String, name: String): Int {
        val n = name.lowercase()
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            q in n -> 2
            else -> 3 // matched on another field (e.g. a track by a matching artist)
        }
    }

    private fun typeTier(hit: SearchHit) = when (hit) {
        is SearchHit.OfArtist -> 0
        is SearchHit.OfAlbum -> 1
        is SearchHit.OfPlaylist -> 2
        is SearchHit.OfTrack -> 3
    }
}
