package com.parking.manager.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.parking.manager.MainActivity
import com.parking.manager.R

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "parking_alarm_channel"
        const val EXTRA_MINUTES_LEFT = "minutes_left"
        const val EXTRA_TYPE = "alarm_type"
        const val TYPE_MOVE_REMINDER = 0   // X분 전 이동 예고 알람
        const val TYPE_MOVE_READY = 1      // 이동 가능 시간 도달 알람
    }

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getIntExtra(EXTRA_TYPE, TYPE_MOVE_REMINDER)
        val minutesLeft = intent.getIntExtra(EXTRA_MINUTES_LEFT, 0)
        showNotification(context, type, minutesLeft)
    }

    private fun showNotification(context: Context, type: Int, minutesLeft: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "주차 이동 알람",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "차량 이동 시간 알람"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 500)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, message) = when (type) {
            TYPE_MOVE_READY -> "🟢 이동 가능" to "이동이 가능한 시간입니다."
            else -> "🚗 주차 이동 알림" to
                if (minutesLeft > 0) "${minutesLeft}분 후 차량을 이동해야 합니다!" else "지금 차량을 이동해야 합니다!"
        }

        val notificationId = if (type == TYPE_MOVE_READY) 200 else minutesLeft + 100

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(null)
            .setVibrate(longArrayOf(0, 300, 200, 300, 200, 500))
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
