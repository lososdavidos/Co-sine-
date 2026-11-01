package app.sine.data

import android.content.Context
import java.util.UUID
import app.sine.playback.JamFollower
import app.sine.playback.RemoteListener
import app.sine.playback.SessionReporter
import app.sine.playback.StreamCache
import app.sine.core.cosine.DeviceInfo
import android.os.Build
import app.sine.core.account.Account
import app.sine.core.account.AccountStore
import app.sine.core.account.SettingsStore
import app.sine.core.cosine.PendingShares
import app.sine.core.server.ServerProbe
import app.sine.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
    val pendingShares = PendingShares(File(context.filesDir, "pending-shares.json"))

    /**
     * This install, as a Device of its accounts (§4A, §4B): an id of its own,
     * made once, and the phone's name, so another device can say "from Pixel 8".
     */
    val device: DeviceInfo = context.getSharedPreferences("device", Context.MODE_PRIVATE).let { p ->
        val id = p.getString("id", null) ?: ("sine-" + UUID.randomUUID()).also { p.edit().putString("id", it).apply() }
        DeviceInfo(id, Build.MODEL.ifBlank { "Phone" }, "phone")
    }

    /** Text shared into Sine from another app, waiting to be opened in Add (§6.10). */
    val incomingShare = MutableStateFlow<String?>(null)

    private val sessions = ConcurrentHashMap<String, AccountSession>()

    /** One session per account for the app's lifetime: two would race on the same files. */
    fun session(account: Account): AccountSession =
        sessions.getOrPut(account.id) { AccountSession(context, account, http, network) }
            .also { if (it.account != account) it.replaceAccount(account) }

    fun sessionById(accountId: String): AccountSession? =
        accounts.state.value.accounts.firstOrNull { it.id == accountId }?.let { session(it) }

    val activeSession: StateFlow<AccountSession?> = accounts.state
        .map { state -> state.active?.let { session(it) } }
        .stateIn(scope, SharingStarted.Eagerly, accounts.state.value.active?.let { session(it) })

    /** Forget an account entirely. Downloads are handled by the caller, who asked the user (§6.20). */
    fun removeAccount(account: Account) {
        sessions.remove(account.id)
        pendingShares.forgetAccount(account.id)
        accounts.remove(account.id)
        settings.forgetAccount(account.id)
        File(context.filesDir, "accounts/${account.id}").deleteRecursively()
    }

    /**
     * The server declares, the client adapts (§9.3): re-read a Cosine's
     * capabilities, since an upgraded server gains features without a new login.
     */
    suspend fun refreshCapabilities(session: AccountSession) {
        val cosine = session.cosine ?: return
        val now = runCatching { cosine.describe() }.getOrNull() ?: return
        if (now != session.account.kind) accounts.update(session.account.copy(kind = now))
    }

    /** Submits links shared while offline, for every Cosine account. */
    suspend fun flushPendingShares() {
        for (account in accounts.state.value.accounts.filter { it.isCosine }) {
            val cosine = session(account).cosine ?: continue
            runCatching { pendingShares.flush(account.id) { urls -> cosine.submit(urls) } }
        }
    }

    /** Whether Sine is on screen: with it, or playing, this phone can be controlled from another (§4B). */
    val visible = MutableStateFlow(false)

    /** Tells Cosine what this phone plays (§4A). */
    val reporter = SessionReporter(this)

    /** The jam this phone is in, held to its clock (§4C). */
    val jam = JamFollower(this)

    init {
        StreamCache.init(context, settings.state.value.streamCacheBytes)
        scope.launch { settings.state.collect { StreamCache.setMax(it.streamCacheBytes) } }
        reporter.start(scope)
        jam.start(scope)
        RemoteListener(this).start(scope)
        // Offline work goes up as soon as there is a network again.
        scope.launch {
            network.state.collect { state ->
                if (!state.connected) return@collect
                activeSession.value?.let { s ->
                    s.forceOnlineRetry()
                    runCatching { s.flushPlays() }
                    refreshCapabilities(s)
                    s.sync()
                }
                flushPendingShares()
            }
        }
        scope.launch {
            activeSession.collect { s -> if (s != null) refreshCapabilities(s) }
        }
    }
}
