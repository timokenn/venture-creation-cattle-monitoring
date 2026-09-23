package com.example.cattlemonitor.ui.status

import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import java.util.Date

object StatusText {
    /** Short human explanation shown under the status badge on Cow Detail. */
    fun explain(cow: Cow, now: Date = Date()): String = when (cow.status) {
        CowStatus.NORMAL ->
            "All readings normal — temperature and activity near this cow's baseline."
        CowStatus.WARNING ->
            "Worth watching: readings have moved away from this cow's baseline."
        CowStatus.ALERT ->
            "Possible health issue detected — temperature or movement is well outside baseline."
        CowStatus.OFFLINE ->
            if (cow.lastSeen == null) "No data received yet from this device."
            else "No readings for a while — device may be powered off or out of range."
    } + lastSeenSuffix(cow.lastSeen, now)

    private fun lastSeenSuffix(lastSeen: Date?, now: Date): String =
        if (lastSeen != null) {
            val m = (now.time - lastSeen.time) / 60_000
            when {
                m < 2 -> ""
                m < 120 -> " Last reading ${m}m ago."
                m < 2880 -> " Last reading ${m / 60}h ago."
                else -> " Last reading ${m / 1440}d ago."
            }
        } else ""
}
