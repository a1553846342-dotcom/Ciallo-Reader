package com.example.download

import com.example.data.ArchiveBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** One Chinese text book in an archive; never route it through the comic importer. */
internal object NovelTextArchive {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val heading = Regex("^第([0-9]{1,7})[章回].{0,150}$")
    private data class Section(val number: Int, val offset: Long)

    suspend fun prepare(file: File): File? {
        val parent = requireNotNull(file.parentFile) { "小说临时文件路径无效" }
        val head = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return null
        val raw = File(parent, "${file.name}.text-${System.nanoTime()}")
        val ordered = File(parent, "${file.name}.ordered-${System.nanoTime()}")
        val utf8 = File(parent, "${file.name}.utf8-${System.nanoTime()}")
        var result: File? = null
        var charset: Charset = Charsets.UTF_8
        try {
            val sections = ArrayList<Section>()
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().take(9).toList()
                require(entries.size <= 8) { "小说压缩包条目异常" }
                entries.forEach { ArchiveBudget.destination(parent.canonicalFile, it.name) }
                val books = entries.filterNot { it.isDirectory }
                require(books.size == 1 && books.single().name.endsWith(".txt", true)) { "本源的压缩包未包含唯一的 TXT 小说" }
                val entry = books.single()
                require(entry.size in 1..MAX_BYTES) { "小说文本体积超出限制" }
                require(parent.usableSpace >= entry.size * 4 + 16L * 1024 * 1024) { "存储空间不足，请清理后重试" }
                val sample = zip.getInputStream(entry).use { input ->
                    val bytes = ByteArray(64 * 1024)
                    val size = input.read(bytes)
                    bytes.copyOf(size.coerceAtLeast(0))
                }
                // This site's Chinese packages are UTF-8 or GB18030. A generic multilingual
                // detector can mistake valid GBK bytes for Korean; constrain only this source.
                val utf8Decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val utf8Result = utf8Decoder.decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.size + 1), entry.size <= sample.size)
                charset = if (!utf8Result.isError) Charsets.UTF_8 else Charset.forName("GB18030")
                val crc = CRC32()
                val line = ByteArray(512)
                var lineSize = 0
                var tooLong = false
                var lineStart = 0L
                var offset = 0L
                fun finishLine() {
                    if (!tooLong) {
                        val text = String(line, 0, lineSize, charset).trim().trimStart('\uFEFF').trim()
                        heading.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            sections.add(Section(it, lineStart))
                        }
                    }
                    lineSize = 0; tooLong = false; lineStart = offset
                }
                zip.getInputStream(entry).use { input -> raw.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(offset + count <= MAX_BYTES) { "小说解压体积超出限制" }
                        output.write(buffer, 0, count); crc.update(buffer, 0, count)
                        for (i in 0 until count) {
                            offset++
                            if (buffer[i] == '\n'.code.toByte()) finishLine()
                            else if (lineSize < line.size) line[lineSize++] = buffer[i] else tooLong = true
                        }
                    }
                    if (lineSize > 0 || tooLong) finishLine()
                } }
                require(offset == entry.size && crc.value == entry.crc) { "小说压缩包不完整，请重新下载" }
            }
            // Some site packages put the last two numbered chapters in reverse order.
            // Sort complete byte ranges only; preface, body bytes and non-numbered notes are preserved.
            if (sections.zipWithNext().any { (a, b) -> a.number > b.number }) {
                var separators = 0L
                RandomAccessFile(raw, "r").use { input -> ordered.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    suspend fun copy(start: Long, end: Long) {
                        input.seek(start)
                        var remaining = end - start
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            require(count > 0) { "小说内容截断" }
                            output.write(buffer, 0, count); remaining -= count
                        }
                    }
                    copy(0, sections.first().offset)
                    sections.withIndex().sortedBy { it.value.number }.forEach { (index, section) ->
                        val end = sections.getOrNull(index + 1)?.offset ?: raw.length()
                        copy(section.offset, end)
                        // A package's final chapter may lack a trailing newline. After moving
                        // it earlier, its footer must not swallow the following chapter heading.
                        input.seek(end - 1)
                        if (input.read() != '\n'.code) {
                            output.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
                            separators += 2
                        }
                    }
                } }
                require(ordered.length() == raw.length() + separators) { "小说章节整理失败" }
            }
            val text = if (ordered.exists()) ordered else raw
            val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(text.inputStream(), decoder).buffered().use { input ->
                utf8.outputStream().writer(Charsets.UTF_8).buffered().use { output ->
                    // Preserve every decoded character, including line endings and notes.
                    val buffer = CharArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            result = utf8
            return result
        } finally {
            if (result != raw) raw.delete()
            if (result != ordered) ordered.delete()
            if (result != utf8) utf8.delete()
        }
    }
}
