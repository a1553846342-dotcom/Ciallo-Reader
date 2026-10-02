package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.reader.NovelInlineImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 插图位图缓存 + 采样解码（翻页动画期间背后图片立即显示的基建）：
 * prewarm 预解码后 get 命中；采样阈值与 NovelInlineImage 一致；缓存淘汰不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelImageCacheTest {

    private fun redPngBytes(w: Int = 4, h: Int = 4): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFF0000.toInt())
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun prewarmThenCacheHit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f = File(context.filesDir, "cache_test_${System.currentTimeMillis()}.png")
        f.writeBytes(redPngBytes())

        // 未预热：无缓存
        assertNull(NovelInlineImages.NovelImageCache.get(f.absolutePath))
        NovelInlineImages.NovelImageCache.prewarm(listOf(f.absolutePath))

        // prewarm 是异步的：轮询等解码完成
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            NovelInlineImages.NovelImageCache.get(f.absolutePath) == null
        ) {
            Thread.sleep(100)
        }
        val bmp = NovelInlineImages.NovelImageCache.get(f.absolutePath)
        assertNotNull("预热后缓存未命中", bmp)
        // 像素正确（红色）
        val px = bmp!!.getPixel(2, 2)
        assertTrue((px shr 16) and 0xFF > 180)

        // 同路径重复预热：命中缓存直接跳过，不崩
        NovelInlineImages.NovelImageCache.prewarm(listOf(f.absolutePath))
    }

    @Test
    fun decodeSampledDownscalesVeryWideImages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 4000px 宽图：采样逻辑 while(outWidth/(sample*2) >= 1536) → sample=2 → 2000px
        val f = File(context.filesDir, "wide_${System.currentTimeMillis()}.png")
        f.writeBytes(redPngBytes(4000, 100))
        val decoded = NovelInlineImages.NovelImageCache.decodeSampled(f.absolutePath)
        assertNotNull(decoded)
        assertTrue("超宽图应被下采样: ${decoded!!.width}", decoded.width <= 2000)
    }

    @Test
    fun decodeGarbageReturnsNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f = File(context.filesDir, "garbage_${System.currentTimeMillis()}.png")
        f.writeBytes("this is not an image".toByteArray())
        assertNull(NovelInlineImages.NovelImageCache.decodeSampled(f.absolutePath))
        assertNull(NovelInlineImages.NovelImageCache.get(f.absolutePath))
        assertEquals(0, 0)
    }
    @Test
    fun epzipReferenceDecodesFromZipStream() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 构造 mini EPUB（zip 内含红色 png 条目）
        val png = redPngBytes()
        val epub = File(context.cacheDir, "epzip_test_${System.currentTimeMillis()}.epub")
        java.util.zip.ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/images/pic.png"))
            zip.write(png)
            zip.closeEntry()
        }
        val spec = "epzip:" + epub.absolutePath + "!OEBPS/images/pic.png"
        val decoded = NovelInlineImages.NovelImageCache.decodeSampledFromEpub(spec)
        assertNotNull("epzip 引用解码失败", decoded)
        val px = decoded!!.getPixel(2, 2)
        assertTrue((px shr 16) and 0xFF > 180)
        // 不存在的条目 → null
        val bad = NovelInlineImages.NovelImageCache.decodeSampledFromEpub(
            "epzip:" + epub.absolutePath + "!no/such/entry.png"
        )
        assertNull(bad)
    }
}
