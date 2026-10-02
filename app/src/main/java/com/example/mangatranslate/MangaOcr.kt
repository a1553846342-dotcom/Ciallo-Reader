package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.get
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCR ONNX 推理引擎（漫画翻译用）。
 *
 * 检测（det）：PP-OCRv6 small det——动态输入，固定按 960×960 短边适配 + 居中补黑边，
 * 输出概率图走 DB 后处理（连通域 → 阈值 → unclip 扩框）还原文字行矩形。
 * 识别（rec）：PP-OCRv6 small rec——48×320 定高动态宽，CTC 解码，字符集 18710 类
 * （blank + 18708 字符 + 空格，随 APK assets 内置 ppocr_keys_v6_small.txt）。
 *
 * 移植自 manga-translator-android（MIT License, Copyright (c) 2026 jedzqer）的
 * PaddleTextLineDetector / PaddleOcrBase，剥离其 SettingsStore/AppLogger 依赖，
 * 模型来源从 assets 改为运行时下载的本地文件（见 TranslateModelManager）。
 */
object OrtSessions {
    /** assets 模型落盘缓存根（BubbleDetector 等从 assets 复制到这里再建 session）。 */
    lateinit var cacheRoot: File

    /** 在引擎首次使用前注入（阅读器创建时）。 */
    fun ensureCacheRoot(context: android.content.Context) {
        if (!::cacheRoot.isInitialized) {
            cacheRoot = File(context.cacheDir, "mt_models_cache").apply { mkdirs() }
        }
    }

    val env: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OrtEnvironment.getEnvironment() }
    @PublishedApi
    internal val cache = ConcurrentHashMap<String, OrtSession>()
    @PublishedApi
    internal val lock = Any()

    /** 全局推理在飞计数：closeAll 必须等它归零，否则会关掉正在推理的 session（native abort = 闪退）。 */
    @PublishedApi
    internal val globalInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 包住每次 ONNX 推理调用。 */
    inline fun <T> track(session: OrtSession, block: () -> T): T {
        synchronized(lock) {
            check(cache.values.any { it === session }) { "翻译会话已释放，请重新开启翻译" }
            globalInFlight.incrementAndGet()
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                globalInFlight.decrementAndGet()
                (lock as java.lang.Object).notifyAll()
            }
        }
    }

    fun getOrCreate(modelFile: File, key: String): OrtSession =
        synchronized(lock) { cache.getOrPut("${modelFile.canonicalPath}|${modelFile.lastModified()}|$key") { createSession(modelFile) } }

    private fun createSession(modelFile: File): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            // 大图全量优化易 OOM（上游实测），保守档 + 关 memory pattern
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setMemoryPatternOptimization(false)
        }
        return options.use { env.createSession(modelFile.absolutePath, it) }
    }

    /**
     * 释放全部 session（阅读器退出时；下次使用自动重建）。
     *
     * 之前这里与 getOrCreate 不互斥、且关闭守卫只看旧 translator 的在飞计数：快速退出再
     * 重进阅读器（或重开翻译）时，旧的延迟 closeAll 会把新一次推理正在用的 session 关掉，
     * ONNX native 直接 abort（"显示正在翻译后闪退"的根因）。现在与 getOrCreate 同锁，
     * 并等全局在飞推理归零后才关闭；推理卡死 60s 则放弃关闭保会话。
     */
    fun closeAll() {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 60_000
            while (globalInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    (lock as java.lang.Object).wait(150)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (globalInFlight.get() > 0) {
                android.util.Log.w("MTPerf", "closeAll: inference still in flight, skip session close")
                return
            }
            cache.values.forEach { runCatching { it.close() } }
            cache.clear()
        }
    }
}

/** 文字行检测器：整页 Bitmap → 文字行矩形（原图坐标系，阅读序）。 */
class PaddleDetector(modelFile: File) {
    private val session: OrtSession = OrtSessions.getOrCreate(modelFile, "det|${modelFile.length()}")
    private val inputName: String = session.inputInfo.keys.first()

    @Synchronized
    // track 包住整页推理（含分块）：关闭会话前必须等全部在飞推理结束，否则 native abort
    fun detectLines(bitmap: Bitmap): List<RectF> = OrtSessions.track(session) {
        if (bitmap.width < MIN_CROP_SIZE || bitmap.height < MIN_CROP_SIZE) return emptyList()
        val srcW = bitmap.width
        val srcH = bitmap.height
        // 第十九轮：长条漫页（webtoon）分块检测——整页缩进 960² 会把文字缩到不可辨
        // （900×4000 页缩到 24%，regions=0 实测翻车），改为按高度切片、逐块检测再合并。
        val ratio = srcH.toFloat() / srcW
        return if (ratio > 1.8f || srcH > 1600) {
            detectLinesTiled(bitmap)
        } else {
            detectLinesWhole(bitmap)
        }
    }

