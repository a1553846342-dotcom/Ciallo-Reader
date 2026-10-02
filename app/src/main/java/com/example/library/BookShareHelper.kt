package com.example.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 书架长按"分享"：把书籍原文件通过系统分享面板发给微信/QQ 等。
 *
 * 策略（按格式区分）：
 * - content://（本地导入的 SAF 文件）：原 URI 直接分享，零拷贝；
 * - file:// 且文件仍在（EPUB / MOBI / PDF / AZW3 / 本地 TXT）：FileProvider 直接包装原文件，零拷贝；
 * - 漫画（filePath 是解压目录）：优先找回下载任务里保留的原始归档（cbz/zip/pdf），
 *   找不到则把解压页重新打成 cbz 放进 share_temp（用完即焚缓存）；
 * - TXT（下载后原文件已被清理）：从数据库章节内容重建 txt 到 share_temp（用完即焚缓存）。
 *
 * 临时文件清理：每次分享前清空 share_temp/ 旧文件，App 冷启动时也兜底清一次，
 * 保证该目录最多只保留"最近一次分享"的文件，不会无限增长。
 */
object BookShareHelper {

    private const val TAG = "BookShare"
    private const val SHARE_TEMP_DIR = "share_temp"
    /** 分享临时文件保留时间：给微信/QQ 留足读取时间，超时后自动删除，不长期占双份内存。 */
    private const val SHARE_TEMP_RETENTION_MS = 24 * 60 * 60 * 1000L

