package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * MOBI / AZW3 / AZW 解析器。
 *
 * 采用自包含实现（不引入任何第三方依赖）：
 *  - PDB 容器 + MOBI 头 + EXTH 元数据
 *  - 正文解压：无压缩(1) / PalmDOC LZ77(2) / HUFF-CDIC(17480)
 *  - KF8(AZW3) 混合容器识别：BOUNDARY 记录后读取 KF8 段正文
 *  - DRM 检测：EncryptionType != 0 时按占位书入库并给出明确提示
 *
 * 结构与算法参考：
 *  - mobi-api4java（Apache-2.0，org.rr.mobi4java）
 *  - Ephemerality.Unpack（MIT，KindleUnpack 思路的 C# 移植）
 */
object MobiParser {

    private const val TAG = "MobiParser"
    private const val DRM_CHAPTER_TITLE = "该文件受版权保护，暂不支持阅读"

    fun isMobiFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mobi") || lower.endsWith(".azw3") ||
            lower.endsWith(".azw") || lower.endsWith(".prc")
    }

    suspend fun importMobi(
        context: Context,
        uri: Uri,
        fileName: String,
        bookDao: BookDao,
        targetBookId: Int? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[MobiParser] Starting MOBI import for $fileName, uri: $uri")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readImportBytes() }
                ?: return@withContext Result.failure(Exception("无法读取 MOBI 文件"))

            val parsed = parseMobi(bytes)
            if (parsed == null) {
                return@withContext Result.failure(Exception("不是有效的 MOBI/AZW3 文件"))
            }
            if (parsed.failureReason != null) {
                return@withContext Result.failure(Exception(parsed.failureReason))
            }

            val title = parsed.title.ifBlank { fileName.substringBeforeLast('.').ifBlank { fileName } }
            val initialBook = Book(
                title = title,
                author = parsed.author.ifBlank { "未知作者" },
                filePath = uri.toString(),
                contentType = "NOVEL",
                totalChapters = 0
            )
            // targetBookId 非空 = 老书补图片迁移：不新建书、失败不删书（调用方事务收尾）
            val bookId = targetBookId ?: bookDao.insertBook(initialBook).toInt()
            Log.d(TAG, "[MobiParser] Using book record ID: $bookId")

            // DRM 保护：占位入库，章节标题给出明确提示，不尝试解析
            if (parsed.encryptionType != 0) {
                bookDao.insertChapters(
                    listOf(
                        Chapter(
                            bookId = bookId,
                            chapterOrder = 0,
                            title = DRM_CHAPTER_TITLE,
                            content = ""
                        )
                    )
                )
                val drmBook = initialBook.copy(id = bookId, totalChapters = 1)
                bookDao.updateBook(drmBook)
                Log.w(TAG, "[MobiParser] DRM protected book imported as placeholder: $title")
                return@withContext Result.success(drmBook)
            }

            val html = parsed.html ?: ""
            // 内嵌图片：<img recindex="N"> → 落盘图片占位符
            val htmlWithImages = embedMobiImages(html, parsed.records, parsed.firstImageIndex, bookId, context)
            val chapters = splitHtmlIntoChapters(htmlWithImages, bookId)
            if (chapters.isEmpty()) {
                if (targetBookId == null) bookDao.deleteBook(initialBook.copy(id = bookId))
                return@withContext Result.failure(Exception("MOBI 文件中未找到有效正文内容"))
            }

            var coverUri: String? = null
            parsed.coverBytes?.let { cover ->
                val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size)
                if (bitmap != null) {
                    try {
                        val coverDir = File(context.filesDir, "mobi_covers")
                        if (!coverDir.exists()) coverDir.mkdirs()
                        val ext = detectImageExt(cover) ?: "jpg"
                        val destCover = File(coverDir, "cover_${bookId}_${System.currentTimeMillis()}.$ext")
                        destCover.writeBytes(cover)
                        coverUri = destCover.absolutePath
                    } catch (e: Exception) {
                        Log.w(TAG, "[MobiParser] Failed to save cover", e)
                    }
                }
            }

            bookDao.insertChapters(chapters)
            val finalBook = initialBook.copy(
                id = bookId,
                coverUri = coverUri,
                totalChapters = chapters.size
            )
            bookDao.updateBook(finalBook)
            Log.d(TAG, "[MobiParser] Successfully imported '${finalBook.title}' with ${chapters.size} chapters.")
            Result.success(finalBook)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[MobiParser] Error during MOBI import", t)
            Result.failure(Exception(t.localizedMessage ?: "MOBI 解析失败"))
        }
    }

    // ------------------------------------------------------------------
    // 解析核心
    // ------------------------------------------------------------------

    private data class ExthRecord(val type: Int, val data: ByteArray)

    private class MobiHeader(
        val recordIndex: Int,
        val compression: Int,
        val textLength: Long,
        val recordCount: Int,
        val encryptionType: Int,
        val textEncoding: Int,
        val firstImageIndex: Int,
        val huffmanRecordOffset: Int,
        val huffmanRecordCount: Int,
        val exthFlags: Int,
        val firstContentRecordIndex: Int,
        val lastContentRecordIndex: Int,
        val fullName: String,
        val exth: List<ExthRecord>,
        val multibyte: Boolean,
        val trailers: Int
    )

    internal class ParsedMobi(
        val title: String,
        val author: String,
        val encryptionType: Int,
        val html: String?,
        val coverBytes: ByteArray?,
        val failureReason: String?,
        val records: List<ByteArray> = emptyList(),
        val firstImageIndex: Int = 0
    )

    internal fun parseMobi(bytes: ByteArray): ParsedMobi? {
        if (bytes.size < 80) return null
        val recordCount = readU16(bytes, 76)
        if (recordCount <= 0 || recordCount > 100_000) return null

        val records = ArrayList<ByteArray>(recordCount)
        for (i in 0 until recordCount) {
            val off = readU32(bytes, 78 + i * 8)
            if (off > bytes.size) break
            val end = if (i + 1 < recordCount) readU32(bytes, 78 + (i + 1) * 8) else bytes.size.toLong()
            val endInt = minOf(end, bytes.size.toLong()).toInt()
            if (off.toInt() >= endInt) break
            records.add(bytes.copyOfRange(off.toInt(), endInt))
        }
        if (records.isEmpty()) return null

        val firstHeader = parseMobiHeader(records[0], 0)
            ?: return null

        // KF8(AZW3) 混合容器：找到 BOUNDARY 记录后，读取其后的 KF8 PalmDoc/MOBI 头
        var active = firstHeader
        var startRecord = if (firstHeader.firstContentRecordIndex > 0) {
            firstHeader.firstContentRecordIndex
        } else {
            1
        }
        if (startRecord >= records.size) startRecord = 1

        var boundaryIndex = -1
        val firstSectionEnd = firstHeader.recordCount
        for (i in firstSectionEnd until records.size) {
            val rec = records[i]
            if (rec.size >= 8 && readU32(rec, 0) == 0x424F554EL && // "BOUN"
                String(rec, 0, 8, StandardCharsets.US_ASCII) == "BOUNDARY"
            ) {
                boundaryIndex = i
                break
            }
        }
        if (boundaryIndex >= 0 && boundaryIndex + 1 < records.size) {
            val kf8 = parseMobiHeader(records[boundaryIndex + 1], boundaryIndex + 1)
            if (kf8 != null) {
                active = kf8
                startRecord = boundaryIndex + 2
            }
        }

        val title = active.fullName.ifBlank {
            active.exth.firstOrNull { it.type == 503 }?.data?.let {
                decodeMobiText(it, active.textEncoding)
            } ?: ""
        }
        val author = active.exth.firstOrNull { it.type == 100 }?.data?.let {
            decodeMobiText(it, active.textEncoding)
        } ?: ""

        // DRM 检测（MOBI 头 EncryptionType 非 0）
        if (active.encryptionType != 0) {
            return ParsedMobi(title, author, active.encryptionType, null, null, null)
        }

        val decompressed: ByteArray = try {
            decompressText(records, active, startRecord)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[MobiParser] Text decompression failed", t)
            return ParsedMobi(
                title,
                author,
                0,
                null,
                null,
                "MOBI 正文解压失败：${t.localizedMessage ?: "不支持的压缩格式"}"
            )
        }

        val html = decodeMobiText(decompressed, active.textEncoding)
        val cover = extractCover(records, active, startRecord)
        return ParsedMobi(title, author, 0, html, cover, null, records, active.firstImageIndex)
    }

    private fun parseMobiHeader(record: ByteArray, recordIndex: Int): MobiHeader? {
        if (record.size < 132) return null
        val magic = String(record, 16, 4, StandardCharsets.US_ASCII)
        if (magic != "MOBI") return null

        val compression = readU16(record, 0)
        val textLength = readU32(record, 4)
        val recordCount = readU16(record, 8)
        val encryptionType = readU16(record, 12)
        val headerLength = readU32(record, 20).toInt()
        val textEncoding = readU16(record, 28)
        val fullNameOffset = readU32(record, 84).toInt()
        val fullNameLength = readU32(record, 88).toInt()
        val minVersion = readU32(record, 104).toInt()
        val firstImageIndex = readU32(record, 108).toInt()
        val huffmanRecordOffset = readU32(record, 112).toInt()
        val huffmanRecordCount = readU32(record, 116).toInt()
        val exthFlags = readU32(record, 128).toInt()

        var firstContentRecordIndex = 1
        var lastContentRecordIndex = -1
        if (headerLength >= 194 && record.size >= 196) {
            firstContentRecordIndex = readU16(record, 192)
        }
        if (headerLength >= 196 && record.size >= 198) {
            lastContentRecordIndex = readU16(record, 194)
        }

        // Multibyte / trailer 标志（MobiHeader 版本 >= 5 且 headerLength >= 228 时有效）
        var multibyte = false
        var trailers = 0
        if (minVersion >= 5 && headerLength >= 228 && record.size >= 244) {
            var mbhFlags = readU32(record, 240).toInt()
            multibyte = (mbhFlags and 1) != 0
            mbhFlags = mbhFlags shr 1
            while (mbhFlags > 0) {
                if ((mbhFlags and 1) != 0) trailers++
                mbhFlags = mbhFlags shr 1
            }
        }

        val fullName = if (fullNameLength > 0 && fullNameOffset >= 0 &&
            fullNameOffset + fullNameLength <= record.size
        ) {
            decodeMobiText(record.copyOfRange(fullNameOffset, fullNameOffset + fullNameLength), textEncoding)
        } else {
            ""
        }

        val exth = parseExth(record, headerLength, exthFlags)
        return MobiHeader(
            recordIndex = recordIndex,
            compression = compression,
            textLength = textLength,
            recordCount = recordCount,
            encryptionType = encryptionType,
            textEncoding = textEncoding,
            firstImageIndex = firstImageIndex,
            huffmanRecordOffset = huffmanRecordOffset,
            huffmanRecordCount = huffmanRecordCount,
            exthFlags = exthFlags,
            firstContentRecordIndex = firstContentRecordIndex,
            lastContentRecordIndex = lastContentRecordIndex,
            fullName = fullName,
            exth = exth,
            multibyte = multibyte,
            trailers = trailers
        )
    }

    private fun parseExth(record: ByteArray, headerLength: Int, exthFlags: Int): List<ExthRecord> {
        if (exthFlags and 0x40 == 0) return emptyList()
        val start = 16 + headerLength
        if (start + 12 > record.size) return emptyList()
        if (readU32(record, start) != 0x45585448L) return emptyList() // EXTH

        val count = readU32(record, start + 8).toInt()
        if (count <= 0 || count > 10_000) return emptyList()
        val out = ArrayList<ExthRecord>(count)
        var p = start + 12
        repeat(count) {
            if (p + 8 > record.size) return@repeat
            val type = readU32(record, p).toInt()
            val len = readU32(record, p + 4).toInt()
            if (len < 8) return@repeat
            val payloadLen = len - 8
            if (p + 8 + payloadLen > record.size) return@repeat
            out.add(ExthRecord(type, record.copyOfRange(p + 8, p + 8 + payloadLen)))
            p += len
        }
        return out
    }

    // ------------------------------------------------------------------
    // 正文解压
    // ------------------------------------------------------------------

    private fun decompressText(
        records: List<ByteArray>,
        header: MobiHeader,
        startRecord: Int
    ): ByteArray {
        var endRecord = startRecord + header.recordCount - 1
        if (header.lastContentRecordIndex > 0 && header.lastContentRecordIndex < records.size) {
            endRecord = minOf(endRecord, header.lastContentRecordIndex)
        }
        if (endRecord >= records.size) endRecord = records.size - 1
        if (startRecord > endRecord) return ByteArray(0)

        return when (header.compression) {
            1 -> {
                // 无压缩：原样拼接
                val out = ByteArrayOutputStream()
                for (i in startRecord..endRecord) {
                    out.write(trimTrailing(records[i], header))
                    require(out.size() <= 64 * 1024 * 1024) { "MOBI正文过大" }
                }
                out.toByteArray()
            }
            2 -> {
                // PalmDOC LZ77
                val first = records[startRecord]
                val decode0 = decodePalmDoc(trimTrailing(first, header))
                val decode2 = if (first.size > 2) {
                    decodePalmDoc(trimTrailing(first.copyOfRange(2, first.size), header))
                } else {
                    decode0
                }
                val useSkip2 = scoreText(decode2) > scoreText(decode0)
                val out = ByteArrayOutputStream()
                for (i in startRecord..endRecord) {
                    var rec = trimTrailing(records[i], header)
                    if (useSkip2 && rec.size > 2) rec = rec.copyOfRange(2, rec.size)
                    out.write(decodePalmDoc(rec))
                    require(out.size() <= 64 * 1024 * 1024) { "MOBI正文过大" }
                }
                out.toByteArray()
            }
            17480 -> {
                // HUFF/CDIC
                val huff = HuffCdic(records, header)
                val out = ByteArrayOutputStream()
                for (i in startRecord..endRecord) {
                    out.write(huff.unpack(trimTrailing(records[i], header)))
                    require(out.size() <= 64 * 1024 * 1024) { "MOBI正文过大" }
                }
                out.toByteArray()
            }
            else -> throw IllegalStateException("不支持的压缩格式: ${header.compression}")
        }
    }

    /** PalmDOC LZ77 解压，输出动态扩容，避免高压缩比记录溢出。 */
    private fun decodePalmDoc(data: ByteArray): ByteArray {
        var out = ByteArray(maxOf(4096, data.size * 4))
        var o = 0
        var i = 0
        fun ensure(extra: Int) {
            if (o + extra > out.size) {
                out = out.copyOf(maxOf(out.size * 2, o + extra))
            }
        }
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            when {
                c in 1..8 -> {
                    ensure(c)
                    val end = minOf(i + c, data.size)
                    while (i < end) out[o++] = data[i++]
                }
                c < 0x80 -> {
                    ensure(1)
                    out[o++] = c.toByte()
                }
                c >= 0xC0 -> {
                    ensure(2)
                    out[o++] = ' '.code.toByte()
                    out[o++] = (c xor 0x80).toByte()
                }
                else -> {
                    if (i < data.size) {
                        val c2 = (c shl 8) or (data[i++].toInt() and 0xFF)
                        val length = (c2 and 0x07) + 3
                        val location = (c2 shr 3) and 0x7FF
                        if (location in 1..o) {
                            ensure(length)
                            repeat(length) {
                                out[o] = out[o - location]
                                o++
                            }
                        }
                    }
                }
            }
        }
        return out.copyOf(o)
    }

    private fun scoreText(bytes: ByteArray): Int {
        var score = 0
        val n = minOf(bytes.size, 300)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E || b == 0x09 || b == 0x0A || b == 0x0D) score++
        }
        return score
    }

    /** 去掉 MbhFlags 描述的记录尾部附加数据（multibyte 长度 + trailers）。 */
    private fun trimTrailing(data: ByteArray, header: MobiHeader): ByteArray {
        var result = data
        repeat(header.trailers) {
            val num = trailingEntrySize(result)
            if (num in 1 until result.size) {
                result = result.copyOfRange(0, result.size - num)
            }
        }
        if (header.multibyte && result.isNotEmpty()) {
            val num = (result[result.size - 1].toInt() and 0x03) + 1
            if (num in 1 until result.size) {
                result = result.copyOfRange(0, result.size - num)
            }
        }
        return result
    }

    private fun trailingEntrySize(data: ByteArray): Int {
        var num = 0
        for (i in data.size - 4 until data.size) {
            if (i < 0) continue
            val b = data[i].toInt() and 0xFF
            if (b and 0x80 != 0) num = 0
            num = (num shl 7) or (b and 0x7F)
        }
        return num
    }

    /** HUFF/CDIC 解压（MIT Ephemerality.Unpack 的 Kotlin 移植，输出动态扩容）。 */
    private class HuffCdic(
        private val records: List<ByteArray>,
        private val header: MobiHeader
    ) {
        private data class DictRecord(val codeLen: Int, val term: Long, val maxCode: Long)

        private class Slice(val data: ByteArray, var flag: Int)

        private val dict1 = ArrayList<DictRecord>(256)
        private val dict2 = ArrayList<Int>(64)
        private val mincode = ArrayList<Long>()
        private val maxcode = ArrayList<Long>()
        private val dictionary = ArrayList<Slice>()
        private val expanding = HashSet<Int>()
        private var cachedBytes = 0L

        init {
            initialize()
        }

        private fun initialize() {
            val huffOff = header.huffmanRecordOffset
            if (huffOff < 0 || huffOff >= records.size) {
                throw IllegalStateException("HUFF 记录索引越界: $huffOff")
            }
            loadHuff(records[huffOff])
            val recCount = header.huffmanRecordCount
            if (recCount <= 0) {
                throw IllegalStateException("HUFF 记录数量无效: $recCount")
            }
            for (i in 1 until recCount) {
                val idx = huffOff + i
                if (idx >= records.size) break
                loadCdic(records[idx])
            }
        }

        private fun loadHuff(data: ByteArray) {
            if (data.size < 16 || readU32(data, 0) != 0x48554646L || readU32(data, 4) != 24L) {
                throw IllegalStateException("无效的 HUFF 头")
            }
            val off1 = readU32(data, 8).toInt()
            val off2 = readU32(data, 12).toInt()
            if (off1 < 0 || off2 < 0 || off1 + 256 * 4 > data.size || off2 + 64 * 4 > data.size) {
                throw IllegalStateException("HUFF 表偏移越界")
            }
            for (i in 0 until 256) {
                dict1.add(dictUnpack(readU32(data, off1 + i * 4)))
            }
            for (i in 0 until 64) {
                dict2.add(readU32(data, off2 + i * 4).toInt())
            }

            var count = 1
            mincode.add(0L)
            maxcode.add(0L)
            var i = 0
            while (i < dict2.size) {
                val item = dict2[i].toLong() and 0xFFFFFFFFL
                mincode.add(item shl (32 - count))
                count++
                i += 2
            }
            count = 1
            i = 1
            while (i < dict2.size) {
                val item = dict2[i].toLong() and 0xFFFFFFFFL
                maxcode.add(((item + 1L) shl (32 - count)) - 1L)
                count++
                i += 2
            }
        }

        private fun dictUnpack(v: Long): DictRecord {
            val codelen = (v and 0x1F).toInt()
            val term = v and 0x80
            if (codelen == 0) throw IllegalStateException("无效的 HUFF codelen")
            if (codelen <= 8 && term == 0L) throw IllegalStateException("无效的 HUFF term")
            var maxcode = (v shr 8) + 1L
            maxcode = (maxcode shl (32 - codelen)) - 1L
            return DictRecord(codelen, term, maxcode)
        }

        private fun loadCdic(data: ByteArray) {
            if (data.size < 16 || readU32(data, 0) != 0x43444943L || readU32(data, 4) != 16L) {
                throw IllegalStateException("无效的 CDIC 头")
            }
            val phrases = readU32(data, 8).toInt()
            val bits = readU32(data, 12).toInt()
            if (bits < 0 || bits > 16) throw IllegalStateException("无效的 CDIC bits: $bits")
            val n = minOf(1 shl bits, phrases - dictionary.size)
            for (i in 0 until n) {
                val offset = readU16(data, 16 + i * 2)
                if (16 + offset + 2 > data.size) break
                dictionary.add(getSlice(data, offset))
            }
        }

        private fun getSlice(data: ByteArray, offset: Int): Slice {
            val blen = readU16(data, 16 + offset)
            val len = blen and 0x7FFF
            if (18 + offset + len > data.size) {
                throw IllegalStateException("CDIC 切片越界")
            }
            val sliceData = data.copyOfRange(18 + offset, 18 + offset + len)
            return Slice(sliceData, if (blen and 0x8000 != 0) 1 else 0)
        }

        fun unpack(input: ByteArray, depth: Int = 0): ByteArray {
            require(depth <= 64) { "HUFF短语递归过深" }
            var out = ByteArray(4096)
            var op = 0
            var bitsleft = input.size * 8
            val data = input + ByteArray(8)
            var pos = 0
            var x = readU64(data, pos)
            var n = 32
            while (true) {
                if (n <= 0) {
                    pos += 4
                    if (pos + 8 > data.size) break
                    x = readU64(data, pos)
                    n += 32
                }
                val code = (x shr n) and 0xFFFFFFFFL
                val firstRec = dict1[(code shr 24).toInt()]
                var codelen = firstRec.codeLen
                var max = firstRec.maxCode
                if (firstRec.term == 0L) {
                    while (codelen <= 32 && code < mincode.getOrElse(codelen) { 0L }) codelen++
                    if (codelen > 32) break
                    max = maxcode.getOrElse(codelen) { 0L }
                }
                n -= codelen
                bitsleft -= codelen
                if (bitsleft < 0) break
                val r = ((max - code) shr (32 - codelen)).toInt()
                if (r !in dictionary.indices) break
                var slice = dictionary[r]
                if (slice.flag == 0) {
                    require(expanding.add(r)) { "HUFF短语循环引用" }
                    val newData = try { unpack(slice.data, depth + 1) } finally { expanding.remove(r) }
                    cachedBytes += newData.size
                    require(cachedBytes <= 32L * 1024 * 1024) { "HUFF字典膨胀过大" }
                    slice = Slice(newData, 1)
                    dictionary[r] = slice
                }
                require(op.toLong() + slice.data.size <= 8L * 1024 * 1024) { "HUFF记录膨胀过大" }
                if (op + slice.data.size > out.size) {
                    out = out.copyOf(maxOf(out.size * 2, op + slice.data.size))
                }
                System.arraycopy(slice.data, 0, out, op, slice.data.size)
                op += slice.data.size
            }
            return out.copyOf(op)
        }
    }

    // ------------------------------------------------------------------
    // 文本与封面
    // ------------------------------------------------------------------

    private fun decodeMobiText(bytes: ByteArray, textEncoding: Int): String {
        if (bytes.isEmpty()) return ""
        val raw = when (textEncoding) {
            1252 -> String(bytes, Charset.forName("windows-1252"))
            65001 -> String(bytes, StandardCharsets.UTF_8)
            65002 -> {
                val be = String(bytes, StandardCharsets.UTF_16BE)
                if (be.count { it == '\uFFFD' } > 0) {
                    String(bytes, StandardCharsets.UTF_16LE)
                } else {
                    be
                }
            }
            else -> {
                val utf8 = String(bytes, StandardCharsets.UTF_8)
                if (!utf8.contains('\uFFFD')) {
                    utf8
                } else {
                    try {
                        String(bytes, Charset.forName("GBK"))
                    } catch (_: Exception) {
                        utf8
                    }
                }
            }
        }
        // 过滤 MOBI 文件里常见的控制字节/随机尾随字节（保留换行制表）
        return raw.filter { ch ->
            ch == '\n' || ch == '\r' || ch == '\t' || (ch.code >= 0x20 && ch.code != 0x7F)
        }
    }

    private fun extractCover(
        records: List<ByteArray>,
        header: MobiHeader,
        startRecord: Int
    ): ByteArray? {
        val coverOffset = header.exth.firstOrNull { it.type == 201 }?.data
            ?.let { if (it.size >= 4) readI32(it, 0) else -1 }
            ?: -1

        val candidates = ArrayList<ByteArray>()
        if (coverOffset >= 0) {
            val idx1 = header.firstImageIndex + coverOffset
            if (idx1 in records.indices) candidates.add(records[idx1])
            val idx2 = startRecord + header.firstImageIndex + coverOffset
            if (idx2 in records.indices && idx2 != idx1) candidates.add(records[idx2])
            val idx3 = startRecord + coverOffset
            if (idx3 in records.indices && idx3 != idx1 && idx3 != idx2) candidates.add(records[idx3])
        }

        // 暴力兜底：扫描所有疑似图片记录，按体积从大到小验证
        val scanned = records.mapIndexedNotNull { i, rec ->
            if (i > 0 && looksLikeImage(rec)) rec else null
        }.sortedByDescending { it.size }

        for (candidate in candidates + scanned) {
            normalizeImage(candidate)?.let { return it }
        }
        return null
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xD8) return true // JPEG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true // PNG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true // GIF
        if (b0 == 0x42 && b1 == 0x4D) return true // BMP
        // 带 6 字节偏移头的 JPEG（JFIF / Exif）
        if (bytes.size >= 10 && b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) {
            val b6 = bytes[6].toInt() and 0xFF
            val b7 = bytes[7].toInt() and 0xFF
            val b8 = bytes[8].toInt() and 0xFF
            val b9 = bytes[9].toInt() and 0xFF
            if (b6 == 0x4A && b7 == 0x46 && b8 == 0x49 && b9 == 0x46) return true // JFIF
            if (b6 == 0x45 && b7 == 0x78 && b8 == 0x69 && b9 == 0x66) return true // Exif
        }
        return false
    }

    private fun normalizeImage(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) return bytes
        val candidates = ArrayList<ByteArray>()
        if (bytes.size > 6) {
            val tail = bytes.copyOfRange(6, bytes.size)
            if (BitmapFactory.decodeByteArray(tail, 0, tail.size) != null) candidates.add(tail)
        }
        for (marker in listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()),
            byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
        )) {
            val idx = indexOfBytes(bytes, marker) ?: continue
            if (idx > 0) {
                val sub = bytes.copyOfRange(idx, bytes.size)
                if (BitmapFactory.decodeByteArray(sub, 0, sub.size) != null) candidates.add(sub)
            }
        }
        return candidates.firstOrNull()
    }

    private fun indexOfBytes(data: ByteArray, pattern: ByteArray): Int? {
        if (pattern.isEmpty() || data.size < pattern.size) return null
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun detectImageExt(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "gif"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 10 && bytes[6] == 0x4A.toByte() && bytes[7] == 0x46.toByte() &&
                bytes[8] == 0x49.toByte() && bytes[9] == 0x46.toByte() -> "jpg"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 章节切分
    // ------------------------------------------------------------------

    private val pageBreakRegex = Regex(
        """(?i)<(?:mbp:pagebreak|pagebreak|hr)[^>]*>"""
    )
    private val headingRegex = Regex(
        """(?is)<(h[1-6])([^>]*)>(.*?)</\1>"""
    )

    /**
     * 把正文 HTML 里的 <img recindex="N"> 替换为落盘图片占位符 [IMG:路径|宽|高]。
     * 图片记录定位：records[firstImageIndex + N]（带多个候选回退，与封面提取同源）。
     */
    private fun embedMobiImages(
        html: String,
        records: List<ByteArray>,
        firstImageIndex: Int,
        bookId: Int,
        context: Context
    ): String {
        if (!html.contains("recindex", ignoreCase = true)) return html
        val imageDir = File(context.filesDir, "mobi_images/$bookId")
        if (!imageDir.exists()) imageDir.mkdirs()

        val imgRegex = Regex("""<img[^>]*recindex="?(\d+)"?[^>]*>""", setOf(RegexOption.IGNORE_CASE))
        var seq = 0
        return imgRegex.replace(html) { m ->
            val rec = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val candidates = listOf(
                firstImageIndex + rec,
                firstImageIndex + rec - 1,
                startRecordFallback(rec, records.size),
                rec
            ).filter { it in records.indices }.distinct()
            val bytes = candidates.mapNotNull { idx ->
                records.getOrNull(idx)?.takeIf { looksLikeImage(it) }
            }.firstOrNull() ?: return@replace m.value
            val normalized = normalizeImage(bytes) ?: return@replace m.value

            seq++
            val ext = detectImageExt(normalized) ?: "jpg"
            val f = File(imageDir, "img_${rec}.$ext")
            f.writeBytes(normalized)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            "[IMG:${f.absolutePath}|${opts.outWidth.coerceAtLeast(1)}|${opts.outHeight.coerceAtLeast(1)}]"
        }
    }

    private fun startRecordFallback(rec: Int, size: Int): Int = -1

    internal fun splitHtmlIntoChapters(html: String, bookId: Int): List<Chapter> {
        if (html.isBlank()) return emptyList()

        // 1. 按 pagebreak / hr 粗切
        val sections = ArrayList<String>()
        var pos = 0
        for (m in pageBreakRegex.findAll(html)) {
            sections.add(html.substring(pos, m.range.first))
            pos = m.range.last + 1
        }
        sections.add(html.substring(pos))

        // 2. 每个 section 内再按 h1-h6 切，标题归属新章节
        val rawSegments = ArrayList<Pair<String, String>>() // title hint -> html segment
        for (section in sections) {
            val matches = headingRegex.findAll(section).toList()
            if (matches.isEmpty()) {
                rawSegments.add("" to section)
                continue
            }
            var p = 0
            for (m in matches) {
                if (m.range.first > p) {
                    rawSegments.add("" to section.substring(p, m.range.first))
                }
                rawSegments.add(cleanHeadingText(m.groupValues[3]) to m.value)
                p = m.range.last + 1
            }
            if (p < section.length) {
                rawSegments.add("" to section.substring(p))
            }
        }

        val chapters = mutableListOf<Chapter>()
        var order = 0
        for ((hint, segment) in rawSegments) {
            val text = cleanHtmlText(segment)
            if (text.isBlank()) continue
            val title = hint.ifBlank { "第 ${chapters.size + 1} 章" }
            addChaptersWithSplit(chapters, bookId, order, title, text)
            order = chapters.size
        }

        if (chapters.isEmpty()) return emptyList()

        // 3. 完全没有章节结构的书：按固定长度兜底切分
        if (chapters.size == 1 && chapters[0].content.length > 5000 && rawSegments.size == 1) {
            val fullText = chapters[0].content
            val fallback = mutableListOf<Chapter>()
            var idx = 0
            var chunkNo = 1
            while (idx < fullText.length) {
                val end = minOf(idx + 5000, fullText.length)
                fallback.add(
                    Chapter(
                        bookId = bookId,
                        chapterOrder = fallback.size,
                        title = "第 $chunkNo 部分",
                        content = fullText.substring(idx, end)
                    )
                )
                chunkNo++
                idx = end
            }
            return fallback
        }

        return chapters
    }

    /** 超大章节按 MAX_CHAPTER_LENGTH 拆分，与 EpubParser/TXT 导入保持一致。 */
    private fun addChaptersWithSplit(
        chapters: MutableList<Chapter>,
        bookId: Int,
        startOrder: Int,
        title: String,
        content: String
    ) {
        if (content.length <= MAX_CHAPTER_LENGTH) {
            chapters.add(
                Chapter(
                    bookId = bookId,
                    chapterOrder = startOrder,
                    title = title,
                    content = content
                )
            )
            return
        }
        val parts = content.let { splitChapterText(it) }
        parts.forEachIndexed { index, part ->
            chapters.add(
                Chapter(
                    bookId = bookId,
                    chapterOrder = startOrder + index,
                    title = if (index == 0) title else "$title (续${index + 1})",
                    content = part
                )
            )
        }
    }

    private fun cleanHeadingText(raw: String): String {
        return cleanHtmlText(raw).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    /** 与 EpubParser.extractCleanTextFromHtml 同一套 HTML -> 纯文本逻辑。 */
    private fun cleanHtmlText(html: String): String {
        if (html.isBlank()) return ""
        var text = html
            .replace(Regex("""(?s)<head.*?>.*?</head>"""), "")
            .replace(Regex("""(?s)<script.*?>.*?</script>"""), "")
            .replace(Regex("""(?s)<style.*?>.*?</style>"""), "")
            .replace(Regex("""(?s)<!--.*?-->"""), "")
        text = text
            .replace(Regex("""(?i)<(?:p|div|br|h[1-6]|li|tr)[^>]*>"""), "\n")
            .replace(Regex("""(?i)</(?:p|div|h[1-6]|li|tr)>"""), "\n")
        text = text.replace(Regex("""<[^>]+>"""), "")
        text = unescapeHtml(text)
        return text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private fun unescapeHtml(input: String): String {
        var text = input
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        text = Regex("""&#(\d+);""").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null) code.toChar().toString() else match.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);""").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null) code.toChar().toString() else match.value
        }
        return text
    }

    // ------------------------------------------------------------------
    // 二进制读取工具
    // ------------------------------------------------------------------

    private fun readU16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun readI32(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readU64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            val b = if (offset + i < data.size) {
                (data[offset + i].toLong() and 0xFF)
            } else {
                0L
            }
            v = (v shl 8) or b
        }
        return v
    }
}
