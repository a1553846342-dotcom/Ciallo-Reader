package com.example.data

import com.example.ui.reader.NovelInlineImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小说全文搜索核心逻辑纯测试（MainViewModel.searchFullText 与 ReaderScreen 跳转
 * 共用的 SearchLocator）—— 对应用户实测的三类问题：
 * 1) 结果条目必须全部包含完整关键词（无"插"混入"插图"之类的残缺匹配）；
 * 2) occurrence「第 N 处出现」在渲染端拼接文本上定位必须与统计端一致，
 *    否则点进去没有关键词；
 * 3) snippet 中心必含关键词、剔除图片占位符噪声。
 *
 * 注：searchFullText 的竞态防护（旧查询 Job 取消 + 300ms 防抖）是 ViewModel 层
 * 协程模式，纯函数测试不覆盖；其正确性由代码路径 + 真机验收确认。
 */
class NovelSearchFlowTest {

    private val query = "插图"

    // 复刻用户场景：大章被拆"续N"后合并回同一逻辑章，匹配跨物理章分布
    private val chapters = listOf(
        Chapter(bookId = 1, chapterOrder = 0, title = "卷一", content = "月亮升起，故事开始。"),
        Chapter(bookId = 1, chapterOrder = 1, title = "卷二", content = "他说：「这里有插图。」然后翻页。"),
        Chapter(bookId = 1, chapterOrder = 2, title = "卷三", content = "第一处插图在前，中间隔很多字，第二处插图在后。"),
        Chapter(bookId = 1, chapterOrder = 3, title = "卷三 (续1)", content = "续页里还有一处插图。"),
        Chapter(bookId = 1, chapterOrder = 4, title = "卷四", content = "这里只有插字。"),
    )

    // 续章合并映射：卷三(2)与续1(3)同属逻辑章 2；其余自映射
    private val logicalIndexOf = { order: Int -> if (order >= 2) 2 else order }
    private val logicalTitleOf = { idx: Int -> chapters.firstOrNull { it.chapterOrder == if (idx == 2) 2 else idx }?.title }

    /** 渲染端基准：逻辑章正文 = 物理章 content 按顺序拼接（loadActiveChaptersContent 同规则）。 */
    private val mergedVolume3 = chapters[2].content + chapters[3].content

    @Test
    fun resultsContainQueryWithCorrectOccurrenceSequence() {
        // 只喂 LIKE 会命中的章（卷一卷四无"插图"，不会出现在 matchedChapters）
        val matched = listOf(chapters[1], chapters[2], chapters[3])
        val results = SearchLocator.buildResults(matched, query, logicalIndexOf, logicalTitleOf)

        assertEquals("卷二 1 处 + 卷三组 3 处 = 4 条", 4, results.size)
        // 条目归属：卷二逻辑章 1 一条，卷三组逻辑章 2 三条
        assertEquals(listOf(1, 2, 2, 2), results.map { it.chapterIndex })
        // occurrence 是「逻辑章内第 N 处」：卷二 0；卷三组内 0、1、2（卷三 2 处 + 续1 1 处）
        assertEquals(
            "occurrence 序列必须与逻辑章文本出现顺序一致",
            listOf(0, 0, 1, 2), results.map { it.occurrence }
        )
        // 标题取逻辑章标题
        assertTrue(results.all { it.chapterTitle == "卷二" || it.chapterTitle == "卷三" })
    }

    @Test
    fun jumpTargetLandsOnCorrectOccurrence() {
        val matched = listOf(chapters[1], chapters[2], chapters[3])
        val results = SearchLocator.buildResults(matched, query, logicalIndexOf, logicalTitleOf)

        // 渲染端对拼接文本逐条定位：第 N 处必须是完整关键词，且上下文与来源物理章一致
        val expectations = listOf(
            "这里有插图",        // 卷二 occurrence 0
            "第一处插图在前",     // 卷三组 occurrence 0（卷三第 1 处）
            "二处插图在后",       // 卷三组 occurrence 1（卷三第 2 处）
            "一处插图",          // 卷三组 occurrence 2（续1）
        )
        results.forEachIndexed { i, r ->
            val merged = if (r.chapterIndex == 2) mergedVolume3 else chapters[1].content
            val pos = SearchLocator.nthOccurrence(merged, query, r.occurrence)
            assertTrue("第 ${r.occurrence} 处在拼接文本上定位失败", pos >= 0)
            val context = merged.substring(
                (pos - 6).coerceAtLeast(0),
                (pos + query.length + 6).coerceAtMost(merged.length)
            )
            assertTrue(
                "occurrence=${r.occurrence} 定位到的上下文不对: '$context'",
                context.contains(expectations[i])
            )
        }
    }

    @Test
    fun snippetCenterContainsQueryAndNoTokenNoise() {
        val withToken = "前文有图[IMG:/data/files/epub_images/3/img_1_7.png|800|600]中间插图在后文。"
        val snippet = SearchLocator.buildSnippet(withToken, query, withToken.indexOf(query))
        assertFalse("snippet 含图片占位符噪声", snippet.contains("[IMG:"))
        assertFalse("snippet 含被剔除图后的空位残片", snippet.contains("|800|600]"))
        // 中心（去掉省略号与前后文后）必须出现完整关键词
        val inner = snippet.trimStart('.').trimEnd('.')
        assertTrue("snippet 中心未包含完整关键词: $snippet", inner.contains(query))
    }

    @Test
    fun caseInsensitiveMatching() {
        val ch = Chapter(bookId = 1, chapterOrder = 0, title = "A", content = "有 ABC 混排 abc 内容。")
        val results = SearchLocator.buildResults(listOf(ch), "abc", { it }, { "A" })
        assertEquals("大小写变体应各出一条", 2, results.size)
        assertEquals(listOf(0, 1), results.map { it.occurrence })
    }

    @Test
    fun nthOccurrenceOutOfRangeReturnsMinusOne() {
        assertEquals(-1, SearchLocator.nthOccurrence("只有一处插图", query, 1))
        assertEquals(-1, SearchLocator.nthOccurrence("没有目标词", query, 0))
        assertEquals(0, SearchLocator.nthOccurrence("插图在前", query, 0))
    }

    @Test
    fun tokenTextDoesNotBreakCounting() {
        // 图片路径本身不含查询词；占位符存在时计数与剔除后一致
        val text = "第一处插图[IMG:/a/b.png|1|1]第二处插图"
        val plain = NovelInlineImages.stripTokens(text)
        assertEquals(
            SearchLocator.countOccurrences(text, query),
            SearchLocator.countOccurrences(plain, query)
        )
        // plain = "第一处插图第二处插图"：两处"插图"分别在 3 与 8
        assertEquals(8, SearchLocator.nthOccurrence(plain, query, 1))
    }
}
