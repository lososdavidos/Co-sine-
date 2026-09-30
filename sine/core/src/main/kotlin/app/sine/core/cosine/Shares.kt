package app.sine.core.cosine

import app.sine.core.persist.JsonFile
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Finds the link in shared text. Share sheets send things like
 * "Listen to Tides by Skeler on #SoundCloud https://on.soundcloud.com/AbC12".
 * The raw link goes to Cosine unfollowed: short links are resolved on the
 * server, not the phone (§6.10). Mirrors Cosine's own rule.
 */
object ShareText {
    fun extractUrl(text: String): String? {
        for (raw in text.split(Regex("\\s+"))) {
            val token = raw.trim('<', '>', '(', ')', '[', ']', '"', '\'')
            val lower = token.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return token
        }
        val t = text.trim()
        if (t.none { it.isWhitespace() }) {
            val dot = t.indexOf('.')
            val slash = t.indexOf('/')
            if (dot > 0 && slash > dot) return "https://$t"
        }
        return null
    }
}

@Serializable
data class PendingShare(val accountId: String, val url: String, val sharedAt: Long)

@Serializable
private data class PendingState(val shares: List<PendingShare> = emptyList())

/**
 * Links shared while offline (§6.10): stored, and submitted on reconnect.
 * Failing a share because the phone is underground is exactly when this matters.
 */
class PendingShares(file: File) {
    private val store = JsonFile(file, PendingState.serializer()) { PendingState() }

    @Synchronized
    fun add(share: PendingShare) {
        val current = store.read().shares
        if (current.none { it.accountId == share.accountId && it.url == share.url }) {
            store.write(PendingState(current + share))
        }
    }

    @Synchronized
    fun forAccount(accountId: String): List<PendingShare> = store.read().shares.filter { it.accountId == accountId }

    @Synchronized
    fun remove(shares: Collection<PendingShare>) {
        store.write(PendingState(store.read().shares - shares.toSet()))
    }

    @Synchronized
    fun forgetAccount(accountId: String) {
        store.write(PendingState(store.read().shares.filterNot { it.accountId == accountId }))
    }

    /** Submits one account's pending links. Anything that fails stays queued. */
    suspend fun flush(accountId: String, submit: suspend (List<String>) -> Unit): Int {
        val pending = forAccount(accountId)
        if (pending.isEmpty()) return 0
        submit(pending.map { it.url })
        remove(pending)
        return pending.size
    }
}
