package com.example.library

import com.example.source.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComicAggregateSearchTest {
    private class FakeSource(
        override val id: String,
        private val searchBlock: suspend (String) -> SourceResult<List<SearchBook>>,
    ) : BookSource {
        override val name = id
        override val capabilities = SourceCapabilities(supportComic = true)
        override suspend fun search(keyword: String) = searchBlock(keyword)
        override suspend fun getDetail(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun getDownloadInfo(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
    }

    private fun TestScope.runner(slots: Semaphore = Semaphore(8), requestTimeoutMs: Long = 20_000) =
        ComicAggregateSearch(StandardTestDispatcher(testScheduler), slots, requestTimeoutMs,
            SourceSearchCoordinator(spacingMs = 0, now = { currentTime }))

    private fun book(id: String, title: String = id) = SearchBook(id, "adapter", title, "")
    private fun group(id: String, books: List<SearchBook> = emptyList(), loading: Boolean = true) =
        LibraryUiState.AggregateGroup(id, id, books, null, loading)

    @Test
    fun originalHitIsPublishedBeforeSlowTitleLookupAndAliasFinish() = runTest {
        val updates = mutableListOf<Pair<Long, LibraryUiState.AggregateGroup>>()
        val source = FakeSource("fast") { keyword ->
            delay(if (keyword == "original") 100 else 5_000)
            SourceResult.Success(listOf(book(keyword)))
        }
        val job = launch {
            runner().search(
                listOf(source), "original", { delay(3_000); listOf(it, "alias") },
            ) { updates += currentTime to it }
        }
        advanceTimeBy(101)
        runCurrent()
        assertEquals(100L, updates.first().first)
        assertEquals(listOf("original"), updates.first().second.books.map { it.id })
        assertTrue(updates.first().second.loading)
        assertTrue(job.isActive)
        advanceUntilIdle()
        assertEquals(listOf("original", "alias"), updates.last().second.books.map { it.id })
        assertFalse(updates.last().second.loading)
    }

    @Test
    fun fourSlowSourcesDoNotDelayTheFifthFastSource() = runTest {
        var active = 0
        var peak = 0
        val sources = (0 until 10).map { index ->
            FakeSource("source$index") {
                active++
                peak = maxOf(peak, active)
                try {
                    delay(if (index < 4) 20_000 else 100)
                    SourceResult.Success(listOf(book("hit$index")))
                } finally {
                    active--
                }
            }
        }
        val hits = mutableListOf<Pair<Long, String>>()
        launch {
            runner().search(sources, "query", { listOf(it) }) {
                if (it.books.isNotEmpty()) hits += currentTime to it.sourceId
            }
        }
        advanceTimeBy(101)
        runCurrent()
        assertTrue(hits.contains(100L to "source4"))
        assertEquals(8, peak)
        advanceUntilIdle()
        assertEquals(0, active)
    }

    @Test
    fun allOriginalsGetPermitsBeforeAliasesAndEachSourceStaysSequential() = runTest {
        val started = mutableListOf<String>()
        val activeSources = mutableSetOf<String>()
        var peak = 0
        val sources = (0 until 6).map { index ->
            val id = "source$index"
            FakeSource(id) { keyword ->
                assertTrue("Same source called concurrently", activeSources.add(id))
                peak = maxOf(peak, activeSources.size)
                started += keyword
                try {
                    delay(100)
                    SourceResult.Success(listOf(book(keyword)))
                } finally {
                    activeSources.remove(id)
                }
            }
        }
        runner( Semaphore(2)).search(
            sources, "original", { listOf(it, "alias", "alias", "") }, {},
        )
        assertEquals(List(6) { "original" } + List(6) { "alias" }, started)
        assertEquals(2, peak)
    }

    @Test
    fun aliasResultsAreCompleteAndDeduplicatedOnlyWithinTheirOwnSource() = runTest {
        val final = mutableMapOf<String, LibraryUiState.AggregateGroup>()
        val sources = listOf("a", "b").map { id ->
            FakeSource(id) { keyword ->
                SourceResult.Success(listOf(book("shared", keyword), book(keyword)))
            }
        }
        runner().search(
            sources, "original", { listOf(it, "english", "native") },
        ) { final[it.sourceId] = it }
        for (id in listOf("a", "b")) {
            assertEquals(listOf("shared", "original", "english", "native"), final.getValue(id).books.map { it.id })
            assertTrue(final.getValue(id).books.all { it.sourceId == id })
            assertEquals("original", final.getValue(id).books.first().title)
            assertFalse(final.getValue(id).loading)
        }
    }

    @Test
    fun cancellationReleasesSharedSlotsAndStopsOldQueryUpdates() = runTest {
        val updates = mutableListOf<LibraryUiState.AggregateGroup>()
        var oldRequestCancelled = false
        val source = FakeSource("same") { keyword ->
            if (keyword == "old") {
                try {
                    delay(20_000)
                } finally {
                    oldRequestCancelled = true
                }
            } else delay(10)
            SourceResult.Success(listOf(book(keyword)))
        }
        val runner = runner( Semaphore(1))
        val old = launch { runner.search(listOf(source), "old", { delay(30_000); listOf(it) }, updates::add) }
        runCurrent()
        old.cancelAndJoin()
        assertTrue(oldRequestCancelled)
        assertTrue(updates.isEmpty())
        runner.search(listOf(source), "new", { listOf(it) }, updates::add)
        assertEquals(10L, currentTime)
        assertTrue(updates.all { it.books.single().id == "new" })
        assertFalse(updates.last().loading)
    }

    @Test
    fun timeoutExceptionAndEmptySuccessAreIndependentAndFinish() = runTest {
        val updates = mutableMapOf<String, LibraryUiState.AggregateGroup>()
        val sources = listOf(
            FakeSource("timeout") { delay(1_000); SourceResult.Success(emptyList()) },
            FakeSource("exception") { throw IllegalStateException("broken") },
            FakeSource("empty") { SourceResult.Success(emptyList()) },
            FakeSource("hit") { delay(10); SourceResult.Success(listOf(book("hit"))) },
        )
        runner( requestTimeoutMs = 100).search(
            sources, "original", { listOf(it) },
        ) { updates[it.sourceId] = it }
        assertEquals("搜索超时", updates.getValue("timeout").error)
        assertEquals("broken", updates.getValue("exception").error)
        assertNull(updates.getValue("empty").error)
        assertEquals("hit", updates.getValue("hit").books.single().id)
        assertTrue(updates.values.none { it.loading })
    }

    @Test
    fun failingTitleLookupFallsBackToOriginalSearch() = runTest {
        val calls = mutableListOf<String>()
        val updates = mutableListOf<LibraryUiState.AggregateGroup>()
        val source = FakeSource("a") {
            calls += it
            SourceResult.Success(listOf(book(it)))
        }
        runner().search(
            listOf(source), "original", { throw IllegalStateException("title database unavailable") }, updates::add,
        )
        assertEquals(listOf("original"), calls)
        assertFalse(updates.last().loading)
        assertNull(updates.last().error)
    }

    @Test
    fun aliasErrorCannotEraseAnAlreadyPublishedHit() = runTest {
        val updates = mutableListOf<LibraryUiState.AggregateGroup>()
        val source = FakeSource("a") {
            if (it == "original") SourceResult.Success(listOf(book("hit")))
            else SourceResult.Error(SourceException.NetworkError("offline"))
        }
        runner().search(
            listOf(source), "original", { listOf(it, "alias") }, updates::add,
        )
        assertEquals("hit", updates.last().books.single().id)
        assertNull(updates.last().error)
        assertFalse(updates.last().loading)
    }

    @Test
    fun resultGroupsStayInFirstHitOrderAheadOfPendingSources() {
        var state = LibraryUiState.AggregateResults(listOf(group("a"), group("b"), group("c")), true)
        state = state.withComicSearchGroup(group("c", listOf(book("c"))))
        assertEquals(listOf("c", "a", "b"), state.groups.map { it.sourceId })
        state = state.withComicSearchGroup(group("a", listOf(book("a"))))
        state = state.withComicSearchGroup(group("c", listOf(book("c"), book("alias")), false))
        assertEquals(listOf("c", "a", "b"), state.groups.map { it.sourceId })
        assertTrue(state.running)
        state = state.withComicSearchGroup(group("a", listOf(book("a")), false))
        state = state.withComicSearchGroup(group("b", loading = false))
        assertFalse(state.running)
    }

    @Test
    fun jumpIndexCountsVisibleBooksWhileAliasesAreStillLoading() {
        assertEquals(4, aggregateGroupItemCount(group("pending")))
        assertEquals(1, aggregateGroupItemCount(group("partial", listOf(book("1")))))
        val many = group("many", (1..10).map { book(it.toString()) })
        assertEquals(7, aggregateGroupItemCount(many)) // Six preview cards plus expand button.
        assertEquals(10, aggregateGroupItemCount(many, expanded = true))
        assertEquals(1, aggregateGroupItemCount(group("empty", loading = false)))
    }
}
