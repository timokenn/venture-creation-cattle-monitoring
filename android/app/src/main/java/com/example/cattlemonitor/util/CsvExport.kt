package com.example.cattlemonitor.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.cattlemonitor.R
import com.example.cattlemonitor.data.Alert
import com.example.cattlemonitor.data.Cow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV export per cow (#5): readings + alerts written to cache/exports and
 * handed to the Android share sheet (Drive, WhatsApp, email — user's choice).
 * Timestamps are ISO-ish local time for spreadsheet friendliness.
 */
object CsvExport {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun export(context: Context, cow: Cow, rows: List<ExportLine>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = cow.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "decow_${safeName}_${System.currentTimeMillis()}.csv")

        file.bufferedWriter().use { w ->
            w.appendln("cow,timestamp,temperature_c,activity_index,data_quality,alert_type,alert_note")
            for (r in rows) {
                w.appendln(
                    listOf(
                        cow.name,
                        stamp.format(r.timestamp),
                        r.temperature?.toString() ?: "",
                        r.activityIndex?.let { String.format(Locale.US, "%.3f", it) } ?: "",
                        r.dataQuality ?: "",
                        r.alertType ?: "",
                        r.alertNote?.replace(',', ' ') ?: "",
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

/** One CSV line: a reading, an alert-only marker, or both at the same moment. */
data class ExportLine(
    val timestamp: Date,
    val temperature: Double? = null,
    val activityIndex: Double? = null,
    val dataQuality: String? = null,
    val alertType: String? = null,
    val alertNote: String? = null,
)
