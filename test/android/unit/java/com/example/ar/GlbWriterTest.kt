package com.example.ar

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class GlbWriterTest{
    @Test fun glbUsesMetresValidChunksAndPhysicalBounds(){
        val mesh=PlacedFurniture(1,"Chair","CHAIR",0f,0f,0f,0f,68f,120f,72f,Color.White).getTransformedMesh()
        val buffer=GlbWriter.encode(mesh).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67,buffer.int);assertEquals(2,buffer.int);assertEquals(buffer.capacity(),buffer.int)
        val jsonLength=buffer.int;assertEquals(0x4e4f534a,buffer.int)
        val raw=ByteArray(jsonLength);buffer.get(raw)
        val json=JSONObject(String(raw,Charsets.UTF_8))
        assertEquals("OPAQUE",json.getJSONArray("materials").getJSONObject(0).getString("alphaMode"))
        val position=json.getJSONArray("accessors").getJSONObject(0)
        assertEquals(0.68,position.getJSONArray("max").getDouble(0)-position.getJSONArray("min").getDouble(0),0.0001)
        assertEquals(1.2,position.getJSONArray("max").getDouble(1),0.0001)
        val binaryLength=buffer.int;assertEquals(0x004e4942,buffer.int)
        assertEquals(binaryLength,buffer.remaining())
        assertEquals(position.getInt("count")*40,binaryLength)
    }

    @Test fun transparentRoomWallsDoNotTurnTheFloorIntoABlendedPrimitive(){
        val solid=PlacedFurniture(1,"Floor","BOX",0f,0f,0f,0f,400f,1f,500f,Color.Gray).getTransformedMesh()
        val mesh=Mesh3D(solid.faces.mapIndexed { index,face -> face.copy(baseColor=Color.Gray.copy(alpha=if(index==0)1f else 0.3f)) })
        val buffer=GlbWriter.encode(mesh,preserveTransparency=true).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(12)
        val length=buffer.int;buffer.int
        val raw=ByteArray(length);buffer.get(raw)
        val json=JSONObject(String(raw,Charsets.UTF_8))
        val materials=json.getJSONArray("materials")
        assertEquals(2,materials.length())
        assertEquals("OPAQUE",materials.getJSONObject(0).getString("alphaMode"))
        assertEquals("BLEND",materials.getJSONObject(1).getString("alphaMode"))
        val primitives=json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        val accessors=json.getJSONArray("accessors")
        assertEquals(2,primitives.length())
        assertEquals(6,accessors.length())
        assertEquals(accessors.getJSONObject(0).getInt("count")*40,accessors.getJSONObject(3).getInt("byteOffset"))
        val binaryLength=buffer.int;buffer.int
        assertEquals(binaryLength,(accessors.getJSONObject(0).getInt("count")+accessors.getJSONObject(3).getInt("count"))*40)
    }
}
