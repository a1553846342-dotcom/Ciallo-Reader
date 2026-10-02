package com.example.source.js

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.SourceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Live diagnostic: opening the real verification UI never counts as a successful source audit. */
@RunWith(AndroidJUnit4::class)
class WebsiteVerificationDeviceTest {
    @Test fun opensWebsiteVerificationAndRecordsActualOutcome() { runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = InstrumentationRegistry.getArguments().getString("sourceKey") ?: "mycomic"
        JsActivityTracker.register(context)
        val intent = Intent().setClassName(context.packageName, "com.example.ui.comic.ComicReaderTestActivity")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeoutOrNull(10_000) { while (JsActivityTracker.currentActivity() == null) delay(100) }
        val source = JsSourceRepo.loadCached(context, true).first { it.sourceKey == key }
        val result = withTimeoutOrNull(30_000) { source.verifyWebsite() }
        File(context.filesDir, "website-verification-audit.json").writeText(JSONObject()
            .put("key", key)
            .put("verified", result is SourceResult.Success && result.data)
            .put("status", if (result == null) "timeout" else result.javaClass.simpleName)
            .put("error", (result as? SourceResult.Error)?.exception?.message.orEmpty()).toString())
    } }
}
