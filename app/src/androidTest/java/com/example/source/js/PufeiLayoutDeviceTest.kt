package com.example.source.js

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class PufeiLayoutDeviceTest {
    private val path = "/comic/wojialaopolaiziyiqiannianqian"
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("pufei-layout/$name.html").bufferedReader().use { it.readText() }
    private suspend fun engine(): JsSourceEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return JsSourceEngine(context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() }, "pufei_layout_fixture", context)
            .also {
                val init = JSONObject(it.call("(()=>{globalThis.cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=JSON.parse(JSON.stringify(v))};return true})()")!!)
                assertTrue(init.toString(), init.getBoolean("ok"))
            }
    }
    private suspend fun data(engine: JsSourceEngine, code: String): JSONObject {
        val raw = engine.call(code)!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        return result.getJSONObject("data")
    }

    @Test fun realMobileAndDesktopTemplatesProduceTheSameCompleteCatalogue() = runBlocking {
        val result = data(engine(), """
            (()=>{
                const desktop=src.parseDetails(${JSONObject.quote(fixture("desktop"))});
                const mobile=src.parseDetails(${JSONObject.quote(fixture("mobile"))});
                return {desktopTitle:desktop.title,mobileTitle:mobile.title,author:mobile.author,
                    description:mobile.description,cover:mobile.cover,
                    desktop:Object.keys(desktop.chapters).length,mobile:Object.keys(mobile.chapters).length,
                    same:JSON.stringify(desktop.chapters)===JSON.stringify(mobile.chapters)};
            })()
        """.trimIndent())
        assertEquals("我家老婆来自一千年前", result.getString("desktopTitle"))
        assertEquals(result.getString("desktopTitle"), result.getString("mobileTitle"))
        assertEquals(459, result.getInt("desktop"))
        assertEquals(459, result.getInt("mobile"))
        assertTrue(result.getBoolean("same"))
        assertEquals("之画文化", result.getString("author"))
        assertTrue(result.getString("description").length > 80)
        assertTrue(result.getString("cover").startsWith("https://www.pufeimh.com/attachment/"))
    }

    @Test fun mobilePageLoadsOnTheFirstRequestWithoutUsingDesktopSelectors() = runBlocking {
        val result = data(engine(), """
            (async()=>{
                let calls=0;src.request=async()=>{calls++;return {status:200,body:${JSONObject.quote(fixture("mobile"))}}};
                const details=await src.comic.loadInfo('https://m.pufeimh.com$path');
                return {calls,title:details.title,count:Object.keys(details.chapters).length};
            })()
        """.trimIndent())
        assertEquals(1, result.getInt("calls"))
        assertEquals(459, result.getInt("count"))
        assertEquals("我家老婆来自一千年前", result.getString("title"))
    }

    @Test fun anInvalid200PageFallsBackToTheOtherLayoutAndDoesNotCacheTheInvalidPage() = runBlocking {
        val result = data(engine(), """
            (async()=>{
                const urls=[];src.request=async(url)=>{
                    urls.push(url);return {status:200,body:urls.length===1?'<title>跳转页面</title>':${JSONObject.quote(fixture("mobile"))}};
                };
                const details=await src.comic.loadInfo('https://www.pufeimh.com$path');
                return {calls:urls.length,alternate:urls[1],count:Object.keys(details.chapters).length,
                    cachedTitle:cache.details_v4.data.title};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("calls"))
        assertEquals("https://m.pufeimh.com$path", result.getString("alternate"))
        assertEquals(459, result.getInt("count"))
        assertEquals("我家老婆来自一千年前", result.getString("cachedTitle"))
    }

    @Test fun absoluteChapterLinksAreNormalizedDeduplicatedAndRecommendationsAreExcluded() = runBlocking {
        val html = """
            <title>漫画全集 - 扑飞漫画</title><p class="detail-main-title">测试作品</p>
            <script src="/api/hits/comic/388892"></script>
            <div class="detail-list">
                <div class="detail-list-item"><a href="http://m.pufeimh.com/chapter/388892-123859.html">预告</a></div>
                <div class="detail-list-item"><a href="https://www.pufeimh.com/chapter/388892-123859.html">预告</a></div>
                <div class="detail-list-item"><a href="//m.pufeimh.com/chapter/388892-125784.html">01</a></div>
                <div class="detail-list-item"><a href="/chapter/999999-123859.html">其它作品推荐</a></div>
            </div><a href="/chapter/388892-22222.html">推荐／开始阅读按钮</a>
        """.trimIndent()
        val result = data(engine(), """
            (()=>{
                const d=src.parseDetails(${JSONObject.quote(html)});
                const keys=Object.keys(d.chapters);
                return {count:keys.length,first:keys[0],second:keys[1],
                    http:src.abs('http://m.pufeimh.com$path')};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("count"))
        assertEquals("https://www.pufeimh.com/chapter/388892-123859.html", result.getString("first"))
        assertEquals("https://www.pufeimh.com/chapter/388892-125784.html", result.getString("second"))
        assertEquals("https://m.pufeimh.com$path", result.getString("http"))
    }

    @Test fun parsedDetailsAreReusedAcrossHostAliasesButRefreshAfterOneMinute() = runBlocking {
        val result = data(engine(), """
            (async()=>{
                let now=Date.now(),calls=0;Date.now=()=>now;
                src.request=async()=>{calls++;return {status:200,body:${JSONObject.quote(fixture("mobile"))}}};
                await src.comic.loadInfo('https://m.pufeimh.com$path');
                await src.comic.loadInfo('https://www.pufeimh.com$path');
                const warm=calls;now+=60001;
                await src.comic.loadInfo('https://m.pufeimh.com$path');
                return {warm,total:calls};
            })()
        """.trimIndent())
        assertEquals(1, result.getInt("warm"))
        assertEquals(2, result.getInt("total"))
    }

    @Test fun a200HomepageWithRecommendationsCannotBecomeACompleteCatalogue() = runBlocking {
        val result = data(engine(), """
            (async()=>{
                let calls=0;src.request=async()=>{calls++;return {status:200,
                    body:'<title>扑飞首页</title><a href="/chapter/388892-123859.html">热门推荐</a>'}};
                let error='';try{await src.comic.loadInfo('https://www.pufeimh.com$path')}catch(e){error=e.message}
                return {calls,error,cached:!!cache.details_v4};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("calls"))
        assertTrue(result.getString("error").contains("完整漫画详情或章节目录"))
        assertFalse(result.getBoolean("cached"))
    }

    @Test fun oldSearchSlugsUseTheirPublicChapterRedirectToRecoverTheCatalogue() = runBlocking {
        val result = data(engine(), """
            (async()=>{
                const urls=[];
                src.matches(src.compact([{name:'斗破苍穹&#40旧&#41',url:'/comic/old-slug',
                    pic:'/cover.webp',chapter_url:'/chapter/260269-86744.html'}]),'斗破');
                src.request=async(url)=>{urls.push(url);return {status:200,
                    body:urls.length===1?'该漫画不存在':${JSONObject.quote(fixture("mobile"))}}};
                const details=await src.comic.loadInfo('/comic/old-slug');
                for(let i=0;i<140;i++) src.rememberSearchLinks([['test','/comic/'+i,'','','/chapter/1-2.html']]);
                return {calls:urls.length,second:urls[1],count:Object.keys(details.chapters).length,
                    bounded:cache.book_links_v4.length,title:src.titleText('番外&middot锈铁&#40旧&#41')};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("calls"))
        assertEquals("https://www.pufeimh.com/chapter/260269-86744.html", result.getString("second"))
        assertEquals(459, result.getInt("count"))
        assertEquals(128, result.getInt("bounded"))
        assertEquals("番外·锈铁(旧)", result.getString("title"))
    }

    @Test fun suppliersWebpMigrationIsAppliedToBothRelayAndDirectScomicUrls() = runBlocking {
        val old = "https://c-nd3-1.6wm.top/scomic/wojialaopolaiziyiqiannianqian-yuewenmanhua/0/2-rolu/1.jpg"
        val relay = "https://s2.325784.xyz/" + java.net.URLEncoder.encode(
            Base64.encodeToString(old.toByteArray(), Base64.NO_WRAP), "UTF-8")
        val result = data(engine(), """
            (()=>({direct:src.originalImage(${JSONObject.quote(old)}),
                relay:src.originalImage(${JSONObject.quote(relay)}),
                unrelated:src.originalImage('https://outside.example/scomic/1.jpg'),
                current:src.originalImage('https://c-nd3-1.6wm.top/hp/current/1.webp'),
                oldCache:src.cachedPages().length}))()
        """.trimIndent())
        assertEquals(old.removeSuffix(".jpg") + ".webp", result.getString("direct"))
        assertEquals(result.getString("direct"), result.getString("relay"))
        assertEquals("https://outside.example/scomic/1.jpg", result.getString("unrelated"))
        assertEquals("https://c-nd3-1.6wm.top/hp/current/1.webp", result.getString("current"))
    }

    @Test fun pageListsReuseValidatedPublicPayloadAndExpireWithoutUnboundedStorage() = runBlocking {
        val image = "https://c-nd3-1.6wm.top/hp/fixture/1.webp"
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("9S8\$vJnU2ANeSRoF".toByteArray(), "AES"), IvParameterSpec(iv))
        }
        val plain = JSONObject().put("host", "www.pufeimh.com").put("images", org.json.JSONArray().put(image)).toString()
        val payload = Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        val result = data(engine(), """
            (async()=>{
                let now=Date.now(),calls=0;Date.now=()=>now;
                src.html=async()=>{calls++;return 'const params="$payload";'};
                await src.comic.loadEp('book','https://www.pufeimh.com/chapter/388892-125784.html');
                const second=await src.comic.loadEp('book','https://m.pufeimh.com/chapter/388892-125784.html');
                const warm=calls;now+=600001;
                await src.comic.loadEp('book','https://www.pufeimh.com/chapter/388892-125784.html');
                for(let i=0;i<12;i++) src.rememberPages('fixture'+i,['$image']);
                const bounded=src.cachedPages().length;
                src.rememberPages('large',Array(1999).fill('$image'));
                return {warm,total:calls,image:second.images[0],bounded,
                    pages:src.cachedPages().reduce((sum,v)=>sum+v.images.length,0)};
            })()
        """.trimIndent())
        assertEquals(1, result.getInt("warm"))
        assertEquals(2, result.getInt("total"))
        assertEquals(image, result.getString("image"))
        assertEquals(8, result.getInt("bounded"))
        assertTrue(result.getInt("pages") <= 2000)
    }
}

