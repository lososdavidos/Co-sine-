package app.sine.desktop.player

import okhttp3.OkHttpClient
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avformat.Write_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_version
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_version
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_UNSPEC
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_compare
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.global.swresample.swresample_version
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.LongPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Plays through FFmpeg, inside Sine (Q84): its libraries come in the
 * installer, so there's nothing else to install. Sine reads the song's
 * bytes itself ([ByteSource]), FFmpeg decodes them as they're stored
 * (never transcoded: NG7), and the sound goes out through Java's own
 * output ([Sink]) as 48 kHz stereo.
 *
 * One song plays at a time, each on its own thread. A new song, a pause or
 * a seek is heard at once: the output holds a fifth of a second.
 */
class FfmpegEngine(
    private val http: OkHttpClient,
    private val output: () -> Sink = Sink::speakers,
) : AudioEngine {
    override var onEnded: () -> Unit = {}
    override var onError: () -> Unit = {}

    @Volatile override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val lock = Any()
    private var sink: Sink? = null
    @Volatile private var playing: Playback? = null

    /** The output, opened with the first song and kept. Null when this computer has none. */
    private fun sink(): Sink? = sink ?: runCatching(output).onFailure {
        System.err.println("Sine: no sound output: $it")
    }.getOrNull()?.also { sink = it }

    override fun load(location: String, startMs: Long, play: Boolean) {
        synchronized(lock) {
            playing?.cancelled = true
            val s = sink()
            if (s == null) {
                playing = null
                handOff { onError() }
                return
            }
            s.stop()
            s.flush()
            playing = Playback(location, startMs.coerceAtLeast(0), paused = !play).also { it.start() }
        }
    }

    override fun play() {
        val p = playing ?: return
        // Played to its end (or broken): play it again, from the start.
        if (p.finished) return load(p.location, 0, play = true)
        p.paused = false
    }

    override fun pause() {
        synchronized(lock) {
            val p = playing ?: return
            p.paused = true
            sink?.stop()
        }
    }

    override fun stop() {
        synchronized(lock) {
            playing?.cancelled = true
            playing = null
            sink?.stop()
            sink?.flush()
        }
    }

    override fun seek(ms: Long) {
        val p = playing ?: return
        val to = ms.coerceAtLeast(0)
        if (p.finished) return load(p.location, to, play = false)
        p.seekTo.set(to)
    }

    override val positionMs: Long
        get() = synchronized(lock) {
            val p = playing ?: return 0
            p.seekTo.get().takeIf { it >= 0 }?.let { return it }
            val heard = (p.written - (sink?.buffered() ?: 0)).coerceAtLeast(0) / Sink.FRAME
            p.baseMs + heard * 1000 / Sink.RATE
        }

    override val durationMs: Long get() = playing?.durationMs ?: 0

    override val isPlaying: Boolean get() = playing?.let { !it.paused && !it.finished } ?: false

    override fun release() {
        stop()
        synchronized(lock) {
            sink?.close()
            sink = null
        }
    }

    /** Calls back off this engine's threads, for the song that's still the one playing. */
    private fun handOff(f: () -> Unit) {
        Thread(f, "sine-audio-event").apply { isDaemon = true }.start()
    }

    /** One song, decoding on its own thread into the output while it's the one playing. */
    private inner class Playback(val location: String, startMs: Long, @Volatile var paused: Boolean) : Thread("sine-audio") {
        @Volatile var cancelled = false
        @Volatile var finished = false
        @Volatile var durationMs = 0L
        val seekTo = AtomicLong(if (startMs > 0) startMs else -1)

        // Under the engine's lock: where the output's bytes start in the song, and how many went in since.
        var baseMs = startMs
        var written = 0L

        init {
            isDaemon = true
        }

        private fun current() = playing === this && !cancelled

        override fun run() {
            var decoder: Decoder? = null
            try {
                decoder = Decoder(ByteSource.open(location, http))
                durationMs = decoder.durationMs
                play(decoder)
            } catch (e: Throwable) {
                if (current()) {
                    System.err.println("Sine: couldn't play $location: $e")
                    finished = true
                    handOff { if (playing === this) onError() }
                }
            } finally {
                decoder?.close()
            }
        }

        private fun play(decoder: Decoder) {
            var chunk: ByteArray? = null
            var off = 0
            var heard = -1L
            var stalledSince = 0L
            while (!cancelled) {
                val to = seekTo.get()
                if (to >= 0) {
                    decoder.seek(to)
                    chunk = null
                    synchronized(lock) {
                        if (!current()) return
                        sink?.flush()
                        baseMs = to
                        written = 0
                        seekTo.compareAndSet(to, -1)
                    }
                    continue
                }
                if (paused) {
                    synchronized(lock) { if (current()) sink?.stop() }
                    heard = -1 // the wait for its end starts again when it plays
                    sleep(15)
                    continue
                }
                if (chunk == null || off >= chunk.size) {
                    chunk = decoder.next(volume)
                    off = 0
                    if (chunk == null) {
                        // The end: once what's in the output has been heard, the song has ended.
                        val left = synchronized(lock) {
                            if (!current()) return
                            sink?.start()
                            sink?.buffered() ?: 0
                        }
                        val now = System.currentTimeMillis()
                        if (left.toLong() != heard) {
                            heard = left.toLong()
                            stalledSince = now
                        }
                        // Some outputs never report quite empty: a buffer that hasn't moved in a second is done.
                        if (left == 0 || now - stalledSince > 1_000) {
                            finished = true
                            handOff { if (playing === this) onEnded() }
                            return
                        }
                        sleep(15)
                        continue
                    }
                }
                val n = synchronized(lock) {
                    if (!current()) return
                    val s = sink ?: return
                    val n = min(s.available(), chunk.size - off) / Sink.FRAME * Sink.FRAME
                    if (n > 0) {
                        s.write(chunk, off, n)
                        off += n
                        written += n
                    }
                    s.start()
                    n
                }
                if (n == 0) sleep(5)
            }
        }
    }

    companion object {
        /** FFmpeg, if its libraries load here (they come with Sine). Null if they don't: Sine then says so. */
        fun create(http: OkHttpClient, output: () -> Sink = Sink::speakers): FfmpegEngine? = runCatching {
            av_log_set_level(AV_LOG_ERROR)
            avformat_version()
            avcodec_version()
            swresample_version()
            FfmpegEngine(http, output)
        }.onFailure { System.err.println("Sine: FFmpeg didn't load: $it") }.getOrNull()
    }
}

