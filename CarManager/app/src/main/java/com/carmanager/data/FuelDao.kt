package com.carmanager.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FuelDao {
    @Query("SELECT * FROM fuel_entries ORDER BY date DESC")
    fun getAll(): Flow<List<FuelEntry>>

    @Insert
    suspend fun insert(entry: FuelEntry)

    @Delete
    suspend fun delete(entry: FuelEntry)

    @Update
    suspend fun update(entry: FuelEntry)
}
