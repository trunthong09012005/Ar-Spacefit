package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ar.*
import com.example.data.*
import io.github.sceneview.*
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

internal suspend fun itemModelBuffer(item:ItemEntity):ByteBuffer=withContext(Dispatchers.IO){
    if(item.modelPath.isNotBlank()){
        val file=File(item.modelPath)
        require(file.isFile){"Model ${item.name} không còn trong bộ nhớ; tải lại từ lịch sử quét."}
        require(file.length()<=150L*1024*1024){"Model quá lớn"}
        ByteBuffer.wrap(file.readBytes())
    }else{
        val mesh=item.toPlacedFurniture(posX=0f,posZ=0f).getTransformedMesh()
        GlbWriter.encode(mesh)
    }
}

@Composable
fun RoomScene(room:RoomEntity,placements:List<FurniturePlacement>,onSelect:(Long)->Unit,modifier:Modifier=Modifier,onSurface:(SceneView,Boolean)->Unit={_,_->}){
    if(LocalInspectionMode.current){Box(modifier){Text("Phòng 3D")};return}
    // A new resource graph is needed only when assets or room geometry change, not when dragging.
    key(room.id,room.widthCm,room.depthCm,room.heightCm,room.openingsJson,placements.map{it.id to it.item}){
        RoomSceneContent(room,placements,onSelect,modifier,onSurface)
    }
}

@Composable
private fun RoomSceneContent(room:RoomEntity,placements:List<FurniturePlacement>,onSelect:(Long)->Unit,modifier:Modifier,onSurface:(SceneView,Boolean)->Unit){
    val engine=rememberEngine()
    val loader=rememberModelLoader(engine)
    val renderView=rememberSpaceFitView(engine)
    val nodes=rememberNodes()
    val modelNodes=remember { mutableMapOf<Long,ModelNode>() }
    val latestSurfaceCallback by rememberUpdatedState(onSurface)
    val ownedSurface=remember{arrayOfNulls<SceneView>(1)}
    DisposableEffect(Unit){onDispose{ownedSurface[0]?.let{latestSurfaceCallback(it,false)};ownedSurface[0]=null}}
    var ready by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val extent=maxOf(room.widthCm,room.depthCm)/100f
    val camera=rememberCameraNode(engine){position=Position(extent*0.75f,extent*0.85f,extent*0.9f);lookAt(Position(0f,0f,0f))}
    LaunchedEffect(Unit){
        try { withContext(Dispatchers.Main.immediate) {
            fun slab(width:Float,depth:Float,height:Float,x:Float,z:Float,color:Color)=
                PlacedFurniture(id=0,name="Room",modelType="BOX",posX=x,posY=0f,posZ=z,rotationY=0f,
                    widthCm=width,heightCm=height,depthCm=depth,color=color).getTransformedMesh().let { mesh ->
                        Mesh3D(mesh.faces.map { it.copy(baseColor=color) })
                    }
            val faces=mutableListOf<PolygonFace>()
            faces+=slab(room.widthCm,room.depthCm,1f,0f,0f,Color(0xFF3C465B)).faces
            faces+=slab(room.widthCm,2f,room.heightCm,0f,-room.depthCm/200,Color(0x557C8DAD)).faces
            faces+=slab(2f,room.depthCm,room.heightCm,-room.widthCm/200,0f,Color(0x447C8DAD)).faces
            room.spec().openings.forEach{o->
                val zone=o.reservedZone(room.spec())
                faces+=slab(zone.widthCm,zone.depthCm,2f,zone.centerXcm/100,zone.centerZcm/100,
                    if(o.kind=="DOOR")Color(0x99F9A857)else Color(0x997DD3FC)).faces
            }
            nodes.add(ModelNode(loader.createModelInstance(GlbWriter.encode(Mesh3D(faces),preserveTransparency=true))))
            for(placement in placements){
                val buffer=itemModelBuffer(placement.item)
                val node=ModelNode(loader.createModelInstance(buffer)).apply{
                    position=Position(placement.xCm/100,0f,placement.zCm/100)
                    rotation=Rotation(0f,-placement.rotation,0f)
                }
                nodes.add(node);modelNodes[placement.id]=node
            }
            ready=true
        }}catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){
            error=failure.localizedMessage ?: "Không thể tải model trong phòng"
            android.util.Log.e("SpaceFitRender","Room model load failed",failure)
        }
    }
    LaunchedEffect(placements,ready){withContext(Dispatchers.Main.immediate){placements.forEach{p->modelNodes[p.id]?.apply{
        position=Position(p.xCm/100,0f,p.zCm/100);rotation=Rotation(0f,-p.rotation,0f)
    }}}}
    Box(modifier.testTag(if(ready)"room_ready" else "room_loading")){
        Scene(Modifier.fillMaxSize(),engine=engine,view=renderView,modelLoader=loader,cameraNode=camera,childNodes=nodes.toList(),
            onViewCreated={ownedSurface[0]=this;latestSurfaceCallback(this,true)},
            onGestureListener=rememberOnGestureListener(onSingleTapConfirmed={_,node->
                var current:Node?=node
                while(current!=null){
                    val matched=modelNodes.entries.firstOrNull{it.value==current}
                    if(matched!=null){onSelect(matched.key);break}
                    current=current.parent
                }
            }))
        if(error.isNotBlank())Surface(Modifier.align(Alignment.Center).padding(16.dp)){Text(error,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        else if(!ready)CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
