package com.example.cattlemonitor.util

import java.util.Date

object TimeFormat {
    /** "just now", "Xm ago", "Xh ago", "Xd ago" — used on every cow card so
     *  cached data is never mistaken for a live reading. */
    fun relative(d: Date?, now: Date = Date()): String {
        if (d == null) return "never"
        val s = (now.time - d.time) / 1000
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }
}
