package app.sine.desktop

import app.sine.desktop.player.VlcEngine
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** libVLC itself, where it's installed: a short tone plays, moves on, and ends. Skipped without VLC. */
class VlcEngineTest {
    /** [seconds] of A, as a WAV file. */
    private fun tone(seconds: Int = 2): File {
        val rate = 44_100
        val samples = rate * seconds
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) data.putShort((sin(2 * PI * 440 * i / rate) * 8_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        return Files.createTempFile("sine-tone", ".wav").toFile().apply {
            writeBytes(header.array() + data.array())
            deleteOnExit()
        }
    }

    @Test
    fun `plays a song to its end`() {
        val engine = VlcEngine.create("--aout=dummy")
        assumeTrue("VLC isn't installed here", engine != null)
        engine!!
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        try {
            engine.load(tone().path, 0, play = true)
            Thread.sleep(800)
            assertTrue(engine.isPlaying, "playing")
            assertTrue(engine.positionMs > 0, "moving: ${engine.positionMs}")
            assertTrue(engine.durationMs in 1_900..2_100, "its length: ${engine.durationMs}")
            engine.pause()
            Thread.sleep(300)
            val held = engine.positionMs
            Thread.sleep(300)
            assertTrue(engine.positionMs == held, "paused stays put")
            engine.play()
            assertTrue(ended.await(5, TimeUnit.SECONDS), "it ended")
        } finally {
            engine.release()
        }
    }

    @Test
    fun `starts partway in`() {
        val engine = VlcEngine.create("--aout=dummy")
        assumeTrue("VLC isn't installed here", engine != null)
        engine!!
        try {
            engine.load(tone(6).path, 2_000, play = true)
            val seen = (1..15).map { Thread.sleep(100); engine.positionMs }
            assertTrue(seen.first() >= 2_000, "from where asked: $seen")
            assertTrue(seen.last() > seen.first(), "and on from there: $seen")
        } finally {
            engine.release()
        }
    }
}
