package app.sine.core.model

import app.sine.core.cosine.SmartPlaylist

/**
 * The rules a smart playlist can have (§2.4): Cosine's views over your own
 * library and plays, the same ones Home's tiles show. Each has at most one
 * setting.
 */
object SmartRules {
    data class Rule(val key: String, val label: String)

    val all = listOf(
        Rule("added", "Recently added"),
        Rule("most-played", "Most played"),
        Rule("recent", "Recently played"),
        Rule("forgotten", "Forgotten"),
        Rule("never-played", "Never played"),
        Rule("this-time-last-year", "This time last year"),
        Rule("liked", "Liked songs"),
    )

    /** most-played's periods. */
    val spans = listOf("30d" to "Last 30 days", "year" to "This year", "all" to "All time")
    /** forgotten: not played in this many months. */
    val months = listOf(3, 6, 12, 24)
    /** added: in the last this many days. */
    val days = listOf(7, 30, 90, 365)

    fun label(rule: String) = all.firstOrNull { it.key == rule }?.label ?: rule

    /** A new rule with its usual setting. */
    fun starting(rule: String, name: String = label(rule)) = withSetting(SmartPlaylist(name = name, rule = rule))

    /** Keeps only the rule's own setting, filling in the usual one. */
    fun withSetting(p: SmartPlaylist): SmartPlaylist = when (p.rule) {
        "most-played" -> p.copy(span = p.span ?: "year", months = null, days = null)
        "forgotten" -> p.copy(span = null, months = p.months ?: 6, days = null)
        "added" -> p.copy(span = null, months = null, days = p.days ?: 30)
        else -> p.copy(span = null, months = null, days = null)
    }

    fun monthsLabel(n: Int) = if (n == 1) "1 month" else if (n % 12 == 0) (n / 12).let { if (it == 1) "1 year" else "$it years" } else "$n months"
    fun daysLabel(n: Int) = when (n) { 7 -> "A week"; 30 -> "A month"; 90 -> "3 months"; 365 -> "A year"; else -> "$n days" }
}
