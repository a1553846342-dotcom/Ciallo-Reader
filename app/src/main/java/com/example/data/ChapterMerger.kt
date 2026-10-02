package com.example.data

/**
 * Builds logical chapters from DB chapters that were split into "title (续N)" parts.
 * The reader sees one merged chapter; the database is left untouched.
 */
private val CONTINUATION_TITLE = Regex("""^(.+?)\s*\((?:续|续[0-9]+)\)\s*$""")

data class LogicalChapterBook(
    val chapters: List<Chapter>,
    val physicalToLogical: IntArray,
    val physicalToLogicalOffset: IntArray,
    val logicalToPhysicalOrders: Array<IntArray>
) {
    fun logicalIndexOf(physicalOrder: Int): Int =
        if (physicalOrder in physicalToLogical.indices) physicalToLogical[physicalOrder] else physicalOrder

    fun logicalOffsetOf(physicalOrder: Int, physicalOffset: Int): Int =
        if (physicalOrder in physicalToLogicalOffset.indices) {
            physicalToLogicalOffset[physicalOrder] + physicalOffset
        } else {
            physicalOffset
        }

    fun physicalIndexFor(logicalIndex: Int): Int {
        if (logicalIndex in logicalToPhysicalOrders.indices) {
            return logicalToPhysicalOrders[logicalIndex].firstOrNull() ?: logicalIndex
        }
        return logicalIndex
    }
}

object ChapterMerger {
    fun cleanSplitTitle(title: String): String =
        CONTINUATION_TITLE.matchEntire(title.trim())?.groupValues?.get(1) ?: title

    fun buildLogicalChapters(physical: List<Chapter>): LogicalChapterBook {
        val byOrder = physical.associateBy { it.chapterOrder }
        val maxOrder = physical.maxOfOrNull { it.chapterOrder } ?: -1
        val physToLog = IntArray(maxOrder + 1) { it }
        val physOffset = IntArray(maxOrder + 1)
        val logToPhys = mutableListOf<IntArray>()
        val logical = mutableListOf<Chapter>()

        var parts = mutableListOf<Int>()
        var baseTitle: String? = null
        val buffer = StringBuilder()

        fun flush() {
            if (parts.isEmpty()) return
            val first = byOrder.getValue(parts.first())
            val last = byOrder.getValue(parts.last())
            logical.add(
                Chapter(
                    bookId = first.bookId,
                    chapterOrder = logical.size,
                    title = baseTitle ?: first.title,
                    content = buffer.toString(),
                    startCharIndex = first.startCharIndex,
                    endCharIndex = last.endCharIndex
                )
            )
            logToPhys.add(parts.toIntArray())
            parts = mutableListOf()
            buffer.setLength(0)
            baseTitle = null
        }

        for (ch in physical) {
            val order = ch.chapterOrder
            val m = CONTINUATION_TITLE.matchEntire(ch.title.trim())
            val isContinuation = m != null && baseTitle == m.groupValues[1] && parts.isNotEmpty() && parts.size < 4
            if (isContinuation) {
                physOffset[order] = buffer.length
                buffer.append(ch.content)
                parts.add(order)
                physToLog[order] = logical.size
            } else {
                flush()
                baseTitle = cleanSplitTitle(ch.title.trim())
                buffer.append(ch.content)
                parts.add(order)
                physOffset[order] = 0
                physToLog[order] = logical.size
            }
        }
        flush()

        return LogicalChapterBook(logical, physToLog, physOffset, logToPhys.toTypedArray())
    }
}
