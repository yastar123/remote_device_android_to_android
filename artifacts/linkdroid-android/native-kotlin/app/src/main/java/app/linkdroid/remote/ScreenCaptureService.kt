package app.linkdroid.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.os.IBinder

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "linkdroid-session"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sesi LinkDroid",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                sessionNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, sessionNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sessionNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkDroid sedang aktif")
            .setContentText("Layar dibagikan dalam sesi yang disetujui.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
}

@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        getParcelableExtra(key)
    }