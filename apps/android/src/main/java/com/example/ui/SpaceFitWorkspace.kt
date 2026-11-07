package com.example.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.*
import com.example.spacefit.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceFitWorkspace(vm:WorkspaceViewModel=viewModel()){
    val draft by vm.draft.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val planning by vm.planning.collectAsStateWithLifecycle()
    var route by rememberSaveable{mutableStateOf("room")}
    var roomSettings by remember{mutableStateOf(false)}
    var projectList by remember{mutableStateOf(false)}
    var adding by remember{mutableStateOf(false)}
    var planView by rememberSaveable{mutableStateOf(false)}
    var regionMode by remember{mutableStateOf(false)}
    var region by remember{mutableStateOf<FreeRegion?>(null)}
    var style by rememberSaveable{mutableStateOf("WALLS")}
    var exportBounds by remember{mutableStateOf<ComposeRect?>(null)}
    val roomSurface=remember{SurfaceReference<io.github.sceneview.SceneView>()}
    var compare by remember{mutableStateOf(false)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val exportStore=remember(context){DesignExportStore(context.cacheDir,File(context.filesDir,"design-images"))}
    var exportSnapshotPath by rememberSaveable{mutableStateOf<String?>(null)}
    var exportProjectId by rememberSaveable{mutableStateOf<String?>(null)}
    var exporting by remember{mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        val snapshot=exportSnapshotPath?.let(::File)
        val owner=exportProjectId
        scope.launch{
            exporting=true
            try{
                if(uri!=null){
                    val local=withContext(Dispatchers.IO){
                        context.contentResolver.openOutputStream(uri)?.use{output->
                            exportStore.publish(snapshot ?: error("Ảnh tạm không còn. Hãy xuất lại."),output)
                        } ?: error("Không thể ghi ảnh")
                    }
                    // Never attach an old project's image to a different design.
                    vm.edit{if(it.id==owner)it.copy(afterPath=local.absolutePath) else it}
                    vm.message.value="Đã xuất ảnh thiết kế PNG."
                }
            }catch(cancel:CancellationException){throw cancel
            }catch(error:Exception){vm.message.value="Chưa xuất được ảnh: ${error.localizedMessage}"}
            finally{
                withContext(NonCancellable+Dispatchers.IO){snapshot?.let(exportStore::discard)}
                exportSnapshotPath=null;exportProjectId=null;exporting=false
            }
        }
    }
    val before=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch{
            try{
                val bitmap=withContext(Dispatchers.IO){com.example.camera.decodeSampledBitmap(context,uri,2400)} ?: error("Không đọc được ảnh phòng")
                val file=withContext(Dispatchers.IO){File(context.filesDir,"design-images/before-${System.currentTimeMillis()}.jpg").apply{
                    parentFile?.mkdirs();outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}
                }}
                bitmap.recycle();vm.edit{it.copy(beforePath=file.absolutePath)}
            }catch(error:Exception){vm.message.value=error.localizedMessage.orEmpty()}
        }
    }
    BackHandler(enabled=route!="room"){route="room"}
    when(route){
        "scan"->{ReconstructionScreen(onClose={route="room"},onLegacyScan={route="legacy"});return}
        "legacy"->{ScannerScreen(onItemSaved={scope.launch{vm.db.itemDao().insertItem(it)}},onTestInARRoom={vm.add(it);route="room"},onCameraPermissionChanged={},onClose={route="room"});return}
        "ar","measure"->{PhysicalArScreen(draft?.placements.orEmpty(),route=="measure",onRoomMeasured={room->vm.updateRoom(room);route="room";roomSettings=true},onClose={route="room"});return}
    }
    Scaffold(modifier=Modifier.testTag("workspace_scaffold"),topBar={
        if(route=="room")TopAppBar(title={Column{Text("AR-SpaceFit");Text(draft?.name ?: "Đang mở thiết kế…",style=MaterialTheme.typography.labelMedium)}},actions={
            IconButton(onClick={projectList=true}){Icon(Icons.Default.FolderOpen,"Dự án thiết kế")}
            IconButton(onClick={vm.saveNow()}){Icon(Icons.Default.Save,"Lưu thiết kế")}
            IconButton(onClick={roomSettings=true}){Icon(Icons.Default.SquareFoot,"Kích thước và cửa")}
        })
    },bottomBar={NavigationBar{
        NavigationBarItem(route=="room",{route="room"},icon={Icon(Icons.Default.ViewInAr,null)},label={Text("Phòng 3D")},modifier=Modifier.testTag("workspace_room"))
        NavigationBarItem(false,{route="scan"},icon={Icon(Icons.Default.DocumentScanner,null)},label={Text("Quét 3D")},modifier=Modifier.testTag("workspace_scan"))
        NavigationBarItem(route=="inventory",{route="inventory"},icon={Icon(Icons.Default.Inventory2,null)},label={Text("My Furniture")},modifier=Modifier.testTag("workspace_inventory"))
    }}){padding->
        if(route=="inventory")InventoryScreen(items,onTestIn3D={vm.add(it);route="room"},onDeleteItem={scope.launch{vm.db.itemDao().deleteItem(it)}},
            onAddNewItem={scope.launch{vm.db.itemDao().insertItem(it)}},onUpdateItem={scope.launch{vm.db.itemDao().updateItem(it)}},modifier=Modifier.padding(padding))
        else if(draft==null)Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center){
            Column(horizontalAlignment=Alignment.CenterHorizontally){Text(message.ifBlank{"Đang đọc dữ liệu…"});if(message.isNotBlank())Button(onClick={vm.newProject()}){Text("Tạo thiết kế mới")}}
        } else {
            val design=requireNotNull(draft)
            val selected=design.placements.firstOrNull{it.id==vm.selectedId}
            Column(Modifier.fillMaxSize().padding(padding)){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    FilterChip(!planView,{planView=false;regionMode=false},label={Text("3D")})
                    FilterChip(planView,{planView=true},label={Text("Mặt bằng")})
                    Button(onClick={adding=true},modifier=Modifier.weight(1f)){Text("Thêm đồ")}
                    OutlinedButton(onClick={route="ar"},enabled=design.placements.isNotEmpty()){Text("AR")}
                }
                Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned{exportBounds=it.boundsInWindow()}){
                    if(planView)FloorPlan(design.room,design.placements,vm.selectedId,{vm.selectedId=it},vm::update,{region=it;regionMode=false},regionMode,Modifier.fillMaxSize().testTag("floor_plan"))
                    else RoomScene(design.room,design.placements,{vm.selectedId=it},Modifier.fillMaxSize(),onSurface={surface,created->
                        if(created)roomSurface.created(surface) else roomSurface.released(surface)
                    })
                    if(design.placements.isEmpty() && !regionMode)Surface(Modifier.align(Alignment.Center).padding(24.dp),shape=MaterialTheme.shapes.large){
                        Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("Quét món đồ đầu tiên của bạn");Button(onClick={route="scan"}){Text("Mở Furniture Scanner")}}
                    }
                    if(regionMode)Surface(Modifier.align(Alignment.TopCenter).padding(8.dp)){Text("Kéo một hình chữ nhật trong vùng trống",Modifier.padding(8.dp))}
                }
                Column(Modifier.fillMaxWidth().heightIn(max=300.dp).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
                    Text("${design.room.widthCm.toInt()} × ${design.room.depthCm.toInt()} × ${design.room.heightCm.toInt()} cm • ${design.placements.size} đồ vật • tự lưu",style=MaterialTheme.typography.labelMedium)
                    if(selected!=null)PlacementControls(selected,design,vm)
                    else Text("Chạm đồ vật để chọn. Kéo trên mặt bằng để di chuyển; kéo trong 3D để xoay góc nhìn.",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        OutlinedButton(onClick={planView=true;regionMode=!regionMode},modifier=Modifier.weight(1f)){Text("Đồ nào vừa đây?")}
                        OutlinedButton(onClick={route="measure"},modifier=Modifier.weight(1f)){Text("Đo phòng AR")}
                    }
                    ChoiceRow("Gợi ý bố trí cục bộ",listOf("WALLS" to "Gần tường","OPEN_CENTER" to "Thoáng giữa phòng"),style,{style=it})
                    Button(onClick={vm.suggest(style)},enabled=design.placements.isNotEmpty() && !planning,modifier=Modifier.fillMaxWidth()){
                        Text(if(planning)"Đang kiểm tra các phương án…"else "Tạo phương án • giữ nguyên đồ đã khóa")
                    }
                    suggestions.forEachIndexed{index,_->OutlinedButton(onClick={vm.applySuggestion(index)},modifier=Modifier.fillMaxWidth()){Text("Áp dụng phương án ${index+1}")}}
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        TextButton(onClick={before.launch(arrayOf("image/*"))}){Text("Ảnh Before")}
                        TextButton(onClick={scope.launch{
                            exporting=true
                            var snapshot:File?=null
                            var launched=false
                            try{
                                val bounds=exportBounds ?: error("Cảnh thiết kế chưa sẵn sàng")
                                val owner=design.id
                                val bitmap=if(planView)captureDesign(context as Activity,bounds)
                                    else captureSurface(roomSurface.current ?: error("Cảnh 3D chưa sẵn sàng. Thử lại sau."))
                                try{withContext(Dispatchers.IO){snapshot=exportStore.capture(bitmap)}}finally{bitmap.recycle()}
                                exportSnapshotPath=requireNotNull(snapshot).absolutePath;exportProjectId=owner
                                export.launch("spacefit-design.png");launched=true
                            }catch(cancel:CancellationException){throw cancel
                            }catch(error:Exception){vm.message.value="Chưa xuất được ảnh: ${error.localizedMessage}"}
                            finally{if(!launched){
                                withContext(NonCancellable+Dispatchers.IO){snapshot?.let(exportStore::discard)}
                                exportSnapshotPath=null;exportProjectId=null;exporting=false
                            }}
                        }},enabled=Build.VERSION.SDK_INT>=26 && !exporting && exportSnapshotPath==null){Text("Xuất PNG")}
                        TextButton(onClick={compare=true},enabled=design.beforePath.isNotBlank() || design.afterPath.isNotBlank()){Text("Before / After")}
                    }
                }
            }
        }
    }
    if(roomSettings && draft!=null)RoomSettingsDialog(requireNotNull(draft).room,onDismiss={roomSettings=false},onSave={vm.updateRoom(it);roomSettings=false})
    if(projectList)ModalBottomSheet(onDismissRequest={projectList=false}){
        var name by remember(draft?.id){mutableStateOf(draft?.name.orEmpty())}
        Column(Modifier.padding(16.dp)){
            OutlinedTextField(name,{name=it},label={Text("Tên thiết kế hiện tại")},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={if(name.isNotBlank())vm.edit{it.copy(name=name.trim())}}){Text("Đổi tên")}
            Button(onClick={vm.newProject();projectList=false}){Text("Tạo dự án mới")}
            LazyColumn(Modifier.heightIn(max=350.dp)){items(projects,key={it.id}){project->
                TextButton(onClick={vm.openProject(project);projectList=false},modifier=Modifier.fillMaxWidth()){Text(project.name)}
            }}
        }
    }
    if(adding)ModalBottomSheet(onDismissRequest={adding=false}){
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=450.dp).padding(16.dp)){
            item{Text("Nội thất đã lưu",style=MaterialTheme.typography.titleLarge)}
            if(items.isEmpty())item{Text("Kho đang trống. Quét đồ hoặc thêm thủ công trong My Furniture.")}
            items(items,key={it.id}){item->TextButton(onClick={vm.add(item);adding=false},modifier=Modifier.fillMaxWidth()){
                Text("${item.name} • %.0f × %.0f × %.0f cm".format(item.widthCm,item.depthCm,item.heightCm))
            }}
        }
    }
    region?.let{area->val design=draft ?: return@let
        RegionFitDialog(design,area,items,onDismiss={region=null},onAdd={result,option->
            vm.addRegionFit(result,option);region=null
        })
    }
    if(compare && draft!=null)AlertDialog(onDismissRequest={compare=false},title={Text("Before / After")},text={
        Column{
            Text("Phòng thật trước bố trí")
            AsyncImage(File(requireNotNull(draft).beforePath),"Before",Modifier.fillMaxWidth().height(170.dp))
            Text("Thiết kế đã xuất gần nhất")
            AsyncImage(File(requireNotNull(draft).afterPath),"After",Modifier.fillMaxWidth().height(170.dp))
        }
    },confirmButton={TextButton(onClick={compare=false}){Text("Đóng")}})
}

