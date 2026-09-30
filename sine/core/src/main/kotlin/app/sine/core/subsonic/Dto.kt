package app.sine.core.subsonic

import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.Track
import kotlinx.serialization.Serializable

// Wire shapes of the Subsonic JSON API. Only the fields Sine uses; everything
// else is ignored so newer servers and OpenSubsonic extensions never break parsing.

@Serializable
internal data class ArtistDto(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val album: List<AlbumDto> = emptyList(),
) {
    fun toModel() = Artist(id, name, albumCount, coverArt)
}

@Serializable
internal data class IndexDto(val name: String = "", val artist: List<ArtistDto> = emptyList())

@Serializable
internal data class ArtistsDto(val index: List<IndexDto> = emptyList())

@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val created: String? = null,
    val song: List<SongDto> = emptyList(),
) {
    fun toModel() = Album(
        id = id,
        name = name ?: title ?: "",
        artist = artist ?: "",
        artistId = artistId,
        coverArt = coverArt,
        trackCount = songCount,
        durationSec = duration,
        year = year,
        created = created,
    )
}

@Serializable
internal data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
internal data class SongDto(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val duration: Int? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val coverArt: String? = null,
) {
    fun toModel() = Track(
        id = id,
        title = title,
        artist = artist ?: "",
        artistId = artistId,
        album = album ?: "",
        albumId = albumId,
        trackNumber = track,
        discNumber = discNumber,
        year = year,
        durationSec = duration ?: 0,
        sizeBytes = size,
        suffix = suffix,
        bitRate = bitRate,
        coverArt = coverArt,
    )
}

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val owner: String? = null,
    val entry: List<SongDto> = emptyList(),
) {
    fun toModel() = Playlist(id, name, songCount, duration, coverArt, owner)
}

@Serializable
internal data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
internal data class SearchResult3Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class ErrorDto(val code: Int = 0, val message: String = "")
