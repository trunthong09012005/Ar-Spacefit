package com.example.reconstruction

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Bounded GLB 2 container validation, not a replacement for the renderer's mesh validation. */
internal fun validateDownloadedGlb(file:File) {
    fun requireGlb(condition:Boolean) { if(!condition)throw IOException("File GLB bị hỏng, thiếu dữ liệu hoặc có phiên bản không hỗ trợ") }
    RandomAccessFile(file,"r").use { input ->
        fun uint32()=Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
        requireGlb(input.length()>=20)
        requireGlb(uint32()==0x46546C67L)
        requireGlb(uint32()==2L)
        requireGlb(uint32()==input.length())
        var index=0
        var hasBinary=false
        while(input.filePointer<input.length()) {
            requireGlb(input.length()-input.filePointer>=8)
            val length=uint32();val type=uint32()
            requireGlb(length%4==0L && length<=input.length()-input.filePointer)
            if(index==0) {
                requireGlb(type==0x4E4F534AL && length in 4..8L*1024*1024)
                val json=ByteArray(length.toInt());input.readFully(json)
                val document=try{JSONObject(json.toString(Charsets.UTF_8))}catch(error:Exception){throw IOException("JSON trong GLB không hợp lệ",error)}
                requireGlb(document.optJSONObject("asset")?.optString("version")=="2.0")
                // The app downloads one file for offline use, not arbitrary external URLs.
                for(key in listOf("buffers","images")) {
                    val entries=document.optJSONArray(key) ?: continue
                    for(i in 0 until entries.length()) {
                        val uri=entries.getJSONObject(i).optString("uri","")
                        if(uri.isNotBlank() && !uri.startsWith("data:"))throw IOException("GLB cần nhúng texture/buffer để xem ngoại tuyến")
                    }
                }
            } else {
                requireGlb(type!=0x4E4F534AL)
                if(type==0x004E4942L){requireGlb(index==1 && !hasBinary);hasBinary=true}
                // Unknown extension chunks may follow the standard chunks.
                input.seek(input.filePointer+length)
            }
            index++
        }
    }
}