@Composable
private fun RegionFitDialog(design:DesignDraft,region:FreeRegion,inventory:List<ItemEntity>,
    onDismiss:()->Unit,onAdd:(RegionFitResult,RegionFitOption)->Unit){
    var result by remember(design,region,inventory){mutableStateOf<RegionFitResult?>(null)}
    var error by remember(design,region,inventory){mutableStateOf<String?>(null)}
    LaunchedEffect(design,region,inventory){
        try{result=RegionFitSearch.find(design,region,inventory)}
        catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){error="Chưa kiểm tra được vùng. Đóng và thử lại."}
    }
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("region_fit_dialog"),
        title={Text("Vùng %.0f × %.0f cm".format(region.widthCm,region.depthCm))},text={
            LazyColumn(Modifier.heightIn(max=400.dp)){
                item{Text("Thử tâm và lưới 11 × 11 ở góc 0°/90°, kiểm tra tường, trần, đồ và cửa. Chưa đánh giá toàn tuyến đi lại hay bảo đảm khoảng cách khuyến nghị. Không tìm được không có nghĩa là chắc chắn không vừa.")}
                val current=result
                when{
                    error!=null->item{Text(requireNotNull(error))}
                    current==null->item{Text("Đang tìm vị trí…",Modifier.testTag("region_fit_loading"))}
                    current.options.isEmpty()->item{Text("Kho đang trống. Thêm nội thất vào My Furniture trước.")}
                    else->{
                        item{Text("${current.options.count{it.placement!=null}}/${current.options.size} món có vị trí thử hợp lệ",Modifier.testTag("region_fit_summary"))}
                        items(current.options,key={it.item.id}){option->
                            TextButton(enabled=option.placement!=null,onClick={onAdd(current,option)},
                                modifier=Modifier.fillMaxWidth().testTag("region_fit_item_${option.item.id}")){
                                Column{
                                    Text("${if(option.placement==null)"—" else "✓"} ${option.item.name} (${option.item.widthCm.toInt()} × ${option.item.depthCm.toInt()} cm)")
                                    if(option.reason.isNotBlank())Text(option.reason,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick=onDismiss){Text("Đóng")}})
}

@Composable
private fun PlacementControls(selected:FurniturePlacement,design:DesignDraft,vm:WorkspaceViewModel){
    val result=SpaceFitEngine.evaluate(design.room.spec(),selected.footprint(),design.placements.map{it.footprint()})
    Text("${selected.item.name} • SpaceFit ${result.score}/100",style=MaterialTheme.typography.titleMedium)
    result.messages.filterNot{it=="Vật thể nằm hoàn toàn trong phòng." || it=="Không phát hiện va chạm."}.forEach{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(selected.locked,{vm.update(selected.copy(locked=it))});Text("Giữ nguyên vị trí khi gợi ý bố trí")}
    if(!selected.locked){
        Text("X: %.0f cm • Z: %.0f cm • Xoay: %.0f°".format(selected.xCm,selected.zCm,selected.rotation))
        Slider(selected.xCm.coerceIn(-design.room.widthCm/2,design.room.widthCm/2),{vm.update(selected.copy(xCm=it))},valueRange=-design.room.widthCm/2..design.room.widthCm/2)
        Slider(selected.zCm.coerceIn(-design.room.depthCm/2,design.room.depthCm/2),{vm.update(selected.copy(zCm=it))},valueRange=-design.room.depthCm/2..design.room.depthCm/2)
        Slider(selected.rotation,{vm.update(selected.copy(rotation=it))},valueRange=0f..360f,steps=71)
        Row{
            TextButton(onClick={
                val corners=selected.footprint().corners()
                val candidates=listOf(
                    selected.copy(xCm=selected.xCm-design.room.widthCm/2-corners.minOf{it.x}),
                    selected.copy(xCm=selected.xCm+design.room.widthCm/2-corners.maxOf{it.x}),
                    selected.copy(zCm=selected.zCm-design.room.depthCm/2-corners.minOf{it.z}),
                    selected.copy(zCm=selected.zCm+design.room.depthCm/2-corners.maxOf{it.z}))
                val closest=candidates.minByOrNull{kotlin.math.abs(it.xCm-selected.xCm)+kotlin.math.abs(it.zCm-selected.zCm)}!!
                vm.update(closest)
            }){Text("Sát tường")}
            TextButton(onClick={vm.duplicate(selected)}){Text("Nhân bản")}
            TextButton(onClick={vm.remove(selected.id)}){Text("Bỏ khỏi phòng")}
        }
    }
}

private suspend fun captureDesign(activity:Activity,bounds:ComposeRect):Bitmap=suspendCancellableCoroutine{continuation->
    if(Build.VERSION.SDK_INT<26){continuation.resumeWithException(IllegalStateException("Xuất PNG cần Android 8 trở lên"));return@suspendCancellableCoroutine}
    val rect=Rect(bounds.left.toInt().coerceAtLeast(0),bounds.top.toInt().coerceAtLeast(0),bounds.right.toInt(),bounds.bottom.toInt())
    val bitmap=Bitmap.createBitmap(rect.width().coerceAtLeast(1),rect.height().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
    PixelCopy.request(activity.window,rect,bitmap,{result->
        if(!continuation.isActive)bitmap.recycle()
        else if(result==PixelCopy.SUCCESS)continuation.resume(bitmap)
        else{bitmap.recycle();continuation.resumeWithException(IllegalStateException("Chưa có khung hình để xuất ($result). Thử lại sau."))}
    },Handler(Looper.getMainLooper()))
}
