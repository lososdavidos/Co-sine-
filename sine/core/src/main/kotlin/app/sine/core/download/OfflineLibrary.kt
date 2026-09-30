package app.sine.core.download

import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.SearchResults
import app.sine.core.model.Track

/**
 * The library as seen from downloads alone. In compat mode there is no metadata
 * mirror (G2 degrades, §2.1), so offline browsing is built from what is pinned.
 */
class OfflineLibrary(downloads: Collection<DownloadedTrack>) {
    private val tracks: List<Track> = downloads.map { it.track }

    fun artists(): List<Artist> =
        tracks.groupBy { artistKey(it) }
            .map { (key, ts) ->
                Artist(
                    id = key,
                    name = ts.first().artist.ifBlank { "Unknown artist" },
                    albumCount = ts.map { albumKey(it) }.distinct().size,
                    coverArt = ts.firstNotNullOfOrNull { it.coverArt },
                )
            }
            .sortedBy { it.name.lowercase() }

    fun albums(artistId: String? = null): List<Album> =
        tracks.filter { artistId == null || artistKey(it) == artistId }
            .groupBy { albumKey(it) }
            .map { (key, ts) ->
                val first = ts.first()
                Album(
                    id = key,
                    name = first.album.ifBlank { first.title },
                    artist = first.artist,
                    artistId = artistKey(first),
                    coverArt = ts.firstNotNullOfOrNull { it.coverArt },
                    trackCount = ts.size,
                    durationSec = ts.sumOf { it.durationSec },
                    year = ts.firstNotNullOfOrNull { it.year },
                )
            }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.name.lowercase() }))

    fun albumTracks(albumId: String): List<Track> =
        tracks.filter { albumKey(it) == albumId }.sortedWith(trackOrder)

    fun all(): List<Track> =
        tracks.sortedWith(compareBy<Track>({ it.artist.lowercase() }, { it.album.lowercase() }).then(trackOrder))

    fun search(query: String): SearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        return SearchResults(
            artists().filter { q in it.name.lowercase() },
            albums().filter { q in it.name.lowercase() },
            all().filter { q in it.title.lowercase() },
        )
    }

    companion object {
        // Server ids when present, so offline ids match online navigation.
        fun artistKey(t: Track) = t.artistId ?: "artist:${t.artist.lowercase()}"
        fun albumKey(t: Track) = t.albumId ?: "album:${t.artist.lowercase()}/${t.album.lowercase()}"

        val trackOrder: Comparator<Track> =
            compareBy<Track>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() })
    }
}
