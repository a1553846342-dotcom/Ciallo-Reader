package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import fi.harism.curl.CurlRenderer
import java.lang.reflect.Proxy
import javax.microedition.khronos.opengles.GL10
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual SurfaceView rendering and gestures: tests the fold, not only page-index arithmetic. */
@RunWith(AndroidJUnit4::class)
class ReaderDirectionDeviceTest {
    @Test fun rtlRendererReflectsPhysicalPointerAndPageRectsAndTheGlBook() {
        val scales = mutableListOf<Float>()
        val gl = Proxy.newProxyInstance(GL10::class.java.classLoader, arrayOf(GL10::class.java)) { _, method, args ->
            if (method.name == "glScalef") scales.add(args!![0] as Float)
            null
        } as GL10
        val renderer = CurlRenderer(object : CurlRenderer.Observer {
            override fun onDrawFrame() = Unit
            override fun onPageSizeChanged(width: Int, height: Int) = Unit
            override fun onSurfaceCreated() = Unit
        })
        renderer.onSurfaceChanged(gl, 1000, 1500)
        val left = PointF(0f, 750f).also(renderer::translate)
        renderer.setRightToLeft(true)
        val mirroredLeft = PointF(0f, 750f).also(renderer::translate)
        assertEquals(-left.x, mirroredLeft.x, 0.0001f)
        renderer.setViewMode(CurlRenderer.SHOW_TWO_PAGES)
        // Asymmetric page sizes / offsets expose a merely reversed page index.
        renderer.setPageRectPixels(RectF(100f, 200f, 400f, 900f), RectF(500f, 300f, 850f, 1100f))
        val right = renderer.getPageRect(CurlRenderer.PAGE_RIGHT)
        assertEquals(0.133333f, right.left, 0.0001f) // physical left page mirrored to model right
        assertEquals(0.533333f, right.right, 0.0001f)
        renderer.onDrawFrame(gl)
        assertEquals(listOf(-1f), scales)
        renderer.setRightToLeft(false)
        renderer.onDrawFrame(gl)
        assertEquals(1, scales.size)
    }