/**
 * One song's FFmpeg: its container, its audio stream's decoder, and a
 * resampler to the output's 48 kHz 16-bit stereo. Not thread-safe: one
 * [FfmpegEngine] playback thread uses it.
 */
private class Decoder(source: ByteSource) : AutoCloseable {
    private val id = ids.incrementAndGet()
    private val opaque = LongPointer(1).put(id)
    private var io: AVIOContext? = null
    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var stream = -1
    private lateinit var timeBase: AVRational

    private var swr: SwrContext? = null
    private var swrFormat = -1
    private var swrRate = -1
    private val swrLayout = AVChannelLayout()
    private var out = BytePointer(0L)
    private val outs = PointerPointer<BytePointer>(1)

    /** The container has nothing more: the decoder is being emptied. */
    private var drained = false
    /** After a seek: sound before here is dropped, so it starts where asked, not at the packet before. */
    private var dropBeforeMs = -1L
    var durationMs = 0L
        private set

    init {
        sources[id] = source
        try {
            val buffer = BytePointer(av_malloc(IO_BUFFER.toLong())).capacity(IO_BUFFER.toLong())
            io = avio_alloc_context(buffer, IO_BUFFER, 0, opaque, Read, null as Write_packet_Pointer_BytePointer_int?, Seek)
            val f = avformat_alloc_context()
            f.pb(io)
            f.flags(f.flags() or AVFMT_FLAG_CUSTOM_IO)
            check(avformat_open_input(f, "", null, null as AVDictionary?), "open")
            format = f
            check(avformat_find_stream_info(f, null as PointerPointer<*>?), "find its streams")
            stream = av_find_best_stream(f, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            check(stream, "find its sound")
            val st = f.streams(stream)
            timeBase = st.time_base()
            val decoder = avcodec_find_decoder(st.codecpar().codec_id())
            if (decoder == null || decoder.isNull) throw IOException("no decoder for codec ${st.codecpar().codec_id()}")
            val c = avcodec_alloc_context3(decoder)
            codec = c
            check(avcodec_parameters_to_context(c, st.codecpar()), "set up its decoder")
            c.pkt_timebase(st.time_base())
            check(avcodec_open2(c, decoder, null as AVDictionary?), "open its decoder")
            durationMs = when {
                f.duration() > 0 -> f.duration() / 1000
                st.duration() > 0 -> st.duration() * 1000 * timeBase.num() / timeBase.den()
                else -> 0
            }
        } catch (e: Throwable) {
            close()
            throw failures.remove(id) ?: e
        }
    }

    private fun check(result: Int, what: String) {
        if (result < 0) throw failures.remove(id) ?: IOException("couldn't $what ($result)")
    }

    /** From [ms] on. Formats that can't seek decode through to it. */
    fun seek(ms: Long) {
        val f = format ?: return
        av_seek_frame(f, -1, ms * 1000, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(codec)
        freeSwr()
        drained = false
        dropBeforeMs = ms
    }

    /** The next stretch of sound as output bytes, at [volume]; null at the end. */
    fun next(volume: Float): ByteArray? {
        val c = codec ?: return null
        val f = format ?: return null
        while (true) {
            val got = avcodec_receive_frame(c, frame)
            if (got >= 0) {
                val bytes = convert(volume)
                av_frame_unref(frame)
                if (bytes != null && bytes.isNotEmpty()) return bytes
                continue
            }
            if (got == AVERROR_EOF || drained) return null
            // It wants more: the next packet of its stream.
            val read = av_read_frame(f, packet)
            if (read < 0) {
                failures.remove(id)?.let { throw it }
                // The end, or a broken tail: what's in the decoder still plays.
                drained = true
                avcodec_send_packet(c, null)
                continue
            }
            if (packet.stream_index() == stream) avcodec_send_packet(c, packet)
            av_packet_unref(packet)
        }
    }

    private fun convert(volume: Float): ByteArray? {
        if (swr == null || frame.format() != swrFormat || frame.sample_rate() != swrRate || av_channel_layout_compare(swrLayout, frame.ch_layout()) != 0) {
            freeSwr()
            val inLayout = AVChannelLayout()
            av_channel_layout_copy(inLayout, frame.ch_layout())
            if (inLayout.order() == AV_CHANNEL_ORDER_UNSPEC) {
                val channels = inLayout.nb_channels()
                av_channel_layout_uninit(inLayout)
                av_channel_layout_default(inLayout, channels)
            }
            val outLayout = AVChannelLayout().also { av_channel_layout_default(it, 2) }
            val s = swr_alloc()
            check(swr_alloc_set_opts2(s, outLayout, AV_SAMPLE_FMT_S16, Sink.RATE, inLayout, frame.format(), frame.sample_rate(), 0, null), "set up its resampler")
            check(swr_init(s), "start its resampler")
            swr = s
            swrFormat = frame.format()
            swrRate = frame.sample_rate()
            av_channel_layout_uninit(swrLayout)
            av_channel_layout_copy(swrLayout, frame.ch_layout())
            av_channel_layout_uninit(inLayout)
        }
        val s = swr!!
        val room = swr_get_out_samples(s, frame.nb_samples())
        if (room <= 0) return null
        if (out.capacity() < room.toLong() * Sink.FRAME) {
            out.close()
            out = BytePointer(room.toLong() * Sink.FRAME)
            outs.put(0, out)
        }
        outs.put(0, out)
        val n = swr_convert(s, outs, room, frame.extended_data(), frame.nb_samples())
        if (n <= 0) return null
        var bytes = ByteArray(n * Sink.FRAME)
        out.position(0).get(bytes, 0, bytes.size)

        if (dropBeforeMs >= 0) {
            val ts = frame.best_effort_timestamp()
            if (ts != AV_NOPTS_VALUE) {
                val startMs = ts * 1000 * timeBase.num() / timeBase.den()
                val drop = ((dropBeforeMs - startMs) * Sink.RATE / 1000).coerceAtLeast(0)
                if (drop >= n) return ByteArray(0)
                if (drop > 0) bytes = bytes.copyOfRange((drop * Sink.FRAME).toInt(), bytes.size)
            }
            dropBeforeMs = -1
        }
        if (volume < 0.999f) gain(bytes, volume * volume) // closer to how loud it sounds than a straight line
        return bytes
    }

    private fun gain(bytes: ByteArray, g: Float) {
        var i = 0
        while (i < bytes.size) {
            val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
            val scaled = (sample * g).toInt()
            bytes[i] = scaled.toByte()
            bytes[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }

    private fun freeSwr() {
        swr?.let { swr_free(it) }
        swr = null
    }

    override fun close() {
        freeSwr()
        av_channel_layout_uninit(swrLayout)
        codec?.let { avcodec_free_context(it) }
        codec = null
        format?.let { if (!it.isNull) avformat_close_input(it) }
        format = null
        io?.let {
            // Custom I/O is ours to free: its buffer (which FFmpeg may have swapped), then it.
            av_free(it.buffer())
            avio_context_free(it)
        }
        io = null
        av_packet_free(packet)
        av_frame_free(frame)
        out.close()
        sources.remove(id)?.close()
        failures.remove(id)
    }

    companion object {
        private const val IO_BUFFER = 64 * 1024
        private const val EIO = -5 // AVERROR(EIO)
        private const val AVFMT_FLAG_CUSTOM_IO = 0x0080 // avformat.h: the I/O is the caller's
        private val ids = AtomicLong()
        private val sources = ConcurrentHashMap<Long, ByteSource>()
        private val failures = ConcurrentHashMap<Long, Throwable>()
        private val scratch = ThreadLocal.withInitial { ByteArray(IO_BUFFER) }

        // FFmpeg calls these to read a song's bytes. One each for every song (JavaCPP has few callback slots),
        // finding the song's source by the id it was opened with.
        private object Read : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer, buf: BytePointer, size: Int): Int {
                val id = LongPointer(opaque).get()
                val source = sources[id] ?: return AVERROR_EOF
                return try {
                    var tmp = scratch.get()
                    if (tmp.size < size) tmp = ByteArray(size).also { scratch.set(it) }
                    val n = source.read(tmp, size)
                    if (n <= 0) return AVERROR_EOF
                    buf.capacity(size.toLong()).position(0).put(tmp, 0, n)
                    n
                } catch (e: Throwable) {
                    failures[id] = e
                    EIO
                }
            }
        }

        private object Seek : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer, offset: Long, whence: Int): Long {
                val id = LongPointer(opaque).get()
                val source = sources[id] ?: return -1
                return try {
                    val target = when (whence and AVSEEK_FORCE.inv()) {
                        AVSEEK_SIZE -> return source.size
                        0 -> offset // SEEK_SET
                        1 -> source.position + offset // SEEK_CUR
                        2 -> if (source.size < 0) return -1 else source.size + offset // SEEK_END
                        else -> return -1
                    }
                    if (source.seek(target)) target else -1
                } catch (e: Throwable) {
                    failures[id] = e
                    -1
                }
            }
        }
    }
}
