package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ar.ModelParameters
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val item = ItemEntity(
        id = 42, name = "Ghế người dùng", category = "FURNITURE", roomType = "OFFICE",
        widthCm = 52.5f, heightCm = 90f, depthCm = 60f, colorHex = "#456789", material = "Gỗ",
        notes = "Giữ nguyên ghi chú", isFavorite = true, timestamp = 123456L, modelType3D = "CHAIR",
        modelParametersJson = ModelParameters(0.6f, true, 0.12f, 3).toJson()
    )

    @Test fun storedShapeSurvivesReopenAndRoomPlacement() = runTest {
        val name = "model-roundtrip-test.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            open().withOpen { it.itemDao().insertItem(item) }
            open().withOpen { db ->
                val restored = requireNotNull(db.itemDao().getItemById(42))
                assertEquals(item, restored)
                val placed = restored.toPlacedFurniture(instanceId = 101)
                assertEquals(0.6f, placed.modelParameters.seatHeightRatio, 0f)
                assertTrue(placed.modelParameters.hasArmrests)
                assertEquals(item.widthCm, placed.widthCm, 0f)
                val edited = restored.copy(modelParametersJson = ModelParameters(0.4f, false).toJson())
                db.itemDao().updateItem(edited)
                assertEquals(edited, db.itemDao().getItemById(42))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromVersion2PreservesUserDataAndOpensWithRoomValidation() = runTest {
        val name = "model-migration-2-3-test.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val schema = JSONObject(File("schemas/com.example.data.AppDatabase/2.json").readText()).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                val entity = schema.getJSONArray("entities").getJSONObject(0)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "inventory_items"))
                old.insertOrThrow("inventory_items", null, ContentValues().apply {
                    put("id", item.id); put("name", item.name); put("category", item.category); put("roomType", item.roomType)
                    put("widthCm", item.widthCm); put("heightCm", item.heightCm); put("depthCm", item.depthCm)
                    put("colorHex", item.colorHex); put("material", item.material); put("estimatedPrice", item.estimatedPrice)
                    put("notes", item.notes); put("imageUri", item.imageUri); put("modelType3D", item.modelType3D)
                    put("isFavorite", 1); put("timestamp", item.timestamp)
                })
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
                old.version = 2
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3).allowMainThreadQueries().build().withOpen { db ->
                    val migrated = requireNotNull(db.itemDao().getItemById(42))
                    assertEquals(item.copy(modelParametersJson = ""), migrated)
                    assertEquals(ModelParameters.defaults("CHAIR"), migrated.toPlacedFurniture().modelParameters)
                }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun <T> AppDatabase.withOpen(block: suspend (AppDatabase) -> T): T =
        try { block(this) } finally { close() }
}
