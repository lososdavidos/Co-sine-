package app.sine.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * What plays the sound: one song at a time, from a file or a URL. The
 * player decides what plays when; this only plays it. On the desktop that's
 * libVLC (Q84), which plays every format a library holds as it is stored.
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

/** libVLC through vlcj. Null when VLC isn't installed: Sine then says so, and plays nothing. */
class VlcEngine private constructor(private val factory: MediaPlayerFactory) : AudioEngine {
    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    override var onEnded: () -> Unit = {}
    override var onError: () -> Unit = {}

    // Where a song loaded partway in should start: some formats ignore start-time, so it's also sought once playing.
    @Volatile private var startAt = 0L

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // libVLC's own thread: nothing may call back into it from here, so these hand off.
            override fun finished(mediaPlayer: MediaPlayer) = handOff { onEnded() }
            override fun error(mediaPlayer: MediaPlayer) = handOff { onError() }
            override fun playing(mediaPlayer: MediaPlayer) {
                val at = startAt
                startAt = 0
                if (at > 0) mediaPlayer.submit { if (mediaPlayer.status().time() < at - SEEK_SLACK_MS) mediaPlayer.controls().setTime(at) }
            }
        })
    }

    private fun handOff(f: () -> Unit) {
        Thread(f, "sine-vlc-event").apply { isDaemon = true }.start()
    }

    override fun load(location: String, startMs: Long, play: Boolean) {
        startAt = startMs
        val options = buildList {
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
            add(":no-video")
        }.toTypedArray()
        if (play) player.media().play(location, *options) else player.media().startPaused(location, *options)
    }

    override fun play() = player.controls().play()
    override fun pause() = player.controls().setPause(true)
    override fun stop() = player.controls().stop()
    override fun seek(ms: Long) = player.controls().setTime(ms.coerceAtLeast(0))
    override val positionMs: Long get() = player.status().time().coerceAtLeast(0)
    override val durationMs: Long get() = player.status().length().coerceAtLeast(0)
    override val isPlaying: Boolean get() = player.status().isPlaying

    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player.audio().setVolume(Math.round(field * 100))
        }

    override fun release() {
        player.release()
        factory.release()
    }

    companion object {
        private const val SEEK_SLACK_MS = 500L

        /** VLC, if it's installed where vlcj looks (its usual place on each system). [extra]: more of libVLC's options. */
        fun create(vararg extra: String): VlcEngine? = runCatching {
            if (!NativeDiscovery().discover()) return null
            VlcEngine(MediaPlayerFactory("--no-video", "--quiet", "--intf=dummy", *extra))
        }.getOrNull()
    }
}
