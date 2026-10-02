package com.example.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.example.ui.comic.ComicReaderTestActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 手势宿主与子 clickable 的事件消费机制实证（PageTurnContainer 的翻页分派模型）：
 * 子层（正文插图）的 clickable 消费了 up 之后，宿主（整页拦截的 pointerInput）
 * 必须看到 isConsumed=true 并跳过 tap 分派 —— 否则点击图片会触发翻页（用户实测）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PageTurnTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test
    fun tapConsumedByChildIsNotDispatchedToParent() {
        var parentTapDispatches = 0
        var childClicks = 0
        var seenConsumedFlag = false

        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 复刻 PageTurnContainer 宿主：整页拦截 + up 时检查子层消费
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var consumed = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    consumed = change.isConsumed
                                    break
                                }
                            }
                            seenConsumedFlag = consumed
                            if (!consumed) parentTapDispatches++
                        }
                    }
            ) {
                // 模拟正文插图块：内层 clickable
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { childClicks++ }
                )
            }
        }

        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()

        assertEquals("子层 clickable 应收到点击", 1, childClicks)
        assertEquals(
            "子层消费的 tap 不应再分派给宿主（否则点击图片会翻页）",
            0, parentTapDispatches
        )
        assertTrue("宿主应看到 up 已被消费", seenConsumedFlag)
    }

    @Test
    fun imageHitRegistryOpensFullscreenInsteadOfTurningPage() {
        var turned = 0
        var openedPath: String? = null
        val imgPath = "registry_test_img.png"

        compose.setContent {
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.clear()
            val boxPx = with(LocalDensity.current) { 200.dp.toPx() }
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                // 模拟正文插图块：注册一块 200x200dp 的屏幕矩形
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(200.dp)
                )
                com.example.ui.pageturn.PageTurnContainer(
                    pageTurnMode = com.example.ui.pageturn.PageTurnType.SLIDE.id,
                    pageKey = "registry-test",
                    currentContent = {
                        // 生产端 RenderSinglePage 在组合期 setActivePage —— 模拟同款
                        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.setActivePage("registry-test")
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned {
                                    // 模拟 RenderSinglePage 的插图矩形注册（左上 200dp 区域）
                                    com.example.ui.reader.NovelInlineImages.ImageHitRegistry.register(
                                        "test_reg_1", imgPath,
                                        androidx.compose.ui.geometry.Rect(
                                            androidx.compose.ui.geometry.Offset.Zero,
                                            androidx.compose.ui.geometry.Size(boxPx, boxPx)
                                        ),
                                        "registry-test"
                                    )
                                }
                        )
                    },
                    nextContent = {},
                    prevContent = {},
                    onNextPage = { turned++ },
                    onPrevPage = { turned++ },
                    onClickCenter = {},
                    onClickLeft = { turned++ },
                    onClickRight = { turned++ },
                    onImageTapAt = { pos ->
                        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(pos)
                            ?.also { openedPath = it } != null
                    }
                )
            }
        }
        compose.waitForIdle()

        // 点击注册矩形内（50dp, 50dp 处）
        compose.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(50.dp.toPx(), 50.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals("命中插图：不应翻页", 0, turned)
        assertEquals("命中插图：应打开对应图片全屏", imgPath, openedPath)

        // 点击右侧（非图片区 + 右翻页区）→ 正常翻页
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 20f, height * 0.5f)) }
        compose.waitForIdle()
        assertEquals("非图片区应正常翻页", 1, turned)
    }

    @Test
    fun overlappingRegistryEntriesResolveToSmallestAndUnregisteredLayersDoNotInterfere() {
        compose.setContent {
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.clear()
            // 复刻 PageCurl 多层同屏叠放：prev 层大图与 current 层小图矩形重叠。
            // 修复后仅当前页注册 —— prev 层先注册再注销，验证"未注册层不干扰"
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.register(
                "prev_a", "/a/prev.png",
                androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 2000f), "pageA"
            )
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.register(
                "cur_b", "/a/current.png",
                androidx.compose.ui.geometry.Rect(100f, 100f, 900f, 1500f), "pageA"
            )
        }
        compose.waitForIdle()
        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.setActivePage("pageA")

        // 重叠区点击：取面积最小者（当前页小图），不能命中前面某层的大图
        val overlap = com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(
            androidx.compose.ui.geometry.Offset(500f, 500f)
        )
        assertEquals("重叠命中应取当前页的图", "/a/current.png", overlap)

        // prev 层注销（= 修复后 next/prev 层不注册的语义）
        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.unregister("prev_a")
        // 大图独占区（原 prev 层位置）：不再命中任何图 → 翻页分派
        val miss = com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(
            androidx.compose.ui.geometry.Offset(50f, 50f)
        )
        assertEquals("未注册层的位置不应命中", null, miss)
        // 当前页小图独占区仍正常命中
        val still = com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(
            androidx.compose.ui.geometry.Offset(500f, 500f)
        )
        assertEquals("当前页图仍正常命中", "/a/current.png", still)
    }

    @Test
    fun staleEntriesFromPreviousPageDoNotHit() {
        // 用户实测：翻到图片的下一页（纯文字），相同位置点击还会打开前面那张图
        compose.setContent {
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.clear()
            // 第 1 页（含图）：组合期 setActive + 注册
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.setActivePage("page1")
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.register(
                "r1", "/a/1.png",
                androidx.compose.ui.geometry.Rect(0f, 0f, 500f, 800f), "page1"
            )
        }
        compose.waitForIdle()
        // 翻到第 2 页（纯文字）：currentSlot 的 RenderSinglePage 组合期切换 active
        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.setActivePage("page2")

        // 相同位置点击：残留矩形失效 → 不命中 → 翻页分派
        val miss = com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(
            androidx.compose.ui.geometry.Offset(250f, 400f)
        )
        assertEquals("上一页的残留矩形不应命中", null, miss)

        // 翻回第 1 页：active 切回 → 命中恢复
        com.example.ui.reader.NovelInlineImages.ImageHitRegistry.setActivePage("page1")
        val back = com.example.ui.reader.NovelInlineImages.ImageHitRegistry.hit(
            androidx.compose.ui.geometry.Offset(250f, 400f)
        )
        assertEquals("翻回图片所在页应恢复命中", "/a/1.png", back)
    }
    @Test
    fun disabledImageDoesNotConsumeClick() {
        // PageCurl 把 prev 页组合在最顶层（绘制被卷曲裁剪隐藏，但命中链仍在）——
        // 非当前层的插图必须 enabled=false，让点击穿透到真正的当前页
        var onTapCalled = 0
        compose.setContent {
            com.example.ui.reader.NovelInlineImage(
                path = "/nonexistent/disabled.png",
                onTap = { onTapCalled++ },
                enabled = false
            )
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("禁用的插图不应响应点击（否则拦截当前页的触摸）", 0, onTapCalled)
    }

    @Test
    fun disabledPreviousPageImageLetsCenterTapReachReader() {
        var centerTaps = 0
        compose.setContent {
            com.example.ui.reader.NovelInlineImages.ImageHitRegistry.clear()
            com.example.ui.pageturn.PageTurnContainer(
                pageTurnMode = com.example.ui.pageturn.PageTurnType.SLIDE.id,
                pageKey = "text-after-image",
                currentContent = { Box(Modifier.fillMaxSize()) },
                nextContent = {},
                prevContent = {
                    com.example.ui.reader.NovelInlineImage(
                        path = "/nonexistent/previous-page.png",
                        onTap = {},
                        enabled = false
                    )
                },
                onNextPage = {},
                onPrevPage = {},
                onClickCenter = { centerTaps++ },
                onClickLeft = {},
                onClickRight = {},
                onImageTapAt = { false }
            )
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("上一页图片所在区域仍应能唤出阅读菜单", 1, centerTaps)
    }
    @Test
    fun horizontalSwipeOnChapterRowTogglesBookmark() {
        // 章节行水平滑动必须触发书签回调（用户实测"左右滑无法标记"——
        // 旧实现 detectHorizontalDragGestures 被 LazyColumn 纵向滚动抢占；
        // 新实现 PageCurl 同款 totalX/totalY 仲裁 + 消费，纯横滑必定接管）
        var toggles = 0
        compose.setContent {
            com.example.ui.favorite.ChapterStatusRow(
                title = "第 12 话",
                state = com.example.ui.favorite.ChapterRowState(),
                onClick = {},
                onLongClick = {},
                onToggleBookmark = { toggles++ }
            )
        }
        compose.waitForIdle()
        // 纯水平滑动（左滑超过 56px 阈值）
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-60f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-60f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals("左滑应触发书签切换", 1, toggles)
    }
    @Test
    fun horizontalSwipeInsideLazyColumnTogglesBookmark() {
        // 真机场景复刻：ChapterStatusRow 在 LazyColumn 内——
        // 旧实现的水平手势被列表纵向滚动抢占（斜滑即失败）；
        // 新仲裁（totalX 主导 + 消费整个 change）必须在列表内仍然接管
        val toggledRows = androidx.compose.runtime.mutableStateListOf<Int>()
        compose.setContent {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(40) { i ->
                    com.example.ui.favorite.ChapterStatusRow(
                        title = "第 $i 话",
                        state = com.example.ui.favorite.ChapterRowState(),
                        onClick = {},
                        onLongClick = {},
                        onToggleBookmark = { toggledRows.add(i) }
                    )
                }
            }
        }
        compose.waitForIdle()
        // 先滚到第 20 话可见（列表中部），再对屏幕中心做带轻微纵向分量的横滑（模拟真实手指）
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.8f))
            moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.6f))
            moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.4f))
            moveTo(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.2f))
            up()
        }
        compose.waitForIdle()
        var toggled = false
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-30f, -3f))
            moveBy(androidx.compose.ui.geometry.Offset(-60f, -4f))
            moveBy(androidx.compose.ui.geometry.Offset(-60f, -3f))
            up()
        }
        compose.waitForIdle()
        assertTrue(
            "LazyColumn 内横滑必须触发书签（旧实现被纵向滚动抢占），toggled=$toggledRows",
            toggledRows.isNotEmpty()
        )
    }
}
