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

class PlaylistEditsTest {
    @Test
    fun `moving and finding songs as listed`() {
        val l = listOf("a", "b", "c", "d")
        kotlin.test.assertEquals(listOf("b", "c", "a", "d"), app.sine.core.model.PlaylistEdits.moved(l, 0, 2))
        kotlin.test.assertEquals(listOf("a", "d", "b", "c"), app.sine.core.model.PlaylistEdits.moved(l, 3, 1))
        kotlin.test.assertEquals(l, app.sine.core.model.PlaylistEdits.moved(l, 0, 9))
        val tracks = listOf("t1", "t2", "t1", "t3").map { app.sine.core.model.Track(id = it, title = it, artist = "a", album = "b") }
        kotlin.test.assertEquals(listOf(0, 2, 3), app.sine.core.model.PlaylistEdits.positionsOf(tracks, setOf("t1", "t3")))
    }

    @Test
    fun `a smart rule keeps only its own setting`() {
        val r = app.sine.core.model.SmartRules
        val p = r.starting("forgotten")
        kotlin.test.assertEquals("Forgotten", p.name)
        kotlin.test.assertEquals(6, p.months)
        val moved = r.withSetting(p.copy(rule = "most-played"))
        kotlin.test.assertEquals("year", moved.span)
        kotlin.test.assertEquals(null, moved.months)
        kotlin.test.assertEquals("2 years", r.monthsLabel(24))
    }
}
