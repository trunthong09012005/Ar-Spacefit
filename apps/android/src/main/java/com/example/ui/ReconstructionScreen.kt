package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ar.parseDimensionCm
import com.example.camera.*
import com.example.data.ScanSessionEntity
import com.example.reconstruction.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconstructionScreen(onClose: () -> Unit, onLegacyScan: () -> Unit, vm: ScanViewModel = viewModel()) {
    val scans by vm.scans.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var captureId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selected = scans.firstOrNull { it.id == selectedId }
    var editCalibration by remember(selectedId, selected?.status) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedId?.let { if (uris.isNotEmpty()) vm.importImages(it, uris) }
    }
    val importVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedId?.let { if (uri != null) vm.importVideo(it, uri) }
    }
    val exit = { if (selectedId != null) selectedId = null else onClose() }
    BackHandler { if (captureId != null) captureId = null else exit() }

    if (captureId != null) {
        FurnitureCaptureScreen(requireNotNull(captureId), vm, onClose = { captureId = null })
    } else Scaffold(topBar = {
        TopAppBar(title={ Text(if (selected == null) "Quét nội thất 3D" else selected.name,maxLines=1,overflow=TextOverflow.Ellipsis) },
            navigationIcon={ IconButton(onClick=exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
            actions={ IconButton(onClick={ showSettings=true }) { Icon(Icons.Default.Computer, "Kết nối laptop") } })
    }) { padding ->
        if (selected == null) LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(bottom=24.dp)) {
            item {
                Text("Đi quanh món đồ, chụp 50–150 ảnh rõ hoặc nhập video. Laptop sẽ dựng lại hình dáng và texture từ ảnh.")
                Spacer(Modifier.height(12.dp))
                Button(onClick={ showCreate=true }, modifier=Modifier.fillMaxWidth()) { Text("Bắt đầu phiên quét") }
                OutlinedButton(onClick={ showSettings=true }, modifier=Modifier.fillMaxWidth()) { Text("Cấu hình / kiểm tra laptop") }
                TextButton(onClick=onLegacyScan) { Text("Nhận diện 5 góc và mô hình tham số") }
                Text("Lịch sử quét", style=MaterialTheme.typography.titleMedium)
            }
            if (scans.isEmpty()) item { Text("Chưa có phiên quét. Ảnh được lưu cục bộ để bạn tiếp tục sau.") }
            items(scans, key={it.id}) { scan ->
                OutlinedCard(onClick={ selectedId=scan.id }, modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(scan.name, style=MaterialTheme.typography.titleMedium)
                        Text("${scan.imageCount} ảnh • ${scanStatusLabel(scan.status)}")
                        if (scan.status in setOf("processing", "calibrating", "uploading")) LinearProgressIndicator(
                            progress={scan.progress / 100f}, modifier=Modifier.fillMaxWidth())
                        if (scan.error.isNotBlank()) Text(scan.error, color=MaterialTheme.colorScheme.error, maxLines=3)
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val active = selected.id in busy
            Text("${scanStatusLabel(selected.status)} • ${selected.imageCount} ảnh", style=MaterialTheme.typography.titleMedium)
            Text("Tham chiếu: ${axisLabel(selected.referenceAxis)} ${selected.referenceCm} cm")
            if (selected.error.isNotBlank()) Text(selected.error, color=MaterialTheme.colorScheme.error)
            if (message.isNotBlank() && message!=selected.error) Text(message, style=MaterialTheme.typography.bodySmall)
            if (active) {
                LinearProgressIndicator(progress={ if(selected.status == "uploading") uploadProgress / 100f else selected.progress / 100f },
                    modifier=Modifier.fillMaxWidth())
                Text(if (selected.status == "uploading") "Đang tải lên: $uploadProgress%" else "${stageLabel(selected.stage)} • ${selected.progress}%")
                Text("Tiến độ là các mốc xử lý; từng bước có thể mất vài phút. Có thể quay lại màn hình này sau.", style=MaterialTheme.typography.bodySmall)
            }
            if (selected.jobId.isBlank()) {
                val canCapture = !active && selected.canEditCapture
                Button(onClick={ captureId=selected.id }, enabled=canCapture, modifier=Modifier.fillMaxWidth()) { Text("${if(selected.imageCount>0) "Tiếp tục" else "Mở camera"} chụp quanh vật thể") }
                OutlinedButton(onClick={ importImages.launch(arrayOf("image/jpeg", "image/png")) }, enabled=canCapture, modifier=Modifier.fillMaxWidth()) { Text("Nhập bộ ảnh có sẵn") }
                OutlinedButton(onClick={ importVideo.launch(arrayOf("video/mp4")) }, enabled=canCapture, modifier=Modifier.fillMaxWidth()) { Text("Nhập video quay vòng quanh") }
                if (!selected.canEditCapture) Text("Ảnh/video đã khóa cho phiên gửi này. Generate sẽ gửi lại cùng dữ liệu tới laptop cũ; muốn đổi dữ liệu, hãy tạo phiên mới.", style=MaterialTheme.typography.bodySmall)
                if (File(vm.directory(selected.id), "capture.mp4").exists()) Text("Video đã được lưu; Generate sẽ dùng video này.")
                Button(onClick={ vm.generate(selected.id) }, enabled=!active, modifier=Modifier.fillMaxWidth()) { Text("Generate 3D trên laptop") }
            } else {
                if (!active) OutlinedButton(onClick={ vm.refresh(selected.id) }, modifier=Modifier.fillMaxWidth()) { Text("Cập nhật / tải lại kết quả") }
                if (selected.status in setOf("failed", "cancelled")) Button(onClick={ vm.action(selected.id, "retry") }, enabled=!active) { Text("Thử xử lý lại") }
                if (selected.status in setOf("queued", "processing", "calibrating", "calibration_queued"))
                    TextButton(onClick={ vm.action(selected.id, "cancel") }) { Text("Hủy xử lý trên laptop") }
            }
            if (selected.status == "completed") TextButton(onClick={editCalibration=!editCalibration}) {
                Text(if(editCalibration) "Xem model đã hoàn tất" else "Hiệu chỉnh lại hướng / vùng cắt")
            }
            if ((selected.status in setOf("needs_review", "failed", "cancelled") || editCalibration) && selected.previewPath.isNotBlank()) {
                CalibrationPanel(selected, enabled=!active, onConfirm={ vm.action(selected.id, "calibrate", it) })
            }
            if (selected.status == "completed" && selected.modelPath.isNotBlank() && !editCalibration) {
                GlbViewer(selected.modelPath, Modifier.fillMaxWidth().height(300.dp))
                val result = runCatching { JSONObject(selected.resultJson) }.getOrNull()
                Text("W × D × H: %.1f × %.1f × %.1f cm".format(result?.optDouble("width_cm"), result?.optDouble("depth_cm"), result?.optDouble("height_cm")))
                Text("${result?.optInt("polygon_count")} tam giác • GLB có texture • Đã hiệu chỉnh scale")
                Button(onClick={ vm.saveToInventory(selected.id) }, enabled=!active, modifier=Modifier.fillMaxWidth()) {
                    Text(if (selected.savedItemId == 0L) "Lưu vào My Furniture" else "Cập nhật My Furniture")
                }
            }
        }
    }
    if (showCreate) NewScanDialog(onDismiss={showCreate=false}, onCreate={ name, type, axis, cm ->
        scope.launch { selectedId=vm.create(name,type,axis,cm); showCreate=false }
    })
    if (showSettings) BackendSettingsDialog(vm.settings, onDismiss={showSettings=false})
}

@Composable
private fun NewScanDialog(onDismiss: () -> Unit, onCreate: (String, String, String, Float) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("CHAIR") }
    var axis by rememberSaveable { mutableStateOf("width") }
    var dimension by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss, title={Text("Quét một món đồ thật")}, text={
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Tên món đồ")},singleLine=true)
            ChoiceRow("Loại", listOf("CHAIR" to "Ghế", "TABLE" to "Bàn", "SOFA" to "Sofa", "WARDROBE" to "Tủ", "BOX" to "Khác"),type,{type=it})
            ChoiceRow("Đo kích thước tham chiếu",listOf("width" to "Rộng", "depth" to "Sâu", "height" to "Cao"),axis,{axis=it})
            OutlinedTextField(dimension,{dimension=it},label={Text("Số đo thực bằng thước (cm)")},singleLine=true)
            Text("Giữ món đồ đứng yên, đủ sáng, tránh vật bóng/gương và nền trơn. Đi quanh vật thể; không xoay vật trên bàn.")
        }
    }, confirmButton={Button(onClick={onCreate(name,type,axis,requireNotNull(parseDimensionCm(dimension)))},enabled=name.trim().length in 1..120 && parseDimensionCm(dimension)!=null){Text("Tạo phiên")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Hủy")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(label: String, options: List<Pair<String,String>>, selected: String, onSelect: (String)->Unit) {
    Text(label, style=MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        options.forEach { (value,title) -> FilterChip(selected=selected==value,onClick={onSelect(value)},label={Text(title)}) }
    }
}

@Composable
private fun BackendSettingsDialog(settings: BackendSettings, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(settings.url) }
    var token by remember { mutableStateOf(settings.token) }
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Kết nối laptop")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("USB: chạy adb reverse tcp:8000 tcp:8000 trên laptop. Wi-Fi: hai máy cùng mạng, nhập IP LAN của laptop.")
            OutlinedTextField(url,{url=it},label={Text("URL máy chủ")},singleLine=true)
            OutlinedTextField(token,{token=it},label={Text("Mã ghép nối SPACEFIT_TOKEN")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            if (status.isNotBlank()) Text(status)
        }
    },confirmButton={
        // Resolve locals inside the Dialog: the activity's focus/window token is different.
        val focusManager=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        val dialogView=LocalView.current
        Button(enabled=!checking,onClick={
        focusManager.clearFocus(force=true)
        keyboard?.hide()
        (dialogView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(dialogView.windowToken,0)
        checking=true
        scope.launch {
            try {
                settings.save(url,token)
                val result=BackendClient(url,token).capabilities()
                requireNativeReady(result)
                status="Đã kết nối. Các công cụ native khởi động được."
            } catch (error: Exception) { status=error.localizedMessage.orEmpty() }
            finally {checking=false}
        }
    }){Text("Lưu và kiểm tra")}},dismissButton={TextButton(onClick=onDismiss){Text("Đóng")}})
}

@Composable
private fun CalibrationPanel(scan: ScanSessionEntity, enabled: Boolean, onConfirm: (JSONObject)->Unit) {
    var rotations by remember(scan.id) { mutableStateOf(listOf(0f,0f,0f)) }
    var crop by remember(scan.id) { mutableStateOf(listOf(0f..1f,0f..1f,0f..1f)) }
    var reviewed by remember(scan.id) { mutableStateOf(false) }
    Text("Kiểm tra hướng và loại nền thừa", style=MaterialTheme.typography.titleMedium)
    GlbViewer(scan.previewPath, Modifier.fillMaxWidth().height(280.dp), rotations)
    Text("Bản xem trước chưa có kích thước thực. Chỉnh để chân đồ vật hướng xuống sàn. Nếu còn nền, thu vùng cắt X/Y/Z; vùng cắt áp dụng lên model gốc, trước khi xoay.")
    "XYZ".forEachIndexed { index, axis ->
        Text("Xoay $axis: ${rotations[index].toInt()}°")
        Slider(value=rotations[index],onValueChange={value-> rotations=rotations.mapIndexed{i,old->if(i==index)value else old};reviewed=false},valueRange=-180f..180f,steps=71,enabled=enabled)
        Text("Giữ vùng $axis: ${(crop[index].start*100).toInt()}–${(crop[index].endInclusive*100).toInt()}%")
        RangeSlider(value=crop[index],onValueChange={range->if(range.endInclusive-range.start>=0.02f)crop=crop.mapIndexed{i,old->if(i==index)range else old};reviewed=false},enabled=enabled)
    }
    Text("Vùng cắt sẽ được xử lý trên laptop khi xác nhận. Kiểm tra lại model sau khi hoàn tất; nếu cần cắt theo hình phức tạp, chỉnh mesh trong Blender.",style=MaterialTheme.typography.bodySmall)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(reviewed,{reviewed=it},enabled=enabled)
        Text("Tôi đã kiểm tra vật thể, hướng đứng và số đo ${scan.referenceCm} cm.")
    }
    Button(enabled=enabled && reviewed,onClick={
        onConfirm(JSONObject().put("reference_axis",scan.referenceAxis).put("reference_cm",scan.referenceCm)
            .put("rotation_x",rotations[0]).put("rotation_y",rotations[1]).put("rotation_z",rotations[2])
            .put("crop_min",JSONArray(crop.map{it.start})).put("crop_max",JSONArray(crop.map{it.endInclusive})).put("reviewed",true))
    },modifier=Modifier.fillMaxWidth()){Text("Hiệu chỉnh và xuất GLB theo số đo thực")}
}

