package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ItemEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ar_spacefit_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
