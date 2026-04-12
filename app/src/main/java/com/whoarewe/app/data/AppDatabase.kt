package com.whoarewe.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Identity::class, TrustedContact::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // No `fallbackToDestructiveMigration()` — that path
                // silently drops every table on a schema bump and wipes
                // the user's identity + every paired contact with no
                // warning. The product's recovery story ("lost device =
                // re-pair in person") tolerates total data loss as a
                // failure mode, but only when it's *visible*; an invisible
                // wipe is exactly what users would interpret as "the app
                // randomly lost my data" and would push them toward
                // insecure workarounds. See cwage/whoarewe#23.
                //
                // Every schema version bump must register an explicit
                // `Migration(from, to)` here via `.addMigrations(...)`
                // *and* commit a matching
                // `app/schemas/com.whoarewe.app.data.AppDatabase/<version>.json`
                // (Room schema export is enabled in build.gradle.kts).
                // The migration must have a `MigrationTestHelper`-based
                // test in androidTest — see `RoomMigrationTest` for the
                // template.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whoarewe.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
