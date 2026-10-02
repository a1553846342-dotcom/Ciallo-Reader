package com.example.data

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.rememberChapterPages
import com.example.ui.shouldCelebrateAfterForwardTurn
import com.example.ui.reader.NovelInlineImages
import com.example.ui.theme.AppFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 分页端"图片按长宽高占空间"的实证：两个整页高的插图必须各占一页
 * （旧文本流内联占位的失效症状就是多图挤进同一页叠绘），
 * 且分页输出拼接必须与输入逐字符一致（不丢字、不重复）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PaginationImageHeightTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    private lateinit var pageTextMeasurer: TextMeasurer

    private fun paginate(
        content: String,
        widthPx: Int,
        heightPx: Int,
        bodyStyle: TextStyle = TextStyle(fontSize = 30.sp, lineHeight = 40.sp),
        titleReservePx: Int = 0
    ): List<String> {
        var pages: List<String> = emptyList()
        compose.setContent {
            pageTextMeasurer = rememberTextMeasurer()
            val p = rememberChapterPages(
                content = content,
                widthPx = widthPx,
                heightPx = heightPx,
                bodyStyle = bodyStyle,
                titleReservePx = titleReservePx,
                isScrollMode = false
            )
            pages = p
        }
        // 分页在后台协程算（LaunchedEffect + Dispatchers.Default）：轮询直到产出
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && pages.isEmpty()) {
            compose.waitForIdle()
            Thread.sleep(120)
        }
        return pages
    }

    /** 使用 Text 渲染端的 TextLayoutResult，而不是整章的行高估算来验证每页。 */
    private fun assertCompactPagesFit(
        fontSizeSp: Int,
        lineHeightSp: Int,
        titleReservePx: Int = 0,
        withImage: Boolean = false
    ) {
        val widthPx = 560
        val heightPx = 600
        val style = TextStyle(
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            fontFamily = AppFonts.Serif,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
        val body = (1..80).joinToString("\n") { "第 $it 行：这是小行距中文正文，含 gypq 和标点。" }
        val content = if (withImage) {
            "$body\n[IMG:/x/pic.png|800|400]\n\n$body\n"
        } else body + "\n"
        val pages = paginate(content, widthPx, heightPx, style, titleReservePx)
        assertTrue("分页应产生多页", pages.size > 1)
        assertEquals("分页不能丢字或重复", content, pages.joinToString(""))
        compose.runOnIdle {
            pages.forEachIndexed { index, page ->
                val measuredHeight = NovelInlineImages.splitIntoBlocks(
                    page, widthPx.toFloat(), heightPx.toFloat()
                ).sumOf { block ->
                    when (block) {
                        is NovelInlineImages.InlineBlock.Text -> pageTextMeasurer.measure(
                            text = block.text,
                            style = style,
                            constraints = Constraints(maxWidth = widthPx)
                        ).size.height
                        is NovelInlineImages.InlineBlock.Image -> block.heightPx.roundToInt()
                    }
                }
                val availableHeight = heightPx - if (index == 0) titleReservePx else 0
                assertTrue(
                    "第 $index 页实际高 $measuredHeight 超过可用高 $availableHeight（行距 $lineHeightSp sp）",
                    measuredHeight <= availableHeight
                )
            }
        }
    }

    @Test
    fun minimumLineHeightKeepsEveryPageFullyVisible() {
        assertCompactPagesFit(fontSizeSp = 20, lineHeightSp = 20)
    }

    @Test
    fun compactLineHeightWithLargeFontKeepsEveryPageFullyVisible() {
        assertCompactPagesFit(fontSizeSp = 32, lineHeightSp = 26)
    }

    @Test
    fun titleReserveAppliesToAllLinesOnFirstPage() {
        assertCompactPagesFit(fontSizeSp = 20, lineHeightSp = 26, titleReservePx = 240)
    }

    @Test
    fun compactMixedContentIncludesTextBlockAndTrailingNewlineHeights() {
        assertCompactPagesFit(fontSizeSp = 20, lineHeightSp = 24, withImage = true)
    }

    @Test
    fun tallImagesOccupySeparatePages() {
        val content = "开头文字行。\n[IMG:/a/1.png|800|1600]\n[IMG:/a/2.png|800|1600]\n结尾文字行。"
        val pages = paginate(content, widthPx = 1000, heightPx = 2000)

        assertTrue("分页未产出", pages.isNotEmpty())
        // 不变量：页拼接 == 输入（分页只在块/行边界切，不丢字不重复）
        assertEquals("分页输出拼接与输入不一致", content, pages.joinToString(""))
        // 两个整页高的图各占一页 —— 挤同页即"按长宽高占空间"失效
        val p1 = pages.indexOfFirst { it.contains("/a/1.png") }
        val p2 = pages.indexOfFirst { it.contains("/a/2.png") }
        assertTrue("图 1 未出现在任何页", p1 >= 0)
        assertTrue("图 2 未出现在任何页", p2 >= 0)
        assertTrue(
            "两个整页高的图片被分进同一页（pages=$pages）——叠绘根因",
            p1 != p2
        )
    }

    @Test
    fun textOnlyContentUnchangedByBlockDriver() {
        val content = (1..80).joinToString("\n") { "这是第 $it 行正文内容，用来填充分页测量。" }
        val pages = paginate(content, widthPx = 1000, heightPx = 2000)
        assertTrue(pages.isNotEmpty())
        assertEquals("纯文本分页拼接必须与输入一致", content, pages.joinToString(""))
        assertTrue("纯文本应产生多页", pages.size > 1)
    }

    @Test
    fun temporaryLastPageDuringProgressivePaginationDoesNotCompleteBook() {
        val partial = listOf("第一页")
        assertTrue(!shouldCelebrateAfterForwardTurn(4, 5, 0, partial, formattedLength = 100))
        val complete = listOf("第一页", "第二页")
        val length = complete.sumOf { it.length }
        assertTrue(shouldCelebrateAfterForwardTurn(4, 5, 0, complete, length))
        assertTrue(!shouldCelebrateAfterForwardTurn(3, 5, 0, complete, length))
    }
}
