package com.example.data.favorite

import com.example.source.ComicChapter

/**
 * 漫画阅读顺序 / 续读定位 / 章节状态的**纯函数**集合（无 Android 依赖、可单测）。
 *
 * 三条容易踩坑的规则在这里一次性收敛：
 * 1. 「下一话」必须按**来源自己的章节顺序索引**计算，而不是按列表下标——
 *    源可能正序也可能倒序返回，还可能缺章（order 不连续、中间被删）。
 * 2. 已读判定：读到最后一页 **或** 进度 ≥ 90%。
 * 3. 「新章节」= 上次进入章节列表后新增的章节（按阅读顺序比快照 id 更大）。
 */

/** 归一化到「阅读顺序」后的章节：携带原始列表下标、排序键、以及显示用的话数。 */
data class OrderedChapter(
    /** 在源返回的原始列表中的下标 */
    val rawIndex: Int,
    /** 在阅读顺序（升序）中的序号：0 起，缺章也连续 */
    val order: Int,
    val chapter: ComicChapter,
    val orderKey: Float,
    /** 从标题里解析出的话数（用于「继续阅读 · 第12话」文案）；解析不到则为序号+1 */
    val displayNumber: Int,
)

object ComicReadingLogic {

    /**
     * 章节排序键：源给的 [ComicChapter.order] 优先（它能表达缺章与乱序）；
     * 源没给（恒为 0）时退化为列表下标，保证正序/倒序列表都能排。
     */
    fun orderKey(chapter: ComicChapter, rawIndex: Int): Float =
        if (chapter.order.isFinite() && chapter.order != 0f) chapter.order else rawIndex.toFloat()

    /** 把源返回的任意顺序列表归一化成阅读顺序（升序）。 */
    fun ordered(chapters: List<ComicChapter>): List<OrderedChapter> {
        val decorated = chapters.mapIndexed { index, chapter -> index to chapter }
        val sorted = decorated.sortedWith(
            compareBy<Pair<Int, ComicChapter>> { orderKey(it.second, it.first) }
                .thenBy { it.first }
        )
        return sorted.mapIndexed { order, (rawIndex, chapter) ->
            OrderedChapter(
                rawIndex = rawIndex,
                order = order,
                chapter = chapter,
                orderKey = orderKey(chapter, rawIndex),
                displayNumber = chapterNumber(chapter.title) ?: (order + 1),
            )
        }
    }

    /** 从「第 12 话 / 第12话 / 12話 / Chapter 12」等标题里取话数。 */
    fun chapterNumber(title: String): Int? {
        val m = Regex("""(\d+(?:\.\d+)?)""").find(title) ?: return null
        return m.groupValues[1].toFloatOrNull()?.toInt()
    }

    /**
     * 更新检测：这次抓到的最新一话，是否真的算「有新话」。
     *
     * ⚠️ 不能只比 chapterId：有的源每次拉取都会生成不同的 id（URL 带时间戳/hash），
     * 裸 id 比较会把"同一话"判成"有更新"——角标永远亮着、「最近更新」排序永远在抖
     * （用户报的"检测是否新更新的算法有问题"）。话数归一化：两边都能从标题解析出
     * 话数且相等 → 是同一话，不算更新；任一边解析不到话数 → 退回纯 id 比较
     * （无法证伪，宁可报更新也别漏报）。
     *
     * @param oldLatestId  收藏快照里的最新章节 id（null = 从没抓到过 → 首次观察即"有"）
     * @param oldLatestTitle 收藏快照里的最新章节标题
     * @param newLatest    这次抓到的最新一话（null = 源没返回章节 → 算没有）
     */
    fun isNewChapterObserved(
        oldLatestId: String?,
        oldLatestTitle: String?,
        newLatest: OrderedChapter?,
    ): Boolean {
        newLatest ?: return false
        val oldId = oldLatestId ?: return true
        if (newLatest.chapter.id == oldId) return false
        val newNum = chapterDecimal(newLatest.chapter.title)
        val oldNum = oldLatestTitle?.let { chapterDecimal(it) }
        if (newNum != null && oldNum != null && newNum.compareTo(oldNum) == 0) return false
        return true
    }

    private fun chapterDecimal(title: String): java.math.BigDecimal? =
        Regex("""(\d+(?:\.\d+)?)""").find(title)?.value?.takeIf { it.length<=64 }?.toBigDecimalOrNull()

