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
)
