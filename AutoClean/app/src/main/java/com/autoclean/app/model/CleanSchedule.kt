package com.autoclean.app.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "clean_schedules")
data class CleanSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderPath: String,
    val folderName: String,
    val scheduledHour: Int,
    val scheduledMinute: Int,
    val repeatDays: String,
    val isEnabled: Boolean = true,
    val deleteMode: String = "TRASH",
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0,
    val nextRunAt: Long = 0,
    val totalDeletedCount: Int = 0
) : Parcelable {
    companion object {
        const val REPEAT_DAILY = "DAILY"
        const val REPEAT_WEEKDAY = "WEEKDAY"
        const val REPEAT_WEEKEND = "WEEKEND"
        const val REPEAT_ONCE = "ONCE"

        const val DELETE_MODE_TRASH = "TRASH"
        const val DELETE_MODE_PERMANENT = "PERMANENT"
    }
}
