package app.sine.core.download

import app.sine.core.model.DownloadNode
import app.sine.core.model.Track
import app.sine.core.subsonic.SubsonicClient

/** Expands a download unit (track, album, artist, playlist) into the tracks still missing. */
class DownloadPlanner(private val client: SubsonicClient, private val index: DownloadIndex) {

    suspend fun expand(node: DownloadNode): List<Track> = when (node) {
        is DownloadNode.OfTrack -> listOf(client.track(node.id))
        is DownloadNode.OfAlbum -> client.album(node.id).tracks
        is DownloadNode.OfPlaylist -> client.playlist(node.id).tracks
        is DownloadNode.OfArtist -> client.artist(node.id).albums.flatMap { client.album(it.id).tracks }
    }

    suspend fun missing(node: DownloadNode): List<Track> = missing(expand(node))

    fun missing(tracks: List<Track>): List<Track> =
        tracks.distinctBy { it.id }.filterNot { it.id in index }
}
