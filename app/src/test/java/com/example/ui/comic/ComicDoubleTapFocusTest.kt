package com.example.ui.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
class ComicDoubleTapFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private fun reader(zoom: ComicZoomState) {
        val callbacks = ComicGestureCallbacks({ _, _ -> }, {}, {}, {})
        compose.setContent {
            Box(Modifier.size(320.dp, 600.dp).testTag("zoom")
                .comicZoomable(zoom, ComicReaderConfig(), callbacks)) {
                ZoomableImageLayer(zoom, ComicFit.FIT_PAGE) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun doubleTap(point: Offset) {
        compose.onNodeWithTag("zoom").performTouchInput {
            down(point); advanceEventTime(60); up()
            advanceEventTime(70)
            down(point); advanceEventTime(60); up()
        }
        compose.waitForIdle()
    }

    @Test fun doubleTapDuringOverscrollDoesNotSnapPositionOnItsFirstFrame() {
        val zoom = ComicZoomState().apply {
            contentSize = Size(320f, 480f)
            containerSize = Size(320f, 600f)
            scale = 2.5f
            offsetX = 310f // 平移橡胶带允许超出 ±240，回弹途中可被下一次触摸打断。
        }
        reader(zoom)
        doubleTap(Offset(200f, 300f))
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("动画应已经推进：${zoom.scale}", zoom.scale < 2.5f)
            assertTrue("双击首帧不能跳到边界：offset=${zoom.offsetX}", abs(zoom.offsetX - 310f) < 60f)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals(1f, zoom.scale, 0.001f); assertEquals(0f, zoom.offsetX, 0.001f) }
    }

    @Test fun offCenterDoubleTapKeepsTheTouchedPointStationaryAtEveryFrame() {
        val zoom = ComicZoomState().apply {
            contentSize = Size(320f, 480f)
            containerSize = Size(320f, 600f)
        }
        reader(zoom)
        val focal = Offset(105f, 345f)
        val source = focal - Offset(160f, 300f)
        doubleTap(focal)
        repeat(18) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val projected = Offset(160f, 300f) + source * zoom.scale + Offset(zoom.offsetX, zoom.offsetY)
                assertEquals("horizontal anchor", focal.x, projected.x, 0.5f)
                assertEquals("vertical anchor", focal.y, projected.y, 0.5f)
            }
        }
        compose.runOnIdle { assertEquals(2.5f, zoom.scale, 0.001f) }
    }
}
