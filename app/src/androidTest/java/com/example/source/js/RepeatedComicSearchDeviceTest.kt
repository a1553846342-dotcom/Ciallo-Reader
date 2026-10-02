package com.example.source.js

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.library.SourceSearchCoordinator
import com.example.source.*
import com.example.source.impl.MangaDexSource
import com.example.ui.comicImageLoader
import com.example.ui.comic.ComicImagePipeline
import com.example.ui.comic.ComicPageLoader
import com.example.ui.comic.ComicPageRef
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Three real searches per source, then the shared coordinator and an actual readable image. */
@RunWith(AndroidJUnit4::class)
class RepeatedComicSearchDeviceTest {
    @Test fun auditRepeatedSearchAndOpenEverySource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val keys = args.getString("sourceKeys").orEmpty().split(',').filter(String::isNotBlank).toSet()
        val sources = (JsSourceRepo.loadCached(context, true) + MangaDexSource(context = context))
            .filter { it.capabilities.supportSearch && (keys.isEmpty() || ((it as? JsComicSource)?.sourceKey ?: it.id) in keys) }
        val report = File(context.filesDir, "repeated-comic-search.jsonl").apply { writeText("") }
        val gate = Semaphore(8)
        // The app searches eight sites but reads one book at a time. Keep the same
        // image footprint here instead of decoding eight unrelated chapters at once.
        val readingGate = Semaphore(1)
        val loader = ComicPageLoader(context, comicImageLoader(context))
        val searches = SourceSearchCoordinator()
        val rows = sources.map { source -> async(Dispatchers.IO) {
            val key = (source as? JsComicSource)?.sourceKey ?: source.id
            val row = JSONObject().put("key", key).put("name", source.name)
            val queries = when (key) {
                "nhentai", "wnacg", "ehentai", "hitomi", "jm" -> listOf("miku", "touhou")
                "mxs" -> listOf("姐姐", "初音")
                "comick", "hcomic", "hot_manga", "mangadex", "vomic" -> listOf("naruto", "one piece")
                "bilimanga" -> listOf("火影", "海贼")
                "pufei" -> listOf("我家老婆来自一千年前", "斗破")
                else -> listOf("斗破", "火影")
            }
            row.put("query", queries.first()).put("alternateQuery", queries.last())
            if (key in setOf("picacg", "vomic")) row.put("loggedIn", source.isLoggedIn())
            val rounds = JSONArray()
            var selected: List<SearchBook> = emptyList()
            suspend fun attempt(label: String, query: String, protected: Boolean) {
                val part = JSONObject().put("stage", label).put("query", query)
                val start = SystemClock.elapsedRealtime()
                try {
                    // Start the network deadline after an eight-site slot is
                    // available, matching the app. Queue time is not site failure.
                    suspend fun execute() = gate.withPermit { withTimeout(25_000) { source.search(query) } }
                    val reply = if (protected) searches.search(source, query) { execute() } else execute()
                    when (reply) {
                        is SourceResult.Success -> {
                            part.put("status", if (reply.data.isEmpty()) "empty" else "found").put("count", reply.data.size)
                            if (reply.data.isNotEmpty()) selected = reply.data
                        }
                        is SourceResult.Error -> part.put("status", "error").put("error", reply.exception.message)
                    }
                } catch (e: Exception) { part.put("status", "error").put("error", e.message) }
                part.put("ms", SystemClock.elapsedRealtime() - start)
                rounds.put(part)
                synchronized(report) { report.appendText(JSONObject().put("key", key).put("event", part).toString() + "\n") }
            }
            repeat(3) { attempt("direct${it + 1}", queries.first(), false) }
            attempt("protected1", queries.first(), true)
            attempt("protectedAlternate", queries.last(), true)
            // Repeating the successful query should use the same 60-second cache as the UI.
            attempt("protectedRepeat", queries.first(), true)
            row.put("searches", rounds)
            if (selected.isEmpty()) row.put("readingStatus", "no_search_result")
            else {
                val attempts = JSONArray()
                for (book in selected.take(3)) {
                    val attempt = JSONObject().put("title", book.title)
                    var stage = "details"
                    try {
                        fun <T> value(result: SourceResult<T>): T = when (result) {
                            is SourceResult.Success -> result.data
                            is SourceResult.Error -> throw result.exception
                        }
                        readingGate.withPermit { withTimeout(60_000) { gate.withPermit {
                            value(source.getDetail(book.id))
                            stage = "chapters"
                            val chapters = value(source.getChapters(book.id))
                            attempt.put("chapterCount", chapters.size)
                            val chapter = chapters.firstOrNull { !it.external } ?: error("No hosted chapter")
                            stage = "pages"
                            val pages = value(source.getChapterImages(chapter.id))
                            attempt.put("pageCount", pages.size)
                            val url = pages.firstOrNull() ?: error("No page images")
                            stage = "image"
                            val headers = source.getChapterImageHeaders(chapter.id, listOf(url))[url].orEmpty()
                            val resolved = source.resolveChapterImage(url) ?: url
                            val bitmap = loader.load(ComicPageRef.Remote("repeat:$key", resolved,
                                headers + source.getResolvedHeaders(resolved)), "repeat:$key",
                                ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                            attempt.put("width", bitmap.width).put("height", bitmap.height)
                            var visible = 0
                            for (y in 0 until 8) for (x in 0 until 8) {
                                if (bitmap.getPixel(x * (bitmap.width - 1) / 7, y * (bitmap.height - 1) / 7) and 0x00ffffff > 0x000c0c0c) visible++
                            }
                            check(visible > 0) { "Image is black" }
                        } } }
                        attempt.put("status", "reading_ok")
                        attempts.put(attempt)
                        row.put("readingStatus", "reading_ok")
                        break
                    } catch (e: Exception) {
                        attempt.put("stage", stage).put("status", "error").put("error", e.message)
                        attempts.put(attempt)
                        row.put("readingStatus", "failed")
                    }
                }
                row.put("readingAttempts", attempts)
            }
            synchronized(report) { report.appendText(row.toString() + "\n") }
            row
        } }.awaitAll()
        File(context.filesDir, "repeated-comic-search-summary.json").writeText(JSONArray(rows).toString(2))
        // A completed audit is distinct from successful source outcomes in the report.
        assertEquals(sources.size, rows.size)
        if (keys.isNotEmpty()) assertEquals(keys.size, rows.size)
    }
}
