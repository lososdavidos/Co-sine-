package app.sine.desktop

import app.sine.core.model.Track
import app.sine.desktop.player.PlayQueue
import app.sine.desktop.player.Repeat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayQueueTest {
    private fun t(id: String) = Track(id, id, "Artist", album = "Album")
    private val album = (1..5).map { t("a$it") }
    private fun PlayQueue.titles() = tracks.map { it.id }
    private fun PlayQueue.nowId() = current?.track?.id

    @Test
    fun `starts at the song chosen, in order`() {
        val q = PlayQueue().start(album, 2)
        assertEquals("a3", q.nowId())
        assertEquals(listOf("a4", "a5"), q.upNext.map { it.second.track.id })
    }

    @Test
    fun `songs added play next, in the order added, before the rest`() {
        val q = PlayQueue().start(album, 0).add(listOf(t("x"))).add(listOf(t("y"), t("z")))
        assertEquals(listOf("x", "y", "z"), q.queuedAhead.map { it.second.track.id })
        assertEquals(listOf("a1", "x", "y", "z", "a2", "a3", "a4", "a5"), q.titles())
        val after = q.next()!!
        assertEquals("x", after.nowId())
        // Added while one added is playing: still before the rest.
        val more = after.add(listOf(t("w")))
        assertEquals(listOf("y", "z", "w"), more.queuedAhead.map { it.second.track.id })
    }

    @Test
    fun `adding to nothing starts it`() {
        val q = PlayQueue().add(listOf(t("x"), t("y")))
        assertEquals("x", q.nowId())
    }

    @Test
    fun `skipping past the songs added keeps them next`() {
        val q = PlayQueue().start(album, 0).add(listOf(t("x"), t("y")))
        val at = q.order.indexOfFirst { q.entries[it].track.id == "a4" }
        val skipped = q.skipTo(at)
        assertEquals("a4", skipped.nowId())
        assertEquals(listOf("x", "y"), skipped.queuedAhead.map { it.second.track.id })
        assertEquals(listOf("a5"), skipped.upNext.map { it.second.track.id })
    }

    @Test
    fun `skipping to a song added skips those before it`() {
        val q = PlayQueue().start(album, 0).add(listOf(t("x"), t("y")))
        val skipped = q.skipTo(2)
        assertEquals("y", skipped.nowId())
        assertEquals(listOf("a2", "a3", "a4", "a5"), skipped.upNext.map { it.second.track.id })
    }

    @Test
    fun `the end stops, unless repeating the queue`() {
        val q = PlayQueue().start(album, 4)
        assertNull(q.next())
        assertEquals("a1", q.withRepeat(Repeat.ALL).next()!!.nowId())
        assertEquals(Repeat.ALL, q.nextRepeat())
        assertEquals(Repeat.ONE, q.withRepeat(Repeat.ALL).nextRepeat())
        assertEquals(Repeat.OFF, q.withRepeat(Repeat.ONE).nextRepeat())
    }

    @Test
    fun `shuffle keeps the song playing and the songs added, and off puts the rest back`() {
        val q = PlayQueue().start(album, 1).add(listOf(t("x")))
        val on = q.withShuffle(true, Random(7))
        assertEquals("a2", on.nowId())
        assertEquals(listOf("x"), on.queuedAhead.map { it.second.track.id })
        assertEquals(setOf("a3", "a4", "a5"), on.upNext.map { it.second.track.id }.toSet())
        val off = on.withShuffle(false)
        assertEquals("a2", off.nowId())
        assertEquals(listOf("x"), off.queuedAhead.map { it.second.track.id })
        assertEquals(listOf("a3", "a4", "a5"), off.upNext.map { it.second.track.id })
    }

    @Test
    fun `shuffled from the start, the song chosen plays first`() {
        val q = PlayQueue().withShuffle(true).start(album, 3, Random(1))
        assertEquals("a4", q.nowId())
        assertEquals(5, q.order.toSet().size)
    }

    @Test
    fun `removing a song to come`() {
        val q = PlayQueue().start(album, 0)
        val removed = q.remove(2)
        assertEquals(listOf("a1", "a2", "a4", "a5"), removed.titles())
        assertEquals("a1", removed.nowId())
    }
}
