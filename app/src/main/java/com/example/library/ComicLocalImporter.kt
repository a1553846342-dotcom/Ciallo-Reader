package com.example.library

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.example.data.Book
import com.example.data.BookDao
import com.example.data.Chapter
import com.example.data.readImportBytes
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.source.executeCancellable
import com.example.source.js.JsCookieJar
import com.example.source.js.JsImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads one online comic chapter as a local comic book:
 * images are saved into a folder and registered in Room, so the existing
 * local comic reader can open it offline (same format as imported CBZ).
 */
object ComicLocalImporter {

    private const val TAG = "ComicDownload"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/108.0.5359.128 Mobile Safari/537.36"

    @Volatile
    private var cachedClient: OkHttpClient? = null

    /** 跨章节全局页并发上限（Mihon 下载队列思想）：同时下载多章时总网络压力仍 ≤4，防图床封禁。 */
    private val globalPageSemaphore = Semaphore(4)

    /** 图片先直连，连接失败或受限时用系统代理重试；picacg 优先走代理。 */
    private fun client(context: Context): OkHttpClient =
        cachedClient ?: synchronized(this) {
            cachedClient ?: com.example.source.js.JsSourceProxy.failoverClient(
                context.applicationContext,
                com.example.source.SharedHttpTransport.builder()
                    .addInterceptor(com.example.source.PufeiImageCacheRetry)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                    .build()
            )
                .also { cachedClient = it }
        }