@Composable
private fun FurnitureCaptureScreen(id: String, vm: ScanViewModel, onClose: () -> Unit) {
    val context=LocalContext.current
    val scans by vm.scans.collectAsStateWithLifecycle()
    val count=scans.firstOrNull{it.id==id}?.imageCount ?: 0
    val message by vm.message.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val requestPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it}
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var tracked by remember { mutableStateOf<TrackedObject?>(null) }
    var error by remember { mutableStateOf("") }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val executor=remember { Executors.newSingleThreadExecutor() }
    val scope=rememberCoroutineScope()
    val capture = {
        if (!capturing && count < 150 && imageCapture!=null) {
            val clipped=tracked?.let{it.left<0.015f || it.top<0.015f || it.right>0.985f || it.bottom>0.985f} ?: false
            if (clipped) error="Vật thể sát mép ảnh. Lùi lại để giữ toàn bộ món đồ trong khung."
            else {
                capturing=true
                capturePhoto(context,imageCapture,executor,onSuccess={bitmap ->
                    if (!scope.isActive) bitmap.recycle() else scope.launch {
                        try {vm.addPhoto(id,bitmap);error=""} catch (failure: Exception) {error=failure.localizedMessage.orEmpty()}
                        finally {bitmap.recycle();capturing=false}
                    }
                },onError={error=it.localizedMessage.orEmpty();capturing=false;automatic=false})
            }
        }
    }
    val latestCapture by rememberUpdatedState(capture)
    LaunchedEffect(automatic) { while(automatic) {latestCapture();delay(1500)} }
    LaunchedEffect(count) { if (count >= 150) automatic=false }
    DisposableEffect(Unit) { onDispose {executor.shutdown()} }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if(permission) CameraPreview(Modifier.fillMaxSize(), objectDetectionEnabled=true,imageCaptureCallback={imageCapture=it},
            onObjectDetected={tracked=it},onCameraError={error=it;automatic=false},onObjectDetectionError={error=it})
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().background(Color(0xCC0F172A)).padding(16.dp)) {
            Text("$count / 150 ảnh • Mục tiêu tối thiểu 50",color=Color.White,style=MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress={(count/60f).coerceAtMost(1f)},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
            Text(when{count<20->"Vòng 1: ngang tầm món đồ, đi chậm quanh 360°.";count<40->"Vòng 2: hạ camera, quét chân và các mặt khuất.";else->"Vòng 3: nâng camera, quét mặt trên. Giữ ảnh chồng lấn 60–80%."},color=Color.White)
            Text("Tiến độ đếm ảnh; số góc thực được kiểm tra bằng camera pose trên laptop.",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(Color(0xDD0F172A)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(error.ifBlank{message.ifBlank{"Giữ trọn vật thể, không đổi zoom hoặc ống kính trong một lượt quét."}},color=if(error.isBlank())Color.White else Color(0xFFFFB4AB))
            if (!permission) Button(onClick={requestPermission.launch(Manifest.permission.CAMERA)},modifier=Modifier.fillMaxWidth()){Text("Cấp quyền camera")}
            else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=capture,enabled=!capturing && count<150 && imageCapture!=null,modifier=Modifier.weight(1f)){Text("Chụp ảnh")}
                OutlinedButton(onClick={automatic=!automatic},enabled=count<150,modifier=Modifier.weight(1f)){Text(if(automatic)"Dừng tự chụp" else "Tự chụp 1,5 giây")}
            }
            TextButton(onClick={automatic=false;onClose()},modifier=Modifier.fillMaxWidth()){Text("Lưu và quay lại • $count ảnh")}
        }
    }
}

