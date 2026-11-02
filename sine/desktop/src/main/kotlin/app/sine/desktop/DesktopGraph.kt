package app.sine.desktop

import app.sine.core.account.Account
import app.sine.core.account.AccountStore
import app.sine.core.account.SettingsStore
import app.sine.core.cosine.DeviceInfo
import app.sine.core.persist.JsonFile
import app.sine.core.server.ServerProbe
import app.sine.core.session.AccountSession
import app.sine.desktop.player.AudioEngine
import app.sine.desktop.player.DesktopPlayer
import app.sine.desktop.sync.JamFollower
import app.sine.desktop.sync.RemoteListener
import app.sine.desktop.sync.SessionReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sine on the desktop, wired by hand as on the phone (AppGraph): the same
 * core, the same account sessions, its own player and folders. [dir] holds
 * the app's state; [music] is where downloads go, a folder you can open.
 */
class DesktopGraph(
    val dir: File,
    val music: File,
    engine: AudioEngine?,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    deviceName: String = computerName(),
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val accounts = AccountStore(File(dir, "accounts.json"))
    val settings = SettingsStore(File(dir, "settings.json"))
    val probe = ServerProbe(http)

    /** This install as a Device of its accounts (§4A, §4B): kind desktop, named after the computer. */
    val device: DeviceInfo = run {
        val store = JsonFile(File(dir, "device.json"), DeviceId.serializer()) { DeviceId("") }
        val id = store.read().id.ifEmpty { ("sine-desktop-" + UUID.randomUUID()).also { store.write(DeviceId(it)) } }
        DeviceInfo(id, deviceName, "desktop")
    }

    private val sessions = ConcurrentHashMap<String, AccountSession>()

    /** One session per account for the app's lifetime: two would race on the same files. */
    fun session(account: Account): AccountSession =
        sessions.getOrPut(account.id) {
            AccountSession(File(dir, "accounts"), account, http, connected = { true }, homeDevice = HOME_DEVICE)
        }.also { if (it.account != account) it.replaceAccount(account) }

    fun sessionById(accountId: String): AccountSession? =
        accounts.state.value.accounts.firstOrNull { it.id == accountId }?.let { session(it) }

    val activeSession: StateFlow<AccountSession?> = accounts.state
        .map { state -> state.active?.let { session(it) } }
        .stateIn(scope, SharingStarted.Eagerly, accounts.state.value.active?.let { session(it) })

    val player = DesktopPlayer(engine, scope, ::sessionById, File(dir, "last-played.json"))
    val downloads = Downloads(this)

    /** The window is open: with it, or playing, this computer can be controlled from another device (§4B). */
    val visible = MutableStateFlow(true)

    val reporter = SessionReporter(this)
    val jam = JamFollower(this)

    /** Forget an account: its files here go; its downloads stay in the music folder, which is yours. */
    fun removeAccount(account: Account) {
        sessions.remove(account.id)
        accounts.remove(account.id)
        settings.forgetAccount(account.id)
        File(dir, "accounts/${account.id}").deleteRecursively()
    }

    /** The server declares, the client adapts (§9.3): an upgraded server gains features without a new sign-in. */
    suspend fun refreshCapabilities(session: AccountSession) {
        val cosine = session.cosine ?: return
        val now = runCatching { cosine.describe() }.getOrNull() ?: return
        if (now != session.account.kind) accounts.update(session.account.copy(kind = now))
    }

    /** Starts what runs in the background: Cosine's half of Resume, the remote and the jam. Once. */
    fun start() {
        player.restore()
        reporter.start(scope)
        jam.start(scope)
        RemoteListener(this).start(scope)
        scope.launch {
            activeSession.collect { s ->
                if (s == null) return@collect
                refreshCapabilities(s)
                s.sync()
                runCatching { s.flushPlays() }
            }
        }
    }

    companion object {
        /** The desktop keeps its own Home, apart from the phone's (§6.15). */
        const val HOME_DEVICE = "desktop"

        /** For playing: a song streams for as long as it plays, so no read deadline beyond a stall. */
        val streams: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class DeviceId(val id: String)

/** The computer's name, as another device lists it: "from Studio". */
fun computerName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Computer"

/** Where each system keeps an app's own files, and the music folder downloads go to. */
object Folders {
    private val os = System.getProperty("os.name").lowercase()
    private val home = File(System.getProperty("user.home"))

    val app: File
        get() = when {
            "win" in os -> File(System.getenv("APPDATA") ?: home.path, "Sine")
            "mac" in os -> File(home, "Library/Application Support/Sine")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "sine")
        }

    val music: File get() = File(File(home, "Music"), "Sine")
}
