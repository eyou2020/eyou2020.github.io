package com.carmanager.ui.mileage

import android.app.Application
import androidx.lifecycle.*
import com.carmanager.data.AppDatabase
import com.carmanager.data.FuelEntry
import com.carmanager.data.MileageEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class LastFuelSummary(
    val lastFuelDate: Long,
    val distanceSinceKm: Int,
    val mileageCount: Int
)

class MileageViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val mileageDao = db.mileageDao()
    private val fuelDao = db.fuelDao()
    private val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val daySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val allMileageEntries: LiveData<List<MileageEntry>> = mileageDao.getAll().asLiveData()
    private val allFuelEntries: LiveData<List<FuelEntry>> = fuelDao.getAll().asLiveData()

    // 주행+주유 기록이 있는 년월 목록 (최신순) + "전체"
    val availableMonths: LiveData<List<String>> = MediatorLiveData<List<String>>().apply {
        fun update() {
            val m = allMileageEntries.value?.map { sdf.format(Date(it.date)) } ?: emptyList()
            val f = allFuelEntries.value?.map { sdf.format(Date(it.date)) } ?: emptyList()
            value = listOf(ALL_MONTHS) + (m + f).distinct().sortedDescending()
        }
        addSource(allMileageEntries) { update() }
        addSource(allFuelEntries) { update() }
    }

    val selectedMonth = MutableLiveData<String>(ALL_MONTHS)

    // 주행 + 주유 통합 목록 (날짜순 내림차순)
    val mergedList: LiveData<List<DriveLogItem>> = MediatorLiveData<List<DriveLogItem>>().apply {
        fun update() {
            val mileageAll = allMileageEntries.value ?: return
            val fuelAll = allFuelEntries.value ?: return
            val month = selectedMonth.value ?: ALL_MONTHS

            val mileageSorted = mileageAll.sortedBy { it.date }
            val withDelta = mileageSorted.mapIndexed { i, entry ->
                val prev = if (i > 0) mileageSorted[i - 1].odometer else entry.odometer
                DriveLogItem.MileageItem(entry, if (i > 0) entry.odometer - prev else 0)
            }

            val mileageFiltered: List<DriveLogItem> = if (month == ALL_MONTHS) withDelta
            else withDelta.filter { sdf.format(Date(it.entry.date)) == month }

            val fuelFiltered: List<DriveLogItem> = fuelAll
                .filter { month == ALL_MONTHS || sdf.format(Date(it.date)) == month }
                .map { DriveLogItem.FuelItem(it) }

            value = (mileageFiltered + fuelFiltered).sortedByDescending { it.date }
        }
        addSource(allMileageEntries) { update() }
        addSource(allFuelEntries) { update() }
        addSource(selectedMonth) { update() }
    }

    // 최근 주유 이후 주행 요약
    val lastFuelSummary: LiveData<LastFuelSummary?> = MediatorLiveData<LastFuelSummary?>().apply {
        fun update() {
            val mileageAll = allMileageEntries.value ?: return
            val fuelAll = allFuelEntries.value ?: return
            if (fuelAll.isEmpty() || mileageAll.isEmpty()) { value = null; return }

            val lastFuel = fuelAll.maxByOrNull { it.date }!!
            val sortedMileage = mileageAll.sortedBy { it.date }

            val afterFuel = sortedMileage.filter { it.date > lastFuel.date }
            if (afterFuel.isEmpty()) { value = null; return }

            val beforeFuel = sortedMileage.filter { it.date <= lastFuel.date }
            val baseOdometer = if (beforeFuel.isNotEmpty()) beforeFuel.maxOf { it.odometer }
                               else afterFuel.minOf { it.odometer }
            val currentOdometer = afterFuel.maxOf { it.odometer }

            value = LastFuelSummary(
                lastFuelDate = lastFuel.date,
                distanceSinceKm = maxOf(0, currentOdometer - baseOdometer),
                mileageCount = afterFuel.size
            )
        }
        addSource(allMileageEntries) { update() }
        addSource(allFuelEntries) { update() }
    }

    // 월별 주행 합계 (선택된 월 기준)
    val monthMileageSummary: LiveData<Pair<Int, Int>?> =
        MediatorLiveData<Pair<Int, Int>?>().apply {
            fun update() {
                val month = selectedMonth.value ?: ALL_MONTHS
                if (month == ALL_MONTHS) { value = null; return }
                val list = mergedList.value
                    ?.filterIsInstance<DriveLogItem.MileageItem>() ?: return
                val total = list.sumOf { it.delta }
                value = Pair(total, list.size)
            }
            addSource(mergedList) { update() }
            addSource(selectedMonth) { update() }
        }

    // 오늘 주행 요약 (전체 데이터 기준, 오늘 날짜)
    val todayMileageSummary: LiveData<Pair<Int, Int>?> =
        MediatorLiveData<Pair<Int, Int>?>().apply {
            fun update() {
                val list = allMileageEntries.value ?: return
                val today = daySdf.format(Date())
                val todayItems = list.filter { daySdf.format(Date(it.date)) == today }
                if (todayItems.isEmpty()) { value = null; return }
                val allSorted = list.sortedBy { it.date }
                val totalKm = todayItems.sortedBy { it.date }.sumOf { entry ->
                    val idx = allSorted.indexOfFirst { it.id == entry.id }
                    if (idx > 0) maxOf(0, entry.odometer - allSorted[idx - 1].odometer) else 0
                }
                value = Pair(totalKm, todayItems.size)
            }
            addSource(allMileageEntries) { update() }
        }

    fun insert(entry: MileageEntry) = viewModelScope.launch { mileageDao.insert(entry) }
    fun update(entry: MileageEntry) = viewModelScope.launch { mileageDao.update(entry) }
    fun delete(entry: MileageEntry) = viewModelScope.launch { mileageDao.delete(entry) }

    companion object {
        const val ALL_MONTHS = "전체"
    }
}
