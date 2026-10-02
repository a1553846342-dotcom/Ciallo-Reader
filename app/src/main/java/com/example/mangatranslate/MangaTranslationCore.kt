package com.example.mangatranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 单个译文区域：矩形（页面位图坐标系）+ 原文 + 译文 + 语言 + 排版方向。 */
data class TranslatedRegion(
    val rect: RectF,
    val original: String,
    val translated: String,
    val lang: String,
    val vertical: Boolean,
    /** 气泡形状轮廓（归一化多边形，第十六轮）；null=矩形块。 */
    val contour: FloatArray? = null,
    /** 原文行矩形（第十八轮）：译文排版锚定原文位置。 */
    val lineRects: List<RectF> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is TranslatedRegion && rect == other.rect && original == other.original &&
            translated == other.translated && lang == other.lang && vertical == other.vertical &&
            contour?.contentEquals(other.contour) == true && lineRects == other.lineRects
    override fun hashCode(): Int = rect.hashCode() * 31 + translated.hashCode()
}

/** 一页的完整译文（含页面尺寸，用于缓存一致性校验）。 */
data class PageTranslation(
    val pageWidth: Int,
    val pageHeight: Int,
    val regions: List<TranslatedRegion>,
) {
    val hasUsableText: Boolean get() = regions.any { it.translated.isNotBlank() }
}

/* ══════════════ 磁盘缓存（cacheDir/manga_translate_v1） ══════════════ */

/** 逐页译文 JSON 缓存：翻回已译页零成本。LRU 总量上限 64MB。 */
object TranslationCache {
    private const val DIR_NAME = "manga_translate_v1"
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    private fun fileFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(40)
        return File(dir(context), "$name.json")
    }

    fun read(context: Context, key: String, pageWidth: Int, pageHeight: Int): PageTranslation? =
        runCatching {
            val f = fileFor(context, key)
            if (!f.isFile) return null
            if(f.length()>4L*1024*1024) { f.delete(); return null }
            val json = JSONObject(f.readText())
            val size = json.optJSONObject("size") ?: return null
            if (size.optInt("w") != pageWidth || size.optInt("h") != pageHeight) return null
            val arr = json.optJSONArray("r") ?: return null
            val regions = ArrayList<TranslatedRegion>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val contour = runCatching {
                    val ca = o.optJSONArray("ct") ?: return@runCatching null
                    if (ca.length() >= 6 && ca.length() % 2 == 0) {
                        FloatArray(ca.length()) { ca.getDouble(it).toFloat() }
                    } else null
                }.getOrNull()
                val lineRects = runCatching {
                    val la = o.optJSONArray("lr") ?: return@runCatching emptyList()
                    (0 until la.length()).mapNotNull { i ->
                        val lo = la.optJSONObject(i) ?: return@mapNotNull null
                        RectF(
                            lo.getDouble("l").toFloat(), lo.getDouble("t").toFloat(),
                            lo.getDouble("r").toFloat(), lo.getDouble("b").toFloat(),
                        )
                    }
                }.getOrDefault(emptyList())
                regions.add(
                    TranslatedRegion(
                        rect = RectF(
                            o.getDouble("l").toFloat(), o.getDouble("t").toFloat(),
                            o.getDouble("r").toFloat(), o.getDouble("b").toFloat(),
                        ),
                        original = o.optString("o"),
                        translated = o.optString("c"),
                        lang = o.optString("lang"),
                        vertical = o.optBoolean("v"),
                        contour = contour,
                        lineRects = lineRects,
                    )
                )
            }
            f.setLastModified(System.currentTimeMillis())
            PageTranslation(pageWidth, pageHeight, regions)
        }.getOrNull()

    fun write(context: Context, key: String, translation: PageTranslation) {
        runCatching {
            val f = fileFor(context, key)
            val arr = JSONArray()
            translation.regions.forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("l", r.rect.left.toDouble()); put("t", r.rect.top.toDouble())
                        put("r", r.rect.right.toDouble()); put("b", r.rect.bottom.toDouble())
                        put("o", r.original); put("c", r.translated)
                        put("lang", r.lang); put("v", r.vertical)
                        r.contour?.let { c ->
                            val ca = JSONArray()
                            c.forEach { f -> ca.put(f.toDouble()) }
                            put("ct", ca)
                        }
                        if (r.lineRects.isNotEmpty()) {
                            val la = JSONArray()
                            r.lineRects.forEach { l ->
                                la.put(JSONObject()
                                    .put("l", l.left.toDouble()).put("t", l.top.toDouble())
                                    .put("r", l.right.toDouble()).put("b", l.bottom.toDouble()))
                            }
                            put("lr", la)
                        }
                    }
                )
            }
            val json = JSONObject().apply {
                put("size", JSONObject().apply { put("w", translation.pageWidth); put("h", translation.pageHeight) })
                put("r", arr)
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString()); tmp.delete()
            }
            trimIfNeeded(context)
        }
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clear(context: Context): Long {
        var freed = 0L
        dir(context).listFiles()?.forEach {
            if (it.isFile) { freed += it.length(); runCatching { it.delete() } }
        }
        return freed
    }

    private fun trimIfNeeded(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}

