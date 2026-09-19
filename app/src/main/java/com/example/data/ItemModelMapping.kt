package com.example.data

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import com.example.ar.ModelParameters
import com.example.ar.PlacedFurniture

/** One mapping for preview, inventory and all room entry points. */
fun ItemEntity.toPlacedFurniture(
    instanceId: Long = System.currentTimeMillis(),
    posX: Float = 0f,
    posZ: Float = 1f
) = PlacedFurniture(
    id = instanceId,
    name = name,
    modelType = modelType3D,
    posX = posX,
    posY = 0f,
    posZ = posZ,
    rotationY = 0f,
    widthCm = widthCm,
    heightCm = heightCm,
    depthCm = depthCm,
    color = runCatching { Color(colorHex.toColorInt()) }.getOrDefault(Color.Gray),
    material = material,
    modelParameters = ModelParameters.fromJson(modelParametersJson, modelType3D)
)
