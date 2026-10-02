package com.example.mangatranslate

import android.graphics.Bitmap
import androidx.core.graphics.get
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 气泡区域检测与归并（第十六轮，精准复刻原仓库 PageRegionDetector 语义）：
 *
 * - YOLO 气泡分割给出"气泡级"区域（形状轮廓 maskContour）；
 * - PP-OCR 文字行检测给出"文字行级"矩形；
 * - 行归属：行完整落在气泡内（或 ≥50% 面积相交）→ 归属该气泡（lineBelongsToRegion）；
 *   与任何气泡相交的行从"游离文字"中剔除（shouldFilterTextRectByBubble，IoU≥0.2）；
 * - 游离文字行按第十五轮的行带聚类成块。
 *
 * 译文渲染（BubbleRenderer 语义）：
 * - 气泡：maskContour → Path 填充（背景色采样），文字排在气泡内最大内接矩形；
 * - 游离文字块：圆角矩形覆盖（第十五轮逻辑）。
 */
object BubblePipeline {

    /* ── 区域模型 ── */

    /** 一个翻译区域：气泡（带形状）或游离文字块（矩形）。 */
    data class Region(
        val rect: RectF,
        val maskContour: FloatArray?,     // 归一化多边形（气泡）；null=游离文字块
        val lines: List<RectF>,           // 归属的文字行（OCR 用）
        val vertical: Boolean,            // 文字排布方向（行形状启发式）
    )

    /** 气泡内行归属（原仓库 lineBelongsToRegion）：包含 或 相交面积≥50%。 */
    internal fun lineBelongsToRegion(line: RectF, bubble: RectF): Boolean {
        if (contains(bubble, line)) return true
        val lineArea = area(line)
        if (lineArea <= 0f) return false
        return interArea(line, bubble) / lineArea >= 0.5f
    }

    /** 行与气泡的抑制判定（shouldFilterTextRectByBubble）：IoU≥0.2 或被包含。 */
    internal fun shouldFilterTextRectByBubble(line: RectF, bubble: RectF): Boolean {
        val inter = interArea(line, bubble)
        if (inter <= 0f) return false
        val union = area(line) + area(bubble) - inter
        if (union > 0f && inter / union >= 0.2f) return true
        return contains(bubble, line)
    }

    /**
     * 归并：气泡（YOLO）+ 文字行（Paddle）→ 区域列表。
     * 游离行（不属于任何气泡、不被气泡抑制）按行带聚类。
     */
    fun buildRegions(
        bubbles: List<BubbleDetector.Detection>,
        textLines: List<RectF>,
    ): List<Region> {
        val regions = ArrayList<Region>(bubbles.size + 4)
        val free = ArrayList<RectF>()
        for (line in textLines) {
            val host = bubbles.firstOrNull { lineBelongsToRegion(line, it.rect) }
            if (host == null) {
                // 不被任何气泡抑制（IoU/包含）才保留为游离行
                val suppressed = bubbles.any { shouldFilterTextRectByBubble(line, it.rect) }
                if (!suppressed) free.add(line)
            }
        }
        for (bubble in bubbles) {
            val own = textLines.filter { lineBelongsToRegion(it, bubble.rect) }
            val vertical = own.isNotEmpty() &&
                own.count { it.height() > it.width() * 1.5f } > own.size / 2
            regions.add(Region(bubble.rect, bubble.maskContour, own, vertical))
        }
        regions.addAll(TextBlockGrouper.group(free).map { block ->
            Region(block.rect, null, block.lines, block.vertical)
        })
        return regions
    }

    /* ── 形状渲染（原仓库 BubbleShapePaths/BubbleColorSampler/BubbleTextScaling） ── */

    private const val SAMPLE_STEP = 4
    private const val DARK_BACKGROUND_MAX_CHANNEL = 72
    private const val INK_VALUE_GAP = 32
    private const val MAX_INK_VALUE = 160
    private const val DEFAULT_TEXT_COLOR = 0xFF1B1B1B.toInt()
    private const val MIN_TEXT_SIZE_PX = 0.5f
    private const val TEXT_SIZE_PRECISION_PX = 0.25f

