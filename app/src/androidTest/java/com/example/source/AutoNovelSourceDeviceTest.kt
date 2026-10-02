package com.example.source

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import com.example.source.impl.AutoNovelSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AutoNovelSourceDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    @Test fun translationFallbackPreservesParagraphsAndNeverShowsUntranslatedOriginal() {
        val source = AutoNovelSource(context())
        val fixture = JSONObject().put("paragraphs", JSONArray(listOf("原文一", "原文二", "原文三", "原文四")))
            .put("sakuraParagraphs", JSONArray(listOf("中文一", "", JSONObject.NULL, "")))
            .put("gptParagraphs", JSONArray(listOf("备用一", "中文二", "", "")))
            .put("baiduParagraphs", JSONArray(listOf("", "", "中文三", "中文四")))
            .put("youdaoParagraphs", JSONArray(listOf("短数组不能导致正文错位")))
        assertEquals("中文一\n\n中文二\n\n中文三\n\n中文四", source.paragraphText(fixture))
        fixture.remove("baiduParagraphs")
        val failure = runCatching { source.paragraphText(fixture) }.exceptionOrNull()
        assertTrue(failure is SourceException.ParseError)
        assertTrue(failure!!.message.orEmpty().contains("中文译文尚未齐全"))
    }

    @Test fun metadataAndTextCacheAvoidRepeatedRequestsAndPreserveDownloadContract() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val path = chain.request().url.encodedPath
            val body = if ("/chapter/" in path) """{"paragraphs":["JP"],"sakuraParagraphs":["中文正文"]}"""
                else """{"titleZh":"Re：从零开始的异世界生活","toc":[{"titleZh":"第一卷"},{"titleZh":"序章","chapterId":"1"}]}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toByteArray().let {
                    okhttp3.ResponseBody.create(null, it)
                }).build()
        }.build()
        val source = AutoNovelSource(context(), "https://fixture.invalid/".toHttpUrl(), client)
        val book = value(source.search("re0")).single()
        assertTrue(source.isNovelSource)
        assertFalse(source.capabilities.supportComic)
        assertFalse(source.requiresLogin)
        val chapters = value(source.getChapters(book.id))
        assertEquals("第一卷", chapters.single().volume)
        assertEquals("中文正文", value(source.getChapterText(chapters.single().id)))
        assertEquals("中文正文", value(source.getChapterText(chapters.single().id)))
        val info = value(source.getDownloadInfo(book.id))
        val url = info.url.toHttpUrl()
        assertEquals("/api/novel/syosetu/n2267be/file", url.encodedPath)
        assertEquals("epub", url.queryParameter("type"))
        assertEquals(info.fileName, url.queryParameter("filename"))
        assertEquals(listOf("sakura", "gpt", "youdao", "baidu"), url.queryParameterValues("translations"))
        assertEquals(2, calls.get())
    }

    @Test fun epubCatalogueHandlesDirectoryAliasesAndRejectsEscapingLinks() = runBlocking {
        val context = context()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val archive = File(context.cacheDir, "catalogue-audit-${System.nanoTime()}.epub")
        fun fixture(navHref: String) {
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                val entries = mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                    "OEBPS/content.opf" to """<package version="3.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>目录测试</dc:title></metadata><manifest><item id="nav" href="$navHref" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="Text/1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
                    "OEBPS/Text/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><a href="1.xhtml">目录中的章节名</a></nav></body></html>""",
                    "OEBPS/Text/1.xhtml" to "<html><body><p>这是实际正文。</p></body></html>"
                )
                entries.forEach { (name, text) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
        }
        try {
            fixture("Text/nav.xhtml")
            val book = com.example.data.EpubParser.importEpub(context, android.net.Uri.fromFile(archive), archive.name, db.bookDao()).getOrThrow()
            assertEquals("目录中的章节名", db.bookDao().getChaptersListForBook(book.id).single().title)
            fixture("../../outside.xhtml")
            val invalid = com.example.data.EpubParser.importEpub(context, android.net.Uri.fromFile(archive), archive.name, db.bookDao())
            assertTrue("Escaping EPUB paths must remain rejected", invalid.isFailure)
            assertEquals(1, db.bookDao().getBooksCount())
        } finally { db.close(); archive.delete() }
    }

    /** Live guest API + production WorkManager download and EPUB import; opt-in. */
    @Test fun liveReZeroReadingDownloadAndOfflineImport() = runBlocking {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withTimeout(180_000) {
            val context = context()
            val source = AutoNovelSource(context)
            val row = JSONObject().put("source", source.id).put("loginRequired", source.requiresLogin)
            suspend fun <T> stage(name: String, action: suspend () -> T): T {
                val start = SystemClock.elapsedRealtime()
                return action().also { row.put(name + "Ms", SystemClock.elapsedRealtime() - start) }
            }
            val book = stage("search") { value(source.search("re0")).single() }
            assertEquals("syosetu/n2267be", book.id)
            val normal = stage("titleSearch") { value(source.search("从零开始")) }
            assertTrue(normal.any { it.id == book.id })
            val chapters = stage("catalogue") { value(source.getChapters(book.id)) }
            assertTrue(chapters.size >= 797)
            row.put("chapters", chapters.size).put("latest", chapters.last().title)
            for (index in listOf(0, chapters.size / 2, chapters.lastIndex)) {
                val text = stage("text$index") { value(source.getChapterText(chapters[index].id)) }
                assertTrue("Missing real chapter text", text.length > 200)
                assertTrue("Missing Chinese translation", text.count { it in '\u4e00'..'\u9fff' } > 100)
                row.put("text${index}Characters", text.length)
            }
            stage("cachedText") { value(source.getChapterText(chapters.last().id)) }
            val info = value(source.getDownloadInfo(book.id))
            val database = AppDatabase.getDatabase(context)
            val manager = DownloadManager(context)
            // Isolate each audit so an existing successful download cannot satisfy a cold test.
            val downloadBookId = book.id + "#audit-" + System.nanoTime()
            val id = DownloadManager.taskId(book.sourceId, downloadBookId)
            val previous = database.downloadTaskDao().getTaskById(id)
            row.put("freshDownload", previous == null)
            manager.enqueueDownload(DownloadRequest(downloadBookId, book.title, book.author, book.sourceId,
                info.url, info.format), info.referer, info.headers)
            stage("downloadAndImport") {
                val terminal = manager.allTasksFlow.first { tasks ->
                    tasks.any { it.id == id && (previous == null || previous.status == DownloadStatus.COMPLETED || it.updatedAt != previous.updatedAt) &&
                        it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) }
                }.first { it.id == id }
                assertEquals(terminal.errorMessage, DownloadStatus.COMPLETED, terminal.status)
                val file = File(terminal.filePath)
                assertTrue(file.isFile && file.length() > 1_000_000)
                ZipFile(file).use { zip ->
                    assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).use { it.readBytes().toString(Charsets.UTF_8) })
                }
                val local = database.bookDao().getAllBooksSync().firstOrNull {
                    it.sourceId == source.id && it.comicId == downloadBookId
                } ?: error("EPUB was not registered in bookshelf")
                val stored = database.bookDao().getChaptersListForBook(local.id)
                assertTrue("Missing EPUB chapters", stored.size >= chapters.size)
                assertTrue("Latest chapter not imported", stored.any { it.title.contains("执行者") || it.content.contains("执行者") })
                listOf(0, stored.size / 2, stored.lastIndex).forEach { i ->
                    assertTrue("Offline chapter empty", stored[i].content.isNotBlank())
                }
                row.put("downloadBytes", file.length()).put("offlineChapters", stored.size)
                    .put("status", "reading_download_and_offline_ok")
            }
            File(context.filesDir, "auto-novel-audit.json").writeText(row.toString())
            android.util.Log.i("NovelAudit", row.toString())
        }
        Unit
    }
}
