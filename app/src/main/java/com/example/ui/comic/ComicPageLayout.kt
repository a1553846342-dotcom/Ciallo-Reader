package com.example.ui.comic

/**
 * 漫画页面布局引擎（纯逻辑，可测试）。
 *
 * 职责：把「原始页列表 + 图片内在尺寸 + 配置」变换为「跨页(spread)列表」，
 * 处理：双页配对、宽页拆分（左右顺序随阅读方向）、临时合页、RTL 排列、首页单独显示。
 * 所有变换都是显示层行为，不修改原始数据。
 */

/** 一张漫画图片的引用：本地文件或在线 URL */
sealed interface ComicPageRef {
    val id: String
    data class Local(override val id: String, val path: String) : ComicPageRef
    data class Remote(
        override val id: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val referer: String? = null
    ) : ComicPageRef
}

data class SizeI(
    val width: Int,
    val height: Int,
    /** 中央装订缝探测结论：null=未探测（在线页/未加载），true/false=已探测有无 */
    val gutter: Boolean? = null,
    /** 缝中心归一化 x（第 19 条：拆分位置精确落在装订缝上）；仅 gutter=true 时有效 */
    val gutterPos: Float? = null,
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 0f
}

/** 拆分半页 */
enum class ComicSplitHalf { FULL, LEFT, RIGHT }

/** 一个显示槽位：完整页或拆分后的半页 */
data class ComicSlot(
    val ref: ComicPageRef,
    val rawIndex: Int,
    val half: ComicSplitHalf = ComicSplitHalf.FULL,
)

/** 一个跨页：1 或 2 个槽位（slots 为阅读顺序） */
data class ComicSpread(
    val index: Int,
    val slots: List<ComicSlot>,
) {
    val isDouble: Boolean get() = slots.size == 2
    val firstRawIndex: Int get() = slots.first().rawIndex
}

data class ComicLayout(
    val spreads: List<ComicSpread>,
    /** 原始页 → 首个所在 spread 索引 */
    val rawToSpread: Map<Int, Int>,
) {
    val spreadCount: Int get() = spreads.size

    fun spreadOfRawPage(raw: Int): Int = rawToSpread[raw] ?: 0

    fun spreadIndexOf(raw: Int): Int = spreads.indexOfFirst { s -> s.slots.any { it.rawIndex == raw } }
        .takeIf { it >= 0 } ?: 0
}

/**
 * 垂直阅读策略（第 6 条：条漫与无缝滚动必须本质不同，不共用逻辑只改开关）。
 *
 * - [Webtoon]（条漫）：允许用户设置页面间距（页与页留白），支持磁吸到页边界
 *   （可关闭，官方 SnapFlingBehavior 同款 item-snap），预加载窗口常规；
 * - [Continuous]（无缝滚动）：强制页间距 0（连续拼接），无磁吸（自由滚动），
 *   更宽的预加载窗口保证"完全连续无停顿"的观感，进度按累计像素高度计。
 */
sealed class ComicScrollStrategy {
    /** 页与页间距（dp） */
    abstract val spacingDp: Float
    /** 松手是否吸附到页边界 */
    abstract val snapToPage: Boolean
    /** 视口外双向预加载页数（保证滚动连续性） */
    abstract val prefetchWindow: Int
    /** 进度语义：条漫=页粒度；无缝=像素高度粒度 */
    abstract val pixelProgress: Boolean

    data class Webtoon(private val config: ComicReaderConfig) : ComicScrollStrategy() {
        override val spacingDp: Float get() = config.pageSpacingDp
        override val snapToPage: Boolean get() = config.webtoonSnap
        // 在线阅读冷启动：窗口 2→4，提前两页取图/解码，滚动追图概率大降；
        // 驻留预算按优先级序自动丢尾部，不会无界涨内存
        override val prefetchWindow: Int get() = 4
        override val pixelProgress: Boolean get() = false
    }

    data class Continuous(private val config: ComicReaderConfig) : ComicScrollStrategy() {
        // 无缝滚动强制 0 间距：用户间距设置在此模式无效（连续性是本模式定义）
        override val spacingDp: Float get() = 0f
        override val snapToPage: Boolean get() = false
        override val prefetchWindow: Int get() = 4
        override val pixelProgress: Boolean get() = true
    }

    companion object {
        fun forConfig(config: ComicReaderConfig): ComicScrollStrategy = when (config.mode) {
            ComicMode.CONTINUOUS -> Continuous(config)
            else -> Webtoon(config)   // WEBTOON 及其余垂直形态
        }
    }
}

/**
 * 垂直模式前瞻预载索引（第 6 条 prefetchWindow 接线），返回 **预载驻留优先级序**：
 * 当前页 → 前瞻 +1..+window（近→远）→ 上一页，全部钳制到 [0, count)——无缝滚动
 * （窗口 4）比条漫（窗口 2）更远地提前解码+处理；窗口驻留预算超限时按此序丢尾部
 * （远前瞻先失去驻留，当前页与近前瞻最后失守——第六轮 Agent C 补审 F1 修正：
 * 旧序 [上一页, 当前, 前瞻…] 在预算溢出时会最先丢掉远前瞻，恰好削弱无缝滚动
 * 的定义性预载特性）。抽纯函数供单测钉死边界行为。
 */
internal fun verticalPreloadIndices(current: Int, window: Int, count: Int): List<Int> {
    if (count <= 0) return emptyList()
    val fwd = (1..window).map { current + it }.filter { it in 0 until count }
    val back = listOf(current - 1).filter { it in 0 until count }
    val head = listOf(current).filter { it in 0 until count }
    return head + fwd + back
}

