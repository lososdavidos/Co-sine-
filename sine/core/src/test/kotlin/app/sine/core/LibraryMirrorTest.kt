package app.sine.core

import app.sine.core.mirror.LibraryMirror
import app.sine.core.mirror.SyncChanges
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.ArtistEntry
import app.sine.core.model.ArtistPage
import app.sine.core.model.Track
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMirrorTest {
    private val dir: File = Files.createTempDirectory("mirror").toFile()
    private val file = File(dir, "library.json")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val skeler = Artist("ar-1", "Skeler", coverArt = "ar-1")
    private val plenka = Artist("ar-2", "plenka")
    private val tidesEP = Album("al-1", "Tides EP", "Skeler", "ar-1", year = 2019, coverArt = "al-1", created = "2026-10-01T00:00:00Z")
    private val glass = Album("al-2", "Glass", "plenka", "ar-2", year = 2018, created = "2026-10-03T00:00:00Z")

    private fun track(id: String, title: String, album: Album, n: Int? = null, sec: Int = 200) =
        Track(id = id, title = title, artist = album.artist, artistId = album.artistId, album = album.name,
            albumId = album.id, trackNumber = n, durationSec = sec, sizeBytes = 1000, suffix = "mp3")

    private val tides = track("tr-1", "Tides", tidesEP, 1)
    private val surge = track("tr-2", "Surge", tidesEP, 2, sec = 180)
    private val glassTrack = track("tr-3", "Glass", glass)

    private fun full() = SyncChanges(cursor = 10, full = true, artists = listOf(skeler, plenka),
        albums = listOf(tidesEP, glass), tracks = listOf(surge, tides, glassTrack))

    @Test
    fun `a full copy, counted and searchable`() {
        val m = LibraryMirror(file)
        assertFalse(m.synced)
        m.apply(full())
        assertTrue(m.synced)
        assertEquals(10, m.cursor)
        val lib = m.library.value
        assertEquals(listOf("plenka" to 1, "Skeler" to 1), lib.artists().map { it.name to it.albumCount })
        val ep = lib.album("al-1")!!
        assertEquals(2, ep.album.trackCount)
        assertEquals(380, ep.album.durationSec)
        assertEquals(listOf("Tides", "Surge"), ep.tracks.map { it.title }) // in track order
        assertEquals(listOf("Glass", "Tides EP"), lib.recentlyAdded(5).map { it.name })
        assertEquals("ar-1", lib.artist("ar-1")!!.artist.coverArt)

        // Search: names, and tracks or albums by a matching artist.
        val hits = lib.search("skel")
        assertEquals(listOf("Skeler"), hits.artists.map { it.name })
        assertEquals(listOf("Tides EP"), hits.albums.map { it.name })
        assertEquals(listOf("Tides", "Surge"), hits.tracks.map { it.title })
        assertTrue(lib.search("  ").isEmpty)

        // Kept on disk: a cold start has it all.
        val reopened = LibraryMirror(file)
        assertEquals(10, reopened.cursor)
        assertEquals(2, reopened.library.value.artists().size)
        assertEquals(2, reopened.library.value.album("al-1")!!.tracks.size)
    }

    @Test
    fun `changes add, update and remove, and empty albums and artists go`() {
        val m = LibraryMirror(file)
        m.apply(full())
        val first = m.library.value

        // A renamed track and a new one.
        m.apply(SyncChanges(cursor = 12, albums = listOf(tidesEP), artists = listOf(skeler),
            tracks = listOf(tides.copy(title = "Tides (VIP)"), track("tr-4", "Undertow", tidesEP, 3))))
        val ep = m.library.value.album("al-1")!!
        assertEquals(listOf("Tides (VIP)", "Surge", "Undertow"), ep.tracks.map { it.title })
        assertTrue(first !== m.library.value, "a change publishes a new library")

        // plenka's only track goes: the album and the artist go with it.
        m.apply(SyncChanges(cursor = 13, removedTracks = listOf("tr-3")))
        val lib = m.library.value
        assertNull(lib.album("al-2"))
        assertNull(lib.artist("ar-2"))
        assertEquals(listOf("Skeler"), lib.artists().map { it.name })

        // Nothing new: nothing written, nothing published.
        val before = m.library.value
        m.apply(SyncChanges(cursor = 13))
        assertTrue(before === m.library.value)

        // A full answer replaces everything (the server's database was restored).
        m.apply(SyncChanges(cursor = 3, full = true, artists = listOf(plenka), albums = listOf(glass), tracks = listOf(glassTrack)))
        assertEquals(listOf("plenka"), m.library.value.artists().map { it.name })
        assertEquals(3, m.cursor)
    }
}

class ArtistPageTest {
    private fun album(id: String, name: String, tracks: Int) = Album(id, name, "Skeler", "ar-1", trackCount = tracks)
    private fun track(id: String, title: String, albumId: String) =
        Track(id = id, title = title, artist = "Skeler", album = "", albumId = albumId, durationSec = 200)

    @Test
    fun `a release of one song named like it is a single`() {
        val albums = listOf(
            album("al-1", "Tides EP", 3),
            album("al-2", "Plume", 1),        // a single
            album("al-3", "Hollow Sessions", 1), // one track, another name: still a release
            album("al-4", "glass ", 1),       // unknown tracks: stays a release
        )
        val tracks = mapOf(
            "al-2" to listOf(track("tr-2", "plume", "al-2")),
            "al-3" to listOf(track("tr-3", "Hollow", "al-3")),
        )
        val entries = ArtistPage.entries(albums) { tracks[it.id] }
        assertEquals(listOf("Release", "Single", "Release", "Release"), entries.map { it::class.simpleName })
        assertEquals("tr-2", (entries[1] as ArtistEntry.Single).track.id)
        assertEquals(albums, entries.map { it.album }) // the order is the releases'
    }
}
