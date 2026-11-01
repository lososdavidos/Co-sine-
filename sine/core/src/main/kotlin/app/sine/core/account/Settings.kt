package app.sine.core.account

import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AccountSettings(
    /** Soft budget for pinned downloads (Q33); null = unlimited. */
    val pinnedBudgetBytes: Long? = null,
    /** Follows Android's metered flag, not "is it WiFi" (§2.5). Off: downloads wait for unmetered. */
    val downloadOnMetered: Boolean = false,
)

@Serializable
data class SettingsState(
    /** The user-visible download folder, chosen once through SAF (§2.5). */
    val downloadTreeUri: String? = null,
    val perAccount: Map<String, AccountSettings> = emptyMap(),
    /**
     * Appearance: null, Sine takes the artwork's colours (§6.1); else the
     * name of one of Accents.fixed, kept whatever is on screen.
     */
    val fixedColour: String? = null,
    /** Songs streamed are kept on the phone up to this many bytes, the least recently played going first; 0 keeps none. */
    val streamCacheBytes: Long = 2L shl 30,
    /**
     * Crossfade (Q69): each song fades into the next over this many
     * seconds; 0 is off, the default, and songs play gapless.
     */
    val crossfadeSec: Int = 0,
    /**
     * Prefetch (§2.5): on an unmetered network, the songs coming up are kept
     * ahead of playing them, so they carry on where the signal doesn't.
     */
    val prefetch: Boolean = true,
) {
    fun forAccount(id: String) = perAccount[id] ?: AccountSettings()
}

class SettingsStore(file: File) {
    private val store = JsonFile(file, SettingsState.serializer()) { SettingsState() }
    private val _state = MutableStateFlow(store.read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    @Synchronized
    fun setDownloadTree(uri: String?) = update { it.copy(downloadTreeUri = uri) }

    @Synchronized
    fun updateAccount(id: String, f: (AccountSettings) -> AccountSettings) =
        update { it.copy(perAccount = it.perAccount + (id to f(it.forAccount(id)))) }

    @Synchronized
    fun setStreamCache(bytes: Long) = update { it.copy(streamCacheBytes = bytes.coerceAtLeast(0)) }

    @Synchronized
    fun setCrossfade(seconds: Int) = update { it.copy(crossfadeSec = seconds.coerceIn(0, MAX_CROSSFADE_SEC)) }

    @Synchronized
    fun setPrefetch(on: Boolean) = update { it.copy(prefetch = on) }

    @Synchronized
    fun setFixedColour(name: String?) = update { it.copy(fixedColour = name) }

    @Synchronized
    fun forgetAccount(id: String) = update { it.copy(perAccount = it.perAccount - id) }

    companion object {
        const val MAX_CROSSFADE_SEC = 12
    }

    private fun update(f: (SettingsState) -> SettingsState) {
        val next = f(_state.value)
        store.write(next)
        _state.value = next
    }
}
