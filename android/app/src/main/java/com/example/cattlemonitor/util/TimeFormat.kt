package com.example.cattlemonitor.util

import java.util.Date

object TimeFormat {

    /** Which bucket a timestamp falls into — locale-independent logic. */
    sealed interface Relative {
        data object Never : Relative
        data object JustNow : Relative
        data class Minutes(val n: Long) : Relative
        data class Hours(val n: Long) : Relative
        data class Days(val n: Long) : Relative
    }

    /**
     * Bucket a timestamp for display ("how long ago?"). The UI renders the
     * bucket with locale resources (ui/common/Strings.kt relativeTime); the
     * English string form below is kept as a testable reference implementation
     * used on every cow card so cached data is never mistaken for live data.
     */
    fun relativeParts(d: Date?, now: Date = Date()): Relative {
        if (d == null) return Relative.Never
        val s = (now.time - d.time) / 1000
        return when {
            s < 90 -> Relative.JustNow
            s < 3600 -> Relative.Minutes(s / 60)
            s < 86_400 -> Relative.Hours(s / 3600)
            else -> Relative.Days(s / 86_400)
        }
    }

    /** English rendering of [relativeParts] — pinned by TimeFormatTest. */
    fun relative(d: Date?, now: Date = Date()): String = when (val p = relativeParts(d, now)) {
        Relative.Never -> "never"
        Relative.JustNow -> "just now"
        is Relative.Minutes -> "${p.n}m ago"
        is Relative.Hours -> "${p.n}h ago"
        is Relative.Days -> "${p.n}d ago"
    }
}
