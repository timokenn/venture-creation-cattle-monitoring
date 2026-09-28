package com.example.cattlemonitor.ui.device

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * QR pairing (#6): the collar ships with a sticker encoding its device_id
 * (plain text, or a `decow:device:<id>` URI). Scanning pre-fills the add-cow
 * form — farmers never type hex-ish device IDs by hand.
 *
 * Uses zxing-android-embedded's ScanContract, which hosts its own capture
 * activity (no CAMERA runtime permission handling needed in our code — the
 * library declares it and the system prompts on launch of the scanner).
 */
@Composable
fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result?.contents
        onResult(
            when {
                raw == null -> null
                raw.startsWith("decow:device:") -> raw.removePrefix("decow:device:")
                else -> raw.trim()
            },
        )
    }
    return {
        val options = ScanOptions().apply {
            setPrompt("Scan the collar's QR code")
            setBeepEnabled(false)
        }
        launcher.launch(options)
    }
}

/** CSV share helper (#5) — writes to cache and returns a shareable content URI. */
fun csvShareUri(context: Context, fileName: String): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
    val file = java.io.File(dir, fileName)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
