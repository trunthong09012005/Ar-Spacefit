package com.example.data

import com.example.spacefit.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FurniturePlacement(
    val id:Long=UUID.randomUUID().mostSignificantBits,
    val item:ItemEntity,
    val xCm:Float=0f,
    val zCm:Float=0f,
    val rotation:Float=0f,
    val locked:Boolean=false
) {
    fun footprint()=FurnitureFootprint(id,item.name,xCm,zCm,item.widthCm,item.depthCm,rotation,item.heightCm)
}

fun RoomEntity.spec():RoomSpec=RoomSpec(widthCm,depthCm,heightCm=heightCm,openings=parseOpenings(openingsJson))
fun parseOpenings(json:String):List<RoomOpening> {
    val array=JSONArray(json)
    return (0 until array.length()).map{i->val o=array.getJSONObject(i)
        RoomOpening(o.getString("id"),o.getString("kind"),o.getString("wall"),o.getDouble("offset").toFloat(),
            o.getDouble("width").toFloat(),o.getDouble("height").toFloat(),o.getDouble("sill").toFloat(),o.getDouble("clearance").toFloat())}
}
fun openingsJson(openings:List<RoomOpening>)=JSONArray(openings.map{o->JSONObject()
    .put("id",o.id).put("kind",o.kind).put("wall",o.wall).put("offset",o.offsetCm)
    .put("width",o.widthCm).put("height",o.heightCm).put("sill",o.sillCm).put("clearance",o.clearanceDepthCm)}).toString()

object DesignCodec {
    fun validatePlacements(items:List<FurniturePlacement>) {
        require(items.size<=100){"Mỗi project tối đa 100 đồ vật"}
        require(items.map{it.id}.distinct().size==items.size){"Mã đồ vật trong project bị trùng"}
        items.forEach{
            it.footprint()
            require(it.item.heightCm>0){"Chiều cao đồ vật phải lớn hơn 0"}
            require(it.item.modelPath.isBlank() || it.item.scaleConfirmed){"Cần xác nhận tỷ lệ GLB trước khi đặt vào phòng"}
        }
    }
    fun room(room:RoomEntity)=JSONObject().put("version",1).put("id",room.id).put("name",room.name)
        .put("width",room.widthCm).put("depth",room.depthCm).put("height",room.heightCm).put("openings",JSONArray(room.openingsJson))
        .put("source",room.measurementSource).toString()
    fun readRoom(json:String):RoomEntity {
        val o=JSONObject(json)
        require(o.getInt("version")==1)
        return RoomEntity(id=o.getString("id"),name=o.getString("name"),widthCm=o.getDouble("width").toFloat(),
            depthCm=o.getDouble("depth").toFloat(),heightCm=o.getDouble("height").toFloat(),openingsJson=o.getJSONArray("openings").toString(),
            measurementSource=o.optString("source","MANUAL")).also{val spec=it.spec();spec.openings.forEach{opening->opening.reservedZone(spec)}}
    }
    fun placements(items:List<FurniturePlacement>):String {
        validatePlacements(items)
        return JSONArray(items.map{p->
        val i=p.item
        JSONObject().put("id",p.id).put("x",p.xCm).put("z",p.zCm).put("rotation",p.rotation).put("locked",p.locked)
            .put("item",JSONObject().put("id",i.id).put("name",i.name).put("type",i.modelType3D).put("width",i.widthCm)
                .put("category",i.category).put("roomType",i.roomType).put("notes",i.notes).put("price",i.estimatedPrice)
                .put("favorite",i.isFavorite).put("timestamp",i.timestamp)
                .put("depth",i.depthCm).put("height",i.heightCm).put("color",i.colorHex).put("material",i.material)
                .put("modelPath",i.modelPath).put("modelSource",i.modelSource).put("polygonCount",i.polygonCount)
                .put("scanSessionId",i.scanSessionId).put("scaleConfirmed",i.scaleConfirmed).put("imageUri",i.imageUri)
                .put("parameters",i.modelParametersJson))
        }).toString()
    }
    fun readPlacements(json:String):List<FurniturePlacement> {
        val a=JSONArray(json)
        require(a.length()<=100){"Project vượt quá 100 đồ vật"}
        return (0 until a.length()).map{index->val p=a.getJSONObject(index);val i=p.getJSONObject("item")
            FurniturePlacement(p.getLong("id"),ItemEntity(id=i.getLong("id"),name=i.getString("name"),category=i.optString("category","FURNITURE"),roomType=i.optString("roomType","LIVING_ROOM"),
                notes=i.optString("notes"),estimatedPrice=i.optString("price"),isFavorite=i.optBoolean("favorite"),timestamp=i.optLong("timestamp"),
                widthCm=i.getDouble("width").toFloat(),depthCm=i.getDouble("depth").toFloat(),heightCm=i.getDouble("height").toFloat(),
                colorHex=i.getString("color"),material=i.getString("material"),modelType3D=i.getString("type"),modelPath=i.optString("modelPath"),
                modelSource=i.optString("modelSource","PARAMETRIC"),polygonCount=i.optInt("polygonCount"),scanSessionId=i.optString("scanSessionId"),
                scaleConfirmed=i.optBoolean("scaleConfirmed"),imageUri=i.optString("imageUri"),modelParametersJson=i.optString("parameters")),
                p.getDouble("x").toFloat(),p.getDouble("z").toFloat(),p.getDouble("rotation").toFloat(),p.getBoolean("locked"))
                .also{it.footprint()}
        }.also{validatePlacements(it)}
    }
}
