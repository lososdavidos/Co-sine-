package app.sine.desktop.player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Where decoded sound goes: 16-bit stereo at [RATE], little-endian. Writes
 * never wait; the engine offers what fits in [available].
 */
interface Sink {
    /** Bytes that can be written now without waiting. */
    fun available(): Int
    fun write(bytes: ByteArray, off: Int, len: Int)
    /** Bytes written that haven't been heard yet. */
    fun buffered(): Int
    fun start()
    fun stop()
    /** Drops what's buffered: after a seek or a new song. */
    fun flush()
    fun close()

    companion object {
        const val RATE = 48_000
        const val FRAME = 4 // two channels of two bytes
        val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)

        /** The computer's sound output, through Java's own: nothing to install. */
        fun speakers(): Sink = LineSink()
    }
}

private class LineSink : Sink {
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(Sink.format).apply {
        // A fifth of a second: little enough that a pause, a seek or a new song is heard at once.
        open(Sink.format, Sink.RATE / 5 * Sink.FRAME)
    }

    override fun available() = line.available()
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        line.write(bytes, off, len)
    }
    override fun buffered() = (line.bufferSize - line.available()).coerceAtLeast(0)
    override fun start() {
        if (!line.isRunning) line.start()
    }
    override fun stop() {
        if (line.isRunning) line.stop()
    }
    override fun flush() = line.flush()
    override fun close() = line.close()
}

/**
 * Plays nothing, in real time: what it's given drains as speakers would.
 * For tests.
 */
class ClockSink(private val capacity: Int = Sink.RATE / 5 * Sink.FRAME) : Sink {
    private var held = 0L
    private var since = 0L
    private var running = false

    @Synchronized private fun drain() {
        if (!running) return
        val now = System.nanoTime()
        val frames = (now - since) * Sink.RATE / 1_000_000_000L
        if (frames > 0) {
            held = (held - frames * Sink.FRAME).coerceAtLeast(0)
            since += frames * 1_000_000_000L / Sink.RATE
        }
    }

    @Synchronized override fun available(): Int {
        drain()
        return (capacity - held).toInt()
    }
    @Synchronized override fun write(bytes: ByteArray, off: Int, len: Int) {
        drain()
        held += len
    }
    @Synchronized override fun buffered(): Int {
        drain()
        return held.toInt()
    }
    @Synchronized override fun start() {
        if (running) return
        running = true
        since = System.nanoTime()
    }
    @Synchronized override fun stop() {
        drain()
        running = false
    }
    @Synchronized override fun flush() {
        held = 0
        since = System.nanoTime()
    }
    override fun close() {}
}
