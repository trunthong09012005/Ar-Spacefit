package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignExportStoreTest{
    @get:Rule val temporary=TemporaryFolder()
    @Test fun snapshotAndPublishedAfterContainExactlyTheCapturedPng(){
        val cache=temporary.newFolder("cache");val images=temporary.newFolder("images")
        val store=DesignExportStore(cache,images)
        val bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.CYAN)}
        val snapshot=store.capture(bitmap)
        assertFalse(bitmap.isRecycled)
        val png=snapshot.readBytes()
        val decoded=BitmapFactory.decodeByteArray(png,0,png.size)
        assertEquals(Color.CYAN,decoded.getPixel(12,12));decoded.recycle();bitmap.recycle()
        val output=ByteArrayOutputStream();val local=store.publish(snapshot,output)
        assertArrayEquals(png,output.toByteArray());assertArrayEquals(png,local.readBytes())
        store.discard(snapshot);assertFalse(snapshot.exists());assertTrue(local.exists())
    }
    @Test fun failedDestinationDoesNotPublishAfterOrDeleteTheSnapshot(){
        val cache=temporary.newFolder("cache");val images=temporary.newFolder("images")
        val store=DesignExportStore(cache,images)
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        val snapshot=store.capture(bitmap);bitmap.recycle()
        assertThrows(IOException::class.java){store.publish(snapshot,object:OutputStream(){override fun write(b:Int){throw IOException("fixture disk full")}})}
        assertTrue(snapshot.exists());assertTrue(images.listFiles()!!.isEmpty())
        store.discard(snapshot);assertFalse(snapshot.exists())
    }
    @Test fun outsideCacheIsNeverReadPublishedOrDeleted(){
        val cache=temporary.newFolder("cache");val images=temporary.newFolder("images")
        val store=DesignExportStore(cache,images)
        val outside=temporary.newFile("spacefit-export-private.png").apply{writeText("private fixture")}
        val output=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java){store.publish(outside,output)}
        assertThrows(IllegalArgumentException::class.java){store.discard(outside)}
        assertTrue(outside.exists());assertEquals(0,output.size());assertTrue(images.listFiles()!!.isEmpty())
    }
    @Test fun captureFailureAndCancelledOrMissingSnapshotLeaveNoCacheFile(){
        val cache=temporary.newFolder("cache");val store=DesignExportStore(cache,temporary.newFolder("images"))
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply{recycle()}
        assertThrows(IllegalStateException::class.java){store.capture(bitmap)}
        assertTrue(cache.listFiles()!!.isEmpty())
        val missing=java.io.File(cache,"spacefit-export-missing.png")
        assertThrows(IllegalArgumentException::class.java){store.publish(missing,ByteArrayOutputStream())}
        store.discard(missing);assertFalse(missing.exists())
    }
}
