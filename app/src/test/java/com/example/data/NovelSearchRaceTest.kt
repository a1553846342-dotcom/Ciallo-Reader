package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import com.example.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * 竞态防护的 ViewModel 层验证（真实 searchFullText 协程链）：
 * 输入过程"插"→"插图"连续两次调用，旧查询必须被取消 —— 最终结果列表
 * 只含"插图"的完整匹配，不得混入"插"独现的条目（用户实测的污染场景）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelSearchRaceTest {

    private lateinit var app: Application
    private lateinit var database: AppDatabase
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        ShadowLog.stream = System.out
        Dispatchers.setMain(mainDispatcher)
        app = ApplicationProvider.getApplicationContext()
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        database = db
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        val bookId = db.bookDao().insertBook(
            Book(title = "竞态测试书", filePath = "test://race", totalChapters = 0)
        ).toInt()
        db.bookDao().insertChapters(
            listOf(
                Chapter(bookId = bookId, chapterOrder = 0, title = "卷二", content = "他说：「这里有插图。」然后翻页。"),
                Chapter(bookId = bookId, chapterOrder = 1, title = "卷三", content = "第一处插图在前，第二处插图在后。"),
                Chapter(bookId = bookId, chapterOrder = 2, title = "卷三 (续1)", content = "续页里还有一处插图。"),
                Chapter(bookId = bookId, chapterOrder = 3, title = "卷四", content = "这里只有插字。"),
            )
        )
        val vm = MainViewModel(app)
        vm.selectBook(db.bookDao().getBookById(bookId)!!)
        awaitUi(20_000) { vm.chapters.value.isNotEmpty() }
        this@NovelSearchRaceTest.vm = vm
    }

    private lateinit var vm: MainViewModel

    @After
    fun tearDown() = kotlinx.coroutines.runBlocking {
        if (this@NovelSearchRaceTest::vm.isInitialized) {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
        database.close()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        Dispatchers.resetMain()
    }

    private fun awaitUi(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(100)
        assertTrue("等待条件超时", cond())
    }

    @Test
    fun shorterQueryRaceDoesNotPolluteResults() {
        // 用户输入过程：先"插"，立刻改搜"插图" —— 旧查询必须被取消
        vm.searchFullText("插")
        vm.searchFullText("插图")

        awaitUi(20_000) { !vm.isSearching.value }
        val results = vm.searchResults.value

        assertTrue("应只有『插图』的 4 条结果，实际 ${results.size}", results.size == 4)
        assertTrue(
            "竞态污染：混入了『插』独现的条目",
            results.none { it.snippet.contains("这里只有插字") }
        )
        results.forEach { r ->
            val inner = r.snippet.trimStart('.').trimEnd('.')
            assertTrue("snippet 未含完整关键词: ${r.snippet}", inner.contains("插图"))
        }
        // occurrence 与渲染端定位一致性（同 SearchLocator 消费方式）
        val merged = "第一处插图在前，第二处插图在后。" + "续页里还有一处插图。"
        results.filter { it.chapterIndex == 1 }.forEach { r ->
            val pos = SearchLocator.nthOccurrence(merged, "插图", r.occurrence)
            assertTrue("occurrence=${r.occurrence} 定位失败", pos >= 0)
            assertTrue(
                "定位处上下文异常: '${merged.substring(pos, pos + 2)}'",
                merged.substring(pos, pos + 2) == "插图"
            )
        }
        // 同一真实 Room 会话中验证切章正文读取不依赖进度写库。
        val bookId = vm.selectedBook.value!!.id
        vm.ensureActiveChapter(bookId, 2)
        awaitUi(20_000) { 2 in vm.loadedChapterIndices.value }
        assertEquals("这里只有插字。", vm.chapters.value[2].content)
        assertFalse(vm.readerLoading.value)
    }
}
