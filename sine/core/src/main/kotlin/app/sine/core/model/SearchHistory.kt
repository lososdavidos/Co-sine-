package app.sine.core.model

import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** Something opened from search, kept so it can be opened again in one tap. */
@Serializable
data class Recent(
    /** One of [ARTIST], [ALBUM], [PLAYLIST] or [SONG]. */
    val kind: String,
    val id: String,
    val name: String,
    val subtitle: String? = null,
    val coverArt: String? = null,
) {
    companion object {
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SONG = "song"
    }
}

@Serializable
internal data class SavedRecents(val items: List<Recent> = emptyList())

/**
 * What one account opened from search, newest first (§6.8): shown while the
 * field is empty. Opening something again moves it to the top; it holds
 * [LIMIT] at most, and never anything typed, only what was chosen.
 */
class SearchHistory(file: File) {
    private val store = JsonFile(file, SavedRecents.serializer()) { SavedRecents() }
    private val _items = MutableStateFlow(store.read().items)

    val items: StateFlow<List<Recent>> = _items.asStateFlow()

    @Synchronized
    fun opened(item: Recent) = save(listOf(item) + _items.value.filterNot { it.kind == item.kind && it.id == item.id })

    @Synchronized
    fun remove(item: Recent) = save(_items.value.filterNot { it.kind == item.kind && it.id == item.id })

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(items: List<Recent>) {
        val next = items.take(LIMIT)
        if (next == _items.value) return
        store.write(SavedRecents(next))
        _items.value = next
    }

    companion object {
        const val LIMIT = 20
    }
}
