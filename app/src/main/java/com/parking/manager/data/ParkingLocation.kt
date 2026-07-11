package com.parking.manager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parking_locations")
data class ParkingLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parkingMinutes: Int = 0,   // 주차 가능 시간 (0 = 미설정)
    val moveTimeMinutes: Int = 0   // 이동시간 알림 (0 = 미설정)
)

fun ParkingLocation.durationText(minutes: Int): String? {
    if (minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

/** 주차 가능 시간 텍스트 */
fun ParkingLocation.durationText(): String? = durationText(parkingMinutes)

/** 이동시간 텍스트 */
fun ParkingLocation.moveTimeText(): String? = durationText(moveTimeMinutes)

/** 위치 선택 목록 표시 이름. "B주차장 (2시간)" 형태. */
fun ParkingLocation.displayName(): String {
    val dur = durationText() ?: return name
    return "$name ($dur)"
}
