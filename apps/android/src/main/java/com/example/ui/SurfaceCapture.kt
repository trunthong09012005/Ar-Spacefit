package com.example.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A Window copy does not contain Filament's separate SurfaceView buffer. */
internal suspend fun captureSurface(source:SurfaceView):Bitmap=withContext(Dispatchers.Main.immediate){
    require(source.isAttachedToWindow && source.holder.surface.isValid && source.width>0 && source.height>0){
        "Cảnh 3D chưa sẵn sàng để xuất. Hãy đợi mô hình hiển thị rồi thử lại."
    }
    suspendCancellableCoroutine { continuation ->
        val bitmap=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(source,bitmap,{result->
                if(!continuation.isActive)bitmap.recycle()
                else if(result==PixelCopy.SUCCESS)continuation.resume(bitmap)
                else {
                    bitmap.recycle()
                    continuation.resumeWithException(IllegalStateException("Chưa lấy được khung hình 3D ($result). Thử lại sau."))
                }
            },Handler(Looper.getMainLooper()))
        }catch(failure:Exception){
            bitmap.recycle()
            continuation.resumeWithException(failure)
        }
    }
}
