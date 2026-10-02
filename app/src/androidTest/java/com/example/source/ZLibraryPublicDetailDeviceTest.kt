package com.example.source

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.zlibrary.ZLibrarySource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ZLibraryPublicDetailDeviceTest {
    @Test fun publicSearchAndDetailProvideActualSynopsis() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = ZLibrarySource(app)
        val search = source.search("三体")
        assertTrue(search.toString(), search is SourceResult.Success)
        val books = (search as SourceResult.Success).data
        assertTrue(books.isNotEmpty())
        val book = books.first()
        val detail = source.getDetail(book.id)
        assertTrue(detail.toString(), detail is SourceResult.Success)
        val info = (detail as SourceResult.Success).data
        assertEquals("zlibrary", info.sourceId)
        assertTrue(info.title.isNotBlank())
        assertFalse("A public detail page should expose its real synopsis", info.description.isNullOrBlank())
        File(app.filesDir, "zlibrary-public-detail-audit.json").writeText(JSONObject()
            .put("passed", true).put("searchCount", books.size).put("title", info.title)
            .put("synopsisLength", info.description!!.length).put("publisher", info.novelInfo?.publisher)
            .put("downloadRequested", false).toString())
    }
}
