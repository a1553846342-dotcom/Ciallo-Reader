package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.get
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLO26n-seg 漫画气泡分割检测器（第十六轮，精准复刻原仓库 BubbleDetector）。
 *
 * 端到端导出图：单类 bubble，输出 [1,300,38]（x1,y1,x2,y2,conf,classId,32 mask 系数）
 * + [1,32,368,368] mask 原型；候选选择已嵌入图内，此处仅做置信度过滤、
 * mask 轮廓重建（扫描线采样成多边形）与类内 NMS 去重。
 * 模型获取方式（2026-09-21 变更）：原先随 APK assets 内置；现改为首次开启翻译时
 * 与 det/rec 模型同批按需下载（见 [TranslateModelManager.bubbleModel]）。
 * 该模型没有官方公开下载源（作者仅随 APK 分发），但 MIT License 允许再分发，
 * 因此托管到本仓库由 jsDelivr CDN 分发。
 */
class BubbleDetector(private val modelProvider: () -> InputStream, private val modelLength: Long) {

    private val session: OrtSession
    private val inputName: String

    init {
        // assets 模型每次复制代价高（11.8MB）——cacheDir 固定缓存文件 + 长度比对免重复拷贝
        val cached = File(OrtSessions.cacheRoot, "mt_yolo_bubble.onnx")
        if (!cached.isFile || cached.length() != modelLength) {
            modelProvider().use { ins ->
                val tmp = File(cached.parentFile, cached.name + ".tmp")
                tmp.outputStream().use { ins.copyTo(it) }
                if (cached.exists()) cached.delete()
                if (!tmp.renameTo(cached)) {
                    tmp.copyTo(cached, overwrite = true); tmp.delete()
                }
            }
        }
        session = OrtSessions.getOrCreate(cached, "yolo|$modelLength")
        inputName = session.inputInfo.keys.first()
    }

    data class Detection(
        val rect: RectF,
        val confidence: Float,
        /** 归一化多边形轮廓 [x0,y0,x1,y1,...]（原图坐标 0..1），null=退化为圆角矩形 */
        val maskContour: FloatArray?,
    )

    @Synchronized
    // track 包住整页推理：关闭会话前必须等在飞推理结束，否则 ONNX native abort（翻译闪退根因）
    fun detect(bitmap: Bitmap): List<Detection> = OrtSessions.track(session) {
        if (bitmap.width <= 1 || bitmap.height <= 1) return emptyList()
        // 第十九轮：长条漫页分块——1472² letterbox 对超高页会把气泡缩到不可检
        val ratio = bitmap.height.toFloat() / bitmap.width
        return if (ratio > 1.6f || bitmap.height > 2000) detectTiled(bitmap) else detectWhole(bitmap)
    }

