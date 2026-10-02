package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PufeiRegexpDeviceTest {
    private fun engine(): JsSourceEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return JsSourceEngine(context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() }, "pufei_regexp_fixture", context)
    }

    @Test fun longPayloadDoesNotNeedARegexpCapture() = runBlocking {
        val engine = engine()
        val raw = engine.call("""
            (()=>{
                let decoded=0;
                Convert.decodeBase64=value=>{decoded=value.length;return Array(32).fill(0)};
                Convert.decryptAesCbc=()=>[];
                Convert.decodeUtf8=()=>JSON.stringify({host:'www.pufeimh.com',images:['https://image.example/1.webp']});
                const result=src.parseImages('<script>const params="'+'A'.repeat(400000)+'";</script>');
                return {decoded,count:result.length};
            })()
        """.trimIndent())!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        assertEquals(400000, result.getJSONObject("data").getInt("decoded"))
        assertEquals(1, result.getJSONObject("data").getInt("count"))
    }

    @Test fun malformedOrOversizedPayloadNeverReachesTheByteBridge() = runBlocking {
        val raw = engine().call("""
            (()=>{
                Convert.decodeBase64=()=>{throw Error('Invalid payload reached decoder')};
                return {results:[
                    '<script>params="'+'A'.repeat(1398108)+'";</script>',
                    '<script>params="'+'A'.repeat(400000),
                    'otherparams="'+ 'A'.repeat(32)+'"',
                    'params="AA??AAAA'+'A'.repeat(24)+'"',
                    'params="'+'A'.repeat(32)+'=A"'
                ].map(text=>src.parseImages(text)===null),
                    next:src.readerPayload('otherparams="ignore";params \n = \t "'+'A'.repeat(32)+'"')};
            })()
        """.trimIndent())!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        val data = result.getJSONObject("data")
        repeat(5) { assertTrue(data.getJSONArray("results").getBoolean(it)) }
        assertEquals("A".repeat(32), data.getString("next"))
    }
}
