package app.sine.core

import app.sine.core.search.SearchHistory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryTest {
    private val file = Files.createTempDirectory("history").resolve("search.json").toFile()

    @Test
    fun newestFirstOnceEachAndKeptOnThePhone() {
        val h = SearchHistory(file, keep = 3)
        h.add("skeler")
        h.add("  plenka  ")
        h.add("")
        h.add("Skeler")
        assertEquals(listOf("Skeler", "plenka"), h.queries.value)
        h.add("tides")
        h.add("night  drive")
        assertEquals(listOf("night drive", "tides", "Skeler"), h.queries.value)
        // Read again from the file, as after a restart.
        assertEquals(listOf("night drive", "tides", "Skeler"), SearchHistory(file, keep = 3).queries.value)
    }

    @Test
    fun oneGoesOrAllDo() {
        val h = SearchHistory(file)
        h.add("a")
        h.add("b")
        h.remove("a")
        assertEquals(listOf("b"), h.queries.value)
        h.clear()
        assertEquals(emptyList(), SearchHistory(file).queries.value)
    }
}
