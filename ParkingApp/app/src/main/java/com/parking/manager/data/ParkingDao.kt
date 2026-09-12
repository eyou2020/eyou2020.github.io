package com.parking.manager.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ParkingDao {

    @Query("SELECT * FROM parking_records ORDER BY parkTime DESC")
    fun getAllRecords(): LiveData<List<ParkingRecord>>

    @Query("SELECT * FROM parking_records WHERE dateKey = :date ORDER BY parkTime DESC")
    fun getRecordsByDate(date: String): LiveData<List<ParkingRecord>>

    @Query("SELECT * FROM parking_records WHERE moveEndTime IS NULL ORDER BY parkTime DESC LIMIT 1")
    suspend fun getActiveRecord(): ParkingRecord?

    @Query("SELECT DISTINCT dateKey FROM parking_records ORDER BY dateKey DESC")
    fun getAllDates(): LiveData<List<String>>

    @Insert
    suspend fun insert(record: ParkingRecord): Long

    @Update
    suspend fun update(record: ParkingRecord)

    @Delete
    suspend fun delete(record: ParkingRecord)

    @Query("DELETE FROM parking_records")
    suspend fun deleteAll()

    // dateKey가 'yyyy-MM' 패턴으로 시작하는 날짜별 최초 주차시간 합계(분) 반환
    @Query("""
        SELECT dateKey,
               SUM(CASE WHEN moveEndTime IS NOT NULL THEN (moveEndTime - parkTime) / 60000
                        ELSE (strftime('%s','now') * 1000 - parkTime) / 60000
                   END) AS totalMinutes
        FROM parking_records
        WHERE dateKey LIKE :yearMonth || '%'
        GROUP BY dateKey
    """)
    suspend fun getDailySummaryForMonth(yearMonth: String): List<DailySummary>
}

data class DailySummary(val dateKey: String, val totalMinutes: Long)
