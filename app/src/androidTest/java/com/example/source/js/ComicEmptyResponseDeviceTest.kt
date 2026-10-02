package com.example.source.js

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ComicEmptyResponseDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private class Server(private val observeBody: ((String) -> Unit)? = null,
                         private val respond: (Int, String) -> ByteArray) : AutoCloseable {
        private val socket = ServerSocket(0)
        val calls = AtomicInteger()
        val url get() = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            while (!socket.isClosed) runCatching { socket.accept().use { client ->
                client.soTimeout = 3000
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                var length = 0
                while (true) {
                    val header = reader.readLine().orEmpty()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                observeBody?.invoke(String(body, 0, read))
                client.getOutputStream().write(respond(calls.incrementAndGet(), request))
            } }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); thread.join(500) }
    }
    private fun response(body: ByteArray, charset: String = "utf-8") =
        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=$charset\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }; Unit }
    private fun proxy(value: String) {
        shell("settings put global http_proxy $value")
        val cm = context().getSystemService(android.net.ConnectivityManager::class.java)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline &&
            (if (value == ":0") cm.defaultProxy != null else cm.defaultProxy?.port != value.substringAfter(':').toInt())) Thread.sleep(50)
        // Let the shared proxy selector's short-lived cache expire.
        Thread.sleep(2100)
    }
    private fun <T> withProxy(value: String, block: () -> T): T {
        val original = Settings.Global.getString(context().contentResolver, "http_proxy")
        try { proxy(value); return block() }
        finally { if (original.isNullOrBlank()) shell("settings delete global http_proxy") else shell("settings put global http_proxy $original") }
    }
    private fun get(key: String, url: String, handler: JsMessageHandler = JsMessageHandler(context(), key)): JSONObject =
        JSONObject(handler.handle(mapOf("method" to "http", "http_method" to "GET", "url" to url)).toString())

    @Test fun ikmmhPublicReadBatchRetryPreservesItsFormBody() = withProxy(":0") {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val payload = "id=123&aid=24&offset=10&limit=10"
        Server(observeBody = { bodies.add(it) }) { count, request ->
            assertTrue(request, request.startsWith("POST /api/comic/read/pics "))
            response(if (count == 1) ByteArray(0) else "{\"code\":1}".toByteArray())
        }.use { server ->
            val reply = JSONObject(JsMessageHandler(context(), "ikmmh").handle(mapOf(
                "method" to "http", "http_method" to "POST", "url" to server.url + "/api/comic/read/pics",
                "headers" to mapOf("Content-Type" to "application/x-www-form-urlencoded"), "data" to payload)).toString())
            assertFalse(reply.toString(), reply.has("error"))
            assertEquals("{\"code\":1}", reply.getString("body"))
            assertEquals(listOf(payload, payload), bodies)
        }
    }

    @Test fun empty200IsRetriedAndGbkDetailIsDecodedWithoutBinaryBridge() = withProxy(":0") {
        val title = "我家老婆来自一千年前"
        Server { count, request ->
            if (count == 1) response(ByteArray(0)) else {
                assertTrue(request, request.contains("_ciallo_retry="))
                response("<h1>$title</h1>".toByteArray(Charset.forName("GBK")), "gbk")
            }
        }.use { server ->
            val reply = get("pufei", server.url + "/comic/fixture")
            assertFalse(reply.toString(), reply.has("error"))
            assertEquals("<h1>$title</h1>", reply.getString("body"))
            assertEquals(2, server.calls.get())
        }
    }

    @Test fun bothEmptyRoutesReturnAnErrorInsteadOfPassingNullToSources() = withProxy(":0") {
        for (key in listOf("pufei", "bilimanga")) Server { _, _ -> response(ByteArray(0)) }.use { server ->
            val reply = get(key, server.url + "/detail/fixture")
            assertTrue(reply.toString(), reply.getString("error").contains("空响应"))
            assertFalse(reply.has("body"))
            assertEquals(2, server.calls.get())
        }
    }

    @Test fun biliTicketRedemptionIsAllowedToReturnEmptyWithoutRetry() = withProxy(":0") {
        Server { _, _ -> response(ByteArray(0)) }.use { server ->
            val reply = get("bilimanga", server.url + "/search.html?search_guard=redeem")
            assertEquals(200, reply.getInt("status"))
            assertEquals("", reply.getString("body"))
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun brokenLearnedProxyRouteCanRetryThroughChromium() {
        for (key in listOf("pufei", "bilimanga")) {
            Server { _, _ -> response("<h1>真实详情</h1>".toByteArray()) }.use { origin ->
                Server { _, _ -> "HTTP/1.1 200 OK\r\nContent-Length: 300\r\nConnection: close\r\n\r\nx".toByteArray() }.use { brokenProxy ->
                    withProxy(brokenProxy.url.removePrefix("http://")) {
                        JsSourceProxy.rememberProxySuccess(context(), origin.url)
                        val reply = get(key, origin.url + "/detail/fixture")
                        assertFalse(reply.toString(), reply.has("error"))
                        assertEquals("<h1>真实详情</h1>", reply.getString("body"))
                        assertTrue("The selected HTTP proxy must actually be attempted", brokenProxy.calls.get() > 0)
                        assertEquals(1, origin.calls.get())
                    }
                }
            }
        }
    }

    @Test fun sourceScriptsRejectNullAndPufeiHtmlUsesTheTextContract() = runBlocking {
        val context = context()
        for (key in listOf("pufei", "bilimanga")) {
            val engine = JsSourceEngine(context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
                context.assets.open("js_extra/$key.js").bufferedReader().use { it.readText() }, "${key}_fixture", context)
            val result = JSONObject(engine.call("""
                (async()=>{
                    Network.get=async()=>({status:200,body:null});
                    if (src.key==='pufei') src.request=async()=>({status:200,body:null});
                    Network.fetchBytes=async()=>{throw new Error('HTML must use the text contract')};
                    return src.comic.loadInfo('https://example.test/detail/fixture');
                })()
            """.trimIndent())!!)
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertTrue(result.toString(), result.getString("error").contains(
                if (key == "pufei") "没有返回完整漫画详情或章节目录" else "空响应"))
            assertFalse(result.toString(), result.getString("error").contains("of null"))
        }
    }
}
