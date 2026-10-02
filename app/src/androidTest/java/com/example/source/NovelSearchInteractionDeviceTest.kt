package com.example.source

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.library.LibraryScreen
import com.example.library.LibraryUiState
import com.example.library.LibraryViewModel
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real aggregate screen, rather than a card with a test-only click handler. */
@RunWith(AndroidJUnit4::class)
class NovelSearchInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun zlibCardOpensSynopsisAndOnlyExplicitDownloadRequestsAFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        lateinit var vm: LibraryViewModel
        compose.runOnUiThread { vm = LibraryViewModel(app); store.put("novel-interaction", vm) }
        compose.waitUntil(25_000) { vm.sourceManager.allSources.value.any { it.id == "js_pufei" } }
        val originals = vm.sourceManager.allSources.value.associateBy { it.id }
        val enabled = vm.sourceManager.enabledStates.value.toMap()
        val active = vm.currentSource.value?.id
        val multiLanguage = vm.prefs.multiLanguageSearch
        val aggregateKind = vm.aggregateKind.value
        val aggregateMode = vm.aggregateMode.value
        val details = AtomicInteger()
        val downloads = AtomicInteger()
        val book = SearchBook("interaction-fixture", "zlibrary", "小说点击流程测试", "测试作者", format = "epub")
        val source = object : BookSource {
            override val id = "zlibrary"
            override val name = "Z-Library"
            override val capabilities = originals.getValue(id).capabilities
            override suspend fun search(keyword: String) = SourceResult.Success(listOf(book))
            override suspend fun getDetail(bookId: String): SourceResult<SearchBook> {
                details.incrementAndGet()
                return SourceResult.Success(book.copy(description = "这是一段小说简介，不会触发文件下载。"))
            }
            override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> {
                downloads.incrementAndGet()
                return SourceResult.Error(SourceException.NetworkError("测试下载已记录"))
            }
            override suspend fun isLoggedIn() = true
            override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
            override suspend fun logout() = Unit
        }
        try {
            originals.keys.forEach { vm.sourceManager.setSourceEnabled(it, false) }
            vm.sourceManager.registerSource(source)
            vm.sourceManager.setSourceEnabled(source.id, true)
            compose.runOnUiThread {
                vm.setMultiLanguageSearch(false); vm.setAggregateMode(true); vm.setAggregateKind("novel")
                vm.aggregateSearch("小说点击流程测试")
            }
            compose.waitUntil(10_000) { (vm.uiState.value as? LibraryUiState.AggregateResults)?.running == false }
            compose.setContent { MyApplicationTheme { LibraryScreen(vm, onBookImported = {}) } }
            compose.onNodeWithContentDescription("加入我喜欢的").assertDoesNotExist()
            compose.onNodeWithText(book.title).performClick()
            compose.waitUntil(10_000) { details.get() == 1 }
            compose.onNodeWithText("作品简介").assertIsDisplayed()
            compose.onNodeWithText("这是一段小说简介，不会触发文件下载。").assertIsDisplayed()
            assertEquals("Opening a Z-Library card must not fetch download information", 0, downloads.get())
            compose.onNodeWithContentDescription("加入我喜欢的").assertDoesNotExist()
            compose.onNodeWithText("整本下载到书架").performClick()
            compose.waitUntil(10_000) { downloads.get() == 1 }
            assertEquals(1, details.get())
        } finally {
            compose.runOnUiThread { compose.activity.setContent { } }
            originals.values.forEach { vm.sourceManager.registerSource(it, enabled[it.id] ?: true) }
            enabled.forEach { (id, state) -> vm.sourceManager.setSourceEnabled(id, state) }
            active?.let { vm.sourceManager.setActiveSource(it) }
            compose.runOnUiThread {
                vm.setMultiLanguageSearch(multiLanguage); vm.setAggregateKind(aggregateKind)
                vm.setAggregateMode(aggregateMode); store.clear()
            }
        }
    }
}