/**
 * 引擎/布局切换淡入 alpha（补1）：240ms smoothstep（≈FastOutSlowIn），起始 0.35
 * 不全黑——挂钟驱动动画的进度函数抽纯函数供单测。
 */
internal fun engineFadeAlpha(elapsedMs: Long, durationMs: Long = 240): Float {
    val t = if (durationMs <= 0) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    return 0.35f + 0.65f * (t * t * (3f - 2f * t))
}

object ComicPageLayout {

    /** 宽高比超过该值进入「疑似跨页」区间（需 gutter 佐证） */
    const val WIDE_ASPECT = 1.35f

    /** 宽高比超过该值无条件视为跨页扫描图（KCC BISECT_THRESHOLD 经验值） */
    const val HARD_WIDE_ASPECT = 1.8f

    /**
     * 宽页拆分判定（二次精修：中央装订缝佐证，降低误拆/漏拆）：
     * - aspect ≥ 1.8 → 无条件拆（超宽必是跨页）；
     * - 1.35 ≤ aspect < 1.8 → 本地页有 gutter 探测结果时按结果定（有缝 → 拆，无缝 → 不拆，
     *   避免把宽幅单页插画误拆）；无探测结果（在线页）保持旧版行为直接拆。
     */
    fun isWidePage(size: SizeI?): Boolean {
        if (size == null) return false
        val a = size.aspect
        if (a < WIDE_ASPECT) return false
        if (a >= HARD_WIDE_ASPECT) return true
        return size.gutter ?: true
    }

    data class Unit(
        val slot: ComicSlot,
        val fromSplit: Boolean,
        val wideFull: Boolean,   // 未拆分的宽页（双页模式下应独占）
    )

    fun build(
        pages: List<ComicPageRef>,
        sizes: Map<String, SizeI>,
        config: ComicReaderConfig,
        bookState: ComicBookState = ComicBookState(),
    ): ComicLayout {
        if (pages.isEmpty()) return ComicLayout(emptyList(), emptyMap())
        val rtl = config.direction == ComicDirection.RTL
        val double = config.mode == ComicMode.DOUBLE

        // 1. 展开为显示单元：宽页按需拆分（记录原始页索引，合页锚点按原始索引判断）
        data class RawUnitGroup(val rawIndex: Int, val units: List<Unit>)

        val rawGroups = mutableListOf<RawUnitGroup>()
        pages.forEachIndexed { idx, page ->
            val size = sizes[page.id]
            val isWide = isWidePage(size)
            val anchorMerged = bookState.mergeAnchors.contains(idx)
            if (config.splitWide && isWide && !anchorMerged) {
                // 拆分顺序：默认 RTL 先右半边；splitReverse 反转手动纠正扫描顺序。
                // 拆分半页各自成组，参与后续配对/合页流程。
                val rightFirst = if (config.splitReverse) !rtl else rtl
                val left = Unit(ComicSlot(page, idx, ComicSplitHalf.LEFT), fromSplit = true, wideFull = false)
                val right = Unit(ComicSlot(page, idx, ComicSplitHalf.RIGHT), fromSplit = true, wideFull = false)
                val ordered = if (rightFirst) listOf(right, left) else listOf(left, right)
                ordered.forEach { rawGroups.add(RawUnitGroup(idx, listOf(it))) }
            } else {
                rawGroups.add(RawUnitGroup(idx, listOf(Unit(ComicSlot(page, idx), fromSplit = false, wideFull = isWide))))
            }
        }

        // 2. 临时合页锚点 → 两页组成一个 spread（显示层行为，不破坏原始数据）
        data class PendingSpread(val unitSlots: List<Unit>)

        val pending = mutableListOf<PendingSpread>()
        var i = 0
        while (i < rawGroups.size) {
            val group = rawGroups[i]
            val isAnchor = bookState.mergeAnchors.contains(group.rawIndex)
            val groupUnits = group.units
            if (isAnchor && i + 1 < rawGroups.size && groupUnits.size == 1 && !groupUnits[0].fromSplit) {
                val nextUnits = rawGroups[i + 1].units
                if (nextUnits.size == 1 && !nextUnits[0].fromSplit) {
                    pending.add(PendingSpread(groupUnits + nextUnits))
                    i += 2
                    continue
                }
            }
            pending.add(PendingSpread(groupUnits))
            i++
        }

        // 3. 分组为 spread
        val groups = mutableListOf<List<Unit>>()
        var cursor = 0
        val firstAlone = config.doubleFirstAlone && double
        if (firstAlone && pending.isNotEmpty()) {
            groups.add(pending[0].unitSlots)
            cursor = 1
        }
        while (cursor < pending.size) {
            val cur = pending[cursor]
            if (!double || cur.unitSlots.size > 1 || cur.unitSlots[0].wideFull) {
                // 单页模式 / 已是合页组 / 未拆分宽页 → 独立 spread
                groups.add(cur.unitSlots)
                cursor++
            } else {
                val next = pending.getOrNull(cursor + 1)
                if (next != null && next.unitSlots.size == 1 && !next.unitSlots[0].wideFull) {
                    groups.add(cur.unitSlots + next.unitSlots)
                    cursor += 2
                } else {
                    groups.add(cur.unitSlots)
                    cursor++
                }
            }
        }

        val spreadList = groups.mapIndexed { si, units ->
            ComicSpread(index = si, slots = units.map { it.slot })
        }
        val rawMap = mutableMapOf<Int, Int>()
        spreadList.forEach { s ->
            s.slots.forEach { slot ->
                rawMap.putIfAbsent(slot.rawIndex, s.index)
            }
        }
        return ComicLayout(spreadList, rawMap)
    }
}
