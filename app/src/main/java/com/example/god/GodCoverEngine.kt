package com.example.god

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 神回封面合成引擎（纯 Kotlin / android.graphics，全部在 [Dispatchers.Default] 跑）。
 *
 * 合成规则（见需求第二节）：
 *  1. **背景层**：裁剪后的图 centerCrop 铺满画布 → 降采样到约 1/8 → 高斯近似模糊
 *     （3 次盒式模糊叠加 = 中心极限逼近高斯；纯 Kotlin，不依赖 RenderScript/
 *     Modifier.blur，全系统版本一致）→ 放大回画布 → 叠 ~15% 暗色蒙版。
 *     降采样本身带一次双线性平均，天然抑制低分辨率图放大后的色块。
 *  2. **前景层**：裁剪后的原图 contain 居中（四周约 8% 边距）+ 圆角（短边 3%）
 *     + Canvas shadowLayer 柔和投影。
 *
 * 结果：**无论用户裁出什么比例，成品尺寸恒定 900×1200，没有黑边白边。**
 */
object GodCoverEngine {

    /* ── 常量（集中在顶部，方便调参） ── */
    const val COVER_W = 900
    const val COVER_H = 1200
    /** 画布比例（3:4）；以后要改尺寸只改这一处 + 上面的宽高 */
    const val COVER_RATIO = 3f / 4f

    /** 预览尺寸：拖动裁剪时保证流畅（合成同样走后台线程） */
    const val PREVIEW_W = 450
    const val PREVIEW_H = 600

    const val JPEG_QUALITY = 90
    const val CACHE_DIR = "god_covers"

    /** 前景四周留白（占画布短边比例） */
    const val FOREGROUND_MARGIN = 0.08f
    /** 前景圆角（占画布短边比例） */
    const val CORNER_RATIO = 0.03f
    /** 背景模糊半径（占画布短边比例，需求给的范围 6%~8%） */
    const val BLUR_RADIUS_RATIO = 0.07f
    /** 背景模糊前的降采样倍率 */
    const val BLUR_DOWNSCALE = 8
    /** 背景暗色蒙版强度 */
    const val SCRIM_ALPHA = 0.15f
    /** 投影：模糊半径 / y 偏移 / 颜色 */
    const val SHADOW_BLUR = 22f
    const val SHADOW_DY = 10f
    const val SHADOW_ALPHA = 0.34f

    /** 解码长边上限（合成 900×1200 绰绰有余，避免大图直接进内存） */
    const val DECODE_MAX_EDGE = 1600

    /* ══════════════ 缓存目录 ══════════════ */

