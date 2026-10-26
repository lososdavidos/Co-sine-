package app.sine.core

import app.sine.core.mirror.LibraryMirror
import app.sine.core.mirror.MirrorPlaylist
import app.sine.core.mirror.MirrorSmart
import app.sine.core.mirror.PlaylistInvite
import app.sine.core.mirror.PlaylistOpResult
import app.sine.core.mirror.PlaylistOps
import app.sine.core.mirror.SyncChanges
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Track
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistMirrorTest {
    private val dir: File = Files.createTempDirectory("playlists").toFile()
    private val file = File(dir, "library.json")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val skeler = Artist("ar-1", "Skeler")
    private val ep = Album("al-1", "Tides EP", "Skeler", "ar-1", coverArt = "al-1")
    private fun track(n: Int) = Track(id = "tr-$n", title = "Song $n", artist = "Skeler", artistId = "ar-1",
        album = ep.name, albumId = ep.id, trackNumber = n, durationSec = 100, coverArt = "al-1")

    private var ops = 0
    private val newId = { "op${++ops}" }

    @Test
    fun `moves match Cosine's`() {
        // The same cases as Cosine's TestMove.
        val cases = listOf(
            listOf("1", "2", "3") to (Triple("1", 0, "3" to 0) to listOf("2", "3", "1")),
            listOf("1", "2", "3") to (Triple("3", 0, "1" to 0) to listOf("1", "3", "2")),
            listOf("1", "2", "3") to (Triple("3", 0, null to 0) to listOf("3", "1", "2")),
            listOf("1", "2", "1") to (Triple("1", 1, null to 0) to listOf("1", "1", "2")),
            listOf("1", "2", "1") to (Triple("2", 0, "1" to 1) to listOf("1", "1", "2")),
            listOf("1", "2") to (Triple("1", 0, "1" to 0) to listOf("1", "2")),
        )
        for ((list, case) in cases) {
            val (move, want) = case
            assertEquals(want, PlaylistOps.move(list, move.first, move.second, move.third.first, move.third.second), "$list $move")
        }
        assertNull(PlaylistOps.move(listOf("1", "2"), "3", 0, "1", 0))
        assertNull(PlaylistOps.move(listOf("1", "2"), "1", 0, "3", 0))
    }

    @Test
    fun `a reorder is the fewest moves, and they land as asked`() {
        val rnd = Random(7)
        repeat(500) {
            // Songs, some twice, and hidden entries (songs not in this library) among them.
            val size = rnd.nextInt(1, 9)
            val tracks = List(size) { i -> if (rnd.nextInt(4) == 0) "hidden-$i" else "tr-${rnd.nextInt(1, 5)}" }
            val shown = tracks.indices.filter { !tracks[it].startsWith("hidden") }
            val order = shown.indices.shuffled(rnd)
            val made = PlaylistOps.reorder("pl-1", tracks, shown, order, newId)
            var lists = mapOf("pl-1" to MirrorPlaylist("pl-1", "x", tracks = tracks))
            for (op in made) lists = PlaylistOps.apply(lists, op)
            val after = lists.getValue("pl-1").tracks
            assertEquals(tracks.sorted(), after.sorted(), "nothing lost or doubled")
            assertEquals(order.map { tracks[shown[it]] }, after.filterNot { it.startsWith("hidden") }, "$tracks $order")
            assertTrue(made.size <= shown.size)
        }
        // Moving one song is one move.
        val one = PlaylistOps.reorder("pl-1", listOf("a", "b", "c", "d"), listOf(0, 1, 2, 3), listOf(1, 2, 0, 3), newId)
        assertEquals(1, one.size)
        assertEquals("a" to "c", one.single().track to one.single().after)
        assertTrue(PlaylistOps.reorder("pl-1", listOf("a", "b"), listOf(0, 1), listOf(0, 1), newId).isEmpty())
    }

    @Test
    fun `removing names each entry as it is just then`() {
        val tracks = listOf("a", "b", "a", "c", "a")
        val made = PlaylistOps.remove("pl-1", tracks, listOf(0, 4, 2), newId)
        assertEquals(listOf("a" to 2, "a" to 1, "a" to 0), made.map { it.track to it.nth })
        var lists = mapOf("pl-1" to MirrorPlaylist("pl-1", "x", tracks = tracks))
        for (op in made) lists = PlaylistOps.apply(lists, op)
        assertEquals(listOf("b", "c"), lists.getValue("pl-1").tracks)
    }

    private fun library(vararg playlists: MirrorPlaylist) = SyncChanges(
        cursor = 10, full = true, artists = listOf(skeler), albums = listOf(ep), tracks = (1..4).map { track(it) },
        playlists = playlists.toList(),
        invites = listOf(PlaylistInvite("pl-7", "Theirs", "carol", 3)),
        smart = listOf(MirrorSmart("sp-1", "Recently added", "added", days = 30, description = "Added in the last 30 days",
            tracks = listOf("tr-2", "tr-1"))),
        smartTag = "t1",
    )

    @Test
    fun `playlists come from the mirror, hidden songs keep their place`() {
        val m = LibraryMirror(file)
        m.apply(library(
            MirrorPlaylist("pl-1", "Night drive", owner = "alice", tracks = listOf("tr-1", "tr-9", "tr-2")),
            MirrorPlaylist("pl-2", "Ours", owner = "bob", mine = false, sharedWith = listOf("alice"), tracks = listOf("tr-3")),
        ))
        val lib = m.library.value
        assertEquals(listOf("Night drive", "Ours", "Recently added"), lib.playlists().map { it.name })
        val night = lib.playlist("pl-1")!!
        assertEquals(listOf("tr-1", "tr-2"), night.tracks.map { it.id }) // tr-9 isn't in this library
        assertEquals(2, night.playlist.trackCount)
        assertEquals(200, night.playlist.durationSec)
        assertEquals("al-1", night.playlist.coverArt)
        assertEquals(listOf(0, 2), lib.shownEntries("pl-1"))
        assertEquals(false, lib.playlist("pl-2")!!.playlist.mine)
        val smart = lib.playlist("sp-1")!!
        assertTrue(smart.playlist.readonly)
        assertEquals("Added in the last 30 days", smart.playlist.comment)
        assertEquals(listOf("tr-2", "tr-1"), smart.tracks.map { it.id })
        assertEquals(30, lib.smartRule("sp-1")!!.days)
        assertEquals("carol", lib.invites().single().owner)
        assertEquals("t1", m.smartTag)

        // Smart playlists unchanged since t1: kept as they are.
        m.apply(SyncChanges(cursor = 11, smartTag = "t1"))
        assertEquals(listOf("tr-2", "tr-1"), m.library.value.playlist("sp-1")!!.tracks.map { it.id })

        // Left, accepted, removed.
        m.apply(SyncChanges(cursor = 12, removedPlaylists = listOf("pl-2"),
            playlists = listOf(MirrorPlaylist("pl-7", "Theirs", owner = "carol", mine = false, tracks = listOf("tr-4")))))
        assertEquals(listOf("Night drive", "Theirs", "Recently added"), m.library.value.playlists().map { it.name })
        assertTrue(m.library.value.invites().isEmpty(), "accepted: no longer an invitation")

        // Kept on disk.
        assertEquals(3, LibraryMirror(file).library.value.playlists().size)
    }

    @Test
    fun `edits made offline show at once and go when Cosine has them`() {
        val m = LibraryMirror(file)
        m.apply(library(MirrorPlaylist("pl-1", "Night drive", tracks = listOf("tr-1", "tr-2"))))

        // Offline: a new playlist, filled and renamed; a song taken out of another.
        val create = PlaylistOps.create(newId(), "Road trip", listOf("tr-3"))
        val local = PlaylistOps.localId(create.id)
        m.edit(listOf(create, PlaylistOps.on(local, newId(), PlaylistOps.ADD).copy(tracks = listOf("tr-4"))))
        val lib = m.library.value
        m.edit(PlaylistOps.remove("pl-1", lib.entries("pl-1")!!, listOf(0), newId))
        assertEquals(listOf("tr-3", "tr-4"), m.library.value.playlist(local)!!.tracks.map { it.id })
        assertEquals(listOf("tr-2"), m.library.value.playlist("pl-1")!!.tracks.map { it.id })
        assertEquals(3, m.pending.size)

        // A restart loses nothing.
        val again = LibraryMirror(file)
        assertEquals(3, again.pending.size)

        // Back online: Cosine takes the first two; the third is still to send.
        again.sent(listOf(PlaylistOpResult("op1", "pl-5"), PlaylistOpResult("op2", "pl-5")))
        assertEquals(1, again.pending.size)
        assertEquals(listOf("tr-3", "tr-4"), again.library.value.playlist(local)!!.tracks.map { it.id }, "shown until the sync brings it")

        // The sync brings Cosine's copy; the screen still open on the old id finds it.
        again.edit(listOf(PlaylistOps.on(local, newId(), PlaylistOps.RENAME).copy(name = "Long drive")))
        again.apply(SyncChanges(cursor = 11, playlists = listOf(MirrorPlaylist("pl-5", "Road trip", tracks = listOf("tr-3", "tr-4")))))
        assertEquals("pl-5", again.library.value.resolve(local))
        assertEquals("Long drive", again.library.value.playlist(local)!!.playlist.name)
        assertEquals(listOf("op3", "op4"), again.pending.map { it.id })
        val rename = again.pending.last()
        assertEquals("pl-5" to null, rename.playlist to rename.new, "sent by Cosine's id now")
        assertEquals(listOf("tr-2"), again.library.value.playlist("pl-1")!!.tracks.map { it.id })
    }
}