    fun bake(base: Bitmap, regions: List<Pair<Region, String>>, textScale: Float): Bitmap {
        PageMemoryBudget.check(base)
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        // 背景采样用未污染快照（重叠气泡不采样到先前填充）
        val sampling = base.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(out)
        try {
            for ((region, translated) in regions) {
                val text = translated.trim()
                if (text.isEmpty()) continue
                if (region.maskContour != null && region.maskContour.size >= 6) {
                    drawBubbleShape(canvas, sampling, region, text, textScale)
                } else {
                    drawFreeBlock(canvas, region, text, textScale)
                }
            }
        } finally {
            sampling.recycle()
        }
        return out
    }

    /**
     * 气泡：轮廓 Path 填充 + 译文锚定原文位置排字（第十八轮）。
     * 排版区 = max(内接矩形 ∩ 原文行包围盒)：译文落回原文所在的位置，
     * 而不是气泡几何中心——多行气泡/偏置气泡读感与原版对齐。
     */
    private fun drawBubbleShape(
        canvas: Canvas,
        sampling: Bitmap,
        region: Region,
        text: String,
        textScale: Float,
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val path = contourToPath(region.maskContour!!, w, h)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= 1f || bounds.height() <= 1f) return

        val bg = sampleBackgroundColor(sampling, bounds) ?: Color.WHITE
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
        canvas.drawPath(path, fill)

