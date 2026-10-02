package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ui.reader.NovelInlineImages
import com.example.ui.reader.buildAnnotatedWithImages
import com.example.ui.theme.AppFonts
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ANR-free pagination layer backed by real text layout.
 *
 * Every page break is a real line boundary produced by [Paragraph] (the same layout
 * engine Compose's Text uses), so a paragraph split across two pages never loses a line
 * and the last line of a page is always fully visible. Measurement runs on
 * [Dispatchers.Default]; very large chapters are split into ~40k-char chunks, each chunk
 * is measured independently and pages are appended as chunks finish so the first pages
 * appear almost immediately.
 */
object ReaderPaginationCache {
    private val lock = Any()
    private val entries = LinkedHashMap<PaginationKey, List<String>>(16, 0.75f, true)
    private const val MAX_ENTRIES = 8

    fun get(key: PaginationKey): List<String>? = synchronized(lock) { entries[key] }

    fun put(key: PaginationKey, pages: List<String>) {
        synchronized(lock) {
            entries[key] = pages
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }
}

data class PaginationKey(
    val content: String,
    val widthPx: Int,
    val heightPx: Int,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val fontFamily: FontFamily,
    val includeFontPadding: Boolean,
    val titleReservePx: Int
)

/** Vertical padding applied by RenderSinglePage around the page content (12dp x 2). */
internal const val PAGE_VERTICAL_PADDING_DP = 12

/** Bottom padding of the body Text inside RenderSinglePage. */
internal const val PAGE_TEXT_BOTTOM_PADDING_DP = 16

/** 渐进分页尚未覆盖整章时，当前“最后一页”只是临时末尾，不能触发全书完成。 */
internal fun shouldCelebrateAfterForwardTurn(
    chapterIndex: Int,
    chapterCount: Int,
    pageIndex: Int,
    pages: List<String>,
    formattedLength: Int
): Boolean = chapterCount > 0 && chapterIndex == chapterCount - 1 && pages.isNotEmpty() &&
    pageIndex >= pages.lastIndex - 1 && pages.sumOf { it.length } == formattedLength

/** Vertical padding around the chapter title block (8dp top + 12dp bottom). */
internal const val TITLE_BLOCK_PADDING_DP = 20

/** Chapters larger than this are paginated chunk-by-chunk instead of in one pass. */
internal const val LARGE_CHAPTER_THRESHOLD = 200_000

/** Chunk size used for large chapters (measured once per chunk, ~40k chars). */
private const val PAGINATION_CHUNK_CHARS = 40_000

/** Chapters up to this size are paginated in one pass and published at once. */
private const val EAGER_CHAPTER_THRESHOLD = 60_000

@Composable
fun rememberChapterPages(
    content: String,
    widthPx: Int,
    heightPx: Int,
    bodyStyle: TextStyle,
    titleReservePx: Int,
    isScrollMode: Boolean
): List<String> {
    if (isScrollMode || content.isEmpty() || widthPx <= 20 || heightPx <= 20) {
        return listOf(content)
    }

    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontSizePx = with(density) { bodyStyle.fontSize.toPx() }.coerceAtLeast(8f)
    // 缓存必须使用实际行高；小于 1.2 倍字号的不同设置也会产生不同排版。
    val lineHeightPx = with(density) { bodyStyle.lineHeight.toPx() }
    val key = PaginationKey(
        content = content,
        widthPx = widthPx,
        heightPx = heightPx,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        fontFamily = bodyStyle.fontFamily ?: AppFonts.Default,
        includeFontPadding = false,
        titleReservePx = titleReservePx
    )

    var pages by remember(key) {
        // TODO(size-change 双缓冲)：缓存未命中时先短暂空页再异步补齐；
        // 真正会走到这里的场景只剩窗口尺寸真实变化（旋转/分屏），可择机
        // 保留上一尺寸的页面直到新结果就绪，消除单帧空白。
        mutableStateOf(ReaderPaginationCache.get(key) ?: emptyList())
    }

    LaunchedEffect(key) {
        if (pages.isNotEmpty()) return@LaunchedEffect
        val cached = ReaderPaginationCache.get(key)
        if (cached != null) {
            pages = cached
            return@LaunchedEffect
        }

        val params = LayoutParams(
            widthPx = widthPx,
            pageHeightPx = heightPx,
            firstPageHeightPx = (heightPx - titleReservePx).coerceAtLeast(80),
            bodyStyle = bodyStyle,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            density = density,
            fontFamilyResolver = fontFamilyResolver
        )

        if (content.length <= EAGER_CHAPTER_THRESHOLD) {
            val computed = withContext(Dispatchers.Default) {
                paginateChunked(content, params, progressiveSink = null)
            }
            ReaderPaginationCache.put(key, computed)
            pages = computed
        } else {
            // Very large chapters: measure the first chunk right away so the reader gets
            // pages almost instantly, then append the remaining chunks in the background.
            val full = withContext(Dispatchers.Default) {
                paginateChunked(content, params) { partial -> pages = partial }
            }
            ReaderPaginationCache.put(key, full)
            pages = full
        }
    }

    return pages
}

private class LayoutParams(
    val widthPx: Int,
    val pageHeightPx: Int,
    val firstPageHeightPx: Int,
    val bodyStyle: TextStyle,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val density: androidx.compose.ui.unit.Density,
    val fontFamilyResolver: androidx.compose.ui.text.font.FontFamily.Resolver
)

/**
 * Splits [content] into ~40k-char chunks (preferring newline boundaries) and paginates
 * each chunk with a real [Paragraph] layout, appending pages so the concatenation is
 * byte-for-byte identical to [content].
 *
 * When [progressiveSink] is provided it is invoked after every chunk with the pages
 * computed so far; the final returned list contains every page.
 */
private fun paginateChunked(
    content: String,
    params: LayoutParams,
    progressiveSink: ((List<String>) -> Unit)?
): List<String> {
    val chunks = splitChunks(content)
    if (chunks.size <= 1) {
        return paginateChunk(chunks.first(), params, reserveTitle = true)
    }

    val allPages = mutableListOf<String>()
    chunks.forEachIndexed { index, chunk ->
        val reserveTitle = index == 0
        allPages += paginateChunk(chunk, params, reserveTitle)
        progressiveSink?.invoke(allPages.toList())
    }
    return allPages
}

/** Splits text into chunks of at most [PAGINATION_CHUNK_CHARS], preferring '\n' cuts. */
private fun splitChunks(content: String, maxChars: Int = PAGINATION_CHUNK_CHARS): List<String> {
    if (content.length <= maxChars) return listOf(content)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
        val end = minOf(content.length, start + maxChars)
        if (end < content.length) {
            val newline = content.lastIndexOf('\n', end)
            if (newline > start + maxChars / 2) {
                chunks.add(content.substring(start, newline))
                start = newline + 1
                continue
            }
        }
        chunks.add(content.substring(start, end))
        start = end
    }
    return chunks
}

/**
 * Real-layout pagination for one chunk. Page breaks are line boundaries reported by
 * [Paragraph], so no line is ever clipped or lost between pages.
 *
 * 内嵌图片走块驱动：图片按长宽比从文本流中拆出、按 (宽, 收敛后的高) 独立占空间，
 * 当前页剩余空间不足时断页 —— 不再依赖 ParagraphIntrinsics 的 placeholder
 * （大图场景测量与渲染不一致，会导致叠绘）。
 *
 * @param reserveTitle whether the first page of this chunk must reserve the chapter
 *   title block (only the very first chunk of a chapter).
 */
private fun paginateChunk(
    chunk: String,
    params: LayoutParams,
    reserveTitle: Boolean
): List<String> {
    // ── 原子项序列：文本行（Paragraph 真实排版）与图片块（按长宽高占空间） ──
    data class Item(val text: String, val heightPx: Float)

    val items = mutableListOf<Item>()
    NovelInlineImages.splitIntoBlocks(chunk, params.widthPx.toFloat(), params.pageHeightPx.toFloat())
        .forEach { block ->
            when (block) {
                is NovelInlineImages.InlineBlock.Image -> {
                    // 图片块：高度 = displaySize 收敛值（不超过页高），整块不可分割
                    val h = block.heightPx.coerceAtMost(params.pageHeightPx.toFloat()).coerceAtLeast(1f)
                    items.add(Item(block.raw, h))
                }
                is NovelInlineImages.InlineBlock.Text -> {
                    if (block.text.isEmpty()) return@forEach
                    // 纯文本段：真实排版后按行拆成原子项（行不可跨页）
                    val paragraph = buildParagraph(block.text, params)
                    val lineCount = paragraph.lineCount
                    if (lineCount == 0) {
                        items.add(Item(block.text, 1f))
                        return@forEach
                    }
                    for (i in 0 until lineCount) {
                        val lineH = (paragraph.getLineBottom(i) - paragraph.getLineTop(i)).coerceAtLeast(1f)
                        val start = paragraph.getLineStart(i)
                        val end = paragraph.getLineEnd(i)
                        // 末尾换行会产生没有字符的空行，其高度由页级复测计入。
                        if (start == end) continue
                        items.add(Item(block.text.substring(start, end), lineH))
                    }
                }
            }
        }

    if (items.isEmpty()) return if (chunk.isEmpty()) emptyList() else listOf(chunk)

    // 行高只用于估算断点。小行距下，整章的中间行成为独立页的首尾行时，
    // Paragraph 会补足字形边界；末尾换行也会多出一行，必须按页重新测量。
    val endOffsets = IntArray(items.size)
    var offset = 0
    items.forEachIndexed { index, item ->
        offset += item.text.length
        endOffsets[index] = offset
    }

    val pages = mutableListOf<String>()
    var startItem = 0
    var startChar = 0
    while (startItem < items.size) {
        // 标题预留必须用于首页所有行，不能只在追加第一项时生效。
        val pageHeight = if (reserveTitle && pages.isEmpty()) {
            params.firstPageHeightPx.toFloat()
        } else {
            params.pageHeightPx.toFloat()
        }

        var endItem = startItem
        var estimatedHeight = 0f
        while (endItem < items.size) {
            val nextHeight = estimatedHeight + items[endItem].heightPx
            if (endItem > startItem && nextHeight > pageHeight) break
            estimatedHeight = nextHeight
            endItem++
        }

        fun fits(end: Int): Boolean = measurePageHeightPx(
            chunk.substring(startChar, endOffsets[end - 1]), params
        ) <= pageHeight

        if (endItem > startItem + 1 && !fits(endItem)) {
            // 在真实行/图边界二分回退，避免每加一行都排版整页。
            // 单项超过可用高度时仍保留该项，确保不丢字且分页能继续。
            var low = startItem + 1
            var high = endItem - 1
            while (low < high) {
                val middle = low + (high - low + 1) / 2
                if (fits(middle)) low = middle else high = middle - 1
            }
            endItem = low
        }

        val endChar = endOffsets[endItem - 1]
        pages.add(chunk.substring(startChar, endChar))
        startChar = endChar
        startItem = endItem
    }

    return pages
}

/** 与 RenderSinglePage 的块布局一致，计入首尾字体边界、末尾空行和像素取整。 */
private fun measurePageHeightPx(text: String, params: LayoutParams): Float =
    NovelInlineImages.splitIntoBlocks(text, params.widthPx.toFloat(), params.pageHeightPx.toFloat())
        .sumOf { block ->
            when (block) {
                is NovelInlineImages.InlineBlock.Text -> ceil(buildParagraph(block.text, params).height).toInt()
                is NovelInlineImages.InlineBlock.Image -> block.heightPx.roundToInt()
            }
        }.toFloat()

/**
 * Builds a [Paragraph] that lays out [text] exactly like RenderSinglePage's Text:
 * same TextStyle (fontFamily / fontSize / lineHeight / includeFontPadding), same width
 * and same density.
 *
 * 章节文本含 [IMG:...] 内嵌图片占位符时，用 AnnotatedString + inlineContent 让
 * 分页测量计入图片实际占位高度（图片尺寸推导与渲染侧 NovelInlineImages 一致）。
 */
private fun buildParagraph(text: String, params: LayoutParams): Paragraph {
    val maxImageHeightPx = params.pageHeightPx.toFloat()
    // 与渲染端同源：相邻 token 强制断行，否则两个整页高占位同行叠绘
    val text = NovelInlineImages.normalizeTokenBreaks(text)
    // 内嵌图片占位符（1.7 API）：占位范围标记正文中的 [IMG:...] 文本区域，
    // 测量时该区域按给定宽高参与排版（与渲染侧 NovelInlineImages 同一套推导）
    val placeholderRanges = mutableListOf<AnnotatedString.Range<Placeholder>>()
    if (NovelInlineImages.hasImages(text)) {
        val spPerPx = 1f / (params.density.density * params.density.fontScale).coerceAtLeast(0.01f)
        NovelInlineImages.TOKEN_REGEX.findAll(text).forEach { m ->
            val w = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val h = m.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val (dw, dh) = NovelInlineImages.displaySize(w, h, params.widthPx.toFloat(), maxImageHeightPx)
            placeholderRanges.add(
                AnnotatedString.Range(
                    item = Placeholder((dw * spPerPx).sp, (dh * spPerPx).sp, PlaceholderVerticalAlign.TextCenter),
                    start = m.range.first,
                    end = m.range.last + 1
                )
            )
        }
    }
    val intrinsics = ParagraphIntrinsics(
        text = text,
        style = params.bodyStyle,
        spanStyles = emptyList(),
        placeholders = placeholderRanges,
        density = params.density,
        fontFamilyResolver = params.fontFamilyResolver
    )
    return Paragraph(
        paragraphIntrinsics = intrinsics,
        constraints = Constraints(maxWidth = params.widthPx)
    )
}