/* ══════════════ 行 → 块合并（纯逻辑，供单测） ══════════════ */

/** 检测行按气泡聚块：同气泡多行合并成一块整体翻译（保上下文、覆盖更干净）。 */
object TextBlockGrouper {
    data class Block(val rect: RectF, val lines: List<RectF>, val vertical: Boolean)

    private fun isVerticalLine(r: RectF): Boolean = r.height() > r.width() * 1.5f
    private fun isHorizontalLine(r: RectF): Boolean = r.width() > r.height() * 1.5f

    private fun overlaps(a: RectF, b: RectF): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    fun group(lines: List<RectF>): List<Block> {
        if (lines.isEmpty()) return emptyList()
        val vertical = lines.filter { isVerticalLine(it) }
        val horizontal = lines.filter { isHorizontalLine(it) }
        val squares = lines.filter { !isVerticalLine(it) && !isHorizontalLine(it) }

        val blocks = ArrayList<Block>()
        blocks.addAll(groupVertical(vertical))
        blocks.addAll(groupHorizontal(horizontal))
        // 近方形行（单字/拟方框）：并入重叠最多且并集面积增量最小的块，否则独立成块
        for (sq in squares) {
            val host = blocks.filter { overlaps(it.rect, sq) }
                .minByOrNull { unionArea(it.rect, sq) - areaOf(it.rect) }
            if (host != null) {
                val merged = RectF(host.rect).apply { union(sq) }
                blocks[blocks.indexOf(host)] = Block(merged, host.lines + sq, host.vertical)
            } else {
                blocks.add(Block(RectF(sq), listOf(sq), vertical = sq.height() >= sq.width()))
            }
        }
        return blocks
    }

