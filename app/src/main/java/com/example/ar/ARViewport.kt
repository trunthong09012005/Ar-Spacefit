package com.example.ar

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.spacefit.FurnitureFootprint
import com.example.spacefit.RoomSpec
import com.example.spacefit.SpaceFitEngine
import com.example.ui.theme.ArCyan
import com.example.ui.theme.ArEmerald
import java.util.Locale
import kotlin.math.roundToInt

data class MeasurePoint(val point3D: Vec3, val screenOffset: Offset)

private fun PlacedFurniture.toFootprint() = FurnitureFootprint(
    id = id,
    name = name,
    centerXcm = posX * 100f,
    centerZcm = posZ * 100f,
    widthCm = widthCm,
    depthCm = depthCm,
    rotationDegrees = rotationY
)

@Composable
fun ARViewport(
    furnitureList: List<PlacedFurniture>,
    selectedFurnitureId: Long?,
    onSelectFurniture: (Long) -> Unit,
    onUpdateFurniture: (PlacedFurniture) -> Unit,
    onDeleteFurniture: (Long) -> Unit,
    roomSpec: RoomSpec,
    backgroundMode: String, // "ROOM_PHOTO", "CAMERA", "STUDIO_GRID"
    cameraComposable: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var camera by remember { mutableStateOf(Camera3D()) }
    var isMeasureMode by remember { mutableStateOf(false) }
    var measurePoints by remember { mutableStateOf<List<Vec3>>(emptyList()) }
    var activeControlTab by remember { mutableStateOf(0) } // 0: Di chuyển, 1: Xoay & Tỷ lệ, 2: Đo đạc

    val selectedFurniture = furnitureList.find { it.id == selectedFurnitureId }
    val selectedEvaluation = selectedFurniture?.let { selected ->
        SpaceFitEngine.evaluate(
            room = roomSpec,
            selected = selected.toFootprint(),
            otherFurniture = furnitureList.map { it.toFootprint() }
        )
    }

    val transformState = rememberTransformableState { zoomChange, _, _ ->
        camera = camera.copy(
            distance = (camera.distance / zoomChange).coerceIn(1.8f, 10.0f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("ar_viewport_container")
    ) {
        // Background layer: Camera Feed or Realistic Room Photo or Studio Grid
        when (backgroundMode) {
            "CAMERA" -> {
                cameraComposable()
                // AR scanline overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.15f))
                )
            }
            "ROOM_PHOTO" -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(R.drawable.bg_sample_room)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Sample Room",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f))
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A))
                            )
                        )
                )
            }
        }

        // 3D Canvas Rendering
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformState)
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        if (isMeasureMode) {
                            // Convert tap into floor-plane Vec3 intersection
                            val clickedWorld = unprojectToFloor(tapOffset, size.width.toFloat(), size.height.toFloat(), camera)
                            measurePoints = if (measurePoints.size >= 2) {
                                listOf(clickedWorld)
                            } else {
                                measurePoints + clickedWorld
                            }
                        } else {
                            // Find clicked furniture
                            val clicked = furnitureList.find { f ->
                                val proj = camera.project(Vec3(f.posX, f.posY + 0.3f, f.posZ), size.width.toFloat(), size.height.toFloat())
                                (tapOffset - proj.offset).getDistance() < 120f
                            }
                            if (clicked != null) {
                                onSelectFurniture(clicked.id)
                            }
                        }
                    }
                }
                .pointerInput(selectedFurnitureId, isMeasureMode) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        if (!isMeasureMode && selectedFurniture != null) {
                            // Move furniture along 3D camera plane
                            val radYaw = Math.toRadians(camera.yaw.toDouble()).toFloat()
                            val forwardX = -kotlin.math.sin(radYaw)
                            val forwardZ = kotlin.math.cos(radYaw)
                            val rightX = kotlin.math.cos(radYaw)
                            val rightZ = kotlin.math.sin(radYaw)

                            val sensitivity = 0.005f
                            val deltaX = (dragAmount.x * rightX - dragAmount.y * forwardX) * sensitivity
                            val deltaZ = (dragAmount.x * rightZ - dragAmount.y * forwardZ) * sensitivity

                            val updated = selectedFurniture.copy(
                                posX = (selectedFurniture.posX + deltaX).coerceIn(-4f, 4f),
                                posZ = (selectedFurniture.posZ + deltaZ).coerceIn(-4f, 4f)
                            )
                            onUpdateFurniture(updated)
                        } else {
                            // Orbit camera
                            camera = camera.copy(
                                yaw = (camera.yaw + dragAmount.x * 0.4f) % 360f,
                                pitch = (camera.pitch - dragAmount.y * 0.3f).coerceIn(10f, 80f)
                            )
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // 1. Draw 3D floor grid
            draw3DFloorGrid(camera, w, h)

            // 2. Collect all faces from all placed furniture
            data class DrawableFace(
                val face: PolygonFace,
                val avgDepth: Float,
                val isSelected: Boolean
            )

            val allDrawableFaces = mutableListOf<DrawableFace>()

            // Shadow beneath furniture
            for (f in furnitureList) {
                drawFurnitureShadow(f, camera, w, h)
                val mesh = f.getTransformedMesh()
                for (face in mesh.faces) {
                    val projected = face.vertices.map { camera.project(it, w, h) }
                    val avgZ = projected.map { it.depth }.average().toFloat()
                    allDrawableFaces.add(DrawableFace(face, avgZ, f.id == selectedFurnitureId))
                }
            }

            // Sort by depth for painter's algorithm
            allDrawableFaces.sortByDescending { it.avgDepth }

            // Directional light vector (top-left-front)
            val lightDir = Vec3(0.5f, 0.8f, 0.4f)

            // 3. Render 3D faces with diffuse shading
            for (df in allDrawableFaces) {
                val proj = df.face.vertices.map { camera.project(it, w, h) }
                if (proj.all { it.depth > 0.2f }) {
                    val path = Path().apply {
                        moveTo(proj[0].offset.x, proj[0].offset.y)
                        for (i in 1 until proj.size) {
                            lineTo(proj[i].offset.x, proj[i].offset.y)
                        }
                        close()
                    }

                    // Calculate lighting intensity
                    val n = df.face.normal
                    val dot = (n.x * lightDir.x + n.y * lightDir.y + n.z * lightDir.z).coerceIn(-1f, 1f)
                    val intensity = ((dot + 1f) / 2f).coerceIn(0.45f, 1.0f)

                    val shadedColor = Color(
                        red = (df.face.baseColor.red * intensity).coerceIn(0f, 1f),
                        green = (df.face.baseColor.green * intensity).coerceIn(0f, 1f),
                        blue = (df.face.baseColor.blue * intensity).coerceIn(0f, 1f),
                        alpha = df.face.baseColor.alpha
                    )

                    drawPath(path, shadedColor)
                    drawPath(
                        path,
                        if (df.isSelected) ArCyan.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.25f),
                        style = Stroke(width = if (df.isSelected) 2f else 1f)
                    )
                }
            }

            // 4. Render selection bounding box & dimension labels for active item
            if (selectedFurniture != null) {
                drawBoundingBoxWithLabels(
                    selectedFurniture,
                    camera,
                    w,
                    h,
                    if (selectedEvaluation?.isSuitable == true) ArEmerald else Color(0xFFEF4444)
                )
            }

            // 5. Render AR Measurement Laser and Points
            if (measurePoints.isNotEmpty()) {
                drawMeasurementOverlay(measurePoints, camera, w, h)
            }
        }

            // Selected item score appears below the studio toolbar.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 146.dp)
        ) {

            if (selectedFurniture != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = selectedFurniture.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Kích thước: ${selectedFurniture.widthCm.roundToInt()}x${selectedFurniture.heightCm.roundToInt()}x${selectedFurniture.depthCm.roundToInt()} cm",
                            color = ArCyan,
                            fontSize = 11.sp
                        )
                        if (selectedEvaluation != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${selectedEvaluation.score}",
                                    color = if (selectedEvaluation.isSuitable) ArEmerald else Color(0xFFEF4444),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "SPACEFIT SCORE",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (selectedEvaluation.isSuitable) "Vị trí phù hợp" else "Cần điều chỉnh",
                                        color = if (selectedEvaluation.isSuitable) ArEmerald else Color(0xFFEF4444),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Cách tường ${selectedEvaluation.minimumWallClearanceCm.coerceAtLeast(0f).roundToInt()} cm • Chiếm ${selectedEvaluation.occupancyPercent.roundToInt()}% phòng",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 9.sp
                            )
                            Text(
                                text = selectedEvaluation.messages.first(),
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // Secondary canvas tools stay separate from the main studio actions.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 146.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Measure Tape Toggle
            FilledTonalIconButton(
                onClick = {
                    isMeasureMode = !isMeasureMode
                    if (isMeasureMode) measurePoints = emptyList()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isMeasureMode) ArEmerald else Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("toggle_measure_mode")
            ) {
                Icon(
                    imageVector = Icons.Default.Straighten,
                    contentDescription = "Thước đo AR"
                )
            }

            // Reset Camera
            FilledTonalIconButton(
                onClick = {
                    camera = Camera3D()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("reset_camera")
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Góc nhìn chuẩn"
                )
            }
        }

        // Bottom AR Control Panel for Selected Furniture & Precision Manipulation
        if (selectedFurniture != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("ar_controls_panel"),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, ArCyan.copy(alpha = 0.35f)),
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Control Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = activeControlTab == 0,
                                onClick = { activeControlTab = 0 },
                                label = { Text("Di chuyển", fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.OpenWith, null, modifier = Modifier.size(16.dp))
                                }
                            )
                            FilterChip(
                                selected = activeControlTab == 1,
                                onClick = { activeControlTab = 1 },
                                label = { Text("Xoay & Tỷ lệ", fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }

                        IconButton(
                            onClick = { onDeleteFurniture(selectedFurniture.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, "Xóa", tint = Color(0xFFEF4444))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    when (activeControlTab) {
                        0 -> {
                            // D-Pad for Precision 3D Movement
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Step move buttons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            onUpdateFurniture(selectedFurniture.copy(posX = selectedFurniture.posX - 0.1f))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                                        Text("Trái", fontSize = 11.sp)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            onUpdateFurniture(selectedFurniture.copy(posX = selectedFurniture.posX + 0.1f))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text("Phải", fontSize = 11.sp)
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            onUpdateFurniture(selectedFurniture.copy(posZ = selectedFurniture.posZ - 0.1f))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(18.dp))
                                        Text("Tiến", fontSize = 11.sp)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            onUpdateFurniture(selectedFurniture.copy(posZ = selectedFurniture.posZ + 0.1f))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text("Lùi", fontSize = 11.sp)
                                        Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        1 -> {
                            // Rotation & Scale Controls
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Góc xoay: ${selectedFurniture.rotationY.roundToInt()}°", color = Color.White, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                onUpdateFurniture(
                                                    selectedFurniture.copy(rotationY = (selectedFurniture.rotationY - 45f + 360f) % 360f)
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Text("-45°", fontSize = 11.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                onUpdateFurniture(
                                                    selectedFurniture.copy(rotationY = (selectedFurniture.rotationY + 45f) % 360f)
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Text("+45°", fontSize = 11.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Tỷ lệ: ${(selectedFurniture.widthCm).roundToInt()}cm", color = Color.White, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                val factor = 0.9f
                                                onUpdateFurniture(
                                                    selectedFurniture.copy(
                                                        widthCm = (selectedFurniture.widthCm * factor).coerceAtLeast(20f),
                                                        heightCm = (selectedFurniture.heightCm * factor).coerceAtLeast(20f),
                                                        depthCm = (selectedFurniture.depthCm * factor).coerceAtLeast(20f)
                                                    )
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Text("-10%", fontSize = 11.sp)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                val factor = 1.1f
                                                onUpdateFurniture(
                                                    selectedFurniture.copy(
                                                        widthCm = (selectedFurniture.widthCm * factor).coerceAtMost(350f),
                                                        heightCm = (selectedFurniture.heightCm * factor).coerceAtMost(300f),
                                                        depthCm = (selectedFurniture.depthCm * factor).coerceAtMost(350f)
                                                    )
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Text("+10%", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Measure Mode Help / Result Banner
        if (isMeasureMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF064E3B).copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(1.dp, ArEmerald)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (measurePoints.size < 2) {
                            Text(
                                text = "Chạm vào sàn để chọn ${if (measurePoints.isEmpty()) "điểm đầu tiên" else "điểm thứ hai"}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tự động đo đạc khoảng cách chính xác theo cm",
                                color = ArEmerald.copy(alpha = 0.9f),
                                fontSize = 11.sp
                            )
                        } else {
                            val distCm = (measurePoints[0].distanceTo(measurePoints[1]) * 100f).roundToInt()
                            Text(
                                text = "KHOẢNG CÁCH MÔ PHỎNG: $distCm cm (${String.format(Locale.US, "%.2f", distCm / 100f)} m)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedFurniture != null) {
                                val diff = distCm - selectedFurniture.widthCm.roundToInt()
                                val fitText = if (diff >= 0) {
                                    "Vừa vặn! Khoảng trống còn dư $diff cm"
                                } else {
                                    "Cảnh báo: Không gian hẹp hơn món đồ ${-diff} cm!"
                                }
                                Text(
                                    text = fitText,
                                    color = if (diff >= 0) Color(0xFF34D399) else Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (measurePoints.isNotEmpty()) {
                        IconButton(onClick = { measurePoints = emptyList() }) {
                            Icon(Icons.Default.Refresh, "Đo lại", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// Draw 3D floor grid lines with perspective
private fun DrawScope.draw3DFloorGrid(camera: Camera3D, w: Float, h: Float) {
    val gridColor = Color(0xFF38BDF8).copy(alpha = 0.22f)
    val centerColor = Color(0xFF38BDF8).copy(alpha = 0.5f)

    val range = 4
    val step = 0.5f // 50cm per grid line

    for (i in -range..range) {
        val x = i * step
        val pStart = camera.project(Vec3(x, 0f, -range * step), w, h)
        val pEnd = camera.project(Vec3(x, 0f, range * step), w, h)
        if (pStart.depth > 0.2f && pEnd.depth > 0.2f) {
            drawLine(
                color = if (i == 0) centerColor else gridColor,
                start = pStart.offset,
                end = pEnd.offset,
                strokeWidth = if (i == 0) 1.8f else 1.0f
            )
        }

        val z = i * step
        val pZStart = camera.project(Vec3(-range * step, 0f, z), w, h)
        val pZEnd = camera.project(Vec3(range * step, 0f, z), w, h)
        if (pZStart.depth > 0.2f && pZEnd.depth > 0.2f) {
            drawLine(
                color = if (i == 0) centerColor else gridColor,
                start = pZStart.offset,
                end = pZEnd.offset,
                strokeWidth = if (i == 0) 1.8f else 1.0f
            )
        }
    }
}

// Draw soft oval contact shadow on floor
private fun DrawScope.drawFurnitureShadow(f: PlacedFurniture, camera: Camera3D, w: Float, h: Float) {
    val hw = f.widthCm / 200f
    val hd = f.depthCm / 200f
    val p0 = camera.project(Vec3(f.posX - hw, 0.01f, f.posZ - hd), w, h)
    val p1 = camera.project(Vec3(f.posX + hw, 0.01f, f.posZ - hd), w, h)
    val p2 = camera.project(Vec3(f.posX + hw, 0.01f, f.posZ + hd), w, h)
    val p3 = camera.project(Vec3(f.posX - hw, 0.01f, f.posZ + hd), w, h)

    if (p0.depth > 0.2f && p2.depth > 0.2f) {
        val path = Path().apply {
            moveTo(p0.offset.x, p0.offset.y)
            lineTo(p1.offset.x, p1.offset.y)
            lineTo(p2.offset.x, p2.offset.y)
            lineTo(p3.offset.x, p3.offset.y)
            close()
        }
        drawPath(path, Color.Black.copy(alpha = 0.35f))
    }
}

// Draw neon selection bounding box and dimension labels in 3D
private fun DrawScope.drawBoundingBoxWithLabels(
    f: PlacedFurniture,
    camera: Camera3D,
    w: Float,
    h: Float,
    indicatorColor: Color = ArCyan
) {
    val hw = f.widthCm / 200f
    val hh = f.heightCm / 100f
    val hd = f.depthCm / 200f

    val corners = listOf(
        Vec3(f.posX - hw, f.posY, f.posZ - hd),
        Vec3(f.posX + hw, f.posY, f.posZ - hd),
        Vec3(f.posX + hw, f.posY, f.posZ + hd),
        Vec3(f.posX - hw, f.posY, f.posZ + hd),
        Vec3(f.posX - hw, f.posY + hh, f.posZ - hd),
        Vec3(f.posX + hw, f.posY + hh, f.posZ - hd),
        Vec3(f.posX + hw, f.posY + hh, f.posZ + hd),
        Vec3(f.posX - hw, f.posY + hh, f.posZ + hd)
    ).map { camera.project(it, w, h) }

    val neon = indicatorColor

    // Draw box edges
    val edges = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0, // bottom
        4 to 5, 5 to 6, 6 to 7, 7 to 4, // top
        0 to 4, 1 to 5, 2 to 6, 3 to 7  // vertical
    )

    for ((i1, i2) in edges) {
        if (corners[i1].depth > 0.2f && corners[i2].depth > 0.2f) {
            drawLine(
                color = neon.copy(alpha = 0.85f),
                start = corners[i1].offset,
                end = corners[i2].offset,
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }
    }

    // Floating dimension text at center top
    val topCenter = camera.project(Vec3(f.posX, f.posY + hh + 0.15f, f.posZ), w, h)
    if (topCenter.depth > 0.2f) {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                isAntiAlias = true
                isFakeBoldText = true
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
            }
            val text = "${f.widthCm.roundToInt()} x ${f.heightCm.roundToInt()} x ${f.depthCm.roundToInt()} cm"
            val textW = paint.measureText(text)
            canvas.nativeCanvas.drawText(text, topCenter.offset.x - textW / 2f, topCenter.offset.y, paint)
        }
    }
}

// Draw AR Measurement tape between points
private fun DrawScope.drawMeasurementOverlay(points: List<Vec3>, camera: Camera3D, w: Float, h: Float) {
    val emerald = ArEmerald

    for ((idx, p) in points.withIndex()) {
        val proj = camera.project(p, w, h)
        if (proj.depth > 0.2f) {
            drawCircle(
                color = emerald,
                radius = 12f,
                center = proj.offset
            )
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = proj.offset
            )
        }
    }

    if (points.size >= 2) {
        val p1 = camera.project(points[0], w, h)
        val p2 = camera.project(points[1], w, h)

        if (p1.depth > 0.2f && p2.depth > 0.2f) {
            // Laser measurement line
            drawLine(
                color = emerald,
                start = p1.offset,
                end = p2.offset,
                strokeWidth = 4f
            )

            // Measurement badge at midpoint
            val midOffset = Offset((p1.offset.x + p2.offset.x) / 2f, (p1.offset.y + p2.offset.y) / 2f - 18f)
            val distCm = (points[0].distanceTo(points[1]) * 100f).roundToInt()

            drawIntoCanvas { canvas ->
                val text = "$distCm cm"
                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 34f
                    isAntiAlias = true
                    isFakeBoldText = true
                    setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                }
                val textW = paint.measureText(text)
                canvas.nativeCanvas.drawText(text, midOffset.x - textW / 2f, midOffset.y, paint)
            }
        }
    }
}

// Simple Raycast unproject from screen tap to Y=0 floor plane
private fun unprojectToFloor(tap: Offset, w: Float, h: Float, camera: Camera3D): Vec3 {
    // Normalizing screen coordinates (-1 to 1)
    val fovFactor = (w.coerceAtLeast(h)) * 0.85f
    val nx = (tap.x - w / 2f) / fovFactor
    val ny = -(tap.y - h / 2f) / fovFactor

    val radYaw = Math.toRadians(camera.yaw.toDouble()).toFloat()
    val radPitch = Math.toRadians(camera.pitch.toDouble()).toFloat()

    // Estimate floor intersection based on camera pitch
    val groundDist = (camera.distance * kotlin.math.cos(radPitch)).coerceIn(1f, 8f)
    val worldX = nx * groundDist
    val worldZ = ny * groundDist

    val rotX = worldX * kotlin.math.cos(radYaw) - worldZ * kotlin.math.sin(radYaw)
    val rotZ = worldX * kotlin.math.sin(radYaw) + worldZ * kotlin.math.cos(radYaw)

    return Vec3(rotX, 0f, rotZ)
}
