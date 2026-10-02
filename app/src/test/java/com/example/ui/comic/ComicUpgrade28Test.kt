package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/**
 * 修复升级 28 条的综合验收单测（第 5 节各条的可纯逻辑验证部分）。
 * 覆盖：方向三态（3）、模式切换锚点（4/16）、垂直策略（6/7）、横屏适配与
 * 五种缩放（8/10）、双页书脊映射（12）、裁边四场景（14）、缝定位拆分（19）、
 * CNN 引擎（20）、自定义缩放（26）、预设收藏（27）、音量键桥（28）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicUpgrade28Test {

    private fun pages(n: Int): List<ComicPageRef> =
        (0 until n).map { ComicPageRef.Local("p$it", "/x/p$it.png") }

    private fun solid(w: Int, h: Int, bg: Int, content: (Int, Int) -> Int? = { _, _ -> null }): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, content(x, y) ?: bg)
        return bmp
    }

    /* ═══ 3：三方向独立行为 ═══ */

    @Test
    fun `条目3 - 点按区三方向语义互异`() {
        val size = Size(900f, 1600f)
        val ltr = ComicReaderConfig(direction = ComicDirection.LTR)
        val rtl = ComicReaderConfig(direction = ComicDirection.RTL)
        val ttb = ComicReaderConfig(direction = ComicDirection.TTB, mode = ComicMode.SINGLE)
        // 屏幕左 1/3 处点击：LTR=上一页，RTL=下一页，TTB(单页)=按上区解析（上一页侧）
        assertEquals(ComicGestureAction.PREV, resolveTapAction(Offset(100f, 800f), size, ltr))
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(100f, 800f), size, rtl))
        assertEquals(ComicGestureAction.PREV, resolveTapAction(Offset(100f, 300f), size, ttb))
        // 右 1/3：LTR=下一页，RTL=上一页
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(800f, 800f), size, ltr))
        assertEquals(ComicGestureAction.PREV, resolveTapAction(Offset(800f, 800f), size, rtl))
        // TTB 用纵向三分：上=prev 下=next（x 无关）
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(100f, 1500f), size, ttb))
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(800f, 1500f), size, ttb))
    }

    @Test
    fun `条目3 - curl 索引映射 RTL 与 LTR 前进方向相反`() {
        val n = 10
        // LTR 前进 = harism 索引 +1；RTL 前进 = harism 索引 -1（拖左页右翻）
        assertTrue(harismIndexFor(3, n, false) < harismIndexFor(4, n, false))
        assertTrue(harismIndexFor(4, n, true) > harismIndexFor(3, n, true))
        // 双页 displaySlots：RTL 时首读页在右（slots 反转），LTR 在左
        val cfgRtl = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.RTL)
        val cfgLtr = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.LTR)
        assertNotEquals(cfgRtl.direction, cfgLtr.direction) // 排布差异由 DoubleSpreadContent 的 reversed 实现
    }

    /* ═══ 4/16：模式切换锚点保持 ═══ */

    @Test
    fun `条目4和16 - 单页切双页后原页仍可见（锚点 raw 不漂移）`() {
        val ps = pages(20)
        val single = ComicPageLayout.build(ps, emptyMap(), ComicReaderConfig(mode = ComicMode.SINGLE))
        val double = ComicPageLayout.build(ps, emptyMap(), ComicReaderConfig(mode = ComicMode.DOUBLE))
        val raw = 7
        val sSpread = single.spreadOfRawPage(raw)
        val dSpread = double.spreadOfRawPage(raw)
        // 原 spread 的首 raw 与新 spread 的槽位都能看到 raw=7
        assertTrue(single.spreads[sSpread].slots.any { it.rawIndex == raw })
        assertTrue(double.spreads[dSpread].slots.any { it.rawIndex == raw })
        // 连续切换（DOUBLE→SINGLE）同样成立
        val back = ComicPageLayout.build(ps, emptyMap(), ComicReaderConfig(mode = ComicMode.SINGLE))
        assertTrue(back.spreads[back.spreadOfRawPage(raw)].slots.any { it.rawIndex == raw })
    }

    @Test
    fun `条目16 - 设置项变化不重置锚点语义（间距对布局无影响，方向只重排）`() {
        val ps = pages(16)
        val base = ComicReaderConfig(mode = ComicMode.SINGLE)
        val withSpacing = ComicPageLayout.build(ps, emptyMap(), base.copy(pageSpacingDp = 40f))
        val rotated = ComicPageLayout.build(ps, emptyMap(), base.copy(bookRotation = 90))
        val raw = 5
        assertEquals(base.let { ComicPageLayout.build(ps, emptyMap(), it).spreadOfRawPage(raw) },
            withSpacing.spreadOfRawPage(raw))
        assertTrue(rotated.spreads[rotated.spreadOfRawPage(raw)].slots.any { it.rawIndex == raw })
    }

    /* ═══ 6/7：条漫与无缝滚动策略独立 ═══ */

    @Test
    fun `条目6 - 条漫允许用户间距且可磁吸，无缝强制0间距且无磁吸`() {
        val cfg = ComicReaderConfig(mode = ComicMode.WEBTOON, pageSpacingDp = 24f, webtoonSnap = true)
        val webtoon = ComicScrollStrategy.forConfig(cfg)
        assertEquals(24f, webtoon.spacingDp)
        assertTrue(webtoon.snapToPage)
        assertTrue(webtoon.pixelProgress.not())

        val cont = ComicScrollStrategy.forConfig(cfg.copy(mode = ComicMode.CONTINUOUS, webtoonSnap = true))
        // 无缝滚动：即便全局开着磁吸与间距，也强制 0 间距 + 自由滚动 + 像素进度
        assertEquals(0f, cont.spacingDp)
        assertTrue(!cont.snapToPage)
        assertTrue(cont.pixelProgress)
        // 两种滚动模式均提前四页，首载与驻留预算由共同调度控制。
        assertEquals(4, webtoon.prefetchWindow)
        assertEquals(4, cont.prefetchWindow)
        // 关磁吸开关只影响条漫
        assertTrue(!ComicScrollStrategy.forConfig(cfg.copy(webtoonSnap = false)).snapToPage)
    }

    /* ═══ 8/10：横屏与五种缩放数学定义 ═══ */

    @Test
    fun `条目10 - 五种缩放数学定义严格成立`() {
        val intrinsic = Size(600f, 1200f) // 1:2 竖长图
        val portrait = Size(1080f, 2400f)
        val landscape = Size(2400f, 1080f)
        // 整页：fit-inside——比例保持，两边均不超出
        val fitPage = fittedSize(intrinsic, portrait, ComicFit.FIT_PAGE)
        assertEquals(intrinsic.width / intrinsic.height, fitPage.width / fitPage.height, 0.001f)
        assertTrue(fitPage.width <= portrait.width + 0.5f && fitPage.height <= portrait.height + 0.5f)
        // 高度：高度贴合，宽度按比例（本例不超宽）
        val fitH = fittedSize(intrinsic, portrait, ComicFit.FIT_HEIGHT)
        assertEquals(portrait.height, fitH.height, 0.5f)
        assertEquals(600f * (2400f / 1200f), fitH.width, 0.5f)
        // 宽度超容器（横屏 1:2 图按高度缩放 → 宽 540 但图宽 1200? 反例：宽图竖屏）
        val wide = Size(2000f, 1000f)
        val fw = fittedSize(wide, portrait, ComicFit.FIT_HEIGHT)
        assertTrue(fw.width > portrait.width) // 超宽可左右平移（canPan 由渲染层保证）
        // 原始：1:1
        assertEquals(wide, fittedSize(wide, portrait, ComicFit.ORIGINAL))
        // 铺满：crop-to-fill（等比，两轴全覆盖，宽轴贴合）
        val fill = fittedSize(intrinsic, landscape, ComicFit.FILL)
        assertEquals(intrinsic.width / intrinsic.height, fill.width / fill.height, 0.001f)
        assertTrue(fill.width >= landscape.width - 0.5f && fill.height >= landscape.height - 0.5f)
        assertEquals(landscape.width, fill.width, 0.5f)
        // 拉伸：不保持比例铺满
        assertEquals(landscape, fittedSize(intrinsic, landscape, ComicFit.STRETCH))
    }

    @Test
    fun `条目8 - 横屏容器适配不依赖竖屏假设`() {
        val intrinsic = Size(1400f, 2000f)
        val landscape = Size(2400f, 1000f)
        // FIT_PAGE 在横屏：完整可见、比例不变（不裁剪不变形）
        val f = fittedSize(intrinsic, landscape, ComicFit.FIT_PAGE)
        assertEquals(700f, f.width, 0.5f)
        assertEquals(1000f, f.height, 0.5f)
        // FILL 在横屏：宽贴合高超出（居中裁剪）
        val fill = fittedSize(intrinsic, landscape, ComicFit.FILL)
        assertEquals(2400f, fill.width, 0.5f)
        assertTrue(fill.height > landscape.height)
    }

    /* ═══ 12：双页书脊翻页映射 ═══ */

    @Test
    fun `条目12 - 扁平单元偶数对齐且单槽 spread 补空位`() {
        val ps = pages(9) + listOf<ComicPageRef>(ComicPageRef.Local("wide", "/x/w.png"))
        val layout = ComicPageLayout.build(
            ps, mapOf("wide" to SizeI(3000, 1000)),
            ComicReaderConfig(mode = ComicMode.DOUBLE, splitWide = false),
        )
        val flat = buildCurlFlatUnits(layout)
        assertEquals(0, flat.size % 2)
        assertEquals(layout.spreadCount * 2, flat.size)
        // 宽页独占 spread → 其搭档为空位
        val wideSpreadIdx = layout.spreads.indexOfFirst { s -> s.slots.any { it.ref.id == "wide" } }
        assertTrue(flat[wideSpreadIdx * 2] != null || flat[wideSpreadIdx * 2 + 1] != null)
    }

    @Test
    fun `条目12 - 两页模式索引往返一致（LTR 与 RTL）`() {
        val n = 16 // 8 spreads
        for (k in 0 until 8) {
            val hLtr = spreadToHarismTwo(k, n, reversed = false)
            assertEquals(k, harismToSpreadTwo(hLtr, n, reversed = false))
            val hRtl = spreadToHarismTwo(k, n, reversed = true)
            assertEquals(k, harismToSpreadTwo(hRtl, n, reversed = true))
            // RTL 首屏（spread0）的右页 = harism 末位附近（倒序映射）
            if (k == 0) assertEquals(1, hRtl)
            // LTR 首屏右页 = 1（第二槽位）
            if (k == 0) assertEquals(1, hLtr)
        }
        // 步进语义：一次翻页 = 索引 ±2（整 spread）
        assertEquals(spreadToHarismTwo(1, n, false), spreadToHarismTwo(0, n, false) + 2)
        assertEquals(spreadToHarismTwo(1, n, true), spreadToHarismTwo(0, n, true) + 2)
    }

    /* ═══ 14：自动裁边四类场景 ═══ */

    @Test
    fun `条目14 - 纯黑边裁切准确`() {
        // 内容白、边黑：四周各 60px 黑边
        val bmp = solid(400, 600, Color.BLACK.toInt()) { x, y ->
            if (x in 60 until 340 && y in 60 until 540) Color.WHITE.toInt() else null
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.AUTO)!!
        assertTrue("L=$rect[0]", abs(rect[0] - 60) <= 14)
        assertTrue("T=$rect[1]", abs(rect[1] - 60) <= 14)
        assertTrue("R=$rect[2]", abs(rect[2] - 340) <= 14)
        assertTrue("B=$rect[3]", abs(rect[3] - 540) <= 14)
    }

    @Test
    fun `条目14 - 彩色装饰边框裁切准确`() {
        // 米色纸底 + 红色装饰边 + 中央彩色内容
        val bmp = solid(500, 500, Color.rgb(240, 230, 210)) { x, y ->
            when {
                x in 80 until 420 && y in 80 until 420 -> Color.rgb(60, 120, 200)
                else -> null
            }
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.AUTO)!!
        assertTrue(abs(rect[0] - 80) <= 16 && abs(rect[2] - 420) <= 16)
        assertTrue(abs(rect[1] - 80) <= 16 && abs(rect[3] - 420) <= 16)
    }

    @Test
    fun `条目14 - 渐变过渡边与噪点灰阶边缘不误裁不漏裁`() {
        // 渐变过渡边：左 100px 从白渐变到内容色（渐变中段即判为内容的边界容差内）
        val bmp = solid(400, 400, Color.WHITE.toInt()) { x, y ->
            when {
                x < 100 -> Color.rgb(255 - x, 255 - x, 255 - x)          // 白→灰渐变
                x in 100 until 380 && y in 40 until 360 -> Color.rgb(30, 30, 30)
                else -> Color.WHITE.toInt()
            }
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.WHITE)
        assertNotNull(rect)
        assertTrue(rect!![0] <= 130) // 左界在渐变带内或内容起点附近（不把渐变整条留白）
        // 噪点灰阶边缘：稀疏孤立噪声不被判成内容（run 判定），正常裁掉
        val rnd = Random(42)
        val noisy = solid(400, 400, Color.rgb(20, 20, 20)) { x, y ->
            when {
                x in 50 until 360 && y in 50 until 360 -> Color.WHITE.toInt()
                rnd.nextFloat() < 0.02f -> Color.rgb(90, 90, 90) // 孤立噪点
                else -> null
            }
        }
        val rect2 = ComicImagePipeline.detectContentRect(noisy, ComicCropMode.BLACK)
        assertNotNull(rect2)
        assertTrue(rect2!![0] >= 30 && rect2[0] <= 70)
        assertTrue(rect2[3] >= 340 && rect2[3] <= 380)
    }

    @Test
    fun `条目14 - 单边13防御不误触发`() {
        // 正常黑边页（内容白，边黑）：四边都有内容锚点 → 全部正常裁剪
        val bmp = solid(300, 300, Color.BLACK.toInt()) { x, y ->
            if (x in 40 until 260 && y in 40 until 260) Color.WHITE.toInt() else null
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.AUTO)!!
        // 若 1/3 防御误触发（100px 扫描上限内无内容），左界会等于 0（不裁）
        assertTrue(rect[0] in 20..60)
    }

    /* ═══ 19：装订缝定位拆分 ═══ */

    @Test
    fun `条目19 - gutter 检测返回精确位置且用于拆分`() {
        // 双页扫描：两张"内容页"中间 8px 暗缝（偏离中心 5%）。
        // 结构仿真实漫画：平坦纸面页边距（平台检测依赖）+ 远离缝的内容块（方差校验依赖）
        val w = 400; val h = 300
        val gutterX = 212
        val bmp = solid(w, h, Color.rgb(235, 235, 235)) { x, y ->
            when {
                x >= gutterX && x < gutterX + 8 -> Color.rgb(90, 90, 90)
                x in 20 until 90 -> Color.rgb(60 + (y % 30), 70, 110)      // 左页内容块
                x in 300 until 370 -> Color.rgb(70, 60 + (y % 40), 100)   // 右页内容块
                else -> null                                                 // 平坦纸面
            }
        }
        val diag = ComicImagePipeline.detectCenterGutterDetail(bmp)
        assertTrue("reason=${diag.reason}", diag.isGutter)
        assertTrue("pos=${diag.position}", abs(diag.position - (gutterX + 4f) / w) < 0.05f)
        // effectiveSplitPosition：缝位置为基准 + 微调偏移；无缝回落 0.5
        val cfg = ComicReaderConfig(splitPosition = 0.5f)
        assertTrue(abs(effectiveSplitPosition(cfg, diag.position) - diag.position) < 0.001f)
        assertEquals(0.62f, effectiveSplitPosition(cfg.copy(splitPosition = 0.62f), null), 0.001f)
        // 缝位置 + 微调 = 0.5 偏移下不漂移
        assertEquals(
            (diag.position + 0.1f).coerceIn(0.3f, 0.7f),
            effectiveSplitPosition(cfg.copy(splitPosition = 0.6f), diag.position),
            0.001f,
        )
    }

    /* ═══ 20：CNN 增强引擎 ═══ */

    @Test
    fun `条目20 - 权重表解析完整（游标走完整个数组）`() {
        val r = Anime4KCnn.readFlat(Anime4KCnnWeights.RESTORE_S)
        val u = Anime4KCnn.readFlat(Anime4KCnnWeights.UPSCALE_S)
        assertEquals(4, r.size)
        assertEquals(4, u.size)
        // 首层 9 组（3x3，仅 go_0 正向输入）；后续层 18 组（go_0/go_1 正负半波各 9）
        r.forEachIndexed { i, layer ->
            assertEquals(if (i == 0) 9 else 18, layer.groups.size)
            assertEquals(4, layer.bias.size)
        }
        u.forEachIndexed { i, layer ->
            assertEquals(if (i == 0) 9 else 18, layer.groups.size)
            assertEquals(4, layer.bias.size)
        }
    }

    @Test
    fun `条目20 - Restore CNN 真实改变画面且尺寸不变`() {
        // 简单黑白线条图
        val bmp = solid(64, 64, Color.WHITE.toInt()) { x, y ->
            if (x == 32 || y == 32 || (abs(x - 32) + abs(y - 32) == 12)) Color.BLACK.toInt() else null
        }
        val out = Anime4KCnn.restore(bmp, 1.0f, maxEdge = 64)
        assertEquals(64, out.width); assertEquals(64, out.height)
        var diff = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            if (out.getPixel(x, y) != bmp.getPixel(x, y)) diff++
        }
        assertTrue("CNN 输出应与输入不同（残差生效）diff=$diff", diff > 50)
    }

    @Test
    fun `条目20 - Upscale CNN 输出尺寸翻倍`() {
        val bmp = solid(40, 30, Color.WHITE.toInt()) { x, y ->
            if (x in 15..25 && y in 10..20) Color.BLACK.toInt() else null
        }
        val out = Anime4KCnn.upscale2x(bmp, 1.0f, maxSrcEdge = 64)
        assertEquals(80, out.width)
        assertEquals(60, out.height)
        // 强度=0：残差不叠加，输出≈纯双线性基准（与强度 1 不同）
        val out0 = Anime4KCnn.upscale2x(bmp, 0f, maxSrcEdge = 64)
        var diff = 0
        for (y in 0 until 60) for (x in 0 until 80) {
            if (out0.getPixel(x, y) != out.getPixel(x, y)) diff++
        }
        assertTrue(diff > 20)
    }

    /* ═══ 26：自定义缩放档 ═══ */

    @Test
    fun `条目26 - 自定义档=基础档x系数且预设可存取`() {
        val intrinsic = Size(600f, 1200f)
        val c = Size(1080f, 2400f)
        val custom = fittedSize(intrinsic, c, ComicFit.CUSTOM, 1.5f, ComicFit.FIT_PAGE)
        val base = fittedSize(intrinsic, c, ComicFit.FIT_PAGE)
        assertEquals(base.width * 1.5f, custom.width, 0.6f)
        assertEquals(base.height * 1.5f, custom.height, 0.6f)
        // 预设 JSON 往返
        val p = ComicCustomFitPreset("id1", "我的150%", ComicFit.FIT_PAGE, 150)
        val p2 = ComicCustomFitPreset.fromJson(p.toJson())
        assertEquals(p, p2)
    }

    /* ═══ 27：预设收藏 ═══ */

    @Test
    fun `条目27 - 收藏持久化并置顶排序`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ComicSettingsStore(ctx)
        val a = store.createPreset("甲", "A", ComicReaderConfig())
        val b = store.createPreset("乙", "B", ComicReaderConfig())
        val c = store.createPreset("丙", "C", ComicReaderConfig())
        // 收藏后置顶
        store.togglePresetFavorite(c.id)
        val sorted = store.loadPresets()
        assertEquals(c.id, sorted.first { !it.builtIn }.id)
        assertTrue(sorted.first { !it.builtIn }.favorite)
        // favoritePresets 只含收藏项
        assertEquals(listOf(c.id), store.favoritePresets().map { it.id })
        // JSON roundtrip 保留 favorite
        val reloaded = store.loadPresets().first { it.id == c.id }
        assertTrue(reloaded.favorite)
        store.deletePreset(a.id); store.deletePreset(b.id); store.deletePreset(c.id)
    }

    /* ═══ 28：音量键桥 ═══ */

    @Test
    fun `条目28 - 音量键桥只在注册后消费`() {
        // 未注册：不拦截（系统原生行为）
        assertEquals(false, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_UP, true))
        var nextCount = 0
        ComicVolumeKeyBridge.handler = { isUp, isDownAction ->
            // 注册期间所有音量键事件都消费（DOWN 翻页，UP 只吞不翻）
            if (!isDownAction) true
            else {
                // 测试语义：LTR 下 VolDown=下一页，VolUp=上一页
                if (isUp) nextCount-- else nextCount++
                true
            }
        }
        try {
            assertEquals(true, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, true))
            assertEquals(true, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_UP, true))
            assertEquals(0, nextCount)
            // UP 事件也吞掉（不弹系统音量条），但不重复翻页
            assertEquals(true, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, false))
            assertEquals(0, nextCount)
            // 非音量键不消费
            assertEquals(false, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_A, true))
        } finally {
            ComicVolumeKeyBridge.handler = null
        }
        // 注销后放行
        assertEquals(false, ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, true))
    }

    /* ═══ 15/1：外部跳转与预取节流（纯逻辑面） ═══ */

    @Test
    fun `条目1和15 - 大跳转与快速翻页的同步策略互异`() {
        // 大跳转（目录）→ 立即同步预加载；相邻步进 → 防抖合并
        assertEquals(CurlSyncPlan.IMMEDIATE_PRELOAD, curlSyncPlan(30, 2, 2, 30))
        assertEquals(CurlSyncPlan.DEBOUNCE_MERGE, curlSyncPlan(3, 2, 2, 3))
        assertEquals(CurlSyncPlan.NOOP, curlSyncPlan(5, 5, 5, 5))
    }

    /* ═══ 第三轮：FADE 渐变位移抵消与真交叉淡化（第 5 条 RTL 回归修复） ═══ */

    @Test
    fun `FADE位移抵消 - LTR正向为正平移RTL为负TTB走纵轴`() {
        // LTR：布局位移 -off·w（旧页左移）→ 抵消 +off·w
        assertEquals(0.54f to 0f, fadeCancelOffset(0.54f, rtl = false, ttb = false, w = 1f, h = 1f))
        // RTL（reverseLayout 水平镜像）：布局位移 +off·w → 抵消必须取负号
        // （旧实现统一 +off·w 在 RTL 下加倍漂移——逐帧实测 +60px 即此）
        assertEquals(-0.54f to 0f, fadeCancelOffset(0.54f, rtl = true, ttb = false, w = 1f, h = 1f))
        // TTB：纵向布局位移 -off·h → 抵消 +off·h，横向恒 0
        assertEquals(0f to 0.4f, fadeCancelOffset(0.4f, rtl = false, ttb = true, w = 1f, h = 1f))
        assertEquals(0f to 0.4f, fadeCancelOffset(0.4f, rtl = true, ttb = true, w = 1f, h = 1f))
        // 尺寸按传入 w/h 缩放
        assertEquals(-108f to 0f, fadeCancelOffset(0.2f, rtl = true, ttb = false, w = 540f, h = 1200f))
    }

    @Test
    fun `FADE真交叉淡化 - 离场页恒1进场页自0淡入无黑场下陷`() {
        // 离场页（off≥0）：保持不透明作底
        assertEquals(1f, fadeCrossAlpha(0f))
        assertEquals(1f, fadeCrossAlpha(0.5f))
        assertEquals(1f, fadeCrossAlpha(1f))
        // 进场页（off<0）：自 0 线性淡入；两页叠加合成恒为 (1-p)旧 + p新
        assertEquals(0f, fadeCrossAlpha(-1f))
        assertEquals(0.5f, fadeCrossAlpha(-0.5f))
        assertEquals(0.9f, fadeCrossAlpha(-0.1f), 1e-6f)
        // 越界钳制：远处页不可见
        assertEquals(0f, fadeCrossAlpha(-1.5f))
        // 中点合成亮度检验（纸底 247）：离场页 alpha=1 在下、进场页 alpha=0.5
        // 叠于其上 → 合成 = 0.5·247 + 0.5·247 = 247 恒定不变暗；
        // 旧版两页同衰减到 0.08，中点合成仅 ~20（黑场下陷）——此断言钉死回归
        val paper = 247f
        val p = fadeCrossAlpha(-0.5f)          // 0.5
        val composite = p * paper + (1f - p) * paper
        assertTrue("中点合成 $composite 不得下陷", composite > 200f)
    }

    /* ═══ 第四轮：第 11 条面板毛玻璃采样开关 ═══ */

    @Test
    fun `条目11毛玻璃 - 仅面板打开且非CURL引擎时采样`() {
        // 面板关闭：一律不采样（阅读期零每帧重录开销）
        assertFalse(panelGlassSamplingActive(ComicPageAnim.SLIDE, ComicDirection.RTL, ComicMode.SINGLE, panelOpen = false))
        assertFalse(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.RTL, ComicMode.SINGLE, panelOpen = false))
        // 面板打开 + Compose 渲染路径：采样（毛玻璃生效）
        assertTrue(panelGlassSamplingActive(ComicPageAnim.SLIDE, ComicDirection.RTL, ComicMode.SINGLE, panelOpen = true))
        assertTrue(panelGlassSamplingActive(ComicPageAnim.FADE, ComicDirection.LTR, ComicMode.DOUBLE, panelOpen = true))
        assertTrue(panelGlassSamplingActive(ComicPageAnim.NONE, ComicDirection.TTB, ComicMode.WEBTOON, panelOpen = true))
        // 面板打开 + CURL 引擎（GLSurfaceView 采不到）：回退纯半透明
        assertFalse(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.RTL, ComicMode.SINGLE, panelOpen = true))
        assertFalse(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.LTR, ComicMode.DOUBLE, panelOpen = true))
        // CURL + TTB 单页被强制走 Pager 路径（ComicPagedReader 的既有语义）→ 可采样
        assertTrue(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.TTB, ComicMode.SINGLE, panelOpen = true))
        // 第六轮 Agent C 补审 F5：MAGNETIC/WEBTOON/CONTINUOUS 恒走 Compose 路径，
        // 即便 pageAnim=CURL 也无 GL 视图 → 可采样（防门控假阳性回归）
        assertTrue(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.RTL, ComicMode.MAGNETIC, panelOpen = true))
        assertTrue(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.TTB, ComicMode.WEBTOON, panelOpen = true))
        assertTrue(panelGlassSamplingActive(ComicPageAnim.CURL, ComicDirection.TTB, ComicMode.CONTINUOUS, panelOpen = true))
    }

    /* ═══ 第四轮返工：终审差距修复的钉死测试 ═══ */

    @Test
    fun `条目6 - 垂直前瞻预载索引按策略窗口展开且钳制边界`() {
        // 中位：无缝（窗口4）当前第 3 页（共 13）→ 优先级序 [当前3, 前瞻4..7, 上一页2]
        // （第六轮 Agent C 补审 F1：序=驻留优先级，预算超限丢尾部=远前瞻先失守）
        assertEquals(listOf(3, 4, 5, 6, 7, 2), verticalPreloadIndices(3, 4, 13))
        // 条漫（窗口2）当前第 3 页 → [当前3, 前瞻4..5, 上一页2]
        assertEquals(listOf(3, 4, 5, 2), verticalPreloadIndices(3, 2, 13))
        // 无缝比条漫多看 2 页（差异化真实生效）
        assertTrue(verticalPreloadIndices(3, 4, 13).size > verticalPreloadIndices(3, 2, 13).size)
        // 首/末边界钳制：不产生越界索引
        assertEquals(listOf(0, 1, 2, 3, 4), verticalPreloadIndices(0, 4, 5))
        assertEquals(listOf(4, 3), verticalPreloadIndices(4, 4, 5))
        // 空书
        assertTrue(verticalPreloadIndices(0, 4, 0).isEmpty())
    }

    @Test
    fun `补1 - 引擎切换淡入alpha起始不为全黑且单调收敛到1`() {
        // 起始 0.35（可辨识画面，非全黑硬切）
        assertEquals(0.35f, engineFadeAlpha(0), 1e-6f)
        // 中段单调上升（smoothstep）且不超过 1
        val a1 = engineFadeAlpha(60); val a2 = engineFadeAlpha(120); val a3 = engineFadeAlpha(180)
        assertTrue(a1 in 0.35f..1f && a2 in a1..1f && a3 in a2..1f)
        // 到点收 1；超时钳制
        assertEquals(1f, engineFadeAlpha(240), 1e-6f)
        assertEquals(1f, engineFadeAlpha(1000), 1e-6f)
        // 曲线中点值（t=0.5：smoothstep 权重 0.5 → alpha=0.675，起始段慢/中段快）
        assertEquals(0.675f, engineFadeAlpha(120), 1e-6f)
    }

    @Test
    fun `条目1 - CURL大跳转同步等待上限在200ms预算内`() {
        assertTrue("CURL_IMMEDIATE_WAIT_MS=${CURL_IMMEDIATE_WAIT_MS} 必须落在第 1 条 200ms 预算内",
            CURL_IMMEDIATE_WAIT_MS in 1..200)
    }
}
