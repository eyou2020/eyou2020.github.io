package com.parking.manager.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ParkingLocationDao {

    @Query("SELECT * FROM parking_locations ORDER BY name ASC")
    fun getAllLocations(): LiveData<List<ParkingLocation>>

    @Query("SELECT * FROM parking_locations WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ParkingLocation?

    @Insert
    suspend fun insert(location: ParkingLocation): Long

    @Update
    suspend fun update(location: ParkingLocation)

    @Delete
    suspend fun delete(location: ParkingLocation)
}
