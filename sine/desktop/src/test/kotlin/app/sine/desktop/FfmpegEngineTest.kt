package app.sine.desktop

import app.sine.desktop.player.FfmpegEngine
import app.sine.desktop.player.Speaker
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FFmpeg itself, bundled: a short tone decodes, plays in real time, pauses, seeks and ends. No sound card needed. */
class FfmpegEngineTest {
    /** A sound output that keeps time like one, and remembers what it heard. */
    private class ClockSpeaker : Speaker {
        private var frameBytes = 4
        private var rate = 44_100f
        @Volatile private var running = false
        @Volatile private var flushed = false
        @Volatile override var framesPlayed = 0L
        @Volatile var loudest = 0
        private val lock = Object()

        override fun open(format: AudioFormat) {
            frameBytes = format.frameSize
            rate = format.frameRate
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            flushed = false
            var done = 0
            while (done < length) {
                synchronized(lock) { while (!running && !flushed) lock.wait(50) }
                if (flushed || !running) return done
                val chunk = minOf(length - done, (rate / 100).toInt() * frameBytes) // 10 ms at a time
                for (i in offset + done until offset + done + chunk step 2) {
                    val s = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                    loudest = maxOf(loudest, kotlin.math.abs(s.toShort().toInt()))
                }
                Thread.sleep(10)
                framesPlayed += chunk / frameBytes
                done += chunk
            }
            return done
        }

        override fun start() = synchronized(lock) { running = true; lock.notifyAll() }
        override fun stop() { running = false }
        override fun flush() = synchronized(lock) { flushed = true; lock.notifyAll() }
        override fun drain() {}
        override fun close() {}
    }

    /** [seconds] of A, as a WAV file's bytes. */
    private fun tone(seconds: Int): ByteArray {
        val rate = 44_100
        val samples = rate * seconds
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) data.putShort((sin(2 * PI * 440 * i / rate) * 8_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        return header.array() + data.array()
    }

    /** Waits for sound past [ms]: the first song also waits for FFmpeg to load. */
    private fun FfmpegEngine.heardPast(ms: Long) {
        val until = System.currentTimeMillis() + 10_000
        while (positionMs <= ms && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(positionMs > ms, "it never got past $ms: $positionMs")
    }

    private fun file(bytes: ByteArray): File = Files.createTempFile("sine-tone", ".wav").toFile().apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    @Test
    fun `plays a song to its end, and pauses on the way`() {
        val speaker = ClockSpeaker()
        val engine = FfmpegEngine { speaker }
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        try {
            engine.load(file(tone(2)).path, 0, play = true)
            engine.heardPast(300)
            assertTrue(engine.isPlaying, "playing")
            assertTrue(engine.positionMs > 0, "moving: ${engine.positionMs}")
            assertTrue(engine.durationMs in 1_900..2_100, "its length: ${engine.durationMs}")
            assertTrue(speaker.loudest > 5_000, "heard the tone (8000 in one channel is 5657 in each of two): ${speaker.loudest}")
            engine.pause()
            Thread.sleep(200)
            val held = engine.positionMs
            Thread.sleep(300)
            assertEquals(held, engine.positionMs, "paused stays put")
            assertTrue(!engine.isPlaying)
            engine.play()
            assertTrue(ended.await(5, TimeUnit.SECONDS), "it ended")
            Thread.sleep(50)
            assertTrue(engine.positionMs >= 1_900, "ended at its end, not back at 0: ${engine.positionMs}")
        } finally {
            engine.release()
        }
    }

    @Test
    fun `starts partway in, and seeks back`() {
        val engine = FfmpegEngine { ClockSpeaker() }
        try {
            engine.load(file(tone(6)).path, 2_000, play = true)
            engine.heardPast(2_000)
            val seen = (1..10).map { Thread.sleep(100); engine.positionMs }
            assertTrue(seen.first() >= 2_000, "from where asked: $seen")
            assertTrue(seen.last() > seen.first(), "and on from there: $seen")
            engine.seek(0)
            Thread.sleep(200)
            assertTrue(engine.positionMs in 1..1_000, "back at the start: ${engine.positionMs}")
        } finally {
            engine.release()
        }
    }

    @Test
    fun `quieter when turned down`() {
        val speaker = ClockSpeaker()
        val engine = FfmpegEngine { speaker }
        try {
            engine.volume = 0.25f
            engine.load(file(tone(2)).path, 0, play = true)
            engine.heardPast(200)
            assertTrue(speaker.loudest in 1..1_500, "a quarter as loud (of 5657): ${speaker.loudest}")
        } finally {
            engine.release()
        }
    }

    @Test
    fun `streams from the server, and says when it can't`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(Buffer().write(tone(1))).addHeader("Content-Type", "audio/wav").build())
            server.start()
            val ended = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val engine = FfmpegEngine { ClockSpeaker() }
            engine.onEnded = { ended.countDown() }
            engine.onError = { failed.countDown() }
            try {
                engine.load(server.url("/rest/stream?id=tr-1").toString(), 0, play = true)
                assertTrue(ended.await(5, TimeUnit.SECONDS), "a stream plays to its end")
                engine.load(File("no such song.flac").absolutePath, 0, play = true)
                assertTrue(failed.await(5, TimeUnit.SECONDS), "a missing file is an error")
            } finally {
                engine.release()
            }
        }
    }
}
