package com.example.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

const val DB_VERSION = 5

@Database(
    entities = [ItemEntity::class, TagEntity::class],
    version = DB_VERSION,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN note TEXT")
            }
        }

        val MIGRATION_UNUSED = Migration(startVersion = 3, endVersion = 4) { }

        val ALL = arrayOf(MIGRATION_1_2)
    }
}
