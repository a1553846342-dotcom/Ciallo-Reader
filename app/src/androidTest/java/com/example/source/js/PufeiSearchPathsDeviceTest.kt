package com.example.source.js

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.library.ComicDownloadManager
import com.example.library.ComicDownloadStatus
import com.example.source.SourceResult
import com.example.source.SearchBook
import com.example.ui.comicImageLoader
import com.example.ui.comic.ComicImagePipeline
import com.example.ui.comic.ComicPageLoader
import com.example.ui.comic.ComicPageRef
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every returned Pufei card in the declared query corpus, never just the first three. */
@RunWith(AndroidJUnit4::class)
class PufeiSearchPathsDeviceTest {
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    @Test fun everyReturnedCardReachesDetailsReadingDownloadAndOfflineShelf() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = JsSourceRepo.loadCached(context, true).single { it.sourceKey == "pufei" }
        val args = InstrumentationRegistry.getArguments()
        val download = args.getString("download", "true") == "true"
        if (args.getString("cold") == "true") {
            // Only the disposable test device's Pufei caches are reset.
            val prefs = context.getSharedPreferences("js_source_data", Context.MODE_PRIVATE)
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("data_pufei_") }.forEach { remove(it) }
            }.commit()
        }
        val queries = args.getString("queries")?.split('|') ?: listOf(
            "我家老婆来自一千年前", "我家娘子来自一千年前", "斗破", "火影", "海贼", "一人之下")
        val report = File(context.filesDir, "pufei-search-paths.jsonl").apply { writeText("") }
        fun record(row: JSONObject) {
            report.appendText(row.toString() + "\n")
            Log.i("PufeiPaths", row.toString())
        }
        val books = linkedMapOf<String, SearchBook>()
        var failures = 0
        for (query in queries) {
            val started = SystemClock.elapsedRealtime()
            try {
                val results = withTimeout(45_000) { value(source.search(query)) }
                    .map { it.copy(sourceId = source.id) }
                results.forEach { books[it.id] = it }
                record(JSONObject().put("stage", "aggregate_source_search").put("query", query)
                    .put("count", results.size).put("ids", JSONArray(results.map { it.id }))
                    .put("status", if (results.isEmpty()) "empty" else "ok")
                    .put("ms", SystemClock.elapsedRealtime() - started))
                if (results.isEmpty()) failures++
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                failures++
                record(JSONObject().put("stage", "aggregate_source_search").put("query", query)
                    .put("status", "failed").put("error", e.message))
            }
        }
        val database = AppDatabase.getDatabase(context)
        val loader = ComicPageLoader(context, comicImageLoader(context))
        try {
            for (book in books.values) {
                val row = JSONObject().put("id", book.id).put("title", book.title)
                val started = SystemClock.elapsedRealtime()
                try {
                    suspend fun <T> stage(name: String, timeout: Long = 45_000, block: suspend () -> T): T {
                        row.put("stage", name)
                        val start = SystemClock.elapsedRealtime()
                        return withTimeout(timeout) { block() }.also {
                            row.put(name + "Ms", SystemClock.elapsedRealtime() - start)
                        }
                    }
                    val detail = stage("detail") { value(source.getDetail(book.id)) }
                    assertTrue("Missing real title", detail.title.isNotBlank())
                    val chapters = stage("chapters") { value(source.getChapters(book.id)) }.filter { !it.external }
                    assertTrue("Empty catalogue", chapters.isNotEmpty())
                    row.put("chapterCount", chapters.size)
                    val chapterSamples = listOf(
                        chapters.firstOrNull { !it.title.contains("预告") } ?: chapters.first(),
                        chapters[chapters.size / 2], chapters.last()).distinctBy { it.id }
                    val checked = JSONArray()
                    var downloadPages: List<String>? = null
                    for ((index, chapter) in chapterSamples.withIndex()) {
                        val pages = stage("pages$index") { value(source.getChapterImages(chapter.id)) }
                        assertTrue("Empty pages ${chapter.title}", pages.isNotEmpty())
                        row.put("chapter${index}Id", chapter.id).put("chapter${index}FirstImage", pages.first())
                        if (index == 0) downloadPages = pages
                        val samples = listOf(0, pages.size / 2, pages.lastIndex).distinct()
                        val headers = source.getChapterImageHeaders(chapter.id, samples.map { pages[it] })
                        for (page in samples) {
                            val url = source.resolveChapterImage(pages[page]) ?: pages[page]
                            val bitmap = stage("image${index}_$page") { loader.load(
                                ComicPageRef.Remote("paths:${chapter.id}:$page", url,
                                    headers[pages[page]].orEmpty() + source.getResolvedHeaders(url)),
                                "paths:${chapter.id}:$page", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap }
                            assertTrue(bitmap.width > 0 && bitmap.height > 0)
                        }
                        checked.put(JSONObject().put("chapter", chapter.title).put("pages", pages.size)
                            .put("decodedSamples", samples.size))
                    }
                    row.put("reading", checked)
                    if (!download) {
                        row.put("status", "reading_ok")
                        continue
                    }
                    val chapter = chapterSamples.first()
                    val fresh = book.copy(id = book.id + "#paths-" + java.util.UUID.randomUUID())
                    val id = ComicDownloadManager.taskId(fresh, chapter.id)
                    stage("download", 180_000) {
                        ComicDownloadManager.start(context.applicationContext as Application, database, fresh, chapter, source)
                        val terminal = try {
                            ComicDownloadManager.tasks.first { it[id]?.status in setOf(
                                ComicDownloadStatus.SUCCESS, ComicDownloadStatus.FAILED) }.getValue(id)
                        } catch (e: CancellationException) {
                            ComicDownloadManager.pause(chapter.id, fresh)
                            throw e
                        }
                        check(terminal.status == ComicDownloadStatus.SUCCESS) { terminal.error ?: "Download failed" }
                        val directory = ComicDownloadManager.directory(context, id)
                        val shelfBook = database.bookDao().getBookByFilePath(directory.absolutePath)
                            ?: error("Download missing from local bookshelf")
                        val localPages = database.bookDao().getChaptersListForBook(shelfBook.id)
                        assertEquals(downloadPages!!.size, localPages.size)
                        for (page in localPages) {
                            val file = File(page.content)
                            assertTrue(file.isFile && file.length() > 0)
                            val hash = java.security.MessageDigest.getInstance("SHA-256")
                                .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                            assertNotEquals("Anti-hotlink placeholder is not a manga page",
                                "4080ca5057dbf7f865082aec81e5e718045ff996dadd9c0021df31dcad418254", hash)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)
                            assertTrue("Invalid offline page", bounds.outWidth > 0 && bounds.outHeight > 0)
                        }
                        val offline = ComicPageLoader(context)
                        try {
                            for (page in listOf(localPages.first(), localPages[localPages.size / 2], localPages.last()).distinct()) {
                                val image = offline.load(ComicPageRef.Local("offline:$id:${page.id}", page.content),
                                    "offline:$id:${page.id}", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                                assertTrue(image.width > 0 && image.height > 0)
                            }
                        } finally { offline.shutdown() }
                        row.put("downloadedPages", localPages.size)
                    }
                    row.put("status", "download_and_offline_ok")
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    failures++
                    row.put("status", "failed").put("error", e.message)
                } finally {
                    row.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    record(row)
                }
            }
        } finally { loader.shutdown() }
        record(JSONObject().put("stage", "summary").put("returnedBooks", books.size)
            .put("failures", failures).put("sourceVersion", source.version))
        assertTrue("No returned cards", books.isNotEmpty())
        assertEquals("Every returned card must complete its path; inspect pufei-search-paths.jsonl", 0, failures)
    }
}
