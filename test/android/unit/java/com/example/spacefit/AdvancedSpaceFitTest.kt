package com.example.spacefit

import org.junit.Assert.*
import org.junit.Test

class AdvancedSpaceFitTest {
    private fun furniture(id:Long=1,x:Float=0f,z:Float=0f,w:Float=60f,d:Float=60f,r:Float=0f,h:Float=80f)=
        FurnitureFootprint(id,"Ghế",x,z,w,d,r,h)

    @Test fun rotatedThinObjectsDoNotCollideJustBecauseTheirAabbsOverlap(){
        val a=furniture(w=200f,d=10f,r=45f)
        val b=furniture(2,x=-20f,z=20f,w=200f,d=10f,r=45f)
        assertFalse(SpaceFitEngine.evaluate(RoomSpec(500f,500f),a,listOf(b)).hasCollision)
        assertEquals(18.28427f,FloorGeometry.distance(a.corners(),b.corners()),0.01f)
    }
    @Test fun doorBlockageUsesIntersectionAreaAndMakesPlacementUnsuitable(){
        val room=RoomSpec(400f,400f,openings=listOf(RoomOpening("door","DOOR","NORTH",150f,100f,clearanceDepthCm=100f)))
        val result=SpaceFitEngine.evaluate(room,furniture(z=-175f,w=100f,d=50f),emptyList())
        assertEquals(50f,result.doorBlockagePercent,0.01f)
        assertFalse(result.isSuitable)
        assertTrue(result.messages.any{it.contains("50")})
    }
    @Test fun lowFurnitureDoesNotBlockWindowButTallFurnitureDoes(){
        val room=RoomSpec(400f,400f,openings=listOf(RoomOpening("window","WINDOW","NORTH",150f,100f,heightCm=100f,sillCm=100f)))
        assertFalse(SpaceFitEngine.evaluate(room,furniture(z=-175f,h=90f),emptyList()).hasWindowObstruction)
        assertTrue(SpaceFitEngine.evaluate(room,furniture(z=-175f,h=120f),emptyList()).hasWindowObstruction)
    }
    @Test fun ceilingAndNeighbourClearanceAreChecked(){
        assertFalse(SpaceFitEngine.evaluate(RoomSpec(400f,400f,heightCm=240f),furniture(h=250f),emptyList()).isInsideRoom)
        val result=SpaceFitEngine.evaluate(RoomSpec(400f,400f),furniture(),listOf(furniture(2,x=80f)))
        assertFalse(result.hasCollision)
        assertEquals(20f,result.nearestFurnitureClearanceCm,0.01f)
        assertTrue(result.score<100)
    }
    @Test fun fitsHereCanRotateAndRejectsOccupiedRegion(){
        val room=RoomSpec(400f,400f)
        val region=FreeRegion(0f,0f,70f,130f)
        val item=furniture(w=120f,d=60f)
        assertEquals(90f,LayoutPlanner.fitsHere(room,region,item,emptyList())!!.rotationDegrees,0f)
        assertNull(LayoutPlanner.fitsHere(room,region,item,listOf(furniture(2))))
    }
    @Test fun suggestionsPreserveLockedObjectsAndReturnCompleteValidDesigns(){
        val room=RoomSpec(500f,500f)
        val locked=furniture(1,x=-140f,z=-140f)
        val items=listOf(locked,furniture(2,w=120f,d=70f),furniture(3))
        val result=LayoutPlanner.suggest(room,items,setOf(1))
        assertTrue(result.isNotEmpty())
        result.forEach{layout->
            assertEquals(3,layout.size)
            assertEquals(locked,layout.first{it.id==1L})
            assertTrue(layout.all{LayoutPlanner.valid(room,it,layout)})
            assertTrue(layout.all{SpaceFitEngine.evaluate(room,it,layout).nearestFurnitureClearanceCm>=59.99f})
        }
    }
    @Test fun impossibleLayoutDoesNotPretendToSucceedWithMissingFurniture(){
        assertTrue(LayoutPlanner.suggest(RoomSpec(100f,100f),listOf(furniture(w=200f)),emptySet()).isEmpty())
    }
    @Test fun clippingAreaRemainsPhysicalUnderRotation(){
        val a=furniture(w=100f,d=100f,r=45f)
        val containing=furniture(2,w=500f,d=500f)
        assertEquals(10000f,FloorGeometry.intersectionArea(a.corners(),containing.corners()),0.1f)
    }
}
