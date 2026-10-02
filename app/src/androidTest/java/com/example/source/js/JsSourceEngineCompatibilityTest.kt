package com.example.source.js

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.example.source.SourceResult
import com.example.source.LoginCredential
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercises the actual native QuickJS engine, including assets and Kotlin bridges. */
@RunWith(AndroidJUnit4::class)
class JsSourceEngineCompatibilityTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun runtime(context: Context) = context.assets.open("venera/_venera_.js")
        .bufferedReader().use { it.readText() }

    @Test
    fun initializesAndSearchesWithoutNetwork() = runBlocking {
        val context = context()
        val engine = JsSourceEngine(
            runtimeJs = runtime(context),
            sourceJs = """
                class TestSource extends ComicSource {
                    name = 'Test';
                    key = 'runtime_test';
                    search = {load: async (keyword, options, page) => ({
                        comics: [{id:'1',title:keyword,cover:'',tags:[]}], maxPage:1
                    })};
                }
            """.trimIndent(),
            sourceKey = "runtime_test",
            context = context
        )
        val result = JSONObject(engine.call("src.search.load.call(src, 'test', [], 1)")!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("test", result.getJSONObject("data").getJSONArray("comics")
            .getJSONObject(0).getString("title"))
        val again = JSONObject(engine.call("src.search.load.call(src, 'second', [], 1)")!!)
        assertTrue(again.toString(), again.getBoolean("ok"))
        assertEquals("second", again.getJSONObject("data").getJSONArray("comics")
            .getJSONObject(0).getString("title"))
    }

    @Test fun initializesBundledBiliManga() = verifyBundledSource("bilimanga")
    @Test fun initializesBundledVomic() = verifyBundledSource("vomic")

    @Test fun mycomicPreservesOriginalHeadersUntilACompletedVerificationIsStored() = runBlocking {
        val context = context()
        val preferences = context.getSharedPreferences("js_source_website_verification", Context.MODE_PRIVATE)
        val key = "mycomic_user_agent"
        val previous = preferences.getString(key, null)
        val script = """
            const headers = {'User-Agent':'original-source-UA','Sec-Ch-Ua-Platform':'Windows'};
            class TestSource extends ComicSource {
                key='mycomic';
                search={load:async()=>({comics:[{
                    id:headers['Sec-Ch-Ua-Platform'] || 'cleared',
                    title:headers['User-Agent'],cover:'',tags:[]
                }],maxPage:1})};
            }
        """.trimIndent()
        try {
            preferences.edit().remove(key).commit()
            val ordinary = JsComicSource(context, "mycomic", "MYComic", "1", script).search("test")
            assertTrue(ordinary.toString(), ordinary is SourceResult.Success)
            val original = (ordinary as SourceResult.Success).data.single()
            assertEquals("original-source-UA", original.title)
            assertEquals("Windows", original.id)

            preferences.edit().putString(key, "verified-webview-UA").commit()
            val restored = JsComicSource(context, "mycomic", "MYComic", "1", script).search("test")
            assertTrue(restored.toString(), restored is SourceResult.Success)
            val verified = (restored as SourceResult.Success).data.single()
            assertEquals("verified-webview-UA", verified.title)
            assertEquals("cleared", verified.id)
        } finally {
            preferences.edit().apply {
                if (previous == null) remove(key) else putString(key, previous)
            }.commit()
        }
    }

    @Test fun cookieBridgeReturnsNamedObjectsAndIncludesParentDomains() = runBlocking {
        val context = context()
        val preferences = context.getSharedPreferences("js_source_cookies", Context.MODE_PRIVATE)
        preferences.edit().putString("ck_audit.invalid", "parent_ticket=a=b")
            .putString("ck_www.audit.invalid", "guard_ticket=ready").commit()
        try {
            val engine = JsSourceEngine(runtime(context),
                "class TestSource extends ComicSource {key='cookie_audit';}", "cookie_audit", context)
            val result = JSONObject(engine.call("Network.getCookies('https://www.audit.invalid/')")!!)
            assertTrue(result.toString(), result.getBoolean("ok"))
            val cookies = result.getJSONArray("data")
            val byName = (0 until cookies.length()).associate {
                cookies.getJSONObject(it).getString("name") to cookies.getJSONObject(it).getString("value")
            }
            assertEquals("a=b", byName["parent_ticket"])
            assertEquals("ready", byName["guard_ticket"])
        } finally {
            preferences.edit().remove("ck_audit.invalid").remove("ck_www.audit.invalid").commit()
        }
    }

    @Test fun shortLivedCookiesExpireAndServerDeletionIsHonored() = runBlocking {
        val context = context()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requests = CompletableFuture<List<String>>()
        val serving = Thread {
            try {
                val captured = mutableListOf<String>()
                repeat(3) { index -> server.accept().use { socket ->
                    socket.soTimeout = 8000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0)
                        header.append(byte.toChar())
                    }
                    captured.add(header.toString())
                    val cookies = when (index) {
                        0 -> "Set-Cookie: delay=1; Max-Age=1; Path=/\r\nSet-Cookie: session=ready; Path=/\r\n"
                        1 -> "Set-Cookie: session=; Max-Age=0; Path=/\r\n"
                        else -> ""
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" + cookies +
                        "Content-Length: 2\r\nConnection: close\r\n\r\n{}").toByteArray())
                } }
                requests.complete(captured)
            } catch (e: Exception) { requests.completeExceptionally(e) }
        }.apply { isDaemon = true; start() }
        JsCookieJar.clear(context, "127.0.0.1")
        try {
            val engine = JsSourceEngine(runtime(context),
                "class TestSource extends ComicSource {key='expiry_audit';}", "expiry_audit", context)
            suspend fun get() {
                val reply = JSONObject(withTimeout(10_000) {
                    engine.call("Network.get('http://127.0.0.1:${server.localPort}/check').then(r=>r.status)")
                }!!)
                assertTrue(reply.toString(), reply.getBoolean("ok"))
                assertEquals(200, reply.getInt("data"))
            }
            get()
            assertTrue(JsCookieJar.cookieHeader(context, "http://127.0.0.1/").contains("delay=1"))
            kotlinx.coroutines.delay(1100)
            get()
            get()
            val headers = requests.get(1, TimeUnit.SECONDS)
            assertFalse(headers[1], headers[1].contains("delay=1"))
            assertTrue(headers[1], headers[1].contains("session=ready"))
            assertFalse(headers[2], headers[2].contains("session="))
        } finally { JsCookieJar.clear(context, "127.0.0.1"); server.close(); serving.join(1000) }
    }

    @Test fun cookieScopeAndLegacySearchThrottleDoNotDiscardLoginSessions() {
        val context = context()
        listOf("www.audit.invalid", "audit.invalid").forEach { JsCookieJar.clear(context, it) }
        val prefs = context.getSharedPreferences("js_source_cookies", Context.MODE_PRIVATE)
        prefs.edit().putString("ck_ymcdnyfqdapp.ikmmh.com", "ss_search_delay=1; PHPSESSID=legacy").commit()
        try {
            assertEquals("PHPSESSID=legacy", JsCookieJar.cookieHeader(context, "https://ymcdnyfqdapp.ikmmh.com/"))
            JsCookieJar.saveSetCookies(context, "https://www.audit.invalid/private", listOf(
                "parent=shared; Domain=audit.invalid; Secure; Path=/private",
                "child=local; Path=/; Secure"
            ))
            assertEquals("parent=shared", JsCookieJar.cookieHeader(context, "https://other.audit.invalid/private/page"))
            assertEquals("", JsCookieJar.cookieHeader(context, "http://other.audit.invalid/private/page"))
            assertEquals("", JsCookieJar.cookieHeader(context, "https://other.audit.invalid/privateSuffix"))
            assertEquals("child=local", JsCookieJar.cookieHeader(context, "https://www.audit.invalid/public"))
        } finally {
            listOf("ymcdnyfqdapp.ikmmh.com", "www.audit.invalid", "audit.invalid").forEach { JsCookieJar.clear(context, it) }
        }
    }

    @Test fun structuredPostBodyReachesTheHttpServer() = runBlocking {
        val context = context()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val received = CompletableFuture<Pair<String, String>>()
        val serving = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 8000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0)
                        header.append(byte.toChar())
                    }
                    val size = Regex("(?im)^Content-Length: (\\d+)").find(header)!!.groupValues[1].toInt()
                    val bytes = ByteArray(size)
                    var offset = 0
                    while (offset < size) {
                        val read = input.read(bytes, offset, size - offset)
                        check(read > 0)
                        offset += read
                    }
                    received.complete(header.toString() to bytes.toString(Charsets.UTF_8))
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()
                    )
                }
            } catch (e: Exception) { received.completeExceptionally(e) }
        }.apply { isDaemon = true; start() }
        try {
            val engine = JsSourceEngine(runtime(context),
                "class TestSource extends ComicSource {key='post_audit';}", "post_audit", context)
            val result = JSONObject(withTimeout(10_000) { engine.call("""
                Network.post('http://127.0.0.1:${server.localPort}/login', {},
                    {label:'测试',nested:{number:42},list:[1,true]}).then(r=>r.status)
            """.trimIndent()) }!!)
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(200, result.getInt("data"))
            val (header, body) = received.get(1, TimeUnit.SECONDS)
            assertTrue(header, header.lowercase().contains("content-type: application/json"))
            val data = JSONObject(body)
            assertEquals("测试", data.getString("label"))
            assertEquals(42, data.getJSONObject("nested").getInt("number"))
            assertTrue(data.getJSONArray("list").getBoolean(1))
        } finally { server.close(); serving.join(1000) }
    }

    @Test fun simultaneousChapterRequestsShareTheirResultAndLoginInvalidatesIt() = runBlocking {
        val source = JsComicSource(context(), "chapter_cache_audit", "Cache audit", "1", """
            class TestSource extends ComicSource {
                key='chapter_cache_audit'; calls=0;
                account={login:async()=> 'ok',logout:()=>null};
                comic={loadInfo:async(id)=>({title:id,chapters:{ep:'Call '+(++this.calls)}})};
            }
        """.trimIndent())
        suspend fun title(): String {
            val result = source.getChapters("book")
            assertTrue(result.toString(), result is SourceResult.Success)
            return (result as SourceResult.Success).data.single().title
        }
        val simultaneous = (0 until 3).map { async { title() } }.awaitAll()
        assertEquals(listOf("Call 1", "Call 1", "Call 1"), simultaneous)
        assertEquals(SourceResult.Success(true), source.login(LoginCredential("test", "test")))
        assertEquals("Call 2", title())
        source.logout()
        assertEquals("Call 3", title())
    }

    @Test fun dynamicSiteConfigurationAndJavaScriptEscapeRegexRemainCompatible() = runBlocking {
        val context = context()
        val engine = JsSourceEngine(runtime(context),
            "class TestSource extends ComicSource {key='runtime_compat';}", "runtime_compat", context)
        val configuration = JSONObject(engine.call(
            "(eval('var auditDynamic={answer:(() => 42)()};'), auditDynamic.answer)"
        )!!)
        assertTrue(configuration.toString(), configuration.getBoolean("ok"))
        assertEquals(42, configuration.getInt("data"))
        val escape = JSONObject(engine.call("""
            '[a]+b?'.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&')
        """.trimIndent())!!)
        assertTrue(escape.toString(), escape.getBoolean("ok"))
        assertEquals("\\[a\\]\\+b\\?", escape.getString("data"))
        val instrumented = JSONObject(engine.call(
            "__cialloInstrument('const f=()=>{while(false) {} };')"
        )!!).getString("data")
        assertTrue(instrumented, instrumented.contains("while(false) {;__cialloTick();"))
    }

    @Test fun dynamicLoopCanBeCancelledAndTheEngineRemainsUsable() = runBlocking {
        val context = context()
        val engine = JsSourceEngine(runtime(context),
            "class TestSource extends ComicSource {key='runtime_cancel';}", "runtime_cancel", context)
        assertTrue(JSONObject(engine.call("null")!!).getBoolean("ok"))
        var cancelled = false
        try {
            withTimeout(300) { engine.call("eval('while(true) {}')") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("Dynamic loops must stop when their request is cancelled", cancelled)
        val recovered = JSONObject(engine.call("42")!!)
        assertTrue(recovered.toString(), recovered.getBoolean("ok"))
        assertEquals(42, recovered.getInt("data"))
    }

    private fun verifyBundledSource(key: String) = runBlocking {
        val context = context()
        val engine = JsSourceEngine(
            runtimeJs = runtime(context),
            sourceJs = context.assets.open("js_extra/$key.js").bufferedReader().use { it.readText() },
            sourceKey = key,
            context = context
        )
        // Exercise the same eager initialization that JsComicSource.getEngine uses.
        assertTrue(JSONObject(engine.call("null")!!).getBoolean("ok"))
        val result = JSONObject(engine.call("({key:src.key,hasSearch:typeof src.search.load === 'function'})")!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(key, result.getJSONObject("data").getString("key"))
        assertTrue(result.getJSONObject("data").getBoolean("hasSearch"))
    }
}
