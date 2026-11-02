package app.sine.desktop.player

/**
 * What plays the sound: one song at a time, from a file or a URL. The
 * player decides what plays when; this only plays it. On the desktop that's
 * [FfmpegEngine] (Q84): FFmpeg, inside Sine, plays every format a library
 * holds as it is stored.
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