    fun coverDir(context: Context): File =
        File(context.filesDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    /** 封面文件名：bookId|chapterId（哈希避免非法文件名） */
    fun fileNameFor(bookId: String, chapterId: String): String =
        "gm_${(bookId + "_" + chapterId).hashCode().toString(36)}.jpg"

    fun fileFor(context: Context, bookId: String, chapterId: String): File =
        File(coverDir(context), fileNameFor(bookId, chapterId))

    fun deleteQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /* ══════════════ 解码 ══════════════ */

    /**
     * 按目标尺寸降采样解码一页。
     * @param remoteLoader 在线页用（阅读器注入的 Coil loader）；本地页传 null 即可
     */
    suspend fun loadPage(
        context: Context,
        ref: GodPageRef,
        maxEdge: Int = DECODE_MAX_EDGE,
        remoteLoader: ImageLoader? = null,
    ): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            if (ref.remote) decodeRemote(context, ref, maxEdge, remoteLoader)
            else decodeLocal(ref.source, maxEdge)
        }.getOrNull()
    }

    /** 相册（Photo Picker Uri）解码 */
    suspend fun loadUri(
        context: Context,
        uri: Uri,
        maxEdge: Int = DECODE_MAX_EDGE,
    ): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val probe = stream.readBytes()
                BitmapFactory.decodeByteArray(probe, 0, probe.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(probe, 0, probe.size, opts)
            }
        }.getOrNull()
    }

    private fun decodeLocal(path: String, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        val m = exifMatrix(path) ?: return decoded
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        }.getOrDefault(decoded)
    }

    private suspend fun decodeRemote(
        context: Context,
        ref: GodPageRef,
        maxEdge: Int,
        remoteLoader: ImageLoader?,
    ): Bitmap? {
        val loader = remoteLoader ?: coil.Coil.imageLoader(context)
        val builder = ImageRequest.Builder(context)
            .data(ref.source)
            .size(maxEdge)
            .allowHardware(false)
        ref.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        // 并发闸：页面选择条一屏 7+ 张缩略图同时发起整图下载，图床（MangaDex 等）
        // 会直接限流（429）→ 一批缩略图集体失败（"很多图片加载不出来"）。
        // 排队上限 4，配合阅读器 loader 的磁盘缓存，已看过基本秒出。
        return remoteGate.withPermit {
            val drawable = loader.execute(builder.build()).drawable ?: return null
            runCatching {
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: drawable.toBitmap()
            }.getOrNull()
        }
    }

    /** 远程页加载并发上限（缩略图 + 封面预览共用一条队列） */
    private val remoteGate = kotlinx.coroutines.sync.Semaphore(4)

    /** EXIF 方向归一化（相册照片常见 90°/270°） */
    private fun exifMatrix(path: String): Matrix? = runCatching {
        val exif = android.media.ExifInterface(path)
        when (exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                Matrix().apply { postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                Matrix().apply { postScale(1f, -1f) }
            else -> null
        }
    }.getOrNull()

    private fun sampleSizeFor(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        val long = max(w, h)
        while (long > maxEdge * sample * 2) sample *= 2
        return sample
    }

    /* ══════════════ 裁剪 ══════════════ */

    /** 按 [CropParams] 裁剪原图（先旋转，再按归一化矩形取子图）。 */
    fun applyCrop(source: Bitmap, crop: CropParams): Bitmap {
        val rotated = rotate(source, crop.rotationDeg)
        val w = rotated.width
        val h = rotated.height
        val l = (crop.cropL * w).roundToInt().coerceIn(0, max(0, w - 1))
        val t = (crop.cropT * h).roundToInt().coerceIn(0, max(0, h - 1))
        val r = (crop.cropR * w).roundToInt().coerceIn(l + 1, w)
        val b = (crop.cropB * h).roundToInt().coerceIn(t + 1, h)
        return runCatching { Bitmap.createBitmap(rotated, l, t, r - l, b - t) }
            .getOrDefault(rotated)
    }

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val d = ((degrees % 360f) + 360f) % 360f
        if (d < 0.5f) return source
        val m = Matrix().apply { postRotate(d) }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        }.getOrDefault(source)
    }

    /* ══════════════ 合成 ══════════════ */

    /**
     * 合成固定比例封面。
     *
     * @param cropped 已经过 [applyCrop] 的图（也可以直接传原图 = 不裁剪）
     * @param outW/outH 输出尺寸（正式 900×1200，预览 450×600）
     */
    suspend fun compose(
        cropped: Bitmap,
        outW: Int = COVER_W,
        outH: Int = COVER_H,
    ): Bitmap = withContext(Dispatchers.Default) {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        /* ── 背景：centerCrop → 降采样模糊 → 放大 → 暗色蒙版 ── */
        val bg = centerCrop(cropped, outW, outH)
        val smallW = max(2, outW / BLUR_DOWNSCALE)
        val smallH = max(2, outH / BLUR_DOWNSCALE)
        val small = Bitmap.createScaledBitmap(bg, smallW, smallH, true)
        val radius = max(2, (min(outW, outH) * BLUR_RADIUS_RATIO / BLUR_DOWNSCALE).roundToInt())
        val blurred = fastBlur(small, radius)
        if (bg !== cropped && bg !== small) bg.recycle()
        small.recycle()

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(blurred, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), bgPaint)
        blurred.recycle()
        canvas.drawColor(Color.argb((SCRIM_ALPHA * 255).roundToInt(), 0, 0, 0))

        /* ── 前景：contain 居中 + 圆角 + 投影 ── */
        val margin = min(outW, outH) * FOREGROUND_MARGIN
        val boxW = outW - margin * 2f
        val boxH = outH - margin * 2f
        val scale = min(boxW / cropped.width.toFloat(), boxH / cropped.height.toFloat())
        val dw = cropped.width * scale
        val dh = cropped.height * scale
        val dst = RectF(
            (outW - dw) / 2f,
            (outH - dh) / 2f,
            (outW - dw) / 2f + dw,
            (outH - dh) / 2f + dh,
        )
        val corner = min(outW, outH) * CORNER_RATIO

        // 阴影层：先画一个带 shadowLayer 的圆角矩形打底（随后被图片完全覆盖）
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            setShadowLayer(SHADOW_BLUR, 0f, SHADOW_DY, Color.argb((SHADOW_ALPHA * 255).roundToInt(), 0, 0, 0))
        }
        canvas.drawRoundRect(dst, corner, corner, shadowPaint)
        shadowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

        val path = Path().apply { addRoundRect(dst, corner, corner, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(cropped, null, dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        canvas.restore()

        out
    }

    /** centerCrop：按目标比例居中裁剪 */
    fun centerCrop(src: Bitmap, outW: Int, outH: Int): Bitmap {
        val srcRatio = src.width.toFloat() / src.height
        val dstRatio = outW.toFloat() / outH
        return if (srcRatio > dstRatio) {
            val nw = (src.height * dstRatio).roundToInt()
            val x = ((src.width - nw) / 2f).roundToInt().coerceIn(0, max(0, src.width - 1))
            Bitmap.createBitmap(src, x, 0, nw.coerceAtMost(src.width - x), src.height)
        } else {
            val nh = (src.width / dstRatio).roundToInt()
            val y = ((src.height - nh) / 2f).roundToInt().coerceIn(0, max(0, src.height - 1))
            Bitmap.createBitmap(src, 0, y, src.width, nh.coerceAtMost(src.height - y))
        }
    }

    /**
     * 快速高斯近似模糊：3 次滑动窗口盒式模糊（O(n)，纯 Kotlin）。
     *
     * 为什么不直接用 StackBlur：盒式模糊 ×3 的效果与高斯几乎一致（中心极限），
     * 实现更短、边界处理更不容易出错，且同样不依赖 RenderScript / API 版本。
     */
    fun fastBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 64)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        boxBlurPass(px, w, h, r)
        boxBlurPass(px, w, h, r)
        boxBlurPass(px, w, h, r)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 一次可分离盒式模糊（水平 + 垂直，滑动窗口累加）。
     * 直接原地修改 ARGB IntArray，无额外分配。
     */
    private fun boxBlurPass(px: IntArray, w: Int, h: Int, radius: Int) {
        val r = radius.coerceAtLeast(1)
        val window = r * 2 + 1
        val tmp = IntArray(px.size)
        var sumR: Int
        var sumG: Int
        var sumB: Int
        var sumA: Int

        // 水平
        for (y in 0 until h) {
            val row = y * w
            sumR = 0; sumG = 0; sumB = 0; sumA = 0
            for (i in -r..r) {
                val c = px[row + i.coerceIn(0, w - 1)]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (sumA / window shl 24) or (sumR / window shl 16) or
                    (sumG / window shl 8) or (sumB / window)
                val outC = px[row + (x - r).coerceIn(0, w - 1)]
                val inC = px[row + (x + r + 1).coerceIn(0, w - 1)]
                sumA += ((inC ushr 24) and 0xFF) - ((outC ushr 24) and 0xFF)
                sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
                sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
                sumB += (inC and 0xFF) - (outC and 0xFF)
            }
        }

        // 垂直
        for (x in 0 until w) {
            sumR = 0; sumG = 0; sumB = 0; sumA = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (sumA / window shl 24) or (sumR / window shl 16) or
                    (sumG / window shl 8) or (sumB / window)
                val outC = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val inC = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sumA += ((inC ushr 24) and 0xFF) - ((outC ushr 24) and 0xFF)
                sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
                sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
                sumB += (inC and 0xFF) - (outC and 0xFF)
            }
        }
    }

    /* ══════════════ 落盘 ══════════════ */

    /** 合成并写入 filesDir/god_covers，返回绝对路径。 */
    suspend fun composeAndSave(
        context: Context,
        bookId: String,
        chapterId: String,
        cropped: Bitmap,
        outW: Int = COVER_W,
        outH: Int = COVER_H,
    ): String? = withContext(Dispatchers.Default) {
        runCatching {
            val file = fileFor(context, bookId, chapterId)
            val bmp = compose(cropped, outW, outH)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bmp.recycle()
            file.absolutePath
        }.getOrNull()
    }

    /** 缩略图（页面选择器用）：等比压到长边 [maxEdge]，走 BitmapFactory 降采样。 */
    suspend fun thumbnail(
        context: Context,
        ref: GodPageRef,
        maxEdge: Int = 240,
        remoteLoader: ImageLoader? = null,
    ): Bitmap? = loadPage(context, ref, maxEdge, remoteLoader)

    /** 清理全部封面缓存（设置里的缓存管理可复用）。 */
    suspend fun clearAll(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        coverDir(context).listFiles()?.forEach { f ->
            freed += f.length()
            f.delete()
        }
        freed
    }
}
