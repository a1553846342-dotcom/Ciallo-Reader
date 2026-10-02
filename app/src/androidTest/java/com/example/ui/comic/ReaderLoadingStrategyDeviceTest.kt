package com.example.ui.comic

import android.content.Context
import android.util.Log
import com.example.data.PreferencesManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.buildComicImageLoader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReaderLoadingStrategyDeviceTest {
    @Test fun neighborsWaitForTheVisiblePageAndPromotionBypassesTheWait() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)
        val oldWifi = prefs.preloadWifiOnly
        prefs.preloadWifiOnly = false
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("source-audit/progressive.jpg").use { it.readBytes() }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        val currentStarted = CountDownLatch(1)
        val currentRequests = AtomicInteger()
        val neighborRequests = AtomicInteger()
        val serving = Thread {
            try { while (!server.isClosed) {
                val socket = server.accept()
                Thread {
                    socket.use {
                        try {
                            val reader = socket.getInputStream().bufferedReader()
                            val request = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val current = request.contains("/current.jpg")
                            if (current) { currentRequests.incrementAndGet(); currentStarted.countDown() }
                            else neighborRequests.incrementAndGet()
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (current) {
                                out.write(bytes, 0, 32768); out.flush()
                                release.await(12, TimeUnit.SECONDS)
                                out.write(bytes, 32768, bytes.size - 32768)
                            } else out.write(bytes)
                            out.flush()
                        } catch (_: Exception) { }
                    }
                }.apply { isDaemon = true; start() }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val imageLoader = buildComicImageLoader(context)
        val loader = ComicPageLoader(context, imageLoader)
        val current = ComicPageRef.Remote("priority-current", "http://127.0.0.1:${server.localPort}/current.jpg")
        val neighbor = ComicPageRef.Remote("priority-neighbor", "http://127.0.0.1:${server.localPort}/neighbor.jpg")
        val geo = ComicImagePipeline.Geometry()
        val tone = ComicImagePipeline.Toning()
        try {
            loader.preloadWindow(listOf(ComicPageLoader.WindowEntry(current, current.id, geo, visible = true)), tone)
            val foreground = async(Dispatchers.Default) { loader.loadForDisplay(current, current.id, geo, tone, visible = true) }
            assertTrue(currentStarted.await(5, TimeUnit.SECONDS))
            val background = async(Dispatchers.Default) { loader.loadForDisplay(neighbor, neighbor.id, geo, tone, visible = false) }
            delay(400)
            assertEquals("offscreen producer must yield to current", 0, neighborRequests.get())
            assertFalse(background.isCompleted)
            // The same page becoming visible must load even while the old page is slow.
            val promoted = withTimeout(6000) { loader.loadForDisplay(neighbor, neighbor.id, geo, tone, visible = true) }
            assertNotNull(promoted.bitmap)
            assertEquals(1, neighborRequests.get())
            release.countDown()
            withTimeout(6000) { foreground.await() }
            assertSame(promoted.bitmap, withTimeout(6000) { background.await() }.bitmap)
            assertEquals(1, currentRequests.get())
            assertEquals(1, neighborRequests.get())
            Log.i("ReaderStrategyAudit", "currentPriority=PASS promotion=PASS neighborDownloads=1")
        } finally {
            release.countDown(); server.close(); serving.join(1000)
            loader.shutdown(); imageLoader.shutdown(); prefs.preloadWifiOnly = oldWifi
        }
    }

    @Test fun slowProgressiveImageShowsPreviewWithEnhancementOffAndDownloadsOnce() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("source-audit/progressive.jpg").use { it.readBytes() }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val releaseTail = CountDownLatch(1)
        val requests = AtomicInteger()
        val serving = Thread {
            try { server.accept().use { socket ->
                requests.incrementAndGet()
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes, 0, 8192); out.flush()
                Thread.sleep(350)
                out.write(bytes, 8192, 32768 - 8192); out.flush()
                releaseTail.await(8, TimeUnit.SECONDS)
                out.write(bytes, 32768, bytes.size - 32768); out.flush()
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val imageLoader = buildComicImageLoader(context)
        val loader = ComicPageLoader(context, imageLoader)
        val ref = ComicPageRef.Remote("stream-test", "http://127.0.0.1:${server.localPort}/page.jpg")
        try {
            val task = async(Dispatchers.Default) { loader.load(ref, "stream-test",
                ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()) }
            withTimeout(6000) { while (loader.previewEpoch.value == 0L) delay(10) }
            assertFalse(task.isCompleted)
            assertNotNull(loader.peekReadingPreview("stream-test"))
            assertNull(loader.peekProcessed("stream-test"))
            releaseTail.countDown()
            val final = task.await().bitmap
            assertSame(final, loader.load(ref, "stream-test",
                ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap)
            assertEquals(1, requests.get())
            assertTrue(final.width > 0 && final.height > 0)
            Log.i("ReaderStrategyAudit", "enhancement=OFF partialPreview=PASS finalBitmap=PASS cache=PASS downloads=1")
        } finally {
            releaseTail.countDown(); server.close(); serving.join(1000)
            loader.shutdown(); imageLoader.shutdown()
        }
    }
}
