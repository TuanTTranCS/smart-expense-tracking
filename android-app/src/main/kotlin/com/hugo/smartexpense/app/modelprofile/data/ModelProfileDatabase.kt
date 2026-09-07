package com.hugo.smartexpense.app.modelprofile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ModelProfileEntity::class, ModelSelectorStateEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(ModelProfileTypeConverters::class)
abstract class ModelProfileDatabase : RoomDatabase() {
    abstract fun modelProfileDao(): ModelProfileDao

    companion object {
        @Volatile private var instance: ModelProfileDatabase? = null

        fun getInstance(context: Context): ModelProfileDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ModelProfileDatabase::class.java,
                "smart-expense.db",
            ).build().also { instance = it }
        }
    }
}
