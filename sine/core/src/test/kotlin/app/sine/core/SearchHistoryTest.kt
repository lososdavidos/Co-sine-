package app.sine.core

import app.sine.core.model.Recent
import app.sine.core.model.SearchHistory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryTest {
    private val dir: File = Files.createTempDirectory("sine-history").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun artist(id: String) = Recent(Recent.ARTIST, id, "Artist $id")

    @Test
    fun `opened again goes to the top, once`() {
        val h = SearchHistory(File(dir, "h.json"))
        h.opened(artist("a"))
        h.opened(Recent(Recent.SONG, "s", "Song"))
        h.opened(artist("a"))
        assertEquals(listOf("a", "s"), h.items.value.map { it.id })
    }

    @Test
    fun `the same id of another kind is another entry`() {
        val h = SearchHistory(File(dir, "h.json"))
        h.opened(artist("x"))
        h.opened(Recent(Recent.ALBUM, "x", "Album x"))
        assertEquals(listOf(Recent.ALBUM, Recent.ARTIST), h.items.value.map { it.kind })
    }

    @Test
    fun `keeps the newest twenty, and survives a restart`() {
        val file = File(dir, "h.json")
        val h = SearchHistory(file)
        repeat(25) { h.opened(artist("$it")) }
        assertEquals(SearchHistory.LIMIT, h.items.value.size)
        assertEquals("24", h.items.value.first().id)
        assertEquals(h.items.value, SearchHistory(file).items.value)
    }

    @Test
    fun `remove and clear`() {
        val h = SearchHistory(File(dir, "h.json"))
        h.opened(artist("a"))
        h.opened(artist("b"))
        h.remove(artist("b"))
        assertEquals(listOf("a"), h.items.value.map { it.id })
        h.clear()
        assertEquals(emptyList(), h.items.value)
    }
}
