package com.example.cattlemonitor

import com.example.cattlemonitor.data.Cow
import com.example.cattlemonitor.data.CowStatus
import com.example.cattlemonitor.ui.status.StatusText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class StatusTextTest {
    private val now = Date(1_700_000_000_000)

    private fun cow(status: CowStatus, lastSeen: Date? = now) =
        Cow(
            id = "c1",
            name = "Bella",
            deviceId = "esp32-1",
            baselineTemp = 38.0,
            baselineActivity = 1.0,
            baselineSamples = 500,
            status = status,
            lastSeen = lastSeen,
            latestTemp = null,
            latestActivity = null,
        )

    @Test
    fun `normal shows all-clear wording`() {
        assertTrue(StatusText.explain(cow(CowStatus.NORMAL), now).contains("normal"))
    }

    @Test
    fun `alert wording mentions baseline`() {
        assertTrue(StatusText.explain(cow(CowStatus.ALERT), now).contains("baseline"))
    }

    @Test
    fun `offline with no data explains no data received`() {
        val text = StatusText.explain(cow(CowStatus.OFFLINE, lastSeen = null), now)
        assertTrue(text.contains("No data received yet"))
    }

    @Test
    fun `offline with stale last-seen mentions range`() {
        val text = StatusText.explain(
            cow(CowStatus.OFFLINE, lastSeen = Date(now.time - 3_600_000)),
            now,
        )
        assertTrue(text.contains("out of range"))
    }
}
