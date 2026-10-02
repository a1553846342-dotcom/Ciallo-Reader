package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * FB2（FictionBook）解析器：XML 结构 -> 书名/作者/分章正文/封面，
 * 入库结构与 EpubParser 一致（Book + Chapter）。
 */
object Fb2Parser {

    private const val TAG = "Fb2Parser"

    fun isFb2File(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".fb2")
    }

    suspend fun importFb2(
        context: Context,
        uri: Uri,
        fileName: String,
        bookDao: BookDao,
        targetBookId: Int? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[Fb2Parser] Starting FB2 import for $fileName")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readImportBytes() }
                ?: return@withContext Result.failure(Exception("无法读取 FB2 文件"))
            val text = decodeFb2Text(bytes)
            if (!text.contains("<FictionBook", ignoreCase = true)) {
                return@withContext Result.failure(Exception("不是有效的 FB2 文件"))
            }

            val doc = Jsoup.parse(text, "", Parser.xmlParser())
            val title = doc.selectFirst("book-title")?.text()?.trim()
                ?.ifBlank { null }
                ?: fileName.substringBeforeLast('.').ifBlank { fileName }
            val author = doc.selectFirst("author")?.text()?.trim()?.ifBlank { null } ?: "未知作者"


            val initialBook = Book(
                title = title,
                author = author,
                filePath = uri.toString(),
                contentType = "NOVEL",
                totalChapters = 0
            )
            // targetBookId 非空 = 老书补图片迁移：不新建书、失败不删书（调用方事务收尾）
            val bookId = targetBookId ?: bookDao.insertBook(initialBook).toInt()

            // 内嵌图片：<binary id/content-type> 全部提取到持久目录，正文占位符按 id 引用
            val fb2ImageDir = File(context.filesDir, "fb2_images/$bookId")
            val binaryImages = HashMap<String, Fb2Image>()
            doc.select("binary").forEach { bin ->
                val binId = bin.attr("id").trim()
                val base64 = bin.text().replace(Regex("\\s"), "")
                if (binId.isBlank() || base64.isBlank()) return@forEach
                runCatching {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) return@forEach
                    val ext = detectImageExt(bytes) ?: "jpg"
                    if (!fb2ImageDir.exists()) fb2ImageDir.mkdirs()
                    val f = File(fb2ImageDir, "$binId.$ext")
                    f.writeBytes(bytes)
                    binaryImages[binId] = Fb2Image(f.absolutePath, opts.outWidth, opts.outHeight)
                }
            }

            // 只取第一个 <body>（正文），跳过 notes/comments 等附加 body
            val body = doc.select("body").firstOrNull()
            val sections = body?.select("section") ?: emptyList()
            val chapters = mutableListOf<Chapter>()
            var order = 0

            if (sections.isNotEmpty()) {
                sections.forEachIndexed { index, section ->
                    val sectionTitle = section.select("title").firstOrNull()?.text()?.trim()
                        ?.ifBlank { null }
                        ?: "第 ${index + 1} 章"
                    // 按文档顺序遍历 p 与 image：文本与内嵌图片占位符交错排列
                    val contentParts = mutableListOf<String>()
                    section.select("p, image").forEach { el ->
                        when (el.tagName().lowercase()) {
                            "image" -> {
                                val href = (el.attr("l:href").ifBlank { el.attr("href") }).trimStart('#')
                                binaryImages[href]?.let { img ->
                                    contentParts.add("[IMG:${img.path}|${img.w}|${img.h}]")
                                }
                            }
                            else -> {
                                if (el.parent()?.tagName() != "title") {
                                    val text = el.text().trim()
                                    if (text.isNotEmpty()) contentParts.add(text)
                                }
                            }
                        }
                    }
                    val contentText = contentParts.joinToString("\n\n").trim()
                    if (contentText.isNotEmpty()) {
                        addChaptersWithSplit(chapters, bookId, order, sectionTitle, contentText)
                        order = chapters.size
                    }
                }
            } else {
                // 无 section 结构：整书文本按固定长度兜底分章
                val flatText = body?.select("p")
                    ?.joinToString("\n\n") { it.text().trim() }
                    ?.trim()
                    ?: ""
                if (flatText.isNotEmpty()) {
                    flatText.let { splitChapterText(it, 5000) }.forEachIndexed { index, part ->
                        chapters.add(
                            Chapter(
                                bookId = bookId,
                                chapterOrder = chapters.size,
                                title = "第 ${index + 1} 部分",
                                content = part
                            )
                        )
                    }
                }
            }

            if (chapters.isEmpty()) {
                if (targetBookId == null) bookDao.deleteBook(initialBook.copy(id = bookId))
                File(context.filesDir, "fb2_images/$bookId").deleteRecursively()
                return@withContext Result.failure(Exception("FB2 文件中未找到有效正文内容"))
            }

            var coverUri: String? = null
            runCatching {
                val coverRef = doc.selectFirst("coverpage image")?.attr("href")?.trimStart('#')
                val binaryEl = coverRef?.let { ref ->
                    doc.selectFirst("binary[id=\"$ref\"]")
                }
                val base64 = binaryEl?.text()?.replace(Regex("\\s"), "")
                if (!base64.isNullOrBlank()) {
                    val coverBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    if (BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size) != null) {
                        val coverDir = File(context.filesDir, "fb2_covers")
                        if (!coverDir.exists()) coverDir.mkdirs()
                        val ext = detectImageExt(coverBytes) ?: "jpg"
                        val destCover = File(
                            coverDir,
                            "cover_${bookId}_${System.currentTimeMillis()}.$ext"
                        )
                        destCover.writeBytes(coverBytes)
                        coverUri = destCover.absolutePath
                    }
                }
            }

            bookDao.insertChapters(chapters)
            val finalBook = initialBook.copy(
                id = bookId,
                coverUri = coverUri,
                totalChapters = chapters.size
            )
            bookDao.updateBook(finalBook)
            Log.d(TAG, "[Fb2Parser] Successfully imported '${finalBook.title}' with ${chapters.size} chapters.")
            Result.success(finalBook)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[Fb2Parser] Error during FB2 import", t)
            Result.failure(Exception(t.localizedMessage ?: "FB2 解析失败"))
        }
    }

    private data class Fb2Image(val path: String, val w: Int, val h: Int)

