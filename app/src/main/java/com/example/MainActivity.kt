package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.ar.ARViewport
import com.example.ar.PlacedFurniture
import com.example.camera.CameraPreview
import com.example.data.AppDatabase
import com.example.data.ItemEntity
import com.example.data.ItemRepository
import com.example.data.toPlacedFurniture
import com.example.spacefit.RoomSpec
import com.example.ui.InventoryScreen
import com.example.ui.ScannerScreen
import com.example.ui.theme.ArCyan
import com.example.ui.theme.ArEmerald
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

enum class AppDestination {
    AR_SPACE,
    SCANNER,
    INVENTORY
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                MainAppContainer()
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember {
        val db = AppDatabase.getInstance(context)
        ItemRepository(db.itemDao())
    }

    // Active Tab Destination
    var currentDestination by remember { mutableStateOf(AppDestination.AR_SPACE) }

    // Inventory Items from Room DB
    var itemsList by remember { mutableStateOf<List<ItemEntity>>(emptyList()) }

    // Listen to database changes
    LaunchedEffect(Unit) {
        repository.allItems.collect { updated ->
            itemsList = updated
        }
    }

    // 3D Placed Furniture list in AR Viewport
    var placedFurnitures by remember {
        mutableStateOf<List<PlacedFurniture>>(emptyList())
    }

    var selectedFurnitureId by remember { mutableStateOf<Long?>(null) }
    var arBackgroundMode by remember { mutableStateOf("ROOM_PHOTO") } // "ROOM_PHOTO", "CAMERA", "STUDIO_GRID"
    var showAddFurnitureSheet by remember { mutableStateOf(false) }
    var showRoomSettings by remember { mutableStateOf(false) }
    val roomPreferences = remember { context.getSharedPreferences("spacefit_room", android.content.Context.MODE_PRIVATE) }
    var roomWidthCm by remember { mutableFloatStateOf(roomPreferences.getFloat("width_cm", 400f)) }
    var roomDepthCm by remember { mutableFloatStateOf(roomPreferences.getFloat("depth_cm", 500f)) }
    var roomWidthInput by remember { mutableStateOf("400") }
    var roomDepthInput by remember { mutableStateOf("500") }
    val roomSpec = RoomSpec(widthCm = roomWidthCm, depthCm = roomDepthCm)

    // Keep permission in state so the 3D camera background updates immediately after the scanner grants it.
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var backgroundCameraError by remember { mutableStateOf<String?>(null) }

