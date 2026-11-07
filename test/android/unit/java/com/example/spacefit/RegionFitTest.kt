package com.example.spacefit

import org.junit.Assert.*
import org.junit.Test

class RegionFitTest {
    private val room=RoomSpec(400f,400f)
    private val item=FurnitureFootprint(1,"Fixture",0f,0f,50f,50f,heightCm=80f)

    @Test fun findsOffCentreSpaceWhenTheMiddleIsOccupied(){
        val area=FreeRegion(0f,0f,240f,100f)
        val obstacle=item.copy(id=2,widthCm=80f,depthCm=100f)
        val fit=LayoutPlanner.fitsHere(room,area,item,listOf(obstacle))
        assertNotNull("A chair fits on either side of the central obstacle",fit)
        assertTrue(area.contains(fit!!))
        assertTrue(LayoutPlanner.valid(room,fit,listOf(obstacle)))
        assertEquals(fit,LayoutPlanner.fitsHere(room,area,item,listOf(obstacle)))
    }

    @Test fun intersectsTheSelectedRegionWithTheRoomBeforeSearching(){
        val area=FreeRegion(230f,0f,160f,100f)
        val fit=LayoutPlanner.fitsHere(room,area,item,emptyList())
        assertNotNull("The 50 cm strip inside the room still fits",fit)
        assertEquals(175f,fit!!.centerXcm,0.001f)
        assertTrue(area.contains(fit));assertTrue(LayoutPlanner.valid(room,fit,emptyList()))
    }

    @Test fun preservesCentrePreferenceAndAllowsExactBoundaryContact(){
        assertEquals(item,LayoutPlanner.fitsHere(room,FreeRegion(0f,0f,50f,50f),item,emptyList()))
        assertNull(LayoutPlanner.fitsHere(room,FreeRegion(240f,0f,50f,50f),item,emptyList()))
        assertNull(LayoutPlanner.fitsHere(room,FreeRegion(0f,0f,49f,49f),item,emptyList()))
        assertNull(LayoutPlanner.fitsHere(room,FreeRegion(0f,0f,100f,100f),item.copy(heightCm=281f),emptyList()))
    }

    @Test fun searchesAwayFromReservedDoorAndTallWindowZones(){
        for(kind in listOf("DOOR","WINDOW")){
            val withOpening=room.copy(openings=listOf(RoomOpening("opening",kind,"NORTH",160f,80f,
                sillCm=50f,clearanceDepthCm=100f)))
            val area=FreeRegion(0f,-175f,240f,50f)
            val fit=LayoutPlanner.fitsHere(withOpening,area,item,emptyList())
            assertNotNull("Must find a side position outside $kind",fit)
            assertTrue(area.contains(fit!!));assertTrue(LayoutPlanner.valid(withOpening,fit,emptyList()))
            assertNull(LayoutPlanner.fitsHere(withOpening,FreeRegion(0f,-175f,50f,50f),item,emptyList()))
        }
    }

    @Test fun rotationAndRotatedObstaclesAreCheckedUsingPhysicalFootprints(){
        val area=FreeRegion(0f,0f,80f,300f)
        val wide=item.copy(widthCm=100f,depthCm=50f)
        val obstacle=item.copy(id=2,widthCm=100f,depthCm=30f,rotationDegrees=30f)
        val witness=wide.copy(centerZcm=100f,rotationDegrees=90f)
        assertTrue(area.contains(witness));assertTrue(LayoutPlanner.valid(room,witness,listOf(obstacle)))
        val fit=LayoutPlanner.fitsHere(room,area,wide,listOf(obstacle))
        assertNotNull(fit)
        assertEquals(90f,fit!!.rotationDegrees,0f)
        assertTrue(area.contains(fit));assertTrue(LayoutPlanner.valid(room,fit,listOf(obstacle)))
    }

    @Test fun rejectsInvalidRegions(){
        for(values in listOf(listOf(Float.NaN,0f,100f,100f),listOf(0f,Float.POSITIVE_INFINITY,100f,100f),
            listOf(0f,0f,0f,100f),listOf(0f,0f,100f,-1f))){
            assertThrows(IllegalArgumentException::class.java){FreeRegion(values[0],values[1],values[2],values[3])}
        }
    }
}
