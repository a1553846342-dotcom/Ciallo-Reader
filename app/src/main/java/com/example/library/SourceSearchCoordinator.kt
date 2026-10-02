package com.example.library

import com.example.source.BookSource
import com.example.source.SearchBook
import com.example.source.SourceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Cross-site concurrency stays independent; each site gets a short cooldown and result reuse. */
internal class SourceSearchCoordinator(
    private val spacingMs: Long = 1_100,
    private val cacheMs: Long = 60_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Cached(val source: BookSource, val at: Long, val books: List<SearchBook>)
    private class Site {
        val gate = Mutex()
        var finishedAt: Long? = null
        @Volatile var cache: Map<String, Cached> = emptyMap()
    }
    private val sites = ConcurrentHashMap<String, Site>()

    suspend fun search(
        source: BookSource, keyword: String,
        request: (suspend () -> SourceResult<List<SearchBook>>)? = null
    ): SourceResult<List<SearchBook>> {
        val query = keyword.trim()
        val site = sites.getOrPut(source.id) { Site() }
        fun cached(): SourceResult.Success<List<SearchBook>>? = site.cache[query]?.takeIf {
            it.source === source && now() - it.at in 0 until cacheMs
        }?.let { SourceResult.Success(it.books) }
        cached()?.let { return it }
        return site.gate.withLock {
            cached()?.let { return@withLock it }
            site.finishedAt?.let { delay((spacingMs - (now() - it)).coerceAtLeast(0)) }
            try {
                (request?.invoke() ?: source.search(query)).also { result ->
                    // Empty results and failures may be temporary throttling responses.
                    if (result is SourceResult.Success && result.data.isNotEmpty()) {
                        site.cache = (site.cache.filter { now() - it.value.at < cacheMs } +
                            (query to Cached(source, now(), result.data.toList())))
                            .entries.sortedByDescending { it.value.at }.take(8).associate { it.toPair() }
                    }
                }
            } finally { site.finishedAt = now() }
        }
    }
}