    /** 竖行聚类：按 x 扫描，列 x 范围重叠（或列距 < 1.1 列宽）且纵向相邻（< 2.6 列宽）。 */
    private fun groupVertical(lines: List<RectF>): List<Block> {
        if (lines.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<RectF>>()
        for (line in lines.sortedBy { it.centerX() }) {
            val last = groups.lastOrNull()
            val attach = last != null && run {
                val avgColW = last.sumOf { it.width().toDouble() } / last.size
                val xOverlap = min(line.right, last.maxOf { r -> r.right }) - max(line.left, last.minOf { r -> r.left })
                // 最近列中心距（组变宽后平均中心会被稀释，改取组内最近列判定邻接）
                val gap = last.minOf { r -> kotlin.math.abs(line.centerX() - r.centerX()) }
                val vAdjacent = line.top < last.maxOf { r -> r.bottom } + avgColW * 2.6f
                (xOverlap > 0f || gap < avgColW * 1.5f) && vAdjacent
            }
            if (attach && last != null) last.add(line) else groups.add(mutableListOf(line))
        }
        return groups.map { g ->
            // 竖排阅读序：列按 x 降序（右起），列内 y 升序
            val ordered = g.sortedWith(compareByDescending<RectF> { it.centerX() }.thenBy { it.top })
            Block(unionOf(g), ordered, vertical = true)
        }
    }

    /** 横行聚类：按 y 扫描，纵向间距 < 3.2 行高 且（水平重叠 或 水平间距 < 1.6 行高）。 */
    private fun groupHorizontal(lines: List<RectF>): List<Block> {
        if (lines.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<RectF>>()
        for (line in lines.sortedBy { it.top }) {
            val last = groups.lastOrNull()
            val attach = last != null && run {
                val avgH = last.sumOf { it.height().toDouble() } / last.size
                val band = max(1f, avgH.toFloat())
                val sameBand = line.top < last.minOf { r -> r.top } + band * 3.2f
                val xOverlap = min(line.right, last.maxOf { r -> r.right }) - max(line.left, last.minOf { r -> r.left })
                val xGap = max(last.minOf { r -> r.left } - line.right, line.left - last.maxOf { r -> r.right })
                sameBand && (xOverlap > 0f || xGap < band * 1.6f)
            }
            if (attach && last != null) last.add(line) else groups.add(mutableListOf(line))
        }
        return groups.map { g ->
            val band = max(1f, avgHeight(g))
            val ordered = g.sortedWith(
                compareBy<RectF> { (it.top / band).toInt() }.thenBy { it.left }
            )
            Block(unionOf(g), ordered, vertical = false)
        }
    }

    private fun avgHeight(g: List<RectF>): Float = (g.sumOf { it.height().toDouble() } / g.size).toFloat()
    private fun unionOf(g: List<RectF>): RectF = RectF(g.first()).apply { g.drop(1).forEach { union(it) } }
    private fun areaOf(r: RectF): Float = r.width() * r.height()
    private fun unionArea(a: RectF, b: RectF): Float =
        (max(a.right, b.right) - min(a.left, b.left)) * (max(a.bottom, b.bottom) - min(a.top, b.top))
}

/* ══════════════ 覆盖渲染（原文抹除 + 译文排版） ══════════════ */

/** 译文烘焙：白底圆角块盖原文 + 适配字号排版（横排居中 / 竖排列右起）。 */
object OverlayRenderer {

    private const val PADDING_FRACTION = 0.06f
    private const val BG_COLOR = 0xFFFDFBF7.toInt()
    private const val TEXT_COLOR = 0xFF1A1A18.toInt()
    private const val OUTLINE_COLOR = 0x14262626

    fun bake(base: Bitmap, translation: PageTranslation, textScale: Float): Bitmap {
        PageMemoryBudget.check(base)
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        translation.regions
            .filter { it.translated.isNotBlank() }
            .forEach { region -> drawRegion(canvas, region, textScale) }
        return out
    }

    private fun drawRegion(canvas: Canvas, region: TranslatedRegion, textScale: Float) {
        val rect = region.rect
        if (rect.width() < 6f || rect.height() < 6f) return
        val pad = min(rect.width(), rect.height()) * PADDING_FRACTION
        val inner = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        if (inner.width() < 4f || inner.height() < 4f) return

        val corner = min(10f, min(rect.width(), rect.height()) * 0.14f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG_COLOR }
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = OUTLINE_COLOR
        }
        canvas.drawRoundRect(rect, corner, corner, outline)

        if (region.vertical) drawVertical(canvas, inner, region.translated, textScale)
        else drawHorizontal(canvas, inner, region.translated, textScale)
    }

    private fun drawHorizontal(canvas: Canvas, inner: RectF, text: String, textScale: Float) {
        val maxW = inner.width().toInt().coerceAtLeast(1)
        val maxH = inner.height()
        var layout: StaticLayout? = null
        var size = (maxH * 0.98f).coerceAtMost(64f)
        val minSize = 7f
        while (size >= minSize && layout == null) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * textScale
                color = TEXT_COLOR
                typeface = Typeface.DEFAULT_BOLD
            }
            val candidate = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.05f)
                .build()
            if (candidate.height <= maxH && linesFitWidth(candidate, maxW.toFloat())) layout = candidate
            else size -= max(1f, size * 0.08f)
        }
        layout ?: return
        canvas.save()
        canvas.translate(
            inner.left + (inner.width() - layout.width) / 2f,
            inner.top + (inner.height() - layout.height) / 2f,
        )
        layout.draw(canvas)
        canvas.restore()
    }

    private fun linesFitWidth(layout: StaticLayout, maxW: Float): Boolean {
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > maxW + 0.5f) return false
        }
        return true
    }

    /** 竖排：字自上而下叠成列，列自右向左（中文漫画习惯）。 */
    private fun drawVertical(canvas: Canvas, inner: RectF, text: String, textScale: Float) {
        val chars = text.replace(Regex("\\s+"), "").toCharArray()
        if (chars.isEmpty()) return
        val fit = fitVertical(chars.size, inner.width(), inner.height())
        if (fit == null) return
        val (size, perCol, step) = fit
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * textScale
            color = TEXT_COLOR
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val startX = inner.right - step / 2f
        var idx = 0
        var col = 0
        while (idx < chars.size) {
            val x = startX - col * step
            var y = inner.top + size
            var inCol = 0
            while (idx < chars.size && inCol < perCol) {
                canvas.drawText(chars, idx, 1, x, y, paint)
                y += step
                idx++; inCol++
            }
            col++
        }
    }

    private data class VerticalFit(val size: Float, val perCol: Int, val step: Float)

    private fun fitVertical(charCount: Int, maxW: Float, maxH: Float): VerticalFit? {
        var size = (maxW * 0.9f).coerceAtMost(maxH * 0.5f)
        val minSize = 7f
        while (size >= minSize) {
            val step = size * 1.18f
            val perCol = max(1, (maxH / step).toInt())
            val cols = ceil(charCount / perCol.toDouble()).toInt()
            if (cols * step <= maxW) return VerticalFit(size, perCol, step)
            size -= max(1f, size * 0.08f)
        }
        return null
    }

    /** 适配字号（供单测）：返回能放下的字号，放不下返回 0。 */
    fun fitFontSize(text: String, maxW: Float, maxH: Float, vertical: Boolean): Float {
        if (vertical) {
            val chars = text.replace(Regex("\\s+"), "").toCharArray()
            if (chars.isEmpty()) return 0f
            return fitVertical(chars.size, maxW, maxH)?.size ?: 0f
        }
        var size = (maxH * 0.98f).coerceAtMost(64f)
        val minSize = 7f
        while (size >= minSize) {
            val paint = TextPaint().apply { textSize = size }
            val w = paint.measureText(text)
            if (w <= maxW) {
                val lines = ceil(w / maxW.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                if (lines * size * 1.05f <= maxH) return size
            }
            size -= max(1f, size * 0.08f)
        }
        return 0f
    }
}

