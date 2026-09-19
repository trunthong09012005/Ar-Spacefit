package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ai.GeminiService
import com.example.ai.RecognizedItemResult
import com.example.camera.CameraPreview
import com.example.camera.TrackedObject
import com.example.camera.capturePhoto
import com.example.camera.clearTemporaryScanImages
import com.example.camera.cropToTrackedObject
import com.example.camera.decodeSampledBitmap
import com.example.data.ItemEntity
import com.example.ui.theme.ArCyan
import com.example.ui.theme.ArEmerald
import com.example.ui.theme.ArPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val requiredScanAngles = listOf(
    "Tổng thể",
    "Bên trái",
    "Bên phải",
    "Từ trên",
    "Từ dưới"
)

private val scanAngleInstructions = listOf(
    "Lùi lại để thấy toàn bộ món đồ",
    "Di chuyển camera sang phía trái món đồ",
    "Di chuyển camera sang phía phải món đồ",
    "Đưa camera cao hơn và hướng xuống",
    "Hạ camera thấp hơn và hướng lên"
)

@Composable
fun ScannerScreen(
    onItemSaved: (ItemEntity) -> Unit,
    onTestInARRoom: (ItemEntity) -> Unit,
    onCameraPermissionChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geminiService = remember { GeminiService() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val permissionPreferences = remember {
        context.getSharedPreferences("spacefit_permissions", Context.MODE_PRIVATE)
    }

    DisposableEffect(cameraExecutor) {
        onDispose { cameraExecutor.shutdown() }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { clearTemporaryScanImages(context) }
    }

    // Camera permissions
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraPermissionRequestedBefore by remember {
        mutableStateOf(permissionPreferences.getBoolean("camera_requested", false))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        cameraPermissionRequestedBefore = true
        permissionPreferences.edit { putBoolean("camera_requested", true) }
        onCameraPermissionChanged(granted)
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !cameraPermissionRequestedBefore) {
            cameraPermissionRequestedBefore = true
            permissionPreferences.edit { putBoolean("camera_requested", true) }
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                hasCameraPermission = granted
                onCameraPermissionChanged(granted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraPermissionPermanentlyDenied = !hasCameraPermission &&
        cameraPermissionRequestedBefore &&
        activity?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } == true

    val openCameraSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    // Camera control states
    var isFrontCamera by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    var cameraRestartKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(isFrontCamera) {
        if (isFrontCamera) isTorchOn = false
    }

    LaunchedEffect(hasCameraPermission, isFrontCamera, cameraRestartKey, isCameraReady) {
        if (hasCameraPermission && !isCameraReady) {
            delay(10_000)
            if (!isCameraReady) {
                cameraErrorMessage = "Camera chưa trả hình. Hãy nhấn Thử lại hoặc kiểm tra Camera trong Cài đặt."
            }
        }
    }

    // Multi-angle capture and AI analysis states
    var isCapturing by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isScanSessionActive by remember { mutableStateOf(false) }
    var trackedObject by remember { mutableStateOf<TrackedObject?>(null) }
    var previousTrackedObject by remember { mutableStateOf<TrackedObject?>(null) }
    var stableDetectionFrames by remember { mutableIntStateOf(0) }
    var captureArmed by remember { mutableStateOf(false) }
    var capturedAngles by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var recognizedItem by remember { mutableStateOf<RecognizedItemResult?>(null) }
    var scanErrorMessage by remember { mutableStateOf<String?>(null) }
    val isBusy = isCapturing || isAnalyzing
    val isObjectLocked = isScanSessionActive &&
        trackedObject?.let {
            it.areaFraction in 0.04f..0.92f && it.width >= 0.12f && it.height >= 0.12f
        } == true && stableDetectionFrames >= 3

    LaunchedEffect(isScanSessionActive, capturedAngles.size) {
        captureArmed = false
        if (isScanSessionActive) {
            delay(1_200)
            captureArmed = true
        }
    }

    val analyzeCapturedAngles: (List<Bitmap>) -> Unit = { images ->
        if (images.size >= requiredScanAngles.size && !isAnalyzing) {
            coroutineScope.launch {
                isAnalyzing = true
                scanErrorMessage = null
                try {
                    recognizedItem = geminiService.analyzeItemFromImages(images.take(requiredScanAngles.size))
                    capturedAngles = emptyList()
                } catch (error: Exception) {
                    scanErrorMessage = error.message ?: "Không thể phân tích năm hướng quét."
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    val addCapturedAngle: (Bitmap) -> Unit = { bitmap ->
        if (capturedAngles.size < requiredScanAngles.size) {
            val updatedImages = capturedAngles + bitmap
            capturedAngles = updatedImages
            if (updatedImages.size == requiredScanAngles.size) {
                isScanSessionActive = false
                trackedObject = null
                previousTrackedObject = null
                stableDetectionFrames = 0
                captureArmed = false
                analyzeCapturedAngles(updatedImages)
            }
        }
    }

    // Each selected gallery image fills the next required angle.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isCapturing = true
                scanErrorMessage = null
                try {
                    val bitmap = loadBitmapFromUri(context, uri)
                    if (bitmap != null) {
                        addCapturedAngle(bitmap)
                    } else {
                        scanErrorMessage = "Không thể đọc tệp ảnh."
                    }
                } catch (e: Exception) {
                    scanErrorMessage = e.message ?: "Không thể phân tích ảnh."
                } finally {
                    isCapturing = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("scanner_screen")
    ) {
        // 1. Camera Viewfinder or Placeholder
        if (hasCameraPermission) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                isFrontCamera = isFrontCamera,
                isTorchOn = isTorchOn,
                restartKey = cameraRestartKey,
                objectDetectionEnabled = isScanSessionActive,
                imageCaptureCallback = {
                    imageCaptureInstance = it
                    if (it == null) isCameraReady = false
                },
                onObjectDetected = { detected ->
                    if (isScanSessionActive) {
                        if (detected == null) {
                            trackedObject = null
                            previousTrackedObject = null
                            stableDetectionFrames = 0
                        } else {
                            stableDetectionFrames = if (
                                previousTrackedObject?.let { previous ->
                                    isSameTrackedObject(previous, detected)
                                } == true
                            ) {
                                (stableDetectionFrames + 1).coerceAtMost(10)
                            } else {
                                1
                            }
                            previousTrackedObject = detected
                            trackedObject = detected
                        }
                    }
                },
                onObjectDetectionError = { message ->
                    scanErrorMessage = message
                },
                onCameraReady = {
                    isCameraReady = true
                    cameraErrorMessage = null
                },
                onCameraError = { message ->
                    isCameraReady = false
                    cameraErrorMessage = message
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        null,
                        tint = ArCyan,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Cần quyền Camera để quét đồ đạc",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (cameraPermissionPermanentlyDenied) {
                            "Quyền Camera đã bị chặn. Hãy mở Cài đặt và bật quyền Camera cho SpaceFit."
                        } else {
                            "Bạn có thể cấp quyền hoặc chọn ảnh sản phẩm từ thư viện"
                        },
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (cameraPermissionPermanentlyDenied) {
                                openCameraSettings()
                            } else {
                                cameraPermissionRequestedBefore = true
                                permissionPreferences.edit { putBoolean("camera_requested", true) }
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ArCyan)
                    ) {
                        Text(
                            if (cameraPermissionPermanentlyDenied) "Mở Cài đặt" else "Cấp quyền Camera",
                            color = Color.Black
                        )
                    }
                }
            }
        }

        // Dynamic bounds follow the detected object; there is no fixed capture frame.
        if (isScanSessionActive && hasCameraPermission) {
            ObjectTrackingOverlay(
                trackedObject = trackedObject,
                isLocked = isObjectLocked,
                mirrorHorizontally = isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Compact camera toolbar: one clear title and three predictable controls.
        Surface(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.72f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier.padding(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Đóng", tint = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Quét nội thất", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("5 góc nhìn  •  nhận diện bằng AI", color = ArCyan, fontSize = 10.sp)
                }
                IconButton(
                    onClick = { isTorchOn = !isTorchOn },
                    enabled = hasCameraPermission && isCameraReady && !isFrontCamera,
                    modifier = Modifier.size(42.dp)
                        .background(if (isTorchOn) ArCyan else Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Bật hoặc tắt đèn", tint = if (isTorchOn) Color.Black else Color.White)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        isCameraReady = false
                        cameraErrorMessage = null
                        isFrontCamera = !isFrontCamera
                    },
                    enabled = hasCameraPermission,
                    modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Đổi camera", tint = Color.White)
                }
            }
        }

        // Bottom Capture & Action Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val visibleErrorMessage = cameraErrorMessage ?: scanErrorMessage
            if (visibleErrorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF7F1D1D).copy(alpha = 0.94f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .testTag("scan_error_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = visibleErrorMessage,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (cameraErrorMessage != null && hasCameraPermission) {
                            TextButton(
                                onClick = {
                                    cameraErrorMessage = null
                                    isCameraReady = false
                                    cameraRestartKey++
                                }
                            ) {
                                Text("Thử lại", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = { scanErrorMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Đóng thông báo", tint = Color.White)
                            }
                        }
                    }
                }
            }

            val scanStatusText = when {
                isAnalyzing -> "AI đang đối chiếu năm góc nhìn..."
                isCapturing -> "Đang lưu góc ${capturedAngles.size + 1}/5..."
                hasCameraPermission && !isCameraReady -> "Đang khởi động camera..."
                !isScanSessionActive && capturedAngles.isEmpty() -> "Nhấn nút quét để bắt đầu nhận diện vật thể"
                !isScanSessionActive -> "Đã chọn ${capturedAngles.size}/5 ảnh từ thư viện"
                trackedObject == null -> "Di chuyển chậm để camera tìm món đồ"
                !isObjectLocked -> "Giữ máy ổn định để khóa phạm vi vật thể"
                !captureArmed -> "Đã khóa vật thể • chuẩn bị góc tiếp theo"
                capturedAngles.size < requiredScanAngles.size -> {
                    val index = capturedAngles.size
                    "${requiredScanAngles[index]} • ${scanAngleInstructions[index]}"
                }
                else -> "Đã đủ năm góc nhìn • sẵn sàng phân tích"
            }

            MultiAngleCaptureProgress(
                images = capturedAngles,
                angleLabels = requiredScanAngles,
                statusText = scanStatusText,
                showReset = isScanSessionActive || capturedAngles.isNotEmpty(),
                onReset = {
                    if (!isBusy) {
                        isScanSessionActive = false
                        trackedObject = null
                        previousTrackedObject = null
                        stableDetectionFrames = 0
                        captureArmed = false
                        capturedAngles = emptyList()
                        scanErrorMessage = null
                    }
                }
            )


            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pick photo from Gallery (online product / saved screenshot)
                IconButton(
                    enabled = !isBusy && !isScanSessionActive && capturedAngles.size < requiredScanAngles.size,
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        .testTag("pick_gallery_image_button")
                ) {
                    Icon(Icons.Default.PhotoLibrary, "Chọn ảnh sản phẩm", tint = Color.White)
                }

                // Shutter Camera Snap Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                        .padding(6.dp)
                        .testTag("camera_shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        enabled = !isBusy && when {
                            capturedAngles.size >= requiredScanAngles.size -> true
                            !isScanSessionActive -> hasCameraPermission && isCameraReady
                            else -> hasCameraPermission && isCameraReady && isObjectLocked && captureArmed
                        },
                        onClick = {
                            if (capturedAngles.size >= requiredScanAngles.size) {
                                analyzeCapturedAngles(capturedAngles)
                            } else if (!isScanSessionActive) {
                                capturedAngles = emptyList()
                                trackedObject = null
                                previousTrackedObject = null
                                stableDetectionFrames = 0
                                captureArmed = false
                                scanErrorMessage = null
                                isScanSessionActive = true
                            } else if (isObjectLocked && captureArmed) {
                                val objectAtCapture = trackedObject
                                isCapturing = true
                                scanErrorMessage = null
                                capturePhoto(
                                    context = context,
                                    imageCapture = imageCaptureInstance,
                                    executor = cameraExecutor,
                                    onSuccess = { bitmap ->
                                        val cropped = objectAtCapture?.let { detected ->
                                            bitmap.cropToTrackedObject(detected)
                                        } ?: bitmap
                                        if (cropped !== bitmap) bitmap.recycle()
                                        addCapturedAngle(cropped)
                                        stableDetectionFrames = 0
                                        captureArmed = false
                                        isCapturing = false
                                    },
                                    onError = { error ->
                                        scanErrorMessage = error.message ?: "Không thể chụp ảnh."
                                        isCapturing = false
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                isBusy -> ArPurple
                                isObjectLocked -> ArEmerald
                                else -> Color.White
                            }
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else if (capturedAngles.size >= requiredScanAngles.size) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Phân tích năm hướng",
                                tint = Color.Black,
                                modifier = Modifier.size(30.dp)
                            )
                        } else if (!isScanSessionActive) {
                            Icon(
                                Icons.Default.DocumentScanner,
                                contentDescription = "Bắt đầu quét vật thể",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        } else if (!isObjectLocked) {
                            Icon(
                                Icons.Default.CenterFocusWeak,
                                contentDescription = "Đang tìm vật thể",
                                tint = Color.DarkGray,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Lưu hướng quét hiện tại",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.size(54.dp))
            }
        }

        // The editor uses the same mesh/parameters as inventory and room placement.
        recognizedItem?.let { item ->
            val editableItem = remember(item) {
                ItemEntity(
                    name = item.name, category = item.category, roomType = item.roomType,
                    widthCm = item.widthCm, heightCm = item.heightCm, depthCm = item.depthCm,
                    colorHex = item.colorHex, material = item.material, notes = item.advice,
                    modelType3D = item.modelType3D, modelParametersJson = item.modelParameters.toJson()
                )
            }
            FurnitureModelEditor(
                item = editableItem,
                onSave = { onItemSaved(it); recognizedItem = null },
                onPlace = {
                    onItemSaved(it)
                    recognizedItem = null
                    onTestInARRoom(it)
                },
                onDismiss = { recognizedItem = null }
            )
        }
    }
}

@Composable
private fun ObjectTrackingOverlay(
    trackedObject: TrackedObject?,
    isLocked: Boolean,
    mirrorHorizontally: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedLeft by animateFloatAsState(trackedObject?.left ?: 0f, label = "object_left")
    val animatedTop by animateFloatAsState(trackedObject?.top ?: 0f, label = "object_top")
    val animatedRight by animateFloatAsState(trackedObject?.right ?: 1f, label = "object_right")
    val animatedBottom by animateFloatAsState(trackedObject?.bottom ?: 1f, label = "object_bottom")

    Canvas(modifier = modifier.testTag("tracked_object_overlay")) {
        val detection = trackedObject ?: return@Canvas
        val sourceWidth = detection.sourceWidth.toFloat().coerceAtLeast(1f)
        val sourceHeight = detection.sourceHeight.toFloat().coerceAtLeast(1f)
        val previewScale = maxOf(size.width / sourceWidth, size.height / sourceHeight)
        val renderedWidth = sourceWidth * previewScale
        val renderedHeight = sourceHeight * previewScale
        val horizontalOffset = (size.width - renderedWidth) / 2f
        val verticalOffset = (size.height - renderedHeight) / 2f

        val normalizedLeft = if (mirrorHorizontally) 1f - animatedRight else animatedLeft
        val normalizedRight = if (mirrorHorizontally) 1f - animatedLeft else animatedRight
        val padding = 8.dp.toPx()
        val left = (horizontalOffset + normalizedLeft * renderedWidth - padding).coerceIn(0f, size.width)
        val top = (verticalOffset + animatedTop * renderedHeight - padding).coerceIn(0f, size.height)
        val right = (horizontalOffset + normalizedRight * renderedWidth + padding).coerceIn(0f, size.width)
        val bottom = (verticalOffset + animatedBottom * renderedHeight + padding).coerceIn(0f, size.height)
        if (right - left < 2f || bottom - top < 2f) return@Canvas

        val shade = Color.Black.copy(alpha = 0.28f)
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(shade, Offset(0f, top), Size(left, bottom - top))
        drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))

        val borderColor = if (isLocked) ArEmerald else ArCyan
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(18.dp.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )
        val cornerLength = 24.dp.toPx().coerceAtMost((right - left) / 3f)
        val strokeWidth = 5.dp.toPx()
        drawLine(borderColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)
        drawLine(borderColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth)
        drawLine(borderColor, Offset(right, top), Offset(right - cornerLength, top), strokeWidth)
        drawLine(borderColor, Offset(right, top), Offset(right, top + cornerLength), strokeWidth)
        drawLine(borderColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeWidth)
        drawLine(borderColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeWidth)
        drawLine(borderColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth)
        drawLine(borderColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth)
    }
}

private fun isSameTrackedObject(previous: TrackedObject, current: TrackedObject): Boolean {
    if (previous.trackingId != null && current.trackingId != null) {
        return previous.trackingId == current.trackingId
    }

    val intersectionWidth = (minOf(previous.right, current.right) - maxOf(previous.left, current.left))
        .coerceAtLeast(0f)
    val intersectionHeight = (minOf(previous.bottom, current.bottom) - maxOf(previous.top, current.top))
        .coerceAtLeast(0f)
    val intersection = intersectionWidth * intersectionHeight
    val union = previous.areaFraction + current.areaFraction - intersection
    return union > 0f && intersection / union >= 0.45f
}

@Composable
private fun MultiAngleCaptureProgress(
    images: List<Bitmap>,
    angleLabels: List<String>,
    statusText: String,
    showReset: Boolean,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .testTag("multi_angle_capture_progress"),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xE60B1220),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tiến trình quét",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = ArCyan.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                        Text("${images.size}/${angleLabels.size}", color = ArCyan, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                if (showReset) {
                    TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Chụp lại", color = ArCyan, fontSize = 10.sp)
                    }
                }
                }
            }

            Text(statusText, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 2)
            Spacer(Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                angleLabels.forEachIndexed { index, label ->
                    val image = images.getOrNull(index)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFF1E293B))
                                .border(
                                    1.dp,
                                    when {
                                        image != null -> ArEmerald
                                        index == images.size -> ArCyan
                                        else -> Color.White.copy(alpha = 0.2f)
                                    },
                                    RoundedCornerShape(9.dp)
                                )
                                .testTag("scan_angle_${index + 1}"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (image != null) {
                                Image(
                                    bitmap = image.asImageBitmap(),
                                    contentDescription = "Ảnh $label",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd),
                                    shape = CircleShape,
                                    color = ArEmerald
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.padding(2.dp).size(13.dp)
                                    )
                                }
                            } else {
                                Text(
                                    "${index + 1}",
                                    color = if (index == images.size) ArCyan else Color.Gray,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            label,
                            color = if (image != null) Color.White else Color.LightGray,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}


private suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        decodeSampledBitmap(context, uri)
    } catch (e: Exception) {
        null
    }
}
