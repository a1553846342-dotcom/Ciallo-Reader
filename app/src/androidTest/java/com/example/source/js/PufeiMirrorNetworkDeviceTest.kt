package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PufeiMirrorNetworkDeviceTest {
    @Test fun inspectPublicMirrorReadersThroughTheProductionNetworkBridge() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val engine=JsSourceEngine(
            context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() }, "pufei", context)
        val raw=engine.call("""
            (async()=>{
                const out=[];
                for(const id of ['531967-178058','531967-178059','531967-178059?fixture']){
                    const url='https://www.guoman.net/chapter/'+id.split('?')[0]+'.html'+(id.includes('?')?'?_reader_retry='+Date.now():'');
                    let r=await sendMessage({method:'http',http_method:'GET',url,headers:{...src.headers,Referer:'https://www.guoman.net/','Cache-Control':'no-cache'}});
                    if(typeof r==='string')r=JSON.parse(r);
                    const text=r?.body||'';
                    out.push({id,status:r?.status,error:r?.error,url:r?.url,bytes:text.length,payload:src.readerPayload(text)?.length,
                        pages:src.parseImages(text)?.length,htmlTitle:text.slice(text.indexOf('<title>'),text.indexOf('</title>')+8)});
                }
                return out;
            })()
        """.trimIndent())!!
        File(context.filesDir,"pufei-mirror-network.json").writeText(raw)
        assertTrue(raw,JSONObject(raw).getBoolean("ok"))
        Unit
    }
}