    if (showRoomSettings) {
        val parsedWidth = roomWidthInput.toFloatOrNull()
        val parsedDepth = roomDepthInput.toFloatOrNull()
        val dimensionsAreValid = parsedWidth != null && parsedWidth >= 200f &&
            parsedDepth != null && parsedDepth >= 200f

        AlertDialog(
            onDismissRequest = { showRoomSettings = false },
            icon = { Icon(Icons.Default.SquareFoot, contentDescription = null, tint = ArEmerald) },
            title = { Text("Thiết lập kích thước phòng") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Nhập kích thước thực để SpaceFit tính ranh giới, khoảng trống và va chạm.",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = roomWidthInput,
                        onValueChange = { roomWidthInput = it.filter(Char::isDigit) },
                        label = { Text("Chiều rộng") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        isError = parsedWidth == null || parsedWidth < 200f
                    )
                    OutlinedTextField(
                        value = roomDepthInput,
                        onValueChange = { roomDepthInput = it.filter(Char::isDigit) },
                        label = { Text("Chiều sâu") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        isError = parsedDepth == null || parsedDepth < 200f
                    )
                    Text(
                        "Lối đi khuyến nghị: tối thiểu 60 cm",
                        color = ArCyan,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = dimensionsAreValid,
                    onClick = {
                        roomWidthCm = parsedWidth!!
                        roomDepthCm = parsedDepth!!
                        roomPreferences.edit {
                            putFloat("width_cm", parsedWidth!!)
                            putFloat("depth_cm", parsedDepth!!)
                        }
                        showRoomSettings = false
                    }
                ) {
                    Text("Áp dụng")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRoomSettings = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        bottomBar = {
            // Hide bottom navigation while the full-screen scanner is open.
            if (currentDestination != AppDestination.SCANNER) {
                NavigationBar(
                    containerColor = Color(0xFF0F172A),
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.AR_SPACE,
                        onClick = { currentDestination = AppDestination.AR_SPACE },
                        icon = { Icon(Icons.Default.ViewInAr, null) },
                        label = { Text("Phòng SpaceFit", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ArCyan,
                            selectedTextColor = ArCyan,
                            indicatorColor = ArCyan.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.testTag("nav_tab_ar_space")
                    )

                    NavigationBarItem(
                        selected = currentDestination == AppDestination.INVENTORY,
                        onClick = { currentDestination = AppDestination.INVENTORY },
                        icon = { Icon(Icons.Default.Inventory2, null) },
                        label = { Text("Kho nội thất", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ArCyan,
                            selectedTextColor = ArCyan,
                            indicatorColor = ArCyan.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.testTag("nav_tab_inventory")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentDestination) {
                AppDestination.AR_SPACE -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ARViewport(
                            furnitureList = placedFurnitures,
                            selectedFurnitureId = selectedFurnitureId,
                            onSelectFurniture = { selectedFurnitureId = it },
                            onUpdateFurniture = { updated ->
                                placedFurnitures = placedFurnitures.map { if (it.id == updated.id) updated else it }
                            },
                            onDeleteFurniture = { id ->
                                placedFurnitures = placedFurnitures.filter { it.id != id }
                                if (selectedFurnitureId == id) {
                                    selectedFurnitureId = placedFurnitures.firstOrNull()?.id
                                }
                            },
                            roomSpec = roomSpec,
                            backgroundMode = arBackgroundMode,
                            cameraComposable = {
                                if (hasCameraPerm) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        CameraPreview(
                                            modifier = Modifier.fillMaxSize(),
                                            onCameraReady = { backgroundCameraError = null },
                                            onCameraError = { backgroundCameraError = it }
                                        )
                                        backgroundCameraError?.let { message ->
                                            Surface(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .padding(24.dp),
                                                color = Color(0xCC7F1D1D),
                                                shape = RoundedCornerShape(14.dp)
                                            ) {
                                                Text(
                                                    text = message,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(14.dp),
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF0F172A))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Unified studio toolbar keeps scene actions in one predictable place.
                        Column(
                            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xE60B1220),
                                shape = RoundedCornerShape(24.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                shadowElevation = 10.dp
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                            Text("SpaceFit Studio", color = Color.White,
                                                style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${roomWidthCm.toInt()} × ${roomDepthCm.toInt()} cm  •  ${placedFurnitures.size} vật thể",
                                                color = Color.White.copy(alpha = 0.62f),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        Button(
                                            onClick = { currentDestination = AppDestination.SCANNER },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ArCyan, contentColor = Color(0xFF042F2E)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            modifier = Modifier.height(42.dp).testTag("launch_camera_scanner_button")
                                        ) {
                                            Icon(Icons.Default.DocumentScanner, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Quét", fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(5.dp))
                                        IconButton(
                                            onClick = {
                                                roomWidthInput = roomWidthCm.toInt().toString()
                                                roomDepthInput = roomDepthCm.toInt().toString()
                                                showRoomSettings = true
                                            },
                                            modifier = Modifier.size(42.dp).testTag("room_settings_button")
                                        ) {
                                            Icon(Icons.Default.SquareFoot, contentDescription = "Kích thước phòng",
                                                tint = ArEmerald)
                                        }
                                        IconButton(
                                            onClick = { showAddFurnitureSheet = true },
                                            modifier = Modifier.size(42.dp).testTag("add_furniture_to_room_button")
                                        ) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = "Thêm đồ vào phòng",
                                                tint = Color.White)
                                        }
                                    }
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        listOf(
                                            "ROOM_PHOTO" to "Phòng mẫu",
                                            "CAMERA" to "Camera",
                                            "STUDIO_GRID" to "Lưới 3D"
                                        ).forEach { (mode, label) ->
                                            FilterChip(
                                                selected = arBackgroundMode == mode,
                                                onClick = {
                                                    if (mode == "CAMERA" && !hasCameraPerm) {
                                                        currentDestination = AppDestination.SCANNER
                                                    } else {
                                                        backgroundCameraError = null
                                                        arBackgroundMode = mode
                                                    }
                                                },
                                                label = { Text(label, fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = ArCyan.copy(alpha = 0.16f),
                                                    selectedLabelColor = ArCyan
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (placedFurnitures.isEmpty()) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
                                color = Color(0xE60B1220),
                                shape = RoundedCornerShape(26.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                shadowElevation = 12.dp
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier.size(54.dp).background(ArCyan.copy(alpha = 0.12f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.ViewInAr, null, tint = ArCyan, modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text("Bắt đầu thiết kế không gian", color = Color.White,
                                        style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Quét một món đồ hoặc chọn mô hình đã lưu trong kho.",
                                        color = Color.White.copy(alpha = 0.62f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = { currentDestination = AppDestination.SCANNER },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ArCyan, contentColor = Color(0xFF042F2E)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.DocumentScanner, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Quét nội thất", fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { currentDestination = AppDestination.INVENTORY }) {
                                        Text("Mở kho nội thất")
                                    }
                                }
                            }
                        }
                    }

                    // Add Furniture Bottom Sheet / Catalog
                    if (showAddFurnitureSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showAddFurnitureSheet = false },
                            containerColor = Color(0xFF0F172A)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                val placeableItems = itemsList.filter {
                                    !it.category.equals("CLOTHING", ignoreCase = true)
                                }
                                Text(
                                    text = "Đặt mô hình vào phòng",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = "Chọn một mô hình đã lưu trong kho nội thất.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.62f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                if (placeableItems.isEmpty()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        shape = RoundedCornerShape(20.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Inventory2, null, tint = ArCyan,
                                                modifier = Modifier.size(30.dp))
                                            Spacer(Modifier.height(8.dp))
                                            Text("Kho nội thất đang trống", color = Color.White,
                                                style = MaterialTheme.typography.titleMedium)
                                            TextButton(onClick = {
                                                showAddFurnitureSheet = false
                                                currentDestination = AppDestination.INVENTORY
                                            }) { Text("Mở kho nội thất") }
                                        }
                                    }
                                } else {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(placeableItems) { item ->
                                        Card(
                                            modifier = Modifier
                                                .width(180.dp)
                                                .clickable {
                                                    val newPlaced = item.toPlacedFurniture()
                                                    placedFurnitures = placedFurnitures + newPlaced
                                                    selectedFurnitureId = newPlaced.id
                                                    showAddFurnitureSheet = false
                                                },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, ArCyan.copy(alpha = 0.4f))
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("${item.widthCm.toInt()}x${item.heightCm.toInt()}x${item.depthCm.toInt()} cm", color = ArCyan, fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Button(
                                                    onClick = {
                                                        val newPlaced = item.toPlacedFurniture()
                                                        placedFurnitures = placedFurnitures + newPlaced
                                                        selectedFurnitureId = newPlaced.id
                                                        showAddFurnitureSheet = false
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = ArCyan),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    contentPadding = PaddingValues(vertical = 4.dp)
                                                ) {
                                                    Text("Đặt vào phòng", color = Color.Black, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }

                AppDestination.SCANNER -> {
                    ScannerScreen(
                        onItemSaved = { savedItem ->
                            coroutineScope.launch {
                                repository.insert(savedItem)
                            }
                        },
                        onTestInARRoom = { item ->
                            val newPlaced = item.toPlacedFurniture()
                            placedFurnitures = placedFurnitures + newPlaced
                            selectedFurnitureId = newPlaced.id
                            currentDestination = AppDestination.AR_SPACE
                        },
                        onCameraPermissionChanged = { granted ->
                            hasCameraPerm = granted
                            if (granted) {
                                backgroundCameraError = null
                                arBackgroundMode = "CAMERA"
                            } else if (arBackgroundMode == "CAMERA") {
                                arBackgroundMode = "ROOM_PHOTO"
                            }
                        },
                        onClose = { currentDestination = AppDestination.AR_SPACE }
                    )
                }

                AppDestination.INVENTORY -> {
                    InventoryScreen(
                        items = itemsList,
                        onTestIn3D = { item ->
                            val newPlaced = item.toPlacedFurniture()
                            placedFurnitures = placedFurnitures + newPlaced
                            selectedFurnitureId = newPlaced.id
                            currentDestination = AppDestination.AR_SPACE
                        },
                        onDeleteItem = { item ->
                            coroutineScope.launch {
                                repository.delete(item)
                            }
                        },
                        onAddNewItem = { newItem ->
                            coroutineScope.launch {
                                repository.insert(newItem)
                            }
                        },
                        onUpdateItem = { editedItem ->
                            coroutineScope.launch {
                                repository.update(editedItem)
                            }
                        }
                    )
                }

            }
        }
    }
}
