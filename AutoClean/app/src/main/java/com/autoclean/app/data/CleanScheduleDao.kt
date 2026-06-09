package com.autoclean.app.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.autoclean.app.model.CleanSchedule

@Dao
interface CleanScheduleDao {

    @Query("SELECT * FROM clean_schedules ORDER BY createdAt DESC")
    fun getAllSchedules(): LiveData<List<CleanSchedule>>

    @Query("SELECT * FROM clean_schedules WHERE isEnabled = 1")
    suspend fun getEnabledSchedules(): List<CleanSchedule>

    @Query("SELECT * FROM clean_schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): CleanSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: CleanSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: CleanSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: CleanSchedule)

    @Query("DELETE FROM clean_schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Long)

    @Query("UPDATE clean_schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE clean_schedules SET lastRunAt = :lastRunAt, totalDeletedCount = totalDeletedCount + :count, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun updateRunStats(id: Long, lastRunAt: Long, count: Int, nextRunAt: Long)
}
