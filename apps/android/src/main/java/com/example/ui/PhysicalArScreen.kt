package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ar.GlbWriter
import com.example.ar.PlacedFurniture
import com.example.data.*
import com.google.ar.core.*
import io.github.sceneview.*
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun PhysicalArScreen(placements:List<FurniturePlacement>,scanRoom:Boolean,onRoomMeasured:(RoomEntity)->Unit,onClose:()->Unit){
    val context=LocalContext.current
    var allowed by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var supported by remember{mutableStateOf<Boolean?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it}
    LaunchedEffect(Unit){ArCoreApk.getInstance().checkAvailabilityAsync(context){supported=it.isSupported}}
    BackHandler(onBack=onClose)
    if(!allowed || supported!=true){
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){
            Text(if(scanRoom)"Đo phòng bằng ARCore"else "Xem nội thất trong phòng thật",style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(when(supported){null->"Đang kiểm tra hỗ trợ ARCore…";false->"Thiết bị chưa hỗ trợ ARCore hoặc chưa có Google Play Services for AR. Bạn vẫn có thể nhập số đo phòng và dùng phòng 3D.";else->"Cần quyền camera để phát hiện mặt sàn và neo mô hình."})
            if(!allowed)Button(onClick={permission.launch(Manifest.permission.CAMERA)}){Text("Cấp quyền camera")}
            TextButton(onClick=onClose){Text("Quay lại")}
        }
        return
    }
    var reset by remember { mutableIntStateOf(0) }
    key(reset) { PhysicalArContent(placements,scanRoom,onRoomMeasured,onClose,onReset={reset++}) }
}

