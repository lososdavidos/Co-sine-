package app.sine.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * How artists are filed, as a record shop's crates are (§6.8): by name
 * without a leading article, accents set aside, each under its first letter;
 * names that start with a digit or a sign under # at the front. The order
 * and the dividers come from the same key, so they always agree.
 */
object Shelving {
    private val articles = listOf("the ")
    private val marks = Regex("\\p{Mn}+")

    /** The name as it's filed: lower case, no article, no accents. */
    fun key(name: String): String {
        var n = name.trim().lowercase(Locale.ROOT)
        for (a in articles) if (n.startsWith(a) && n.length > a.length) n = n.removePrefix(a)
        return marks.replace(Normalizer.normalize(n, Normalizer.Form.NFD), "")
    }

    /** The divider it's filed under: its first letter, or # for a digit or a sign. */
    fun letter(name: String): String {
        val c = key(name).firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** Artists in shelf order: # first, then by letter and name. */
    val artists: Comparator<Artist> = compareBy<Artist>({ letter(it.name) != "#" }, { key(it.name) }, { it.name })
}
