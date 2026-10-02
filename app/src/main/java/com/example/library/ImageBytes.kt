package com.example.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * 部分图源（禁漫天堂等）脚本自带 Accept-Encoding: gzip，
 * OkHttp 不再透明解压，图片字节实际是 gzip 流，解码前必须先解压。
 */
object ImageBytes {

    private val normalizationLock=Any()
    private val expandedLimit: Long get() = minOf(32L*1024*1024,Runtime.getRuntime().maxMemory()/12)
    private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte())

    fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 ||
            bytes[0] != GZIP_MAGIC[0] ||
            bytes[1] != GZIP_MAGIC[1]
        ) {
            return bytes
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size().toLong() + read <= expandedLimit) { "图片解压体积过大" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } catch (e: Exception) {
            bytes
        }
    }

    /** 图片字节统一归一化：gzip 解压 + AVIF 转 PNG（BitmapFactory 在部分设备不支持 AVIF）。 */
    fun normalizeImage(bytes: ByteArray, contentEncoding: String? = null): ByteArray = synchronized(normalizationLock) {
        require(bytes.size<=32L*1024*1024) { "图片文件过大" }
        var result = if (contentEncoding?.contains("br", ignoreCase = true) == true) {
            brotliDecompress(bytes) ?: bytes
        } else {
            gunzipIfNeeded(bytes)
        }
        if (isAvif(result)) {
            result = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val source = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(result))
                    val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        require(info.size.width.toLong()*info.size.height <= minOf(32L*1024*1024,Runtime.getRuntime().maxMemory()/8)/4) { "AVIF 图片过大" }
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        ByteArrayOutputStream().use { out ->
                            check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
                            out.toByteArray()
                        }
                    } finally { bmp.recycle() }
                } else {
                    result
                }
            } catch (e: Exception) {
                Log.w("ImageBytes", "avif decode failed", e)
                result
            }
        }
        result
    }

    private fun brotliDecompress(bytes: ByteArray): ByteArray? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                org.brotli.dec.BrotliInputStream(ByteArrayInputStream(bytes)).use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size().toLong() + read <= expandedLimit) { "图片解压体积过大" }
                        output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isAvif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val ftyp = bytes[4].toInt() == 'f'.code &&
            bytes[5].toInt() == 't'.code &&
            bytes[6].toInt() == 'y'.code &&
            bytes[7].toInt() == 'p'.code
        if (!ftyp) return false
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return brand == "avif" || brand == "avis"
    }

    /** 尝试用 BitmapFactory 解码，判断图片字节是否可用。 */
    fun decodeOk(bytes: ByteArray): Boolean = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) { false }

    /**
     * hitomi 类图源会返回平台解不了的 AVIF；
     * 按 hitomi 域名规则生成 webp 变体候选（w 子域 + .webp 后缀）。
     */
    fun webpVariants(url: String): List<String> {
        val out = LinkedHashSet<String>()
        val hostPattern = Regex("""//(a|w)(\d*)\.(gold-usergeneratedcontent\.net|hitomi\.la)/""")
        val m = hostPattern.find(url) ?: return emptyList()
        val suffix = m.groupValues[1]
        val digits = m.groupValues[2]
        val domain = m.groupValues[3]
        val rest = url.substring(m.range.last + 1)
        val baseNoExt = rest.removeSuffix(".avif").removeSuffix(".webp")
        // 原域名换扩展
        out += "https://a$digits.$domain/$baseNoExt.webp"
        // w 子域 + 无扩展 / 带 .webp
        out += "https://w$digits.$domain/$baseNoExt"
        out += "https://w$digits.$domain/$baseNoExt.webp"
        // 无数字的 w 子域
        out += "https://w.$domain/$baseNoExt"
        out += "https://w.$domain/$baseNoExt.webp"
        return out.toList()
    }
}
