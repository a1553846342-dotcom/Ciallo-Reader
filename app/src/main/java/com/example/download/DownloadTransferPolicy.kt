package com.example.download

/** HTTP byte ranges must describe exactly the requested tail of the same representation. */
internal object DownloadTransferPolicy {
    data class Range(val start: Long, val end: Long, val total: Long)

    fun parseRange(header: String?): Range? {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return Range(start, end, total).takeIf { start <= end && end < total }
    }

    fun validateTail(header: String?, offset: Long, contentLength: Long): Long {
        val range = parseRange(header) ?: error("服务器返回了无效的续传范围")
        require(range.start == offset && range.end == range.total - 1) { "服务器返回的续传位置不一致" }
        require(contentLength < 0 || contentLength == range.end - range.start + 1) { "续传响应长度不一致" }
        return range.total
    }

    fun validator(etag: String?, lastModified: String?): String? =
        etag?.takeIf { it.startsWith('"') && it.endsWith('"') }
            ?: lastModified?.takeIf { it.isNotBlank() }
}
