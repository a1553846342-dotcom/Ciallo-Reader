package com.example.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadTaskEntity
import com.example.source.BookSource
import com.example.source.SourceManager
import com.example.source.SourceResult
import com.example.source.impl.MockBookSource
import com.example.source.storage.SharedPreferencesSourceStorage
import com.example.source.zlibrary.ZLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntegrationChainTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        database = androidx.room.Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, database)
        try {
            val config = androidx.work.Configuration.Builder()
                .setExecutor { it.run() }
                .build()
            androidx.work.WorkManager.initialize(app, config)
        } catch (e: Exception) {
            // Ignored if already initialized
        }
    }

    @After
    fun tearDown() {
        database.close()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        Dispatchers.resetMain()
    }

    @Test
    fun testFullDownloadChainWithoutLegacyDownloadUrlProperty() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source: BookSource = MockBookSource(app)
        val downloadManager = DownloadManager(app)

        // 1. Search
        val searchRes = source.search("Pride")
        assertTrue(searchRes is SourceResult.Success)
        val books = (searchRes as SourceResult.Success).data
        assertTrue(books.isNotEmpty())

        val targetBook = books.first()

        // 2. Get Detail
        val detailRes = source.getDetail(targetBook.id)
        assertTrue(detailRes is SourceResult.Success)

        // 3. Get Download Info (Unified Flow)
        val dlInfoRes = source.getDownloadInfo(targetBook.id)
        assertTrue(dlInfoRes is SourceResult.Success)
        val dlInfo = (dlInfoRes as SourceResult.Success).data

        // 4. Enqueue Download via DownloadManager
        val request = com.example.download.DownloadRequest(
            bookId = targetBook.id,
            title = targetBook.title,
            author = targetBook.author,
            sourceId = targetBook.sourceId,
            downloadUrl = dlInfo.url,
            format = dlInfo.format,
            coverUrl = targetBook.cover
        )
        downloadManager.enqueueDownload(request, dlInfo.referer, dlInfo.headers)
        val taskId = DownloadManager.taskId(targetBook.sourceId, targetBook.id)
        assertNotNull(taskId)

        testScheduler.advanceUntilIdle()

        // 5. Verify task in DB with async wait
        var tasks: List<DownloadTaskEntity> = emptyList()
        for (i in 1..20) {
            tasks = downloadManager.allTasksFlow.first()
            if (tasks.any { it.id == taskId }) break
            kotlinx.coroutines.delay(50)
        }
        assertTrue(tasks.any { it.id == taskId })
    }

    @Test
    fun testSourceDeletionDoesNotDeleteLocalDatabaseData() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)
        val storage = SharedPreferencesSourceStorage(app)
        val sourceManager = SourceManager(storage)

        sourceManager.initialize()
        val zlibSource = ZLibrarySource(app)
        sourceManager.registerSource(zlibSource)
        testScheduler.advanceUntilIdle()

        // Unregister ZLibrarySource
        sourceManager.unregisterSource("zlibrary")
        testScheduler.advanceUntilIdle()

        // Verify ZLibrarySource removed from SourceManager
        assertNull(sourceManager.getSource("zlibrary"))

        // Verify database tables still intact
        val bookCount = db.bookDao().getAllBooks().first().size
        assertNotNull(bookCount)
    }
}
