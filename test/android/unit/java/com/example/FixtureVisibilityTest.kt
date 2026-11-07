package com.example

import org.junit.Assert.*
import org.junit.Test

class FixtureVisibilityTest{
    @Test fun blueWallsGreyAndBlackDoNotPretendToBeFurniture(){
        // Observed wall colours in the initially blank room screenshot.
        listOf(0xFFA4BFE3.toInt(),0xFF8CB4EE.toInt(),0xFF3C465B.toInt(),
            0xFF808080.toInt(),0xFF000000.toInt(),0xFF0000FF.toInt()).forEach{
            assertFalse("Background pixel ${it.toUInt().toString(16)}",isCyanFixturePixel(it))
        }
    }
    @Test fun authoredCyanFurnitureAndFloorplanFillAreRecognized(){
        listOf(0xFF00FFFF.toInt(),0xFF00B1B1.toInt(),0xFF209BB2.toInt(),0xFF37C8D2.toInt()).forEach{
            assertTrue("Fixture pixel ${it.toUInt().toString(16)}",isCyanFixturePixel(it))
        }
    }
}
