package com.carmanager.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MileageDao {
    @Query("SELECT * FROM mileage_entries ORDER BY date DESC")
    fun getAll(): Flow<List<MileageEntry>>

    @Insert
    suspend fun insert(entry: MileageEntry)

    @Delete
    suspend fun delete(entry: MileageEntry)

    @Update
    suspend fun update(entry: MileageEntry)
}