    /** 长图切片检测：每片高度≈宽×1.4（保证 det 输入缩放≈1），12% 重叠防切行，合并去重。 */
    private fun detectLinesTiled(bitmap: Bitmap): List<RectF> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val tileH = (srcW * 1.4f).toInt().coerceIn(600, 2400)
        val overlap = (tileH * 0.12f).toInt()
        val all = ArrayList<RectF>()
        var y = 0
        while (y < srcH) {
            val yEnd = min(srcH, y + tileH)
            val tile = Bitmap.createBitmap(bitmap, 0, y, srcW, yEnd - y)
            val piece = runCatching { detectLinesWhole(tile) }.getOrDefault(emptyList())
            tile.recycle()
            piece.forEach { r -> all.add(RectF(r.left, r.top + y, r.right, r.bottom + y)) }
            if (yEnd >= srcH) break
            y = yEnd - overlap
        }
        // 重叠区去重：同行被相邻两片各检一次（交集/较小面积 > 0.6 视为重复）
        val kept = ArrayList<RectF>()
        for (r in all) {
            val dup = kept.any { k ->
                val inter = max(0f, min(r.right, k.right) - max(r.left, k.left)) *
                    max(0f, min(r.bottom, k.bottom) - max(r.top, k.top))
                val minArea = min(r.width() * r.height(), k.width() * k.height())
                minArea > 0f && inter / minArea > 0.6f
            }
            if (!dup) kept.add(r)
        }
        return sortBoxesReadingOrder(kept)
    }

    private fun detectLinesWhole(bitmap: Bitmap): List<RectF> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        // 超大页缩到 1920 长边内再检测（上游同款限制；矩形按比例还原）
        val shrink = min(1f, DETECTION_MAX_EDGE.toFloat() / max(srcW, srcH))
        val detSrc: Bitmap = if (shrink < 1f) bitmap.scale((srcW * shrink).toInt(), (srcH * shrink).toInt()) else bitmap
        try {
            val pre = preprocess(detSrc)
            return pre.tensor.use { tensor ->
                try {
                    session.run(mapOf(inputName to tensor)).use { outputs ->
                        val output = outputs[0]
                        val shape = (output.info as TensorInfo).shape
                        val prob = extractProbMap(output.value, shape) ?: return emptyList()
                        val rects = extractLineRects(prob, pre)
                        sortBoxesReadingOrder(rects)
                    }
                } catch (_: OrtException) {
                    emptyList()
                }
            }
        } finally {
            if (detSrc !== bitmap) detSrc.recycle()
        }
    }

    private fun preprocess(bitmap: Bitmap): PreResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(INPUT / srcW.toFloat(), INPUT / srcH.toFloat()).coerceAtLeast(1e-6f)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val resized = bitmap.scale(newW, newH)
        val padded = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)
        val padX = ((INPUT - newW) / 2f).coerceAtLeast(0f)
        val padY = ((INPUT - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)
        resized.recycle()

        val input = FloatArray(3 * INPUT * INPUT)
        var offset = 0
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                val pixel = padded[x, y]
                val b = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val r = (pixel and 0xFF) / 255f
                input[offset] = (b - MEAN[0]) / STD[0]
                input[offset + INPUT * INPUT] = (g - MEAN[1]) / STD[1]
                input[offset + 2 * INPUT * INPUT] = (r - MEAN[2]) / STD[2]
                offset++
            }
        }
        padded.recycle()
        val tensor = OnnxTensor.createTensor(
            OrtSessions.env, FloatBuffer.wrap(input), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())
        )
        return PreResult(
            tensor = tensor, ratioW = newW / srcW.toFloat(), ratioH = newH / srcH.toFloat(),
            padW = padX, padH = padY, originalWidth = srcW, originalHeight = srcH
        )
    }

    /** DB 后处理（纯逻辑，供单测直接调用）。 */
    internal fun extractLineRects(prob: FloatArray, pre: PreResult): List<RectF> {
        val width = INPUT
        val height = INPUT
        val total = width * height
        if (prob.size < total) return emptyList()
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        val results = ArrayList<RectF>()

        for (i in 0 until total) {
            if (visited[i] || prob[i] <= PROB_THRESHOLD) continue
            var minX = width; var minY = height; var maxX = 0; var maxY = 0
            var count = 0; var sum = 0f; var sp = 0
            stack[sp++] = i
            visited[i] = true
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                count++
                sum += prob[idx]
                for (ny in y - 1..y + 1) {
                    if (ny < 0 || ny >= height) continue
                    val rowOffset = ny * width
                    for (nx in x - 1..x + 1) {
                        if (nx < 0 || nx >= width) continue
                        val nidx = rowOffset + nx
                        if (!visited[nidx] && prob[nidx] > PROB_THRESHOLD) {
                            visited[nidx] = true
                            stack[sp++] = nidx
                        }
                    }
                }
            }
            if (count < MIN_COMPONENT_PIXELS) continue
            if (sum / count < BOX_THRESHOLD) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < MIN_SIZE || boxH < MIN_SIZE) continue

            val distance = (boxW * boxH) / (2f * (boxW + boxH)) * UNCLIP_RATIO
            val left = (minX - distance).coerceIn(0f, width.toFloat())
            val top = (minY - distance).coerceIn(0f, height.toFloat())
            val right = (maxX + 1 + distance).coerceIn(0f, width.toFloat())
            val bottom = (maxY + 1 + distance).coerceIn(0f, height.toFloat())

            val leftOrig = ((left - pre.padW) / pre.ratioW).coerceIn(0f, pre.originalWidth.toFloat())
            val topOrig = ((top - pre.padH) / pre.ratioH).coerceIn(0f, pre.originalHeight.toFloat())
            val rightOrig = ((right - pre.padW) / pre.ratioW).coerceIn(0f, pre.originalWidth.toFloat())
            val bottomOrig = ((bottom - pre.padH) / pre.ratioH).coerceIn(0f, pre.originalHeight.toFloat())
            if (rightOrig - leftOrig <= MIN_ORIGINAL_SIZE || bottomOrig - topOrig <= MIN_ORIGINAL_SIZE) continue
            results.add(RectF(leftOrig, topOrig, rightOrig, bottomOrig))
        }
        return results
    }

    /** 阅读序排序（纯逻辑，供单测）：按行带分组，行内按 x。竖排文字另行在块合并时处理。 */
    internal fun sortBoxesReadingOrder(rects: List<RectF>): List<RectF> {
        if (rects.isEmpty()) return emptyList()
        val yCoords = rects.map { it.top }
        val indices = yCoords.indices.sortedWith(compareBy({ yCoords[it] }, { it }))
        val ySorted = indices.map { yCoords[it] }
        val lineIds = IntArray(indices.size)
        for (i in 1 until indices.size) {
            val dy = ySorted[i] - ySorted[i - 1]
            lineIds[i] = lineIds[i - 1] + if (dy >= BOX_SORT_Y_THRESHOLD) 1 else 0
        }
        return indices.withIndex()
            .sortedWith(compareBy({ lineIds[it.index] }, { rects[it.value].left }))
            .map { rects[it.value] }
    }

    internal class PreResult(
        @Suppress("unused") val tensor: OnnxTensor,
        val ratioW: Float, val ratioH: Float,
        val padW: Float, val padH: Float,
        val originalWidth: Int, val originalHeight: Int
    )

    private fun extractProbMap(raw: Any, shape: LongArray): FloatArray? {
        val h: Int; val w: Int; val rows: Array<*>
        when (shape.size) {
            4 -> {
                h = (shape.getOrNull(2) ?: 0L).toInt(); w = (shape.getOrNull(3) ?: 0L).toInt()
                val batch = raw as? Array<*> ?: return null
                val channel = batch.firstOrNull() as? Array<*> ?: return null
                rows = channel.firstOrNull() as? Array<*> ?: return null
            }
            3 -> {
                h = (shape.getOrNull(1) ?: 0L).toInt(); w = (shape.getOrNull(2) ?: 0L).toInt()
                val batch = raw as? Array<*> ?: return null
                rows = batch.firstOrNull() as? Array<*> ?: return null
            }
            else -> return null
        }
        if (h <= 0 || w <= 0 || rows.size < h) return null
        val prob = FloatArray(h * w)
        for (y in 0 until h) {
            val row = rows[y] as? FloatArray ?: return null
            if (row.size < w) return null
            System.arraycopy(row, 0, prob, y * w, w)
        }
        return prob
    }

    companion object {
        const val INPUT = 960
        const val DETECTION_MAX_EDGE = 1920
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        const val PROB_THRESHOLD = 0.2f
        const val BOX_THRESHOLD = 0.45f
        const val UNCLIP_RATIO = 1.4f
        const val MIN_COMPONENT_PIXELS = 3
        const val MIN_SIZE = 3
        const val MIN_ORIGINAL_SIZE = 3f
        const val BOX_SORT_Y_THRESHOLD = 10f
        const val MIN_CROP_SIZE = 32
    }
}

