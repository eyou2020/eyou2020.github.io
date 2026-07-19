package com.carmanager.ui.stats

import android.app.Application
import androidx.lifecycle.*
import com.carmanager.data.AppDatabase
import com.carmanager.data.FuelEntry
import com.carmanager.data.MileageEntry
import java.text.SimpleDateFormat
import java.util.*

data class MileageMonthDetail(
    val month: String,
    val distanceKm: Int,
    val entries: List<MileageEntry>
)

data class FuelMonthDetail(
    val month: String,
    val totalAmount: Int,
    val entries: List<FuelEntry>
)

data class YearSummary(
    val year: String,
    val mileageKm: Int,
    val mileageCount: Int,
    val fuelAmount: Int,
    val fuelCount: Int
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val yearSdf = SimpleDateFormat("yyyy", Locale.getDefault())

    private val allMileageEntries: LiveData<List<MileageEntry>> =
        db.mileageDao().getAll().asLiveData()
    private val allFuelEntries: LiveData<List<FuelEntry>> =
        db.fuelDao().getAll().asLiveData()

    // 주행기록이 있는 년월 목록 (최신순)
    val mileageMonths: LiveData<List<String>> = allMileageEntries.map { entries ->
        entries.map { sdf.format(Date(it.date)) }.distinct().sortedDescending()
    }

    // 주유기록이 있는 년월 목록 (최신순)
    val fuelMonths: LiveData<List<String>> = allFuelEntries.map { entries ->
        entries.map { sdf.format(Date(it.date)) }.distinct().sortedDescending()
    }

    val selectedMileageMonth = MutableLiveData<String?>()
    val selectedFuelMonth = MutableLiveData<String?>()

    // 올해 누적 요약
    val yearSummary: LiveData<YearSummary?> = MediatorLiveData<YearSummary?>().apply {
        fun update() {
            val mileageAll = allMileageEntries.value ?: return
            val fuelAll = allFuelEntries.value ?: return
            val thisYear = yearSdf.format(Date())

            // 올해 주행 km: 전체 오름차순 정렬 후 올해 항목들의 delta 합산
            val allSorted = mileageAll.sortedBy { it.date }
            val yearMileageKm = allSorted.mapIndexed { i, entry ->
                val delta = if (i > 0) maxOf(0, entry.odometer - allSorted[i - 1].odometer) else 0
                Pair(entry, delta)
            }.filter { (entry, _) -> yearSdf.format(Date(entry.date)) == thisYear }
             .sumOf { (_, delta) -> delta }

            val yearMileageEntries = mileageAll.filter { yearSdf.format(Date(it.date)) == thisYear }
            val yearFuelEntries = fuelAll.filter { yearSdf.format(Date(it.date)) == thisYear }
            val yearFuelAmount = yearFuelEntries.sumOf { it.amount }

            value = YearSummary(
                year = thisYear,
                mileageKm = yearMileageKm,
                mileageCount = yearMileageEntries.size,
                fuelAmount = yearFuelAmount,
                fuelCount = yearFuelEntries.size
            )
        }
        addSource(allMileageEntries) { update() }
        addSource(allFuelEntries) { update() }
    }

    // 선택한 달의 주행거리 상세
    val mileageDetail: LiveData<MileageMonthDetail?> =
        MediatorLiveData<MileageMonthDetail?>().apply {
            fun update() {
                val month = selectedMileageMonth.value ?: run { value = null; return }
                val entries = allMileageEntries.value ?: run { value = null; return }
                value = computeMileageDetail(month, entries)
            }
            addSource(selectedMileageMonth) { update() }
            addSource(allMileageEntries) { update() }
        }

    // 선택한 달의 주유 상세
    val fuelDetail: LiveData<FuelMonthDetail?> =
        MediatorLiveData<FuelMonthDetail?>().apply {
            fun update() {
                val month = selectedFuelMonth.value ?: run { value = null; return }
                val entries = allFuelEntries.value ?: run { value = null; return }
                value = computeFuelDetail(month, entries)
            }
            addSource(selectedFuelMonth) { update() }
            addSource(allFuelEntries) { update() }
        }

    private fun computeMileageDetail(month: String, allEntries: List<MileageEntry>): MileageMonthDetail {
        val sorted = allEntries.sortedBy { it.date }
        val monthEntries = sorted.filter { sdf.format(Date(it.date)) == month }
        if (monthEntries.isEmpty()) return MileageMonthDetail(month, 0, emptyList())
        val lastOdometerThisMonth = monthEntries.maxOf { it.odometer }
        val prevEntries = sorted.filter { sdf.format(Date(it.date)) < month }
        val baseOdometer = if (prevEntries.isNotEmpty()) prevEntries.maxOf { it.odometer }
                           else monthEntries.minOf { it.odometer }
        val distance = lastOdometerThisMonth - baseOdometer
        return MileageMonthDetail(month, maxOf(0, distance), monthEntries.sortedByDescending { it.date })
    }

    private fun computeFuelDetail(month: String, allEntries: List<FuelEntry>): FuelMonthDetail {
        val monthEntries = allEntries
            .filter { sdf.format(Date(it.date)) == month }
            .sortedByDescending { it.date }
        return FuelMonthDetail(month, monthEntries.sumOf { it.amount }, monthEntries)
    }
}