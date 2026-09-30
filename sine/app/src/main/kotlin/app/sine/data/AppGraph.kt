package app.sine.data

import android.content.Context
import app.sine.core.account.Account
import app.sine.core.account.AccountStore
import app.sine.core.account.SettingsStore
import app.sine.core.server.ServerProbe
import app.sine.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies. Small enough that a DI framework would cost more than it saves. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val accounts = AccountStore(File(context.filesDir, "accounts.json"))
    val settings = SettingsStore(File(context.filesDir, "settings.json"))
    val network = NetworkMonitor(context)
    val probe = ServerProbe(http)
    val player = PlayerConnection(context)

    private val sessions = ConcurrentHashMap<String, AccountSession>()

    fun session(account: Account): AccountSession =
        sessions.getOrPut(account.id) { AccountSession(context, account, http, network) }

    fun sessionById(accountId: String): AccountSession? =
        accounts.state.value.accounts.firstOrNull { it.id == accountId }?.let { session(it) }

    val activeSession: StateFlow<AccountSession?> = accounts.state
        .map { state -> state.active?.let { session(it) } }
        .stateIn(scope, SharingStarted.Eagerly, accounts.state.value.active?.let { session(it) })

    /** Forget an account entirely. Downloads are handled by the caller, who asked the user (§6.20). */
    fun removeAccount(account: Account) {
        sessions.remove(account.id)
        accounts.remove(account.id)
        settings.forgetAccount(account.id)
        File(context.filesDir, "accounts/${account.id}").deleteRecursively()
    }

    init {
        // Plays recorded offline go up as soon as there is a network again.
        scope.launch {
            network.state.collect { state ->
                if (state.connected) activeSession.value?.let { s ->
                    s.forceOnlineRetry()
                    runCatching { s.flushPlays() }
                }
            }
        }
    }
}
