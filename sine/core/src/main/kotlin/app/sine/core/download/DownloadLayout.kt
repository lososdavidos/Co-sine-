package app.sine.core.download

import app.sine.core.model.Track

/**
 * Where a download goes inside the chosen folder. Mirrors the Store's
 * `Artist / Album / Track` layout (§2.2) so the folder makes sense on its own,
 * with a top-level folder per account so two accounts never collide.
 */
object DownloadLayout {
    const val NOMEDIA = ".nomedia"

    fun segments(accountFolder: String, track: Track): List<String> {
        val number = track.trackNumber?.let { "%02d ".format(it) } ?: ""
        val ext = track.suffix?.takeIf { it.isNotBlank() }?.let { ".${sanitize(it)}" } ?: ""
        return listOf(
            sanitize(accountFolder),
            sanitize(track.artist.ifBlank { "Unknown artist" }),
            sanitize(track.album.ifBlank { track.title }),
            sanitize("$number${track.title}".ifBlank { track.id }) + ext,
        )
    }

    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** Safe on FAT/exFAT SD cards and every desktop OS. */
    fun sanitize(name: String): String {
        val cleaned = name.replace(illegal, "_").trim().trimEnd('.', ' ')
        val safe = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
        return if (safe.length > 120) safe.take(120).trimEnd() else safe
    }
}

/** Q33: the pinned tier has a soft budget that warns rather than refuses — you asked for those files. */
object PinnedBudget {
    sealed interface Check {
        data object Within : Check
        data class Over(val byBytes: Long) : Check
    }

    fun check(usedBytes: Long, addingBytes: Long, budgetBytes: Long?): Check {
        if (budgetBytes == null) return Check.Within
        val over = usedBytes + addingBytes - budgetBytes
        return if (over > 0) Check.Over(over) else Check.Within
    }
}
