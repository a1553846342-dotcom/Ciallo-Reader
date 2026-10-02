package com.example.data.favorite

import com.example.source.ComicChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我喜欢的」+ 阅读进度的核心纯函数单测：
 * 阅读顺序归一化（正序/倒序/缺章）、续读定位、已读判定、新章节判定。
 */
class ComicReadingLogicTest {

    private fun ch(id: String, title: String, order: Float = 0f) =
        ComicChapter(id = id, title = title, order = order)

    @Test
    fun `ordered 把倒序列表归一化成阅读顺序`() {
        val chapters = listOf(
            ch("c3", "第 3 话", order = 3f),
            ch("c1", "第 1 话", order = 1f),
            ch("c2", "第 2 话", order = 2f),
        )
        val seq = ComicReadingLogic.ordered(chapters)
        assertEquals(listOf("c1", "c2", "c3"), seq.map { it.chapter.id })
        assertEquals(listOf(0, 1, 2), seq.map { it.order })
    }

    @Test
    fun `源没给 order 时退化为列表下标`() {
        val chapters = listOf(ch("a", "第 1 话"), ch("b", "第 2 话"))
        val seq = ComicReadingLogic.ordered(chapters)
        assertEquals(listOf("a", "b"), seq.map { it.chapter.id })
    }

