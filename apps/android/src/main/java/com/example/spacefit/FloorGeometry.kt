package com.example.spacefit

import kotlin.math.*

data class FloorPoint(val x:Float,val z:Float)

fun FurnitureFootprint.corners():List<FloorPoint> {
    val radians=Math.toRadians(rotationDegrees.toDouble())
    val c=cos(radians).toFloat();val s=sin(radians).toFloat()
    return listOf(-1f to -1f,1f to -1f,1f to 1f,-1f to 1f).map{(x,z)->
        val px=x*widthCm/2;val pz=z*depthCm/2
        FloorPoint(centerXcm+px*c-pz*s,centerZcm+px*s+pz*c)
    }
}

/** Convex floor footprints: SAT for overlap and polygon clipping for area. Touching is allowed. */
object FloorGeometry {
    private const val EPS=0.001f
    fun overlaps(a:List<FloorPoint>,b:List<FloorPoint>):Boolean {
        for(poly in listOf(a,b)) for(i in poly.indices){
            val p=poly[i];val q=poly[(i+1)%poly.size]
            val nx=-(q.z-p.z);val nz=q.x-p.x
            fun project(point:FloorPoint)=point.x*nx+point.z*nz
            val tolerance=EPS*sqrt(nx*nx+nz*nz)
            if(a.maxOf(::project)<=b.minOf(::project)+tolerance || b.maxOf(::project)<=a.minOf(::project)+tolerance) return false
        }
        return true
    }
    fun area(poly:List<FloorPoint>):Float=if(poly.size<3)0f else abs(poly.indices.sumOf{i->
        val a=poly[i];val b=poly[(i+1)%poly.size];(a.x*b.z-b.x*a.z).toDouble()
    }.toFloat())/2

    fun intersectionArea(subject:List<FloorPoint>,clip:List<FloorPoint>):Float {
        var output=subject
        for(i in clip.indices){
            val a=clip[i];val b=clip[(i+1)%clip.size]
            fun cross(p:FloorPoint)=(b.x-a.x)*(p.z-a.z)-(b.z-a.z)*(p.x-a.x)
            val input=output
            if(input.isEmpty())return 0f
            output=buildList{
                var previous=input.last();var lastCross=cross(previous)
                for(point in input){
                    val nextCross=cross(point)
                    if((nextCross>=0)!=(lastCross>=0)){
                        val t=lastCross/(lastCross-nextCross)
                        add(FloorPoint(previous.x+(point.x-previous.x)*t,previous.z+(point.z-previous.z)*t))
                    }
                    if(nextCross>=0)add(point)
                    previous=point;lastCross=nextCross
                }
            }
        }
        return area(output)
    }
    fun distance(a:List<FloorPoint>,b:List<FloorPoint>):Float {
        if(overlaps(a,b))return 0f
        fun toSegment(p:FloorPoint,x:FloorPoint,y:FloorPoint):Float {
            val dx=y.x-x.x;val dz=y.z-x.z
            val t=(((p.x-x.x)*dx+(p.z-x.z)*dz)/(dx*dx+dz*dz).coerceAtLeast(1e-8f)).coerceIn(0f,1f)
            return hypot(p.x-x.x-t*dx,p.z-x.z-t*dz)
        }
        return min(a.minOf{p->b.indices.minOf{i->toSegment(p,b[i],b[(i+1)%b.size])}},
            b.minOf{p->a.indices.minOf{i->toSegment(p,a[i],a[(i+1)%a.size])}})
    }
}

/** Offset is measured along the wall from its negative-coordinate end. Door zone is conservative, rectangular. */
data class RoomOpening(val id:String,val kind:String,val wall:String,val offsetCm:Float,val widthCm:Float,
    val heightCm:Float=210f,val sillCm:Float=0f,val clearanceDepthCm:Float=90f){
    init {
        require(kind in setOf("DOOR","WINDOW") && wall in setOf("NORTH","SOUTH","WEST","EAST"))
        require(listOf(offsetCm,widthCm,heightCm,sillCm,clearanceDepthCm).all{it.isFinite()})
        require(offsetCm>=0 && widthCm>0 && heightCm>0 && sillCm>=0 && clearanceDepthCm>0)
    }
    fun reservedZone(room:RoomSpec):FurnitureFootprint {
        val depth=if(kind=="WINDOW")min(clearanceDepthCm,30f)else clearanceDepthCm
        val horizontal=wall in setOf("NORTH","SOUTH")
        val length=if(horizontal)room.widthCm else room.depthCm
        require(offsetCm+widthCm<=length+0.001f){"Cửa nằm ngoài chiều dài tường"}
        val along=-length/2+offsetCm+widthCm/2
        val x=when(wall){"WEST"->-room.widthCm/2+depth/2;"EAST"->room.widthCm/2-depth/2;else->along}
        val z=when(wall){"NORTH"->-room.depthCm/2+depth/2;"SOUTH"->room.depthCm/2-depth/2;else->along}
        return FurnitureFootprint(Long.MIN_VALUE,"Vùng $kind",x,z,if(horizontal)widthCm else depth,if(horizontal)depth else widthCm)
    }
}

