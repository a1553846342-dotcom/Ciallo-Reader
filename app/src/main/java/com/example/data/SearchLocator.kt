package com.example.data

import com.example.ui.reader.NovelInlineImages

/**
 * 小说全文搜索的纯定位逻辑：MainViewModel（结果统计）与 ReaderScreen（跳转定位）
 * 共用同一份实现，保证「第 N 处出现」在两端口径一致 —— 任何一端单独改动都会被
 * NovelSearchFlowTest 抓住。
 *
 * 设计要点：不用字符偏移做跨章换算（ChapterMerger 的物理→逻辑偏移表基于空 content
 * 的 metadata，不可信），全部用「忽略大小写的第 N 处出现」在拼接后文本上直接数。
 */
object SearchLocator {

    /** 忽略大小写的出现次数。 */
    fun countOccurrences(text: String, query: String): Int {
        if (query.isBlank()) return 0
        var count = 0
        val visible = visibleText(text)
        var at = visible.indexOf(query, ignoreCase = true)
        while (at >= 0) {
            count++
            at = visible.indexOf(query, at + query.length, ignoreCase = true)
        }
        return count
    }

    /**
     * 合并后文本（逻辑章 formatted 文本 = 物理章 content 按顺序拼接 + 行首缩进）中
     * 关键词第 occurrence 处（0 起）的位置；不存在返回 -1。
     */
    fun nthOccurrence(text: String, query: String, occurrence: Int): Int {
        if (query.isBlank() || occurrence < 0) return -1
        val visible = visibleText(text)
        var at = visible.indexOf(query, ignoreCase = true)
        var i = 0
        while (at >= 0) {
            if (i == occurrence) return at
            i++
            at = visible.indexOf(query, at + query.length, ignoreCase = true)
        }
        return -1
    }

    /**
     * 搜索预览：剔除图片占位符、压平换行（原文段落换行会把关键词挤到
     * maxLines=2 的截断区之外 —— 用户看到"预览没有关键词"即此因），
     * 关键词位于预览中心（前后各留若干字）。
     * content 内 pos 处的匹配 → 干净预览；pos 传 -1 时在剔除后的文本里重新定位。
     */
    fun buildSnippet(content: String, query: String, pos: Int): String {
        val plain = visibleText(content)
        val at = if (pos >= 0 && pos + query.length <= plain.length && plain.regionMatches(pos, query, 0, query.length, true)) pos
            else plain.indexOf(query, ignoreCase = true)
        if (at < 0) return "..."
        val start = (at - 15).coerceAtLeast(0)
        val end = (at + query.length + 25).coerceAtMost(plain.length)
        return "..." + plain.substring(start, end).replace(Regex("\\s+"), " ") + "..."

    }

    /**
     * 由 LIKE 命中的物理章列表构建搜索结果行：每章只出一条，
     * occurrence = 同逻辑章内、按物理章顺序之前各章出现次数之和 + 本章内第几处。
     * MainViewModel.searchFullText 与测试共用 —— 两端口径单一来源。
     *
     * @param logicalIndexOf 物理章 order → 逻辑章 index（ChapterMerger 映射）
     * @param logicalTitleOf 逻辑章 index → 标题
     */
    fun buildResults(
        matchedChapters: List<com.example.data.Chapter>,
        query: String,
        logicalIndexOf: (Int) -> Int,
        logicalTitleOf: (Int) -> String?
    ): List<com.example.data.SearchResultItem> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResultItem>()
        val beforeCounts = HashMap<Int, Int>()
        for (chapter in matchedChapters.sortedBy { it.chapterOrder }) {
            val logicalIndex = logicalIndexOf(chapter.chapterOrder)
            val plain = visibleText(chapter.content)
            var at = plain.indexOf(query, ignoreCase = true)
            var occurrence = beforeCounts[logicalIndex] ?: 0
            while (at >= 0) {
                val start = (at - 15).coerceAtLeast(0)
                val end = (at + query.length + 25).coerceAtMost(plain.length)
                val snippet = "..." + plain.substring(start, end).replace(Regex("\\s+"), " ") + "..."
                results.add(SearchResultItem(logicalIndex, logicalTitleOf(logicalIndex) ?: chapter.title, snippet, occurrence++))
                if (results.size >= MAX_RESULTS) return results
                at = plain.indexOf(query, at + query.length, ignoreCase = true)
            }
            beforeCounts[logicalIndex] = occurrence
        }
        return results
    }

    // Same length as raw text: reader jumps retain their character offsets, but never count image paths.
    private fun visibleText(text: String): String = if (!NovelInlineImages.hasImages(text)) text
        else NovelInlineImages.TOKEN_REGEX.replace(text) { " ".repeat(it.value.length) }

    const val MAX_RESULTS = 1000
}
