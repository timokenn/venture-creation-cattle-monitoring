package com.example.cattlemonitor.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.Cow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV export per cow (#5): readings + alerts written to cache/exports and
 * handed to the Android share sheet (Drive, WhatsApp, email — user's choice).
 *
 * Built for a HUMAN reader first (farmer/lecturer opens it in Excel):
 *
 *   1. Short preamble — cow, device, export time.
 *   2. DAILY SUMMARY — one row per day: reading count, min/avg/max temp,
 *      alert count. The 10-second overview.
 *   3. READINGS — one row per reading, six plain columns someone can
 *      actually name: time, temperature, activity, battery, alert.
 *      Raw accelerometer/gyroscope values are deliberately NOT exported —
 *      they are machine signals, meaningless in a spreadsheet, and were
 *      what made the earlier 13-column export confusing.
 *
 * Timestamps are device-local "yyyy-MM-dd HH:mm:ss". A UTF-8 BOM is written
 * so Excel renders ° and — correctly on double-click. Fields are quoted per
 * RFC 4180, so alert notes containing commas survive round-tripping.
 */
object CsvExport {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** RFC 4180: quote fields containing comma, quote, or newline; "" escapes "." */
    private fun esc(v: String?): String {
        val s = v ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    private fun num(v: Double?, decimals: Int): String =
        v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""

    fun export(context: Context, cow: Cow, rows: List<ExportLine>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = cow.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "decow_${safeName}_${System.currentTimeMillis()}.csv")

        // --- daily summary: group by local calendar day -----------------------
        // rows arrive sorted by timestamp, so LinkedHashMap keeps day order.
        // Alert-only rows carry no temperature — count the alert, then skip
        // the temp aggregation (else the alerts count would silently be 0).
        class DayStat {
            var count = 0
            var min = Double.NaN
            var max = Double.NaN
            var sum = 0.0
            var alerts = 0
            val avg: Double get() = if (count == 0) Double.NaN else sum / count
        }
        val byDay = linkedMapOf<String, DayStat>()
        for (r in rows) {
            val s = byDay.getOrPut(day.format(r.timestamp)) { DayStat() }
            if (r.alertType != null) s.alerts++
            val t = r.temperature ?: continue
            s.count++
            if (s.count == 1) {
                s.min = t
                s.max = t
            } else {
                s.min = minOf(s.min, t)
                s.max = maxOf(s.max, t)
            }
            s.sum += t
        }

        file.bufferedWriter().use { w ->
            w.append('\uFEFF') // UTF-8 BOM — Excel detects encoding on double-click
            w.appendLine("DeCow data export")
            w.appendLine("cow: ${cow.name}")
            w.appendLine("device_id: ${cow.deviceId}")
            w.appendLine("exported: ${stamp.format(Date())} (device local time)")
            w.appendLine()
            w.appendLine("DAILY SUMMARY")
            w.appendLine("date,readings,temp_min_c,temp_avg_c,temp_max_c,alerts")
            for ((d, s) in byDay) {
                w.appendLine(
                    listOf(d, s.count, num(s.min, 1), num(s.avg, 1), num(s.max, 1), s.alerts)
                        .joinToString(","),
                )
            }
            w.appendLine()
            w.appendLine("READINGS")
            w.appendLine("timestamp,temperature_c,activity_0_100,battery_pct,alert_type,alert_note")
            for (r in rows) {
                w.appendLine(
                    listOf(
                        stamp.format(r.timestamp),
                        num(r.temperature, 2),
                        // Same 0–100 scale the app's charts show (not the raw 0–1 ratio).
                        num(r.activityIndex?.toActivityIndex(), 1),
                        num(r.battery, 0),
                        esc(r.alertType),
                        esc(r.alertNote),
                    ).joinToString(","),
                )
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DeCow — ${cow.name} data")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share_title)))
    }
}

/**
 * One timeline entry: a reading, an alert, or both at the same moment.
 * Nulls export as empty cells. (Accelerometer/gyroscope stay available here
 * for future machine-facing exports; the human CSV does not show them.)
 */
data class ExportLine(
    val timestamp: Date,
    val temperature: Double? = null,
    val activityIndex: Double? = null,
    val battery: Double? = null,
    val accelX: Double? = null,
    val accelY: Double? = null,
    val accelZ: Double? = null,
    val gyroX: Double? = null,
    val gyroY: Double? = null,
    val gyroZ: Double? = null,
    val dataQuality: String? = null,
    val alertType: String? = null,
    val alertNote: String? = null,
)
