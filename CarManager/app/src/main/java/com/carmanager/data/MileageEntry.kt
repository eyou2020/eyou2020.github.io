package com.carmanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mileage_entries")
data class MileageEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: Long,
    val odometer: Int,
    val origin: String = "",
    val destination: String = ""
)
