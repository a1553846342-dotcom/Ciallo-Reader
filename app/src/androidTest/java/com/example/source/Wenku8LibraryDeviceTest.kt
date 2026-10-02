package com.example.source

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import com.example.source.impl.Wenku8LibrarySource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class Wenku8LibraryDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    @Test fun catalogueUsesChineseEbookGroupAndStableDownloadEndpoint() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("中文书名", chain.request().url.queryParameter("q"))
            val json = """{"items":[{"id":3765,"title":"中文书名","author":"作者","coverUrl":"/covers/3765.jpg","volumeCount":3,"updatedAt":"2026-03-15"}]}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(json.toResponseBody()).build()
        }.build()
        val source = Wenku8LibrarySource(context(), "https://fixture.invalid/".toHttpUrl(), client)
        val book = value(source.search(" 中文书名 ")).single()
        assertTrue(source.isNovelSource)
        assertFalse(source.requiresLogin)
        assertEquals("中文", book.language)
        assertEquals("https://fixture.invalid/covers/3765.jpg", book.cover)
        assertEquals(book, value(source.getDetail(book.id)))
        val info = value(source.getDownloadInfo(book.id))
        assertEquals("https://fixture.invalid/api/books/3765/download", info.url)
        assertFalse(info.url.contains("X-Amz"))
        assertEquals("epub", info.format)
        assertEquals(1, calls)
        assertTrue(source.getDownloadInfo("../escape") is SourceResult.Error)
        Unit
    }

    @Test fun liveChineseLightNovelsDownloadIntoOfflineBookshelf() = runBlocking {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withTimeout(180_000) {
            val context = context()
            val source = Wenku8LibrarySource(context)
            val db = AppDatabase.getDatabase(context)
            val manager = DownloadManager(context)
            val rows = JSONArray()
            for ((query, expectedId) in listOf("无职转生" to "3765", "推理迷宫" to "3846")) {
                val start = SystemClock.elapsedRealtime()
                val book = value(source.search(query)).first { it.id == expectedId }
                val row = JSONObject().put("source", source.id).put("title", book.title)
                    .put("bookId", book.id).put("searchMs", SystemClock.elapsedRealtime() - start)
                assertEquals("中文", book.language)
                assertEquals(book.title, value(source.getDetail(book.id)).title)
                val info = value(source.getDownloadInfo(book.id))
                val auditBookId = book.id + "#audit-" + System.nanoTime()
                val id = DownloadManager.taskId(source.id, auditBookId)
                val downloadStart = SystemClock.elapsedRealtime()
                manager.enqueueDownload(DownloadRequest(auditBookId, book.title, book.author, source.id,
                    info.url, info.format), info.referer, info.headers)
                val terminal = manager.allTasksFlow.first { tasks -> tasks.any { it.id == id &&
                    it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == id }
                assertEquals(terminal.errorMessage, DownloadStatus.COMPLETED, terminal.status)
                row.put("downloadAndImportMs", SystemClock.elapsedRealtime() - downloadStart)
                val file = File(terminal.filePath)
                assertTrue(file.length() > 1_000_000)
                ZipFile(file).use { zip ->
                    assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).use { it.readBytes().toString(Charsets.UTF_8) })
                }
                val local = db.bookDao().getAllBooksSync().first { it.sourceId == source.id && it.comicId == auditBookId }
                val chapters = db.bookDao().getChaptersListForBook(local.id)
                assertTrue(chapters.size >= if (expectedId == "3765") 42 else 8)
                assertTrue(chapters.joinToString { it.content }.count { it in '\u4e00'..'\u9fff' } > 10_000)
                listOf(0, chapters.size / 2, chapters.lastIndex).forEach { assertTrue(chapters[it].content.isNotBlank()) }
                row.put("downloadBytes", file.length()).put("offlineChapters", chapters.size)
                    .put("freshDownload", true).put("loginRequired", false).put("status", "chinese_epub_download_and_offline_ok")
                rows.put(row)
            }
            File(context.filesDir, "wenku8-library-audit.json").writeText(rows.toString())
        }
        Unit
    }
}
