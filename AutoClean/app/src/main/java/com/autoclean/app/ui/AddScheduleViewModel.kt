package com.autoclean.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.autoclean.app.data.AppDatabase
import com.autoclean.app.data.CleanScheduleRepository
import com.autoclean.app.model.CleanSchedule
import com.autoclean.app.service.CleanupWorker
import kotlinx.coroutines.launch

class AddScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CleanScheduleRepository(
        AppDatabase.getInstance(application).cleanScheduleDao()
    )

    val folderUri = MutableLiveData<String>()
    val folderName = MutableLiveData<String>()
    val scheduledHour = MutableLiveData(8)
    val scheduledMinute = MutableLiveData(0)
    val repeatDays = MutableLiveData(CleanSchedule.REPEAT_DAILY)
    val deleteMode = MutableLiveData(CleanSchedule.DELETE_MODE_TRASH)

    val saveResult = MutableLiveData<Boolean>()

    fun loadSchedule(schedule: CleanSchedule) {
        folderUri.value = schedule.folderPath
        folderName.value = schedule.folderName
        scheduledHour.value = schedule.scheduledHour
        scheduledMinute.value = schedule.scheduledMinute
        repeatDays.value = schedule.repeatDays
        deleteMode.value = schedule.deleteMode
    }

    fun saveSchedule(existingId: Long = 0) {
        val uri = folderUri.value ?: run { saveResult.value = false; return }
        val name = folderName.value ?: run { saveResult.value = false; return }
        val hour = scheduledHour.value ?: 8
        val minute = scheduledMinute.value ?: 0
        val repeat = repeatDays.value ?: CleanSchedule.REPEAT_DAILY
        val mode = deleteMode.value ?: CleanSchedule.DELETE_MODE_TRASH

        viewModelScope.launch {
            val schedule = CleanSchedule(
                id = existingId,
                folderPath = uri,
                folderName = name,
                scheduledHour = hour,
                scheduledMinute = minute,
                repeatDays = repeat,
                deleteMode = mode,
                isEnabled = true
            )

            val savedId = repository.insertSchedule(schedule)
            val savedSchedule = schedule.copy(id = if (existingId == 0L) savedId else existingId)

            if (existingId != 0L) {
                CleanupWorker.cancelWork(getApplication(), existingId)
            }
            CleanupWorker.scheduleWork(getApplication(), savedSchedule)
            saveResult.value = true
        }
    }
}
