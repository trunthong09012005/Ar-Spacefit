package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.*
import com.example.spacefit.RoomOpening
import java.util.UUID

@Composable
fun RoomSettingsDialog(room:RoomEntity,onDismiss:()->Unit,onSave:(RoomEntity)->Unit){
    var name by remember{mutableStateOf(room.name)}
    var width by remember{mutableStateOf(room.widthCm.toString())}
    var depth by remember{mutableStateOf(room.depthCm.toString())}
    var height by remember{mutableStateOf(room.heightCm.toString())}
    var openings by remember{mutableStateOf(parseOpenings(room.openingsJson))}
    var adding by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    fun parsed(value:String)=value.replace(',','.').toFloatOrNull()?.takeIf{it.isFinite() && it in 100f..2000f}
    val w=parsed(width);val d=parsed(depth);val h=parsed(height)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Phòng, cửa và chiều cao")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},label={Text("Tên phòng")},singleLine=true)
            OutlinedTextField(width,{width=it},label={Text("Rộng (cm)")},singleLine=true,isError=w==null)
            OutlinedTextField(depth,{depth=it},label={Text("Sâu (cm)")},singleLine=true,isError=d==null)
            OutlinedTextField(height,{height=it},label={Text("Cao (cm)")},singleLine=true,isError=h==null)
            Text("Nguồn số đo: ${if(room.measurementSource=="ARCORE_3_POINTS")"3 điểm ARCore; hãy kiểm tra bằng thước"else "Nhập bằng thước đo"}")
            Text("Bắc = phía trên mặt bằng, Tây = bên trái. Offset tính từ đầu tường phía trái/trên.",style=MaterialTheme.typography.bodySmall)
            openings.forEach{o->
                Text("${if(o.kind=="DOOR")"Cửa đi"else "Cửa sổ"} • ${wallName(o.wall)} • cách ${o.offsetCm.toInt()} cm • rộng ${o.widthCm.toInt()} cm")
                TextButton(onClick={openings=openings.filterNot{it.id==o.id}}){Text("Bỏ dấu cửa")}
            }
            OutlinedButton(onClick={adding=true}){Text("Đánh dấu cửa đi / cửa sổ")}
            Text("Vùng cửa đi là hình chữ nhật giữ trống để mở cửa; không mô phỏng chính xác cung quét cánh cửa.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={Button(enabled=w!=null && d!=null && h!=null && name.isNotBlank(),onClick={
        try{
            val next=room.copy(name=name.trim(),widthCm=w!!,depthCm=d!!,heightCm=h!!,openingsJson=openingsJson(openings),
                measurementSource=if(w==room.widthCm && d==room.depthCm)room.measurementSource else "MANUAL")
            val spec=next.spec();openings.forEach{it.reservedZone(spec);require(it.heightCm+it.sillCm<=spec.heightCm){"Cửa cao hơn phòng"}}
            onSave(next)
        }catch(failure:Exception){error=failure.localizedMessage.orEmpty()}
    }){Text("Lưu phòng")}},dismissButton={TextButton(onClick=onDismiss){Text("Hủy")}})
    if(adding)OpeningDialog(onDismiss={adding=false},onAdd={openings=openings+it;adding=false})
}

@Composable
private fun OpeningDialog(onDismiss:()->Unit,onAdd:(RoomOpening)->Unit){
    var kind by remember{mutableStateOf("DOOR")};var wall by remember{mutableStateOf("NORTH")}
    var offset by remember{mutableStateOf("50")};var width by remember{mutableStateOf("90")}
    var height by remember{mutableStateOf("210")};var sill by remember{mutableStateOf("0")};var clearance by remember{mutableStateOf("90")}
    var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Đánh dấu cửa")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
            ChoiceRow("Loại",listOf("DOOR" to "Cửa đi","WINDOW" to "Cửa sổ"),kind,{kind=it})
            ChoiceRow("Tường",listOf("NORTH" to "Bắc","SOUTH" to "Nam","WEST" to "Tây","EAST" to "Đông"),wall,{wall=it})
            OutlinedTextField(offset,{offset=it},label={Text("Cách đầu tường (cm)")},singleLine=true)
            OutlinedTextField(width,{width=it},label={Text("Rộng cửa (cm)")},singleLine=true)
            OutlinedTextField(height,{height=it},label={Text("Cao cửa (cm)")},singleLine=true)
            if(kind=="WINDOW")OutlinedTextField(sill,{sill=it},label={Text("Bệ cửa cách sàn (cm)")},singleLine=true)
            OutlinedTextField(clearance,{clearance=it},label={Text("Vùng giữ trống trước cửa (cm)")},singleLine=true)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={Button(onClick={
        try{onAdd(RoomOpening(UUID.randomUUID().toString(),kind,wall,offset.replace(',','.').toFloat(),width.replace(',','.').toFloat(),height.replace(',','.').toFloat(),
            if(kind=="WINDOW")sill.replace(',','.').toFloat()else 0f,clearance.replace(',','.').toFloat()))}
        catch(_:Exception){error="Nhập số đo hợp lệ; kích thước phải lớn hơn 0, offset/bệ cửa không âm."}
    }){Text("Thêm")}},dismissButton={TextButton(onClick=onDismiss){Text("Hủy")}})
}
private fun wallName(value:String)=when(value){"NORTH"->"Bắc";"SOUTH"->"Nam";"WEST"->"Tây";else->"Đông"}
