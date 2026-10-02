package com.example.download

import androidx.room.withTransaction
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.source.executeCancellable
import com.example.source.zlibrary.DiamWallInterceptor
import com.example.source.zlibrary.EncryptedCookieJar
import com.example.source.zlibrary.ZLibraryCredentialStorage
import com.example.source.zlibrary.network.SystemProxyResolver
import com.example.source.zlibrary.network.ZLibraryDns
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private val slots = Semaphore(2)
        private val taskLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
        private const val CHANNEL = "book_downloads"

        internal suspend fun <T> withTaskLock(taskId: String, block: suspend () -> T): T {
            val lock = taskLocks[(taskId.hashCode() and Int.MAX_VALUE) % taskLocks.size]
            lock.lock()
            return try { block() } finally { lock.unlock() }
        }
    }

    private val client by lazy {
        val jar = EncryptedCookieJar(ZLibraryCredentialStorage(applicationContext))
        com.example.source.SharedHttpTransport.builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .dns(ZLibraryDns.INSTANCE).cookieJar(jar).addInterceptor(DiamWallInterceptor(jar))
            .apply { SystemProxyResolver.resolve(applicationContext)?.let { proxy(it) } }.build()
    }

    private val publicClient by lazy {
        com.example.source.SharedHttpTransport.builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .apply { SystemProxyResolver.resolve(applicationContext)?.let { proxy(it) } }.build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "书籍下载", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("正在下载书籍")
            .setContentText(inputData.getString("title") ?: "书籍下载").setOngoing(true)
            .setProgress(0, 0, true).build()
        val notificationId = id.hashCode() and Int.MAX_VALUE
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString("book_id") ?: return@withContext Result.failure()
        withTaskLock(taskId) { performTask() }
    }

    private suspend fun performTask(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString("book_id") ?: return@withContext Result.failure()
        val db = AppDatabase.getDatabase(context)
        val dao = db.downloadTaskDao()
        val task = dao.getTaskById(taskId) ?: return@withContext Result.failure()
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.CANCELLED) return@withContext Result.success()
        val downloads = File(context.filesDir, "downloads").apply { mkdirs() }
        val format = task.format.lowercase().trim()
        if (!format.matches(Regex("[a-z0-9]{1,10}"))) return@withContext Result.failure()
        val finalFile = File(task.filePath)
        if (finalFile.canonicalFile.parentFile != downloads.canonicalFile) return@withContext Result.failure()
        val temp = File(downloads, "${finalFile.nameWithoutExtension}.tmp")
        val resume = File(downloads, "${finalFile.nameWithoutExtension}.resume")
        var total = task.totalBytes
        try {
            val secrets=com.example.data.EncryptedSecretStore(applicationContext)
            val savedHeaders=secrets.read("download:$taskId")?.let { JSONObject(it) } ?: JSONObject()
            // Existing jobs may still contain the pre-upgrade header fields.
            inputData.getString("referer")?.takeIf { it.isNotBlank() }?.let { if(!savedHeaders.has("Referer")) savedHeaders.put("Referer",it) }
            inputData.getString("cookie")?.takeIf { it.isNotBlank() }?.let { if(!savedHeaders.has("Cookie")) savedHeaders.put("Cookie",it) }
            val requestHeaders=savedHeaders.keys().asSequence().associateWith { savedHeaders.getString(it) }
            setForeground(getForegroundInfo())
            slots.withPermit {
                currentCoroutineContext().ensureActive()
                dao.updateProgressAndStatus(taskId, DownloadStatus.DOWNLOADING, temp.length(), total, null)
                // Failed imports can be retried from the completed file without consuming another download.
                val metadata = runCatching { JSONObject(resume.readText()) }.getOrNull()
                if (!(finalFile.isFile && metadata?.optString("url") == task.downloadUrl && metadata.optBoolean("complete"))) {
                    var offset = temp.takeIf { it.isFile }?.length() ?: 0L
                    val validator = metadata?.optString("validator")?.takeIf { it.isNotBlank() }
                    if (metadata?.optString("url") != task.downloadUrl || validator == null) offset = 0L
                    val builder = Request.Builder().url(task.downloadUrl).header("Accept-Encoding", "identity")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) CialloReader/1.1")
                    requestHeaders.filterKeys { !it.equals("Cookie",true) && !it.equals("Range",true) && !it.equals("If-Range",true) && !it.equals("Accept-Encoding",true) }.forEach { (key,value) -> builder.header(key,value) }
                    val host = builder.build().url.host
                    val cookie = requestHeaders.entries.firstOrNull { it.key.equals("Cookie",true) }?.value
                    if (!cookie.isNullOrBlank() && listOf("ncdn", "cdn-zlib", "s3proxy", "dln").none { it in host }) builder.header("Cookie", cookie)
                    if (offset > 0) builder.header("Range", "bytes=$offset-").header("If-Range", validator!!)
                    var request = builder.build()
                    var rangeRestarted = false
                    while (true) {
                        val restart = withResponse(request, task.sourceId) { response ->
                            // A 416 is not proof of file completeness. Restart safely once.
                            if (response.code == 416 && offset > 0 && !rangeRestarted) return@withResponse true
                            require(response.isSuccessful) { when (response.code) {
                                401, 403 -> "下载授权已失效，请重新登录或重新获取链接（HTTP ${response.code}）"
                                429 -> "请求过于频繁，请稍后重试（HTTP 429）"
                                else -> "下载失败（HTTP ${response.code}）"
                            } }
                            require(!isHtmlError(response, format)) { "服务器返回了验证或错误页，下载链接可能已过期，请重新获取链接" }
                            val body = response.body ?: error("下载响应为空")
                            val append = response.code == 206 && offset > 0
                            if (response.code == 206) {
                                require(append) { "服务器意外返回部分文件" }
                                total = DownloadTransferPolicy.validateTail(response.header("Content-Range"), offset, body.contentLength())
                                val returned = DownloadTransferPolicy.validator(response.header("ETag"), response.header("Last-Modified"))
                                require(returned == null || returned == validator) { "续传文件版本发生变化，请重新下载" }
                                val previousTotal = metadata?.optLong("total", 0L) ?: 0L
                                require(previousTotal <= 0 || previousTotal == total) { "续传文件大小发生变化" }
                            } else {
                                require(response.code == 200) { "服务器未返回完整文件" }
                                offset = 0L
                                total = body.contentLength()
                            }
                            require(total <= 0 || downloads.usableSpace >= total - offset + 16L * 1024 * 1024) { "存储空间不足，请清理后重试" }
                            // Truncate before replacing metadata: a crash cannot pair an old prefix with a new validator.
                            FileOutputStream(temp, append).use { out ->
                                val nextValidator = DownloadTransferPolicy.validator(response.header("ETag"), response.header("Last-Modified"))
                                resume.writeText(JSONObject().put("url", task.downloadUrl).put("validator", nextValidator ?: "")
                                    .put("total", total).put("complete", false).toString())
                                var downloaded = offset
                                var lastUi = 0L
                                var lastDb = 0L
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                        downloaded += read
                                        require(total <= 0 || downloaded <= total) { "下载数据超过声明长度" }
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastUi >= 300) {
                                            lastUi = now
                                            DownloadProgressBroadcaster.updateState(taskId, DownloadState.Downloading(downloaded, total,
                                                if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f))
                                        }
                                        if (now - lastDb >= 2000) {
                                            lastDb = now
                                            dao.updateProgressAndStatus(taskId, DownloadStatus.DOWNLOADING, downloaded, total, null)
                                        }
                                    }
                                }
                                out.flush()
                                require(total <= 0 || downloaded == total) { "文件下载不完整，请恢复下载" }
                            }
                            false
                        }
                        if (!restart) break
                        rangeRestarted = true
                        offset = 0L
                        request = request.newBuilder().removeHeader("Range").removeHeader("If-Range").build()
                    }
                    if (task.sourceId == "ixdzs8" && format == "txt") {
                        val text = NovelTextArchive.prepare(temp) ?: error("网文源未返回 TXT 打包文件，请重试")
                        text.let {
                            try {
                                // The text bytes cannot resume against the archive's validator.
                                resume.writeText(JSONObject().put("url", task.downloadUrl).put("complete", false).toString())
                                check(temp.delete() && text.renameTo(temp)) { "无法保存小说文本" }
                            } finally { text.delete() }
                        }
                    }
                    val integrity = DownloadFileValidator.validateFileIntegrity(temp, format)
                    require(integrity.valid) { integrity.htmlErrorHint ?: "下载文件格式校验失败" }
                    val actualFormat = integrity.actualFormat ?: format
                    val actualFile = File(downloads, "${finalFile.nameWithoutExtension}.$actualFormat")
                    check(!actualFile.exists() || actualFile.delete()) { "无法替换旧下载文件" }
                    check(temp.renameTo(actualFile)) { "无法保存下载文件" }
                    dao.insertOrUpdate(task.copy(status = DownloadStatus.DOWNLOADING, format = actualFormat,
                        filePath = actualFile.absolutePath, downloadedBytes = actualFile.length(), totalBytes = actualFile.length()))
                    val completeMetadata = runCatching { JSONObject(resume.readText()) }.getOrDefault(JSONObject())
                    resume.writeText(completeMetadata.put("complete", true).toString())
                }
                var latest = dao.getTaskById(taskId) ?: error("下载任务已取消")
                val file = File(latest.filePath)
                val integrity = DownloadFileValidator.validateFileIntegrity(file, latest.format)
                require(integrity.valid) { "下载文件损坏，请重新下载" }
                latest = latest.copy(format = integrity.actualFormat ?: latest.format)
                val novelStore = NovelDownloadStore(context)
                val novel = novelStore.pending(taskId)?.takeIf {
                    com.example.source.WholeBookNovelSources.contains(task.sourceId) && it.book.sourceId == task.sourceId &&
                        it.book.id == DownloadManager.originalBookId(task.id, task.sourceId)
                }
                val repository = BookRepository(context, db.bookDao(), db)
                if (novel != null) {
                    val baseline = novelStore.baseline(novel.book.sourceId, novel.book.id)?.novelInfo
                    val old = db.bookDao().getBookBySourceResource(novel.book.sourceId, novel.book.id)
                    if (novel.replace && old != null && baseline?.hasRevision == true &&
                        novel.book.novelInfo?.hasRevision == true && baseline.revision != novel.book.novelInfo.revision) {
                        val oldFile = File(old.filePath.removePrefix("file://"))
                        fun fingerprint(input: File): ByteArray {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            input.inputStream().buffered().use { stream ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                            }
                            return digest.digest()
                        }
                        require(!oldFile.isFile || !fingerprint(oldFile).contentEquals(fingerprint(file))) {
                            "源站整本下载包尚未更新，已保留本地版本；请稍后重试"
                        }
                    }
                    repository.importDownloadedNovel(Uri.fromFile(file), novel.book.copy(format = latest.format), novel.replace).getOrThrow()
                }
                else repository.importBookFromUri(Uri.fromFile(file), "${task.title}.${latest.format}", task.sourceId,
                    DownloadManager.originalBookId(task.id, task.sourceId)).getOrThrow()
                // Once import commits, finish bookkeeping even if pause arrives at that boundary.
                withContext(NonCancellable) {
                    if (novel != null) novelStore.imported(taskId)
                    db.withTransaction {
                        dao.insertOrUpdate(latest.copy(status = DownloadStatus.COMPLETED, errorMessage = null,
                            downloadedBytes = file.length(), totalBytes = file.length()))
                    }
                    secrets.remove("download:$taskId")
                    DownloadProgressBroadcaster.updateState(taskId, DownloadState.Success(file.absolutePath))
                }
                Result.success()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Explicit cancel deleted the row; never recreate or rebroadcast it.
                val latest = dao.getTaskById(taskId)
                if (latest != null && latest.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)) {
                    dao.updateProgressAndStatus(taskId, DownloadStatus.PAUSED, temp.length(), total, null)
                    DownloadProgressBroadcaster.updateState(taskId, DownloadState.Paused(temp.length(), total))
                }
            }
            throw e
        } catch (e: Exception) {
            val message = if (e.message?.contains("ENOSPC", true) == true) "存储空间不足，请清理后重试" else e.message ?: "下载或入库失败"
            if (dao.getTaskById(taskId) != null) {
                dao.updateProgressAndStatus(taskId, DownloadStatus.FAILED, temp.length(), total, message)
                DownloadProgressBroadcaster.updateState(taskId, DownloadState.Error(message))
            }
            Result.failure()
        }
    }

    /** Cancellation closes the active socket even during a blocking body read. */
    private suspend fun <T> withResponse(request: Request, sourceId: String, block: suspend (Response) -> T): T {
        val origin=request.url
        val guarded=(if (sourceId == "zlibrary") client else publicClient).newBuilder().addNetworkInterceptor { chain ->
            val next=chain.request()
            val safe=if(next.url.host!=origin.host || next.url.scheme!=origin.scheme) next.newBuilder()
                .removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization").removeHeader("X-Api-Key").build() else next
            chain.proceed(safe)
        }.build()
        return guarded.newCall(request).executeCancellable().use { block(it) }
    }

    private fun isHtmlError(response: Response, format: String): Boolean {
        val sample = response.peekBody(4096).string().trimStart().lowercase()
        val html = response.header("Content-Type")?.contains("text/html", true) == true ||
            sample.startsWith("<!doctype html") || sample.startsWith("<html")
        if (!html) return false
        if (format != "txt") return true
        // Literal HTML in a TXT book is valid; only known challenge/error markers reject it.
        return listOf("diamwall", "checking your browser", "verifying your browser", "daily limit", "page not found", "solve this captcha").any { it in sample }
    }
}
