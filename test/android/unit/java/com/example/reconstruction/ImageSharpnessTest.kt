package com.example.reconstruction

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ImageSharpnessTest {
    @Test fun flatImageIsBlurredAndOriginalRemainsUsable(){
        val bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(Color.GRAY);assertEquals(0.0,imageSharpness(bitmap),0.0001);assertFalse(bitmap.isRecycled)}
        finally{bitmap.recycle()}
    }
    @Test fun highFrequencyTexturePassesTheSharpnessThreshold(){
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until 64)for(x in 0 until 64)bitmap.setPixel(x,y,if((x+y)%2==0)Color.BLACK else Color.WHITE)
            assertTrue(imageSharpness(bitmap)>35)
        }finally{bitmap.recycle()}
    }
    @Test fun extremelyThinImageDoesNotCrashDuringDownsampling(){
        val bitmap=Bitmap.createBitmap(1,1000,Bitmap.Config.ARGB_8888)
        try{assertEquals(0.0,imageSharpness(bitmap),0.0);assertFalse(bitmap.isRecycled)}finally{bitmap.recycle()}
    }
}
