package com.example.cattlemonitor

import com.example.cattlemonitor.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class TimeFormatTest {
    private val now = Date(1_700_000_000_000)

    @Test
    fun `null is never`() {
        assertEquals("never", TimeFormat.relative(null, now))
    }

    @Test
    fun `under 90 seconds is just now`() {
        assertEquals("just now", TimeFormat.relative(Date(now.time - 89_000), now))
    }

    @Test
    fun `minutes and hours and days`() {
        assertEquals("5m ago", TimeFormat.relative(Date(now.time - 5 * 60_000), now))
        assertEquals("3h ago", TimeFormat.relative(Date(now.time - 3 * 3_600_000), now))
        assertEquals("2d ago", TimeFormat.relative(Date(now.time - 2 * 86_400_000), now))
    }
}
