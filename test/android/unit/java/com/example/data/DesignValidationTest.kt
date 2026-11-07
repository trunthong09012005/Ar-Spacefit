package com.example.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DesignValidationTest {
    private val item=ItemEntity(name="fixture",category="FURNITURE",roomType="OFFICE",widthCm=50f,heightCm=90f,depthCm=60f,colorHex="#FFFFFF",material="test")
    @Test fun duplicateIdsAndOversizedProjectsAreRejected(){
        val p=FurniturePlacement(id=1,item=item)
        assertTrue(runCatching{DesignCodec.readPlacements(DesignCodec.placements(listOf(p,p)))}.isFailure)
        val hundred=(1L..100L).map{p.copy(id=it)}
        assertEquals(hundred,DesignCodec.readPlacements(DesignCodec.placements(hundred)))
        assertTrue(runCatching{DesignCodec.readPlacements(DesignCodec.placements(hundred+p.copy(id=101)))}.isFailure)
    }
    @Test fun nonFiniteOrNegativePlacementValuesCannotEnterGeometry(){
        for((key,value) in listOf("x" to "NaN","rotation" to "Infinity")){
            val array=JSONArray(DesignCodec.placements(listOf(FurniturePlacement(item=item))))
            array.getJSONObject(0).put(key,value)
            assertTrue(runCatching{DesignCodec.readPlacements(array.toString())}.isFailure)
        }
        val array=JSONArray(DesignCodec.placements(listOf(FurniturePlacement(item=item))))
        array.getJSONObject(0).getJSONObject("item").put("width",-1)
        assertTrue(runCatching{DesignCodec.readPlacements(array.toString())}.isFailure)
    }
    @Test fun unsupportedRoomVersionOrInvalidDimensionsFailClearly(){
        val room=JSONObject(DesignCodec.room(RoomEntity()))
        assertTrue(runCatching{DesignCodec.readRoom(room.put("version",999).toString())}.isFailure)
        assertTrue(runCatching{DesignCodec.readRoom(room.put("version",1).put("width",0).toString())}.isFailure)
    }
    @Test fun projectSnapshotDoesNotChangeWhenInventoryItemIsEdited(){
        val p=FurniturePlacement(item=item)
        val saved=DesignCodec.placements(listOf(p))
        val edited=item.copy(name="new",widthCm=100f)
        assertNotEquals(edited,DesignCodec.readPlacements(saved).single().item)
        assertEquals(item,DesignCodec.readPlacements(saved).single().item)
    }
    @Test fun liveDraftCannotExceedReloadLimitOrPlaceUnscaledGlb(){
        val hundred=(1L..100L).map{FurniturePlacement(id=it,item=item)}
        val draft=DesignDraft("qa","QA",RoomEntity(),hundred)
        assertTrue(runCatching{draft.copy(placements=hundred+FurniturePlacement(id=101,item=item))}.isFailure)
        assertTrue(runCatching{draft.copy(placements=listOf(FurniturePlacement(item=item.copy(modelPath="preview.glb",scaleConfirmed=false))))}.isFailure)
        assertEquals(100,draft.placements.size)
    }
}
