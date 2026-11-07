package com.example.ar

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Export the existing parametric meshes to the same metre-based GLB renderer as real scans. */
object GlbWriter {
    fun encode(mesh: Mesh3D, preserveTransparency: Boolean = false): ByteBuffer {
        // Legacy canvas meshes use alpha for fake face shading. Native furniture
        // is solid: real lighting supplies the shading. Keep explicit room-wall
        // transparency separate from opaque geometry so it cannot hide furniture.
        val faces = mesh.faces.map { if(preserveTransparency) it else it.copy(baseColor=it.baseColor.copy(alpha=1f)) }
        val groups = faces.groupBy { it.baseColor.alpha < 1f }.values.map { group -> group.flatMap { face ->
            (1 until face.vertices.lastIndex).flatMap { index ->
                listOf(face.vertices[0], face.vertices[index], face.vertices[index + 1]).map { it to face }
            }
        }}.filter { it.isNotEmpty() }
        val vertices = groups.flatten()
        require(vertices.isNotEmpty())
        val binary = ByteBuffer.allocate(vertices.size * 40).order(ByteOrder.LITTLE_ENDIAN)
        vertices.forEach { (v,face) ->
            binary.putFloat(v.x).putFloat(v.y).putFloat(v.z)
            binary.putFloat(face.normal.x).putFloat(face.normal.y).putFloat(face.normal.z)
            binary.putFloat(face.baseColor.red).putFloat(face.baseColor.green).putFloat(face.baseColor.blue).putFloat(face.baseColor.alpha)
        }
        val accessors=JSONArray()
        val materials=JSONArray()
        val primitives=JSONArray()
        var start=0
        groups.forEachIndexed { index, group ->
            fun accessor(offset:Int,type:String) = JSONObject().put("bufferView",0).put("byteOffset",start*40+offset)
                .put("componentType",5126).put("count",group.size).put("type",type)
            val min=listOf(group.minOf{it.first.x},group.minOf{it.first.y},group.minOf{it.first.z})
            val max=listOf(group.maxOf{it.first.x},group.maxOf{it.first.y},group.maxOf{it.first.z})
            accessors.put(accessor(0,"VEC3").put("min",JSONArray(min)).put("max",JSONArray(max)))
                .put(accessor(12,"VEC3")).put(accessor(24,"VEC4"))
            materials.put(JSONObject().put("doubleSided",true).put("alphaMode",if(group.first().second.baseColor.alpha<1f)"BLEND" else "OPAQUE")
                .put("pbrMetallicRoughness",JSONObject().put("metallicFactor",0).put("roughnessFactor",0.85)))
            primitives.put(JSONObject().put("attributes",JSONObject().put("POSITION",index*3).put("NORMAL",index*3+1).put("COLOR_0",index*3+2))
                .put("mode",4).put("material",index))
            start+=group.size
        }
        val json = JSONObject().put("asset",JSONObject().put("version","2.0").put("generator","AR-SpaceFit parametric mesh"))
            .put("scene",0).put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray().put(0))))
            .put("nodes",JSONArray().put(JSONObject().put("mesh",0)))
            .put("buffers",JSONArray().put(JSONObject().put("byteLength",binary.capacity())))
            .put("bufferViews",JSONArray().put(JSONObject().put("buffer",0).put("byteLength",binary.capacity()).put("byteStride",40).put("target",34962)))
            .put("accessors",accessors).put("materials",materials)
            .put("meshes",JSONArray().put(JSONObject().put("primitives",primitives)))
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        val jsonLength=(raw.size+3)/4*4
        val length=12+8+jsonLength+8+binary.capacity()
        return ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67);putInt(2);putInt(length)
            putInt(jsonLength);putInt(0x4e4f534a);put(raw)
            repeat(jsonLength-raw.size){put(0x20.toByte())}
            putInt(binary.capacity());putInt(0x004e4942);put(binary.array());flip()
        }
    }
}