/* ══════════════ 页面翻译器（OCR + 语言检测 + 翻译） ══════════════ */

/** 单页 OCR → 译文。OCR 全离线（ONNX CPU）；气泡级形状检测；翻译走引擎链。 */
class MangaPageTranslator(
    context: Context,
    private val textTranslator: TextTranslator,
    private val llmTranslator: LlmBubbleTranslator,
) {
    private val appContext = context.applicationContext
    private val engineLock = Any()
    private var detRef: PaddleDetector? = null
    private var detLen: Long = 0L
    private var recRef: PaddleRecognizer? = null
    private var recLen: Long = 0L
    private var bubbleRef: BubbleDetector? = null
    private var bubbleLen: Long = 0L

    /** 在飞 native 推理计数：release 关会话前必须等它归零（对运行中的会话 close 会 FORTIFY abort）。 */
    private val activeInference = java.util.concurrent.atomic.AtomicInteger(0)

    internal fun inFlightInference(): Int = activeInference.get()

    val ocrReady: Boolean get() = TranslateModelManager.isReady(appContext)

    private fun detector(): PaddleDetector? {
        synchronized(engineLock) {
            val f = TranslateModelManager.detFile(appContext)
            if (!f.isFile) return null
            if (detRef == null || detLen != f.length()) {
                detRef = runCatching { PaddleDetector(f) }.getOrNull()
                detLen = f.length()
            }
            return detRef
        }
    }

    private fun recognizer(): PaddleRecognizer? {
        synchronized(engineLock) {
            val f = TranslateModelManager.recFile(appContext)
            if (!f.isFile) return null
            if (recRef == null || recLen != f.length()) {
                recRef = runCatching {
                    val charset = appContext.assets.open("mt/ppocr_keys_v6_small.txt")
                        .bufferedReader(Charsets.UTF_8).readLines()
                    PaddleRecognizer(f, charset)
                }.getOrNull()
                recLen = f.length()
            }
            return recRef
        }
    }

    /**
     * YOLO 气泡分割（2026-09-21：模型由 APK 内置改为按需下载）。
     *
     * 模型文件由 [TranslateModelManager.ensureDownloaded] 在首次开启翻译时下载到
     * filesDir/manga_translate_models/，与 det/rec 同批、同一进度条。
     * 这里只读本地文件；若尚未下载则返回 null（调用方会看到「模型未就绪」，
     * 正常流程下不会发生，因为下载是翻译的前置步骤）。
     */
    private fun bubbleDetector(): BubbleDetector? {
        synchronized(engineLock) {
            if (bubbleRef != null) return bubbleRef
            bubbleRef = runCatching {
                val f = TranslateModelManager.bubbleFile(appContext)
                if (!f.isFile || f.length() < TranslateModelManager.bubbleModel.minBytes) {
                    // 尚未下载：交给调用方走「先下载模型」流程，不在此处抛异常
                    null
                } else {
                    BubbleDetector(
                        modelProvider = { f.inputStream() },
                        modelLength = f.length(),
                    )
                }
            }.getOrNull()
            return bubbleRef
        }
    }

    /**
     * 释放引擎引用（会话由 OrtSessions.closeAll() 统一关闭）。
     * det/rec/yolo 三个 ONNX 会话常驻约 60-100MB——退出阅读器或关闭翻译时调用，
     * 下次翻译自动重建（模型文件已在本地，重建仅需秒级）。
     */
    fun release() {
        synchronized(engineLock) {
            detRef = null
            detLen = 0L
            recRef = null
            recLen = 0L
            bubbleRef = null
            pageRegionRef = null
        }
    }

    /** 页面区域检测器（第十九轮：完整复刻原仓库 PageRegionDetector 流程）。 */
    @Volatile
    private var pageRegionRef: PageRegionDetector? = null

    private fun pageRegionDetector(): PageRegionDetector? {
        synchronized(engineLock) {
            pageRegionRef?.let { return it }
            val det = detector() ?: return null
            val bubble = bubbleDetector() ?: return null
            pageRegionRef = PageRegionDetector(det, bubble)
            return pageRegionRef
        }
    }

    /**
     * 第十九轮检测：完整复刻原仓库 PageRegionDetector——
     * 常规页一次检测 + 行归属；长条漫页自适应切片 + 跨片去重 + 行跨越合并；
     * tiny 假阳性过滤、文字行→TextBlockMerger 块合并（原仓库算法 1:1）。
     */
    suspend fun detectRegions(bitmap: Bitmap): List<BubblePipeline.Region> =
        withContext(Dispatchers.Default) {
            activeInference.incrementAndGet()
            try {
                detectRegionsTracked(bitmap)
            } finally {
                activeInference.decrementAndGet()
            }
        }

    private suspend fun detectRegionsTracked(bitmap: Bitmap): List<BubblePipeline.Region> =
        withContext(Dispatchers.Default) {
            val pageRegion = pageRegionDetector() ?: return@withContext emptyList()
            val result = pageRegion.detect(bitmap) ?: return@withContext emptyList()
            if (result.textLines.isEmpty() && result.bubbles.isEmpty()) {
                return@withContext emptyList()
            }
            // 气泡区域携带自己的文字行（lineBelongsToRegion，原仓库语义）；
            // 游离行用 TextBlockMerger 的块（原仓库 buildRegions 等价物）
            val regions = ArrayList<BubblePipeline.Region>(result.bubbles.size + result.textBlocks.size)
            for (bubble in result.bubbles) {
                val own = result.textLines.filter {
                    PageRegionTiling.lineBelongsToRegion(it, bubble.rect)
                }
                val vertical = own.isNotEmpty() &&
                    own.count { it.height() > it.width() * 1.5f } > own.size / 2
                regions.add(
                    BubblePipeline.Region(
                        bubble.rect, bubble.maskContour, own, vertical,
                    )
                )
            }
            // 游离行：不被任何气泡抑制的行 → 已合并的 TextBlock。
            // merge 的输入已经过气泡抑制过滤，块的 lines 直接全部保留
            // （注意 RectF 未重写 equals，严禁再做实例级求交过滤）。
            val freeLines = result.textLines.filter { line ->
                result.bubbles.none { PageRegionTiling.shouldFilterTextRectByBubble(line, it.rect, 0.2f) }
            }
            for (block in result.textBlocks) {
                if (block.lines.isEmpty()) continue
                val vertical = block.orientation == TextLineOrientation.VERTICAL
                regions.add(
                    BubblePipeline.Region(block.rect, null, block.lines, vertical)
                )
            }
            // 兜底：有游离行但没有任何块覆盖到（合并失败等）→ 直接逐行成块
            if (regions.none { it.maskContour == null && it.lines.isNotEmpty() } && freeLines.isNotEmpty()) {
                for (line in freeLines) {
                    regions.add(BubblePipeline.Region(RectF(line), null, listOf(RectF(line)), false))
                }
            }
            regions
        }

    /** OCR 归并后的区域（按行拼接块文本；竖排行旋转后识别）。 */
    suspend fun ocrRegions(bitmap: Bitmap, regions: List<BubblePipeline.Region>, forcedLang: String? = null): List<TranslatedRegion> =
        withContext(Dispatchers.Default) {
            activeInference.incrementAndGet()
            try {
                ocrRegionsTracked(bitmap, regions, forcedLang)
            } finally {
                activeInference.decrementAndGet()
            }
        }

    private suspend fun ocrRegionsTracked(bitmap: Bitmap, regions: List<BubblePipeline.Region>, forcedLang: String?): List<TranslatedRegion> =
        withContext(Dispatchers.Default) {
            val rec = recognizer() ?: return@withContext emptyList()
            val out = ArrayList<TranslatedRegion>(regions.size)
            for (region in regions) {
                val sb = StringBuilder()
                for (line in region.lines) {
                    val crop = cropRect(bitmap, line) ?: continue
                    val oriented = if (region.vertical) rotateCcw(crop) else null
                    val target = oriented ?: crop
                    val result = rec.recognize(target)
                    oriented?.recycle()
                    if (oriented === null || oriented !== crop) crop.recycle()
                    val piece = result.text.trim()
                    // OCR 置信度过滤：低分识别（画面纹理/拟声词误读）不进译文
                    if (piece.isNotEmpty() && result.score >= 0.45f) {
                        if (sb.isNotEmpty() && !region.vertical) sb.append(' ')
                        sb.append(piece)
                    }
                }
                val text = sb.toString().trim()
                if (text.length < 2) continue
                val lang = forcedLang ?: ScriptDetector.detect(text)
                out.add(
                    TranslatedRegion(
                        rect = region.rect,
                        original = text,
                        translated = "",
                        lang = lang ?: "",
                        vertical = region.vertical,
                        contour = region.maskContour,
                        lineRects = region.lines,
                    )
                )
            }
            out
        }

    /** 旧入口（保留给单测/兼容）：整页检测+归并。 */
    suspend fun ocrPage(bitmap: Bitmap, forcedLang: String? = null): List<TranslatedRegion> {
        val regions = detectRegions(bitmap)
        return ocrRegions(bitmap, regions, forcedLang)
    }

    /**
     * 完整翻译一页（自定义 AI 链）：区域检测 → OCR → 整页一次请求。
     * AI 失败自动降级在线兜底。
     */
    suspend fun translatePage(bitmap: Bitmap, forcedLang: String? = null): PageTranslation =
        withContext(Dispatchers.Default) {
            activeInference.incrementAndGet()
            try {
                translatePageTracked(bitmap, forcedLang)
            } finally {
                activeInference.decrementAndGet()
            }
        }

    private suspend fun translatePageTracked(bitmap: Bitmap, forcedLang: String?): PageTranslation =
        withContext(Dispatchers.Default) {
            val ocr = detectAndOcr(bitmap, forcedLang)
            if (ocr.isEmpty()) return@withContext PageTranslation(bitmap.width, bitmap.height, emptyList())

            val llmOut: Map<Int, String>? = llmTranslator.translateBuckets(ocr)
            val translated: List<TranslatedRegion> = if (llmOut != null) {
                ocr.mapIndexed { i, region ->
                    val t = llmOut[i]
                    if (t.isNullOrBlank()) region else region.copy(translated = t.trim())
                }
            } else {
                translateRegionsOnline(ocr)
            }
            PageTranslation(bitmap.width, bitmap.height, translated)
        }

    /** 在线兜底专用（用户显式选择 online 引擎）：跳过 AI，直接腾讯批量。 */
    suspend fun translatePageOnline(bitmap: Bitmap, forcedLang: String? = null): PageTranslation =
        withContext(Dispatchers.Default) {
            activeInference.incrementAndGet()
            try {
                translatePageOnlineTracked(bitmap, forcedLang)
            } finally {
                activeInference.decrementAndGet()
            }
        }

    private suspend fun translatePageOnlineTracked(bitmap: Bitmap, forcedLang: String?): PageTranslation =
        withContext(Dispatchers.Default) {
            val ocr = detectAndOcr(bitmap, forcedLang)
            if (ocr.isEmpty()) return@withContext PageTranslation(bitmap.width, bitmap.height, emptyList())
            PageTranslation(bitmap.width, bitmap.height, translateRegionsOnline(ocr))
        }

    private suspend fun detectAndOcr(bitmap: Bitmap, forcedLang: String?): List<TranslatedRegion> {
        val regions = detectRegions(bitmap)
        return ocrRegions(bitmap, regions, forcedLang)
            .filter { it.lang.isNotBlank() && it.lang != "zh" }
    }

    /** 按语言分组批量在线翻译（腾讯一次请求保持顺序）。 */
    private suspend fun translateRegionsOnline(ocr: List<TranslatedRegion>): List<TranslatedRegion> {
        val out = ocr.toMutableList()
        val byLang = LinkedHashMap<String, MutableList<Int>>()
        ocr.forEachIndexed { index, region ->
            byLang.getOrPut(region.lang) { mutableListOf() }.add(index)
        }
        for ((lang, idxs) in byLang) {
            val batch = idxs.map { ocr[it].original }
            val results = (textTranslator as? OnlineFallbackTranslator)
                ?.translateBatch(batch, lang)
                ?: batch.map { textTranslator.translate(it, lang) }
            idxs.forEachIndexed { j, regionIdx ->
                val t = results.getOrNull(j)
                if (!t.isNullOrBlank()) out[regionIdx] = out[regionIdx].copy(translated = t.trim())
            }
        }
        return out
    }

    private fun cropRect(src: Bitmap, r: RectF): Bitmap? {
        val l = r.left.toInt().coerceIn(0, src.width - 1)
        val t = r.top.toInt().coerceIn(0, src.height - 1)
        val w = (r.right.toInt() - l).coerceIn(1, src.width - l)
        val h = (r.bottom.toInt() - t).coerceIn(1, src.height - t)
        if (w < 4 || h < 4) return null
        return runCatching { Bitmap.createBitmap(src, l, t, w, h) }.getOrNull()
    }

    private fun rotateCcw(src: Bitmap): Bitmap {
        val m = Matrix()
        m.setRotate(-90f)
        return runCatching { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) }
            .getOrElse { src }
    }
}

