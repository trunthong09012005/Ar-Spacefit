package com.example.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.*
import com.example.spacefit.*
import kotlin.math.*

@Composable
fun FloorPlan(room:RoomEntity,placements:List<FurniturePlacement>,selectedId:Long?,onSelect:(Long?)->Unit,
    onMove:(FurniturePlacement)->Unit,onRegion:(FreeRegion)->Unit,selectRegion:Boolean,modifier:Modifier=Modifier){
    val currentPlacements by rememberUpdatedState(placements)
    val latestMove by rememberUpdatedState(onMove)
    var regionStart by remember{mutableStateOf<Offset?>(null)}
    var regionEnd by remember{mutableStateOf<Offset?>(null)}
    var dragging by remember{mutableStateOf<Long?>(null)}
    fun scale(w:Float,h:Float)=min((w-48).coerceAtLeast(1f)/room.widthCm,(h-48).coerceAtLeast(1f)/room.depthCm)
    fun world(p:Offset,w:Float,h:Float):FloorPoint{val s=scale(w,h);return FloorPoint((p.x-w/2)/s,(p.y-h/2)/s)}
    fun hit(p:FloorPoint)=currentPlacements.lastOrNull{item->
        val radians=Math.toRadians(-item.rotation.toDouble());val dx=p.x-item.xCm;val dz=p.z-item.zCm
        abs(dx*cos(radians)-dz*sin(radians))<=item.item.widthCm/2 && abs(dx*sin(radians)+dz*cos(radians))<=item.item.depthCm/2
    }
    Canvas(modifier.pointerInput(room,selectRegion){detectTapGestures{point->onSelect(hit(world(point,size.width.toFloat(),size.height.toFloat()))?.id)}}
        .pointerInput(room,selectRegion){detectDragGestures(onDragStart={point->
            if(selectRegion){regionStart=point;regionEnd=point}
            else{dragging=hit(world(point,size.width.toFloat(),size.height.toFloat()))?.takeUnless{it.locked}?.id;onSelect(dragging)}
        },onDragEnd={
            val start=regionStart;val end=regionEnd
            if(selectRegion && start!=null && end!=null){
                val a=world(start,size.width.toFloat(),size.height.toFloat());val b=world(end,size.width.toFloat(),size.height.toFloat())
                if(abs(a.x-b.x)>5 && abs(a.z-b.z)>5)onRegion(FreeRegion((a.x+b.x)/2,(a.z+b.z)/2,abs(a.x-b.x),abs(a.z-b.z)))
            }
            regionStart=null;regionEnd=null;dragging=null
        },onDragCancel={regionStart=null;regionEnd=null;dragging=null}){change,delta->
            change.consume()
            if(selectRegion)regionEnd=change.position
            else currentPlacements.firstOrNull{it.id==dragging}?.let{item->
                val s=scale(size.width.toFloat(),size.height.toFloat())
                latestMove(item.copy(xCm=(item.xCm+delta.x/s).coerceIn(-room.widthCm,room.widthCm),zCm=(item.zCm+delta.y/s).coerceIn(-room.depthCm,room.depthCm)))
            }
        }}){
        drawRect(Color(0xFF0B1220))
        val s=scale(size.width,size.height)
        fun screen(p:FloorPoint)=Offset(size.width/2+p.x*s,size.height/2+p.z*s)
        val left=size.width/2-room.widthCm*s/2;val top=size.height/2-room.depthCm*s/2
        drawRect(Color(0xFF1E293B),Offset(left,top),androidx.compose.ui.geometry.Size(room.widthCm*s,room.depthCm*s))
        drawRect(Color(0xFF94A3B8),Offset(left,top),androidx.compose.ui.geometry.Size(room.widthCm*s,room.depthCm*s),style=Stroke(2f))
        fun polygon(points:List<FloorPoint>):Path=Path().apply{points.forEachIndexed{i,p->val o=screen(p);if(i==0)moveTo(o.x,o.y)else lineTo(o.x,o.y)};close()}
        val spec=room.spec()
        spec.openings.forEach{opening->drawPath(polygon(opening.reservedZone(spec).corners()),if(opening.kind=="DOOR")Color(0x889F5916)else Color(0x884487BB))}
        placements.forEach{placement->
            val result=SpaceFitEngine.evaluate(spec,placement.footprint(),placements.map{it.footprint()})
            val invalid=!result.isInsideRoom || result.hasCollision || result.doorBlockagePercent>0 || result.hasWindowObstruction
            val path=polygon(placement.footprint().corners())
            drawPath(path,if(invalid)Color(0xAAEF4444)else Color(0xAA22D3EE))
            drawPath(path,if(placement.id==selectedId)Color.White else Color(0xFF67E8F9),style=Stroke(if(placement.id==selectedId)3f else 1f))
            val label=screen(FloorPoint(placement.xCm,placement.zCm))
            drawContext.canvas.nativeCanvas.drawText(placement.item.name.take(14),label.x,label.y,Paint().apply{color=android.graphics.Color.WHITE;textAlign=Paint.Align.CENTER;textSize=24f;isAntiAlias=true})
        }
        val a=regionStart;val b=regionEnd
        if(a!=null && b!=null)drawRect(Color(0x88A3E635),Offset(min(a.x,b.x),min(a.y,b.y)),androidx.compose.ui.geometry.Size(abs(a.x-b.x),abs(a.y-b.y)),style=Stroke(3f))
        drawContext.canvas.nativeCanvas.drawText("${room.widthCm.toInt()} × ${room.depthCm.toInt()} cm",size.width/2,18f,Paint().apply{color=android.graphics.Color.LTGRAY;textAlign=Paint.Align.CENTER;textSize=22f;isAntiAlias=true})
    }
}
