package com.example.source.js

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native transport diagnosis for the public alternate reader, not a success assertion for a book. */
@RunWith(AndroidJUnit4::class)
class PufeiRecoveryNetworkDeviceTest {
    @Test fun publicAlternateCdnIsCheckedWithTheAppsActualTransports() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val engine=JsSourceEngine(
            context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() },"pufei",context)
        val raw=engine.call("""
            (async()=>{
                let r=await sendMessage({method:'http',http_method:'GET',bytes:true,
                    url:'https://manhua1039-61-174-50-98.cdndm5.com/90/89250/1618457/1_3825.jpg?cid=1618457&key=88cd2f81a2ae56b17c200bcc55304bc7',
                    headers:{Referer:'https://www.dm5.com/m1618457','User-Agent':src.headers['User-Agent']}});
                return typeof r==='string'?JSON.parse(r):r;
            })()
        """.trimIndent())!!
        val result=JSONObject(raw)
        assertTrue(raw,result.getBoolean("ok"))
        val data=result.getJSONObject("data")
        if(data.optInt("status")==200 && data.has("body")) {
            val bytes=Base64.decode(data.getString("body"),Base64.DEFAULT)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            data.put("decodedWidth",bounds.outWidth).put("decodedHeight",bounds.outHeight).put("bytes",bytes.size)
            if(bounds.outWidth>0)File(context.filesDir,"pufei-recovery-cdn-first.jpg").writeBytes(bytes)
        }
        data.remove("body")
        File(context.filesDir,"pufei-recovery-network.json").writeText(data.toString())
        Unit
    }
}