fun axisLabel(axis:String)=when(axis){"width"->"Rộng";"depth"->"Sâu";else->"Cao"}
fun scanStatusLabel(status:String)=when(status){
    "capturing"->"Đang thu ảnh";"ready"->"Sẵn sàng tải lên";"uploading"->"Đang tải lên";"queued"->"Đang chờ laptop"
    "processing"->"Đang dựng 3D";"needs_review"->"Cần kiểm tra hướng và scale";"calibrating","calibration_queued"->"Đang hiệu chỉnh"
    "completed"->"Đã tạo GLB";"failed"->"Xử lý lỗi";"cancelled"->"Đã hủy";else->status
}
private fun stageLabel(stage:String)=when(stage){
    "checking_tools"->"Kiểm tra khởi động công cụ native"
    "preparing_images"->"Lọc và chuẩn hóa ảnh";"features"->"Tìm đặc trưng";"matching"->"Ghép đặc trưng";"camera_poses"->"Khôi phục vị trí camera"
    "undistortion"->"Hiệu chỉnh ảnh";"mvs_import"->"Chuyển dữ liệu 3D";"dense_cloud"->"Dựng đám mây điểm";"mesh"->"Dựng bề mặt"
    "refine"->"Tinh chỉnh mesh";"texture"->"Tạo texture";"export_preview"->"Xuất mô hình xem trước";"calibrating"->"Hiệu chỉnh scale";else->scanStatusLabel(stage)
}
