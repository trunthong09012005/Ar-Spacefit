package com.example.camera

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackedObjectTest {

    @Test
    fun `crop follows normalized tracked bounds`() {
        val source = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        val tracked = TrackedObject(
            left = 0.25f,
            top = 0.25f,
            right = 0.75f,
            bottom = 0.75f,
            sourceWidth = 100,
            sourceHeight = 80,
            trackingId = 7
        )

        val cropped = source.cropToTrackedObject(tracked, paddingFraction = 0f)

        assertEquals(50, cropped.width)
        assertEquals(40, cropped.height)
    }

    @Test
    fun `full frame detection does not allocate another bitmap`() {
        val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val tracked = TrackedObject(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
            sourceWidth = 32,
            sourceHeight = 24,
            trackingId = null
        )

        assertSame(source, source.cropToTrackedObject(tracked, paddingFraction = 0f))
    }
}
