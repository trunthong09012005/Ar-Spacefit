package com.example.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: String, // FURNITURE, DECOR, ELECTRONICS
    val roomType: String, // LIVING_ROOM, BEDROOM, OFFICE, KITCHEN, WARDROBE
    val widthCm: Float,
    val heightCm: Float,
    val depthCm: Float,
    val colorHex: String,
    val material: String,
    val estimatedPrice: String = "",
    val notes: String = "",
    val imageUri: String = "",
    val modelType3D: String = "BOX", // SOFA, CHAIR, TABLE, BED, LAMP, WARDROBE, PLANT, BOX
    val isFavorite: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val modelParametersJson: String = "",
    @ColumnInfo(defaultValue = "''") val modelPath: String = "",
    @ColumnInfo(defaultValue = "'PARAMETRIC'") val modelSource: String = "PARAMETRIC",
    @ColumnInfo(defaultValue = "0") val polygonCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val scanSessionId: String = "",
    @ColumnInfo(defaultValue = "0") val scaleConfirmed: Boolean = false
)
