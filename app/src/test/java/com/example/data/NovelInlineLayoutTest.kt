package com.example.data

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.reader.buildAnnotatedWithImages
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule

/**
 * 渲染层实证：buildAnnotatedWithImages 的 inlineContent 在 BasicText 里必须真的
 * 被排版成占位区（getPlaceholderRect 非 Zero）—— 否则阅读器会把 novel_img_0
 * 这类 key 当字面文本画出来（用户实测的症状）。
 * 同时验证高亮样式在渲染层不丢。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NovelInlineLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test
    fun inlineContentPlaceholderIsActuallyLaidOut() {
        val text = "前文段落。${"[IMG:/x/pic.png|40|40]"}后文段落。"
        val (ann, contents) = buildAnnotatedWithImages(
            text = text,
            contentWidthPx = 300f,
            maxHeightPx = 500f,
            density = Density(2f),
            imageContent = null // 尺寸/排版验证不需要真图
        )
        assertTrue("contents 未生成占位", contents.isNotEmpty())
        var layout: TextLayoutResult? = null
        compose.setContent {
            BasicText(
                text = ann,
                inlineContent = contents,
                onTextLayout = { layout = it },
                style = TextStyle(fontSize = 14.sp)
            )
        }
        compose.waitForIdle()
        val l = layout
        assertTrue("onTextLayout 未回调", l != null)
        assertTrue("输入里没有 placeholder", l!!.layoutInput.placeholders.isNotEmpty())
        val rect = l.multiParagraph.placeholderRects.firstOrNull() ?: androidx.compose.ui.geometry.Rect.Zero
        assertTrue(
            "placeholder 没有被真实排版（rect=$rect）—— inlineContent 失效，key 会以字面渲染",
            rect.width > 0f && rect.height > 0f
        )
    }

    @Test
    fun adjacentLargeImageTokensDoNotOverlap() {
        // 用户实测：EPUB 彩页两图连排（token 相邻无文本分隔）在同一行叠绘
        val text = "开场文字。[IMG:/x/a.png|800|1200][IMG:/x/b.png|800|1200]结尾文字。"
        val (ann, contents) = buildAnnotatedWithImages(
            text = text,
            contentWidthPx = 900f,
            maxHeightPx = 1600f,
            density = Density(2.6f),
            imageContent = null
        )
        assertTrue("断行规范化后仍应识别到两个占位", contents.size == 2)
        var layout: TextLayoutResult? = null
        compose.setContent {
            BasicText(
                text = ann,
                inlineContent = contents,
                onTextLayout = { layout = it },
                style = TextStyle(fontSize = 16.sp)
            )
        }
        compose.waitForIdle()
        val l = layout
        assertTrue(l != null)
        val rects = l!!.multiParagraph.placeholderRects
        assertTrue("应有两个图片占位", rects.size >= 2)
        val r0 = rects[0] ?: return
        val r1 = rects[1] ?: return
        val overlaps = !(r0.right <= r1.left + 0.5f || r1.right <= r0.left + 0.5f ||
            r0.bottom <= r1.top + 0.5f || r1.bottom <= r0.top + 0.5f)
        assertTrue("两个图片占位区重叠: $r0 vs $r1", !overlaps)
    }

    @Test
    fun highlightSurvivesIntoRenderedLayout() {
        val text = "前文关键词后文。"
        val (ann, _) = buildAnnotatedWithImages(
            text = text,
            contentWidthPx = 300f,
            maxHeightPx = 500f,
            density = Density(2f),
            imageContent = null,
            highlightQuery = "关键词",
            highlightStyle = androidx.compose.ui.text.SpanStyle(
                background = androidx.compose.ui.graphics.Color.Red
            )
        )
        var layout: TextLayoutResult? = null
        compose.setContent {
            BasicText(text = ann, onTextLayout = { layout = it }, style = TextStyle(fontSize = 14.sp))
        }
        compose.waitForIdle()
        val l = layout
        assertTrue(l != null)
        val pos = l!!.layoutInput.text.text.indexOf("关键词")
        assertTrue("渲染文本里找不到关键词", pos >= 0)
        val span = l.layoutInput.text.spanStyles.firstOrNull {
            pos >= it.start && pos < it.end && it.item.background == androidx.compose.ui.graphics.Color.Red
        }
        assertTrue("高亮 span 未进入渲染输入", span != null)
    }
}
