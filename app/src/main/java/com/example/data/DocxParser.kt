package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * DOCX 解析器：从 zip 里取 word/document.xml，按 Heading1/Heading2 分章，
 * 提取 <w:t> 文本入库（结构与 EpubParser 一致）。
 */
object DocxParser {

    private const val TAG = "DocxParser"

    fun isDocxFile(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".docx")
    }

    suspend fun importDocx(
        context: Context,
        uri: Uri,
        fileName: String,
        bookDao: BookDao,
        targetBookId: Int? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[DocxParser] Starting DOCX import for $fileName")
            val docx = readDocxContent(context, uri, 0)
                ?: return@withContext Result.failure(Exception("无法读取 DOCX 文件（缺少 word/document.xml）"))
            val xml = docx.documentXml

            val paragraphs = parseParagraphsWithImages(xml, docx.relTargets)
            if (paragraphs.isEmpty()) {
                return@withContext Result.failure(Exception("DOCX 文件中未找到有效正文内容"))
            }

            val title = paragraphs.firstOrNull { it.isHeading }?.text
                ?.ifBlank { null }
                ?: fileName.substringBeforeLast('.').ifBlank { fileName }

            val initialBook = Book(
                title = title,
                author = "未知作者",
                filePath = uri.toString(),
                contentType = "NOVEL",
                totalChapters = 0
            )
            // targetBookId 非空 = 老书补图片迁移：不新建书、失败不删书（调用方事务收尾）
            val bookId = targetBookId ?: bookDao.insertBook(initialBook).toInt()

            val chapters = mutableListOf<Chapter>()
            var order = 0
            var currentTitle = "第 1 章"
            val currentContent = StringBuilder()

            fun flushChapter() {
                val content = currentContent.toString().trim()
                if (content.isNotEmpty()) {
                    addChaptersWithSplit(chapters, bookId, order, currentTitle, content)
                    order = chapters.size
                }
                currentContent.setLength(0)
            }

            val hasHeadings = paragraphs.any { it.isHeading }
            for (para in paragraphs) {
                if (para.isHeading && hasHeadings) {
                    flushChapter()
                    currentTitle = para.text.ifBlank { "第 ${chapters.size + 1} 章" }
                } else if (para.text.isNotBlank()) {
                    if (currentContent.isNotEmpty()) currentContent.append("\n\n")
                    currentContent.append(para.text.trim())
                }
            }
            flushChapter()

            if (chapters.isEmpty()) {
                // 无标题结构：按固定长度兜底
                val flatText = paragraphs.joinToString("\n\n") { it.text.trim() }
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

            if (chapters.isEmpty()) {
                if (targetBookId == null) bookDao.deleteBook(initialBook.copy(id = bookId))
                return@withContext Result.failure(Exception("DOCX 文件中未找到有效正文内容"))
            }

            bookDao.insertChapters(chapters)
            val finalBook = initialBook.copy(
                id = bookId,
                totalChapters = chapters.size
            )
            bookDao.updateBook(finalBook)
            Log.d(TAG, "[DocxParser] Successfully imported '${finalBook.title}' with ${chapters.size} chapters.")
            Result.success(finalBook)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[DocxParser] Error during DOCX import", t)
            Result.failure(Exception(t.localizedMessage ?: "DOCX 解析失败"))
        }
    }

    private data class DocxParagraph(val text: String, val isHeading: Boolean)

    private data class DocxContent(
        val documentXml: String,
        /** rId -> 已落盘的媒体文件绝对路径（正文插图的引用来源） */
        val relTargets: Map<String, String>
    )

    private fun readDocxContent(context: Context, uri: Uri, bookId: Int): DocxContent? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var documentXml: String? = null
        val rels = mutableMapOf<String, String>()
        val imageDir = File(context.filesDir, "docx_images/$bookId")

        val budget = ArchiveBudget(16L * 1024 * 1024)
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    ArchiveBudget.destination(imageDir, entry.name)
                    when {
                        entry.name == "word/document.xml" -> {
                            val out = ByteArrayOutputStream()
                            budget.copyEntry(zip, out)
                            documentXml = String(out.toByteArray(), StandardCharsets.UTF_8)
                        }
                        entry.name == "word/_rels/document.xml.rels" -> {
                            val out = ByteArrayOutputStream()
                            budget.copyEntry(zip, out)
                            val relXml = String(out.toByteArray(), StandardCharsets.UTF_8)
                            // 关系映射：<Relationship Id="rIdX" Target="media/image1.png"/>
                            Regex("<Relationship\b[^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"[^>]*/?>")
                                .findAll(relXml)
                                .forEach { rm ->
                                    val target = rm.groupValues[2]
                                    if (target.contains("media/")) rels[rm.groupValues[1]] = target
                                }
                        }
                        !entry.isDirectory && entry.name.startsWith("word/media/") -> {
                            if (!imageDir.exists()) imageDir.mkdirs()
                            val f = File(imageDir, entry.name.substringAfterLast('/'))
                            FileOutputStream(f).use { budget.copyEntry(zip, it) }
                            rels["__file__" + entry.name] = f.absolutePath
                        }
                        else -> budget.copyEntry(zip)
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (documentXml == null) return null
        // rId -> 落盘图片路径
        val resolved = mutableMapOf<String, String>()
        for ((rid, target) in rels) {
            if (rid.startsWith("__file__")) continue
            val name = target.substringAfterLast('/')
            val local = File(imageDir, name)
            if (local.exists()) resolved[rid] = local.absolutePath
        }
        return DocxContent(documentXml, resolved)
    }

    /** 段落文本 + 内嵌插图（r:embed → 已落盘媒体）→ 文本与 [IMG:...] 占位符交错 */
    private fun parseParagraphsWithImages(
        xml: String,
        relTargets: Map<String, String>
    ): List<DocxParagraph> {
        val result = mutableListOf<DocxParagraph>()
        val paraRegex = Regex("""<w:p[ >].*?</w:p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val headingRegex = Regex("""<w:pStyle w:val="(Heading[1-6][^"]*)"/""", RegexOption.IGNORE_CASE)
        val blipRegex = Regex("""r:embed="(rId\d+)"""")
        for (m in paraRegex.findAll(xml)) {
            val raw = m.value
            val isHeading = headingRegex.containsMatchIn(raw)
            val text = extractText(raw)
            val tokens = mutableListOf<String>()
            blipRegex.findAll(raw).forEach { bm ->
                val path = relTargets[bm.groupValues[1]] ?: return@forEach
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val w = if (opts.outWidth > 0) opts.outWidth else 800
                val h = if (opts.outHeight > 0) opts.outHeight else 600
                tokens.add("[IMG:$path|$w|$h]")
            }
            val combined = (text.trim() + (if (tokens.isNotEmpty()) "\n" + tokens.joinToString("\n") else "")).trim()
            if (combined.isNotBlank() || isHeading) {
                result.add(DocxParagraph(combined, isHeading))
            }
        }
        return result
    }

    private fun parseParagraphs(xml: String): List<DocxParagraph> {
        val result = mutableListOf<DocxParagraph>()
        val paraRegex = Regex("""<w:p[ >].*?</w:p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val headingRegex = Regex("""<w:pStyle w:val="(Heading[1-6][^"]*)"/""", RegexOption.IGNORE_CASE)
        for (m in paraRegex.findAll(xml)) {
            val raw = m.value
            val isHeading = headingRegex.containsMatchIn(raw)
            val text = extractText(raw)
            if (text.isNotBlank() || isHeading) {
                result.add(DocxParagraph(text, isHeading))
            }
        }
        return result
    }

    private fun extractText(raw: String): String {
        val sb = StringBuilder()
        val tRegex = Regex("""<w:t[^>]*>(.*?)</w:t>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in tRegex.findAll(raw)) {
            sb.append(m.groupValues[1])
        }
        var text = sb.toString()
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
            }
        return text.replace(Regex("""\s+"""), " ").trim()
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
        content.let { splitChapterText(it) }.forEachIndexed { index, part ->
            chapters.add(
                Chapter(
                    bookId = bookId,
                    chapterOrder = startOrder + index,
                    title = if (index == 0) title else "$title (续${index + 1})",
                    content = part
                )
            )
        }
    }
}
