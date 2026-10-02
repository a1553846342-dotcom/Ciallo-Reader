package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.*
import com.example.source.impl.MangaDexSource
import com.example.ui.comicImageLoader
import com.example.ui.comic.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit live reading audit on a disposable emulator, including middle/end chapters. */
@RunWith(AndroidJUnit4::class)
class ThreeSourceLiveReadingDeviceTest {
    @Test fun readMultipleMxsBooksAndTheReportedOfficial404Route() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = File(context.filesDir, "three-source-reading.jsonl").apply { writeText("") }
        val source = JsSourceRepo.loadCached(context, true).single { it.sourceKey == "mxs" }
        val loader = ComicPageLoader(context, comicImageLoader(context))
        val failures = mutableListOf<String>()
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        fun record(row: JSONObject) { report.appendText(row.toString() + "\n") }
        suspend fun sample(source: ComicSource, chapterId: String, label: String): JSONObject {
            val urls = value(source.getChapterImages(chapterId))
            check(urls.isNotEmpty()) { "No images" }
            val samples = JSONArray()
            for (index in listOf(0, urls.size / 2, urls.lastIndex).distinct()) {
                val url = urls[index]
                val headers = source.getChapterImageHeaders(chapterId, listOf(url))[url].orEmpty()
                val resolved = source.resolveChapterImage(url) ?: url
                val bitmap = loader.load(ComicPageRef.Remote("$label:$index", resolved, headers + source.getResolvedHeaders(resolved)),
                    "$label:$index", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                var low = 255
                var high = 0
                for (y in 0 until 32) for (x in 0 until 32) {
                    val c = bitmap.getPixel(x * (bitmap.width - 1) / 31, y * (bitmap.height - 1) / 31)
                    val luma = (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3
                    low = minOf(low, luma); high = maxOf(high, luma)
                }
                samples.put(JSONObject().put("page", index).put("host", android.net.Uri.parse(resolved).host)
                    .put("width", bitmap.width).put("height", bitmap.height).put("contrast", high - low))
                // End cards and intentional blank separators are recorded, not classified
                // as a broken chapter. Its first and middle pages must contain an image.
                if (index != urls.lastIndex) check(high - low > 2) { "Flat/blank content page $index" }
            }
            return JSONObject().put("pageCount", urls.size).put("samples", samples)
        }
        val books = withTimeout(60_000) { value(source.search("姐姐")) }.take(6)
        assertEquals("Need six different MXS books for the live audit", 6, books.size)
        for (book in books) {
            val chapters = withTimeout(60_000) { value(source.getChapters(book.id)) }
            for (index in listOf(0, chapters.size / 2, chapters.lastIndex).distinct()) {
                val chapter = chapters[index]
                val row = JSONObject().put("key", "mxs").put("bookId", book.id).put("title", book.title)
                    .put("chapterId", chapter.id).put("chapter", chapter.title)
                try {
                    row.put("reading", withTimeout(90_000) { sample(source, chapter.id, "mxs:${book.id}:$index") })
                    row.put("status", "reading_ok")
                } catch (e: Exception) {
                    row.put("status", "failed").put("error", e.message)
                    failures.add("mxs:${book.id}:$index ${e.message}")
                }
                record(row)
            }
        }
        val mangaDex = MangaDexSource(context = context)
        // Live official API returned these URLs but the image host returned HTTP 404.
        val chapterId = "mdapich:275c3ee8-bdeb-4070-a333-6add23a8415a:8cac25d2-61c3-435b-8843-d8c7ccc86039:36"
        val row = JSONObject().put("key", "mangadex").put("chapterId", chapterId)
        try {
            row.put("reading", withTimeout(90_000) { sample(mangaDex, chapterId, "md:official404") })
            row.put("status", "reading_ok")
        } catch (e: Exception) {
            row.put("status", "failed").put("error", e.message)
            failures.add("mangadex ${e.message}")
        }
        record(row)
        loader.clearProcessedCache()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
