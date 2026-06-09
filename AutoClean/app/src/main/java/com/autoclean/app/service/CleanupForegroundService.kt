package com.autoclean.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CleanupForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