    @Test
    fun `缺章时下一话按源顺序索引计算`() {
        // order 1、2、5（中间 3、4 被删）——下一话必须是 5 而不是"下标+1"
        val chapters = listOf(
            ch("c5", "第 5 话", order = 5f),
            ch("c2", "第 2 话", order = 2f),
            ch("c1", "第 1 话", order = 1f),
        )
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c",
            lastChapterId = "c2", lastChapterIndex = 1, lastPageIndex = 0, lastPageCount = 10,
        )
        val target = ComicReadingLogic.resolveContinue(
            chapters = chapters,
            states = mapOf("c2" to ChapterReadState.READ),
            progress = progress,
        )
        assertTrue(target is ComicReadingLogic.ContinueTarget.Next)
        assertEquals(2, (target as ComicReadingLogic.ContinueTarget.Next).chapterIndex)
        assertEquals("c5", ComicReadingLogic.ordered(chapters)[target.chapterIndex].chapter.id)
    }

    @Test
    fun `未读完回到上次章节与页码`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f))
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c",
            lastChapterId = "c1", lastChapterIndex = 0, lastPageIndex = 7, lastPageCount = 20,
        )
        val target = ComicReadingLogic.resolveContinue(chapters, mapOf("c1" to ChapterReadState.READING), progress)
        assertTrue(target is ComicReadingLogic.ContinueTarget.Resume)
        val resume = target as ComicReadingLogic.ContinueTarget.Resume
        assertEquals(0, resume.chapterIndex)
        assertEquals(7, resume.pageIndex)
        assertTrue(ComicReadingLogic.continueLabel(target, chapters).contains("第8页"))
    }

    @Test
    fun `读到最后一话后返回已读到最新`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f))
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c",
            lastChapterId = "c2", lastChapterIndex = 1, lastPageIndex = 9, lastPageCount = 10,
        )
        val target = ComicReadingLogic.resolveContinue(chapters, mapOf("c2" to ChapterReadState.READ), progress)
        assertEquals(ComicReadingLogic.ContinueTarget.UpToDate, target)
        assertEquals("已读到最新", ComicReadingLogic.continueLabel(target, chapters))
    }

    @Test
    fun `没读过则是开始阅读`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f))
        val target = ComicReadingLogic.resolveContinue(chapters, emptyMap(), null)
        assertEquals(ComicReadingLogic.ContinueTarget.Start, target)
        assertEquals("开始阅读", ComicReadingLogic.continueLabel(target, chapters))
    }

    @Test
    fun `上次章节被源删除时退化到第一条未读`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f))
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c",
            lastChapterId = "gone", lastChapterIndex = 0, lastPageIndex = 0, lastPageCount = 0,
        )
        val target = ComicReadingLogic.resolveContinue(
            chapters, mapOf("c1" to ChapterReadState.READ), progress
        )
        assertTrue(target is ComicReadingLogic.ContinueTarget.Next)
        assertEquals(1, (target as ComicReadingLogic.ContinueTarget.Next).chapterIndex)
    }

    @Test
    fun `已读判定为最后一页或进度九成`() {
        assertTrue(ComicReadingLogic.isFinished(pageIndex = 9, pageCount = 10))
        assertTrue(ComicReadingLogic.isFinished(pageIndex = 8, pageCount = 10)) // 9/10 = 90%
        assertTrue(!ComicReadingLogic.isFinished(pageIndex = 6, pageCount = 10))
        assertTrue(!ComicReadingLogic.isFinished(pageIndex = 0, pageCount = 0))
    }

    @Test
    fun `新章节按快照 id 之后的部分判定`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f), ch("c3", "第 3 话", 3f))
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c", seenTopChapterId = "c1", seenChapterCount = 1
        )
        assertEquals(setOf("c2", "c3"), ComicReadingLogic.newChapterIds(chapters, progress))
    }

    @Test
    fun `快照失效时按章节数增量兜底`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f), ch("c3", "第 3 话", 3f))
        val progress = ComicProgressEntity(
            sourceId = "s", comicId = "c", seenTopChapterId = "gone", seenChapterCount = 2
        )
        assertEquals(setOf("c3"), ComicReadingLogic.newChapterIds(chapters, progress))
    }

    @Test
    fun `话数解析用于继续阅读文案`() {
        assertEquals(12, ComicReadingLogic.chapterNumber("第 12 话"))
        assertEquals(12, ComicReadingLogic.chapterNumber("第12话"))
        assertEquals(7, ComicReadingLogic.chapterNumber("Chapter 7"))
        assertEquals(null, ComicReadingLogic.chapterNumber("番外"))
    }

    @Test
    fun `更新检测 话数相同不算新话（id 不稳定源）`() {
        // 有的源每次拉取 chapterId 都不同：id 变了但话数没涨 = 同一话，不算更新
        val top = ComicReadingLogic.ordered(listOf(ch("c12-v2", "第 12 话"))).last()
        assertTrue(
            !ComicReadingLogic.isNewChapterObserved(
                oldLatestId = "c12-v1", oldLatestTitle = "第 12 话", newLatest = top,
            )
        )
    }

    @Test
    fun `更新检测 话数涨了算新话`() {
        val top = ComicReadingLogic.ordered(listOf(ch("c13", "第 13 话"))).last()
        assertTrue(
            ComicReadingLogic.isNewChapterObserved(
                oldLatestId = "c12", oldLatestTitle = "第 12 话", newLatest = top,
            )
        )
    }

    @Test
    fun `更新检测 id 相同不算新话`() {
        val top = ComicReadingLogic.ordered(listOf(ch("c12", "第 12 话"))).last()
        assertTrue(
            !ComicReadingLogic.isNewChapterObserved(
                oldLatestId = "c12", oldLatestTitle = "第 12 话", newLatest = top,
            )
        )
    }

    @Test
    fun `更新检测 话数解析不到时退回 id 比较`() {
        // 新旧标题都解析不出话数：id 不同只能当更新（宁可误报不可漏报）
        val top = ComicReadingLogic.ordered(listOf(ch("x2", "番外"))).last()
        assertTrue(
            ComicReadingLogic.isNewChapterObserved(
                oldLatestId = "x1", oldLatestTitle = "番外篇", newLatest = top,
            )
        )
    }

    @Test
    fun `更新检测 首次观察与空章节`() {
        // 从没抓到过快照 → 首次观察到算"有"（要推进更新时间）
        val top = ComicReadingLogic.ordered(listOf(ch("c1", "第 1 话"))).last()
        assertTrue(
            ComicReadingLogic.isNewChapterObserved(
                oldLatestId = null, oldLatestTitle = null, newLatest = top,
            )
        )
        // 源没返回章节 → 算没有
        assertTrue(
            !ComicReadingLogic.isNewChapterObserved(
                oldLatestId = "c1", oldLatestTitle = "第 1 话", newLatest = null,
            )
        )
    }

    @Test
    fun `未读话数与已读占比`() {
        val chapters = listOf(ch("c1", "第 1 话", 1f), ch("c2", "第 2 话", 2f), ch("c3", "第 3 话", 3f))
        val states = mapOf("c1" to ChapterReadState.READ, "c2" to ChapterReadState.READING)
        assertEquals(2, ComicReadingLogic.unreadCount(chapters, states))
        assertEquals(1f / 3f, ComicReadingLogic.readRatio(chapters, states), 1e-5f)
    }
}
