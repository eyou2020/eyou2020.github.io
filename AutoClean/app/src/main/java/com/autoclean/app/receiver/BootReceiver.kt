package com.autoclean.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoclean.app.data.AppDatabase
import com.autoclean.app.data.CleanScheduleRepository
import com.autoclean.app.service.CleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val repo = CleanScheduleRepository(db.cleanScheduleDao())
                val enabledSchedules = repo.getEnabledSchedules()

                enabledSchedules.forEach { schedule ->
                    CleanupWorker.scheduleWork(context, schedule)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
