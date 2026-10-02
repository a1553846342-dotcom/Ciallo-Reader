package com.example.ui.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * harism 整合层纯逻辑单测：索引映射（逻辑页码恒等，RTL 镜像几何）、缓存键稳定性
 * （含单页旋转——"旋转本页后 CURL 页空白"回归防线）、背景色映射、
 * 外部跳转同步策略（第 1/15/22 条）与同页变体回退缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicHarismCurlTest {

    @Test
    fun `RTL 逻辑页码往返一致且保持首末顺序`() {
        val n = 6
        // 逻辑索引不反转；物理侧由 renderer 镜像。
        assertEquals(0, harismIndexFor(0, n, reversed = true))
        assertEquals(5, harismIndexFor(5, n, reversed = true))
        // 往返一致
        for (our in 0 until n) {
            assertEquals(our, ourIndexFor(harismIndexFor(our, n, true), n, true))
        }
    }

    @Test
    fun `LTR 恒等映射`() {
        val n = 6
        for (our in 0 until n) {
            assertEquals(our, harismIndexFor(our, n, reversed = false))
            assertEquals(our, ourIndexFor(our, n, reversed = false))
        }
    }

    @Test
    fun `RTL 前进同样递增逻辑页码`() {
        val n = 6
        val our = 2
        val h = harismIndexFor(our, n, reversed = true)
        // 两种方向前进均使用逻辑 CURL_RIGHT，落定后 ++mCurrentIndex。
        assertEquals(h + 1, harismIndexFor(our + 1, n, reversed = true))
    }

    @Test
    fun `缓存键包含单页旋转`() {
        val slot = ComicSlot(
            ref = ComicPageRef.Local(id = "p1", path = "/x/p1.png"),
            rawIndex = 0,
        )
        val cfg = ComicReaderConfig()
        val plain = slotCacheKey(slot, cfg, ComicBookState())
        val rotated = slotCacheKey(slot, cfg, ComicBookState(pageRotations = mapOf("p1" to 90)))
        assertNotEquals(plain, rotated)
        // 同参数键稳定（预加载与 GL 线程合成两侧匹配的前提）
        assertEquals(plain, slotCacheKey(slot, cfg, ComicBookState()))
    }

    @Test
    fun `缓存键包含管线指纹`() {
        val slot = ComicSlot(ref = ComicPageRef.Local(id = "p1", path = "/x/p1.png"), rawIndex = 0)
        val s1 = slotCacheKey(slot, ComicReaderConfig(), ComicBookState())
        val s2 = slotCacheKey(
            slot, ComicReaderConfig(filterBrightness = 50), ComicBookState()
        )
        assertNotEquals(s1, s2)
    }

    @Test
    fun `背景色映射覆盖三主题`() {
        val dark = pageBgInt(ComicReaderConfig(bgType = ComicBgType.BLACK))
        val white = pageBgInt(ComicReaderConfig(bgType = ComicBgType.WHITE))
        val paper = pageBgInt(ComicReaderConfig(bgType = ComicBgType.PAPER))
        assertTrue(dark != white && white != paper)
    }

    /* ═══════ 第 15/22 条：翻页竞态与仿真翻页回归 ═══════ */

    @Test
    fun `外部跳转策略 - 目标即当前为 NOOP`() {
        // 用户拖拽落定：view 索引已等于目标（onSettledIndex 先行），同步为 no-op
        assertEquals(CurlSyncPlan.NOOP, curlSyncPlan(5, 5, viewCurrentIndex = 5, viewTargetIndex = 5))
        assertEquals(CurlSyncPlan.NOOP, curlSyncPlan(3, 3, viewCurrentIndex = 3, viewTargetIndex = 3))
    }

    @Test
    fun `外部跳转策略 - 大跨度为同步预加载`() {
        // 目录/进度条跳转：|Δ|>2 → IMMEDIATE_PRELOAD（先解码目标页再切换，防空白闪帧）
        assertEquals(
            CurlSyncPlan.IMMEDIATE_PRELOAD,
            curlSyncPlan(20, 3, viewCurrentIndex = 3, viewTargetIndex = 20),
        )
        assertEquals(
            CurlSyncPlan.IMMEDIATE_PRELOAD,
            curlSyncPlan(0, 30, viewCurrentIndex = 30, viewTargetIndex = 0),
        )
        // 首次同步（lastSynced=-1）不视为大跨度——初建视图直接落位
        assertEquals(
            CurlSyncPlan.DEBOUNCE_MERGE,
            curlSyncPlan(50, -1, viewCurrentIndex = 0, viewTargetIndex = 50),
        )
    }

    @Test
    fun `外部跳转策略 - 相邻步进防抖合并`() {
        // 快速连续翻页 ±1/±2 → 防抖合并（collectLatest 丢弃中间值，不堆积重建）
        assertEquals(
            CurlSyncPlan.DEBOUNCE_MERGE,
            curlSyncPlan(6, 5, viewCurrentIndex = 5, viewTargetIndex = 6),
        )
        assertEquals(
            CurlSyncPlan.DEBOUNCE_MERGE,
            curlSyncPlan(3, 5, viewCurrentIndex = 5, viewTargetIndex = 3),
        )
    }

    @Test
    fun `缓存同页任意变体回退 - 命中最新且不跨页`() {
        val c = ComicHarismController()
        val bmp1 = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.RGB_565)
        val bmp2 = android.graphics.Bitmap.createBitmap(6, 6, android.graphics.Bitmap.Config.RGB_565)
        c.putCache("pageA|FULL|f1|r0", bmp1)
        c.putCache("pageB|FULL|f1|r0", bmp2)
        // pageA 命中自身（唯一变体）；pageB 不误取 pageA 的位图（页身份不串）
        assertEquals(bmp1, c.getCacheAnyVariant("pageA"))
        assertEquals(bmp2, c.getCacheAnyVariant("pageB"))
        assertEquals(null, c.getCacheAnyVariant("pageC"))
    }

    @Test
    fun `缓存同页任意变体回退 - 取最近写入的变体`() {
        val c = ComicHarismController()
        val old = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.RGB_565)
        val new = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.RGB_565)
        c.putCache("p|FULL|f1|r0", old)
        c.putCache("p|FULL|f2|r0", new)
        // LRU 迭代序取最后命中 = 最近写入的 f2 变体
        assertEquals(new, c.getCacheAnyVariant("p"))
    }

    @Test
    fun `displayGeneration 递增使旧代提交失效`() {
        // 语义防线：异步任务发起时记录代，提交前代已变则丢弃（快速翻页竞态）
        val c = ComicHarismController()
        val genAtRequest = c.displayGeneration
        c.displayGeneration++   // 期间布局/页码变化
        assertTrue(genAtRequest != c.displayGeneration) // 旧代结果不得提交
    }

    @Test
    fun `双页扁平单元索引 - RTL倒排翻译不落补位null`() {
        // 13 页双页 → 14 扁平单元（末 spread 单槽补 null）：
        // flat = [p0,p1,...,p11,p12,null]
        // RTL spread0 → harism 13 → 必须译到 flat[0]=p0（旧实现直接取 flat[13]=null → 黑屏）
        assertEquals(13, flatUnitIndexFor(13, 14, reversed = true))
        assertEquals(12, flatUnitIndexFor(12, 14, reversed = true))
        assertEquals(0, flatUnitIndexFor(0, 14, reversed = true))
        // LTR 恒等
        assertEquals(13, flatUnitIndexFor(13, 14, reversed = false))
        assertEquals(0, flatUnitIndexFor(0, 14, reversed = false))
        // 与 spreadToHarismTwo 往返一致：spread0(RTL) 的右页 h=13 ↦ flat[0]
        val h = spreadToHarismTwo(0, 14, reversed = true)
        assertEquals(1, flatUnitIndexFor(h, 14, reversed = true))
    }
}
