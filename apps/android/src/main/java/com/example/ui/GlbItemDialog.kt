package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ItemEntity

@Composable
fun GlbItemDialog(item:ItemEntity,onSave:(ItemEntity)->Unit,onPlace:(ItemEntity)->Unit,onDismiss:()->Unit){
    var name by rememberSaveable(item.id){mutableStateOf(item.name)}
    var notes by rememberSaveable(item.id){mutableStateOf(item.notes)}
    Dialog(onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp),shape=MaterialTheme.shapes.large){
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(when(item.modelSource){"PHOTOGRAMMETRY"->"Mô hình quét thật";"TEST_FIXTURE"->"Mô hình kiểm thử GLB";else->"Mô hình GLB"},
                    modifier=Modifier.testTag("glb_item_dialog"),style=MaterialTheme.typography.titleLarge)
                GlbViewer(item.modelPath,Modifier.fillMaxWidth().height(300.dp))
                Text("W × D × H: %.1f × %.1f × %.1f cm".format(item.widthCm,item.depthCm,item.heightCm))
                Text("${item.polygonCount} tam giác • ${if(item.modelSource=="PHOTOGRAMMETRY")"texture từ ảnh" else item.modelSource} • lưu ngoại tuyến")
                OutlinedTextField(name,{name=it},label={Text("Tên")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(notes,{notes=it},label={Text("Ghi chú")},modifier=Modifier.fillMaxWidth())
                Text("Muốn đổi tỷ lệ hoặc cắt mesh, mở lại phiên quét trên laptop. Kích thước ở đây là kích thước model đã hiệu chỉnh.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={onSave(item.copy(name=name.trim(),notes=notes))},enabled=name.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Lưu thay đổi")}
                Button(onClick={onPlace(item.copy(name=name.trim(),notes=notes))},enabled=name.isNotBlank() && item.scaleConfirmed,modifier=Modifier.fillMaxWidth()){Text("Đặt vào phòng")}
                TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()){Text("Đóng")}
            }
        }
    }
}
