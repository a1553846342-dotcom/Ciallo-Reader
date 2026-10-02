package com.example.source.js

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.PufeiImageCacheRetry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class PufeiSourceTest {
    private suspend fun engine(): JsSourceEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return JsSourceEngine(
            runtimeJs = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            sourceJs = context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() },
            sourceKey = "pufei_fixture", context = context
        ).also {
            val init = JSONObject(it.call("(()=>{src.loadData=()=>null;src.saveData=()=>{};return true})()")!!)
            assertTrue(init.toString(), init.getBoolean("ok"))
        }
    }

    @Test fun publicReaderPayloadReachesImageContractWithoutRemoteScriptExecution() = runBlocking {
        val image = "https://c-nd3-1.6wm.top/hp/fixture/1.webp"
        val relay = "https://s2.325784.xyz/" + Base64.encodeToString(image.toByteArray(), Base64.NO_WRAP)
        val payload = JSONObject().put("host", "www.pufeimh.com")
            .put("images", org.json.JSONArray().put(relay)).toString()
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("9S8\$vJnU2ANeSRoF".toByteArray(), "AES"), IvParameterSpec(iv))
        }
        val encoded = Base64.encodeToString(iv + cipher.doFinal(payload.toByteArray()), Base64.NO_WRAP)
        val runtime = engine()
        val html = "<script>var params='$encoded';throw new Error('Remote script must never execute');</script>"
        val raw = runtime.call("(async()=>{src.html=async()=>${JSONObject.quote(html)};return src.comic.loadEp('book','chapter')})()")!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        assertEquals(image, result.getJSONObject("data").getJSONArray("images").getString(0))
        val config = JSONObject(runtime.call("src.comic.onImageLoad(${JSONObject.quote(image)})")!!)
            .getJSONObject("data")
        assertEquals("https://manhuafree.com/", config.getJSONObject("headers").getString("Referer"))
        val malformed = JSONObject(runtime.call("(async()=>{src.html=async()=>\"params='AA=='\";return src.comic.loadEp('book','chapter')})()")!!)
        assertFalse(malformed.toString(), malformed.getBoolean("ok"))
    }

    @Test fun titleSearchDeduplicatesAndReusesCachedCatalogueInNativeBridge() = runBlocking {
        val runtime = engine()
        val raw = runtime.call("""
            (async()=>{
                let calls=0; const cache={};
                src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                src.request=async(url)=>{
                    calls++;
                    if(!url.includes('type%5Bmark%5D=W')||url.includes('&key=')) throw new Error('Wrong API');
                    return {status:200,body:'pufei('+JSON.stringify({code:1,data:[
                        {name:'我家老婆来自一千年前',url:'/comic/fixture',pic:'/cover.jpg'},
                        {name:'我家老婆来自一千年前',url:'/comic/fixture',pic:'/cover.jpg'}
                    ]})+')'};
                };
                const first=await src.search.load('我家娘子来自一千年前',[],1);
                const second=await src.search.load('我家老婆来自一千年前',[],1);
                return {calls,count:first.comics.length,again:second.comics.length,
                    id:first.comics[0].id,initials:['斗','火','我'].map(v=>src.initial(v)).join('')};
            })()
        """.trimIndent())!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        val data = result.getJSONObject("data")
        assertEquals(1, data.getInt("calls"))
        assertEquals(1, data.getInt("count"))
        assertEquals(1, data.getInt("again"))
        assertEquals("DHW", data.getString("initials"))
        assertEquals("https://www.pufeimh.com/comic/fixture", data.getString("id"))
    }

    @Test fun cachedServerErrorRefreshesOnceAndPreservesReferer() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(PufeiImageCacheRetry)
            .addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("https://manhuafree.com/", request.header("Referer"))
                val refreshed = request.url.queryParameter("pufei_retry") != null
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (refreshed) 200 else 502).message("fixture")
                    .body((if (refreshed) "image" else "cached error").toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url("https://c-nd3-1.6wm.top/hp/fixture.webp")
            .header("Referer", "https://manhuafree.com/").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("image", it.body!!.string())
        }
        assertEquals(2, calls)
    }

    @Test fun permanentErrorsAreBoundedAndOtherSourcesDoNotRefresh() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(PufeiImageCacheRetry)
            .addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(502).message("fixture").body("error".toResponseBody()).build()
            }.build()
        fun fetch(host: String) = client.newCall(Request.Builder().url("https://$host/hp/fixture.webp")
            .header("Referer", "https://manhuafree.com/").build()).execute().use { assertEquals(502, it.code) }
        fetch("c-nd3-1.6wm.top")
        assertEquals(2, calls)
        fetch("other.invalid")
        assertEquals(3, calls)
    }
}
