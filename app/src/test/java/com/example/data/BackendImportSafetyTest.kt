package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackendImportSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun rejectsZipSlipWithBothPathSeparators() {
        val root = File(context.cacheDir, "safe-root")
        for (name in listOf("../outside.jpg", "..\\outside.jpg", "/outside.jpg", "C:/outside.jpg")) {
            assertTrue(runCatching { ArchiveBudget.destination(root, name) }.isFailure)
        }
        assertEquals(File(root, "images/page.jpg").canonicalFile, ArchiveBudget.destination(root, "images/page.jpg"))
    }

    @Test fun expandedByteAndEntryLimitsIncludeIgnoredEntries() {
        val budget = ArchiveBudget(maxEntryBytes = 10, maxTotalBytes = 15, maxEntries = 2)
        budget.copyEntry(ByteArrayInputStream(ByteArray(10)))
        assertTrue(runCatching { budget.copyEntry(ByteArrayInputStream(ByteArray(6))) }.isFailure)
        val entries = ArchiveBudget(maxEntries = 1)
        entries.copyEntry(ByteArrayInputStream(byteArrayOf()))
        assertTrue(runCatching { entries.copyEntry(ByteArrayInputStream(byteArrayOf())) }.isFailure)
    }

    @Test fun chapterSplitsPreserveEmojiAndImageTokens() {
        val text = "a".repeat(9) + "😀" + "b".repeat(7) + "[IMG:/x|1|1]" + "end"
        val parts = splitChapterText(text, 15)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 15 })
        assertFalse(parts.any { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertEquals(1, parts.count { it.contains("[IMG:/x|1|1]") })
    }

    @Test fun numericPageSortDoesNotOverflowAndIsTransitive() {
        val names = listOf("p_100000000000000000000000000000.jpg", "p_10.jpg", "p_2.jpg", "p_0002.jpg")
        val sorted = names.sortedWith(ComicParser::naturalOrderCompare)
        assertEquals(listOf("p_0002.jpg", "p_2.jpg", "p_10.jpg", "p_100000000000000000000000000000.jpg"), sorted)
        for (a in names) for (b in names) {
            assertEquals(ComicParser.naturalOrderCompare(a, b).compareTo(0), -ComicParser.naturalOrderCompare(b, a).compareTo(0))
        }
    }

    @Test fun cbzSortsOriginalPathsAndIgnoresMacMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val archive = File(context.cacheDir, "backend-pages.cbz")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, bytes) in listOf("10.jpg" to byteArrayOf(10), "2.jpg" to byteArrayOf(2), "__MACOSX/1.jpg" to byteArrayOf(1))) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
            val book = ComicParser.importComic(context, Uri.fromFile(archive), archive.name, db.bookDao()).getOrThrow()
            val pages = db.bookDao().getChaptersListForBook(book.id)
            assertEquals(2, pages.size)
            assertEquals(listOf(2, 10), pages.map { File(it.content).readBytes().single().toInt() })
        } finally { db.close(); archive.delete() }
    }

    @Test fun failedChapterInsertRollsBackBookRow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val file = File(context.cacheDir, "backend-failed.txt").apply { writeText("正文") }
        try {
            val dao = Proxy.newProxyInstance(BookDao::class.java.classLoader, arrayOf(BookDao::class.java)) { _, method, args ->
                if (method.name == "insertChapters") throw IllegalStateException("模拟数据库写入失败")
                method.invoke(db.bookDao(), *(args ?: emptyArray()))
            } as BookDao
            val result = BookRepository(context, dao, db).importBookFromUri(Uri.fromFile(file), file.name)
            assertTrue(result.isFailure)
            assertEquals(0, db.bookDao().getBooksCount())
        } finally { db.close(); file.delete() }
    }

    @Test fun txtImportsFromPrivateCopyAndBoundsGiantSingleLine() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val uri = Uri.parse("content://test/novel.txt")
        val text = "正文😀".repeat(20_000)
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        try {
            val book = BookRepository(context, db.bookDao(), db).importBookFromUri(uri, "novel.txt").getOrThrow()
            assertTrue(book.filePath.startsWith("file://"))
            assertTrue(File(context.filesDir, "imports").listFiles().orEmpty().any {
                it.isFile && Uri.fromFile(it).toString() == book.filePath
            })
            val chapters = db.bookDao().getChaptersListForBook(book.id)
            assertTrue(chapters.all { it.content.length <= MAX_CHAPTER_LENGTH })
            assertEquals(text, chapters.joinToString("") { it.content })
        } finally { db.close() }
    }

    @Test fun htmlParserHandlesRubyAndSupplementaryEntities() {
        val text = EpubParser.extractCleanTextFromHtml("<head><title>坏标题</title></head><p><ruby>漢字<rt>かんじ</rt></ruby>&#x1F600;</p><script>垃圾</script><p>第二段</p>")
        assertEquals("漢字😀\n\n第二段", text)
    }

    @Test fun logicalMergingIsBoundedAndMetadataMapsAgree() {
        val physical = (0 until 100).map { Chapter(bookId = 1, chapterOrder = it,
            title = if (it == 0) "长章节" else "长章节 (续${it + 1})", content = "正文") }
        val loaded = ChapterMerger.buildLogicalChapters(physical)
        val metadata = ChapterMerger.buildLogicalChapters(physical.map { it.copy(content = "") })
        assertEquals(25, loaded.chapters.size)
        assertArrayEquals(loaded.physicalToLogical, metadata.physicalToLogical)
        assertTrue(loaded.logicalToPhysicalOrders.all { it.size <= 4 })
    }

    @Test fun searchResultsAreBoundedAndSkipMatchesInsideImagePaths() {
        val chapter = Chapter(bookId = 1, chapterOrder = 0, title = "搜索", content = "的".repeat(30_000))
        assertEquals(SearchLocator.MAX_RESULTS, SearchLocator.buildResults(listOf(chapter), "的", { it }, { "搜索" }).size)
        val token = chapter.copy(content = "[IMG:/目录/的.png|1|1]这是的正文")
        val results = SearchLocator.buildResults(listOf(token), "的", { it }, { "搜索" })
        assertEquals(1, results.size)
        assertEquals(0, results.single().occurrence)
    }

    @Test fun deletingBookPreservesHistoryAndRemovesAnnotations() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.bookDao()
            val book = dao.insertBookWithChapters(Book(title = "测试", filePath = "test://delete"), listOf(Chapter(bookId = 0, chapterOrder = 0, title = "章", content = "文")))
            dao.insertBookmark(Bookmark(bookId = book.id, chapterIndex = 0, scrollOffset = 0, title = "章", snippet = "文"))
            dao.insertHighlight(Highlight(bookId = book.id, chapterIndex = 0, selectedText = "文"))
            dao.insertReadingSession(ReadingSession(bookId = book.id, bookTitle = book.title, dateStr = "2026-09-30",
                startTimeMs = 1000, endTimeMs = 2000, durationSeconds = 1, startHour = 0))
            BookRepository(context, dao, db).deleteBook(book)
            assertEquals(0, dao.getBooksCount())
            assertTrue(dao.getChaptersListForBook(book.id).isEmpty())
            assertNull(dao.getReadingSessionsForDate("2026-09-30").single().bookId)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bookmarks UNION ALL SELECT COUNT(*) FROM highlights").use { cursor ->
                while (cursor.moveToNext()) assertEquals(0, cursor.getInt(0))
            }
        } finally { db.close() }
    }
    @Test fun unavailableSecureStorageFailsClosed() {
        val storage = com.example.source.zlibrary.ZLibraryCredentialStorage(null as android.content.SharedPreferences?)
        assertNull(storage.getUserKey())
        assertFalse(storage.isLoggedIn())
        storage.clear()
        assertTrue(runCatching { storage.saveCredentials(userKey = "secret") }.isFailure)
    }

}
