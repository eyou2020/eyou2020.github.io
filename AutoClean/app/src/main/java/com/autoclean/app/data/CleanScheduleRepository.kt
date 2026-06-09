package com.autoclean.app.data

import androidx.lifecycle.LiveData
import com.autoclean.app.model.CleanSchedule

class CleanScheduleRepository(private val dao: CleanScheduleDao) {

    val allSchedules: LiveData<List<CleanSchedule>> = dao.getAllSchedules()

    suspend fun getEnabledSchedules(): List<CleanSchedule> = dao.getEnabledSchedules()

    suspend fun getScheduleById(id: Long): CleanSchedule? = dao.getScheduleById(id)

    suspend fun insertSchedule(schedule: CleanSchedule): Long = dao.insertSchedule(schedule)

    suspend fun updateSchedule(schedule: CleanSchedule) = dao.updateSchedule(schedule)

    suspend fun deleteSchedule(schedule: CleanSchedule) = dao.deleteSchedule(schedule)

    suspend fun deleteScheduleById(id: Long) = dao.deleteScheduleById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun updateRunStats(id: Long, lastRunAt: Long, count: Int, nextRunAt: Long) =
        dao.updateRunStats(id, lastRunAt, count, nextRunAt)
}
