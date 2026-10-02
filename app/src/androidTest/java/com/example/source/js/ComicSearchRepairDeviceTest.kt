package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ComicSearchRepairDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun singleSearchRedirectUsesItsOwnIdDespiteEarlierRecommendedLinks() = runBlocking {
        val context = context()
        val engine = JsSourceEngine(context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/bilimanga.js").bufferedReader().use { it.readText() }, "bilimanga_fixture", context)
        val html = """<a href="/detail/999.html">推荐漫画</a><div id="bookDetailWrapper"><div class="book-title">火影忍者外传</div><img class="book-cover" src="/files/1230/1230s.jpg"></div>"""
        val reply = JSONObject(engine.call("src.parseSearch({url:'https://www.bilimanga.net/detail/1230.html',body:${JSONObject.quote(html)}},1)")!!)
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val books = reply.getJSONObject("data").getJSONArray("comics")
        assertEquals(1, books.length())
        assertEquals("https://www.bilimanga.net/detail/1230.html", books.getJSONObject(0).getString("id"))
        assertEquals("火影忍者外传", books.getJSONObject(0).getString("title"))
        val unrelated = JSONObject(engine.call("src.parseSearch({url:'https://www.bilimanga.net/',body:'<a class=\"book-layout\" href=\"/detail/999.html\"><img alt=\"推荐漫画\"></a>'},1)")!!)
        assertFalse(unrelated.toString(), unrelated.getBoolean("ok"))
    }

    @Test fun closedChromiumResponseRetriesWholeGetThroughHttpWithoutSystemProxy() {
        val context = context()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val proxy = android.provider.Settings.Global.getString(context.contentResolver, "http_proxy")
        fun shell(command: String) { automation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        } }
        val calls = AtomicInteger()
        val server = ServerSocket(0)
        val thread = Thread {
            while (!server.isClosed) runCatching { server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                val partial = calls.incrementAndGet() == 1
                socket.getOutputStream().write((if (partial)
                    "HTTP/1.1 200 OK\r\nContent-Length: 300\r\nConnection: close\r\n\r\nx"
                else "HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\ncomplete").toByteArray())
            } }
        }.apply { isDaemon = true; start() }
        try {
            shell("settings put global http_proxy :0")
            val deadline = System.currentTimeMillis() + 5000
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            while (cm.defaultProxy != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            // A preceding proxy regression can leave the selector's two-second
            // cache pointing at its now-closed loopback proxy.
            Thread.sleep(2100)
            val url = "http://127.0.0.1:${server.localPort}/comic"
            val reply = JsMessageHandler(context, "pufei").handle(mapOf("method" to "http", "http_method" to "GET", "url" to url))
            val parsed = JSONObject(reply.toString())
            assertFalse(parsed.toString(), parsed.has("error"))
            assertEquals(200, parsed.getInt("status"))
            assertEquals("complete", parsed.getString("body"))
            assertEquals(url, parsed.getString("url"))
            assertEquals(2, calls.get())
        } finally {
            server.close(); thread.join(500)
            if (proxy.isNullOrBlank()) shell("settings delete global http_proxy") else shell("settings put global http_proxy $proxy")
        }
    }
}