    suspend fun importChapter(
        context: Context,
        bookDao: BookDao,
        book: SearchBook,
        chapter: ComicChapter,
        imageUrls: List<String>,
        headers: Map<String, Map<String, String>> = emptyMap(),
        referer: String? = null,
        onProgress: (Float) -> Unit,
        targetDir: File? = null,
        resolveImage: (suspend (String) -> String?)? = null,
        resolveHeaders: (suspend (String) -> Map<String, String>)? = null,
        concurrency: Int = 3
    ): Result<Book> = withContext(Dispatchers.IO) {
        var comicDir: File? = null
        var pendingCover: File? = null
        try {
            require(imageUrls.size <= 10_000) { "章节图片过多" }
            if (imageUrls.isEmpty()) {
                return@withContext Result.failure(Exception("章节没有图片"))
            }

            val chapterDir = targetDir ?: File(context.filesDir, "comics_${System.currentTimeMillis()}")
            comicDir = chapterDir
            if (!chapterDir.exists()) chapterDir.mkdirs()
            // Snapshot once: scanning every file and rereading the same stamp for each
            // page makes resuming a large chapter quadratic in the number of pages.
            val existingPageFiles = chapterDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("img_") && !it.name.endsWith(".part") }
                .groupBy { it.name.substringBefore('.') }

            // 逐页检查磁盘文件，而不是用进度浮点数推算起点：并发完成顺序不固定。
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            val progressLock = Any()
            var completedPages = 0
            fun reportPageDone() {
                synchronized(progressLock) {
                    completedPages++
                    onProgress(completedPages / imageUrls.size.toFloat())
                }
            }
            suspend fun downloadPage(index: Int): File {
                coroutineContext.ensureActive()
                val url = imageUrls[index]
                val stamp = File(chapterDir, "page_${index + 1}.url")
                val identity = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val filePrefix = String.format("img_%04d.", index + 1)
                val existing = if (stamp.isFile && stamp.readText() == identity) {
                    existingPageFiles[filePrefix.removeSuffix(".")]?.firstOrNull { file ->
                        file.length() > 0 && hasImageBounds(file)
                    }
                } else null
                if (existing != null) {
                    reportPageDone()
                    return existing
                }
                var fetchUrl = url
                var fetchHeaders = headers[url].orEmpty()
                if (resolveImage != null) {
                    val resolved = resolveImage(url)
                    if (!resolved.isNullOrBlank() && resolved != url) {
                        fetchUrl = resolved
                        fetchHeaders = fetchHeaders + resolveHeaders?.invoke(resolved).orEmpty()
                    }
                }
                val rawExt = fetchUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
                val ext = if (rawExt.length in 3..4 && rawExt.all { it.isLetterOrDigit() }) rawExt else "jpg"
                val pageFile = File(chapterDir, String.format("img_%04d.%s", index + 1, ext))
                if (fetchUrl.startsWith("file:")) {
                    val srcFile = java.io.File(java.net.URI.create(fetchUrl))
                    if (srcFile.exists() && hasImageBounds(srcFile)) {
                        require(srcFile.length() <= 32L * 1024 * 1024) { "图片过大" }
                        srcFile.copyTo(pageFile, overwrite = true)
                        stamp.writeText(identity)
                        reportPageDone()
                        return pageFile
                    }
                    throw IOException("本地缓存图片无效，请重新打开章节后重试")
                }
                val requestBuilder = Request.Builder()
                    .url(fetchUrl)
                    .header("User-Agent", UA)
                fetchHeaders.forEach { (k, v) ->
                    requestBuilder.header(k, v)
                }
                if (fetchHeaders.keys.none { it.equals("Accept", ignoreCase = true) }) {
                    requestBuilder.header("Accept", "image/webp,image/jpeg,image/png,*/*;q=0.8")
                }
                val cookie = JsCookieJar.cookieHeader(context, fetchUrl)
                    .ifBlank { JsCookieJar.cookieHeader(context, url) }
                if (cookie.isNotBlank() &&
                    fetchHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }
                ) {
                    requestBuilder.header("Cookie", cookie)
                }
                if (!referer.isNullOrBlank() && fetchHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                    requestBuilder.header("Referer", referer)
                }
                val request = requestBuilder.build()
                // 跨章节全局页并发上限：总网络压力 ≤4
                globalPageSemaphore.acquire()
                try {
                fetchWithRetry(context, request).use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("图片下载失败 HTTP ${response.code} (${url.take(80)})")
                    }
                    val raw = response.body?.byteStream()?.use { it.readImportBytes(32 * 1024 * 1024) } ?: throw IOException("图片响应为空")
                    var bytes = ImageBytes.normalizeImage(raw, response.header("Content-Encoding"))
                    if (ImageBytes.isAvif(bytes) && !ImageBytes.decodeOk(bytes)) {
                        for (candidate in ImageBytes.webpVariants(fetchUrl)) {
                            try {
                                val rb = request.newBuilder().url(candidate)
                                    .header("Accept", "image/webp,image/jpeg,image/png,*/*;q=0.8")
                                val cookie2 = JsCookieJar.cookieHeader(context, candidate)
                                if (cookie2.isNotBlank()) rb.header("Cookie", cookie2)
                                if (!referer.isNullOrBlank()) rb.header("Referer", referer)
                                client(context).newCall(rb.build()).executeCancellable().use { r2 ->
                                    if (r2.isSuccessful) {
                                        val b2 = r2.body?.byteStream()?.use { it.readImportBytes(32 * 1024 * 1024) }
                                        if (b2 != null) {
                                            val p2 = ImageBytes.normalizeImage(b2, r2.header("Content-Encoding"))
                                            if (!ImageBytes.isAvif(p2) && ImageBytes.decodeOk(p2)) {
                                                bytes = p2
                                                return@use
                                            }
                                        }
                                    }
                                }
                                if (!ImageBytes.isAvif(bytes) && ImageBytes.decodeOk(bytes)) break
                            } catch (e: Exception) {
                                coroutineContext.ensureActive()
                                // 尝试下一个候选
                            }
                        }
                    }
                    bytes = if (MhttuImageDecryptor.isEncryptedHost(
                            try { java.net.URL(fetchUrl).host } catch (e: Exception) { "" }
                        )
                    ) {
                        MhttuImageDecryptor.decryptIfNeeded(bytes)
                    } else {
                        bytes
                    }
                    bytes = JsImageProcessor.transform(fetchUrl, bytes) ?: bytes
                    if (!hasImageBounds(bytes)) {
                        throw IOException("图片不是可解码的图像 (${fetchUrl.take(80)})")
                    }
                    val part = File(chapterDir, "${pageFile.name}.part")
                    require(chapterDir.usableSpace > bytes.size.toLong() + 16L * 1024 * 1024) { "磁盘空间不足" }
                    part.outputStream().use { output -> output.write(bytes) }
                    check(part.renameTo(pageFile)) { "图片保存失败" }
                    stamp.writeText(identity)
                }
                } finally {
                    globalPageSemaphore.release()
                }
                reportPageDone()
                return pageFile
            }
            val pageFileResults: List<File?> = coroutineScope {
                // 每页失败先记下，整章不立即失败；全部完成后对失败页做一轮集中重试，
                // 网络抖动（代理切换/DNS 波动）不再毁掉整章下载。
                suspend fun attempt(index: Int): Result<File> = try {
                    Result.success(downloadPage(index))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                var results = imageUrls.indices.map { index ->
                    async { semaphore.withPermit { attempt(index) } }
                }.awaitAll().map { it.getOrNull() }
                val firstFail = results.withIndex().filter { it.value == null }.map { it.index }
                if (firstFail.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    Log.i(TAG, "second pass for ${firstFail.size}/${imageUrls.size} failed pages")
                    val retried = firstFail.map { index ->
                        async { semaphore.withPermit { attempt(index) } }
                    }.awaitAll()
                    retried.forEachIndexed { i, r -> results = results.toMutableList().also { it[firstFail[i]] = r.getOrNull() } }
                }
                results
            }
            coroutineContext.ensureActive()
            val failedAt = pageFileResults.indexOfFirst { it == null }
            if (failedAt >= 0) {
                if (targetDir == null) chapterDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("第 ${failedAt + 1}/${imageUrls.size} 页多次重试后仍下载失败，请检查网络后重试")
                )
            }
            val pageFiles: List<File> = pageFileResults.map { requireNotNull(it) }

            val coverDir = File(context.filesDir, "comic_covers")
            if (!coverDir.exists()) coverDir.mkdirs()
            val coverFile = File(coverDir, "cover_${java.util.UUID.randomUUID()}.jpg")
            pendingCover = coverFile
            pageFiles.first().copyTo(coverFile, overwrite = true)

            val cleanTitle = "${book.title} · ${chapter.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .take(120)
                .ifBlank { book.title }

            val newBook = Book(
                title = cleanTitle,
                author = book.author.ifBlank { "漫画" },
                filePath = chapterDir.absolutePath,
                coverUri = coverFile.absolutePath,
                totalChapters = pageFiles.size,
                contentType = "COMIC",
                // 记录来源：让书架里的这本书能反查到「我喜欢的」在线条目
                sourceId = book.sourceId.ifBlank { null },
                comicId = book.id.ifBlank { null },
            )
            val inserted = withContext(NonCancellable) {
                com.example.data.ContentMutationGate.mutex.lock()
                try {
                bookDao.getBookByFilePath(chapterDir.absolutePath) ?: bookDao.insertBookWithChapters(newBook,
                    pageFiles.mapIndexed { index, file -> Chapter(bookId=0,chapterOrder=index,title="第 ${index+1} 页",content=file.absolutePath) })
                } finally { com.example.data.ContentMutationGate.mutex.unlock() }
            }

            if (inserted.coverUri == coverFile.absolutePath) pendingCover = null
            Log.i(TAG, "downloaded chapter ${chapter.title} -> ${pageFiles.size} pages")
            Result.success(inserted)
        } catch (e: CancellationException) {
            // 暂停/取消：保留目标目录，由调用方决定删除还是续传
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "chapter download failed", t)
            // 只有新建目录时失败才清理；续传目录由调用方（暂停/取消）管理
            if (targetDir == null) comicDir?.deleteRecursively()
            Result.failure(Exception(t.localizedMessage ?: "章节下载失败"))
        } finally { pendingCover?.delete() }
    }

    private fun hasImageBounds(file: File): Boolean = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) { false }

    private fun hasImageBounds(bytes: ByteArray): Boolean = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) { false }

    private suspend fun fetchWithRetry(context: Context, request: Request): Response {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                val response = client(context).newCall(request).executeCancellable()
                if (response.code !in 500..599 && response.code != 429 || attempt == 2) {
                    return response
                }
                val rateLimited = response.code == 429
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                response.close()
                // 图床限流（如 comick）按 Retry-After/指数退避多等一会，普通 5xx 短退避；加抖动防雷群
                delay(
                    (retryAfter?.coerceIn(1, 10)?.times(1000)
                        ?: if (rateLimited) 2000L * (attempt + 1) else 700L * (attempt + 1)
                    ) + (0L..300L).random()
                )
                return@repeat
            } catch (e: IOException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lastError = e
                if (attempt == 2) throw e
            }
            delay(700L * (attempt + 1))
        }
        throw lastError ?: IOException("图片请求失败")
    }
}
