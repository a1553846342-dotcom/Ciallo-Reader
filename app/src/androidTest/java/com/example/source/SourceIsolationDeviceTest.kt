package com.example.source

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.library.LibraryUiState
import com.example.library.LibraryViewModel
import com.example.source.impl.AutoNovelSource
import com.example.source.impl.IxdzsSource
import com.example.source.impl.Wenku8LibrarySource
import com.example.source.zlibrary.ZLibrarySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SourceIsolationDeviceTest {
    private fun app() = ApplicationProvider.getApplicationContext<Application>()
    private fun <T> main(block: () -> T): T {
        var outcome: kotlin.Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { outcome = runCatching(block) }
        return outcome!!.getOrThrow()
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(20_000) {
        while (!condition()) delay(20)
    }
    private class Fake(
        override val id: String,
        override val capabilities: SourceCapabilities,
        private val respond: suspend (String) -> SourceResult<List<SearchBook>> = {
            SourceResult.Success(listOf(SearchBook("shared-id", id, "中文小说", "作者")))
        }
    ) : BookSource {
        override val name = id
        val calls = AtomicInteger()
        override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
            calls.incrementAndGet(); return respond(keyword)
        }
        override suspend fun getDetail(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun getDownloadInfo(bookId: String) = SourceResult.Error(SourceException.LoginRequired)
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
    }
    private suspend fun withViewModel(block: suspend (LibraryViewModel) -> Unit) {
        val store = ViewModelStore()
        val vm = main { LibraryViewModel(app()).also { store.put("isolation-audit", it) } }
        until { vm.sourceManager.allSources.value.any { it.id == "js_pufei" } }
        delay(100)
        val originals = vm.sourceManager.allSources.value.associateBy { it.id }
        val enabled = vm.sourceManager.enabledStates.value.toMap()
        val active = vm.currentSource.value?.id
        val multiple = vm.prefs.multiLanguageSearch
        try {
            main { vm.setMultiLanguageSearch(false) }
            originals.keys.forEach { vm.sourceManager.setSourceEnabled(it, false) }
            block(vm)
        } finally {
            vm.sourceManager.allSources.value.filter { it.id !in originals }.forEach {
                vm.sourceManager.unregisterSource(it.id)
            }
            originals.values.forEach { vm.sourceManager.registerSource(it, enabled[it.id] ?: true) }
            enabled.forEach { (id, state) -> vm.sourceManager.setSourceEnabled(id, state) }
            active?.let { vm.sourceManager.setActiveSource(it) }
            main { vm.setMultiLanguageSearch(multiple); store.clear() }
        }
    }
    private fun report(name: String, data: JSONObject) {
        File(app().filesDir, "source-isolation-$name.json").writeText(data.put("passed", true).toString())
    }

    @Test fun actualNovelSourcesAndZlibHaveExclusiveCapabilities() = runBlocking {
        val zlib = ZLibrarySource(app())
        for (source in listOf(zlib, AutoNovelSource(app()), Wenku8LibrarySource(app()), IxdzsSource(app()))) {
            assertTrue(source.id, source.isNovelSource)
            assertFalse(source.id, source.isComicSource)
        }
        assertFalse(zlib.capabilities.searchRequiresLogin)
        assertTrue(zlib.capabilities.downloadRequiresLogin)
        if (!zlib.isLoggedIn()) {
            val result = zlib.getDownloadInfo("123")
            assertTrue(result is SourceResult.Error)
            assertEquals(SourceException.LoginRequired, (result as SourceResult.Error).exception)
        }
        for (caps in listOf(SourceCapabilities(supportComic = true),
            SourceCapabilities(supportComic = true, supportEbook = true, supportOnlineText = true))) {
            val comic = Fake("isolation-comic", caps)
            assertTrue(comic.isComicSource); assertFalse(comic.isNovelSource)
        }
        val unknown = Fake("isolation-unknown", SourceCapabilities())
        assertFalse(unknown.isComicSource); assertFalse(unknown.isNovelSource)
        report("capabilities", JSONObject().put("novels", listOf("zlibrary", "auto_novel", "wenku8_library", "ixdzs8"))
            .put("zlibSearchRequiresLogin", false).put("zlibDownloadRequiresLogin", true))
    }

    @Test fun aggregateNovelSearchFiltersTypesAndIsolatesFailedSources() = runBlocking {
        withViewModel { vm ->
            val originalZlib = vm.sourceManager.getSource("zlibrary")!!
            val zlib = Fake("zlibrary", originalZlib.capabilities)
            val novel = Fake("isolation-novel", SourceCapabilities(supportEbook = true)) {
                SourceResult.Success(listOf(SearchBook("shared-id", "mangadex", "中文网文", "作者")))
            }
            val failed = Fake("isolation-failed", SourceCapabilities(supportOnlineText = true)) {
                throw java.io.IOException("fixture failure")
            }
            val comic = Fake("isolation-comic", SourceCapabilities(supportComic = true))
            val disabled = Fake("isolation-disabled", SourceCapabilities(supportEbook = true))
            val unavailable = Fake("isolation-no-search", SourceCapabilities(supportEbook = true, supportSearch = false))
            val environment = Fake("isolation-environment", SourceCapabilities(supportEbook = true, environmentOnly = true))
            listOf(zlib, novel, failed, comic, disabled, unavailable, environment).forEach {
                vm.sourceManager.registerSource(it); vm.sourceManager.setSourceEnabled(it.id, it != disabled)
            }
            main { vm.setAggregateKind("novel"); vm.aggregateSearch("中文小说") }
            until { (vm.uiState.value as? LibraryUiState.AggregateResults)?.running == false }
            val result = vm.uiState.value as LibraryUiState.AggregateResults
            assertEquals(setOf(zlib.id, novel.id, failed.id), result.groups.map { it.sourceId }.toSet())
            assertEquals(2, result.groups.sumOf { it.books.count { book -> book.id == "shared-id" } })
            assertTrue(result.groups.flatMap { group -> group.books.map { it.sourceId == group.sourceId } }.all { it })
            assertNotNull(result.groups.first { it.sourceId == failed.id }.error)
            assertEquals(1, zlib.calls.get()); assertEquals(1, novel.calls.get())
            listOf(comic, disabled, unavailable, environment).forEach { assertEquals(it.id, 0, it.calls.get()) }
            report("aggregate", JSONObject().put("sourceIds", result.groups.map { it.sourceId })
                .put("crossSourceIdsCorrect", true).put("failureDoesNotCancelOtherSources", true))
        }
    }

    @Test fun switchingAggregateCategoriesClearsAndCancelsOldResultsInBothDirections() = runBlocking {
        withViewModel { vm ->
            val comicStarted = CompletableDeferred<Unit>()
            val comicGate = CompletableDeferred<Unit>()
            val novelStarted = CompletableDeferred<Unit>()
            val novelGate = CompletableDeferred<Unit>()
            var comicCalls = 0
            val comic = Fake("isolation-comic", SourceCapabilities(supportComic = true)) {
                if (comicCalls++ == 0) { comicStarted.complete(Unit); comicGate.await() }
                SourceResult.Success(listOf(SearchBook("comic-book", "isolation-comic", "漫画", "作者")))
            }
            val novel = Fake("isolation-novel", SourceCapabilities(supportEbook = true)) {
                novelStarted.complete(Unit); novelGate.await()
                SourceResult.Success(listOf(SearchBook("novel-book", "isolation-novel", "小说", "作者")))
            }
            for (source in listOf(comic, novel)) {
                vm.sourceManager.registerSource(source); vm.sourceManager.setSourceEnabled(source.id, true)
            }
            main { vm.aggregateSearch("旧漫画") }
            withTimeout(20_000) { comicStarted.await() }
            main { vm.setAggregateKind("novel") }
            assertEquals(LibraryUiState.Empty, vm.uiState.value)
            comicGate.complete(Unit); delay(100)
            assertEquals(LibraryUiState.Empty, vm.uiState.value)
            main { vm.aggregateSearch("旧小说") }
            withTimeout(20_000) { novelStarted.await() }
            main { vm.setAggregateKind("comic"); vm.aggregateSearch("新漫画") }
            novelGate.complete(Unit)
            until { (vm.uiState.value as? LibraryUiState.AggregateResults)?.running == false }
            delay(100)
            val result = vm.uiState.value as LibraryUiState.AggregateResults
            assertEquals(listOf(comic.id), result.groups.map { it.sourceId })
            assertEquals("comic-book", result.groups.single().books.single().id)
            report("category-switch", JSONObject().put("bothDirections", true).put("oldResultsCannotReturn", true))
        }
    }

    @Test fun switchingToSingleSourceOrNoAvailableSourcesCancelsAggregation() = runBlocking {
        withViewModel { vm ->
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val novel = Fake("isolation-novel", SourceCapabilities(supportEbook = true)) {
                started.complete(Unit)
                try { delay(60_000); SourceResult.Success(emptyList()) }
                finally { cancelled.complete(Unit) }
            }
            val comic = Fake("isolation-comic", SourceCapabilities(supportComic = true)) {
                SourceResult.Success(listOf(SearchBook("comic-single", "wrong-source", "漫画", "作者")))
            }
            for (source in listOf(novel, comic)) {
                vm.sourceManager.registerSource(source); vm.sourceManager.setSourceEnabled(source.id, true)
            }
            main { vm.setAggregateKind("novel"); vm.aggregateSearch("小说") }
            withTimeout(20_000) { started.await() }
            main { vm.selectSource(comic.id) }
            withTimeout(20_000) { cancelled.await() }
            assertEquals(LibraryUiState.Empty, vm.uiState.value)
            until { vm.currentSource.value?.id == comic.id }
            main { vm.search("漫画") }
            until { vm.uiState.value is LibraryUiState.SearchResults }
            assertEquals(comic.id, (vm.uiState.value as LibraryUiState.SearchResults).results.single().sourceId)
            vm.sourceManager.setSourceEnabled(novel.id, false)
            main { vm.setAggregateMode(true); vm.aggregateSearch("无可用小说源") }
            assertTrue(vm.uiState.value is LibraryUiState.Error)
            report("single-source", JSONObject().put("aggregationCancelled", true).put("noNovelSourcesShowsError", true))
        }
    }

    @Test fun liveNovelAggregationIncludesAllFourNovelSourcesAndStreamsChineseResults() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withViewModel { vm ->
            val ids = setOf("zlibrary", "auto_novel", "wenku8_library", "ixdzs8")
            ids.forEach { vm.sourceManager.setSourceEnabled(it, true) }
            val started = SystemClock.elapsedRealtime()
            main { vm.setAggregateKind("novel"); vm.aggregateSearch("无职转生") }
            until { (vm.uiState.value as? LibraryUiState.AggregateResults)?.groups?.any {
                it.sourceId == "wenku8_library" && it.books.isNotEmpty()
            } == true }
            val firstMs = SystemClock.elapsedRealtime() - started
            withTimeout(65_000) {
                while ((vm.uiState.value as? LibraryUiState.AggregateResults)?.running != false) delay(25)
            }
            val result = vm.uiState.value as LibraryUiState.AggregateResults
            assertEquals(ids, result.groups.map { it.sourceId }.toSet())
            assertTrue(result.groups.flatMap { group -> group.books.map { it.sourceId == group.sourceId } }.all { it })
            report("live-aggregate", JSONObject().put("firstChineseResultsMs", firstMs)
                .put("allFinishedMs", SystemClock.elapsedRealtime() - started)
                .put("groups", org.json.JSONArray(result.groups.map { group ->
                    JSONObject().put("sourceId", group.sourceId).put("books", group.books.size)
                        .put("error", group.error ?: JSONObject.NULL)
                })))
        }
    }
}
