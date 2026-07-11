package com.parking.manager.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val scheduler = AlarmScheduler(context)
            val scheduledTime = scheduler.getScheduledMoveTime()
            if (scheduledTime > System.currentTimeMillis()) {
                scheduler.scheduleAlarms(scheduledTime)
            }

            val moveReadyTime = scheduler.getMoveReadyTime()
            if (moveReadyTime > System.currentTimeMillis()) {
                scheduler.scheduleMoveReadyAlarm(moveReadyTime)
            }
        }
    }
}
