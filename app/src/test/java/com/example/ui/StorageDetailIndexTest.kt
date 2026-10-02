package com.example.ui

import com.example.data.Book
import com.example.download.DownloadStatus
import com.example.download.DownloadTaskEntity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageDetailIndexTest {
    @Test
    fun importedSourceAndComicDirectoryUseLibraryTitles() = runBlocking {
        val root = Files.createTempDirectory("reader-named-storage").toFile()
        try {
            val imported = root.resolve("imports").apply { mkdirs() }.resolve("1727_opaque.epub")
                .apply { writeText("novel") }
            val comic = root.resolve("comics_123").apply { mkdirs() }
            comic.resolve("001.jpg").writeText("page")
            val books = listOf(
                Book(id = 7, title = "三体", filePath = "file://${imported.absolutePath}"),
                Book(id = 8, title = "星际漫画", filePath = comic.absolutePath, contentType = "COMIC")
            )
            assertEquals("《三体》", StorageDetailIndex.scan("imports", listOf(imported.parentFile!!), books).single().label)
            assertEquals("《星际漫画》", StorageDetailIndex.scan("comics", listOf(comic), books).single().label)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun downloadedOpaqueFileUsesDownloadTaskTitle() = runBlocking {
        val root = Files.createTempDirectory("reader-task-storage").toFile()
        try {
            val file = root.resolve("f93a2.epub").apply { writeText("novel") }
            val task = DownloadTaskEntity(
                id = "f93a2", sourceId = "source", title = "海边的卡夫卡", author = "作者",
                coverUrl = null, downloadUrl = "", format = "epub", status = DownloadStatus.COMPLETED,
                filePath = file.absolutePath
            )
            assertEquals("《海边的卡夫卡》", StorageDetailIndex.scan("downloads", listOf(root), tasks = listOf(task)).single().label)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun activeDownloadBlocksDetailDeletionUntilWriteIsStale() = runBlocking {
        val root = Files.createTempDirectory("reader-download-detail").toFile()
        try {
            val book = root.resolve("book.epub").apply { writeText("book") }
            val activePart = root.resolve("book.tmp").apply { writeText("partial") }
            val entries = StorageDetailIndex.scan("downloads", listOf(root))
            assertTrue(entries.none { it.deletable })
            assertEquals(0L, StorageDetailIndex.delete(listOf(book), "downloads"))
            assertTrue(book.exists())

            activePart.setLastModified(System.currentTimeMillis() - 31 * 60_000L)
            assertEquals(book.length(), StorageDetailIndex.delete(listOf(book), "downloads"))
            assertFalse(book.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recentSharedFileIsPreservedButOldFileCanBeCleaned() = runBlocking {
        val root = Files.createTempDirectory("reader-share-detail").toFile()
        val share = root.resolve("share_temp").apply { mkdirs() }
        try {
            val recent = share.resolve("new.cbz").apply { writeText("recent") }
            val old = share.resolve("old.cbz").apply {
                writeText("old")
                setLastModified(System.currentTimeMillis() - 16 * 60_000L)
            }
            val entries = StorageDetailIndex.scan("temp", listOf(share))
            assertFalse(entries.first { it.file == recent }.deletable)
            assertTrue(entries.first { it.file == old }.deletable)
            assertEquals(0L, StorageDetailIndex.delete(listOf(recent), "temp"))
            assertTrue(recent.exists())
            assertEquals(old.length(), StorageDetailIndex.delete(listOf(old), "temp"))
            assertFalse(old.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
