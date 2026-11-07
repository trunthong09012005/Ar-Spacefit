package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sceneview.*
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

@Composable
fun GlbViewer(path:String, modifier:Modifier=Modifier, rotations:List<Float> = listOf(0f,0f,0f)) {
    if(LocalInspectionMode.current) { Box(modifier){Text("GLB • xoay / zoom")};return }
    key(path) { GlbViewerContent(path, modifier, rotations) }
}

@Composable
private fun GlbViewerContent(path:String, modifier:Modifier, rotations:List<Float>) {
    val engine=rememberEngine()
    val loader=rememberModelLoader(engine)
    val renderView=rememberSpaceFitView(engine)
    val nodes=rememberNodes()
    val camera=rememberCameraNode(engine) { position=Position(0f,0f,3f) }
    var node by remember { mutableStateOf<ModelNode?>(null) }
    var renderedFrames by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(path) {
        try {
            val buffer=withContext(Dispatchers.IO){
                val file=File(path)
                require(file.exists()){ "Model chưa có trên máy. Mở lịch sử quét để tải lại." }
                require(file.length() in 20..150L*1024*1024){ "Model quá lớn hoặc file trống." }
                ByteBuffer.wrap(file.readBytes())
            }
            // Explicit affinity: never depend on the effect/test coroutine dispatcher.
            withContext(Dispatchers.Main.immediate) {
                check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
                node=ModelNode(loader.createModelInstance(buffer),scaleToUnits=1.7f).also{
                    // This SceneView version's centerOrigin(0,0,0) does not subtract
                    // the source bounding-box centre. Centre explicitly for viewing.
                    it.position=Position(-it.center.x*it.scale.x,-it.center.y*it.scale.y,-it.center.z*it.scale.z)
                    nodes.add(it)
                }
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.localizedMessage ?: "Không thể mở GLB"}
    }
    LaunchedEffect(node,rotations){withContext(Dispatchers.Main.immediate){node?.rotation=Rotation(rotations[0],rotations[1],rotations[2])}}
    Box(modifier) {
        Scene(Modifier.fillMaxSize(),engine=engine,view=renderView,modelLoader=loader,cameraNode=camera,childNodes=nodes.toList(),
            onFrame={if(node!=null && renderedFrames<3)renderedFrames++})
        if(error.isNotBlank()) Surface(Modifier.align(Alignment.Center).padding(16.dp)){Text(error,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        else if(node==null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        Text("Kéo để xoay • Chụm để zoom",modifier=Modifier.align(Alignment.BottomCenter).padding(8.dp)
            .testTag(if(node!=null && renderedFrames>=3)"glb_ready" else "glb_loading"),style=MaterialTheme.typography.labelSmall)
    }
}
