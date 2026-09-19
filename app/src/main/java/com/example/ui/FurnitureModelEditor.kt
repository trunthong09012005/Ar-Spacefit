package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ar.ModelParameters
import com.example.ar.modelTypeLabels
import com.example.ar.parseDimensionCm
import com.example.data.ItemEntity
import com.example.ui.theme.ArCyan
import com.example.ui.theme.ArEmerald
import androidx.core.graphics.toColorInt
import kotlin.math.roundToInt

@Composable
fun FurnitureModelEditor(
    item: ItemEntity,
    onSave: (ItemEntity) -> Unit,
    onPlace: (ItemEntity) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FurnitureModelEditorContent(item, onSave, onPlace, onDismiss,
            Modifier.padding(12.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.95f).imePadding())
    }
}

@Composable
internal fun FurnitureModelEditorContent(
    item: ItemEntity,
    onSave: (ItemEntity) -> Unit,
    onPlace: (ItemEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable(item) { mutableStateOf(item.name) }
    var type by rememberSaveable(item) { mutableStateOf(item.modelType3D) }
    var width by rememberSaveable(item) { mutableStateOf(dimensionText(item.widthCm)) }
    var height by rememberSaveable(item) { mutableStateOf(dimensionText(item.heightCm)) }
    var depth by rememberSaveable(item) { mutableStateOf(dimensionText(item.depthCm)) }
    var material by rememberSaveable(item) { mutableStateOf(item.material) }
    var color by rememberSaveable(item) { mutableStateOf(item.colorHex) }
    var parametersJson by rememberSaveable(item) { mutableStateOf(item.modelParametersJson) }
    var dimensionsConfirmed by rememberSaveable(item) { mutableStateOf(false) }
    var submitted by remember(item) { mutableStateOf(false) }
    val parameters = ModelParameters.fromJson(parametersJson, type)
    val w = parseDimensionCm(width)
    val h = parseDimensionCm(height)
    val d = parseDimensionCm(depth)
    val dimensionsValid = w != null && h != null && d != null
    val colorValid = color.trim().matches(Regex("#[0-9a-fA-F]{6}"))
    val draft = item.copy(
        name = name.trim(), modelType3D = type, widthCm = w ?: 0f, heightCm = h ?: 0f, depthCm = d ?: 0f,
        material = material.trim(), colorHex = color.trim(), modelParametersJson = parameters.toJson()
    )
    val canSave = dimensionsValid && dimensionsConfirmed && colorValid && name.isNotBlank() && !submitted
    Surface(
        modifier = modifier.testTag("recognized_result_sheet"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 10.dp
    ) {
        Column {
            EditorHeader(onDismiss)
            if (dimensionsValid) {
                FurnitureModelPreview(
                    item = draft,
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 14.dp)
                )
            } else {
                MissingDimensionsCard()
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("model_editor_scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EditorSection("Thông tin sản phẩm", Icons.Default.AutoAwesome) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Tên món đồ") },
                        placeholder = { Text("Ví dụ: Ghế làm việc") },
                        singleLine = true,
                        isError = name.isBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("model_name")
                    )
                    Spacer(Modifier.height(10.dp))
                    ModelTypeSelector(
                        selectedType = type,
                        onSelected = { selected ->
                            type = selected
                            parametersJson = ModelParameters.defaults(selected).toJson()
                        }
                    )
                }

                EditorSection("Kích thước phủ bì", Icons.Default.Straighten) {
                    Text(
                        "Đo cả phần nhô ra. AI chỉ gợi ý, số đo bạn xác nhận mới được dùng để tính SpaceFit.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelDimensionField("Rộng", width, {
                            width = it
                            dimensionsConfirmed = false
                        }, Modifier.weight(1f), "model_width")
                        ModelDimensionField("Cao", height, {
                            height = it
                            dimensionsConfirmed = false
                        }, Modifier.weight(1f), "model_height")
                        ModelDimensionField("Sâu", depth, {
                            depth = it
                            dimensionsConfirmed = false
                        }, Modifier.weight(1f), "model_depth")
                    }
                    if (!dimensionsValid) {
                        Spacer(Modifier.height(8.dp))
                        Text("Nhập số từ 1 đến 1.000 cm.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }

                EditorSection("Hình dáng mô hình", Icons.Default.Tune) {
                    Text(
                        "Chỉnh chi tiết theo đồ thật. Mô hình phía trên cập nhật ngay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelShapeControls(type, parameters) { parametersJson = it.toJson() }
                }

                EditorSection("Màu & chất liệu", Icons.Default.Palette) {
                    OutlinedTextField(
                        value = material,
                        onValueChange = { material = it },
                        label = { Text("Chất liệu") },
                        placeholder = { Text("Để trống nếu chưa chắc chắn") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    ColorPresetSelector(color) { color = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = color,
                        onValueChange = { color = it },
                        label = { Text("Mã màu") },
                        prefix = {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape)
                                    .background(parsePreviewColor(color))
                            )
                        },
                        supportingText = {
                            Text(if (colorValid) "Định dạng HEX · #RRGGBB" else "Mã màu chưa đúng định dạng #RRGGBB")
                        },
                        singleLine = true,
                        isError = !colorValid,
                        modifier = Modifier.fillMaxWidth().testTag("model_color")
                    )
                }

                if (item.notes.isNotBlank()) {
                    Surface(
                        color = ArCyan.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ArCyan.copy(alpha = 0.18f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Lightbulb, null, tint = ArCyan, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Gợi ý từ AI", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(3.dp))
                                Text(item.notes, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            EditorActions(
                confirmed = dimensionsConfirmed,
                dimensionsValid = dimensionsValid,
                canSave = canSave,
                onConfirmationChange = { dimensionsConfirmed = it },
                onSave = { submitted = true; onSave(draft) },
                onPlace = { submitted = true; onPlace(draft) }
            )
        }
    }
}

@Composable
private fun EditorHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                .background(ArCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ViewInAr, null, tint = ArCyan)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("MODEL BUILDER", color = ArCyan, style = MaterialTheme.typography.labelMedium)
            Text("Tinh chỉnh mô hình 3D", style = MaterialTheme.typography.titleLarge)
            Text("Mô hình đại diện có thể chỉnh sửa", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Đóng trình chỉnh 3D") }
    }
}

@Composable
private fun MissingDimensionsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(ArCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Straighten, null, tint = ArCyan)
            }
            Column {
                Text("Chưa thể dựng mô hình", style = MaterialTheme.typography.titleMedium)
                Text("Nhập đủ rộng, cao và sâu ở phần bên dưới.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = ArCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelTypeSelector(selectedType: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = modelTypeLabels[selectedType] ?: "Khối đơn giản",
            onValueChange = {},
            readOnly = true,
            label = { Text("Loại mô hình") },
            leadingIcon = { Icon(Icons.Default.Category, null, tint = ArCyan) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth().testTag("model_type_selector")
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            modelTypeLabels.forEach { (key, label) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            Text(modelTypeDescription(key), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = {
                        if (key == selectedType) Icon(Icons.Default.CheckCircle, null, tint = ArEmerald)
                        else Icon(Icons.Default.ViewInAr, null)
                    },
                    onClick = {
                        onSelected(key)
                        expanded = false
                    },
                    modifier = Modifier.testTag("model_type_$key")
                )
            }
        }
    }
}

private fun modelTypeDescription(type: String) = when (type) {
    "SOFA", "CHAIR" -> "Chỉnh mặt ngồi và tay vịn"
    "TABLE" -> "Chỉnh độ dày mặt bàn"
    "WARDROBE" -> "Chỉnh số lượng cánh tủ"
    "BOX" -> "Khối 3D cơ bản"
    else -> "Mẫu hình học theo kích thước"
}

@Composable
private fun ColorPresetSelector(selected: String, onSelected: (String) -> Unit) {
    val presets = listOf("#67E8F9", "#34D399", "#818CF8", "#FBBF24", "#F8FAFC", "#64748B", "#7C4A32", "#111827")
    Column {
        Text("Màu nhanh", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            presets.forEach { hex ->
                val isSelected = selected.equals(hex, true)
                Box(
                    Modifier.size(if (isSelected) 34.dp else 30.dp)
                        .clip(CircleShape)
                        .background(Color(hex.toColorInt()))
                        .border(if (isSelected) 3.dp else 1.dp,
                            if (isSelected) Color.White else Color.White.copy(alpha = 0.22f), CircleShape)
                        .semantics { contentDescription = "Chọn màu $hex" }
                        .clickable { onSelected(hex) }
                )
            }
        }
    }
}

private fun parsePreviewColor(value: String): Color = runCatching {
    Color(value.trim().toColorInt())
}.getOrDefault(Color.Transparent)

@Composable
private fun EditorActions(
    confirmed: Boolean,
    dimensionsValid: Boolean,
    canSave: Boolean,
    onConfirmationChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onPlace: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 12.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = dimensionsValid) { onConfirmationChange(!confirmed) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(confirmed, onConfirmationChange, enabled = dimensionsValid,
                    modifier = Modifier.testTag("confirm_model_dimensions"))
                Column(Modifier.weight(1f)) {
                    Text("Xác nhận số đo", style = MaterialTheme.typography.labelLarge)
                    Text("Tôi đã kiểm tra rộng, cao và sâu.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (confirmed) Icon(Icons.Default.Verified, null, tint = ArEmerald)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.weight(0.85f).height(50.dp).testTag("save_scanned_item_button")
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Lưu kho")
                }
                Button(
                    onClick = onPlace,
                    enabled = canSave,
                    modifier = Modifier.weight(1.3f).height(50.dp).testTag("test_in_ar_room_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = ArCyan, contentColor = Color(0xFF042F2E))
                ) {
                    Icon(Icons.Default.ViewInAr, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Đặt vào phòng")
                }
            }
        }
    }
}


@Composable
private fun ModelDimensionField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, tag: String) {
    OutlinedTextField(value, onChange, label = { Text("$label (cm)", fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && parseDimensionCm(value) == null, modifier = modifier.testTag(tag))
}

@Composable
private fun ModelShapeControls(type: String, parameters: ModelParameters, onChange: (ModelParameters) -> Unit) {
    when (type) {
        "SOFA", "CHAIR" -> {
            Text("Cao mặt ngồi / tổng chiều cao: ${(parameters.seatHeightRatio * 100).roundToInt()}%", fontSize = 13.sp)
            Slider(parameters.seatHeightRatio, { onChange(parameters.copy(seatHeightRatio = it)) },
                valueRange = 0.3f..0.65f, modifier = Modifier.testTag("seat_height_slider"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Có tay vịn")
                Switch(parameters.hasArmrests, { onChange(parameters.copy(hasArmrests = it)) },
                    modifier = Modifier.testTag("model_armrests"))
            }
        }
        "TABLE" -> {
            Text("Dày mặt bàn / tổng chiều cao: ${(parameters.topThicknessRatio * 100).roundToInt()}%", fontSize = 13.sp)
            Slider(parameters.topThicknessRatio, { onChange(parameters.copy(topThicknessRatio = it)) },
                valueRange = 0.03f..0.2f, modifier = Modifier.testTag("table_thickness_slider"))
        }
        "WARDROBE" -> {
            Text("Số cánh tủ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { count ->
                    FilterChip(parameters.doorCount == count, { onChange(parameters.copy(doorCount = count)) },
                        label = { Text("$count") }, modifier = Modifier.testTag("model_doors_$count"))
                }
            }
        }
        else -> Text("Loại này dùng hình dáng cơ bản; bạn có thể chỉnh kích thước và màu.", fontSize = 12.sp)
    }
}

private fun dimensionText(value: Float): String = if (value.isFinite() && value in 1f..1000f) {
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
} else ""