    /** 已读判定：最后一页 或 进度 ≥ 90%。 */
    fun isFinished(pageIndex: Int, pageCount: Int): Boolean {
        if (pageCount <= 0) return false
        if (pageIndex >= pageCount - 1) return true
        return (pageIndex + 1).toFloat() / pageCount.toFloat() >= 0.9f
    }

    /** 续读目标（决定「继续阅读」按钮的文案与落点）。 */
    sealed interface ContinueTarget {
        /** 从未读过 */
        data object Start : ContinueTarget

        /** 有进度：精确回到上次章节与页码 */
        data class Resume(val chapterIndex: Int, val pageIndex: Int) : ContinueTarget

        /** 上次那一话已读完：跳下一话第 1 页 */
        data class Next(val chapterIndex: Int) : ContinueTarget

        /** 已读到最新 */
        data object UpToDate : ContinueTarget
    }

    /**
     * 计算续读目标。
     * @param chapters 源返回的原始章节列表（顺序任意）
     * @param states   章节状态表（chapterId → 状态）
     * @param progress 漫画级进度（可为 null = 没读过）
     */
    fun resolveContinue(
        chapters: List<ComicChapter>,
        states: Map<String, ChapterReadState>,
        progress: ComicProgressEntity?,
    ): ContinueTarget {
        val seq = ordered(chapters)
        if (seq.isEmpty()) return ContinueTarget.Start
        val lastId = progress?.lastChapterId
        if (lastId == null) return ContinueTarget.Start

        // 上次读的章节可能已被源删除 → 退化成「第一条未读」，读不到就从头
        val lastOrder = seq.indexOfFirst { it.chapter.id == lastId }
        if (lastOrder < 0) {
            val firstUnread = seq.firstOrNull { states[it.chapter.id] != ChapterReadState.READ }
            return if (firstUnread != null) ContinueTarget.Next(firstUnread.order) else ContinueTarget.UpToDate
        }
        val lastState = states[lastId] ?: ChapterReadState.UNREAD
        val finishedByPage = isFinished(progress.lastPageIndex, progress.lastPageCount)
        return when {
            lastState == ChapterReadState.READ || finishedByPage -> {
                val next = seq.getOrNull(lastOrder + 1)
                if (next != null) ContinueTarget.Next(next.order) else ContinueTarget.UpToDate
            }
            else -> ContinueTarget.Resume(lastOrder, progress.lastPageIndex.coerceAtLeast(0))
        }
    }

    /** 「继续阅读」按钮文案（带上下文）。 */
    fun continueLabel(target: ContinueTarget, chapters: List<ComicChapter>): String {
        val seq = ordered(chapters)
        fun number(index: Int): Int = seq.getOrNull(index)?.displayNumber ?: (index + 1)
        return when (target) {
            ContinueTarget.Start -> "开始阅读"
            is ContinueTarget.Resume -> {
                val page = (target.pageIndex + 1).coerceAtLeast(1)
                "继续阅读 · 第${number(target.chapterIndex)}话 · 第${page}页"
            }
            is ContinueTarget.Next -> "继续阅读 · 第${number(target.chapterIndex)}话"
            ContinueTarget.UpToDate -> "已读到最新"
        }
    }

    /**
     * 上次进入章节列表后新增的章节 id（用于「新」小红点）。
     * 优先按快照 id 的阅读顺序比较；快照 id 已失效时按章节数增量兜底取末尾若干条。
     */
    fun newChapterIds(
        chapters: List<ComicChapter>,
        progress: ComicProgressEntity?,
    ): Set<String> {
        if (progress == null) return emptySet()
        val seq = ordered(chapters)
        val topId = progress.seenTopChapterId
        if (topId != null) {
            val idx = seq.indexOfFirst { it.chapter.id == topId }
            if (idx >= 0) return seq.subList(idx + 1, seq.size).map { it.chapter.id }.toSet()
        }
        val delta = seq.size - progress.seenChapterCount
        if (delta > 0) return seq.takeLast(delta.coerceAtMost(seq.size)).map { it.chapter.id }.toSet()
        return emptySet()
    }

    /** 已读章节占比（卡片底部细进度条）。 */
    fun readRatio(chapters: List<ComicChapter>, states: Map<String, ChapterReadState>): Float {
        if (chapters.isEmpty()) return 0f
        val read = chapters.count { states[it.id] == ChapterReadState.READ }
        return read.toFloat() / chapters.size.toFloat()
    }

    /** 未读话数（卡片「未读 K 话」）。 */
    fun unreadCount(chapters: List<ComicChapter>, states: Map<String, ChapterReadState>): Int =
        chapters.count { states[it.id] != ChapterReadState.READ }
}
