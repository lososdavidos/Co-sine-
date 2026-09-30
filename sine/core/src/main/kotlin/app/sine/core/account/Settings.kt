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
    fun forgetAccount(id: String) = update { it.copy(perAccount = it.perAccount - id) }

    private fun update(f: (SettingsState) -> SettingsState) {
        val next = f(_state.value)
        store.write(next)
        _state.value = next
    }
}
