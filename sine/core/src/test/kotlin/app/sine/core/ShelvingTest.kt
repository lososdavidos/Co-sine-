package app.sine.core

import app.sine.core.model.Artist
import app.sine.core.model.Shelving
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelvingTest {
    @Test
    fun theOrderAndTheDividersAgree() {
        val names = listOf("The Weeknd", "Ólafur Arnalds", "Wet Leg", "100 gecs", "\$uicideboy\$", "Björk", "Burial", "the xx", "Air", "The")
        val sorted = names.map { Artist(it, it) }.sortedWith(Shelving.artists).map { it.name }
        assertEquals(listOf("\$uicideboy\$", "100 gecs", "Air", "Björk", "Burial", "Ólafur Arnalds", "The", "The Weeknd", "Wet Leg", "the xx"), sorted)
        assertEquals(listOf("#", "#", "A", "B", "B", "O", "T", "W", "W", "X"), sorted.map(Shelving::letter))
    }
}
