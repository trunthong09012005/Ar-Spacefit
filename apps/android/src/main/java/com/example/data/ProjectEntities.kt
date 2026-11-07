package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "Nội thất mới",
    val modelType: String = "CHAIR",
    val referenceAxis: String = "width",
    val referenceCm: Float = 0f,
    val status: String = "capturing",
    val imageCount: Int = 0,
    val backendUrl: String = "",
    val jobId: String = "",
    val progress: Int = 0,
    val stage: String = "",
    val error: String = "",
    val resultJson: String = "",
    val modelPath: String = "",
    val previewPath: String = "",
    val thumbnailPath: String = "",
    val savedItemId: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "Phòng của tôi",
    val widthCm: Float = 400f,
    val depthCm: Float = 500f,
    val heightCm: Float = 280f,
    val openingsJson: String = "[]",
    val measurementSource: String = "MANUAL",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "design_projects")
data class DesignProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val name: String,
    // A complete room snapshot makes old designs independent of subsequent room edits.
    val roomJson: String,
    val placementsJson: String = "[]",
    val beforeImagePath: String = "",
    val afterImagePath: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM scan_sessions ORDER BY createdAt DESC") fun scans(): Flow<List<ScanSessionEntity>>
    @Query("SELECT * FROM scan_sessions WHERE id = :id") suspend fun scan(id: String): ScanSessionEntity?
    @Upsert suspend fun saveScan(scan: ScanSessionEntity)
    @Query("SELECT * FROM rooms ORDER BY createdAt DESC") fun rooms(): Flow<List<RoomEntity>>
    @Upsert suspend fun saveRoom(room: RoomEntity)
    @Query("DELETE FROM rooms WHERE id = :id AND NOT EXISTS (SELECT 1 FROM design_projects WHERE roomId = :id)") suspend fun deleteUnreferencedRoom(id: String)
    @Query("SELECT * FROM design_projects ORDER BY updatedAt DESC") fun projects(): Flow<List<DesignProjectEntity>>
    @Upsert suspend fun saveProject(project: DesignProjectEntity)
    @Query("DELETE FROM design_projects WHERE id = :id") suspend fun deleteProject(id: String)
}
