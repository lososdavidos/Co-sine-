package app.sine.core

import app.sine.core.car.CarNode
import app.sine.core.car.CarSource
import app.sine.core.car.CarTree
import app.sine.core.download.DownloadedTrack
import app.sine.core.download.OfflineLibrary
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.SearchResults
import app.sine.core.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarTreeTest {
    private fun t(id: String, title: String, album: String, albumId: String, n: Int) =
        Track(id, title, "Skeler", "ar1", album, albumId, trackNumber = n)

    private val tides = listOf(t("1", "Fall", "Tides", "al1", 1), t("2", "Rise", "Tides", "al1", 2), t("3", "Ebb", "Tides", "al1", 3))
    private val night = listOf(t("4", "Dusk", "Night", "al2", 1))

    private inner class Fake(private val downloads: List<Track>) : CarSource {
        override fun downloaded() = OfflineLibrary(downloads.map { DownloadedTrack(it, "x", 1, 0) })
        override suspend fun playlists() = listOf(Playlist("p1", "Drive", trackCount = 2))
        override suspend fun playlist(id: String) = if (id == "p1") listOf(night[0], tides[1]) else emptyList()
        override suspend fun artists() = listOf(Artist("ar1", "Skeler"))
        override suspend fun artist(id: String) = listOf(Album("al1", "Tides", "Skeler", "ar1"), Album("al2", "Night", "Skeler", "ar1"))
        override suspend fun albums() = artist("ar1")
        override suspend fun album(id: String) = when (id) { "al1" -> tides; "al2" -> night; else -> emptyList() }
        override suspend fun track(id: String) = (tides + night).firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults(
            emptyList(),
            albums().filter { query in it.name.lowercase() },
            (tides + night).filter { query in it.title.lowercase() },
        )
    }

    @Test
    fun theFirstTabIsWhatsOnThisPhone() = runTest {
        val tree = CarTree(Fake(listOf(tides[0])))
        val tabs = tree.children(CarTree.ROOT)
        assertEquals(listOf("On this phone", "Playlists", "Artists", "Albums"), tabs.map { it.title })
        val phone = tree.children(CarTree.OFFLINE)
        assertEquals("Shuffle all", phone.first().title)
        // Only what's downloaded: one album, with its one song.
        assertEquals(listOf("Tides"), phone.drop(1).map { it.title })
        assertEquals(listOf("Fall"), tree.children(phone[1].id).map { it.title })
    }

    @Test
    fun nothingDownloadedShowsNothingToShuffle() = runTest {
        val tree = CarTree(Fake(emptyList()))
        assertTrue(tree.children(CarTree.OFFLINE).isEmpty())
        assertNull(tree.play(CarTree.SHUFFLE_OFFLINE))
    }

    @Test
    fun aSongPlaysItsAlbumFromIt() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val album = tree.children(tree.children(CarTree.ALBUMS).first().id)
        val play = tree.play(album[1].id)!!
        assertEquals(tides, play.tracks)
        assertEquals(1, play.start)
    }

    @Test
    fun aSongInAPlaylistPlaysThePlaylist() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val songs = tree.children(tree.children(CarTree.PLAYLISTS).first().id)
        val play = tree.play(songs[1].id)!!
        assertEquals(listOf("4", "2"), play.tracks.map { it.id })
        assertEquals(1, play.start)
    }

    @Test
    fun anArtistOpensToTheirAlbums() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val artist = tree.children(CarTree.ARTISTS).single()
        assertEquals(listOf("Tides", "Night"), tree.children(artist.id).map { it.title })
        assertEquals(CarNode.Kind.Artist, (artist as CarNode.Folder).kind)
    }

    @Test
    fun anAlbumPlaysFromTheTop() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val play = tree.play(CarTree.ALBUM + "al1")!!
        assertEquals(tides, play.tracks)
        assertEquals(0, play.start)
    }

    @Test
    fun itemsAreFoundAgainByTheirIds() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val song = CarTree.song(CarTree.ALBUM + "al1", "3")
        assertEquals("Ebb", tree.item(song)!!.title)
        assertEquals("Drive", tree.item(CarTree.PLAYLIST + "p1")!!.title)
        assertEquals("Playlists", tree.item(CarTree.PLAYLISTS)!!.title)
        assertNull(tree.item(CarTree.song(CarTree.ALBUM + "al1", "9")))
        assertNull(tree.play("nonsense"))
    }

    @Test
    fun songIdsSurviveBarsInTheirContext() {
        assertEquals("playlist:a|b" to "7", CarTree.parseSong(CarTree.song("playlist:a|b", "7")))
        assertNull(CarTree.parseSong("album:al1"))
    }

    @Test
    fun spokenSearchPlaysTheSongWithItsAlbum() = runTest {
        val tree = CarTree(Fake(emptyList()))
        val play = tree.search("ebb")!!
        assertEquals(tides, play.tracks)
        assertEquals(2, play.start)
        assertEquals(night, tree.search("night")!!.tracks)
        assertNull(tree.search("nothing like it"))
    }
}