/** 文字识别器：裁剪行 Bitmap → 文本 + 置信度（CTC 解码）。 */
class PaddleRecognizer(modelFile: File, charsetLines: List<String>) {
    private val session: OrtSession = OrtSessions.getOrCreate(modelFile, "rec|${modelFile.length()}")
    private val inputName: String = session.inputInfo.keys.first()

    /** blank 头 + 字符表 + 空格尾 = 与模型 18710 输出类对齐 */
    val charset: List<String> = buildList {
        add("blank")
        addAll(charsetLines.filter { it.isNotEmpty() })
        if (lastOrNull() != " ") add(" ")
    }

    data class RecResult(val text: String, val score: Float)

    @Synchronized
    fun recognize(bitmap: Bitmap): RecResult = OrtSessions.track(session) {
        val imgH = IMG_H
        val imgW = IMG_W
        val h = bitmap.height
        val w = bitmap.width
        if (h < 1 || w < 1) return RecResult("", 0f)
        val ratio = w.toFloat() / h.toFloat()
        val targetW = (imgH * ratio).toInt().coerceIn(1, imgW)
        val resized = bitmap.scale(targetW, imgH)
        return try {
            val input = FloatArray(3 * imgH * imgW)
            for (y in 0 until imgH) {
                for (x in 0 until targetW) {
                    val pixel = resized[x, y]
                    val b = ((pixel shr 16) and 0xFF) / 255f
                    val g = ((pixel shr 8) and 0xFF) / 255f
                    val r = (pixel and 0xFF) / 255f
                    val base = y * imgW + x
                    input[base] = (b - 0.5f) / 0.5f
                    input[base + imgH * imgW] = (g - 0.5f) / 0.5f
                    input[base + 2 * imgH * imgW] = (r - 0.5f) / 0.5f
                }
            }
            val tensor = OnnxTensor.createTensor(
                OrtSessions.env, FloatBuffer.wrap(input), longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
            )
            tensor.use { t ->
                try {
                    session.run(mapOf(inputName to t)).use { outputs ->
                        val output = outputs[0]
                        val shape = (output.info as TensorInfo).shape
                        ctcDecode(output.value, shape)
                    }
                } catch (_: OrtException) {
                    RecResult("", 0f)
                }
            }
        } finally {
            resized.recycle()
        }
    }

