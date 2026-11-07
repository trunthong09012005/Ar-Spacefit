package com.example.ui

import android.graphics.Bitmap
import java.io.File
import java.io.OutputStream

/** Capture before SAF pauses/destroys the renderer; publish the exact same PNG afterwards. */
internal class DesignExportStore(private val cacheDir:File,private val imagesDir:File){
    fun capture(bitmap:Bitmap):File{
        check(cacheDir.isDirectory || cacheDir.mkdirs()){"Không tạo được thư mục ảnh tạm"}
        val snapshot=File.createTempFile("spacefit-export-",".png",cacheDir)
        try{
            snapshot.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)){"Không chụp được ảnh PNG"}}
            return snapshot
        }catch(error:Exception){snapshot.delete();throw error}
    }

    private fun validate(snapshot:File){
        require(snapshot.canonicalFile.parentFile==cacheDir.canonicalFile &&
            snapshot.name.startsWith("spacefit-export-") && snapshot.name.endsWith(".png")){"Ảnh tạm không hợp lệ"}
    }

    fun publish(snapshot:File,destination:OutputStream):File{
        validate(snapshot)
        require(snapshot.isFile && snapshot.length()>0){"Ảnh tạm không còn. Hãy xuất lại."}
        snapshot.inputStream().use{it.copyTo(destination)}
        destination.flush()
        check(imagesDir.isDirectory || imagesDir.mkdirs()){"Không tạo được thư mục After"}
        val local=File.createTempFile("after-",".png",imagesDir)
        try{snapshot.copyTo(local,overwrite=true);return local}
        catch(error:Exception){local.delete();throw error}
    }

    fun discard(snapshot:File){validate(snapshot);snapshot.delete()}
}
