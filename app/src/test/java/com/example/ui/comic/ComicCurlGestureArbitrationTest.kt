package com.example.ui.comic

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第 17 条：仿真翻页 View 层手势仲裁（第三轮补充）。
 *
 * 模拟器 /dev/input 设备是"单工具触笔"语义，注入无法产生格式合法的
 * ACTION_POINTER_DOWN（系统只派发单指 DOWN + 双指 MOVE，第三轮 CURLDBG
 * 实证）——该分支在真机之外无法端到端触发，故用 Robolectric 合成
 * MotionEvent 流直接驱动 onTouch 验证应用层行为：
 * 双指落下 → 打开缩放覆盖层并吞掉余下事件，抬净后恢复卷页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicCurlGestureArbitrationTest {

    private lateinit var v: ComicCurlView
    private var zoomOpened = 0

    @Before
    fun setUp() {
        v = ComicCurlView(ApplicationProvider.getApplicationContext<Context>())
        v.onZoomGesture = { zoomOpened++ }
        v.doubleTapZoomEnabled = true
        v.longPressZoomEnabled = true
        v.swipeEnabled = true
    }

    private fun twoFingerEvent(action: Int, t: Long): MotionEvent {
        val c0 = MotionEvent.PointerCoords().apply { x = 400f; y = 1200f; pressure = 1f; size = 1f }
        val c1 = MotionEvent.PointerCoords().apply { x = 680f; y = 1200f; pressure = 1f; size = 1f }
        return MotionEvent.obtain(
            t, t, action, 2, intArrayOf(0, 1), arrayOf(c0, c1),
            0 /*metaState*/, 0f /*xPrecision*/, 0f /*yPrecision*/,
            0 /*deviceId*/, 0 /*edgeFlags*/, InputDevice.SOURCE_TOUCHSCREEN, 0 /*flags*/,
        )
    }

    @Test
    fun `双指POINTER_DOWN打开缩放覆盖层并吞掉余下事件`() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 400f, 1200f, 0)
        assertTrue(v.onTouch(v, down))

        val pointerDown = twoFingerEvent(
            MotionEvent.ACTION_POINTER_DOWN + (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            t + 50,
        )
        assertTrue("POINTER_DOWN 必须被仲裁层消费", v.onTouch(v, pointerDown))
        assertEquals("onZoomGesture 应恰好触发一次", 1, zoomOpened)

        // 双指激活后：余下 MOVE（含双指）与单指 UP 全部吞掉，不再触发卷页/翻页
        val move = twoFingerEvent(MotionEvent.ACTION_MOVE, t + 100)
        assertTrue(v.onTouch(v, move))
        val up = MotionEvent.obtain(t + 200, t + 200, MotionEvent.ACTION_UP, 448f, 1200f, 0)
        assertTrue(v.onTouch(v, up))
        assertEquals("手势期间不得重复打开", 1, zoomOpened)
    }

    @Test
    fun `双指抬净后仲裁复位不残留`() {
        val t = SystemClock.uptimeMillis()
        v.onTouch(v, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 400f, 1200f, 0))
        v.onTouch(
            v,
            twoFingerEvent(
                MotionEvent.ACTION_POINTER_DOWN + (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                t + 30,
            ),
        )
        assertEquals(1, zoomOpened)
        // 最后一根手指抬起（pointerCount=1 的 UP）→ multiTouch 复位
        v.onTouch(v, MotionEvent.obtain(t + 60, t + 60, MotionEvent.ACTION_UP, 400f, 1200f, 0))
        // 复位后再来一次双指 → 覆盖层应能再次打开（无残留死锁）
        v.onTouch(v, MotionEvent.obtain(t + 100, t + 100, MotionEvent.ACTION_DOWN, 400f, 1200f, 0))
        v.onTouch(
            v,
            twoFingerEvent(
                MotionEvent.ACTION_POINTER_DOWN + (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                t + 130,
            ),
        )
        assertEquals("复位后双指应重新打开覆盖层", 2, zoomOpened)
    }

    @Test
    fun `双击第二击直接进缩放不再走点按`() {
        val t = SystemClock.uptimeMillis()
        // 第一击：快 tap（<250ms 抬起）
        v.onTouch(v, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 1200f, 0))
        var quickTap = 0
        v.onQuickTap = { _, _ -> quickTap++ }
        v.onTouch(v, MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, 500f, 1200f, 0))
        assertEquals("第一击应等待双击判定，不能提前翻页或打开面板", 0, quickTap)
        // 第二击（双击窗口内）：DOWN 即进缩放
        v.onTouch(v, MotionEvent.obtain(t + 180, t + 180, MotionEvent.ACTION_DOWN, 505f, 1200f, 0))
        assertEquals("双击第二击应打开缩放", 1, zoomOpened)
        v.onTouch(v, MotionEvent.obtain(t + 180, t + 260, MotionEvent.ACTION_UP, 505f, 1200f, 0))
        assertEquals("UP 不重复触发", 1, zoomOpened)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals("双击不能触发单击动作", 0, quickTap)
    }

    private fun edgeDrag(start: Float, finish: Float, finalAction: Int = MotionEvent.ACTION_UP) {
        val t = SystemClock.uptimeMillis()
        listOf(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, start, 500f, 0),
            MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_MOVE, finish, 500f, 0),
            MotionEvent.obtain(t, t + 180, finalAction, finish, 500f, 0),
        ).forEach { event -> v.onTouch(v, event); event.recycle() }
    }

    @Test fun curlChapterEdgesWorkInBothReadingDirections() {
        val opened = mutableListOf<Boolean>()
        v.onChapterEdge = { opened += it }
        v.doubleTapZoomEnabled = false
        for (sign in listOf(-1f, 1f)) {
            v.forwardSign = sign
            v.isAtForwardEdge = { true }; v.isAtBackwardEdge = { false }
            edgeDrag(500f, 500f + 200f * sign)
            v.isAtForwardEdge = { false }; v.isAtBackwardEdge = { true }
            edgeDrag(500f, 500f - 200f * sign)
        }
        assertEquals(listOf(true, false, true, false), opened)
    }

    @Test fun curlCancelledOrReversedEdgeDragDoesNotNavigate() {
        var opened = 0
        v.onChapterEdge = { opened++ }
        v.isAtForwardEdge = { true }
        v.forwardSign = -1f
        edgeDrag(500f, 300f, MotionEvent.ACTION_CANCEL)
        val t = SystemClock.uptimeMillis()
        v.onTouch(v, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 500f, 0))
        v.onTouch(v, MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_MOVE, 300f, 500f, 0))
        v.onTouch(v, MotionEvent.obtain(t, t + 160, MotionEvent.ACTION_MOVE, 490f, 500f, 0))
        v.onTouch(v, MotionEvent.obtain(t, t + 180, MotionEvent.ACTION_UP, 490f, 500f, 0))
        assertEquals(0, opened)
    }

    @Test fun curlMultitouchTakesOverEdgeDragWithoutNavigating() {
        var opened = 0
        v.onChapterEdge = { opened++ }
        v.isAtForwardEdge = { true }
        v.forwardSign = -1f
        val t = SystemClock.uptimeMillis()
        v.onTouch(v, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 500f, 0))
        v.onTouch(v, MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_MOVE, 300f, 500f, 0))
        v.onTouch(v, twoFingerEvent(MotionEvent.ACTION_POINTER_DOWN + (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t + 100))
        v.onTouch(v, MotionEvent.obtain(t, t + 180, MotionEvent.ACTION_UP, 300f, 500f, 0))
        assertEquals(0, opened)
        assertEquals(1, zoomOpened)
    }
}
