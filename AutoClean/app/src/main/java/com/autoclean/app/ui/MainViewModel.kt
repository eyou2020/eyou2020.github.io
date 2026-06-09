package com.autoclean.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoclean.app.data.AppDatabase
import com.autoclean.app.data.CleanScheduleRepository
import com.autoclean.app.model.CleanSchedule
import com.autoclean.app.service.CleanupWorker
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CleanScheduleRepository(
        AppDatabase.getInstance(application).cleanScheduleDao()
    )

    val schedules = repository.allSchedules

    fun deleteSchedule(schedule: CleanSchedule) {
        viewModelScope.launch {
            CleanupWorker.cancelWork(getApplication(), schedule.id)
            repository.deleteSchedule(schedule)
        }
    }

    fun toggleSchedule(schedule: CleanSchedule, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(schedule.id, enabled)
            if (enabled) {
                val updated = schedule.copy(isEnabled = true)
                CleanupWorker.scheduleWork(getApplication(), updated)
            } else {
                CleanupWorker.cancelWork(getApplication(), schedule.id)
            }
        }
    }

    fun runNow(schedule: CleanSchedule) {
        viewModelScope.launch {
            val immediate = schedule.copy(
                scheduledHour = 0,
                scheduledMinute = 0,
                repeatDays = CleanSchedule.REPEAT_ONCE
            )
            CleanupWorker.scheduleWork(getApplication(), immediate)
        }
    }
}
