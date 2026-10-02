package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object ComicParser {
    private const val TAG = "BookImport"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    fun isComicFile(fileName: String): Boolean =
        fileName.substringAfterLast('.').lowercase() in setOf("cbz", "zip", "pdf", "cbr", "cb7", "rar", "7z")

    suspend fun importComic(context: Context, uri: Uri, fileName: String, bookDao: BookDao): Result<Book> =
        withContext(Dispatchers.IO) {
            val comicDir = File(context.filesDir, "comics_${UUID.randomUUID()}")
            var coverFile: File? = null
            try {
                val extension = fileName.substringAfterLast('.').lowercase()
                require(extension in setOf("pdf", "cbz", "zip")) { "请转换为CBZ、ZIP或PDF格式后导入。" }
                check(comicDir.mkdirs()) { "无法创建漫画目录" }
                // Sort original paths, not generated names that encode extraction order.
                val pages = mutableListOf<Pair<String, File>>()
                if (extension == "pdf") {
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法打开PDF文件")
                    descriptor.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            require(renderer.pageCount <= 10_000) { "PDF页数超过安全上限" }
                            for (i in 0 until renderer.pageCount) {
                                coroutineContext.ensureActive()
                                renderer.openPage(i).use { page ->
                                    val scale = minOf(1080f / page.width, 1920f / page.height, 2f)
                                    val bitmap = Bitmap.createBitmap(
                                        (page.width * scale).toInt().coerceAtLeast(1),
                                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
                                    )
                                    try {
                                        bitmap.eraseColor(Color.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        val file = File(comicDir, "page_$i.jpg")
                                        file.outputStream().use { out ->
                                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "PDF页面保存失败" }
                                        }
                                        require(comicDir.usableSpace >= 16L * 1024 * 1024) { "存储空间不足" }
                                        pages.add("page_$i.jpg" to file)
                                    } finally { bitmap.recycle() }
                                }
                            }
                        }
                    }
                } else {
                    var extracted = false
                    var lastFailure: Exception? = null
                    for (charset in listOf(Charsets.UTF_8, Charset.forName("GBK"))) {
                        comicDir.listFiles()?.forEach { it.delete() }
                        pages.clear()
                        try {
                            val budget = ArchiveBudget()
                            val input = context.contentResolver.openInputStream(uri) ?: error("无法打开漫画文件")
                            input.use { stream ->
                                ZipInputStream(stream, charset).use { zip ->
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val entry = zip.nextEntry ?: break
                                        val name = entry.name.replace('\\', '/')
                                        ArchiveBudget.destination(comicDir, name)
                                        val visible = name.split('/').none { it.startsWith('.') || it == "__MACOSX" }
                                        if (!entry.isDirectory && visible && name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS) {
                                            val file = File(comicDir, "img_${pages.size}.${name.substringAfterLast('.').lowercase()}")
                                            file.outputStream().use { budget.copyEntry(zip, it) }
                                            pages.add(name to file)
                                        } else budget.copyEntry(zip)
                                        zip.closeEntry()
                                    }
                                }
                            }
                            extracted = true
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { lastFailure = e }
                    }
                    if (!extracted) throw lastFailure ?: IllegalStateException("无法解压漫画文件")
                }
                require(pages.isNotEmpty()) { "未在文件中找到有效漫画页面" }
                pages.sortWith { a, b -> naturalOrderCompare(a.first, b.first) }
                val coverDir = File(context.filesDir, "comic_covers").apply { mkdirs() }
                val cover = File(coverDir, "cover_${UUID.randomUUID()}.jpg")
                coverFile = cover
                pages.first().second.copyTo(cover)
                val book = Book(title = fileName.substringBeforeLast('.'), author = "漫画",
                    filePath = comicDir.absolutePath, coverUri = cover.absolutePath,
                    totalChapters = pages.size, contentType = "COMIC")
                val chapters = pages.mapIndexed { i, (_, file) ->
                    Chapter(bookId = 0, chapterOrder = i, title = "第 ${i + 1} 页", content = file.absolutePath)
                }
                Result.success(bookDao.insertBookWithChapters(book, chapters))
            } catch (e: Exception) {
                comicDir.deleteRecursively()
                coverFile?.delete()
                if (e is CancellationException) throw e
                Log.e(TAG, "Comic import failed", e)
                Result.failure(e)
            }
        }

    /** Numeric runs compare without overflow, regex allocation, or integer parsing. */
    internal fun naturalOrderCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val aStart = i
                val bStart = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                var ai = aStart
                var bj = bStart
                while (ai < i && a[ai] == '0') ai++
                while (bj < j && b[bj] == '0') bj++
                val lengthOrder = (i - ai).compareTo(j - bj)
                if (lengthOrder != 0) return lengthOrder
                while (ai < i) {
                    val digitOrder = a[ai++].compareTo(b[bj++])
                    if (digitOrder != 0) return digitOrder
                }
            } else {
                val order = a[i++].lowercaseChar().compareTo(b[j++].lowercaseChar())
                if (order != 0) return order
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
