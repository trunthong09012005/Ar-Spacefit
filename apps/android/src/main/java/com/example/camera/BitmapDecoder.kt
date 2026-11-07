package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.max

private const val DEFAULT_MAX_SCAN_DIMENSION = 1600

fun decodeSampledBitmap(
    file: File,
    maxDimension: Int = DEFAULT_MAX_SCAN_DIMENSION
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return decoded.resizeAndOrient(maxDimension, orientation)
}

fun decodeSampledBitmap(
    context: Context,
    uri: Uri,
    maxDimension: Int = DEFAULT_MAX_SCAN_DIMENSION
): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val orientation = resolver.openInputStream(uri)?.use(::readExifOrientation)
        ?: ExifInterface.ORIENTATION_NORMAL
    return decoded.resizeAndOrient(maxDimension, orientation)
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (max(width, height) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun readExifOrientation(stream: InputStream): Int = runCatching {
    ExifInterface(stream).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun Bitmap.resizeAndOrient(maxDimension: Int, orientation: Int): Bitmap {
    val longestEdge = max(width, height)
    val resized = if (longestEdge > maxDimension) {
        val ratio = maxDimension.toFloat() / longestEdge
        scale(
            width = (width * ratio).toInt().coerceAtLeast(1),
            height = (height * ratio).toInt().coerceAtLeast(1)
        ).also { if (it !== this) recycle() }
    } else {
        this
    }

    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }
    if (matrix.isIdentity) return resized

    return Bitmap.createBitmap(
        resized,
        0,
        0,
        resized.width,
        resized.height,
        matrix,
        true
    ).also { transformed ->
        if (transformed !== resized) resized.recycle()
    }
}