    /** CTC 解码（纯逻辑，供单测直接调用）。 */
    internal fun ctcDecode(raw: Any, shape: LongArray): RecResult {
        val batch = raw as? Array<*> ?: return RecResult("", 0f)
        val first = batch.firstOrNull() as? Array<*> ?: return RecResult("", 0f)
        val firstVec = first.firstOrNull()
        if (firstVec !is FloatArray) return RecResult("", 0f)

        val chars = StringBuilder()
        val scores = ArrayList<Float>()
        var prevIdx = -1

        fun append(maxIdx: Int, prob: Float) {
            if (maxIdx == prevIdx) { prevIdx = maxIdx; return }
            prevIdx = maxIdx
            if (maxIdx == 0) return
            if (maxIdx < charset.size) {
                chars.append(charset[maxIdx])
                scores.add(prob)
            }
        }

        fun argmax(probs: FloatArray): Pair<Int, Float> {
            var mi = 0; var mp = probs[0]
            for (i in 1 until probs.size) if (probs[i] > mp) { mp = probs[i]; mi = i }
            return mi to mp
        }

        val dim0 = batch.size
        val dim1 = first.size
        val dim2 = firstVec.size
        val looksLikeClasses: (Int) -> Boolean = { v -> v == charset.size || v == charset.size - 1 || v == charset.size + 1 }

        when {
            dim1 == 1 && dim0 > 1 -> {
                for (t in 0 until dim0) {
                    val inner = batch[t] as? Array<*> ?: continue
                    val probs = inner.firstOrNull() as? FloatArray ?: continue
                    val (mi, mp) = argmax(probs)
                    append(mi, mp)
                }
            }
            dim0 == 1 && looksLikeClasses(dim2) -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val (mi, mp) = argmax(probs)
                    append(mi, mp)
                }
            }
            dim0 == 1 && looksLikeClasses(dim1) -> {
                val classArrays = first.mapNotNull { it as? FloatArray }
                val timeCount = classArrays.firstOrNull()?.size ?: 0
                for (t in 0 until timeCount) {
                    var mi = 0; var mp = Float.NEGATIVE_INFINITY
                    for (c in classArrays.indices) {
                        if (t >= classArrays[c].size) continue
                        val v = classArrays[c][t]
                        if (v > mp) { mp = v; mi = c }
                    }
                    append(mi, mp)
                }
            }
            else -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val (mi, mp) = argmax(probs)
                    append(mi, mp)
                }
            }
        }
        val score = if (scores.isEmpty()) 0f else scores.sum() / scores.size
        return RecResult(chars.toString(), score)
    }

    companion object {
        const val IMG_H = 48
        const val IMG_W = 320
    }
}
