package com.example.library

import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchCoordinatorTest {
    private class Fake(override val id: String, val block: suspend (String) -> SourceResult<List<SearchBook>>) : BookSource {
        override val name = id
        override val capabilities = SourceCapabilities(supportComic = true)
        override suspend fun search(keyword: String) = block(keyword)
        override suspend fun getDetail(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun getDownloadInfo(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
    }
    private fun hit(query: String) = SourceResult.Success(listOf(SearchBook(query, "s", query, "")))

    @Test fun eightSitesRemainParallelAndTheirFirstRequestHasNoCooldown() = runTest {
        var active = 0
        var peak = 0
        val searches = SourceSearchCoordinator(now = { currentTime })
        (1..8).map { id -> async {
            searches.search(Fake("$id") {
                active++; peak = maxOf(peak, active)
                delay(100); active--; hit(it)
            }, "first")
        } }.awaitAll()
        assertEquals(8, peak)
        assertEquals(100L, currentTime)
    }

    @Test fun oneSiteIsSequentialAndSpacedFromCompletionAcrossQueries() = runTest {
        val starts = mutableListOf<Long>()
        val source = Fake("s") { starts += currentTime; delay(100); hit(it) }
        val searches = SourceSearchCoordinator(now = { currentTime })
        listOf("a", "b", "c").map { async { searches.search(source, it) } }.awaitAll()
        assertEquals(listOf(0L, 1200L, 2400L), starts)
    }

    @Test fun successfulDuplicatesShareOneRequestAndRepeatWithoutDelay() = runTest {
        var calls = 0
        val source = Fake("s") { calls++; delay(100); hit(it) }
        val searches = SourceSearchCoordinator(now = { currentTime })
        (1..8).map { async { searches.search(source, "same") } }.awaitAll()
        searches.search(source, " same ")
        assertEquals(1, calls)
        assertEquals(100L, currentTime)
    }

    @Test fun emptyResponsesAndFailuresNeverBecomeCachedResults() = runTest {
        var calls = 0
        val source = Fake("s") { when (++calls) {
            1 -> SourceResult.Success(emptyList())
            2 -> SourceResult.Error(SourceException.NetworkError("HTTP 429"))
            3 -> throw IllegalStateException("temporary")
            else -> hit(it)
        } }
        val searches = SourceSearchCoordinator(now = { currentTime })
        searches.search(source, "same")
        searches.search(source, "same")
        try { searches.search(source, "same"); fail() } catch (_: IllegalStateException) { }
        assertTrue(searches.search(source, "same") is SourceResult.Success)
        assertEquals(4, calls)
        assertEquals(3300L, currentTime)
    }

    @Test fun expiryAndReplacementSourceRequireFreshResults() = runTest {
        var calls = 0
        val source = Fake("s") { calls++; hit(it) }
        val searches = SourceSearchCoordinator(now = { currentTime })
        searches.search(source, "same")
        delay(60_000)
        searches.search(source, "same")
        searches.search(Fake("s") { calls++; hit("replacement") }, "same")
        assertEquals(3, calls)
    }

    @Test fun cancellationDuringNetworkAndCooldownReleasesTheSite() = runTest {
        val source = Fake("s") { if (it == "old") delay(20_000); hit(it) }
        val searches = SourceSearchCoordinator(now = { currentTime })
        val old = launch { searches.search(source, "old") }
        runCurrent(); old.cancelAndJoin()
        val waiting = launch { searches.search(source, "abandoned") }
        advanceTimeBy(500); waiting.cancelAndJoin()
        searches.search(source, "new")
        assertEquals(1100L, currentTime)
    }

    @Test fun aCoolingSiteDoesNotHoldTheOnlyCrossSiteNetworkSlot() = runTest {
        val searches = SourceSearchCoordinator(now = { currentTime })
        val runner = ComicAggregateSearch(StandardTestDispatcher(testScheduler), Semaphore(1), searches = searches)
        val source = Fake("cooling") { hit(it) }
        runner.search(listOf(source), "first", { listOf(it) }, {})
        val updates = mutableListOf<Pair<Long, String>>()
        runner.search(listOf(source, Fake("new") { delay(100); hit(it) }), "second", { listOf(it) }) {
            if (it.books.isNotEmpty()) updates += currentTime to it.sourceId
        }
        assertEquals(100L to "new", updates.first())
        assertEquals(1100L, currentTime)
    }
}
