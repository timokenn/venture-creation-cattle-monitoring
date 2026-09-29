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
 * Detailed export: one row per reading with EVERYTHING the collar sent
 * (temperature, activity, battery, accelerometer, gyroscope) plus alert
 * events merged into the same timeline. Timestamps are device-local
 * "yyyy-MM-dd HH:mm:ss" — spreadsheet-friendly and matching what the farmer
 * sees in the app. Fields are quoted per RFC 4180, so alert notes containing
 * commas survive round-tripping (the old exporter just deleted them).
 *
 * The file starts with a short human-readable block (cow, device, exported
 * at, row count) before the column header — plain rows a person can read
 * without any CSV knowledge.
 */
object CsvExport {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

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

        file.bufferedWriter().use { w ->
            // Human-readable preamble (one label per line, no commas to parse).
            w.appendLine("DeCow data export")
            w.appendLine("cow: ${cow.name}")
            w.appendLine("device_id: ${cow.deviceId}")
            w.appendLine("exported: ${stamp.format(Date())} (device local time)")
            w.appendLine("rows: ${rows.size}")
            w.appendLine()
            w.appendLine(
                "timestamp,temperature_c,activity_index,battery_pct," +
                    "accel_x_g,accel_y_g,accel_z_g,gyro_x,gyro_y,gyro_z," +
                    "data_quality,alert_type,alert_note",
            )
            for (r in rows) {
                w.appendLine(
                    listOf(
                        stamp.format(r.timestamp),
                        num(r.temperature, 2),
                        num(r.activityIndex, 3),
                        num(r.battery, 0),
                        num(r.accelX, 4),
                        num(r.accelY, 4),
                        num(r.accelZ, 4),
                        num(r.gyroX, 4),
                        num(r.gyroY, 4),
                        num(r.gyroZ, 4),
                        esc(r.dataQuality),
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
 * One CSV line: a full sensor reading, an alert-only marker, or both at the
 * same moment. Nulls export as empty cells.
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
