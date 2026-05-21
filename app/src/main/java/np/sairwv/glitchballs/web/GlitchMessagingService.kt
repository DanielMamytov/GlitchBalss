package np.sairwv.glitchballs.web

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import np.sairwv.glitchballs.AppIntents
import np.sairwv.glitchballs.MainActivity
import np.sairwv.glitchballs.R
import np.sairwv.glitchballs.GlitchBallsApp
import np.sairwv.glitchballs.launch.LaunchPreferences
import java.net.HttpURLConnection
import java.net.URL

class GlitchMessagingService : FirebaseMessagingService() {

    private val launchPreferences by lazy { LaunchPreferences(this) }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed present=${token.isNotBlank()}")
        launchPreferences.pushToken = token
        (application as? GlitchBallsApp)?.let { app ->
            app.appsFlyerBridge.updateServerUninstallToken(token)
            app.syncConfigForUpdatedPushToken()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        ensureChannel(this)

        Log.d(
            TAG,
            "FCM message received dataKeys=${message.data.keys} " +
                "hasNotification=${message.notification != null}",
        )

        val title = message.data["title"]
            ?: message.notification?.title
            ?: getString(R.string.app_name)
        val body = message.data["body"]
            ?: message.notification?.body
            ?: getString(R.string.web_push_fallback_body)
        val notificationUrl = extractNotificationUrl(message)
        val imageUrl = message.data["image"]
            ?: message.data["imageUrl"]
            ?: message.data["picture"]
            ?: message.notification?.imageUrl?.toString()

        if (notificationUrl.isBlank() && title.isBlank() && body.isBlank()) {
            Log.w(TAG, "Skipping empty FCM notification")
            return
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.data.forEach { (key, value) ->
                putExtra(key, value)
            }
            putExtra(AppIntents.EXTRA_NOTIFICATION_URL, notificationUrl)
            putExtra("url", notificationUrl)
            putExtra("af_push_link", notificationUrl)
        }

        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            notificationUrl.hashCode(),
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_web_push)
            .setColor(ContextCompat.getColor(this, R.color.glitch_green))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        fetchNotificationBitmap(imageUrl)?.let { bitmap ->
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as Bitmap?),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission is not granted; notification not shown")
            return
        }

        NotificationManagerCompat.from(this)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
        Log.d(
            TAG,
            "Notification shown urlPresent=${notificationUrl.isNotBlank()} " +
                "imagePresent=${imageUrl?.isNotBlank() == true}",
        )
    }

    private fun fetchNotificationBitmap(imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank()) {
            return null
        }

        return runCatching {
            val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun extractNotificationUrl(message: RemoteMessage): String {
        return message.data["url"]
            ?: message.data["af_push_link"]
            ?: message.data["link"]
            ?: message.data["deep_link"]
            ?: message.notification?.link?.toString()
            ?: ""
    }

    companion object {
        private const val TAG = "GlitchMessagingService"
        private const val CHANNEL_ID = "glitchballs_web_push"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
                ?: return
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
                return
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.web_push_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.web_push_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
