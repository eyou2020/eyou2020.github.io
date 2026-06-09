package com.autoclean.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.autoclean.app.model.CleanSchedule

@Database(entities = [CleanSchedule::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cleanScheduleDao(): CleanScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autoclean_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
