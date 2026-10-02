package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * 新反馈 8 条的验收单测（第 1/2/3/5 条可单测面）：
 * - 第 1 条：curlPageRects——纸张矩形=漫画本体适配矩形（单/双页 × RTL/LTR ×
 *   gap/align/shift/缺槽），不再整幅屏幕；
 * - 第 2 条：adjacentBackFlat——双页背面=目标 spread 同槽位（flat 奇偶定方向，
 *   StPageFlip front/back 两张不同页图模型）；
 * - 第 5 条：四档增强输出恒 ≥ 原分辨率（回程往返降级病根防线）+ 量化指标
 *   （拉普拉斯方差/双线性基准对比）+ 非恒等变换断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicNewFeedbackTest {

    /* ── 第 1 条：curlPageRects ───────────────────────────── */

    private fun slot(id: String, raw: Int) = ComicSlot(
        ref = ComicPageRef.Local(id = id, path = "/x/$id.png"),
        rawIndex = raw,
    )

    private fun doubleSpread() = ComicSpread(
        index = 1,
        slots = listOf(slot("a", 0), slot("b", 1)),
    )

    @Test
    fun `双页矩形 - LTR 首槽在左且两矩形分居中线两侧`() {
        // 800×1200 与 800×1200 两页，容器 1080×2200，gap 0：每页 fitOne = 540×810 居中
        val rects = curlPageRects(
            twoPage = true, spread = doubleSpread(),
            config = ComicReaderConfig(direction = ComicDirection.LTR, doubleGapDp = 0f),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { Size(800f, 1200f) },
        )!!
        val (l, r) = rects
        assertTrue("LTR 首槽（阅读顺序第一页）应在左半屏", l.centerX() < 540f)
        assertTrue("次槽应在右半屏", r.centerX() > 540f)
        assertEquals("矩形宽=fitOne 半宽", 540f, l.width(), 1f)
        assertEquals(540f, r.width(), 1f)
        assertEquals("两页等高 contain", 810f, l.height(), 1f)
        // 纸张不再是整幅屏幕（第 1 条核心断言）
        assertTrue("右矩形宽必须小于屏宽（背景不卷入）", r.width() < 1080f)
        assertTrue("右矩形高必须小于屏高", r.height() < 2200f)
        // 垂直居中（默认 CENTER 对齐）
        assertEquals((2200f - 810f) / 2f, l.top, 1f)
    }

    @Test
    fun `双页矩形 - RTL 首槽在右`() {
        val rects = curlPageRects(
            twoPage = true, spread = doubleSpread(),
            config = ComicReaderConfig(direction = ComicDirection.RTL),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { Size(800f, 1200f) },
        )!!
        val (l, r) = rects
        assertTrue("RTL 首读页（slots[0]）应在右半屏", r.centerX() > 540f)
        assertTrue(l.centerX() < 540f)
    }

    @Test
    fun `双页矩形 - gap 与 shift 与 align 生效`() {
        val cfg = ComicReaderConfig(
            direction = ComicDirection.LTR,
            doubleGapDp = 24f, doubleShiftXDp = 10f, doubleShiftYDp = 6f,
            doubleAlign = ComicDoubleAlign.BOTTOM,
        )
        val rects = curlPageRects(
            twoPage = true, spread = doubleSpread(), config = cfg,
            containerW = 1080f, containerH = 2200f, density = 2f,
            intrinsicOf = { Size(800f, 1200f) },
        )!!
        val (l, r) = rects
        // density=2：gap=48px；availW=(1080-48)/2=516；fitOne=516×774
        assertEquals(516f, l.width(), 1f)
        assertEquals("gap 距离", 48f, r.left - l.right, 2f)
        assertEquals("shiftY 底对齐", 2200f - 774f + 12f, l.top, 1f)
        assertEquals("shiftX=20px", (1080f - (516f * 2 + 48f)) / 2f + 20f, l.left, 1f)
    }

    @Test
    fun `双页矩形 - 缺失侧用伙伴同尺寸占位`() {
        val single = ComicSpread(index = 0, slots = listOf(slot("only", 0)))
        var callsForMissing: String? = null
        val rects = curlPageRects(
            twoPage = true, spread = single,
            config = ComicReaderConfig(direction = ComicDirection.LTR, doubleGapDp = 0f),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { s ->
                if (s.ref.id == "only") Size(800f, 1200f) else { callsForMissing = s.ref.id; null }
            },
        )!!
        assertEquals(540f, rects.first.width(), 1f)
        assertEquals(540f, rects.second.width(), 1f)
        assertEquals("缺失侧以伙伴尺寸占位", 540f, rects.second.width(), 1f)
        assertNull("单槽 spread 不应查询不存在的第二槽内禀", callsForMissing)
    }

    @Test
    fun `单页矩形 - FIT_PAGE contain 居中且非整幅`() {
        val spread = ComicSpread(index = 0, slots = listOf(slot("a", 0)))
        val rects = curlPageRects(
            twoPage = false, spread = spread,
            config = ComicReaderConfig(fit = ComicFit.FIT_PAGE),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { Size(900f, 1300f) },
        )!!
        val r = rects.second
        // scale = min(1080/900, 2200/1300) = 1.2 → 1080×1560，垂直居中
        assertEquals(1080f, r.width(), 2f)
        assertEquals(1560f, r.height(), 2f)
        assertEquals("垂直居中", (2200f - 1560f) / 2f, r.top, 1f)
    }

    @Test
    fun `单页矩形 - FIT_WIDTH 溢出容器（视口裁切语义）`() {
        val spread = ComicSpread(index = 0, slots = listOf(slot("a", 0)))
        val rects = curlPageRects(
            twoPage = false, spread = spread,
            config = ComicReaderConfig(fit = ComicFit.FIT_WIDTH),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { Size(900f, 1300f) },
        )!!
        val r = rects.second
        assertEquals("宽贴合容器", 1080f, r.width(), 2f)
        assertTrue("高自然溢出（1560 < 2200 此例不溢出，改用长条页验证）", r.height() == 1560f)
        val tall = curlPageRects(
            twoPage = false, spread = spread,
            config = ComicReaderConfig(fit = ComicFit.FIT_WIDTH),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { Size(900f, 2600f) },
        )!!
        assertEquals("长条页高溢出容器", 3120f, tall.second.height(), 2f)
        assertEquals("溢出时仍水平居中、垂直居中（负 top）", (2200f - 3120f) / 2f, tall.second.top, 1f)
    }

    @Test
    fun `未加载内禀时返回 null（整幅占位回退）`() {
        val rects = curlPageRects(
            twoPage = true, spread = doubleSpread(),
            config = ComicReaderConfig(),
            containerW = 1080f, containerH = 2200f, density = 1f,
            intrinsicOf = { null },
        )
        assertNull(rects)
    }

    /* ── 第 2 条：adjacentBackFlat（双页背面取值，第六轮第 3 条修正为 ±1 相邻页） ── */

    @Test
    fun `背面取值 - 奇数 flat 前进取 +1 真相邻页`() {
        // 物理书模型：spread [p0,p1] 前进翻起 p1（flat1），其背面是同一张纸的
        // 另一面 = 下一 spread 首页 p2（flat2）——不是 flat3（旧 ±2 的
        // "目标 spread 同槽位"是用户实测"背面短暂显示第 4 页"的错误来源）
        assertEquals(2, adjacentBackFlat(1, 8))
        assertEquals(4, adjacentBackFlat(3, 8))
    }

    @Test
    fun `背面取值 - 偶数 flat 回退取 -1 真相邻页`() {
        // 回退翻起 p2（flat2），背面 = 上一 spread 末页 p1（flat1）
        assertEquals(1, adjacentBackFlat(2, 8))
        assertEquals(5, adjacentBackFlat(6, 8))
    }

    @Test
    fun `背面取值 - 首 spread 回退与末 spread 前进越界为 null`() {
        assertNull(adjacentBackFlat(0, 8))   // 首页回退无目标
        assertNull(adjacentBackFlat(7, 8))   // 末页前进无目标
        assertNotNull(adjacentBackFlat(1, 4)) // 四页（两个 spread）时前进侧有目标
        assertNull(adjacentBackFlat(3, 4))   // 末 spread 前进无目标
    }

    @Test
    fun `背面取值 - RTL 端到端 composeAdjacentUnit 取到真相邻页位图`() {
        val controller = ComicHarismController().apply {
            twoPage = true
            reversed = true // RTL：flat = N-1-h
            flatUnits = buildCurlFlatUnits(
                ComicLayout(
                    spreads = listOf(
                        ComicSpread(0, listOf(slot("p0", 0), slot("p1", 1))),
                        ComicSpread(1, listOf(slot("p2", 2), slot("p3", 3))),
                        ComicSpread(2, listOf(slot("p4", 4), slot("p5", 5))),
                        ComicSpread(3, listOf(slot("p6", 6), slot("p7", 7))),
                    ),
                    rawToSpread = emptyMap(),
                ),
            )
            config = ComicReaderConfig()
        }
        // 每页塞一张可区分纯色位图（RGB_565 量化：通道 16 步长可无损往返）
        controller.flatUnits.forEachIndexed { i, s ->
            s?.let { controller.putCache(slotCacheKey(it, controller.config!!, ComicBookState()), solid(i * 16)) }
        }
        // RTL 下 harism h 的 flat = N-1-h：flat1（前进侧翻起页，页 p1）的背面
        // = 同一张纸的另一面 = flat2（页 p2，下一 spread 首页）
        val h = flatUnitIndexFor(1, 8, reversed = true) // = 6
        val back = controller.composeAdjacentUnit(h, 200, 300)!!
        val px = IntArray(1)
        back.getPixels(px, 0, 1, back.width / 2, back.height / 2, 1, 1)
        val got = px[0]
        val expect = 32 shr 3 shl 3   // flat2 × 16 = 32
        assertTrue(
            "背面必须采到 flat2 的真实内容（真相邻页），got=0x%06X".format(got and 0xFFFFFF),
            abs((got shr 16 and 0xFF) - expect) <= 2 &&
                abs((got shr 8 and 0xFF) - expect) <= 2 &&
                abs((got and 0xFF) - expect) <= 2,
        )
    }

    @Test
    fun `背面取值 - 用户场景逐帧推演（RTL 右1左2 前进，背面=第3页）`() {
        // 第六轮第 3 条验收场景：spread0 显示右1左2；前进翻起左页（页2，flat1），
        // 翻页动画每一帧背面都必须是第 3 页（flat2），不允许先显示第 4 页再纠正
        val flats = buildCurlFlatUnits(
            ComicLayout(
                spreads = listOf(
                    ComicSpread(0, listOf(slot("p1", 0), slot("p2", 1))),
                    ComicSpread(1, listOf(slot("p3", 2), slot("p4", 3))),
                    ComicSpread(2, listOf(slot("p5", 4), slot("p6", 5))),
                ),
                rawToSpread = emptyMap(),
            ),
        )
        // flat1 = 页2（前进侧）；背面必须是 flat2 = 页3
        assertEquals("p2", (flats[1] as ComicSlot).ref.id)
        assertEquals(2, adjacentBackFlat(1, flats.size))
        assertEquals("p3", (flats[adjacentBackFlat(1, flats.size)!!] as ComicSlot).ref.id)
        // 反向：从 spread1 翻回，翻起页3（flat2），背面必须是页2（flat1）
        assertEquals(1, adjacentBackFlat(2, flats.size))
        assertEquals("p2", (flats[adjacentBackFlat(2, flats.size)!!] as ComicSlot).ref.id)
    }

    private fun solid(tag: Int): Bitmap {
        val bmp = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(tag, tag, tag))
        return bmp
    }

    /* ── 第 5 条：四档增强输出分辨率与量化指标 ─────────────── */

    /** 合成漫画页：白底 + 多宽度黑线 + 网点 + 斜线（锯齿源），细节丰富 */
    private fun syntheticMangaPage(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { Color.WHITE }
        // 竖直线（1~4px 宽）
        var x = 12
        var lw = 1
        while (x < w - 8) {
            for (dx in 0 until lw) for (y in 0 until h) px[y * w + x + dx] = Color.BLACK
            x += lw * 3 + 17
            lw = lw % 4 + 1
        }
        // 横线（实线格线，宽 1~3px 轮换——真实漫画的框线）
        var y2 = 20
        var hw = 1
        while (y2 < h - 6) {
            for (dy in 0 until hw) for (dx in 0 until w) px[(y2 + dy) * w + dx] = Color.BLACK
            y2 += 41 + hw
            hw = hw % 3 + 1
        }
        // 网点块（3×3 间隔棋盘）
        for (cy in h * 55 / 100 until h * 62 / 100 step 3) {
            for (cx in w * 20 / 100 until w * 80 / 100 step 3) {
                if ((cx / 3 + cy / 3) % 2 == 0) px[cy * w + cx] = Color.BLACK
            }
        }
        // 斜线（锯齿源）
        for (i in 0 until w + h) {
            val xx = i * w / (w + h)
            val yy = i * h / (w + h)
            if (yy in 0 until h && xx in 0 until w) px[yy * w + xx] = Color.BLACK
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * 锐度密度 = 拉普拉斯响应 RMS（边缘密度量化）。
     * [yFrac] 限定度量区域（默认上半——线条区；网点带在 55%-62% 高度，CAS 对
     * 网点做的是降噪，混入度量会把"锐度提升"错判为下降）。
     */
    private fun laplacianVariance(bmp: Bitmap, yFrac: Float = 0.5f): Double {
        val w = bmp.width; val h = bmp.height
        val yMax = (h * yFrac).toInt().coerceIn(2, h - 1)
        val px = IntArray(w * yMax)
        bmp.getPixels(px, 0, w, 0, 0, w, yMax)
        fun lum(i: Int) = ((px[i] shr 16 and 0xFF) * 299 + (px[i] shr 8 and 0xFF) * 587 + (px[i] and 0xFF) * 114) / 1000.0
        var sumSq = 0.0; var n = 0
        for (y in 1 until yMax - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = 4 * lum(i) - lum(i - 1) - lum(i + 1) - lum(i - w) - lum(i + w)
            sumSq += lap * lap; n++
        }
        return kotlin.math.sqrt(sumSq / n)
    }

    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val w = max(a.width, b.width); val h = max(a.height, b.height)
        var diff = 0L
        for (y in 0 until h step 3) for (x in 0 until w step 3) {
            val pa = a.getPixel(x * a.width / w, y * a.height / h)
            val pb = b.getPixel(x * b.width / w, y * b.height / h)
            diff += abs((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF))
        }
        return diff.toDouble()
    }

    private fun toning(mode: ComicEnhanceMode) = ComicImagePipeline.Toning(
        enhanceMode = mode, enhanceStrength = 60,
    )

    @Test
    fun `四档增强 - 输出恒不小于原始分辨率`() {
        // 1900×2600（长边 > CNN 预算，覆盖钳制回程路径）
        val src = syntheticMangaPage(600, 820)
        val big = Bitmap.createScaledBitmap(src, 1425, 1950, true)
        for (mode in listOf(
            ComicEnhanceMode.CAS, ComicEnhanceMode.ANIME4K,
            ComicEnhanceMode.WAIFU2X, ComicEnhanceMode.SUPER_RES,
        )) {
            val out = ComicImagePipeline.process(big, ComicImagePipeline.Geometry(), toning(mode))
            assertTrue("$mode 输出宽 ${out.width} ≥ 原始 ${big.width}", out.width >= big.width)
            assertTrue("$mode 输出高 ${out.height} ≥ 原始 ${big.height}", out.height >= big.height)
        }
    }

    /**
     * 模拟在线低质页（增强的真实对象）：2x 绘制 → 0.45x 重采样 → 放大回 1x。
     * 两轮重采样使所有边缘软化（对齐错开产生真实 AA），等效于低码率在线图——
     * 硬边图增强无可作用空间，软化页才能量化"线条更锐利、锯齿减少"。
     */
    private fun realisticPage(w: Int, h: Int): Bitmap {
        val hi = syntheticMangaPage(w * 2, h * 2)
        val low = Bitmap.createScaledBitmap(hi, (w * 0.45f).toInt(), (h * 0.45f).toInt(), true)
        return Bitmap.createScaledBitmap(low, w, h, true)
    }

    /** 软化页 + 高斯噪声（压缩噪点，降噪档的量化对象） */
    private fun noisyPage(w: Int, h: Int, sigma: Int, seed: Long = 7): Bitmap {
        val base = realisticPage(w, h)
        val bw = base.width; val bh = base.height
        val px = IntArray(bw * bh)
        base.getPixels(px, 0, bw, 0, 0, bw, bh)
        val rnd = java.util.Random(seed)
        for (i in px.indices) {
            val n = (rnd.nextGaussian() * sigma).toInt().coerceIn(-70, 70)
            val c = px[i]
            fun ch(sh: Int) = ((c shr sh) and 0xFF) + n
            px[i] = (0xFF shl 24) or (ch(16).coerceIn(0, 255) shl 16) or
                (ch(8).coerceIn(0, 255) shl 8) or ch(0).coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, bw, 0, 0, bw, bh)
        return out
    }

    /**
     * 平坦区噪声 RMS：以[flatMaskSource]判定平坦像素——"远离任何强边缘 ≥5px"，
     * 避免缓坡边缘尾部落入掩码（软页坡极缓、局部极差可 <2 但仍在坡上，增强对坡
     * 尾的锐化会被误判为噪声）；度量各图在这些像素上偏离 3x3 均值的起伏。
     */
    private fun flatNoiseRms(bmp: Bitmap, flatMaskSource: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val ref = IntArray(w * h); flatMaskSource.getPixels(ref, 0, w, 0, 0, w, h)
        fun lum(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000.0
        val l = DoubleArray(w * h) { lum(ref[it]) }
        // 强边缘指示（3x3 极差 ≥ 8）→ 膨胀 5px
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var mn = 1e9; var mx = -1e9
            for (dy in -1..1) for (dx in -1..1) {
                val v = l[i + dy * w + dx]; if (v < mn) mn = v; if (v > mx) mx = v
            }
            if (mx - mn >= 8.0) edge[i] = true
        }
        val dil = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!edge[y * w + x]) continue
            for (dy in -5..5) for (dx in -5..5) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) dil[yy * w + xx] = true
            }
        }
        var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (dil[i]) continue
            val mean = (0..2).sumOf { dy -> (0..2).sumOf { dx -> lum(px[i + (dy - 1) * w + dx - 1]) } } / 9.0
            val d = lum(px[i]) - mean
            sq += d * d; n++
        }
        return kotlin.math.sqrt(sq / n.coerceAtLeast(1))
    }

    @Test
    fun `锐化档 - 软化页锐度密度显著提升`() {
        val src = realisticPage(480, 660)
        val base = laplacianVariance(src)
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(ComicEnhanceMode.CAS))
        val v = laplacianVariance(out)
        assertTrue("CAS 锐度密度 $v 应显著高于原始 $base（≥1.1×）", v >= base * 1.1)
        assertTrue("CAS 必须真实改动画面（非恒等）", meanAbsDiff(src, out) > 1.0)
    }

    /* ── 第 4/5 条终审补强（B 路判定差距后的返工验收） ────── */

    @Test
    fun `轻量档 - 增强差异人眼可辨（差异幅度与边缘锐度）`() {
        // 终审 B 路判定旧版 ANIME4K 档与原始"几乎不可辨"，不满足"一眼看出差异"硬要求；
        // 返工 = 线深系数叠加 kExtra + 边缘掩码 CAS。本测试锁定差异幅度与锐度方向。
        val src = realisticPage(480, 660)
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(ComicEnhanceMode.ANIME4K))
        val diff = meanAbsDiff(src, out)
        val v = laplacianVariance(out)
        val base = laplacianVariance(src)
        assertTrue("ANIME4K 与原始平均差异 $diff 应 ≥3.0（人眼可辨下限）", diff >= 3.0)
        assertTrue("ANIME4K 边缘锐度 $v 应 ≥ 原始 $base×1.08（线重建+边缘锐化方向）", v >= base * 1.08)
    }

    @Test
    fun `CURL 占位纹理保持纸底并请求补纹理`() {
        // 加载动画由 Compose 统一提供；GL 不重复绘制静态圈。
        val controller = ComicHarismController().apply {
            config = ComicReaderConfig()
            currentSpreadHint = 0
            layout = ComicLayout(listOf(
                ComicSpread(0, listOf(slot("p0", 0))),
                ComicSpread(1, listOf(slot("p1", 1))),
            ), emptyMap())
        }
        val bmp = controller.composeSpread(0, 400, 600)!!
        assertEquals(400, bmp.width)
        assertEquals(bmp.getPixel(2, 2), bmp.getPixel(200, 300))
        assertTrue(controller.pendingRetexture)
        controller.pendingRetexture = false
        controller.twoPage = true
        controller.reversed = true
        controller.flatUnits = buildCurlFlatUnits(controller.layout!!)
        val unit = controller.composeUnit(0, 200, 300)!!
        assertEquals(unit.getPixel(2, 2), unit.getPixel(100, 150))
        assertTrue(controller.pendingRetexture)
    }

    @Test
    fun `轻量档 - 压缩噪声显著清除（平坦区噪声 RMS）`() {
        val clean = realisticPage(480, 660)
        // σ=8：轻度压缩噪声（Anime4K Restore 网络的适用输入域；重度 iid 噪声属分布外）
        val src = noisyPage(480, 660, sigma = 8)
        val base = flatNoiseRms(src, clean)
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(ComicEnhanceMode.ANIME4K))
        val v = flatNoiseRms(out, clean)
        assertTrue(
            "Anime4K 轻量档平坦区噪声 RMS $v 应显著低于原始 $base（≤0.75×，降噪可感）",
            v <= base * 0.75,
        )
        assertTrue("ANIME4K 必须真实改动画面（非恒等）", meanAbsDiff(src, out) > 1.0)
    }

    @Test
    fun `超分档 - 锐度密度显著高于朴素 Lanczos 2x 基准`() {
        val src = realisticPage(480, 660)
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(ComicEnhanceMode.SUPER_RES))
        val naive = ComicImagePipeline.lanczosScale(src, 2f)
        val vOut = laplacianVariance(out)
        val vNaive = laplacianVariance(naive)
        assertTrue(
            "超分输出锐度密度 $vOut 应高于 Lanczos 朴素放大 $vNaive（≥1.1×）",
            vOut >= vNaive * 1.1,
        )
        assertTrue(meanAbsDiff(naive, out) > 1.0)
    }

    @Test
    fun `完整档超分 - 清晰度显著优于双线性 2x 模糊基准`() {
        val src = realisticPage(480, 660)
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(ComicEnhanceMode.WAIFU2X))
        val naive = Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true)
        val vOut = laplacianVariance(out)
        val vNaive = laplacianVariance(naive)
        assertTrue(
            "CNN 超分清晰度 $vOut 应高于双线性基准 $vNaive（≥1.3×）",
            vOut >= vNaive * 1.3,
        )
        assertTrue("输出应为 2x", out.width == src.width * 2 && out.height == src.height * 2)
    }

    /* ── 第 5 条证据输出：四档增强前/后裁剪对比图（Agent B 视觉证据） ── */

    @Test
    fun `证据输出 - 四档增强前后对比图`() {
        val dir = File(
            "C:/Users/GuanXingRen/Downloads/novel-reader (1)/visual-evidence/nf/enhance"
        )
        if (!dir.exists()) return  // 证据目录不存在时跳过（CI 环境零依赖）
        dir.mkdirs()
        val src = realisticPage(480, 660)
        // 原图
        File(dir, "crop_original.png").writeBytes(pngOf(crop(src, 60, 210)))
        for (mode in listOf(
            ComicEnhanceMode.CAS, ComicEnhanceMode.ANIME4K,
            ComicEnhanceMode.WAIFU2X, ComicEnhanceMode.SUPER_RES,
        )) {
            val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), toning(mode))
            // 放大 2x 的裁剪（细节可见度）——输出与原图同缩放基准（按输出/输入宽比归一）
            val ratio = out.width.toFloat() / src.width
            val c = crop(out, (60 * ratio).toInt(), (210 * ratio).toInt())
            val zoom = Bitmap.createScaledBitmap(c, c.width * 2, c.height * 2, true)
            File(dir, "crop_${mode.name.lowercase()}.png").writeBytes(pngOf(zoom))
        }
    }

    private fun crop(b: Bitmap, x: Int, y: Int): Bitmap {
        val w = (b.width * 0.35f).toInt().coerceAtLeast(8)
        val h = (b.height * 0.30f).toInt().coerceAtLeast(8)
        return Bitmap.createBitmap(b, x.coerceIn(0, b.width - w), y.coerceIn(0, b.height - h), w, h)
    }

    private fun pngOf(b: Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
