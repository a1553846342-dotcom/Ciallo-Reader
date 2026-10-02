package com.example.library

import com.example.data.Book
import com.example.data.ReadingRecord
import com.example.data.favorite.FavoriteEntity
import com.example.source.BookSource
import com.example.source.SearchBook
import com.example.source.SourceResult
import com.example.source.anilist.TitleNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Local identities first; only legacy records without a saved destination need a search. */
internal class ReadingRecordResolver(private val searches: SourceSearchCoordinator = SourceSearchCoordinator()) {
    private val requests = Semaphore(4)
    private val titles = Semaphore(3)

    suspend fun resolve(
        records: List<ReadingRecord>, books: List<Book>, favorites: List<FavoriteEntity>,
        sources: List<BookSource>, cached: (ReadingRecord) -> SearchBook?,
        publish: (ReadingRecord, SearchBook) -> Unit
    ) = coroutineScope {
        val byId = books.associateBy { it.id }
        val remaining = records.filter { record ->
            val local = record.bookId?.let { byId[it] }
            val favorite = favorites.filter { it.title == record.bookTitle }
                .distinctBy { it.sourceId to it.comicId }.singleOrNull()
            val result = local?.let { SearchBook(
                id = it.comicId ?: "local:${it.id}", sourceId = it.sourceId.orEmpty(),
                title = it.title, author = it.author, cover = it.coverUri
            ) } ?: cached(record) ?: favorite?.let { SearchBook(
                id = it.comicId, sourceId = it.sourceId, title = it.title, author = it.author,
                cover = it.localThumbPath ?: it.coverUrl
            ) }
            if (result != null) publish(record, result)
            result == null
        }
        remaining.groupBy { it.bookTitle }.map { (title, group) -> async {
            titles.withPermit {
                search(title, sources)?.let { found -> group.forEach { publish(it, found) } }
            }
        } }.awaitAll()
        Unit
    }

    private fun normalized(title: String) = TitleNormalizer.normalize(title).filter { it.isLetterOrDigit() }

    internal suspend fun search(title: String, sources: List<BookSource>): SearchBook? = coroutineScope {
        val wanted = normalized(title)
        if (wanted.isBlank() || sources.isEmpty()) return@coroutineScope null
        val results = Channel<SearchBook?>(sources.size)
        val jobs = sources.map { source -> launch {
            val found = try {
                requests.withPermit {
                    val reply = withTimeoutOrNull(8_000) { searches.search(source, title) }
                    (reply as? SourceResult.Success)?.data?.firstOrNull { normalized(it.title) == wanted }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
            results.send(found)
        } }
        try {
            repeat(sources.size) {
                results.receive()?.let { found ->
                    return@coroutineScope found.copy(id = found.comicId?.takeIf { it.isNotBlank() } ?: found.id)
                }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
    }
}
