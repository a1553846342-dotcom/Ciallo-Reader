package com.example.ui.comic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest

/** Includes authorization/referer in cache identity; never exposes header values in keys. */
internal fun comicRemoteCacheKey(url: String, headers: Map<String, String>, referer: String? = null): String {
    val normalized = headers.entries.associate { it.key.lowercase() to it.value }.toMutableMap()
    if (!referer.isNullOrBlank()) normalized["referer"] = referer
    val identity = buildString {
        append(url.length).append(':').append(url)
        normalized.toSortedMap().forEach { (k, v) ->
            append(k.length).append(':').append(k).append(v.length).append(':').append(v)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    val hex = "0123456789abcdef"
    return buildString(64) { digest.forEach { b ->
        val v = b.toInt() and 255
        append(hex[v shr 4]).append(hex[v and 15])
    } }
}

internal data class ComicTransferProgress(val bytes: Long = 0, val total: Long = -1) {
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Bounded telemetry only: no image bytes or credentials are retained. */
internal object ComicTransfers {
    private val states = LinkedHashMap<String, MutableStateFlow<ComicTransferProgress>>(128, .75f, true)
    @Synchronized fun state(url: String): StateFlow<ComicTransferProgress> = mutable(url)
    @Synchronized private fun mutable(url: String): MutableStateFlow<ComicTransferProgress> {
        return states.getOrPut(url) {
            if (states.size >= 128) states.remove(states.keys.first())
            MutableStateFlow(ComicTransferProgress())
        }
    }
    fun update(url: String, bytes: Long, total: Long) { mutable(url).value = ComicTransferProgress(bytes, total) }
}

/** Waiters keep their lock registered until the last caller leaves, including cancellation. */
internal class ComicLoadLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = HashMap<String, Entry>()
    @Synchronized fun acquire(key: String): Mutex = entries.getOrPut(key) { Entry() }.let {
        it.users++
        it.mutex
    }
    @Synchronized fun release(key: String) {
        entries[key]?.let { if (--it.users == 0) entries.remove(key) }
    }
}
