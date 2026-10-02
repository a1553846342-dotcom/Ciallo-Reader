package com.example.source

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.*
import com.example.download.NovelDownloadStore
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import androidx.test.platform.app.InstrumentationRegistry
import android.provider.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WholeBookNovelUpdateDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private val original = "第1章 春天\n这是第一章完整的中文正文。\n第2章 夏天\n这是第二章完整的中文正文。\n"
    private fun snapshot(id: String = "update-${System.nanoTime()}") = SearchBook(id, "auto_novel", "更新验证小说", "真实作者", format = "txt",
        novelInfo = NovelInfo(latestChapter = "第2章 夏天", updatedAt = "2026-10-01", chapterCount = 2))

    @Test fun actualNovelSourceCoversDecodeWithTheSameLoaderAsSearchAndDetails() = runBlocking {
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        val context = context()
        val domestic = com.example.source.impl.IxdzsSource(context)
        val searched = value(domestic.search("没钱修什么仙")).first { it.id == "571203" }
        val detailed = value(domestic.getDetail(searched.id))
        assertEquals(detailed.cover, searched.cover)
        val translated = value(com.example.source.impl.Wenku8LibrarySource(context).getDetail("3765"))
        val rows = org.json.JSONArray()
        for (book in listOf(searched, detailed, translated)) {
            assertFalse(book.cover.isNullOrBlank())
            val result = com.example.library.GenericCoverLoader.get(context).execute(
                com.example.library.novelCoverRequest(context, book))
            assertTrue("${book.sourceId}: ${(result as? coil.request.ErrorResult)?.throwable}", result is coil.request.SuccessResult)
            val image = (result as coil.request.SuccessResult).drawable
            assertTrue(image.intrinsicWidth > 0 && image.intrinsicHeight > 0)
            rows.put(org.json.JSONObject().put("source", book.sourceId).put("cover", book.cover)
                .put("width", image.intrinsicWidth).put("height", image.intrinsicHeight))
        }
        File(context.filesDir, "novel-cover-native-verification.json").writeText(rows.toString(2))
    }

    private suspend fun database(block: suspend (AppDatabase, BookRepository, File) -> Unit) {
        val context = context()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dir = File(context.cacheDir, "whole-novel-test-${System.nanoTime()}").apply { mkdirs() }
        try { block(db, BookRepository(context, db.bookDao(), db), dir) }
        finally { db.close(); dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun fullBookReplacementKeepsIdentityProgressBookmarksAndNotes() = runBlocking {
        database { db, repository, dir ->
            val remote = snapshot()
            val first = File(dir, "first.txt").apply { writeText(original) }
            val book = repository.importDownloadedNovel(Uri.fromFile(first), remote, replace = false).getOrThrow()
            val dao = db.bookDao()
            dao.updateBook(book.copy(currentChapterIndex = 1, scrollOffset = 12, isFinished = true, category = "我的小说"))
            dao.insertBookmark(Bookmark(bookId = book.id, chapterIndex = 1, scrollOffset = 12, title = "我的书签", snippet = "第二章"))
            dao.insertHighlight(Highlight(bookId = book.id, chapterIndex = 1, selectedText = "中文正文", note = "我的笔记", colorHex = "#7FD8C8"))
            val updated = File(dir, "updated.txt").apply { writeText("第0章 序章\n新增的序章。\n" + original + "第3章 秋天\n这是第三章的新正文。\n") }
            val refreshed = repository.importDownloadedNovel(Uri.fromFile(updated), remote.copy(novelInfo = remote.novelInfo!!.copy(chapterCount = 4)), replace = true).getOrThrow()
            assertEquals(book.id, refreshed.id)
            assertEquals("真实作者", refreshed.author)
            assertEquals("我的小说", refreshed.category)
            assertEquals(2, refreshed.currentChapterIndex)
            assertEquals(12, refreshed.scrollOffset)
            assertFalse(refreshed.isFinished)
            assertEquals(4, refreshed.totalChapters)
            assertEquals(1, dao.getBooksCount())
            assertEquals(2, dao.getBookmarksForBook(book.id).first().single().chapterIndex)
            val note = dao.getHighlightsForBook(book.id).first().single()
            assertEquals(2, note.chapterIndex); assertEquals("我的笔记", note.note)
            assertTrue(dao.getChaptersListForBook(book.id).last().content.contains("第三章的新正文"))
            assertTrue(first.exists())
        }
    }

    @Test fun incompleteReplacementRollsBackAndZlibCannotEnterThisImporter() = runBlocking {
        database { db, repository, dir ->
            val remote = snapshot()
            val first = File(dir, "first.txt").apply { writeText(original) }
            val book = repository.importDownloadedNovel(Uri.fromFile(first), remote, false).getOrThrow()
            val before = db.bookDao().getChaptersListForBook(book.id)
            val bad = File(dir, "bad.txt").apply { writeText("第1章 春天\n只有一章的不完整新版。\n") }
            val failed = repository.importDownloadedNovel(Uri.fromFile(bad), remote, true)
            assertTrue(failed.isFailure)
            assertEquals(book, db.bookDao().getBookById(book.id))
            assertEquals(before, db.bookDao().getChaptersListForBook(book.id))
            assertEquals(1, db.bookDao().getBooksCount())
            assertTrue(runCatching { repository.importDownloadedNovel(Uri.fromFile(bad), remote.copy(sourceId = "zlibrary"), true) }.isFailure)
            assertTrue(first.exists())
        }
    }

    @Test fun updateBaselineAdvancesOnlyAfterSuccessfulWholeBookImport() {
        val store = NovelDownloadStore(context())
        val first = snapshot()
        val task = "baseline-${System.nanoTime()}"
        store.prepare(task, first, false)
        assertNull(store.baseline(first.sourceId, first.id))
        store.imported(task)
        val second = first.copy(novelInfo = first.novelInfo!!.copy(chapterCount = 3, latestChapter = "第3章 秋天"))
        store.prepare(task, second, true)
        assertEquals(first, store.baseline(first.sourceId, first.id))
        assertTrue(store.pending(task)!!.replace)
        store.imported(task)
        assertEquals(second, store.baseline(first.sourceId, first.id))
        assertNull(store.pending(task))
        assertTrue(runCatching { store.prepare("zlib", first.copy(sourceId = "zlibrary"), true) }.isFailure)
        assertFalse(WholeBookNovelSources.contains("zlibrary"))
        assertFalse(WholeBookNovelSources.contains("js_pufei"))
    }

    @Test fun actualWorkerDownloadsWholeNewVersionAndRejectsStalePackageWithoutLosingOldBook() = runBlocking {
        withTimeout(90_000) {
            val context = context()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val proxy = Settings.Global.getString(context.contentResolver, "http_proxy")
            fun shell(command: String) { automation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            } }
            val content = AtomicReference(original.toByteArray())
            val server = ServerSocket(0)
            val thread = Thread {
                while (!server.isClosed) runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                        val bytes = content.get()
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val remote = snapshot("worker-${System.nanoTime()}")
            val manager = DownloadManager(context)
            val id = DownloadManager.taskId(remote.sourceId, remote.id)
            val db = AppDatabase.getDatabase(context)
            try {
                shell("settings put global http_proxy :0")
                withTimeout(5_000) {
                    val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                    while (connectivity.defaultProxy != null) kotlinx.coroutines.delay(50)
                }
                val request = DownloadRequest(remote.id, remote.title, remote.author, remote.sourceId,
                    "http://127.0.0.1:${server.localPort}/novel.txt", "txt", novelSnapshot = remote)
                manager.enqueueDownload(request)
                val first = manager.allTasksFlow.first { tasks -> tasks.any { it.id == id && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == id }
                assertEquals(first.errorMessage, DownloadStatus.COMPLETED, first.status)
                val old = db.bookDao().getBookBySourceResource(remote.sourceId, remote.id)!!
                db.bookDao().updateBook(old.copy(currentChapterIndex = 1, scrollOffset = 5))
                content.set((original + "第3章 秋天\n新增完整的第三章正文。\n").toByteArray())
                val newer = remote.copy(novelInfo = remote.novelInfo!!.copy(chapterCount = 3, latestChapter = "第3章 秋天"))
                manager.enqueueDownload(request.copy(novelSnapshot = newer, replaceExistingNovel = true))
                val second = manager.allTasksFlow.first { tasks -> tasks.any { it.id == id && it.filePath != first.filePath && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == id }
                assertEquals(second.errorMessage, DownloadStatus.COMPLETED, second.status)
                val refreshed = db.bookDao().getBookBySourceResource(remote.sourceId, remote.id)!!
                assertEquals(old.id, refreshed.id); assertEquals(3, refreshed.totalChapters)
                assertEquals(1, refreshed.currentChapterIndex); assertEquals(5, refreshed.scrollOffset)
                assertNotEquals(old.filePath, refreshed.filePath)
                assertEquals(newer, NovelDownloadStore(context).baseline(remote.sourceId, remote.id))
                val unready = newer.copy(novelInfo = newer.novelInfo!!.copy(chapterCount = 4, latestChapter = "第4章 冬天"))
                manager.enqueueDownload(request.copy(novelSnapshot = unready, replaceExistingNovel = true))
                val third = manager.allTasksFlow.first { tasks -> tasks.any { it.id == id && it.filePath != second.filePath && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == id }
                assertEquals(DownloadStatus.FAILED, third.status)
                assertTrue(third.errorMessage.orEmpty().contains("尚未更新"))
                assertEquals(refreshed, db.bookDao().getBookBySourceResource(remote.sourceId, remote.id))
                assertTrue(File(refreshed.filePath.removePrefix("file://")).exists())
                assertEquals(newer, NovelDownloadStore(context).baseline(remote.sourceId, remote.id))
            } finally {
                server.close(); thread.join(500)
                if (proxy.isNullOrBlank()) shell("settings delete global http_proxy") else shell("settings put global http_proxy $proxy")
            }
        }
    }
}
