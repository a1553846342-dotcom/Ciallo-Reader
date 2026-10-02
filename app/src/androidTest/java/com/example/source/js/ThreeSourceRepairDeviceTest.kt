package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the production patches through native QuickJS and the real Jsoup DOM bridge. */
@RunWith(AndroidJUnit4::class)
class ThreeSourceRepairDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun patched(key: String, script: String): String = JsSourceRepo::class.java
        .getDeclaredMethod("patchScript", String::class.java, String::class.java)
        .apply { isAccessible = true }.invoke(JsSourceRepo, key, script) as String

    private fun engine(key: String, script: String) = JsSourceEngine(
        context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
        patched(key, script), key + "_fixture", context
    )

    private val wnacg = """
        class Wnacg extends ComicSource {
            get baseUrl() { return 'https://site.example'; }
            search = {load:async()=>{throw 'old search';}}
            // favorite related
        }
    """.trimIndent()

    private suspend fun search(html: String): JSONObject {
        val engine = engine("wnacg", wnacg)
        val setup = JSONObject(engine.call("(()=>{Network.get = async () => ({status:200,body:${JSONObject.quote(html)}}); return null;})()")!!)
        assertTrue(setup.toString(), setup.getBoolean("ok"))
        return JSONObject(engine.call("src.search.load.call(src,'fixture',[],1)")!!)
    }

    @Test fun mobileSearchParsesCardsWithoutLegacyTotalAndKeepsPagination() = runBlocking {
        val result = search("""<ul id="classify_container">
            <li><a class="ImgA" href="/photos-index-aid-123.html"><img src="//cdn.example/123.webp"><span>测试漫画</span></a><span class="info">20张图片</span></li>
            <li><a href="/advertisement">广告</a></li></ul>
            <div class="paginator"><a href="/search/?q=a&amp;p=10">10</a></div>""")
        assertTrue(result.toString(), result.getBoolean("ok"))
        val data = result.getJSONObject("data")
        val comic = data.getJSONArray("comics").getJSONObject(0)
        assertEquals(1, data.getJSONArray("comics").length())
        assertEquals("123", comic.getString("id"))
        assertEquals("测试漫画", comic.getString("title"))
        assertEquals("https://cdn.example/123.webp", comic.getString("cover"))
        assertEquals(10, data.getInt("maxPage"))
    }

    @Test fun desktopSearchRetainsCardsAndTotal() = runBlocking {
        val result = search("""<div class="grid"><div class="gallary_wrap"><ul class="cc"><li>
            <div class="pic_box"><a href="/photos-index-aid-456.html"><img src="//cdn.example/456.jpg"></a></div>
            <div class="info"><div class="title"><a href="/photos-index-aid-456.html">旧版漫画</a></div><div class="info_col">信息</div></div>
            </li></ul></div></div><p class="result"><b>1,200</b></p>""")
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("456", result.getJSONObject("data").getJSONArray("comics").getJSONObject(0).getString("id"))
        assertEquals(50, result.getJSONObject("data").getInt("maxPage"))
    }

    @Test fun emptySearchIsEmptyWhileInvalidResponseIsAnError() = runBlocking {
        val empty = search("<ul id='classify_container'></ul>")
        assertTrue(empty.toString(), empty.getBoolean("ok"))
        assertEquals(0, empty.getJSONObject("data").getJSONArray("comics").length())
        assertEquals(1, empty.getJSONObject("data").getInt("maxPage"))
        val failed = search("<html><title>Verification required</title></html>")
        assertFalse(failed.toString(), failed.getBoolean("ok"))
        assertFalse(failed.getString("error").contains("undefined"))
    }

    private val mxs = """
        class MXS extends ComicSource {
            get baseUrl() { return 'https://catalogue.example'; }
            async fetchDocument() { return new HtmlDocument(globalThis.fixtureHtml); }
            comic = {
                loadEp: async (comicId, epId) => { throw 'old reader'; },
                // 加载评论列表
                loadComments:async()=>({comments:[]})
            };
        }
    """.trimIndent()

    @Test fun mxsPreservesImageHostsAndOrderAndSupportsLazyAttributes() = runBlocking {
        val engine = engine("mxs", mxs)
        val html = """<img class="lazy" data-original=" https://cdn.example/1.jpg ">
            <img class="lazy" data-src="//cdn.example/2.jpg"><img class="lazy" data-lazy-src="/3.jpg">
            <img class="lazy" src="data:image/gif;base64,AA"><img class="lazy">
            <div class="comicpage"><img src="/4.jpg"></div>"""
        val setup = JSONObject(engine.call("(()=>{globalThis.fixtureHtml = ${JSONObject.quote(html)}; return null;})()")!!)
        assertTrue(setup.toString(), setup.getBoolean("ok"))
        val result = JSONObject(engine.call("src.comic.loadEp.call(src,'book','chapter')")!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        val images = result.getJSONObject("data").getJSONArray("images")
        assertEquals(listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg", "https://catalogue.example/3.jpg", "https://catalogue.example/4.jpg"),
            (0 until images.length()).map { images.getString(it) })
        val config = JSONObject(engine.call("src.comic.onImageLoad.call(src,'https://cdn.example/1.jpg','book','chapter')")!!)
        assertEquals("https://catalogue.example/chapter/chapter", config.getJSONObject("data").getJSONObject("headers").getString("Referer"))
    }

    @Test fun cachedMxsAndWnacgRepairsCanBeAppliedRepeatedly() {
        for ((key, script) in listOf("mxs" to mxs, "wnacg" to wnacg)) {
            val once = patched(key, script)
            assertEquals(key, once, patched(key, once))
        }
    }

    @Test fun currentCommunityScriptsRemainValidAfterRawAndLegacyCacheRepairs() = runBlocking {
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        for (key in listOf("wnacg", "mxs")) {
            val raw = assets.open("source-audit/scripts/$key.js").bufferedReader().use { it.readText() }
            val once = patched(key, raw)
            assertEquals(key, once, patched(key, once))
            // Domain discovery is irrelevant to this offline compilation regression.
            val script = once.replace("async init() {", "async init() { return;")
            val result = JSONObject(engine(key, script).call("null")!!)
            assertTrue(result.toString(), result.getBoolean("ok"))
        }
    }
}
