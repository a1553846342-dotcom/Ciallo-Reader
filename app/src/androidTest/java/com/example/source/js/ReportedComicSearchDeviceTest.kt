package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.SourceResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReportedComicSearchDeviceTest {
    @Test fun denseGlobalRegexPreservesCapturesAndExplicitLastIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() }
        val engine = JsSourceEngine(runtime, "class Fixture extends ComicSource {key='dense_regex';}", "dense_regex", context)
        val reply = JSONObject(engine.call("""
            (()=>{
                const input='<a href="42">title</a>'.repeat(8000);
                const pattern=/<a\s+href="([^\"]+)"[^>]*>(.*?)<\/a>/g;
                let count=0,match;
                while((match=pattern.exec(input))!==null){
                    if(match[1]!=='42'||match[2]!=='title') throw Error('capture changed');
                    count++;
                }
                const reset=pattern.lastIndex;
                pattern.lastIndex=100;
                const after=pattern.exec(input).index;
                pattern.lastIndex=0;
                const first=pattern.exec(input).index;
                return {count,reset,after,first};
            })()
        """.trimIndent())!!)
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val data = reply.getJSONObject("data")
        assertEquals(8000, data.getInt("count"))
        assertEquals(0, data.getInt("reset"))
        assertEquals(110, data.getInt("after"))
        assertEquals(0, data.getInt("first"))
    }

    @Test fun cancellingFirstSearchKeepsBootstrapAndSourceReusable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = JsComicSource(context, "cold_cancel", "fixture", "1", """
            class Fixture extends ComicSource {
                key='cold_cancel';
                async init(){await new Promise(resolve=>setTimeout(resolve,300));}
                search={load:async(keyword)=>({comics:[{id:'42',title:keyword,cover:''}],maxPage:1})};
            }
        """.trimIndent())
        try {
            withTimeout(40) { source.search("old") }
            fail("The first caller should be cancelled")
        } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        val field = JsComicSource::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val original = field.get(source)
        assertNotNull("A cancelled bootstrap must retain its runtime", original)
        val next = withTimeout(3_000) { source.search("new") }
        assertTrue(next.toString(), next is SourceResult.Success && next.data.single().title == "new")
        assertSame(original, field.get(source))
    }

    @Test fun repeatedAsyncCallsReleaseJobsAndKeepAnotherSourceUsable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() }
        val source = JsSourceEngine(runtime, "class Fixture extends ComicSource {key='async_retention';}", "async_retention", context)
        val other = JsSourceEngine(runtime, "class Fixture extends ComicSource {key='other_active';}", "other_active", context)
        assertTrue(JSONObject(other.call("42")!!).getBoolean("ok"))
        repeat(40) {
            val reply = JSONObject(source.call("sendMessage({method:'delay',time:1}).then(()=>42)")!!)
            assertTrue(reply.toString(), reply.getBoolean("ok"))
            assertEquals(42, reply.getInt("data"))
        }
        val runtimeField = JsSourceEngine::class.java.getDeclaredField("quickJs").apply { isAccessible = true }
        val native = runtimeField.get(source) as com.dokar.quickjs.QuickJs
        val jobsField = com.dokar.quickjs.QuickJs::class.java.getDeclaredField("asyncJobs").apply { isAccessible = true }
        assertEquals("Completed bridge jobs must not accumulate", 0, (jobsField.get(native) as List<*>).size)
        val unaffected = JSONObject(other.call("43")!!)
        assertTrue(unaffected.toString(), unaffected.getBoolean("ok"))
        assertEquals(43, unaffected.getInt("data"))
    }

    @Test fun cancelledAsyncSearchDoesNotPoisonTheNextSearchOrDetails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() }
        val engine = JsSourceEngine(runtime, """
            class Fixture extends ComicSource {
                key='cancelled_async';
                search={load:async(keyword)=>{
                    if(keyword==='old') await new Promise(resolve=>setTimeout(resolve, 300));
                    return {comics:[{id:'42',title:keyword,cover:''}],maxPage:1};
                }};
            }
        """.trimIndent(), "cancelled_async", context)
        assertTrue(JSONObject(engine.call("'ready'")!!).getBoolean("ok"))
        repeat(3) { round ->
            try {
                withTimeout(40) { engine.call("src.search.load('old')") }
                fail("The old async request should be cancelled")
            } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
            val next = JSONObject(withTimeout(3_000) { engine.call("src.search.load('new$round')") }!!)
            assertTrue(next.toString(), next.getBoolean("ok"))
            assertEquals("new$round", next.getJSONObject("data").getJSONArray("comics").getJSONObject(0).getString("title"))
        }
    }

    @Test fun hitomiCircleAliasOnlyRetriesSuccessfulEmptySearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val script = """
            class Fixture extends ComicSource {
                key='hitomi'; calls=[];
                search={load:async(keyword)=>{
                    this.calls.push(keyword);
                    if(keyword==='broken (alias)') throw Error('HTTP 403');
                    return {comics:keyword==='STUDIO TRIUMPH'?[
                        {id:'42',title:'Studio S.D.T. 27',cover:'https://cdn.example/42.jpg'}]:[],maxPage:1};
                }};
            }
        """.trimIndent()
        val source = JsComicSource(context, "hitomi", "fixture", "1", script)
        val result = source.search("STUDIO TRIUMPH (むとうけいじ)")
        assertTrue(result.toString(), result is SourceResult.Success && result.data.single().id == "42")
        val field = JsComicSource::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val engine = field.get(source) as JsSourceEngine
        val calls = JSONObject(engine.call("src.calls")!!).getJSONArray("data")
        assertEquals(2, calls.length())
        assertEquals("STUDIO TRIUMPH", calls.getString(1))
        assertTrue(source.search("broken (alias)") is SourceResult.Error)
        assertEquals(3, JSONObject(engine.call("src.calls")!!).getJSONArray("data").length())
    }

    /** Explicit live check of the screenshot query; an absent work is recorded separately from errors. */
    @Test fun liveReportedQuery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        // ikmmh was audited separately and retired after repeated transport failures.
        val keys = args.getString("sourceKeys", "hitomi,mycomic,bilimanga,pufei")!!.split(',').toSet()
        val query = args.getString("query", "STUDIO TRIUMPH (むとうけいじ)")!!
        val rows = JSONArray()
        for (source in JsSourceRepo.loadCached(context, true).filter { it.sourceKey in keys }) {
            val row = JSONObject().put("key", source.sourceKey).put("query", query)
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                when (val result = withTimeout(60_000) { source.search(query) }) {
                    is SourceResult.Success -> row.put("status", if (result.data.isEmpty()) "absent" else "found")
                        .put("count", result.data.size).put("titles", JSONArray(result.data.take(3).map { it.title }))
                    is SourceResult.Error -> row.put("status", "error").put("error", result.exception.message)
                }
            } catch (e: Exception) { row.put("status", "error").put("error", e.message) }
            row.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
            rows.put(row)
            File(context.filesDir, "reported-comic-search.json").writeText(rows.toString(2))
        }
        assertEquals(keys.size, rows.length())
    }
}