private fun decodeFb2Text(bytes: ByteArray): String {
        val sample = bytes.copyOf(minOf(bytes.size, 2048))
        val header = String(sample, StandardCharsets.ISO_8859_1)
        val encMatch = Regex(
            """<\?xml[^>]*encoding=["']([a-zA-Z0-9_-]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(header)
        return try {
            encMatch?.groupValues?.get(1)?.let { name ->
                if (name.equals("utf-8", true) || name.equals("utf8", true)) {
                    return String(bytes, StandardCharsets.UTF_8)
                }
                return String(bytes, Charset.forName(name))
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            }
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (!utf8.contains('\uFFFD')) {
                utf8
            } else {
                try {
                    String(bytes, Charset.forName("GBK"))
                } catch (_: Exception) {
                    utf8
                }
            }
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun addChaptersWithSplit(
        chapters: MutableList<Chapter>,
        bookId: Int,
        startOrder: Int,
        title: String,
        content: String
    ) {
        if (content.length <= MAX_CHAPTER_LENGTH) {
            chapters.add(
                Chapter(
                    bookId = bookId,
                    chapterOrder = startOrder,
                    title = title,
                    content = content
                )
            )
            return
        }
        // 拆分点优先取换行，避免把 [IMG:...] 内嵌图片占位符从中间截断
        var start = 0
        while (start < content.length) {
            val end = minOf(content.length, start + MAX_CHAPTER_LENGTH)
            if (end >= content.length) {
                chapters.add(
                    Chapter(
                        bookId = bookId,
                        chapterOrder = startOrder + chapters.size,
                        title = title,
                        content = content.substring(start)
                    )
                )
                break
            }
            val nl = content.lastIndexOf('\n', end)
            val cut = if (nl > start + MAX_CHAPTER_LENGTH / 2) nl + 1 else end
            chapters.add(
                Chapter(
                    bookId = bookId,
                    chapterOrder = startOrder + chapters.size,
                    title = if (start == 0) title else "$title (续${chapters.size - startOrder + 1})",
                    content = content.substring(start, cut)
                )
            )
            start = cut
        }
    }

    private fun detectImageExt(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "gif"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> null
        }
    }
}
