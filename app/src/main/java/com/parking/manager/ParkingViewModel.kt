package com.parking.manager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.parking.manager.alarm.AlarmScheduler
import com.parking.manager.data.ParkingDatabase
import com.parking.manager.data.ParkingLocation
import com.parking.manager.data.ParkingRecord
import com.parking.manager.data.SessionState
import com.parking.manager.data.getState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ParkingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ParkingDatabase.getDatabase(application)
    private val dao = db.parkingDao()
    private val locationDao = db.parkingLocationDao()
    private val alarmScheduler = AlarmScheduler(application)

    private val _currentRecord = MutableLiveData<ParkingRecord?>()
    val currentRecord: LiveData<ParkingRecord?> = _currentRecord

    val allLocations = locationDao.getAllLocations()
    val allDates = dao.getAllDates()

    init {
        loadActiveRecord()
    }

    private fun loadActiveRecord() {
        viewModelScope.launch {
            _currentRecord.postValue(dao.getActiveRecord())
        }
    }

    // ── 주차 세션 ──────────────────────────────────────────────

    /**
     * 주차 등록. parkingMinutes > 0 이면 이동 예정 시간을 자동으로 설정하고 알람을 예약한다.
     */
    fun registerParking(location: String, parkingMinutes: Int = 0, moveTimeMinutes: Int = 0) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val autoMoveTime = if (parkingMinutes > 0) now + parkingMinutes * 60_000L else null
            val record = ParkingRecord(
                parkTime = now,
                parkLocation = location,
                scheduledMoveTime = autoMoveTime,
                dateKey = dateKey
            )
            val id = dao.insert(record)
            _currentRecord.postValue(record.copy(id = id))
            autoMoveTime?.let { alarmScheduler.scheduleAlarms(it) }
            if (moveTimeMinutes > 0) {
                alarmScheduler.scheduleMoveReadyAlarm(now + moveTimeMinutes * 60_000L)
            }
        }
    }

    /** 주차 위치 변경 */
    fun updateParkLocation(newLocation: String) {
        val record = _currentRecord.value ?: return
        viewModelScope.launch {
            val updated = record.copy(parkLocation = newLocation)
            dao.update(updated)
            _currentRecord.postValue(updated)
        }
    }

    /** 주차 시간 수정. 이동 예정 시간이 있으면 같은 간격으로 재계산한다. */
    fun updateParkTime(newParkTime: Long) {
        val record = _currentRecord.value ?: return
        viewModelScope.launch {
            val updatedMoveTime = record.scheduledMoveTime?.let { oldMove ->
                newParkTime + (oldMove - record.parkTime)
            }
            val updated = record.copy(parkTime = newParkTime, scheduledMoveTime = updatedMoveTime)
            dao.update(updated)
            _currentRecord.postValue(updated)
            updatedMoveTime?.let { alarmScheduler.scheduleAlarms(it) }
        }
    }

    /** 주차 후 경과 시간(분)으로 이동 예정 시간을 설정한다. */
    fun setMoveTimeAfterMinutes(minutesAfterPark: Long) {
        val record = _currentRecord.value ?: return
        val moveTime = record.parkTime + minutesAfterPark * 60_000L
        viewModelScope.launch {
            val updated = record.copy(scheduledMoveTime = moveTime)
            dao.update(updated)
            _currentRecord.postValue(updated)
            alarmScheduler.scheduleAlarms(moveTime)
        }
    }

    fun startMove() {
        val record = _currentRecord.value ?: return
        viewModelScope.launch {
            val updated = record.copy(moveStartTime = System.currentTimeMillis())
            dao.update(updated)
            _currentRecord.postValue(updated)
            alarmScheduler.cancelAllAlarms()
            alarmScheduler.cancelMoveReadyAlarm()
        }
    }

    /**
     * 이동 완료 처리.
     * 1) 현재 세션에 moveEndTime·newLocation 저장
     * 2) 이동 완료 위치·시각으로 새 주차 세션 자동 생성
     * 3) parkingMinutes > 0 이면 이동 예정 시간 자동 설정
     */
    fun completeMove(newLocation: String, parkingMinutes: Int = 0, moveTimeMinutes: Int = 0) {
        val record = _currentRecord.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            // 이동 완료 경로가 UI 밖에서 호출되어도 이전 세션의 알림이 남지 않게 한다.
            alarmScheduler.cancelAllAlarms()
            alarmScheduler.cancelMoveReadyAlarm()
            dao.update(record.copy(moveEndTime = now, newLocation = newLocation))

            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val autoMoveTime = if (parkingMinutes > 0) now + parkingMinutes * 60_000L else null
            val newRecord = ParkingRecord(
                parkTime = now,
                parkLocation = newLocation,
                scheduledMoveTime = autoMoveTime,
                dateKey = dateKey
            )
            val id = dao.insert(newRecord)
            _currentRecord.postValue(newRecord.copy(id = id))
            autoMoveTime?.let { alarmScheduler.scheduleAlarms(it) }
            if (moveTimeMinutes > 0) {
                alarmScheduler.scheduleMoveReadyAlarm(now + moveTimeMinutes * 60_000L)
            }
        }
    }

    fun deleteRecord(record: ParkingRecord) {
        viewModelScope.launch {
            dao.delete(record)
            if (_currentRecord.value?.id == record.id) {
                alarmScheduler.cancelAllAlarms()
                alarmScheduler.cancelMoveReadyAlarm()
                _currentRecord.postValue(null)
            }
        }
    }

    fun deleteAllRecords() {
        viewModelScope.launch {
            dao.deleteAll()
            alarmScheduler.cancelAllAlarms()
            alarmScheduler.cancelMoveReadyAlarm()
            _currentRecord.postValue(null)
        }
    }

    // ── 위치 관리 ──────────────────────────────────────────────

    fun addLocation(name: String, parkingMinutes: Int = 0, moveTimeMinutes: Int = 0) {
        viewModelScope.launch {
            locationDao.insert(ParkingLocation(name = name, parkingMinutes = parkingMinutes, moveTimeMinutes = moveTimeMinutes))
        }
    }

    fun updateLocation(location: ParkingLocation) {
        viewModelScope.launch { locationDao.update(location) }
    }

    fun deleteLocation(location: ParkingLocation) {
        viewModelScope.launch { locationDao.delete(location) }
    }

    fun getRecordsByDate(date: String) = dao.getRecordsByDate(date)

    fun getCurrentState(): SessionState =
        _currentRecord.value?.getState() ?: SessionState.IDLE
}
