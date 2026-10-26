package app.sine.core.home

import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class SavedHome(val layout: HomeLayout? = null, val unsent: Boolean = false)

/**
 * One account's Home arrangement, kept on the phone (§6.4). Null until the
 * person changes something: until then Home shows the defaults, worked out
 * afresh. An edit made here is [unsent] until Cosine has it, and while it
 * is, Cosine's copy doesn't replace it: changes made offline go with the
 * next sync.
 */
class HomeStore(file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = JsonFile(file, SavedHome.serializer()) { SavedHome() }
    private var saved = store.read()
    private val _layout = MutableStateFlow(saved.layout)

    val layout: StateFlow<HomeLayout?> = _layout.asStateFlow()

    /** Edited here, and Cosine doesn't have it yet. */
    val unsent: Boolean get() = saved.unsent

    /** Keeps a new arrangement, later than whatever was here. */
    @Synchronized
    fun edit(tiles: List<Tile>) {
        val updated = maxOf(clock(), (saved.layout?.updated ?: 0) + 1)
        save(SavedHome(HomeLayout(tiles, updated), unsent = true))
    }

    /** Cosine has [layout]; if nothing changed here meanwhile, nothing's waiting. */
    @Synchronized
    fun sent(layout: HomeLayout) {
        if (saved.layout == layout) save(saved.copy(unsent = false))
    }

    /** Cosine's copy, from another phone or an earlier install: taken when it's later and nothing here waits. */
    @Synchronized
    fun received(remote: HomeLayout?) {
        if (remote == null || saved.unsent) return
        val local = saved.layout
        if (local == null || remote.updated > local.updated) save(SavedHome(remote, unsent = false))
    }

    private fun save(next: SavedHome) {
        if (next == saved) return
        store.write(next)
        saved = next
        _layout.value = next.layout
    }
}
