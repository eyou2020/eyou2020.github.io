package com.carmanager.ui.fuel

import android.app.Application
import androidx.lifecycle.*
import com.carmanager.data.AppDatabase
import com.carmanager.data.FuelEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FuelViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).fuelDao()
    private val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())

    private val allEntries: LiveData<List<FuelEntry>> = dao.getAll().asLiveData()

    // 기록이 있는 년월 목록 (최신순) + "전체" 항목 포함
    val availableMonths: LiveData<List<String>> = allEntries.map { entries ->
        val months = entries.map { sdf.format(Date(it.date)) }.distinct().sortedDescending()
        listOf(ALL_MONTHS) + months
    }

    val selectedMonth = MutableLiveData<String>(ALL_MONTHS)

    // 선택된 달의 항목
    val filteredEntries: LiveData<List<FuelEntry>> =
        MediatorLiveData<List<FuelEntry>>().apply {
            fun update() {
                val all = allEntries.value ?: return
                val month = selectedMonth.value ?: ALL_MONTHS
                value = if (month == ALL_MONTHS) {
                    all
                } else {
                    all.filter { sdf.format(Date(it.date)) == month }
                }
            }
            addSource(allEntries) { update() }
            addSource(selectedMonth) { update() }
        }

    // 선택된 달의 주유 합계 (전체 선택 시 null)
    val monthTotal: LiveData<Int?> = MediatorLiveData<Int?>().apply {
        fun update() {
            val month = selectedMonth.value ?: ALL_MONTHS
            value = if (month == ALL_MONTHS) {
                null
            } else {
                filteredEntries.value?.sumOf { it.amount }
            }
        }
        addSource(filteredEntries) { update() }
        addSource(selectedMonth) { update() }
    }

    fun insert(entry: FuelEntry) = viewModelScope.launch { dao.insert(entry) }
    fun update(entry: FuelEntry) = viewModelScope.launch { dao.update(entry) }
    fun delete(entry: FuelEntry) = viewModelScope.launch { dao.delete(entry) }

    companion object {
        const val ALL_MONTHS = "전체"
    }
}
