package com.example.cattlemonitor.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.cattlemonitor.R
import com.example.cattlemonitor.ServiceLocator
import com.example.cattlemonitor.data.AlertType
import com.example.cattlemonitor.settings.NotificationPrefs
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Receives FCM pushes, filters them against the user's per-alert-type
 * notification preferences, and deep-links to the cow detail screen.
 */
class CattleMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        runBlocking {
            ServiceLocator.repository.registerFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val cowId = message.data["cowId"] ?: return
        val type = AlertType.from(message.data["type"]) ?: return
        // Data-only push: the payload carries title/body so the service always
        // builds the notification itself — with a contentIntent, so a tap
        // deep-links to the cow even from a cold start (backgrounded app).
        val title = message.data["title"] ?: "Cattle alert"
        val body = message.data["body"] ?: ""

        val enabled = runBlocking {
            NotificationPrefs(this@CattleMessagingService).enabled.first()
        }
        if (type !in enabled) return

        showNotification(cowId, type, title, body)
    }

    private fun showNotification(cowId: String, type: AlertType, title: String, body: String) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Health alerts", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("cattleapp://cow/$cowId"),
        ).setPackage(packageName)
        // One-shot: every alert gets its own tap target (no cross-alert reuse).
        val pending = PendingIntent.getActivity(
            this, cowId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(type.name.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing else to do here.
        }
    }

    companion object {
        private const val CHANNEL_ID = "health_alerts"
    }
}
