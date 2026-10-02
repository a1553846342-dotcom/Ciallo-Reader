package com.example.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Limits apply to actual expanded bytes, including entries the parser ignores. */
internal class ArchiveBudget(
    private val maxEntryBytes: Long = 64L * 1024 * 1024,
    private val maxTotalBytes: Long = 512L * 1024 * 1024,
    private val maxEntries: Int = 10_000,
) {
    private var total = 0L
    private var entries = 0

    fun copyEntry(input: InputStream, output: OutputStream? = null) {
        require(++entries <= maxEntries) { "压缩包条目过多" }
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            total += read
            require(size <= maxEntryBytes && total <= maxTotalBytes) { "压缩包解压体积超过安全上限" }
            output?.write(buffer, 0, read)
        }
    }

    companion object {
        fun destination(root: File, entryName: String): File {
            val normalized = entryName.replace('\\', '/')
            require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
                "压缩包包含绝对路径"
            }
            val target = File(root, normalized).canonicalFile
            require(target.canonicalPath.startsWith(root.canonicalPath.trimEnd(File.separatorChar)+File.separator)) {
                "压缩包包含越界路径"
            }
            return target
        }
    }
}

internal fun InputStream.readImportBytes(maxBytes: Int = 64 * 1024 * 1024): ByteArray {
    val output = ByteArrayOutputStream()
    ArchiveBudget(maxBytes.toLong(), maxBytes.toLong(), 1).copyEntry(this, output)
    return output.toByteArray()
}

/** Keep UTF-16 pairs and inline image references intact when storing chapter rows. */
internal fun splitChapterText(text: String, limit: Int = MAX_CHAPTER_LENGTH): List<String> {
    require(limit >= 2)
    if (text.isEmpty()) return emptyList()
    val parts = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val tokenStart = text.lastIndexOf("[IMG:", end - 1)
            if (tokenStart >= start && text.indexOf(']', tokenStart).let { it >= end }) {
                require(tokenStart > start) { "内嵌图片引用超过章节长度上限" }
                end = tokenStart
            }
        }
        parts.add(text.substring(start, end))
        start = end
    }
    return parts
}
