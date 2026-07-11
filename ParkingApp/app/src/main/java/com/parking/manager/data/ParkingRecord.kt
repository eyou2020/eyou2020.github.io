package com.parking.manager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parking_records")
data class ParkingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parkTime: Long = System.currentTimeMillis(),
    val parkLocation: String = "",
    val scheduledMoveTime: Long? = null,
    val moveStartTime: Long? = null,
    val moveEndTime: Long? = null,
    val newLocation: String? = null,
    val dateKey: String = ""
)

enum class SessionState { IDLE, PARKED, TIMER_SET, MOVING }

fun ParkingRecord.getState(): SessionState = when {
    moveEndTime != null -> SessionState.IDLE
    moveStartTime != null -> SessionState.MOVING
    scheduledMoveTime != null -> SessionState.TIMER_SET
    else -> SessionState.PARKED
}
