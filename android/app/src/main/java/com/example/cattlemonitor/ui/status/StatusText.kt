package com.example.cattlemonitor.ui.status

import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import java.util.Date

/**
 * Locale-independent explanation logic: picks the explanation case and the
 * last-seen suffix bucket for a cow. The UI renders these with string
 * resources (ui/common/Strings.kt statusExplanation); the English rendering
 * below stays as a reference implementation pinned by StatusTextTest.
 */
object StatusText {

    enum class ExplainCase { NORMAL, WARNING, ALERT, OFFLINE_NO_DATA, OFFLINE_STALE }
    enum class LastSeenBucket { NONE, MINUTES, HOURS, DAYS }

    data class Explanation(val case: ExplainCase, val lastSeen: LastSeenBucket, val minutesAgo: Long = 0)

    fun explainParts(cow: Cow, now: Date = Date()): Explanation {
        val case = when (cow.status) {
            CowStatus.NORMAL -> ExplainCase.NORMAL
            CowStatus.WARNING -> ExplainCase.WARNING
            CowStatus.ALERT -> ExplainCase.ALERT
            CowStatus.OFFLINE ->
                if (cow.lastSeen == null) ExplainCase.OFFLINE_NO_DATA
                else ExplainCase.OFFLINE_STALE
        }
        val lastSeen = cow.lastSeen ?: return Explanation(case, LastSeenBucket.NONE)
        val m = (now.time - lastSeen.time) / 60_000
        return when {
            m < 2 -> Explanation(case, LastSeenBucket.NONE)
            m < 120 -> Explanation(case, LastSeenBucket.MINUTES, m)
            m < 2880 -> Explanation(case, LastSeenBucket.HOURS, m)
            else -> Explanation(case, LastSeenBucket.DAYS, m)
        }
    }

    /** English rendering — pinned by StatusTextTest. */
    fun explain(cow: Cow, now: Date = Date()): String {
        val p = explainParts(cow, now)
        val base = when (p.case) {
            ExplainCase.NORMAL ->
                "All readings normal — temperature and activity near this cow's baseline."
            ExplainCase.WARNING ->
                "Worth watching: readings have moved away from this cow's baseline."
            ExplainCase.ALERT ->
                "Possible health issue detected — temperature or movement is well outside baseline."
            ExplainCase.OFFLINE_NO_DATA ->
                "No data received yet from this device."
            ExplainCase.OFFLINE_STALE ->
                "No readings for a while — device may be powered off or out of range."
        }
        // Newline (not a space): the last-seen line is its own sentence —
        // gluing it onto "...out of range." read as one run-on sentence.
        val suffix = when (p.lastSeen) {
            LastSeenBucket.NONE -> ""
            LastSeenBucket.MINUTES -> "\nLast reading ${p.minutesAgo}m ago."
            LastSeenBucket.HOURS -> "\nLast reading ${p.minutesAgo / 60}h ago."
            LastSeenBucket.DAYS -> "\nLast reading ${p.minutesAgo / 1440}d ago."
        }
        return base + suffix
    }
}
