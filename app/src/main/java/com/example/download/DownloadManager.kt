package com.example.download

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private val taskMutex = kotlinx.coroutines.sync.Mutex()
        internal suspend fun <T> withControlLock(block:suspend ()->T):T = taskMutex.withLock { block() }

        /** Book ids contain slashes/Chinese (e.g. book/xxx/三体.html); sanitize for file paths. */
        fun sanitizeFileName(id: String): String {
            // Truncate to stay under filesystem filename limits (255 bytes) — long titles
            // like 球状闪电（…超长营销文案…） produce ENAMETOOLONG otherwise.
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            return id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40) + "_" + hash
        }

        fun taskId(sourceId: String, bookId: String): String = "v2:${sourceId.length}:$sourceId$bookId"

        fun originalBookId(taskId: String, sourceId: String): String =
            taskId.removePrefix("v2:${sourceId.length}:$sourceId")

    }

    private val db = AppDatabase.getDatabase(context)
    private val taskDao = db.downloadTaskDao()
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val enqueueMutex = taskMutex
    private val secrets = com.example.data.EncryptedSecretStore(context)

    val allTasksFlow: Flow<List<DownloadTaskEntity>> = taskDao.getAllTasksFlow()
    val downloadStates: StateFlow<Map<String, DownloadState>> = DownloadProgressBroadcaster.states

    init {
        restoreTasks()
    }

    /** 已完成任务对应的书是否还在书架（按文件路径匹配，file:// 前缀归一化）。 */
    private suspend fun bookExistsForTask(task: DownloadTaskEntity): Boolean =
        runCatching {
            db.bookDao().getAllBooksSync().any { it.filePath.removePrefix("file://") == task.filePath ||
                    (com.example.source.WholeBookNovelSources.contains(task.sourceId) &&
                        it.sourceId == task.sourceId && it.comicId == originalBookId(task.id, task.sourceId)) }
        }.getOrDefault(false)

    private fun restoreTasks() {
        scope.launch {
            taskMutex.withLock {
                val unfinished = taskDao.getAllTasksSync()
                Log.i(TAG, "Restoring unfinished tasks from DB. Count=${unfinished.size}")
                for (task in unfinished) {
                    if (task.status in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING)) {
                        val work = workManager.getWorkInfosForUniqueWork("download_${task.id}").get()
                        if (work.any { !it.state.isFinished }) continue
                        Log.i(TAG, "Restoring interrupted DOWNLOADING task to PAUSED: bookId=${task.id}")
                        taskDao.updateProgressAndStatus(
                            id = task.id,
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = task.downloadedBytes,
                            totalBytes = task.totalBytes,
                            errorMessage = null
                        )
                        DownloadProgressBroadcaster.updateState(
                            task.id,
                            DownloadState.Paused(task.downloadedBytes, task.totalBytes)
                        )
                    } else if (task.status == DownloadStatus.PAUSED) {
                        Log.i(TAG, "Restoring PAUSED task state in broadcaster: bookId=${task.id}")
                        DownloadProgressBroadcaster.updateState(
                            task.id,
                            DownloadState.Paused(task.downloadedBytes, task.totalBytes)
                        )
                    } else if (task.status == DownloadStatus.COMPLETED) {
                        // 书已不在书架的残留任务不能广播 Success，否则书库搜索
                        // 会一直显示「已存入书架」且无法重新下载；清掉任务行即可恢复下载。
                        if (bookExistsForTask(task)) {
                            val finalFile = File(task.filePath)
                            Log.i(TAG, "Restoring COMPLETED task state in broadcaster: bookId=${task.id}")
                            DownloadProgressBroadcaster.updateState(
                                task.id,
                                DownloadState.Success(finalFile.absolutePath)
                            )
                        } else {
                            Log.i(TAG, "Dropping stale COMPLETED task (book removed from shelf): bookId=${task.id}")
                            taskDao.deleteTaskById(task.id)
                        }
                    }
            }
            }
        }
    }


    private suspend fun resolveTaskId(id: String): String {
        if (taskDao.getTaskById(id) != null) return id
        return taskDao.getAllTasksSync().firstOrNull {
            taskId(it.sourceId, originalBookId(it.id, it.sourceId)) == id
        }?.id ?: id
    }

    fun enqueueDownload(request: DownloadRequest, referer: String? = null, headers: Map<String, String> = emptyMap()) {
        Log.i(TAG, "enqueueDownload requested for bookId=${request.bookId}, title=${request.title}")
        scope.launch {
            enqueueMutex.lock()
            try {
            val canonicalId = taskId(request.sourceId, request.bookId)
            val id = resolveTaskId(canonicalId)
            // Dedupe: don't enqueue a second task for the same book if one is already
            // pending/downloading/paused/completed. Failed tasks may be retried.
            val existing = taskDao.getTaskById(id)
            val replaceNovel = request.replaceExistingNovel && com.example.source.WholeBookNovelSources.contains(request.sourceId)
            if (existing != null && existing.status != DownloadStatus.FAILED && !(replaceNovel && existing.status == DownloadStatus.COMPLETED)) {
                if (existing.status == DownloadStatus.COMPLETED) {
                    // 书架里是否还存在这本书；若已被删除，则清掉旧任务重新下载并重新入库
                    val stillOnShelf = bookExistsForTask(existing)
                    if (stillOnShelf) {
                        DownloadProgressBroadcaster.updateState(
                            id,
                            DownloadState.Success(existing.filePath)
                        )
                        return@launch
                    }
                    Log.i(TAG, "Completed task found but book removed from shelf; re-downloading ${request.bookId}")
                    taskDao.deleteTaskById(existing.id)
                    runCatching { File(existing.filePath).delete() }
                    runCatching {
                        File(File(existing.filePath).parentFile, "${File(existing.filePath).nameWithoutExtension}.tmp").delete()
                    }
                } else {
                    Log.i(TAG, "Task already exists for bookId=${request.bookId} (status=${existing.status}); skipping duplicate")
                    DownloadProgressBroadcaster.updateState(
                        id,
                        when (existing.status) {
                            DownloadStatus.PAUSED -> DownloadState.Paused(existing.downloadedBytes, existing.totalBytes)
                            DownloadStatus.DOWNLOADING -> DownloadState.Downloading(
                                existing.downloadedBytes,
                                existing.totalBytes,
                                if (existing.totalBytes > 0) {
                                    (existing.downloadedBytes.toFloat() / existing.totalBytes).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            )
                            else -> DownloadState.Pending
                        }
                    )
                    return@launch
                }
            }

            val downloadsDir = File(context.filesDir, "downloads")
            val finalFormat = request.format.trim().lowercase()
            require(finalFormat.matches(Regex("[a-z0-9]{1,10}"))) { "下载格式无效" }
            val finalFilePath = if (replaceNovel) File(downloadsDir, "${sanitizeFileName(id)}_${System.nanoTime()}.$finalFormat").absolutePath
                else existing?.filePath ?: File(downloadsDir, "${sanitizeFileName(id)}.$finalFormat").absolutePath


            val task = DownloadTaskEntity(
                id = id,
                sourceId = request.sourceId,
                title = request.title,
                author = request.author,
                coverUrl = request.coverUrl,
                downloadUrl = request.downloadUrl,
                format = finalFormat,
                status = DownloadStatus.PENDING,
                downloadedBytes = 0L,
                totalBytes = 0L,
                filePath = finalFilePath
            )

            if (com.example.source.WholeBookNovelSources.contains(request.sourceId)) {
                request.novelSnapshot?.takeIf { it.sourceId == request.sourceId && it.id == request.bookId }?.let {
                    NovelDownloadStore(context).prepare(id, it, replaceNovel)
                }
            }
            taskDao.insertOrUpdate(task)
            DownloadProgressBroadcaster.updateState(id, DownloadState.Pending)

            try { enqueueWorker(
                bookId = id,
                url = request.downloadUrl,
                title = request.title,
                format = finalFormat,
                referer = referer,
                headers = headers
            ) } catch(e:Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                taskDao.updateProgressAndStatus(id,DownloadStatus.FAILED,0L,0L,e.message)
                DownloadProgressBroadcaster.updateState(id,DownloadState.Error(e.message ?: "后台任务启动失败"))
            }
            } finally { enqueueMutex.unlock() }
        }
    }

    fun pauseDownload(requestedId: String) {
        Log.i(TAG, "pauseDownload requested for bookId=$requestedId")
        scope.launch {
            taskMutex.withLock {
                val bookId = resolveTaskId(requestedId)
                workManager.cancelUniqueWork("download_$bookId").result.get()
                DownloadWorker.withTaskLock(bookId) {
                    val task = taskDao.getTaskById(bookId) ?: return@withTaskLock
                    if (task.status == DownloadStatus.COMPLETED) return@withTaskLock
                    val downloadsDir = File(context.filesDir, "downloads")
                    val tempFile = File(downloadsDir, "${File(task.filePath).nameWithoutExtension}.tmp")
                    val currentDownloaded = if (tempFile.exists()) tempFile.length() else task.downloadedBytes

                    Log.i(TAG, "Task paused for bookId=$bookId. Preserved temp bytes=$currentDownloaded")
                    taskDao.updateProgressAndStatus(
                        id = bookId,
                        status = DownloadStatus.PAUSED,
                        downloadedBytes = currentDownloaded,
                        totalBytes = task.totalBytes,
                        errorMessage = null
                    )
                    DownloadProgressBroadcaster.updateState(
                        bookId,
                        DownloadState.Paused(currentDownloaded, task.totalBytes)
                    )
                }
            }
        }
    }


    fun resumeDownload(requestedId: String) {
        Log.i(TAG, "resumeDownload requested for bookId=$requestedId")
        scope.launch {
            taskMutex.withLock {
                val bookId = resolveTaskId(requestedId)
                val task = taskDao.getTaskById(bookId) ?: return@withLock
                if (task.status == DownloadStatus.COMPLETED) return@withLock
                taskDao.updateProgressAndStatus(
                    id = bookId,
                    status = DownloadStatus.PENDING,
                    downloadedBytes = task.downloadedBytes,
                    totalBytes = task.totalBytes,
                    errorMessage = null
                )
                DownloadProgressBroadcaster.updateState(bookId, DownloadState.Pending)

                enqueueWorker(bookId, task.downloadUrl, task.title, task.format)
            }
        }
    }


    fun cancelDownload(requestedId: String) {
        scope.launch {
            taskMutex.withLock {
                val bookId = resolveTaskId(requestedId)
                workManager.cancelUniqueWork("download_$bookId").result.get()
                DownloadWorker.withTaskLock(bookId) {
                    val task = taskDao.getTaskById(bookId) ?: return@withTaskLock
                    if (task.status == DownloadStatus.COMPLETED) return@withTaskLock
                    val finalFile = File(task.filePath)
                    val downloadsDir = File(context.filesDir, "downloads")
                    File(downloadsDir, "${finalFile.nameWithoutExtension}.tmp").delete()
                    File(downloadsDir, "${finalFile.nameWithoutExtension}.resume").delete()
                    finalFile.delete()
                    taskDao.deleteTaskById(bookId)
                    secrets.remove("download:$bookId")
                    DownloadProgressBroadcaster.removeState(bookId)
                }
            }
        }
    }

    private fun enqueueWorker(
        bookId: String,
        url: String,
        title: String,
        format: String,
        referer: String? = null,
        headers: Map<String,String>? = null
    ) {
        if(headers!=null) {
            val values=org.json.JSONObject(headers)
            if(!referer.isNullOrBlank()) values.put("Referer",referer)
            val raw=values.toString()
            require(raw.length<=32*1024) { "下载请求头过大" }
            if(values.length()>0) secrets.write("download:$bookId",raw) else secrets.remove("download:$bookId")
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            "book_id" to bookId,
            "url" to url,
            "title" to title,
            "format" to format
        )

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        Log.i(TAG, "Enqueuing WorkManager task download_$bookId")
        workManager.enqueueUniqueWork(
            "download_$bookId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        ).result.get()
    }

    fun getDownloadState(bookId: String): DownloadState {
        return DownloadProgressBroadcaster.getState(bookId)
    }
}
