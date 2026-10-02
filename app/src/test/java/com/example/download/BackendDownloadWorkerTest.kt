package com.example.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.data.AppDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Real sockets + real Room + the actual worker, without contacting book sites. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackendDownloadWorkerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private val requests = AtomicInteger()
    private var status = 200
    private var body = "第一章 测试\n下载后的正文。".toByteArray()
    private var responseRange: String? = null
    private var requestedRange: String? = null
    private var requestedValidator: String? = null
    private var stall: CountDownLatch? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverThread = thread(isDaemon = true, name = "download-test-http") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                    }
                    requests.incrementAndGet()
                    requestedRange = headers["range"]
                    requestedValidator = headers["if-range"]
                    val responseBody = body
                    val header = buildString {
                        append("HTTP/1.1 $status Test\r\nContent-Type: application/octet-stream\r\n")
                        append("ETag: \"v1\"\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n")
                        responseRange?.let { append("Content-Range: $it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(header.toByteArray(Charsets.ISO_8859_1))
                        val gate = stall
                        if (gate == null) write(responseBody) else {
                            write(responseBody, 0, 8192)
                            flush()
                            gate.await(10, TimeUnit.SECONDS)
                        }
                        flush()
                    }
                }
            }
        }
    }

    @After fun tearDown() {
        stall?.countDown()
        server.close()
        serverThread.join(5000)
        db.close()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
    }

    private suspend fun task(format: String = "txt"): DownloadTaskEntity {
        val id = DownloadManager.taskId("test-source", UUID.randomUUID().toString())
        val file = File(context.filesDir, "downloads/${DownloadManager.sanitizeFileName(id)}.$format")
        file.parentFile!!.mkdirs()
        val task = DownloadTaskEntity(id, "test-source", "网络测试", "作者", null,
            "http://127.0.0.1:${server.localPort}/book", format, DownloadStatus.PENDING, filePath = file.absolutePath)
        db.downloadTaskDao().insertOrUpdate(task)
        return task
    }

    private fun worker(task: DownloadTaskEntity): DownloadWorker {
        val direct = Executor { it.run() }
        fun completed() = SettableFuture.create<Void>().apply { set(null) }
        val parameters = WorkerParameters(UUID.randomUUID(), workDataOf("book_id" to task.id, "title" to task.title),
            emptyList(), WorkerParameters.RuntimeExtras(), 0, 0, direct, WorkManagerTaskExecutor(direct),
            WorkerFactory.getDefaultWorkerFactory(), ProgressUpdater { _, _, _ -> completed() },
            ForegroundUpdater { _, _, _ -> completed() })
        return DownloadWorker(context, parameters)
    }

    @Test fun completedTxtIsImportedRetainedAndLinkedToSource() = runBlocking {
        val task = task()
        assertEquals(ListenableWorker.Result.success(), worker(task).doWork())
        assertEquals(DownloadStatus.COMPLETED, db.downloadTaskDao().getTaskById(task.id)!!.status)
        assertTrue(File(task.filePath).isFile)
        val book = db.bookDao().getAllBooksSync().single()
        assertEquals(task.sourceId, book.sourceId)
        assertEquals(DownloadManager.originalBookId(task.id, task.sourceId), book.comicId)
        assertTrue(db.bookDao().getChaptersListForBook(book.id).single().content.contains("正文"))
    }

    @Test fun completedComicImportRetryReusesSourceIdentity() = runBlocking {
        val task = task("cbz")
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("1.jpg"))
            zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        body = bytes.toByteArray()
        assertEquals(ListenableWorker.Result.success(), worker(task).doWork())
        db.downloadTaskDao().updateProgressAndStatus(task.id, DownloadStatus.FAILED, body.size.toLong(), body.size.toLong(), "模拟完成记账前中断")
        assertEquals(ListenableWorker.Result.success(), worker(task).doWork())
        assertEquals(1, requests.get())
        assertEquals(1, db.bookDao().getBooksCount())
    }

    @Test fun validatedRangeAppendsOnlyMatchingTail() = runBlocking {
        val task = task()
        val all = body
        val offset = 12
        val file = File(task.filePath)
        File(file.parentFile, "${file.nameWithoutExtension}.tmp").writeBytes(all.copyOfRange(0, offset))
        File(file.parentFile, "${file.nameWithoutExtension}.resume").writeText(JSONObject()
            .put("url", task.downloadUrl).put("validator", "\"v1\"").put("total", all.size).toString())
        status = 206
        responseRange = "bytes $offset-${all.lastIndex}/${all.size}"
        body = all.copyOfRange(offset, all.size)
        assertEquals(ListenableWorker.Result.success(), worker(task).doWork())
        assertEquals("bytes=$offset-", requestedRange)
        assertEquals("\"v1\"", requestedValidator)
        assertArrayEquals(all, File(task.filePath).readBytes())
    }

    @Test fun mismatchedRangeFailsWithoutAppending() = runBlocking {
        val task = task()
        val file = File(task.filePath)
        val prefix = "已有内容".toByteArray()
        val temp = File(file.parentFile, "${file.nameWithoutExtension}.tmp").apply { writeBytes(prefix) }
        File(file.parentFile, "${file.nameWithoutExtension}.resume").writeText(JSONObject()
            .put("url", task.downloadUrl).put("validator", "\"v1\"").toString())
        status = 206
        responseRange = "bytes 0-${body.lastIndex}/${body.size}"
        assertEquals(ListenableWorker.Result.failure(), worker(task).doWork())
        assertEquals(DownloadStatus.FAILED, db.downloadTaskDao().getTaskById(task.id)!!.status)
        assertArrayEquals(prefix, temp.readBytes())
        assertEquals(0, db.bookDao().getBooksCount())
    }

    @Test fun failedImportNeverBroadcastsSuccessAndDoesNotLeaveHalfBook() = runBlocking {
        val task = task("epub")
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write("<container/>".toByteArray()); zip.closeEntry()
        }
        body = bytes.toByteArray()
        assertEquals(ListenableWorker.Result.failure(), worker(task).doWork())
        assertEquals(DownloadStatus.FAILED, db.downloadTaskDao().getTaskById(task.id)!!.status)
        assertTrue(DownloadProgressBroadcaster.getState(task.id) is DownloadState.Error)
        assertEquals(0, db.bookDao().getBooksCount())
        assertTrue(File(task.filePath).isFile)
        // Retry the import from the already downloaded bytes, without another HTTP request.
        worker(task).doWork()
        assertEquals(1, requests.get())
    }

    @Test fun cancellationClosesBlockedReadAndPreservesPartialFile() = runBlocking {
        val task = task()
        stall = CountDownLatch(1)
        body = ByteArray(128 * 1024) { 'a'.code.toByte() }
        try {
            val job = launch(Dispatchers.IO) { worker(task).doWork() }
            val temp = File(File(task.filePath).parentFile, "${File(task.filePath).nameWithoutExtension}.tmp")
            withTimeout(15_000) { while (temp.length() == 0L) delay(10) }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertEquals(DownloadStatus.PAUSED, db.downloadTaskDao().getTaskById(task.id)!!.status)
            assertEquals(8192L, temp.length())
            assertEquals(0, db.bookDao().getBooksCount())
        } finally { stall!!.countDown() }
    }

    @Test fun errorPageDoesNotRetryAndConsumeMoreQuota() = runBlocking {
        val task = task()
        body = "<html>daily limit - diamwall</html>".toByteArray()
        assertEquals(ListenableWorker.Result.failure(), worker(task).doWork())
        assertEquals(1, requests.get())
        assertEquals(0, db.bookDao().getBooksCount())
    }
}
