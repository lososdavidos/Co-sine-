package app.sine.core

import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.SearchRanking
import app.sine.core.model.SearchResults
import app.sine.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRankingTest {
    @Test
    fun `exact beats prefix beats contains, then type order`() {
        val results = SearchResults(
            artists = listOf(Artist("a1", "Night Tempo"), Artist("a2", "Night")),
            albums = listOf(Album("al1", "Night", "x"), Album("al2", "Late Night", "x")),
            tracks = listOf(Track(id = "t1", title = "night", artist = "y", album = "z")),
        )
        val ranked = SearchRanking.rank("night", results, listOf(Playlist("p1", "Nightdrive")))
        assertEquals(
            listOf("Night", "Night", "night", "Night Tempo", "Nightdrive", "Late Night"),
            ranked.map { it.name },
        )
        // Exact tier: artist, then album, then track.
        assertEquals(listOf("a2", "al1", "t1"), ranked.take(3).map {
            when (it) {
                is app.sine.core.model.SearchHit.OfArtist -> it.artist.id
                is app.sine.core.model.SearchHit.OfAlbum -> it.album.id
                is app.sine.core.model.SearchHit.OfTrack -> it.track.id
                is app.sine.core.model.SearchHit.OfPlaylist -> it.playlist.id
            }
        })
    }
}