    /** 长图切片：每片高≈宽×1.6，15% 重叠；轮廓坐标平移回原图后统一去重。 */
    private fun detectTiled(bitmap: Bitmap): List<Detection> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val tileH = (srcW * 1.6f).toInt().coerceIn(900, 2800)
        val overlap = (tileH * 0.15f).toInt()
        val all = ArrayList<Detection>()
        var y = 0
        while (y < srcH) {
            val yEnd = min(srcH, y + tileH)
            val tileHActual = yEnd - y
            val tile = Bitmap.createBitmap(bitmap, 0, y, srcW, tileHActual)
            val piece = detectWhole(tile)
            tile.recycle()
            piece.forEach { d ->
                val newRect = RectF(d.rect.left, d.rect.top + y, d.rect.right, d.rect.bottom + y)
                val newContour = d.maskContour?.let { c ->
                    FloatArray(c.size) { i ->
                        if (i % 2 == 0) c[i] else (c[i] * tileHActual + y) / srcH
                    }
                }
                all.add(Detection(newRect, d.confidence, newContour))
            }
            if (yEnd >= srcH) break
            y = yEnd - overlap
        }
        return deduplicate(all)
    }

    private fun detectWhole(bitmap: Bitmap): List<Detection> {
        val pre = letterbox(bitmap, INPUT_SIZE, INPUT_SIZE)
        return try {
            val input = bitmapToRgbChw(pre.bitmap)
            val tensor = OnnxTensor.createTensor(
                OrtSessions.env, FloatBuffer.wrap(input),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )
            tensor.use { t ->
                try {
                    session.run(mapOf(inputName to t)).use { outputs ->
                        val out0 = outputs[0]
                        val shape = (out0.info as TensorInfo).shape
                        if (shape.size != 3 || shape[1] != 300L || shape[2] != 38L) return emptyList()
                        val buf = (out0 as OnnxTensor).floatBuffer
                        val raw = ArrayList<RawDet>()
                        buf.rewind()
                        val row = FloatArray(38)
                        for (i in 0 until 300) {
                            for (j in 0 until 38) row[j] = buf.get(i * 38 + j)
                            if (row[4] < MIN_CONFIDENCE) continue
                            if (row[5].roundToInt() != 0) continue
                            if (!row[0].isFinite() || !row[1].isFinite() || !row[2].isFinite() || !row[3].isFinite()) continue
                            if (row[2] <= row[0] || row[3] <= row[1]) continue
                            raw.add(RawDet(row.copyOf()))
                        }
                        val protos = if (outputs.size() >= 2) parsePrototypes(outputs[1] as OnnxTensor) else null

                        val detections = ArrayList<Detection>(raw.size)
                        for (det in raw) {
                            val rect = det.toRect(pre, bitmap.width, bitmap.height)
                            if (rect.width() <= 1f || rect.height() <= 1f) continue
                            val contour = protos?.let { computeMaskContour(det, it, pre, bitmap.width, bitmap.height) }
                            detections.add(Detection(rect, det.row[4], contour))
                        }
                        deduplicate(detections)
                    }
                } catch (_: OrtException) {
                    emptyList()
                }
            }
        } finally {
            pre.bitmap.recycle()
        }
    }

    /* ── letterbox：等比缩放 + 居中灰 pad（gainX/gainY 分轴支持短图拉伸路径） ── */

    internal class Letterbox(
        val bitmap: Bitmap,
        val gainX: Float, val gainY: Float,
        val padX: Float, val padY: Float,
    )

    internal fun toOriginalX(inputX: Float, pre: Letterbox): Float = letterboxInverseX(inputX, pre)
    internal fun toOriginalY(inputY: Float, pre: Letterbox): Float = letterboxInverseY(inputY, pre)

    internal fun letterbox(bitmap: Bitmap, inputWidth: Int, inputHeight: Int): Letterbox {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val fitX = (inputWidth.toFloat() / srcW).coerceAtLeast(1e-6f)
        val fitY = (inputHeight.toFloat() / srcH).coerceAtLeast(1e-6f)
        // 短图（高不足）：垂直拉伸填满，气泡在输入里更大更易识别（原仓库同策略）
        if (fitY > fitX) {
            val stretched = bitmap.scale(inputWidth, inputHeight)
            return Letterbox(stretched, fitX, fitY, 0f, 0f)
        }
        val gain = fitY
        val newW = (srcW * gain).toInt().coerceAtLeast(1)
        val newH = (srcH * gain).toInt().coerceAtLeast(1)
        val resized = bitmap.scale(newW, newH)
        val padded = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)
        resized.recycle()
        return Letterbox(padded, gain, gain, padX, padY)
    }

    private fun bitmapToRgbChw(bitmap: Bitmap): FloatArray {
        val w = bitmap.width; val h = bitmap.height
        val plane = w * h
        val out = FloatArray(3 * plane)
        var offset = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bitmap[x, y]
                out[offset] = ((p shr 16) and 0xFF) / 255f
                out[offset + plane] = ((p shr 8) and 0xFF) / 255f
                out[offset + 2 * plane] = (p and 0xFF) / 255f
                offset++
            }
        }
        return out
    }

    /* ── mask 轮廓重建：裁原型区域 → 32 系数加权 → 最大连通域 → 扫描线左右缘多边形 ── */

    internal class Prototypes(val data: FloatArray, val height: Int, val width: Int)

    private fun parsePrototypes(tensor: OnnxTensor): Prototypes? {
        val shape = (tensor.info as TensorInfo).shape
        if (shape.size != 4 || shape[0] != 1L) return null
        val channels = shape[1].toInt(); val h = shape[2].toInt(); val w = shape[3].toInt()
        if (channels != 32 || h <= 0 || w <= 0) return null
        val expected = channels * h * w
        val data = FloatArray(expected)
        val buf = tensor.floatBuffer
        buf.rewind()
        buf.get(data)
        return Prototypes(data, h, w)
    }

    internal fun computeMaskContour(
        det: RawDet,
        proto: Prototypes,
        pre: Letterbox,
        originalWidth: Int,
        originalHeight: Int,
    ): FloatArray? {
        val inputLeft = (det.cx() - det.w() / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val inputTop = (det.cy() - det.h() / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val inputRight = (det.cx() + det.w() / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val inputBottom = (det.cy() + det.h() / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
        val x1 = floor(inputLeft / INPUT_SIZE * proto.width).toInt().coerceIn(0, proto.width - 1)
        val y1 = floor(inputTop / INPUT_SIZE * proto.height).toInt().coerceIn(0, proto.height - 1)
        val x2 = ceil(inputRight / INPUT_SIZE * proto.width).toInt().coerceIn(x1 + 1, proto.width)
        val y2 = ceil(inputBottom / INPUT_SIZE * proto.height).toInt().coerceIn(y1 + 1, proto.height)
        if (x2 <= x1 || y2 <= y1) return null

        val maskW = x2 - x1
        val maskH = y2 - y1
        val foreground = BooleanArray(maskW * maskH)
        for (ly in 0 until maskH) {
            val protoOffset = (y1 + ly) * proto.width + x1
            for (lx in 0 until maskW) {
                var score = 0f
                for (c in 0 until 32) {
                    score += det.row[6 + c] * proto.data[c * proto.height * proto.width + protoOffset + lx]
                }
                foreground[ly * maskW + lx] = score >= 0f
            }
        }
        val main = retainLargestConnectedComponent(foreground, maskW, maskH) ?: return null

        val sampleCount = (y2 - y1).coerceIn(4, MAX_CONTOUR_SAMPLES)
        val leftEdge = ArrayList<Float>(sampleCount * 2)
        val rightEdge = ArrayList<Float>(sampleCount * 2)
        for (sample in 0 until sampleCount) {
            val fraction = if (sampleCount == 1) 0f else sample / (sampleCount - 1f)
            val y = (y1 + ((y2 - 1 - y1) * fraction).toInt()).coerceIn(y1, y2 - 1)
            var leftX = -1; var rightX = -1
            for (x in x1 until x2) {
                if (main[(y - y1) * maskW + (x - x1)]) {
                    if (leftX < 0) leftX = x
                    rightX = x
                }
            }
            if (leftX >= 0) {
                leftEdge.add(mapMaskX(leftX.toFloat(), proto, pre, originalWidth))
                leftEdge.add(mapMaskY(y.toFloat(), proto, pre, originalHeight))
                rightEdge.add(mapMaskX((rightX + 1).toFloat(), proto, pre, originalWidth))
                rightEdge.add(mapMaskY(y.toFloat(), proto, pre, originalHeight))
            }
        }
        if (leftEdge.size < 6) return null
        val polygon = FloatArray(leftEdge.size + rightEdge.size)
        leftEdge.toFloatArray().copyInto(polygon, 0)
        var idx = leftEdge.size
        var i = rightEdge.size - 2
        while (i >= 0) {
            polygon[idx] = rightEdge[i]; polygon[idx + 1] = rightEdge[i + 1]
            idx += 2; i -= 2
        }
        return polygon
    }

    private fun mapMaskX(x: Float, proto: Prototypes, pre: Letterbox, originalWidth: Int): Float {
        val inputX = x / proto.width * INPUT_SIZE
        return (toOriginalX(inputX, pre) / max(1f, originalWidth - 1f)).coerceIn(0f, 1f)
    }

    private fun mapMaskY(y: Float, proto: Prototypes, pre: Letterbox, originalHeight: Int): Float {
        val inputY = y / proto.height * INPUT_SIZE
        return (toOriginalY(inputY, pre) / max(1f, originalHeight - 1f)).coerceIn(0f, 1f)
    }

    internal class RawDet(val row: FloatArray) {
        fun cx() = (row[0] + row[2]) / 2f
        fun cy() = (row[1] + row[3]) / 2f
        fun w() = row[2] - row[0]
        fun h() = row[3] - row[1]
        fun toRect(pre: Letterbox, ow: Int, oh: Int): RectF {
            val left = letterboxInverseX(cx() - w() / 2f, pre)
            val top = letterboxInverseY(cy() - h() / 2f, pre)
            val right = letterboxInverseX(cx() + w() / 2f, pre)
            val bottom = letterboxInverseY(cy() + h() / 2f, pre)
            return RectF(
                left.coerceIn(0f, max(0f, ow - 1f)),
                top.coerceIn(0f, max(0f, oh - 1f)),
                right.coerceIn(0f, max(0f, ow - 1f)),
                bottom.coerceIn(0f, max(0f, oh - 1f)),
            )
        }
    }

    /* ── 类内 NMS（IoU + 包含 + 尺寸/中心一致的宽松重判，原仓库同参数） ── */

    internal fun deduplicate(detections: List<Detection>): List<Detection> {
        if (detections.size <= 1) return detections
        val ranked = detections.indices.sortedWith(
            compareByDescending<Int> { detections[it].confidence }
                .thenByDescending { area(detections[it].rect) }
                .thenBy { it }
        )
        val kept = ArrayList<Int>(detections.size)
        outer@ for (cand in ranked) {
            for (k in kept) {
                if (areDuplicate(detections[cand].rect, detections[k].rect)) {
                    continue@outer
                }
            }
            kept.add(cand)
        }
        kept.sort()
        return kept.map(detections::get)
    }

    internal fun areDuplicate(a: RectF, b: RectF): Boolean {
        val areaA = area(a); val areaB = area(b)
        if (areaA <= 0f || areaB <= 0f) return false
        val inter = interArea(a, b)
        if (inter <= 0f) return false
        val union = areaA + areaB - inter
        if (union > 0f && inter / union >= IOU_THRESHOLD) return true
        val overlapOverMin = inter / min(areaA, areaB)
        if (overlapOverMin >= 0.85f) return true
        if (overlapOverMin < 0.55f) return false
        val wa = a.width(); val wb = b.width(); val ha = a.height(); val hb = b.height()
        if (min(wa, wb) / max(wa, wb) < 0.75f || min(ha, hb) / max(ha, hb) < 0.75f) return false
        val cdx = abs((a.left + a.right) - (b.left + b.right)) * 0.5f
        val cdy = abs((a.top + a.bottom) - (b.top + b.bottom)) * 0.5f
        return cdx <= min(wa, wb) * 0.25f && cdy <= min(ha, hb) * 0.25f
    }

    private fun area(r: RectF) = max(0f, r.width()) * max(0f, r.height())

    private fun interArea(a: RectF, b: RectF): Float =
        max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    companion object {
        const val INPUT_SIZE = 1472
        const val MIN_CONFIDENCE = 0.15f
        const val IOU_THRESHOLD = 0.65f
        private const val MAX_CONTOUR_SAMPLES = 48
    }
}

/** letterbox 正向变换的逆变换（文件级纯函数，供实例与嵌套类共用）。 */
internal fun letterboxInverseX(inputX: Float, pre: BubbleDetector.Letterbox): Float =
    (inputX - pre.padX) / pre.gainX

internal fun letterboxInverseY(inputY: Float, pre: BubbleDetector.Letterbox): Float =
    (inputY - pre.padY) / pre.gainY

/** 最大连通域（8 邻域 BFS，纯逻辑供单测）。 */
internal fun retainLargestConnectedComponent(foreground: BooleanArray, width: Int, height: Int): BooleanArray? {
    if (width <= 0 || height <= 0 || foreground.size != width * height) return null
    val labels = IntArray(foreground.size)
    val queue = IntArray(foreground.size)
    var nextLabel = 0
    var largestLabel = 0
    var largestSize = 0
    for (start in foreground.indices) {
        if (!foreground[start] || labels[start] != 0) continue
        nextLabel++
        var head = 0; var tail = 0; var size = 0
        queue[tail++] = start
        labels[start] = nextLabel
        while (head < tail) {
            val cur = queue[head++]
            size++
            val cx = cur % width
            val cy = cur / width
            for (ny in maxOf(0, cy - 1)..minOf(height - 1, cy + 1)) {
                for (nx in maxOf(0, cx - 1)..minOf(width - 1, cx + 1)) {
                    val n = ny * width + nx
                    if (!foreground[n] || labels[n] != 0) continue
                    labels[n] = nextLabel
                    queue[tail++] = n
                }
            }
        }
        if (size > largestSize) { largestLabel = nextLabel; largestSize = size }
    }
    if (largestLabel == 0) return null
    return BooleanArray(foreground.size) { labels[it] == largestLabel }
}
