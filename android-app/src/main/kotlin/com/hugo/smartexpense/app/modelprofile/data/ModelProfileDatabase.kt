package com.hugo.smartexpense.app.modelprofile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ModelProfileEntity::class, ModelSelectorStateEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(ModelProfileTypeConverters::class)
abstract class ModelProfileDatabase : RoomDatabase() {
    abstract fun modelProfileDao(): ModelProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_profiles ADD COLUMN show_tailscale_toggle INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: ModelProfileDatabase? = null

        fun getInstance(context: Context): ModelProfileDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ModelProfileDatabase::class.java,
                "smart-expense.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
