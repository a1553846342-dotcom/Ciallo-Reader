package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit live diagnostics; never included in the ordinary regression suite. */
@RunWith(AndroidJUnit4::class)
class PublicSourceRoutesDeviceTest {
    @Test fun probeOfficialPublicRoutes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() }
        val report = File(context.filesDir, "public-source-routes.jsonl")
        report.writeText("")
        val gate = Semaphore(2)
        val urls = listOf(
            "https://www.nhentai.net/api/v2/search?query=miku&page=1&sort=date",
            "https://nhentai.net/api/galleries/search?query=miku&page=1",
            "https://nhentai.net/search/?q=miku&page=1",
            "https://www.nhentai.net/search/?q=miku&page=1",
            "https://mycomic.com/cn/comics?q=naruto",
            "http://www.ikmmh.com/search?searchkey=naruto",
            "https://ikmmh.com/search?searchkey=naruto"
        )
        urls.mapIndexed { index, url -> async { gate.withPermit {
            val engine = JsSourceEngine(runtime,
                "class Probe extends ComicSource {key='public_probe_$index';}", "public_probe_$index", context)
            val row = JSONObject().put("url", url)
            try {
                val code = """
                    (async()=>{
                        const r=await Network.get(${JSONObject.quote(url)},{});
                        const b=typeof r.body==='string'?r.body:'';
                        return {status:r.status,length:b.length,
                            challenge:b.includes('challenge-platform'),regionDenied:b.includes('region has been denied'),
                            title:(b.match(/<title>([^<]*)<\/title>/i)||[])[1]||'',
                            galleries:new HtmlDocument(b).querySelectorAll('div.gallery').length,
                            contentType:r.headers?.['content-type']||r.headers?.['Content-Type']||''};
                    })()
                """.trimIndent()
                row.put("response", JSONObject(withTimeout(45_000) { engine.call(code) }!!))
            } catch (e: Exception) { row.put("error", e.message ?: e.javaClass.simpleName) }
            synchronized(report) { report.appendText(row.toString()+"\n") }
        } } }.awaitAll()
        Unit
    }
}
