package com.example

/** Debug-only QA classifier: blue/grey room walls must never count as cyan furniture. */
internal fun isCyanFixturePixel(pixel:Int):Boolean{
    val red=(pixel ushr 16) and 255
    val green=(pixel ushr 8) and 255
    val blue=pixel and 255
    return green>100 && blue>100 && green-red>80 && blue-red>80
}
