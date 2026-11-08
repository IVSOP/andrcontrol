package dev.andrcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class BoostService : Service() {
    private val mixer get() = (application as MixerApplication).mixer
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val update: () -> Unit = { manager.notify(NOTIFICATION_ID, notification()) }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.boost_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification())
        mixer.addListener(update)
        mixer.enableBoost()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RESET -> mixer.setMediaPercent(100)
            ACTION_MUTE -> mixer.setMediaPercent(0)
        }
        // Never silently restore amplification following process death.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BoostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val mute = PendingIntent.getService(this, 3, Intent(this, BoostService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
        val detail = when {
            !mixer.boostEnabled -> "Starting audio effects"
            mixer.fineApplied -> "${mixer.mediaPercent}% · quiet output attenuation active"
            mixer.fineRequested -> "${mixer.mediaPercent}% requested · muted until attenuation is available"
            mixer.mediaPercent <= 100 -> "${mixer.mediaPercent}% media · boost ready"
            mixer.globalAvailable -> "${mixer.mediaPercent}% requested · device effect enabled"
            mixer.controlledSessionCount > 0 -> "${mixer.mediaPercent}% on compatible players"
            else -> "${mixer.mediaPercent}% requested · waiting for a compatible player"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AndrControl")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Mute", mute).build())
            .addAction(Notification.Action.Builder(null, "Stop control", stop).build())
            .build()
    }

    override fun onDestroy() {
        mixer.removeListener(update)
        mixer.disableBoost()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "audio_boost"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.andrcontrol.STOP"
        const val ACTION_RESET = "dev.andrcontrol.RESET"
        const val ACTION_MUTE = "dev.andrcontrol.MUTE"
    }
}