        val textRect = insetTextBounds(path, bounds)
        if (textRect.width() <= 2f || textRect.height() <= 2f) return
        val anchor = anchorRect(textRect, region.lines)
        val textColor = contrastingTextColor(bg)
        drawTextHorizontal(canvas, text, anchor, textScale, textColor, medianLineHeight(region.lines))
    }

    /** 排版锚定：原文行包围盒与安全区求交；交集过小（<40%）时退回安全区居中。 */
    internal fun anchorRect(safe: RectF, lines: List<RectF>): RectF {
        if (lines.isEmpty()) return safe
        val box = RectF(lines.first())
        for (i in 1 until lines.size) box.union(lines[i])
        val inter = RectF(
            max(safe.left, box.left), max(safe.top, box.top),
            min(safe.right, box.right), min(safe.bottom, box.bottom),
        )
        if (inter.width() <= 4f || inter.height() <= 4f) return safe
        val boxArea = box.width() * box.height()
        if (boxArea <= 0f) return safe
        val coverage = (inter.width() * inter.height()) / boxArea
        if (coverage < 0.4f) return safe
        // 交集略外扩（原文行紧贴时译文太挤），但仍限制在安全区内
        val growX = inter.width() * 0.10f
        val growY = inter.height() * 0.10f
        val grown = RectF(
            (inter.left - growX).coerceAtLeast(safe.left),
            (inter.top - growY).coerceAtLeast(safe.top),
            (inter.right + growX).coerceAtMost(safe.right),
            (inter.bottom + growY).coerceAtMost(safe.bottom),
        )
        return grown
    }

    /** 游离文字块：圆角矩形覆盖（第十五轮行为）+ 译文字号锚定原文行高。 */
    private fun drawFreeBlock(canvas: Canvas, region: Region, text: String, textScale: Float) {
        val rect = region.rect
        if (rect.width() < 6f || rect.height() < 6f) return
        val pad = min(rect.width(), rect.height()) * 0.06f
        val inner = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        if (inner.width() < 4f || inner.height() < 4f) return
        val corner = min(10f, min(rect.width(), rect.height()) * 0.14f)
        canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDFBF7.toInt() })
        canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x14262626
        })
        drawTextHorizontal(canvas, text, inner, textScale, DEFAULT_TEXT_COLOR, medianLineHeight(region.lines))
    }

    /** 原文行高中位数（第十九轮）：译文字号的锚——译文不得比原文显眼得多。 */
    internal fun medianLineHeight(lines: List<RectF>): Float {
        if (lines.isEmpty()) return 0f
        val heights = lines.map { it.height() }.sorted()
        return heights[heights.size / 2]
    }

    internal fun contourToPath(contour: FloatArray, w: Float, h: Float): Path {
        val path = Path()
        path.moveTo(contour[0] * w, contour[1] * h)
        var i = 2
        while (i + 1 < contour.size) {
            path.lineTo(contour[i] * w, contour[i + 1] * h)
            i += 2
        }
        path.close()
        return path
    }

    /**
     * 气泡内文字安全矩形（原仓库 estimateSafeTextRect）：把 Path 光栅化到
     * ≤96px 蒙版，找最大内接矩形（柱状图法），按面积×长短边平衡评分。
     */
    internal fun insetTextBounds(path: Path, pathBounds: RectF): RectF {
        val fallbackPad = (min(pathBounds.width(), pathBounds.height()) * 0.08f).coerceAtLeast(6f)
        val inset = RectF(pathBounds)
        inset.inset(fallbackPad, fallbackPad)
        if (inset.width() <= 0f || inset.height() <= 0f) return RectF(pathBounds)

        val maxMask = 96
        val maskW = pathBounds.width().toInt().coerceIn(16, maxMask)
        val maskH = pathBounds.height().toInt().coerceIn(16, maxMask)
        val mask = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        val maskPath = Path(path)
        val m = Matrix().apply {
            postTranslate(-pathBounds.left, -pathBounds.top)
            postScale((maskW - 1).toFloat() / pathBounds.width().coerceAtLeast(1f),
                (maskH - 1).toFloat() / pathBounds.height().coerceAtLeast(1f))
        }
        maskPath.transform(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        mc.drawPath(maskPath, paint)
        val pixels = IntArray(maskW * maskH)
        mask.getPixels(pixels, 0, maskW, 0, 0, maskW, maskH)
        mask.recycle()
        val filled = BooleanArray(maskW * maskH) { i -> (pixels[i] ushr 24) >= 224 }

        val rectMask = findLargestFilledRect(filled, maskW, maskH) ?: return inset
        val wScale = pathBounds.width() / maskW.toFloat()
        val hScale = pathBounds.height() / maskH.toFloat()
        val extraX = min(fallbackPad, pathBounds.width() * 0.12f)
        val extraY = min(fallbackPad, pathBounds.height() * 0.12f)
        val safe = RectF(
            pathBounds.left + rectMask.left * wScale + extraX * 0.35f,
            pathBounds.top + rectMask.top * hScale + extraY * 0.35f,
            pathBounds.left + rectMask.right * wScale - extraX * 0.35f,
            pathBounds.top + rectMask.bottom * hScale - extraY * 0.35f,
        )
        return if (safe.width() > pathBounds.width() * 0.18f && safe.height() > pathBounds.height() * 0.18f) safe
        else inset
    }

    internal fun findLargestFilledRect(filled: BooleanArray, width: Int, height: Int): RectF? {
        val heights = IntArray(width)
        val stack = IntArray(width + 1)
        var best: RectF? = null
        var bestScore = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                heights[x] = if (filled[y * width + x]) heights[x] + 1 else 0
            }
            var stackSize = 0
            var x = 0
            while (x <= width) {
                val cur = if (x == width) 0 else heights[x]
                if (stackSize == 0 || cur >= heights[stack[stackSize - 1]]) {
                    stack[stackSize++] = x; x++
                } else {
                    val top = stack[--stackSize]
                    val rectH = heights[top]
                    if (rectH <= 0) continue
                    val right = x
                    val left = if (stackSize == 0) 0 else stack[stackSize - 1] + 1
                    val wRect = right - left
                    val minSide = min(wRect, rectH).toFloat()
                    val maxSide = max(wRect, rectH).toFloat().coerceAtLeast(1f)
                    val score = wRect * rectH * (minSide / maxSide).coerceIn(0.35f, 1f)
                    if (score > bestScore) {
                        bestScore = score
                        best = RectF(left.toFloat(), (y - rectH + 1).toFloat(), right.toFloat(), (y + 1).toFloat())
                    }
                }
            }
        }
        return best
    }

    /** 气泡背景色采样（原仓库 averagePixels）：直方图去墨，暗底/亮底双策略。 */
    internal fun sampleBackgroundColor(bitmap: Bitmap, bounds: RectF): Int? {
        val bw = bitmap.width; val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return null
        val left = bounds.left.toInt().coerceIn(0, bw - 1)
        val top = bounds.top.toInt().coerceIn(0, bh - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bw)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bh)
        if (right <= left || bottom <= top) return null

        val histogram = IntArray(256)
        val rSum = LongArray(256); val gSum = LongArray(256); val bSum = LongArray(256)
        var samples = 0; var dark = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val p = bitmap[x, y]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val v = max(r, max(g, b))
                histogram[v]++; rSum[v] += r.toLong(); gSum[v] += g.toLong(); bSum[v] += b.toLong()
                samples++
                if (v <= DARK_BACKGROUND_MAX_CHANNEL) dark++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (samples == 0) return null
        val darkBackground = dark * 2 >= samples
        val cutoff: Int = if (darkBackground) {
            DARK_BACKGROUND_MAX_CHANNEL
        } else {
            var seen = 0; val mid = (samples - 1) / 2; var median = 0
            for (v in 0..255) { seen += histogram[v]; if (seen > mid) { median = v; break } }
            (median - INK_VALUE_GAP).coerceIn(0, MAX_INK_VALUE)
        }
        var r = 0L; var g = 0L; var b = 0L; var cnt = 0
        for (v in 0..255) {
            val include = if (darkBackground) v <= cutoff else v > cutoff
            if (include) { r += rSum[v]; g += gSum[v]; b += bSum[v]; cnt += histogram[v] }
        }
        if (cnt == 0) return null
        return Color.rgb((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
    }

    internal fun contrastingTextColor(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance > 127f) DEFAULT_TEXT_COLOR else 0xFFF4F4F2.toInt()
    }

    /** 横排填满（原仓库 findAutoHorizontalTextSize 二分）。竖排沿用第十五轮列排版。 */
    internal fun drawTextHorizontal(
        canvas: Canvas,
        text: String,
        rect: RectF,
        textScale: Float,
        textColor: Int,
        /** 原文行高（第十九轮）：译文字号上限锚定原文——否则长文本译文会撑满整页盖掉排版。 */
        refLineHeight: Float = 0f,
    ) {
        val maxW = rect.width().toInt().coerceAtLeast(1)
        val maxH = rect.height()
        if (rect.height() > rect.width() * 1.6f) {
            drawTextVertical(canvas, text, rect, textScale, textColor)
            return
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; typeface = Typeface.DEFAULT_BOLD }
        fun layoutAt(size: Float): StaticLayout {
            paint.textSize = size * textScale
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
        }
        fun fits(size: Float): Boolean {
            val l = layoutAt(size)
            if (l.height > maxH) return false
            for (i in 0 until l.lineCount) if (l.getLineWidth(i) > maxW + 0.5f) return false
            return true
        }
        var low = MIN_TEXT_SIZE_PX
        // 字号锚定原文（第十九轮核心修复）：上限 = 原文中位行高 × 1.15，
        // 译文与原文视觉重量一致、位置一致；放不下才按二分缩到可读下限。
        var high = max(maxW.toFloat(), maxH)
        if (refLineHeight > 4f) {
            high = (refLineHeight * 1.15f * textScale).coerceIn(MIN_ANCHORED_SIZE_PX, high)
        }
        if (!fits(low)) return
        var bestSize = low
        while (high - low > TEXT_SIZE_PRECISION_PX) {
            val mid = (low + high) / 2f
            if (fits(mid)) { bestSize = mid; low = mid } else high = mid
        }
        val layout = layoutAt(bestSize)
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left + (rect.width() - layout.width) / 2f,
            rect.top + (rect.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** 锚定模式下的字号下限（原文行高很小时译文仍需可读）。 */
    private const val MIN_ANCHORED_SIZE_PX = 10f

    /** 竖排（细高区域）：字上到下、列右到左（第十五轮实现迁移）。 */
    private fun drawTextVertical(canvas: Canvas, text: String, rect: RectF, textScale: Float, textColor: Int) {
        val chars = text.replace(Regex("\\s+"), "").toCharArray()
        if (chars.isEmpty()) return
        val maxW = rect.width(); val maxH = rect.height()
        var size = (maxW * 0.9f).coerceAtMost(maxH * 0.5f)
        val minSize = 7f
        var chosenSize = 0f; var perCol = 1; var step = 0f
        while (size >= minSize) {
            step = size * 1.18f
            perCol = max(1, (maxH / step).toInt())
            val cols = ceil(chars.size / perCol.toDouble()).toInt()
            if (cols * step <= maxW) { chosenSize = size; break }
            size -= max(1f, size * 0.08f)
        }
        if (chosenSize <= 0f) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = chosenSize * textScale; color = textColor
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val startX = rect.right - step / 2f
        var idx = 0; var col = 0
        while (idx < chars.size) {
            val x = startX - col * step
            var y = rect.top + chosenSize
            var inCol = 0
            while (idx < chars.size && inCol < perCol) {
                canvas.drawText(chars, idx, 1, x, y, paint)
                y += step; idx++; inCol++
            }
            col++
        }
    }

    /* ── 几何工具 ── */

    private fun area(r: RectF): Float = max(0f, r.width()) * max(0f, r.height())

    private fun interArea(a: RectF, b: RectF): Float =
        max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    private fun contains(outer: RectF, inner: RectF): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom
}
