package com.carmanager.ui.mileage

import com.carmanager.data.FuelEntry
import com.carmanager.data.MileageEntry

sealed class DriveLogItem {
    data class MileageItem(val entry: MileageEntry, val delta: Int) : DriveLogItem()
    data class FuelItem(val entry: FuelEntry) : DriveLogItem()

    val date: Long get() = when (this) {
        is MileageItem -> entry.date
        is FuelItem    -> entry.date
    }
}
