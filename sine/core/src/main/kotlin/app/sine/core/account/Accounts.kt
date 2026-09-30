package app.sine.core.account

import app.sine.core.persist.JsonFile
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * One login on one server. Sine holds several at once and switches between them
 * explicitly (§2.1); each has its own downloads and its own settings. No merged view.
 */
@Serializable
data class Account(
    val id: String,
    val serverUrl: String,
    val username: String,
    val token: String,
    val salt: String,
    val kind: ServerKind,
    val addedAt: Long,
) {
    val credentials get() = SubsonicCredentials(username, token, salt)
    val isCosine get() = kind is ServerKind.Cosine
    val displayName get() = "$username @ ${serverUrl.substringAfter("://")}"

    companion object {
        fun create(serverUrl: String, credentials: SubsonicCredentials, kind: ServerKind, now: Long) =
            Account(UUID.randomUUID().toString(), serverUrl, credentials.username,
                credentials.token, credentials.salt, kind, now)
    }
}

@Serializable
data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val activeId: String? = null,
) {
    val active: Account? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    /**
     * The account a share-sheet URL goes to (§6.10): compat accounts cannot
     * ingest, so they are never candidates; among Cosine accounts the most
     * recently added wins. No setting, no picker.
     */
    val shareTarget: Account? get() = accounts.filter { it.isCosine }.maxByOrNull { it.addedAt }
}

class AccountStore(file: File) {
    private val store = JsonFile(file, AccountsState.serializer()) { AccountsState() }
    private val _state = MutableStateFlow(store.read())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    @Synchronized
    fun add(account: Account) = update {
        it.copy(accounts = it.accounts + account, activeId = account.id)
    }

    @Synchronized
    fun remove(id: String) = update { s ->
        val remaining = s.accounts.filterNot { it.id == id }
        s.copy(accounts = remaining, activeId = if (s.activeId == id) remaining.firstOrNull()?.id else s.activeId)
    }

    /** Replaces an account's stored details, e.g. when its server's capabilities change (§9.3). */
    @Synchronized
    fun update(account: Account) = update { s ->
        s.copy(accounts = s.accounts.map { if (it.id == account.id) account else it })
    }

    @Synchronized
    fun setActive(id: String) = update { s ->
        require(s.accounts.any { it.id == id }) { "No account $id" }
        s.copy(activeId = id)
    }

    private fun update(f: (AccountsState) -> AccountsState) {
        val next = f(_state.value)
        store.write(next)
        _state.value = next
    }
}
