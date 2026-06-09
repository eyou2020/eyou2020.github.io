package com.autoclean.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.autoclean.app.data.AppDatabase
import com.autoclean.app.data.CleanScheduleRepository
import com.autoclean.app.model.CleanSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CleanupWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"

        fun scheduleWork(context: Context, schedule: CleanSchedule) {
            val delayMs = calculateDelay(schedule)
            if (delayMs < 0) return

            val data = workDataOf(KEY_SCHEDULE_ID to schedule.id)
            val tag = "cleanup_${schedule.id}"

            val request = OneTimeWorkRequestBuilder<CleanupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(tag)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelWork(context: Context, scheduleId: Long) {
            WorkManager.getInstance(context).cancelAllWorkByTag("cleanup_$scheduleId")
        }

        private fun calculateDelay(schedule: CleanSchedule): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, schedule.scheduledHour)
                set(Calendar.MINUTE, schedule.scheduledMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val daysToAdd = when (schedule.repeatDays) {
                CleanSchedule.REPEAT_WEEKDAY -> daysUntilWeekday(now, target)
                CleanSchedule.REPEAT_WEEKEND -> daysUntilWeekend(now, target)
                CleanSchedule.REPEAT_DAILY, CleanSchedule.REPEAT_ONCE -> {
                    if (target.timeInMillis <= now.timeInMillis) 1 else 0
                }
                else -> daysUntilSpecificDays(now, target, schedule.repeatDays)
            }

            target.add(Calendar.DAY_OF_YEAR, daysToAdd)
            return target.timeInMillis - now.timeInMillis
        }

        private fun daysUntilWeekday(now: Calendar, target: Calendar): Int {
            var days = if (target.timeInMillis <= now.timeInMillis) 1 else 0
            val check = Calendar.getInstance().apply {
                timeInMillis = target.timeInMillis
                add(Calendar.DAY_OF_YEAR, days)
            }
            while (check.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                check.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                days++
                check.add(Calendar.DAY_OF_YEAR, 1)
            }
            return days
        }

        private fun daysUntilWeekend(now: Calendar, target: Calendar): Int {
            var days = if (target.timeInMillis <= now.timeInMillis) 1 else 0
            val check = Calendar.getInstance().apply {
                timeInMillis = target.timeInMillis
                add(Calendar.DAY_OF_YEAR, days)
            }
            while (check.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY &&
                check.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
                days++
                check.add(Calendar.DAY_OF_YEAR, 1)
            }
            return days
        }

        private fun daysUntilSpecificDays(now: Calendar, target: Calendar, repeatDays: String): Int {
            val dayMap = mapOf(
                "SUN" to Calendar.SUNDAY, "MON" to Calendar.MONDAY,
                "TUE" to Calendar.TUESDAY, "WED" to Calendar.WEDNESDAY,
                "THU" to Calendar.THURSDAY, "FRI" to Calendar.FRIDAY,
                "SAT" to Calendar.SATURDAY
            )
            val targetDays = repeatDays.split(",").mapNotNull { dayMap[it.trim()] }.toSet()
            if (targetDays.isEmpty()) {
                return if (target.timeInMillis <= now.timeInMillis) 1 else 0
            }

            var days = if (target.timeInMillis <= now.timeInMillis) 1 else 0
            val check = Calendar.getInstance().apply {
                timeInMillis = target.timeInMillis
                add(Calendar.DAY_OF_YEAR, days)
            }
            for (i in 0..6) {
                if (check.get(Calendar.DAY_OF_WEEK) in targetDays) return days + i
                check.add(Calendar.DAY_OF_YEAR, 1)
            }
            return days
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1L)
        if (scheduleId == -1L) return@withContext Result.failure()

        val db = AppDatabase.getInstance(context)
        val repo = CleanScheduleRepository(db.cleanScheduleDao())
        val schedule = repo.getScheduleById(scheduleId) ?: return@withContext Result.failure()

        if (!schedule.isEnabled) return@withContext Result.success()

        val notifId = scheduleId.toInt() + 2000
        NotificationHelper.createNotificationChannel(context)

        return@withContext try {
            val deletedCount = deleteFiles(schedule)
            val now = System.currentTimeMillis()

            if (schedule.repeatDays != CleanSchedule.REPEAT_ONCE) {
                val nextSchedule = schedule.copy(nextRunAt = now)
                scheduleWork(context, nextSchedule)
                val nextTarget = calculateNextRunTime(schedule)
                repo.updateRunStats(scheduleId, now, deletedCount, nextTarget)
            } else {
                repo.updateRunStats(scheduleId, now, deletedCount, 0)
                repo.setEnabled(scheduleId, false)
            }

            NotificationHelper.showCompletionNotification(context, schedule.folderName, deletedCount, notifId)
            Result.success()
        } catch (e: Exception) {
            NotificationHelper.showErrorNotification(context, schedule.folderName, e.message ?: "Unknown error", notifId)
            Result.failure()
        }
    }

    private fun deleteFiles(schedule: CleanSchedule): Int {
        val uri = Uri.parse(schedule.folderPath)
        val folder = DocumentFile.fromTreeUri(context, uri) ?: return 0

        var count = 0
        folder.listFiles().forEach { file ->
            if (file.isFile && file.delete()) count++
        }
        return count
    }

    private fun calculateNextRunTime(schedule: CleanSchedule): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.scheduledHour)
            set(Calendar.MINUTE, schedule.scheduledMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }
}
