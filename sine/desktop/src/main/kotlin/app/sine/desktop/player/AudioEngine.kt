package app.sine.desktop.player

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * What plays the sound: one song at a time, from a file or a URL. The
 * player decides what plays when; this only plays it. On the desktop that's
 * FFmpeg, inside Sine (Q84), which plays every format a library holds as it
 * is stored, with nothing to install.
 */
interface AudioEngine {
    /** Loads [location] (a file path or a URL) at [startMs], playing at once or paused. */
    fun load(location: String, startMs: Long, play: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun seek(ms: Long)
    val positionMs: Long
    /** The song's length as the engine knows it, 0 until it does. */
    val durationMs: Long
    val isPlaying: Boolean
    /** 0 to 1. */
    var volume: Float
    /** The song played to its end. Called off the engine's own thread. */
    var onEnded: () -> Unit
    /** It couldn't play: the file or stream is broken or gone. */
    var onError: () -> Unit
    fun release()
}

/**
 * Where decoded sound goes: 16-bit PCM, at the song's rate. [write] blocks
 * while the sound is behind, and returns early when stopped or flushed.
 */
interface Speaker {
    fun open(format: AudioFormat)
    /** How much of [bytes] it took before it was stopped or flushed. */
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun start()
    fun stop()
    fun flush()
    fun drain()
    fun close()
    /** Frames heard since it opened. */
    val framesPlayed: Long
}

/** The computer's sound output, through Java's own audio. Before [open], there's nothing to stop or flush. */
class SystemSpeaker : Speaker {
    @Volatile private var line: SourceDataLine? = null

    override fun open(format: AudioFormat) {
        // A fifth of a second ahead: pause, seek and volume answer that quickly.
        line = AudioSystem.getSourceDataLine(format).apply { open(format, (format.frameRate / 5).toInt() * format.frameSize) }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) = line?.write(bytes, offset, length) ?: 0
    override fun start() { line?.start() }
    override fun stop() { line?.stop() }
    override fun flush() { line?.flush() }
    override fun drain() { line?.drain() }
    override fun close() { line?.close() }
    override val framesPlayed: Long get() = line?.longFramePosition ?: 0
}

/** FFmpeg through javacv, heard through a [Speaker]. */
class FfmpegEngine(private val speaker: () -> Speaker = ::SystemSpeaker) : AudioEngine {
    override var onEnded: () -> Unit = {}
    override var onError: () -> Unit = {}

    @Volatile private var song: Song? = null

    @Volatile override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    override fun load(location: String, startMs: Long, play: Boolean) {
        song?.end()
        song = Song(location, startMs.coerceAtLeast(0), play).also { it.start() }
    }

    override fun play() { song?.carryOn() }
    override fun pause() { song?.hold() }
    override fun stop() {
        song?.end()
        song = null
    }
    override fun seek(ms: Long) { song?.seekTo(ms.coerceAtLeast(0)) }
    override val positionMs: Long get() = song?.positionMs ?: 0
    override val durationMs: Long get() = song?.durationMs ?: 0
    override val isPlaying: Boolean get() = song?.let { it.playing && !it.over } ?: false
    override fun release() = stop()

    /** One song, decoded on its own thread from [location] into a speaker of its own. */
    private inner class Song(private val location: String, startMs: Long, play: Boolean) : Thread("sine-playback") {
        @Volatile var playing = play
        @Volatile var over = false
        @Volatile var durationMs = 0L
        @Volatile private var seekTo = startMs // -1: none asked for
        @Volatile private var baseMs = startMs
        @Volatile private var baseFrames = 0L
        @Volatile private var rate = 0f
        private val out = speaker()
        private val wake = Object()

        init {
            isDaemon = true
        }

        val positionMs: Long
            get() = if (rate == 0f) baseMs else baseMs + ((out.framesPlayed - baseFrames) * 1000 / rate).toLong()

        fun hold() {
            playing = false
            out.stop() // at once: a write in progress returns, and is finished on resume
        }

        fun carryOn() {
            playing = true
            out.start() // also for the song's last moments, waiting to drain
            synchronized(wake) { wake.notifyAll() }
        }

        fun seekTo(ms: Long) {
            seekTo = ms
            out.flush() // what was queued is from before: drop it, and free a blocked write
            synchronized(wake) { wake.notifyAll() }
        }

        fun end() {
            over = true
            out.flush()
            synchronized(wake) { wake.notifyAll() }
        }

        override fun run() {
            var grabber: FFmpegFrameGrabber? = null
            var opened = false
            try {
                grabber = FFmpegFrameGrabber(location).apply {
                    sampleMode = FrameGrabber.SampleMode.SHORT
                    // Stereo always: a 5.1 file plays on two speakers, as everywhere else.
                    audioChannels = 2
                    start()
                }
                durationMs = (grabber.lengthInTime / 1000).coerceAtLeast(0)
                val format = AudioFormat(grabber.sampleRate.toFloat(), 16, grabber.audioChannels, true, false)
                out.open(format)
                opened = true
                rate = format.frameRate
                var bytes = ByteArray(0)
                var pending = 0 // bytes of the last frame not yet heard
                var at = 0
                var first = true
                while (!over) {
                    val target = seekTo
                    if (target >= 0) {
                        seekTo = -1
                        if (target > 0 || !first) grabber.timestamp = target * 1000
                        out.flush()
                        baseMs = target
                        baseFrames = out.framesPlayed
                        pending = 0
                    }
                    first = false
                    if (!playing) {
                        out.stop()
                        synchronized(wake) { if (!playing && !over && seekTo < 0) wake.wait() }
                        continue
                    }
                    out.start()
                    if (pending == 0) {
                        val frame = grabber.grabSamples()
                        if (frame == null) {
                            out.drain() // returns early on a seek or an end, which the loop then takes
                            if (over || seekTo >= 0 || !playing) continue
                            over = true
                            handOff(onEnded)
                            break
                        }
                        val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                        val n = samples.remaining()
                        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                        val gain = volume
                        for (i in 0 until n) {
                            val s = (samples.get(samples.position() + i) * gain).toInt()
                            bytes[2 * i] = s.toByte()
                            bytes[2 * i + 1] = (s shr 8).toByte()
                        }
                        pending = n * 2
                        at = 0
                    }
                    val wrote = out.write(bytes, at, pending)
                    at += wrote
                    pending -= wrote
                }
            } catch (e: Exception) {
                if (!over) {
                    over = true
                    handOff(onError)
                }
            } finally {
                runCatching { grabber?.release() }
                if (opened) {
                    baseMs = positionMs // where it stopped stays the position once the speaker is gone
                    rate = 0f
                    runCatching { out.close() }
                }
            }
        }
    }

    private fun handOff(f: () -> Unit) {
        Thread(f, "sine-playback-event").apply { isDaemon = true }.start()
    }

    companion object {
        /** FFmpeg and a sound output, or null when this computer has none to play through: Sine then says so. */
        fun create(): FfmpegEngine? = runCatching {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            AudioSystem.getSourceDataLine(AudioFormat(44_100f, 16, 2, true, false))
            FfmpegEngine()
        }.getOrNull()
    }
}
