package com.carmanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fuel_entries")
data class FuelEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: Long,
    val amount: Int,
    val liters: Float = 0f
)
