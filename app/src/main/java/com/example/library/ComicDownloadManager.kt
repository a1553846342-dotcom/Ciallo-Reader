package com.example.library

import android.app.Application
import android.content.Context
import androidx.work.*
import com.example.data.AppDatabase
import com.example.download.DownloadWorker
import com.example.source.*
import com.example.source.storage.SharedPreferencesSourceStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ComicDownloadStatus { DOWNLOADING, PAUSED, FAILED, SUCCESS }
data class ComicDownloadTask(val chapterId: String, val book: SearchBook, val chapter: ComicChapter,
    val status: ComicDownloadStatus, val progress: Float = 0f, val error: String? = null)

/** Durable descriptors + unique WorkManager jobs, with source-qualified identities. */
object ComicDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> _message.value = e.message ?: "下载任务操作失败" })
    private val operations = Mutex()
    private val stateLock = Any()
    @Volatile private var app: Context? = null
    private val _tasks = MutableStateFlow<Map<String, ComicDownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, ComicDownloadTask>> = _tasks.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val sources = ConcurrentHashMap<String, ComicSource>()
    private val lastSave = ConcurrentHashMap<String, Long>()
    fun taskId(book: SearchBook, chapterId: String): String = "comic:${book.sourceId.length}:${book.sourceId}${book.id.length}:${book.id}$chapterId"
    private fun workName(id: String) = "comic_download_$id"
    internal fun directory(context: Context, id: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "comics_$hash")
    }
    fun initialize(context: Context) { load(context.applicationContext) }
    private fun load(context: Context) = synchronized(stateLock) {
        if (app != null) return@synchronized
        app = context
        val f = android.util.AtomicFile(File(context.filesDir, "comic_download_tasks.json"))
        val restored = runCatching {
            if (!f.baseFile.exists()) emptyMap() else {
                require(f.baseFile.length() <= 4 * 1024 * 1024)
                val a = JSONArray(f.openRead().bufferedReader().use { it.readText() })
                (0 until a.length()).associate { i ->
                    val o = a.getJSONObject(i)
                    val b = o.getJSONObject("book")
                    val c = o.getJSONObject("chapter")
                    val book = SearchBook(id=b.getString("id"), sourceId=b.getString("sourceId"), title=b.getString("title"),
                        author=b.optString("author"), cover=b.optString("cover").takeIf { it.isNotBlank() }, format=b.optString("format", "comic"))
                    val chapter = ComicChapter(c.getString("id"), c.getString("title"), c.optString("volume").takeIf { it.isNotBlank() }, c.optDouble("order",0.0).toFloat())
                    taskId(book, chapter.id) to ComicDownloadTask(chapter.id,book,chapter,
                        ComicDownloadStatus.valueOf(o.getString("status")),o.optDouble("progress",0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f,o.optString("error").takeIf { it.isNotBlank() })
                }
            }
        }.getOrElse { _message.value = "下载任务记录损坏，请重新添加任务"; emptyMap() }
        _tasks.value = restored
        scope.launch { operations.withLock {
            val work = WorkManager.getInstance(context)
            for ((id, task) in restored) {
                if (task.status != ComicDownloadStatus.DOWNLOADING || _tasks.value[id]?.status != ComicDownloadStatus.DOWNLOADING) continue
                try {
                    if (work.getWorkInfosForUniqueWork(workName(id)).get().none { !it.state.isFinished }) enqueue(work, id)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(id) { it.copy(status = ComicDownloadStatus.PAUSED, error = "后台任务恢复失败，请手动继续") }
                }
            }
        } }
    }
    private fun persist() {
        val context = app ?: return
        val a = JSONArray()
        _tasks.value.values.forEach { t ->
            a.put(JSONObject().put("book",JSONObject().put("id",t.book.id).put("sourceId",t.book.sourceId).put("title",t.book.title)
                .put("author",t.book.author).put("cover",t.book.cover ?: "").put("format",t.book.format))
                .put("chapter",JSONObject().put("id",t.chapter.id).put("title",t.chapter.title).put("volume",t.chapter.volume ?: "").put("order",t.chapter.order))
                .put("status",t.status.name).put("progress",t.progress).put("error",t.error ?: ""))
        }
        val atomic = android.util.AtomicFile(File(context.filesDir,"comic_download_tasks.json"))
        val out = atomic.startWrite()
        try { out.write(a.toString().toByteArray()); atomic.finishWrite(out) }
        catch(e:Exception) { atomic.failWrite(out); throw e }
    }
    internal fun task(context: Context,id:String): ComicDownloadTask? { load(context); return _tasks.value[id] }
    internal fun update(id:String, immediate:Boolean=true, transform:(ComicDownloadTask)->ComicDownloadTask) = synchronized(stateLock) {
        val old = _tasks.value[id] ?: return@synchronized
        if (old.status != ComicDownloadStatus.DOWNLOADING) return@synchronized
        _tasks.value = _tasks.value + (id to transform(old))
        val now = android.os.SystemClock.elapsedRealtime()
        if (immediate || now - (lastSave[id] ?: 0) >= 2000) { persist(); lastSave[id]=now }
    }
    fun start(app: Application,database:AppDatabase,book:SearchBook,chapter:ComicChapter,source:ComicSource) {
        load(app); sources[book.sourceId]=source
        scope.launch { operations.withLock {
            val id=taskId(book,chapter.id)
            val work=WorkManager.getInstance(app)
            if (work.getWorkInfosForUniqueWork(workName(id)).get().any { !it.state.isFinished }) return@withLock
            synchronized(stateLock) {
                _tasks.value = _tasks.value + (id to ComicDownloadTask(chapter.id,book,chapter,ComicDownloadStatus.DOWNLOADING,_tasks.value[id]?.progress ?: 0f))
                persist()
            }
            enqueue(work, id)
        } }
    }
    private fun enqueue(work: WorkManager, id: String) {
        val request = OneTimeWorkRequestBuilder<ComicDownloadWorker>()
            .setInputData(workDataOf("task_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request).result.get()
    }
    private fun resolve(chapterId:String, book:SearchBook?):String? = book?.let { taskId(it,chapterId) }
        ?: _tasks.value.entries.singleOrNull { it.value.chapterId==chapterId }?.key
    fun pause(chapterId:String,book:SearchBook?=null) { scope.launch { operations.withLock {
        val id=resolve(chapterId,book) ?: return@withLock
        val context=app ?: return@withLock
        synchronized(stateLock) {
            val old=_tasks.value[id] ?: return@synchronized
            if (old.status==ComicDownloadStatus.DOWNLOADING) { _tasks.value=_tasks.value+(id to old.copy(status=ComicDownloadStatus.PAUSED)); persist() }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
        DownloadWorker.withTaskLock(id) { }
    } } }
    fun cancel(chapterId:String,book:SearchBook?=null) { scope.launch { operations.withLock {
        val id=resolve(chapterId,book) ?: return@withLock
        val context=app ?: return@withLock
        pauseState(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
        DownloadWorker.withTaskLock(id) {
            val owned=directory(context,id)
            val registered=AppDatabase.getDatabase(context).bookDao().getBookByFilePath(owned.absolutePath)
            synchronized(stateLock) { _tasks.value=_tasks.value-id; persist() }
            if (registered==null) owned.deleteRecursively()
        }
    } } }
    private fun pauseState(id:String) = synchronized(stateLock) {
        _tasks.value[id]?.let { _tasks.value=_tasks.value+(id to it.copy(status=ComicDownloadStatus.PAUSED)); persist() }
    }
    internal suspend fun source(context:Context,id:String):ComicSource? {
        sources[id]?.let { return it }
        val manager=SourceManager(SharedPreferencesSourceStorage(context))
        manager.initialize()
        manager.registerSource(com.example.source.impl.MangaDexSource(context=context))
        com.example.source.js.JsSourceRepo.loadCached(context,includeAdult=true).forEach { manager.registerSource(it) }
        return (manager.getSource(id) as? ComicSource)?.also { sources[id]=it }
    }
    internal fun message(text:String) { _message.value=text }
    internal suspend fun <T> withControlLock(block:suspend ()->T):T = operations.withLock { block() }
    internal suspend fun pauseAllLocked(context:Context) {
        load(context)
        for((id,task) in _tasks.value.toMap()) if(task.status==ComicDownloadStatus.DOWNLOADING) {
            pauseState(id)
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
            DownloadWorker.withTaskLock(id) { }
        }
    }
    internal suspend fun removeForDirectoryLocked(context:Context,path:String) {
        load(context)
        val ids=_tasks.value.keys.filter { directory(context,it).canonicalPath==path }
        for(id in ids) {
            pauseState(id)
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
            DownloadWorker.withTaskLock(id) { }
            synchronized(stateLock) { _tasks.value=_tasks.value-id; persist() }
        }
    }
    fun clearMessage() { _message.value=null }
}
