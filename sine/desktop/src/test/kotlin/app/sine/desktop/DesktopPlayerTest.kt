package app.sine.desktop

import app.sine.core.account.Account
import app.sine.core.model.Track
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.desktop.player.DesktopPlayer
import app.sine.desktop.player.Repeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerTest {
    private val dir: File = Files.createTempDirectory("sine-desktop").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val account = Account.create(
        "http://music.test", SubsonicCredentials.fromPassword("me", "pw"), ServerKind.Subsonic("1.16.1"), 1L,
    )
    private val album = (1..4).map { Track("t$it", "Song $it", "Artist", album = "Album", durationSec = 200) }

    private fun graph(engine: FakeEngine?) = DesktopGraph(File(dir, "app"), File(dir, "music"), engine, scope, "Studio").also {
        it.accounts.add(account)
    }

    @AfterTest
    fun done() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `plays the album through, streaming each song`() {
        val engine = FakeEngine()
        val g = graph(engine)
        val session = g.session(account)
        g.player.play(session, album, 1)
        assertTrue("id=t2" in engine.loaded.last())
        assertTrue(g.player.state.value.isPlaying)
        engine.end()
        assertTrue("id=t3" in engine.loaded.last())
        assertEquals("t3", g.player.state.value.current?.id)
        engine.end()
        engine.end()
        // The end: nothing more, and it stops.
        assertEquals(3, engine.loaded.size)
        assertFalse(g.player.state.value.isPlaying)
    }

    @Test
    fun `a song added to the queue plays next`() {
        val engine = FakeEngine()
        val g = graph(engine)
        val session = g.session(account)
        g.player.play(session, album, 0)
        g.player.addToQueue(session, listOf(Track("x", "Extra", "Other", album = "Elsewhere")))
        engine.end()
        assertTrue("id=x" in engine.loaded.last())
        engine.end()
        assertTrue("id=t2" in engine.loaded.last())
    }

    @Test
    fun `repeat one plays the song again`() {
        val engine = FakeEngine()
        val g = graph(engine)
        g.player.play(g.session(account), album, 0)
        g.player.cycleRepeat()
        g.player.cycleRepeat()
        assertEquals(Repeat.ONE, g.player.state.value.queue.repeat)
        engine.end()
        assertTrue("id=t1" in engine.loaded.last())
        assertEquals(2, engine.loaded.size)
    }

    @Test
    fun `previous restarts the song a few seconds in, else goes back`() {
        val engine = FakeEngine()
        val g = graph(engine)
        g.player.play(g.session(account), album, 2)
        engine.positionMs = 10_000
        g.player.previous()
        assertEquals(0, engine.positionMs)
        assertEquals("t3", g.player.state.value.current?.id)
        g.player.previous()
        assertEquals("t2", g.player.state.value.current?.id)
    }

    @Test
    fun `without VLC it says so and plays nothing`() {
        val g = graph(null)
        assertEquals(DesktopPlayer.NO_VLC, g.player.state.value.problem)
        g.player.play(g.session(account), album, 0)
        assertFalse(g.player.state.value.isPlaying)
        assertEquals(DesktopPlayer.NO_VLC, g.player.state.value.problem)
    }

    @Test
    fun `picks up where the last run stopped, paused`() {
        val engine = FakeEngine()
        val first = graph(engine)
        first.player.play(first.session(account), album, 2)
        first.player.pause()
        assertNotNull(first.player.last.value)

        val again = DesktopGraph(File(dir, "app"), File(dir, "music"), FakeEngine(), scope, "Studio")
        assertNull(again.player.state.value.current)
        again.player.restore()
        assertEquals("t3", again.player.state.value.current?.id)
        assertFalse(again.player.state.value.isPlaying)
    }

    @Test
    fun `this computer is a desktop device, its id kept`() {
        val g = graph(FakeEngine())
        assertEquals("desktop", g.device.kind)
        assertEquals("Studio", g.device.name)
        val again = DesktopGraph(File(dir, "app"), File(dir, "music"), FakeEngine(), scope, "Studio")
        assertEquals(g.device.id, again.device.id)
    }
}
