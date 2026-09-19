package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ItemEntity
import com.example.ar.parseDimensionCm
import com.example.ar.modelTypeLabels
import com.example.ui.theme.ArCyan
import com.example.ui.theme.ArEmerald
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    items: List<ItemEntity>,
    onTestIn3D: (ItemEntity) -> Unit,
    onDeleteItem: (ItemEntity) -> Unit,
    onAddNewItem: (ItemEntity) -> Unit,
    onUpdateItem: (ItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedModelType by remember { mutableStateOf("ALL") }
    var editingItem by remember { mutableStateOf<ItemEntity?>(null) }

    val modelFilters = listOf(
        "ALL" to "Tất cả",
        "SOFA" to "Sofa",
        "CHAIR" to "Ghế",
        "TABLE" to "Bàn",
        "BED" to "Giường",
        "WARDROBE" to "Tủ",
        "LAMP" to "Đèn",
        "PLANT" to "Cây"
    )

    val supportedModelTypes = setOf("SOFA", "CHAIR", "TABLE", "BED", "LAMP", "WARDROBE", "PLANT", "BOX")
    val filteredItems = items.filter { item ->
        val isSupportedInterior = !item.category.equals("CLOTHING", ignoreCase = true) &&
            item.modelType3D.uppercase() in supportedModelTypes
        val matchesModel = selectedModelType == "ALL" || item.modelType3D.equals(selectedModelType, ignoreCase = true)
        val matchesSearch = searchQuery.isBlank() ||
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.material.contains(searchQuery, ignoreCase = true) ||
                item.notes.contains(searchQuery, ignoreCase = true)
        isSupportedInterior && matchesModel && matchesSearch
    }

    Scaffold(
        modifier = modifier.testTag("inventory_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kho nội thất", style = MaterialTheme.typography.titleLarge)
                        Text("${items.size} mô hình đã lưu", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Surface(
                        color = ArCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(ArEmerald, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("Sẵn sàng 3D", color = ArCyan, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingItem = ItemEntity(
                        name = "", category = "FURNITURE", roomType = "LIVING_ROOM",
                        widthCm = 0f, heightCm = 0f, depthCm = 0f,
                        colorHex = "#67E8F9", material = "", modelType3D = "BOX"
                    )
                },
                containerColor = ArCyan,
                contentColor = Color.Black,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Thêm thủ công", fontWeight = FontWeight.Bold) },
                modifier = Modifier.testTag("add_item_fab")
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Tìm tên hoặc chất liệu...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = ArCyan) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("inventory_search_field")
            )

            // Useful model filters instead of categories that are always empty for scanned furniture.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modelFilters) { (filterKey, filterLabel) ->
                    FilterChip(
                        selected = selectedModelType == filterKey,
                        onClick = { selectedModelType = filterKey },
                        label = { Text(filterLabel, fontSize = 12.sp) },
                        leadingIcon = {
                            val icon = when (filterKey) {
                                "SOFA" -> Icons.Default.Weekend
                                "CHAIR" -> Icons.Default.Chair
                                "TABLE" -> Icons.Default.TableRestaurant
                                "BED" -> Icons.Default.Bed
                                "WARDROBE" -> Icons.Default.DoorSliding
                                "LAMP" -> Icons.Default.Lightbulb
                                "PLANT" -> Icons.Default.Park
                                else -> Icons.Default.Category
                            }
                            Icon(icon, null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            Text("${filteredItems.size} kết quả", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))

            // Items List
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp)) {
                            Box(Modifier.size(58.dp).background(ArCyan.copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Inventory2, null, tint = ArCyan, modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Chưa có mô hình phù hợp", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Quét bằng Camera AI hoặc thêm số đo thủ công.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        InventoryItemCard(
                            item = item,
                            onClick = { editingItem = item },
                            onTestIn3D = { onTestIn3D(item) },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
            }
        }
    }

    // Add Item Dialog
    editingItem?.let { item ->
        FurnitureModelEditor(
            item = item,
            onSave = {
                if (it.id == 0L) onAddNewItem(it) else onUpdateItem(it)
                editingItem = null
            },
            onPlace = {
                if (it.id == 0L) onAddNewItem(it) else onUpdateItem(it)
                editingItem = null
                onTestIn3D(it)
            },
            onDismiss = { editingItem = null }
        )
    }
}

@Composable
fun InventoryItemCard(
    item: ItemEntity,
    onClick: () -> Unit,
    onTestIn3D: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Xóa mô hình này?") },
            text = { Text("“${item.name}” sẽ bị xóa khỏi kho. Thao tác này không thể hoàn tác.") },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Giữ lại") } }
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("item_card_${item.id}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Name, Category badge, and Delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    // Category Icon Avatar
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ArCyan.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when (item.modelType3D) {
                            "SOFA" -> Icons.Default.Weekend
                            "CHAIR" -> Icons.Default.Chair
                            "TABLE" -> Icons.Default.TableRestaurant
                            "LAMP" -> Icons.Default.Lightbulb
                            "BED" -> Icons.Default.Bed
                            "WARDROBE" -> Icons.Default.DoorSliding
                            "PLANT" -> Icons.Default.Park
                            else -> Icons.Default.Category
                        }
                        Icon(
                            icon,
                            null,
                            tint = ArCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${modelTypeLabels[item.modelType3D] ?: "Mô hình"}  •  ${item.material.ifBlank { "Chưa rõ chất liệu" }}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Xóa", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dimensions and Room tag
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Straighten, null, tint = ArCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${item.widthCm.roundToInt()} x ${item.heightCm.roundToInt()} x ${item.depthCm.roundToInt()} cm",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ArCyan
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArCyan.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = roomLabel(item.roomType),
                        color = ArCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary AR Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier.weight(0.8f).height(46.dp).testTag("edit_model_${item.id}")
                ) {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chỉnh sửa", fontSize = 12.sp)
                }
                Button(
                    onClick = onTestIn3D,
                    colors = ButtonDefaults.buttonColors(containerColor = ArCyan, contentColor = Color(0xFF042F2E)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1.2f).height(46.dp)
                ) {
                    Icon(Icons.Default.ViewInAr, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Đặt vào phòng", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun roomLabel(roomType: String) = when (roomType) {
    "LIVING_ROOM" -> "Phòng khách"
    "BEDROOM" -> "Phòng ngủ"
    "OFFICE" -> "Làm việc"
    "KITCHEN" -> "Nhà bếp"
    "WARDROBE" -> "Phòng đồ"
    else -> "Không gian 3D"
}
