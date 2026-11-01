package app.sine.core.cosine

import kotlinx.serialization.Serializable

/*
 * Devices and their playback sessions (§4A, §4B): each install of Sine, and
 * each Subsonic app, keeps its own queue and position on Cosine, so playback
 * picks up on another.
 */

@Serializable
data class DeviceInfo(val id: String, val name: String, val kind: String, val lastSeen: String = "")

/** What a Device is playing: its queue as song ids in the order they play, where it is, how far in. */
@Serializable
data class PlaySession(
    val queue: List<String> = emptyList(),
    val current: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val updatedAt: String = "",
    /** Its media volume, 0 to 1; null when it doesn't say (§4B). */
    val volume: Double? = null,
)

/** A Device and the song it's at, for picking up where it left off. */
@Serializable
data class DeviceNow(
    val device: DeviceInfo,
    val track: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val coverArt: String? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val songs: Int = 0,
    val updatedAt: String = "",
    /** Its media volume, 0 to 1; null when it doesn't say. */
    val volume: Double? = null,
    /** There now, and can be controlled (§4B). */
    val listening: Boolean = false,
)

/**
 * Something one Device tells another to do (§4B): play, pause, toggle,
 * next, previous, seek ([positionMs]), skip (to [index] in its queue),
 * volume ([volume], 0 to 1), or queue: play [queue] from [index] at [positionMs].
 */
@Serializable
data class RemoteCommand(
    val type: String,
    val positionMs: Long = 0,
    val index: Int = 0,
    val volume: Double = 0.0,
    val queue: List<String> = emptyList(),
    val from: String = "",
)