    @Test fun singlePageAndDoublePageCurlMirrorTheBookAndKeepArtworkUpright() {
        for (double in listOf(false, true)) for (rtl in listOf(false, true)) {
            val config = ComicReaderConfig(mode = if (double) ComicMode.DOUBLE else ComicMode.SINGLE,
                direction = if (rtl) ComicDirection.RTL else ComicDirection.LTR, fit = ComicFit.STRETCH,
                doubleGapDp = 0f)
            val slots = (0 until 8).map { ComicSlot(ComicPageRef.Local("native$it", "/unused"), it) }
            val spreads = if (double) slots.chunked(2) else slots.map(::listOf)
            val layout = ComicLayout(spreads.mapIndexed { i, pair -> ComicSpread(i, pair) }, emptyMap())
            val controller = ComicHarismController().apply {
                this.config = config; this.layout = layout; reversed = rtl; twoPage = double
                flatUnits = if (double) buildCurlFlatUnits(layout) else emptyList()
            }
            slots.forEach { slot ->
                val bmp = Bitmap.createBitmap(480, 720, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                val paint = Paint()
                paint.color = if (slot.rawIndex % 2 == 0) Color.RED else Color.GREEN
                canvas.drawRect(0f, 0f, 240f, 720f, paint)
                paint.color = Color.BLUE; canvas.drawRect(240f, 0f, 480f, 720f, paint)
                paint.color = Color.WHITE; paint.textSize = 54f
                canvas.drawText("PAGE ${slot.rawIndex + 1}", 40f, 150f, paint)
                controller.putCache(slotCacheKey(slot, config, ComicBookState()), bmp)
            }
            val scenario = ActivityScenario.launch(ComicReaderTestActivity::class.java)
            lateinit var view: ComicCurlView
            try {
                scenario.onActivity { activity ->
                    view = ComicCurlView(activity).apply {
                        setRightToLeft(rtl)
                        setViewMode(if (double) CurlView.SHOW_TWO_PAGES else CurlView.SHOW_ONE_PAGE)
                        setSpreadStep(if (double) 2 else 1)
                        setAllowLastPageCurl(false)
                        setBackgroundColor(Color.BLACK)
                        setPageProvider(object : CurlView.PageProvider {
                            override fun getPageCount() = if (double) controller.flatUnits.size else layout.spreadCount
                            override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
                                val front = if (double) controller.composeUnit(index, width, height)
                                    else controller.composeSpread(index, width, height)
                                if (front != null) {
                                    page.setTexture(front, CurlPage.SIDE_FRONT)
                                    page.setTexture(if (double) controller.composeAdjacentUnit(index, width, height)
                                        ?: controller.composeBackTexture(front, width, height)
                                        else controller.composeBackTexture(front, width, height), CurlPage.SIDE_BACK)
                                }
                            }
                        })
                        setCurrentIndex(if (double) 1 else 0)
                    }
                    controller.view = view
                    activity.setContentView(view)
                }
                val ready = waitForFrame(view)
                // In a single page, printed left red/right blue is invariant under RTL.
                // In a spread, first-read page is on the right in RTL and on the left in LTR.
                val firstX = if (double && rtl) ready.width * 3 / 4 else ready.width / 4
                assertTrue("rtl=$rtl double=$double artwork", Color.red(ready.getPixel(
                    if (double) firstX - ready.width / 8 else firstX, ready.height / 2)) > 180)
                save(ready, "curl_${if (double) "double" else "single"}_${if (rtl) "rtl" else "ltr"}_before")
                val downTime = SystemClock.uptimeMillis()
                fun send(action: Int, fraction: Float) {
                    scenario.onActivity {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            view.width * fraction, view.height * 0.55f, 0)
                        view.dispatchTouchEvent(event); event.recycle()
                    }
                }
                send(MotionEvent.ACTION_DOWN, if (rtl) 0.05f else 0.95f)
                send(MotionEvent.ACTION_MOVE, if (rtl) 0.30f else 0.70f)
                SystemClock.sleep(100)
                // Both directions fold the current page outward; internal native forward
                // state is CURL_RIGHT, reflected onto the LEFT edge for RTL.
                val field = CurlView::class.java.getDeclaredField("mCurlState").apply { isAccessible = true }
                assertEquals("forward fold rtl=$rtl double=$double", 2, field.getInt(view))
                save(copy(view), "curl_${if (double) "double" else "single"}_${if (rtl) "rtl" else "ltr"}_fold")
                send(MotionEvent.ACTION_MOVE, if (rtl) 0.85f else 0.15f)
                send(MotionEvent.ACTION_UP, if (rtl) 0.85f else 0.15f)
                val until = SystemClock.uptimeMillis() + 4000
                val expected = if (double) 3 else 1
                while (view.currentIndex != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
                assertEquals(expected, view.currentIndex)
                save(copy(view), "curl_${if (double) "double" else "single"}_${if (rtl) "rtl" else "ltr"}_after")
            } finally {
                scenario.onActivity { view.onPause() }
                scenario.close(); controller.clearCache()
            }
        }
    }

    private fun waitForFrame(view: ComicCurlView): Bitmap {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) {
            if (view.width > 0 && view.height > 0) {
                val bitmap = runCatching { copy(view) }.getOrNull()
                if (bitmap != null && (bitmap.getPixel(bitmap.width / 4, bitmap.height / 2) and 0xffffff) != 0) return bitmap
            }
            SystemClock.sleep(50)
        }
        error("GL frame was not ready")
    }

    private fun copy(view: ComicCurlView): Bitmap {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(view, bmp, { result -> status = result; done.countDown() }, Handler(Looper.getMainLooper()))
        check(done.await(3, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "PixelCopy: $status" }
        return bmp
    }

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.filesDir, "reader-feedback/$name.png").apply { parentFile?.mkdirs() }
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
