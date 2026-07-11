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
}
