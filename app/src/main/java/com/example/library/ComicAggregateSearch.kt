package com.example.library

import com.example.source.BookSource
import com.example.source.SearchBook
import com.example.source.SourceException
import com.example.source.SourceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Search orchestration only; each source continues to own its transport and parsing. */
internal class ComicAggregateSearch(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val slots: Semaphore = Semaphore(8),
    private val requestTimeoutMs: Long = 20_000L,
    private val searches: SourceSearchCoordinator = SourceSearchCoordinator(),
) {
    suspend fun search(
        sources: List<BookSource>,
        keyword: String,
        expandVariants: suspend (String) -> List<String>,
        onGroup: (LibraryUiState.AggregateGroup) -> Unit,
    ) = coroutineScope {
        // Title lookup must never delay the original keyword's first network request.
        val variants = async(dispatcher) {
            try {
                expandVariants(keyword).filter { it.isNotBlank() && it != keyword }.distinct()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Enqueue EVERY original request before aliases can take a permit. UNDISPATCHED
        // only acquires/queues the permit; the source itself always runs on dispatcher.
        val originals = sources.map { source ->
            async(start = CoroutineStart.UNDISPATCHED) { request(source, keyword) }
        }
        sources.forEachIndexed { index, source ->
            launch {
                val books = LinkedHashMap<String, SearchBook>()
                var anySuccess = false
                var lastError: String? = null

                fun publish(loading: Boolean) {
                    onGroup(
                        LibraryUiState.AggregateGroup(
                            sourceId = source.id,
                            sourceName = source.name,
                            books = books.values.toList(),
                            error = if (anySuccess) null else lastError,
                            loading = loading,
                        )
                    )
                }

                fun accept(result: SourceResult<List<SearchBook>>?) {
                    when (result) {
                        is SourceResult.Success -> {
                            anySuccess = true
                            result.data.forEach { book ->
                                // Keep first-hit order and all distinct resources in each source.
                                if (book.id !in books) books[book.id] = book.copy(sourceId = source.id)
                            }
                            publish(loading = true)
                        }
                        is SourceResult.Error -> lastError = result.exception.message
                        null -> lastError = "搜索超时"
                    }
                }

                accept(originals[index].await())
                // Aliases stay sequential WITHIN a source, respecting its runtime/session.
                for (variant in variants.await()) accept(request(source, variant))
                publish(loading = false)
            }
        }
    }

    private suspend fun request(source: BookSource, keyword: String): SourceResult<List<SearchBook>>? =
        try {
            searches.search(source, keyword) {
                // Cooling sites and cache hits must not occupy another site's network slot.
                slots.withPermit {
                    withContext(dispatcher) {
                        withTimeoutOrNull(requestTimeoutMs) { source.search(keyword) }
                            ?: SourceResult.Error(SourceException.NetworkError("搜索超时"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceResult.Error(SourceException.NetworkError(e.message ?: "搜索失败", e))
        }
}

/** Promote first hits ahead of pending sources, retaining the arrival order of result groups. */
internal fun LibraryUiState.AggregateResults.withComicSearchGroup(
    group: LibraryUiState.AggregateGroup,
): LibraryUiState.AggregateResults {
    val updated = groups.map { if (it.sourceId == group.sourceId) group else it }
        .sortedBy { it.books.isEmpty() }
    return copy(groups = updated, running = updated.any { it.loading })
}
