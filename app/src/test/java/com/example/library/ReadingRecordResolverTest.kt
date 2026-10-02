package com.example.library

import com.example.data.ReadingRecord
import com.example.data.favorite.FavoriteEntity
import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingRecordResolverTest {
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
    private fun record(id: Int, title: String = "作品$id") = ReadingRecord(id, null, title, "2026-10-02", 60)
    private fun book(title: String, source: String = "original") = SearchBook("resource", source, title, "", "https://cdn.example/cover.jpg")

    @Test fun savedReadingDestinationAndFavoriteNeedNoNetwork() = runTest {
        val output = mutableMapOf<Int, SearchBook>()
        val source = Fake("unexpected") { error("Local metadata must not search") }
        ReadingRecordResolver().resolve(listOf(record(1), record(2)), emptyList(),
            listOf(FavoriteEntity("favorite", "favorite-id", "作品2")), listOf(source),
            { if (it.id == 1) book(it.bookTitle) else null }, { r, b -> output[r.id] = b })
        assertEquals("original", output.getValue(1).sourceId)
        assertEquals("favorite-id", output.getValue(2).id)
        assertEquals(0L, currentTime)
    }

    @Test fun fastSourcePublishesWithoutWaitingForSlowSourcesAndCancelsThem() = runTest {
        var slowCancelled = false
        val sources = listOf(Fake("slow") {
            try { delay(20_000); SourceResult.Success(emptyList()) }
            finally { slowCancelled = true }
        }, Fake("fast") { delay(100); SourceResult.Success(listOf(book(it, "fast"))) })
        val output = mutableListOf<Pair<Long, SearchBook>>()
        ReadingRecordResolver().resolve(listOf(record(1)), emptyList(), emptyList(), sources,
            { null }, { _, b -> output += currentTime to b })
        assertEquals(100L, output.single().first)
        assertEquals("fast", output.single().second.sourceId)
        assertTrue(slowCancelled)
    }

    @Test fun allRecordsBeyondFiveResolveAndDuplicateTitlesSearchOnce() = runTest {
        var calls = 0
        var active = 0
        var peak = 0
        val source = Fake("source") {
            calls++; active++; peak = maxOf(peak, active)
            try { delay(100); SourceResult.Success(listOf(book(it))) }
            finally { active-- }
        }
        val records = (1..9).map { record(it) } + record(10, "作品1")
        val output = mutableSetOf<Int>()
        ReadingRecordResolver().resolve(records, emptyList(), emptyList(), listOf(source),
            { null }, { r, _ -> output += r.id })
        assertEquals(10, output.size)
        assertEquals(9, calls)
        assertTrue(peak <= 3)
    }

    @Test fun unrelatedAndPartialTitlesCannotReplaceTheOriginalWork() = runTest {
        val source = Fake("wrong") { SourceResult.Success(listOf(book("作品"), book("其他作品"))) }
        assertNull(ReadingRecordResolver().search("作品1", listOf(source)))
    }

    @Test fun sourceFailureAndTimeoutAreIsolatedAndRequestsStayBounded() = runTest {
        var active = 0
        var peak = 0
        val sources = (0..8).map { id -> Fake("source$id") {
            active++; peak = maxOf(peak, active)
            try { if (id == 0) error("broken") else delay(9_000); SourceResult.Success(emptyList()) }
            finally { active-- }
        } }
        assertNull(ReadingRecordResolver().search("作品1", sources))
        assertTrue(peak <= 4)
        assertEquals(0, active)
    }
}