/* ══════════════ 阅读器协调器（去重/缓存/烘焙发布） ══════════════ */

/**
 * 翻译协调器：按页调度（当前+预取），缓存优先，烘焙结果经回调发布回阅读器缓存。
 * epoch 自增通知 UI 重新播种位图状态。
 */
class TranslationCoordinator(
    context: Context,
    textTranslator: TextTranslator,
    private val llmTranslator: LlmBubbleTranslator,
) {
    private val appContext = context.applicationContext
    private val translator = MangaPageTranslator(appContext, textTranslator, llmTranslator)

    /** 用户显式选择的引擎（第十八轮二级导航）；调用方随配置更新。 */
    @Volatile
    var selectedEngine: String = "online"

    /** 当前引擎标识（进缓存 key）：换引擎后同页自动重译，不读旧引擎结果。 */
    fun engineTag(): String = selectedEngine
    private val jobs = ConcurrentHashMap<String, Job>()
    private val translateMutex = PageMemoryBudget.gate
    private val bakedKeys = ConcurrentHashMap.newKeySet<String>()

    /** 快速开关保护：release 后又来了新任务（重开翻译）时，待执行的会话关闭作废。 */
    @Volatile
    private var releasePending = false

    /** 每次烘焙完成 +1；阅读器将其纳入 rememberPageBitmap/翻页特效重建键。 */
    val epoch = MutableStateFlow(0)
    /** 正在翻译的 cacheKey 集合（UI 显示"翻译中"角标）。 */
    val busyKeys = MutableStateFlow<Set<String>>(emptySet())

    val ocrReady: Boolean get() = translator.ocrReady

    /**
     * 调度一页翻译。[loadBase] 返回该页当前处理位图（loader.load）；
     * [publishBaked] 把烘焙后的位图写回 loader 缓存并触发 UI 更新。
     */
    fun schedule(
        scope: kotlinx.coroutines.CoroutineScope,
        cacheKey: String,
        translationKey: String,
        forcedLang: String?,
        textScale: Float,
        loadBase: suspend () -> Bitmap?,
        publishBaked: suspend (Bitmap) -> Unit,
    ) {
        if (!translator.ocrReady) return
        if (bakedKeys.contains(cacheKey) || jobs.containsKey(cacheKey)) return
        releasePending = false // 新任务到达：翻译重新启用，作废挂起的会话关闭
        // 第十七轮：缓存 key 带引擎+源语言标识——切换引擎或"页面文字"语言后同页自动重译
        val engine = selectedEngine
        val modelTag = if(engine == "ai") llmTranslator.cacheFingerprint() else "online-v1"
        val enginePrefix = "$translationKey@$engine-${forcedLang ?: "auto"}-$modelTag-v2"
        jobs[cacheKey] = scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                busyKeys.value = busyKeys.value + cacheKey
                val base = loadBase() ?: return@launch
                if (bakedKeys.contains(cacheKey)) return@launch
                PageMemoryBudget.check(base)
                val engineKey = "$enginePrefix@${PageMemoryBudget.fingerprint(base)}"
                val t1 = android.os.SystemClock.elapsedRealtime()
                val cached = TranslationCache.read(appContext, engineKey, base.width, base.height)
                val translation = cached ?: translateMutex.withLock {
                    // 串行 OCR（CPU 密集），缓存 JSON 仍可命中并发写
                    TranslationCache.read(appContext, engineKey, base.width, base.height)
                        ?: (if (engine == "ai") translator.translatePage(base, forcedLang)
                            else translator.translatePageOnline(base, forcedLang)).also {
                            if (it.hasUsableText) TranslationCache.write(appContext, engineKey, it)
                        }
                }
                val t2 = android.os.SystemClock.elapsedRealtime()
                if (!translation.hasUsableText) {
                    bakedKeys.add(cacheKey)     // 空结果也标记，避免反复重试
                    android.util.Log.w("MTPerf", "page=${cacheKey.take(40)} engine=$selectedEngine NO-USABLE-TEXT total=${t2 - t0}ms regions=${translation.regions.size}")
                    return@launch
                }
                val baked = BubblePipeline.bake(
                    base,
                    translation.regions.map { r ->
                        BubblePipeline.Region(r.rect, r.contour, r.lineRects, r.vertical) to r.translated
                    },
                    textScale,
                )
                val t3 = android.os.SystemClock.elapsedRealtime()
                bakedKeys.add(cacheKey)
                publishBaked(baked)
                epoch.value = epoch.value + 1
                android.util.Log.d(
                    "MTPerf",
                    "page=${cacheKey.take(40)} engine=$selectedEngine cached=${cached != null} " +
                        "load=${t1 - t0}ms translate=${t2 - t1}ms bake=${t3 - t2}ms total=${t3 - t0}ms " +
                        "regions=${translation.regions.size} ok=${translation.regions.count { it.translated.isNotBlank() }} " +
                        "baseSameAsBaked=${base === baked}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 翻页/换章导致的正常取消：静默重抛让协程正常结束（不是失败）
                throw e
            } catch (e: Throwable) {
                // 单页失败静默（保留原文）；下次进入该页会重试
                android.util.Log.w("MTPerf", "page=${cacheKey.take(40)} engine=$selectedEngine FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)} elapsed=${android.os.SystemClock.elapsedRealtime() - t0}ms")
            } finally {
                busyKeys.value = busyKeys.value - cacheKey
                jobs.remove(cacheKey)
            }
        }
    }

    /** 字号缩放变化：清烘焙标记（缓存 JSON 仍有效，重烘焙很快）。 */
    fun onTextScaleChanged() {
        bakedKeys.clear()
    }

    /** 已烘焙页快照（关闭翻译时供调用方逐页解除缓存）。 */
    fun takeBakedKeys(): Set<String> = bakedKeys.toSet()

    /** 清空烘焙标记（配合 evictProcessed 使用）。 */
    fun resetMarks() {
        bakedKeys.clear()
    }

    /** 手动触发 UI 重播种（缓存替换/失效后）。 */
    fun bumpEpoch() {
        epoch.value = epoch.value + 1
    }

    /** 某页缓存位图被外部替换/失效（如配置变更）时解除烘焙标记。 */
    fun onBitmapInvalidated(cacheKey: String) {
        bakedKeys.remove(cacheKey)
        jobs.remove(cacheKey)?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        busyKeys.value = emptySet()
    }

    /**
     * 完整释放翻译引擎（退出阅读器/关闭翻译时）：
     * 取消任务 → 后台等在飞 native 推理归零 → 清引用 → 关全部 ONNX 会话。
     * native 推理不可被取消打断，立即 close 运行中的会话会 FORTIFY abort
     * （退出阅读器实测），故关闭必须延迟到推理结束之后。
     */
    fun release() {
        cancelAll()
        releasePending = true
        Thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (translator.inFlightInference() > 0 && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150) } catch (_: InterruptedException) { return@Thread }
            }
            if (!releasePending) return@Thread // 期间又有新翻译任务，放弃关闭
            if (translator.inFlightInference() > 0) {
                // 兜底：推理卡死 60s——放弃关闭（保留会话），避免 close 运行中会话导致 abort
                android.util.Log.w("MTPerf", "release: inference still in flight, skip session close")
                return@Thread
            }
            translator.release()
            OrtSessions.closeAll()
        }.apply {
            isDaemon = true
            name = "mt-onnx-release"
            start()
        }
    }
}
