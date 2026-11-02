package app.sine.desktop

import app.sine.desktop.player.AudioEngine

/** An engine with no sound: it remembers what it was asked to play, and ends songs when told. */
class FakeEngine : AudioEngine {
    val loaded = mutableListOf<String>()
    var startedAt = 0L
    override var positionMs = 0L
    override var durationMs = 0L
    override var isPlaying = false
    override var volume = 1f
    override var onEnded: () -> Unit = {}
    override var onError: () -> Unit = {}

    override fun load(location: String, startMs: Long, play: Boolean) {
        loaded += location
        startedAt = startMs
        positionMs = startMs
        durationMs = 200_000
        isPlaying = play
    }

    override fun play() { isPlaying = true }
    override fun pause() { isPlaying = false }
    override fun stop() { isPlaying = false }
    override fun seek(ms: Long) { positionMs = ms }
    override fun release() {}

    /** The song plays to its end. */
    fun end() {
        positionMs = durationMs
        onEnded()
    }
}
