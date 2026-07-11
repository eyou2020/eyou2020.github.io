package com.parking.manager.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
    private val alarmMinutes = listOf(30, 15, 10, 5)

    // ── 이동 예고 알람 (X분 전) ────────────────────────────────

    fun scheduleAlarms(moveTimeMillis: Long) {
        cancelAllAlarms()
        prefs.edit().putLong("scheduled_move_time", moveTimeMillis).apply()

        alarmMinutes.forEach { minutes ->
            val triggerTime = moveTimeMillis - (minutes * 60 * 1000L)
            if (triggerTime > System.currentTimeMillis()) {
                scheduleOne(triggerTime, minutes, AlarmReceiver.TYPE_MOVE_REMINDER, requestCode = minutes)
            }
        }
    }

    fun cancelAllAlarms() {
        prefs.edit().remove("scheduled_move_time").apply()
        alarmMinutes.forEach { minutes ->
            val intent = Intent(context, AlarmReceiver::class.java)
            PendingIntent.getBroadcast(
                context, minutes, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { alarmManager.cancel(it) }
        }
    }

    // ── 이동 가능 알람 (경과 시간 도달) ───────────────────────

    private val MOVE_READY_REQUEST_CODE = 500

    fun scheduleMoveReadyAlarm(triggerTimeMillis: Long) {
        cancelMoveReadyAlarm()
        if (triggerTimeMillis <= System.currentTimeMillis()) return
        prefs.edit().putLong("move_ready_time", triggerTimeMillis).apply()
        scheduleOne(triggerTimeMillis, 0, AlarmReceiver.TYPE_MOVE_READY, MOVE_READY_REQUEST_CODE)
    }

    fun cancelMoveReadyAlarm() {
        prefs.edit().remove("move_ready_time").apply()
        val intent = Intent(context, AlarmReceiver::class.java)
        PendingIntent.getBroadcast(
            context, MOVE_READY_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
    }

    fun getMoveReadyTime(): Long = prefs.getLong("move_ready_time", 0L)

    // ── 공통 ───────────────────────────────────────────────────

    private fun scheduleOne(triggerTime: Long, minutesBefore: Int, type: Int, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_MINUTES_LEFT, minutesBefore)
            putExtra(AlarmReceiver.EXTRA_TYPE, type)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    fun getScheduledMoveTime(): Long = prefs.getLong("scheduled_move_time", 0L)
}
