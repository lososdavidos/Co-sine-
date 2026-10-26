package app.sine.core.search

import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class HistoryState(val queries: List<String> = emptyList())

/**
 * What an account searched for lately (#19), newest first, kept on the
 * phone. A search counts once it found something you opened, or you pressed
 * search: not every word typed on the way. The same search again moves to
 * the top, as last typed.
 */
class SearchHistory(file: File, private val keep: Int = 10) {
    private val store = JsonFile(file, HistoryState.serializer()) { HistoryState() }
    private val _queries = MutableStateFlow(store.read().queries)
    val queries: StateFlow<List<String>> = _queries.asStateFlow()

    @Synchronized
    fun add(query: String) {
        val q = query.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
        if (q.isEmpty()) return
        save((listOf(q) + _queries.value.filterNot { it.equals(q, ignoreCase = true) }).take(keep))
    }

    @Synchronized
    fun remove(query: String) = save(_queries.value - query)

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(queries: List<String>) {
        if (queries == _queries.value) return
        store.write(HistoryState(queries))
        _queries.value = queries
    }

    private companion object {
        const val MAX_LENGTH = 200
    }
}
