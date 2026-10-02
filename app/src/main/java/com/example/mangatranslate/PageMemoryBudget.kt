package com.example.mangatranslate

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object PageMemoryBudget {
    val gate=Mutex()
    fun check(bitmap:Bitmap) {
        val pixels=bitmap.width.toLong()*bitmap.height
        val allowed=minOf(32L*1024*1024,Runtime.getRuntime().maxMemory()/8)
        require(pixels<=allowed/4) { "页面过大，请降低图片清晰度后翻译" }
    }
    fun fingerprint(bitmap:Bitmap):String {
        check(bitmap)
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        val rows=maxOf(1,minOf(32,16384/bitmap.width.coerceAtLeast(1)))
        val pixels=IntArray(bitmap.width*rows)
        val buffer=java.nio.ByteBuffer.allocate(pixels.size*4)
        var y=0
        while(y<bitmap.height) {
            val height=minOf(rows,bitmap.height-y)
            bitmap.getPixels(pixels,0,bitmap.width,0,y,bitmap.width,height)
            buffer.clear()
            for(i in 0 until bitmap.width*height) buffer.putInt(pixels[i])
            digest.update(buffer.array(),0,buffer.position()); y+=height
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
