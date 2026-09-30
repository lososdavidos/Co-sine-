package app.sine.core.model

import kotlinx.serialization.Serializable

/**
 * Library entities as Sine sees them, independent of the wire format.
 * They are @Serializable so the download index can keep full metadata for
 * offline browsing (G1): a downloaded track must be browsable with the server gone.
 */

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val coverArt: String? = null,
    val trackCount: Int = 0,
    val durationSec: Int = 0,
    val year: Int? = null,
    val created: String? = null,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String,
    val albumId: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val durationSec: Int = 0,
    val sizeBytes: Long? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val coverArt: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val durationSec: Int = 0,
    val coverArt: String? = null,
    val owner: String? = null,
)

data class ArtistDetail(val artist: Artist, val albums: List<Album>)
data class AlbumDetail(val album: Album, val tracks: List<Track>)
data class PlaylistDetail(val playlist: Playlist, val tracks: List<Track>)
data class SearchResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/** Something that can be downloaded as a unit (§2.5: any node or playlist). */
sealed interface DownloadNode {
    data class OfTrack(val id: String) : DownloadNode
    data class OfAlbum(val id: String) : DownloadNode
    data class OfArtist(val id: String) : DownloadNode
    data class OfPlaylist(val id: String) : DownloadNode
}
