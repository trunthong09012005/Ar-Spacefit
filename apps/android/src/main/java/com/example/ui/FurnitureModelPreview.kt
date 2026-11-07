package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ar.modelTypeLabels
import java.util.Locale
import com.example.ar.Camera3D
import com.example.ar.Vec3
import com.example.data.ItemEntity
import com.example.data.toPlacedFurniture

@Composable
fun FurnitureModelPreview(item: ItemEntity, modifier: Modifier = Modifier) {
    if (item.modelPath.isNotBlank()) {
        GlbViewer(item.modelPath, modifier)
        return
    }
    var yaw by remember { mutableFloatStateOf(145f) }
    var pitch by remember { mutableFloatStateOf(-22f) }
    val mesh = remember(item) { item.toPlacedFurniture(instanceId = 0, posZ = 0f).getTransformedMesh() }
    val extent = maxOf(item.widthCm, item.heightCm, item.depthCm) / 100f
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(
        Brush.linearGradient(listOf(Color(0xFF111E33), Color(0xFF08101E)))
    )) {
        Canvas(Modifier.fillMaxSize().testTag("furniture_model_preview")
            .semantics { contentDescription = "Mô hình 3D ${item.name}. Kéo để xoay xem nhiều phía." }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    yaw = (yaw + drag.x * 0.5f) % 360f
                    pitch = (pitch - drag.y * 0.4f).coerceIn(-80f, 70f)
                }
            }) {
            if (!extent.isFinite() || extent <= 0f) return@Canvas
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x3367E8F9), Color.Transparent)),
                radius = size.minDimension * 0.46f,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.52f)
            )
            val gridColor = Color.White.copy(alpha = 0.045f)
            repeat(7) { index ->
                val y = size.height * (0.58f + index * 0.065f)
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
            }
            repeat(9) { index ->
                val x = size.width * index / 8f
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.52f),
                    androidx.compose.ui.geometry.Offset(x, size.height))
            }
            val camera = Camera3D(
                yaw = yaw, pitch = pitch,
                distance = 2.55f,
                target = Vec3(0f, item.heightCm / 100f / extent / 2f, 0f)
            )
            // Normalize only for camera framing; the item's real dimensions remain unchanged.
            val faces = mesh.faces.map { face ->
                face to face.vertices.map { camera.project(it * (1f / extent), size.width, size.height) }
            }.sortedByDescending { (_, points) -> points.map { it.depth }.average() }
            faces.forEach { (face, points) ->
                val path = Path().apply {
                    moveTo(points.first().offset.x, points.first().offset.y)
                    points.drop(1).forEach { lineTo(it.offset.x, it.offset.y) }
                    close()
                }
                val light = (0.78f + face.normal.y * 0.16f - face.normal.x * 0.08f).coerceIn(0.5f, 1f)
                drawPath(path, Color(face.baseColor.red * light, face.baseColor.green * light,
                    face.baseColor.blue * light, face.baseColor.alpha))
                drawPath(path, Color.White.copy(alpha = 0.12f), style = Stroke(0.7.dp.toPx()))
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.42f), shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        ) {
            Text(modelTypeLabels[item.modelType3D] ?: "Mô hình", fontSize = 11.sp,
                color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Text("↔  Kéo để xoay", fontSize = 11.sp, color = Color(0xFFB8CADF),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        Text("${formatCm(item.widthCm)} × ${formatCm(item.heightCm)} × ${formatCm(item.depthCm)} cm",
            fontSize = 11.sp, color = Color(0xFFB8CADF),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        TextButton(onClick = { yaw = 145f; pitch = -22f }, modifier = Modifier.align(Alignment.TopEnd),
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB8CADF))) {
            Text("Đặt lại góc", fontSize = 11.sp)
        }
    }
}

private fun formatCm(value: Float): String = if (value == value.toInt().toFloat()) {
    value.toInt().toString()
} else String.format(Locale.US, "%.1f", value)