data class FreeRegion(val centerXcm:Float,val centerZcm:Float,val widthCm:Float,val depthCm:Float){
    init { require(listOf(centerXcm,centerZcm,widthCm,depthCm).all{it.isFinite()} && widthCm>0 && depthCm>0) }
    fun contains(item:FurnitureFootprint)=item.corners().all{abs(it.x-centerXcm)<=widthCm/2+0.001f && abs(it.z-centerZcm)<=depthCm/2+0.001f}
}

object LayoutPlanner {
    /** Bounded deterministic search: centre first, then an 11 x 11 grid at 0/90 degrees.
     * Null means no sampled solution, not a proof that no continuous placement exists.
     */
    fun fitsHere(room:RoomSpec,region:FreeRegion,item:FurnitureFootprint,others:List<FurnitureFootprint>):FurnitureFootprint? {
        if(item.heightCm>room.heightCm)return null
        val obstacles=others.filter{it.id!=item.id}.map{it.corners()}
        for(rotation in listOf(0f,90f)){
            val width=if(rotation==0f)item.widthCm else item.depthCm
            val depth=if(rotation==0f)item.depthCm else item.widthCm
            val minX=max(-room.widthCm/2,region.centerXcm-region.widthCm/2)+width/2
            val maxX=min(room.widthCm/2,region.centerXcm+region.widthCm/2)-width/2
            val minZ=max(-room.depthCm/2,region.centerZcm-region.depthCm/2)+depth/2
            val maxZ=min(room.depthCm/2,region.centerZcm+region.depthCm/2)-depth/2
            if(minX>maxX || minZ>maxZ)continue
            val centre=FloorPoint(region.centerXcm.coerceIn(minX,maxX),region.centerZcm.coerceIn(minZ,maxZ))
            val candidates=buildList{
                add(centre)
                for(x in 0..10)for(z in 0..10)add(FloorPoint(minX+(maxX-minX)*x/10,minZ+(maxZ-minZ)*z/10))
            }.distinct().sortedBy{hypot(it.x-centre.x,it.z-centre.z)}
            for(point in candidates){
                val proposed=item.copy(centerXcm=point.x,centerZcm=point.z,rotationDegrees=rotation)
                // Reject collisions using cached polygons before the more expensive clearance score.
                val polygon=proposed.corners()
                if(obstacles.any{FloorGeometry.overlaps(polygon,it)})continue
                if(region.contains(proposed) && valid(room,proposed,others))return proposed
            }
        }
        return null
    }
    fun valid(room:RoomSpec,item:FurnitureFootprint,others:List<FurnitureFootprint>):Boolean {
        val result=SpaceFitEngine.evaluate(room,item,others)
        return result.isInsideRoom && !result.hasCollision && result.doorBlockagePercent<=0.01f && !result.hasWindowObstruction
    }
    /** Deterministic candidates; returns only complete, validated layouts. Locked objects never move. */
    fun suggest(room:RoomSpec,items:List<FurnitureFootprint>,locked:Set<Long>,style:String="WALLS"):List<List<FurnitureFootprint>> {
        require(items.map{it.id}.distinct().size==items.size)
        if(items.isEmpty())return emptyList()
        val fixed=items.filter{it.id in locked}
        if(fixed.any{!valid(room,it,fixed)})return emptyList()
        val moving=items.filter{it.id !in locked}.sortedByDescending{it.widthCm*it.depthCm}
        val results=mutableListOf<List<FurnitureFootprint>>()
        for(variant in 0..2){
            val placed=fixed.toMutableList()
            for(item in moving){
                val candidates=mutableListOf<Pair<FurnitureFootprint,Float>>()
                for(rotation in listOf(0f,90f)){
                    val width=if(rotation==0f)item.widthCm else item.depthCm
                    val depth=if(rotation==0f)item.depthCm else item.widthCm
                    if(width>room.widthCm || depth>room.depthCm)continue
                    for(ix in 0..20)for(iz in 0..20){
                        val x=-(room.widthCm-width)/2+ix*(room.widthCm-width)/20
                        val z=-(room.depthCm-depth)/2+iz*(room.depthCm-depth)/20
                        val next=item.copy(centerXcm=x,centerZcm=z,rotationDegrees=rotation)
                        if(!valid(room,next,placed))continue
                        val evaluation=SpaceFitEngine.evaluate(room,next,placed)
                        if(evaluation.nearestFurnitureClearanceCm+0.01f<room.minimumClearanceCm)continue
                        val preference=if(style=="OPEN_CENTER")hypot(x,z)/10 else -evaluation.minimumWallClearanceCm/10
                        val direction=when(variant){0->-x-z;1->x-z;else->-x+z}/100
                        candidates.add(next to (evaluation.score+preference+direction))
                    }
                }
                val best=candidates.maxByOrNull{it.second}?.first ?: break
                placed.add(best)
            }
            if(placed.size==items.size && placed.all{valid(room,it,placed)} && results.none{it==placed})results.add(placed)
        }
        return results
    }
}
