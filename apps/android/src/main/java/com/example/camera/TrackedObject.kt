package com.example.camera

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Object bounds normalized to the upright CameraX analysis frame. */
data class TrackedObject(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val trackingId: Int?
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val areaFraction: Float get() = width * height
}

fun Bitmap.cropToTrackedObject(
    trackedObject: TrackedObject,
    paddingFraction: Float = 0.1f
): Bitmap {
    if (trackedObject.width <= 0f || trackedObject.height <= 0f) return this

    val horizontalPadding = trackedObject.width * paddingFraction
    val verticalPadding = trackedObject.height * paddingFraction
    val normalizedLeft = max(0f, trackedObject.left - horizontalPadding)
    val normalizedTop = max(0f, trackedObject.top - verticalPadding)
    val normalizedRight = min(1f, trackedObject.right + horizontalPadding)
    val normalizedBottom = min(1f, trackedObject.bottom + verticalPadding)

    val cropLeft = (normalizedLeft * width).toInt().coerceIn(0, width - 1)
    val cropTop = (normalizedTop * height).toInt().coerceIn(0, height - 1)
    val cropRight = (normalizedRight * width).toInt().coerceIn(cropLeft + 1, width)
    val cropBottom = (normalizedBottom * height).toInt().coerceIn(cropTop + 1, height)

    if (cropLeft == 0 && cropTop == 0 && cropRight == width && cropBottom == height) {
        return this
    }
    return Bitmap.createBitmap(
        this,
        cropLeft,
        cropTop,
        cropRight - cropLeft,
        cropBottom - cropTop
    )
}
