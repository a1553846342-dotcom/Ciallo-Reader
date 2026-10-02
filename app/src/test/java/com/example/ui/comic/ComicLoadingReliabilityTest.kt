package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ComicLoadSupportTest {
    @Test fun `waiters preserve the same mutex until the final caller leaves`() {
        val locks = ComicLoadLocks()
        val a = locks.acquire("page")
        assertSame(a, locks.acquire("page"))
        locks.release("page")
        assertSame(a, locks.acquire("page"))
        locks.release("page")
        locks.release("page")
        assertNotSame(a, locks.acquire("page"))
    }

    @Test fun `cache identity separates credentials and normalizes header spelling and order`() {
        val key = comicRemoteCacheKey("https://example.org/p", mapOf("Cookie" to "a", "Referer" to "r"))
        assertEquals(key, comicRemoteCacheKey("https://example.org/p", mapOf("referer" to "r", "cookie" to "a")))
        assertEquals(key, comicRemoteCacheKey("https://example.org/p", mapOf("cookie" to "a"), "r"))
        assertNotEquals(key, comicRemoteCacheKey("https://example.org/p", mapOf("Cookie" to "b"), "r"))
        assertFalse(key.contains("Cookie"))
    }

    @Test fun `unknown content length is indeterminate and known progress is clamped`() {
        assertNull(ComicTransferProgress(100, -1).fraction)
        assertNull(ComicTransferProgress(100, 0).fraction)
        assertEquals(.5f, ComicTransferProgress(50, 100).fraction!!, .001f)
        assertEquals(1f, ComicTransferProgress(200, 100).fraction!!, .001f)
    }

    @Test fun `only complete progressive scans are eligible for a network preview`() {
        val progressive = javaClass.getResourceAsStream("/comic/progressive.jpg")!!.use { it.readBytes() }
        val baseline = javaClass.getResourceAsStream("/comic/baseline.jpg")!!.use { it.readBytes() }
        assertNull(completeProgressiveScan(baseline.copyOf(32768)))
        assertNull(completeProgressiveScan(progressive.copyOf(100)))
        val partial = completeProgressiveScan(progressive.copyOf(32768))!!
        assertTrue(partial.size < progressive.size)
        assertEquals(255, partial[partial.lastIndex - 1].toInt() and 255)
        assertEquals(217, partial.last().toInt() and 255)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComicLoadingReliabilityTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Coil posts request lifecycle work to Main. Pump the paused Robolectric looper
    // while the load runs off-main, instead of blocking Main with runBlocking.
    private fun <T> imageLoad(block: suspend CoroutineScope.() -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = scope.async(block = block)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        try {
            while (!task.isCompleted) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                check(System.nanoTime() < deadline) { "Image load timed out" }
                Thread.sleep(10)
            }
            return runBlocking { task.await() }
        } finally { scope.cancel() }
    }

    @Test fun `compose double tap zoom never dispatches the first single tap`() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val zoom = ComicZoomState()
        var taps = 0
        val callbacks = ComicGestureCallbacks({ _, _ -> taps++ }, {}, {}, {})
        compose.setContent { Box(Modifier.size(300.dp).graphicsLayer {
            scaleX = zoom.scale; scaleY = zoom.scale
        }.comicZoomable(zoom, ComicReaderConfig(), callbacks)) }
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        compose.waitForIdle()
        assertEquals(0, taps)
        assertTrue("double tap must zoom; scale=${zoom.scale}, epoch=${zoom.gestureEpoch}, lastTap=${zoom.lastTapTime}, job=${zoom.releaseJob}", zoom.scale > 1.02f)
    }

    @Test fun `edge close yields to zoomed content and works again after reset`() {
        var exits = 0
        val zoomed = mutableStateOf(true)
        compose.setContent { Box(Modifier.size(300.dp).comicEdgeSwipe(true, { zoomed.value }) { exits++ }) }
        fun swipe() { compose.onRoot().performTouchInput {
            down(Offset(1f, 100f)); moveBy(Offset(180f, 0f)); up()
        } }
        swipe()
        compose.waitForIdle()
        assertEquals(0, exits)
        compose.runOnIdle { zoomed.value = false }
        swipe()
        compose.waitForIdle()
        assertEquals(1, exits)
    }

    @Test fun `new page never reads the old ready bitmap in composition`() {
        val loader = ComicPageLoader(context)
        val cfg = ComicReaderConfig()
        val book = ComicBookState()
        val first = ComicSlot(ComicPageRef.Local("first", "/missing-first"), 0)
        val second = ComicSlot(ComicPageRef.Local("second", "/missing-second"), 1)
        val bmp = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        loader.putProcessedForTest(slotCacheKey(first, cfg, book), bmp)
        val selected = mutableStateOf(first)
        val observed = mutableListOf<Pair<String, PageBitmapState>>()
        try {
            compose.setContent {
                val slot = selected.value
                val state by rememberPageBitmap(loader, slot, cfg, book)
                observed.add(slot.ref.id to state)
            }
            compose.waitForIdle()
            compose.runOnIdle { selected.value = second }
            compose.waitForIdle()
            assertTrue(observed.any { it.first == "second" })
            assertFalse(observed.filter { it.first == "second" }.any {
                (it.second as? PageBitmapState.Ready)?.bitmap === bmp
            })
        } finally { loader.shutdown() }
    }

    @Test fun `parallel remote loads download once and enhancement publishes a geometry-correct preview`() = imageLoad {
        val requests = AtomicInteger()
        val src = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val bytes = ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Thread.sleep(60)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "image/png")
                .body(bytes.toResponseBody()).build()
        }.build()
        val imageLoader = ImageLoader.Builder(context).okHttpClient(client).build()
        val loader = ComicPageLoader(context, imageLoader)
        try {
            val ref = ComicPageRef.Remote("remote", "https://example.org/page.png")
            val geo = ComicImagePipeline.Geometry(half = ComicSplitHalf.LEFT, rotationDeg = 90)
            val tone = ComicImagePipeline.Toning(enhanceMode = ComicEnhanceMode.CAS)
            val results = (0..3).map { async { loader.load(ref, "one-key", geo, tone) } }.awaitAll()
            assertEquals(1, requests.get())
            assertTrue(results.all { it.bitmap === results.first().bitmap })
            val preview = loader.peekReadingPreview("one-key")!!
            assertEquals(240, preview.width)
            assertEquals(80, preview.height)
            assertTrue(loader.previewEpoch.value > 0)
            assertSame(results.first().bitmap, loader.peekProcessed("one-key"))
        } finally { loader.shutdown(); imageLoader.shutdown() }
    }

    @Test fun `remote http error is failure even if Coil supplies an error drawable`() = imageLoad {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Unavailable").body("no image".toResponseBody()).build()
        }.build()
        val imageLoader = ImageLoader.Builder(context).okHttpClient(client)
            .error(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)).build()
        val loader = ComicPageLoader(context, imageLoader)
        try {
            val result = runCatching { loader.load(ComicPageRef.Remote("failure", "https://example.org/fail"),
                "failure", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()) }
            assertTrue(result.isFailure)
            assertNull(loader.peekProcessed("failure"))
        } finally { loader.shutdown(); imageLoader.shutdown() }
    }

    @Test fun `enhancement off shows a progressive page before the slow response finishes`() = imageLoad {
        val bytes = javaClass.getResourceAsStream("/comic/progressive.jpg")!!.use { it.readBytes() }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val releaseTail = java.util.concurrent.CountDownLatch(1)
        val requests = AtomicInteger()
        val serving = Thread {
            try {
                server.accept().use { socket ->
                    requests.incrementAndGet()
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes, 0, 8192); out.flush()
                    Thread.sleep(350)
                    out.write(bytes, 8192, 32768 - 8192); out.flush()
                    releaseTail.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    out.write(bytes, 32768, bytes.size - 32768); out.flush()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val imageLoader = com.example.ui.buildComicImageLoader(context)
        val loader = ComicPageLoader(context, imageLoader)
        try {
            val task = async { loader.load(ComicPageRef.Remote("stream", "http://127.0.0.1:${server.localPort}/page.jpg"),
                "stream", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()) }
            kotlinx.coroutines.withTimeout(6000) { while (loader.previewEpoch.value == 0L) kotlinx.coroutines.delay(10) }
            assertFalse("Preview must arrive while the full HTTP response is still pending", task.isCompleted)
            val preview = loader.peekReadingPreview("stream")!!
            assertEquals(480, preview.width)
            assertEquals(720, preview.height)
            assertTrue(android.graphics.Color.red(preview.getPixel(240, 10)) > 100)
            assertNull("Partial data must not be cached as a final page", loader.peekProcessed("stream"))
            releaseTail.countDown()
            val final = task.await().bitmap
            assertSame(final, loader.peekProcessed("stream"))
            assertEquals(1, requests.get())
        } finally {
            releaseTail.countDown(); server.close(); serving.join(1000)
            loader.shutdown(); imageLoader.shutdown()
        }
    }

    @Test fun `remote prefetch waits until the visible page is available`() = imageLoad {
        val requests = AtomicInteger()
        val bmp = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val imageLoader = ImageLoader.Builder(context).okHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/png").body(bytes.toResponseBody()).build()
        }.build()).build()
        val loader = ComicPageLoader(context, imageLoader)
        val entries = listOf(
            ComicPageLoader.WindowEntry(ComicPageRef.Remote("current", "https://example.org/current"), "current", ComicImagePipeline.Geometry(), visible = true),
            ComicPageLoader.WindowEntry(ComicPageRef.Remote("next", "https://example.org/next"), "next", ComicImagePipeline.Geometry()),
        )
        try {
            loader.preloadWindow(entries, ComicImagePipeline.Toning())
            kotlinx.coroutines.delay(250)
            assertEquals("No speculative requests while the foreground page is waiting", 0, requests.get())
            loader.putProcessedForTest("current", bmp)
            loader.preloadWindow(entries, ComicImagePipeline.Toning())
            kotlinx.coroutines.withTimeout(6000) { while (loader.peekProcessed("next") == null) kotlinx.coroutines.delay(10) }
            assertEquals(1, requests.get())
        } finally { loader.shutdown(); imageLoader.shutdown() }
    }
}
