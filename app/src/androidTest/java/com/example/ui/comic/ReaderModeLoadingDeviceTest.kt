package com.example.ui.comic

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.PreferencesManager
import com.example.ui.buildComicImageLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the real reader composition, including offscreen Pager/LazyColumn producers. */
@RunWith(AndroidJUnit4::class)
class ReaderModeLoadingDeviceTest {
    @Test fun everyModeYieldsNeighborDownloadsUntilTheVisibleImageFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)
        val oldWifi = prefs.preloadWifiOnly
        prefs.preloadWifiOnly = false
        val bytes = instrumentation.context.assets.open("source-audit/progressive.jpg").use { it.readBytes() }
        val cases = listOf(
            "curl" to ComicReaderConfig(pageAnim = ComicPageAnim.CURL),
            "slide" to ComicReaderConfig(pageAnim = ComicPageAnim.SLIDE),
            "fade" to ComicReaderConfig(pageAnim = ComicPageAnim.FADE),
            "none" to ComicReaderConfig(pageAnim = ComicPageAnim.NONE),
            "magnetic" to ComicReaderConfig(mode = ComicMode.MAGNETIC),
            "webtoon" to ComicReaderConfig(mode = ComicMode.WEBTOON, direction = ComicDirection.TTB),
            "continuous" to ComicReaderConfig(mode = ComicMode.CONTINUOUS, direction = ComicDirection.TTB),
        )
        try { for ((name, config) in cases) {
            val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val neighbors = AtomicInteger()
            val currentRequests = AtomicInteger()
            val serving = Thread {
                try { while (!server.isClosed) {
                    val socket = server.accept()
                    Thread {
                        socket.use {
                            try {
                                val reader = socket.getInputStream().bufferedReader()
                                val first = reader.readLine()
                                while (!reader.readLine().isNullOrEmpty()) { }
                                val current = first.contains("/0.jpg")
                                if (current) { currentRequests.incrementAndGet(); started.countDown() }
                                else neighbors.incrementAndGet()
                                val out = socket.getOutputStream()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                if (current) {
                                    out.write(bytes, 0, 8192); out.flush()
                                    Thread.sleep(350)
                                    out.write(bytes, 8192, 32768 - 8192); out.flush()
                                    release.await(12, TimeUnit.SECONDS)
                                    out.write(bytes, 32768, bytes.size - 32768)
                                } else out.write(bytes)
                                out.flush()
                            } catch (_: Exception) { }
                        }
                    }.apply { isDaemon = true; start() }
                } } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val bookKey = "mode-audit-$name-${server.localPort}"
            ComicSettingsStore(context).saveBookConfig(bookKey, config.copy(splitWide = false))
            val loader = buildComicImageLoader(context)
            val pages = (0..3).map { ComicPageRef.Remote("$bookKey-$it", "http://127.0.0.1:${server.localPort}/$it.jpg") }
            val scenario = ActivityScenario.launch(ComicReaderTestActivity::class.java)
            try {
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    ComicReaderCore(pages, name, null, bookKey, 0, remoteImageLoader = loader, onExit = {})
                } } }
                assertTrue("$name current request", started.await(8, TimeUnit.SECONDS))
                var preview = instrumentation.uiAutomation.takeScreenshot()
                val previewUntil = SystemClock.uptimeMillis() + 6000
                fun imagePixels(bitmap: android.graphics.Bitmap): Int {
                    var light = 0
                    // Reader image area excludes controls. The fixture is predominantly gray.
                    for (y in 5..14) for (x in 2..17) {
                        val pixel = bitmap.getPixel(x * bitmap.width / 20, y * bitmap.height / 20)
                        if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) > 150) light++
                    }
                    return light
                }
                while (imagePixels(preview) < 80 && SystemClock.uptimeMillis() < previewUntil) {
                    SystemClock.sleep(100)
                    preview = instrumentation.uiAutomation.takeScreenshot()
                }
                assertTrue("$name readable partial image before tail", imagePixels(preview) >= 80)
                assertEquals("$name speculative requests while current is incomplete", 0, neighbors.get())
                val file = File(context.filesDir, "reader-feedback/mode_${name}_preview.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                release.countDown()
                val until = SystemClock.uptimeMillis() + 8000
                while (neighbors.get() == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                assertTrue("$name resumes neighbor preload", neighbors.get() > 0)
                assertEquals("$name current downloaded once", 1, currentRequests.get())
                SystemClock.sleep(350)
                val finalImage = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "reader-feedback/mode_${name}_final.png").outputStream().use {
                    finalImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                Log.i("ReaderStrategyAudit", "mode=$name priority=PASS readablePreview=PASS neighborResume=PASS currentDownloads=1")
            } finally {
                release.countDown(); scenario.close(); server.close(); serving.join(1000); loader.shutdown()
            }
        } } finally { prefs.preloadWifiOnly = oldWifi }
    }
}