@Composable
private fun PhysicalArContent(placements:List<FurniturePlacement>,scanRoom:Boolean,onRoomMeasured:(RoomEntity)->Unit,onClose:()->Unit,onReset:()->Unit){
    val engine=rememberEngine()
    val loader=rememberModelLoader(engine)
    val renderView=rememberSpaceFitView(engine)
    val nodes=rememberNodes()
    val frameRef=remember{arrayOfNulls<Frame>(1)}
    val measured=remember{mutableStateListOf<Anchor>()}
    var anchorNode by remember{mutableStateOf<AnchorNode?>(null)}
    var tracking by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var height by remember{mutableStateOf("280")}
    var angle by remember{mutableFloatStateOf(0f)}
    var offsetX by remember{mutableFloatStateOf(0f)}
    var offsetZ by remember{mutableFloatStateOf(0f)}
    val content=rememberNode(engine)
    val pendingModels=rememberNodes()
    var modelsReady by remember{mutableStateOf(scanRoom)}
    LaunchedEffect(Unit){
        if(!scanRoom)try{withContext(Dispatchers.Main.immediate){
            for(placement in placements){
                val node=ModelNode(loader.createModelInstance(itemModelBuffer(placement.item))).apply{
                    position=Position(placement.xCm/100,0f,placement.zCm/100)
                    rotation=Rotation(0f,-placement.rotation,0f)
                }
                pendingModels.add(node);content.addChildNode(node)
            }
            modelsReady=true
        }}catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.localizedMessage.orEmpty()}
    }
    LaunchedEffect(angle,offsetX,offsetZ){withContext(Dispatchers.Main.immediate){content.rotation=Rotation(0f,-angle,0f);content.position=Position(offsetX,0f,offsetZ)}}
    Box(Modifier.fillMaxSize()){
        ARScene(Modifier.fillMaxSize(),engine=engine,view=renderView,modelLoader=loader,childNodes=nodes.toList(),
            sessionConfiguration={session,config->
                config.planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.depthMode=if(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.instantPlacementMode=Config.InstantPlacementMode.DISABLED
            },onSessionUpdated={_,frame->frameRef[0]=frame;val now=frame.camera.trackingState==TrackingState.TRACKING;if(tracking!=now)tracking=now},
            onSessionFailed={error=it.localizedMessage ?: "Không khởi tạo được ARCore"},
            onTrackingFailureChanged={reason->if(reason!=null)error="Camera mất bám vị trí: $reason. Di chuyển chậm, tăng ánh sáng." else error=""},
            onGestureListener=rememberOnGestureListener(onSingleTapConfirmed={event,_->
                val frame=frameRef[0]
                if(frame!=null && tracking && modelsReady){
                    val hit=frame.hitTest(event).firstOrNull{result->
                        val plane=result.trackable as? Plane
                        plane!=null && plane.type==Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(result.hitPose)
                    }
                    if(hit==null)error="Chưa thấy mặt sàn tại đây. Quét chậm qua sàn có hoa văn."
                    else if(scanRoom && measured.size<3){
                        val anchor=hit.createAnchor()
                        measured.add(anchor)
                        val marker=PlacedFurniture(0,"Góc phòng","BOX",0f,0f,0f,0f,5f,5f,5f,Color.Cyan).getTransformedMesh()
                        nodes.add(AnchorNode(engine,anchor).apply{addChildNode(ModelNode(loader.createModelInstance(GlbWriter.encode(marker))))})
                        error=""
                    }else if(!scanRoom && anchorNode==null){
                        anchorNode=AnchorNode(engine,hit.createAnchor()).also{it.addChildNode(content);nodes.add(it)}
                        error=""
                    }
                }
            }))
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),color=Color(0xDD0F172A)){
            Column(Modifier.padding(12.dp)){
                Text(if(scanRoom)"Đo 3 góc phòng chữ nhật"else "AR Preview • đúng scale theo model",color=Color.White,style=MaterialTheme.typography.titleMedium)
                Text(if(!tracking)"Di chuyển chậm để nhận diện sàn…" else if(scanRoom)when(measured.size){0->"Chạm góc A trên mặt sàn.";1->"Chạm góc B liền kề theo chiều rộng.";2->"Chạm góc C liền kề B theo chiều sâu.";else->"Đã chọn 3 góc. Kiểm tra số đo bên dưới."}
                    else if(anchorNode==null)"Chạm tâm phòng trên sàn để đặt thiết kế." else "Xoay để khớp hướng phòng; dịch chuyển bằng nút. Không kéo để đổi scale.",color=Color.White)
                if(error.isNotBlank())Text(error,color=Color(0xFFFFB4AB))
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),color=Color(0xEE0F172A)){
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                if(scanRoom && measured.size==3){
                    val a=measured[0].pose;val b=measured[1].pose;val c=measured[2].pose
                    val width=hypot(b.tx()-a.tx(),b.tz()-a.tz())*100
                    val depth=hypot(c.tx()-b.tx(),c.tz()-b.tz())*100
                    val dot=abs((b.tx()-a.tx())*(c.tx()-b.tx())+(b.tz()-a.tz())*(c.tz()-b.tz()))
                    val orthogonal=dot / (width*depth/10000).coerceAtLeast(0.001f)<0.2f
                    val sameFloor=maxOf(a.ty(),b.ty(),c.ty())-minOf(a.ty(),b.ty(),c.ty())<0.08f
                    val measuredHeight=height.replace(',','.').toFloatOrNull()
                    Text("Rộng × sâu: %.1f × %.1f cm".format(width,depth),color=Color.White)
                    OutlinedTextField(height,{height=it},label={Text("Chiều cao đo bằng thước (cm)")},singleLine=true)
                    if(!orthogonal || !sameFloor)Text("Ba điểm chưa vuông góc hoặc khác mặt sàn. Hãy đo lại.",color=Color(0xFFFFB4AB))
                    Text("Đây là phòng chữ nhật suy ra từ 3 điểm AR, không phải mesh toàn bộ tường. Cửa sẽ được đánh dấu ở bước tiếp theo.",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
                    Button(enabled=orthogonal && sameFloor && width in 100f..2000f && depth in 100f..2000f && measuredHeight!=null && measuredHeight.isFinite() && measuredHeight in 100f..1000f,
                        onClick={onRoomMeasured(RoomEntity(widthCm=width,depthCm=depth,heightCm=measuredHeight!!,measurementSource="ARCORE_3_POINTS"))},modifier=Modifier.fillMaxWidth()){Text("Dùng số đo phòng này")}
                }
                if(!scanRoom && anchorNode!=null){
                    Text("Xoay toàn thiết kế: ${angle.toInt()}°",color=Color.White)
                    Slider(angle,{angle=it},valueRange=-180f..180f)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        TextButton(onClick={offsetX-=0.05f}){Text("← 5 cm")}
                        TextButton(onClick={offsetX+=0.05f}){Text("5 cm →")}
                        TextButton(onClick={offsetZ-=0.05f}){Text("↑ 5 cm")}
                        TextButton(onClick={offsetZ+=0.05f}){Text("↓ 5 cm")}
                    }
                }
                Text("Độ chính xác phụ thuộc tracking và số đo tham chiếu; đối chiếu thước trước khi bố trí thật.",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onReset,modifier=Modifier.fillMaxWidth()){Text(if(scanRoom) "Đo lại từ góc A" else "Đặt lại vị trí neo")}
                TextButton(onClick=onClose,modifier=Modifier.fillMaxWidth()){Text("Quay lại thiết kế")}
            }
        }
    }
}
