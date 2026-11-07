package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ItemEntity::class, ScanSessionEntity::class, RoomEntity::class, DesignProjectEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Removes only the five legacy generated demo rows; user-created rows are preserved. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM inventory_items
                    WHERE (id = 1 AND name = 'Sofa Băng Scandinavia Vải Nỉ')
                       OR (id = 2 AND name = 'Bàn Trà Tròn Mặt Kính Khung Kim Loại')
                       OR (id = 3 AND name = 'Ghế Thư Giãn Lounge Gỗ Ash Tự Nhiên')
                       OR (id = 4 AND name = 'Đèn Cây Đứng Nordic Minimalist')
                       OR (id = 7 AND name = 'Cây Bàng Singapore Giả Chậu Gốm Sứ')
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN modelParametersJson TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN modelPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN modelSource TEXT NOT NULL DEFAULT 'PARAMETRIC'")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN polygonCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN scanSessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN scaleConfirmed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS scan_sessions (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, modelType TEXT NOT NULL,
                    referenceAxis TEXT NOT NULL, referenceCm REAL NOT NULL, status TEXT NOT NULL,
                    imageCount INTEGER NOT NULL, backendUrl TEXT NOT NULL, jobId TEXT NOT NULL,
                    progress INTEGER NOT NULL, stage TEXT NOT NULL, error TEXT NOT NULL,
                    resultJson TEXT NOT NULL, modelPath TEXT NOT NULL, previewPath TEXT NOT NULL,
                    thumbnailPath TEXT NOT NULL, savedItemId INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS rooms (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, widthCm REAL NOT NULL,
                    depthCm REAL NOT NULL, heightCm REAL NOT NULL, openingsJson TEXT NOT NULL,
                    measurementSource TEXT NOT NULL, createdAt INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS design_projects (
                    id TEXT NOT NULL PRIMARY KEY, roomId TEXT NOT NULL, name TEXT NOT NULL,
                    roomJson TEXT NOT NULL, placementsJson TEXT NOT NULL, beforeImagePath TEXT NOT NULL,
                    afterImagePath TEXT NOT NULL, updatedAt INTEGER NOT NULL)""")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ar_spacefit_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
