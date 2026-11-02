package app.sine.desktop

import app.sine.desktop.player.ClockSink
import app.sine.desktop.player.FfmpegEngine
import app.sine.desktop.player.Sink
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FFmpeg itself, as it comes with Sine: songs in the formats a library
 * holds play, move on, pause, seek and end, from a file or a server. The
 * output is a clock, not speakers, that keeps what it's given to check.
 */
class FfmpegEngineTest {
    private val http = OkHttpClient()
    private val heard = RecordingSink()
    private val engine = FfmpegEngine.create(http) { heard }!!
    private var server: MockWebServer? = null

    @AfterTest
    fun done() {
        engine.release()
        server?.close()
    }

    /** Keeps what plays, as it's given: to hear what came out. */
    class RecordingSink(private val clock: ClockSink = ClockSink()) : Sink by clock {
        val bytes = ByteArrayOutputStream()
        @Synchronized override fun write(bytes: ByteArray, off: Int, len: Int) {
            clock.write(bytes, off, len)
            this.bytes.write(bytes, off, len)
        }

        /** The left channel's pitch, from how often it crosses zero, over what's been played. */
        @Synchronized fun pitch(): Double {
            val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val samples = b.remaining() / Sink.FRAME
            var crossings = 0
            var last = 0
            for (i in 0 until samples) {
                val s = b.getShort(i * Sink.FRAME).toInt()
                if (i > 0 && (last < 0) != (s < 0)) crossings++
                last = s
            }
            return crossings / 2.0 / (samples.toDouble() / Sink.RATE)
        }

        @Synchronized fun loudest(): Int {
            val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until b.remaining() / 2).maxOfOrNull { abs(b.getShort(it * 2).toInt()) } ?: 0
        }
    }

    /** [seconds] of A, as a mono 44.1 kHz WAV file. */
    private fun wav(seconds: Int = 2): File {
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

    /** One of the three-second tones in test resources, as a file. */
    private fun tone(name: String): File = Files.createTempFile("sine-", "-$name").toFile().apply {
        writeBytes(FfmpegEngineTest::class.java.getResourceAsStream("/tones/$name")!!.readBytes())
        deleteOnExit()
    }

    @Test
    fun `plays a song to its end`() {
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        engine.load(wav().path, 0, play = true)
        Thread.sleep(800)
        assertTrue(engine.isPlaying, "playing")
        assertTrue(engine.positionMs in 300..1_000, "moving: ${engine.positionMs}")
        assertTrue(engine.durationMs in 1_900..2_100, "its length: ${engine.durationMs}")
        engine.pause()
        Thread.sleep(100)
        val held = engine.positionMs
        Thread.sleep(300)
        assertEquals(held, engine.positionMs, "paused stays put")
        assertTrue(!engine.isPlaying)
        engine.play()
        assertTrue(ended.await(5, TimeUnit.SECONDS), "it ended")
        assertTrue(!engine.isPlaying)
        assertTrue(engine.positionMs in 1_900..2_100, "at its end: ${engine.positionMs}")
        assertTrue(abs(heard.pitch() - 440) < 5, "an A: ${heard.pitch()}")
    }

    @Test
    fun `every format a library holds plays as stored`() {
        for (name in listOf("a440.flac", "a440.opus", "a440.m4a", "a440.mp3", "a440.ogg", "a440-alac.m4a")) {
            heard.bytes.reset()
            engine.load(tone(name).path, 0, play = true)
            Thread.sleep(700)
            assertTrue(engine.isPlaying, "$name plays")
            assertTrue(engine.durationMs in 2_900..3_100, "$name's length: ${engine.durationMs}")
            assertTrue(abs(heard.pitch() - 440) < 6, "$name is an A: ${heard.pitch()}")
            assertTrue(heard.loudest() > 2_000, "$name is heard: ${heard.loudest()}")
        }
    }

    @Test
    fun `starts partway in, and seeks`() {
        engine.load(tone("a440.flac").path, 2_000, play = true)
        val seen = (1..6).map { Thread.sleep(100); engine.positionMs }
        assertTrue(seen.first() >= 2_000, "from where asked: $seen")
        assertTrue(seen.last() > seen.first(), "and on from there: $seen")
        engine.seek(500)
        assertEquals(500, engine.positionMs, "where it was sent, at once")
        Thread.sleep(400)
        assertTrue(engine.positionMs in 600..1_100, "and on from there: ${engine.positionMs}")
    }

    @Test
    fun `loads paused, and plays when asked`() {
        engine.load(tone("a440.mp3").path, 1_000, play = false)
        Thread.sleep(400)
        assertTrue(!engine.isPlaying)
        assertEquals(1_000, engine.positionMs)
        assertTrue(engine.durationMs > 0, "its length is known")
        engine.play()
        Thread.sleep(400)
        assertTrue(engine.isPlaying)
        assertTrue(engine.positionMs > 1_100, "moving: ${engine.positionMs}")
    }

    @Test
    fun `volume`() {
        engine.volume = 0.5f
        engine.load(tone("a440.flac").path, 0, play = true)
        Thread.sleep(500)
        // The tone peaks at an eighth of full scale; at half volume, a quarter of that.
        assertTrue(heard.loudest() in 700..1_300, "quieter: ${heard.loudest()}")
    }

    @Test
    fun `streams from a server, seeking with ranges`() {
        val song = FfmpegEngineTest::class.java.getResourceAsStream("/tones/a440.m4a")!!.readBytes()
        val ranges = mutableListOf<String?>()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.headers["Range"]
                    synchronized(ranges) { ranges += range }
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    return MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes $from-${song.size - 1}/${song.size}")
                        .body(Buffer().write(song, from, song.size - from))
                        .build()
                }
            }
            start()
        }
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        engine.load(server!!.url("/rest/stream?id=1").toString(), 0, play = true)
        Thread.sleep(500)
        assertTrue(engine.isPlaying, "playing")
        assertTrue(engine.durationMs in 2_900..3_100, "its length: ${engine.durationMs}")
        engine.seek(2_500)
        assertTrue(ended.await(3, TimeUnit.SECONDS), "it ended")
        // The seek splices the tone, which counts a few crossings more.
        assertTrue(abs(heard.pitch() - 440) < 12, "an A: ${heard.pitch()}")
        assertTrue(synchronized(ranges) { ranges.first() } == "bytes=0-")
    }

    @Test
    fun `a broken file says so`() {
        val failed = CountDownLatch(1)
        engine.onError = { failed.countDown() }
        val junk = Files.createTempFile("sine-junk", ".flac").toFile().apply { writeBytes(ByteArray(5_000) { it.toByte() }); deleteOnExit() }
        engine.load(junk.path, 0, play = true)
        assertTrue(failed.await(5, TimeUnit.SECONDS), "it failed")
        assertTrue(!engine.isPlaying)
    }

    @Test
    fun `a song that isn't there says so`() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(404).build()
            }
            start()
        }
        val failed = CountDownLatch(1)
        engine.onError = { failed.countDown() }
        engine.load(server!!.url("/rest/stream?id=gone").toString(), 0, play = true)
        assertTrue(failed.await(5, TimeUnit.SECONDS), "it failed")
    }

    @Test
    fun `a new song replaces the one playing at once`() {
        engine.load(tone("a440.flac").path, 0, play = true)
        Thread.sleep(300)
        engine.load(tone("a440.ogg").path, 1_000, play = true)
        Thread.sleep(300)
        assertTrue(engine.positionMs in 1_100..1_600, "the new one, from where asked: ${engine.positionMs}")
        engine.stop()
        assertTrue(!engine.isPlaying)
    }
}
