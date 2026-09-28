package com.example.cattlemonitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.cattlemonitor.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Cow profile photos: compresses a gallery-picked image and uploads it to the
 * public 'cow-photos' Storage bucket via its REST endpoint. The uploaded
 * object is world-readable (public bucket) so the app renders it with plain
 * URLs via Coil; the upload itself carries the logged-in user's token
 * (storage RLS: authenticated insert only).
 *
 * Naming: cowId.jpg — deterministic, so re-picking a photo overwrites via the
 * x-upsert header and every device re-fetches the same URL.
 */
class CowPhotoUploader(
    private val tokenProvider: () -> String?,
    private val client: HttpClient = HttpClient(),
) {

    suspend fun upload(context: Context, cowId: String, imageUri: Uri): Result<String> = runCatching {
        val bitmap = decodeScaled(context, imageUri)
        val bytes = bitmap.jpegBytes(85)
        bitmap.recycle()

        val token = tokenProvider() ?: error("not signed in")
        val response = client.post("${BuildConfig.SUPABASE_URL}/storage/v1/object/cow-photos/$cowId.jpg") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            header("x-upsert", "true")
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        if (!response.status.isSuccess()) {
            error("upload failed ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/cow-photos/$cowId.jpg"
    }

    /** Decodes the picked image, downsampling so the largest side ≤ 1024px. */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("cannot decode image")
        val largest = maxOf(bitmap.width, bitmap.height)
        return if (largest > 1024) {
            val scale = 1024f / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            )
        } else {
            bitmap
        }
    }

    private fun Bitmap.jpegBytes(quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
