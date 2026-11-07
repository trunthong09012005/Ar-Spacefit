package com.example.spacefit

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GeometryPropertiesTest {
    @Test fun overlapDistanceAndClippingAreSymmetricForSeededRotatedRectangles(){
        val random=Random(421)
        repeat(150){index->
            fun rectangle(id:Long)=FurnitureFootprint(id,"fixture",random.nextFloat()*300-150,random.nextFloat()*300-150,
                10+random.nextFloat()*150,10+random.nextFloat()*150,random.nextFloat()*360,80f)
            val a=rectangle(index*2L).corners();val b=rectangle(index*2L+1).corners()
            assertEquals(FloorGeometry.overlaps(a,b),FloorGeometry.overlaps(b,a))
            assertEquals(FloorGeometry.distance(a,b),FloorGeometry.distance(b,a),.01f)
            assertEquals(FloorGeometry.intersectionArea(a,b),FloorGeometry.intersectionArea(b,a),.1f)
            assertTrue(FloorGeometry.intersectionArea(a,b)<=minOf(FloorGeometry.area(a),FloorGeometry.area(b))+.1f)
        }
    }
    @Test fun touchingIsNotOverlapAndDistanceIsZero(){
        val a=FurnitureFootprint(1,"a",0f,0f,50f,50f)
        val b=a.copy(id=2,centerXcm=50f)
        assertFalse(FloorGeometry.overlaps(a.corners(),b.corners()))
        assertEquals(0f,FloorGeometry.distance(a.corners(),b.corners()),.001f)
    }
    @Test fun fullTurnDoesNotChangeSpaceFitDecision(){
        val room=RoomSpec(400f,500f)
        val item=FurnitureFootprint(1,"fixture",60f,90f,70f,110f,37f,90f)
        val first=SpaceFitEngine.evaluate(room,item,emptyList())
        val second=SpaceFitEngine.evaluate(room,item.copy(rotationDegrees=397f),emptyList())
        assertEquals(first.score,second.score)
        assertEquals(first.minimumWallClearanceCm,second.minimumWallClearanceCm,.01f)
    }
}