    /** 进程级作用域：分享面板关闭后延时清理临时文件，不依赖弹窗/页面存活。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ShareTarget(
        val uri: Uri,
        val mime: String,
        /** 本次分享新建的临时文件（用完即焚，分享后自动删除）。 */
        val tempFiles: List<File> = emptyList()
    )

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 冷启动兜底清理：清空分享临时目录。 */
    fun cleanupTempShareDir(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, SHARE_TEMP_DIR)
            if (dir.exists()) {
                val cutoff = System.currentTimeMillis() - SHARE_TEMP_RETENTION_MS
                dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
            }
        }
    }

    /**
     * 分享书籍。返回 null 表示已成功拉起分享面板，否则返回错误文案。
     * 内部在 IO 线程定位/生成文件，在主线程拉起 Intent。
     */
    suspend fun shareBook(context: Context, book: Book): String? = withContext(Dispatchers.IO) {
        try {
            // 用前清空旧的分享临时文件（用完即焚：目录里永远只有最近一份）
            cleanupTempShareDir(context)

            val target = resolveShareTarget(context, book)

            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = target.mime
                    putExtra(Intent.EXTRA_STREAM, target.uri)
                    putExtra(Intent.EXTRA_SUBJECT, book.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "分享「${book.title}」")
                if (context !is Activity) {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }

            // 用完即焚：分享拉起来后延时删除本次临时文件。
            // 不立即删——分享面板关闭不代表目标应用已经读完文件。
            if (target.tempFiles.isNotEmpty()) {
                scope.launch {
                    delay(SHARE_TEMP_RETENTION_MS)
                    target.tempFiles.forEach { file ->
                        runCatching { file.delete() }
                    }
                }
            }
            null
        } catch (e: ActivityNotFoundException) {
            "没有找到可分享的应用"
        } catch (e: Exception) {
            Log.w(TAG, "shareBook failed: ${book.title}", e)
            e.message ?: "分享失败"
        }
    }

    /** 定位分享目标：返回 (content URI, MIME)。 */
    private suspend fun resolveShareTarget(context: Context, book: Book): ShareTarget {
        val raw = book.filePath

        // 1) SAF 导入的书：content://
        if (raw.startsWith("content://")) {
            val uri = Uri.parse(raw)
            val mime = runCatching { context.contentResolver.getType(uri) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: mimeForPath(raw, book)
            // 零拷贝格式（EPUB / 漫画归档）：原 URI 直接分享，系统授权给目标应用读取
            if (isZeroCopyFormat(raw, mime, book)) {
                return ShareTarget(uri, mime)
            }
            // MOBI / PDF / AZW3 / FB2 / DJVU 等：把源文件字节原样复制到 share_temp
            // 再分享，保证微信/QQ 拿到的就是完整的原始文件（用完即焚缓存）
            return copyContentUriToTemp(context, uri, book, mime)
        }

        val path = raw.removePrefix("file://")
        val file = File(path)

        // 2) 原文件还在：FileProvider 直接包装，零拷贝
        if (file.isFile && file.exists()) {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            return ShareTarget(uri, mimeForPath(raw, book))
        }

        // 3) 漫画：filePath 是解压目录（comics_xxx），原归档要么在下载任务里，要么重新打包
        if (file.isDirectory) {
            return resolveComicArchive(context, book, file)
        }

        // 4) 原文件路径失效（被移动/清理）：从下载任务记录找回原始文件
        if (!file.exists()) {
            val taskFile = findCompletedTaskFile(context, book)
            if (taskFile != null) {
                val uri = FileProvider.getUriForFile(context, authority(context), taskFile)
                return ShareTarget(uri, mimeForPath(taskFile.absolutePath, book))
            }
        }

        // 5) TXT 下载后原文件已被清理：从数据库章节内容重建（用完即焚缓存）
        if (!book.isComic && hasTextChapters(context, book)) {
            return rebuildTxt(context, book)
        }

        throw IllegalStateException("源文件已不存在，无法分享（原文件可能已被移动或删除）")
    }

    /** 漫画：优先用下载任务里保留的原始归档；找不到则把解压页重新打成 CBZ。 */
    private suspend fun resolveComicArchive(context: Context, book: Book, dir: File): ShareTarget {
        // 3a) 下载的漫画：DownloadTask 记录里保留着原始 cbz/zip/pdf 文件
        val archive = findCompletedTaskFile(context, book)
        if (archive != null) {
            val uri = FileProvider.getUriForFile(context, authority(context), archive)
            return ShareTarget(uri, mimeForPath(archive.absolutePath, book))
        }

        // 3b) 本地导入的漫画：原归档没保留，把解压页重新打包成 CBZ（用完即焚缓存）
        val pages = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
            .mapNotNull { ch -> ch.content.takeIf { it.isNotBlank() }?.let { File(it) } }
            .filter { it.isFile }
        if (pages.isEmpty()) {
            throw IllegalStateException("漫画页面文件缺失，无法分享")
        }

        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.cbz")
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            pages.forEachIndexed { index, page ->
                zip.putNextEntry(ZipEntry(String.format("%04d_%s", index, page.name)))
                page.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, authority(context), out)
        return ShareTarget(uri, "application/vnd.comicbook+zip", tempFiles = listOf(out))
    }

    /** 从下载任务记录里找回这本书的原始文件（按书名 + 已完成 + 文件仍存在匹配）。 */
    private suspend fun findCompletedTaskFile(context: Context, book: Book): File? {
        val tasks = runCatching {
            AppDatabase.getDatabase(context).downloadTaskDao().getAllTasksSync()
        }.getOrDefault(emptyList())
        return tasks.firstOrNull {
            it.status == DownloadStatus.COMPLETED &&
                (it.filePath.removePrefix("file://") == book.filePath.removePrefix("file://") ||
                    (book.sourceId == it.sourceId && book.comicId == com.example.download.DownloadManager.originalBookId(it.id, it.sourceId))) &&
                it.filePath.isNotBlank() &&
                File(it.filePath).isFile
        }?.let { File(it.filePath) }
    }

    /** 零拷贝格式：EPUB 和漫画归档直接分享原 URI；其余二进制格式走临时副本。 */
    private fun isZeroCopyFormat(rawPath: String, mime: String, book: Book): Boolean {
        if (book.isComic) return true
        val ext = rawPath.substringAfterLast('.', "").lowercase()
        return ext == "epub" || mime == "application/epub+zip" ||
            ext == "cbz" || ext == "zip" ||
            mime == "application/vnd.comicbook+zip"
    }

    /** content:// 源文件字节原样复制到 share_temp（用完即焚），保证目标应用拿到完整原始文件。 */
    private fun copyContentUriToTemp(
        context: Context,
        uri: Uri,
        book: Book,
        mime: String
    ): ShareTarget {
        val ext = resolveExtension(context, uri, book, mime)
        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.$ext")
        out.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取源文件，可能已被移动或删除")
        input.use { src ->
            FileOutputStream(out).use { dst -> src.copyTo(dst) }
        }
        val fileUri = FileProvider.getUriForFile(context, authority(context), out)
        return ShareTarget(fileUri, mime, tempFiles = listOf(out))
    }

    private fun resolveExtension(context: Context, uri: Uri, book: Book, mime: String): String {
        // 1) 原文件显示名里的扩展名（最准确）
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) {
                    val name = cursor.getString(idx)
                    val ext = name?.substringAfterLast('.', "")?.lowercase()
                    if (!ext.isNullOrBlank() && ext.length in 1..6) return ext
                }
            }
        }
        // 2) URI 路径里的扩展名
        val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (!pathExt.isNullOrBlank() && pathExt.length in 1..6 && pathExt.all(Char::isLetterOrDigit)) {
            return pathExt
        }
        // 3) MIME 反推
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/epub+zip" -> "epub"
            "application/vnd.comicbook+zip" -> "cbz"
            "application/x-fictionbook+xml" -> "fb2"
            "text/plain" -> "txt"
            else -> book.filePath.substringAfterLast('.', "").lowercase().ifBlank { "file" }
        }
    }

    /** TXT 重建：把数据库章节内容按顺序写回文本文件。 */
    private suspend fun rebuildTxt(context: Context, book: Book): ShareTarget {
        val chapters = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            throw IllegalStateException("书籍内容为空，无法分享")
        }
        val sb = StringBuilder()
        chapters.forEach { ch ->
            if (ch.title.isNotBlank()) {
                sb.append(ch.title).append("\n\n")
            }
            sb.append(ch.content)
            if (!ch.content.endsWith("\n")) sb.append("\n")
            sb.append("\n")
        }
        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, authority(context), out)
        return ShareTarget(uri, "text/plain", tempFiles = listOf(out))
    }

    private suspend fun hasTextChapters(context: Context, book: Book): Boolean {
        val chapters = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
        // 漫画章节内容是图片路径；文本书章节内容才是正文
        return chapters.any { it.content.length > 50 }
    }

    private fun mimeForPath(path: String, book: Book): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> "application/epub+zip"
            "cbz" -> "application/vnd.comicbook+zip"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            "mobi", "prc" -> "application/x-mobipocket-ebook"
            "azw3", "azw", "kfx" -> "application/octet-stream"
            "fb2" -> "application/x-fictionbook+xml"
            "txt" -> "text/plain"
            "djvu" -> "image/vnd.djvu"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "book" }.take(80)
    }
}
