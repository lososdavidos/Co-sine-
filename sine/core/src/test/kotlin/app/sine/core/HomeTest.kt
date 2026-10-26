package app.sine.core

import app.sine.core.cosine.SmartPlaylist
import app.sine.core.home.HomeDefaults
import app.sine.core.home.HomeEdits
import app.sine.core.home.HomeLayout
import app.sine.core.home.HomeStore
import app.sine.core.home.Tile
import app.sine.core.home.TileGrid
import app.sine.core.home.TileKind
import app.sine.core.home.TileSize
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeTest {
    private val dir: File = Files.createTempDirectory("sine-home").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun album(id: String, size: TileSize) = Tile(TileKind.ALBUM, id, size)

    private fun cells(tiles: List<Tile>) = TileGrid.place(tiles).map { "${it.tile.ref}@${it.column},${it.row}" }

    @Test
    fun `tiles go in order, each in the first spot from the top where it fits`() {
        val tiles = listOf(
            album("wide", TileSize.Wide),
            album("a", TileSize.Medium),
            album("s1", TileSize.Small),
            album("s2", TileSize.Small),
            album("s3", TileSize.Small),
            album("b", TileSize.Medium),
            album("s4", TileSize.Small),
        )
        assertEquals(
            listOf("wide@0,0", "a@0,2", "s1@2,2", "s2@3,2", "s3@2,3", "b@0,4", "s4@3,3"),
            cells(tiles),
        )
        assertEquals(6, TileGrid.height(TileGrid.place(tiles)))
    }

    @Test
    fun `a square sits on a multiple of its width, so squares stay in line`() {
        // One small tile first: the medium one can't start in column 1.
        assertEquals(listOf("s@0,0", "m@2,0"), cells(listOf(album("s", TileSize.Small), album("m", TileSize.Medium))))
        // A wide tile after a small one starts a row of its own.
        assertEquals(listOf("s@0,0", "w@0,1"), cells(listOf(album("s", TileSize.Small), album("w", TileSize.Wide))))
    }

    @Test
    fun `edits add once, resize round, move by place and remove`() {
        val a = album("a", TileSize.Small)
        val b = album("b", TileSize.Medium)
        val c = album("c", TileSize.Wide)
        var tiles = HomeEdits.add(listOf(a, b), c)
        assertEquals(listOf(a, b, c), tiles)
        assertEquals(tiles, HomeEdits.add(tiles, c.copy(size = TileSize.Small)), "the same thing twice is one tile")
        assertTrue(HomeEdits.add(tiles, c.copy(shuffle = true)).size == 4, "its shuffle variant is another")

        tiles = HomeEdits.resize(tiles, a.id)
        assertEquals(TileSize.Medium, tiles[0].size)
        assertEquals(TileSize.Small, HomeEdits.resize(tiles, c.id)[2].size, "wide goes back to small")

        assertEquals(listOf("c", "a", "b"), HomeEdits.move(tiles, c.id, 0).map { it.ref })
        assertEquals(listOf("b", "c", "a"), HomeEdits.move(tiles, a.id, 99).map { it.ref })
        assertEquals(listOf("a", "c"), HomeEdits.remove(tiles, b.id).map { it.ref })
    }

    @Test
    fun `defaults use the account's smart playlists, found by rule, and only what the server can fill`() {
        val smart = listOf(
            SmartPlaylist(id = "sp-1", name = "Fresh", rule = "added"),
            SmartPlaylist(id = "sp-2", name = "Most played this year", rule = "most-played", span = "year"),
            SmartPlaylist(id = "sp-5", name = "Never played", rule = "never-played"),
        )
        val cosine = HomeDefaults.tiles(smart, mirrored = true, review = true, ingest = false)
        assertEquals(
            listOf("now-playing", "playlist:sp-1", "playlist:sp-2", "shuffle-all", "downloaded", "review", "playlist:sp-5"),
            cosine.map { it.id },
        )
        assertEquals("Fresh", cosine[1].name)
        assertEquals(TileSize.Wide, cosine[0].size)

        val compat = HomeDefaults.tiles(emptyList(), mirrored = false, review = false, ingest = false)
        assertEquals(listOf("now-playing", "downloaded"), compat.map { it.id })
    }

    @Test
    fun `an edit waits for Cosine, and Cosine's copy doesn't replace it meanwhile`() {
        var now = 1_000L
        val file = File(dir, "home.json")
        val store = HomeStore(file) { now }
        assertNull(store.layout.value, "nothing arranged yet: Home shows the defaults")

        val mine = listOf(Tile(TileKind.NOW_PLAYING, size = TileSize.Wide))
        store.edit(mine)
        assertTrue(store.unsent)
        val edited = store.layout.value!!
        assertEquals(1_000L, edited.updated)

        // Another phone's later copy arrives while this edit waits: this one stays.
        store.received(HomeLayout(listOf(Tile(TileKind.DOWNLOADED)), updated = 5_000))
        assertEquals(mine, store.layout.value!!.tiles)

        store.sent(edited)
        assertFalse(store.unsent)
        // Kept across a restart.
        assertEquals(edited, HomeStore(file) { now }.layout.value)

        // Now a later copy replaces it; an earlier one doesn't.
        store.received(HomeLayout(listOf(Tile(TileKind.DOWNLOADED)), updated = 500))
        assertEquals(mine, store.layout.value!!.tiles)
        store.received(HomeLayout(listOf(Tile(TileKind.DOWNLOADED)), updated = 5_000))
        assertEquals("downloaded", store.layout.value!!.tiles.single().id)

        // An edit is always later than what it changed, whatever the clock says.
        now = 10
        store.edit(mine)
        assertEquals(5_001L, store.layout.value!!.updated)
    }

    @Test
    fun `sent only clears the wait when nothing changed since`() {
        val store = HomeStore(File(dir, "home.json"))
        store.edit(listOf(Tile(TileKind.DOWNLOADED)))
        val first = store.layout.value!!
        store.edit(listOf(Tile(TileKind.DOWNLOADED), Tile(TileKind.NOW_PLAYING)))
        store.sent(first)
        assertTrue(store.unsent, "the second edit still has to go")
    }
}
