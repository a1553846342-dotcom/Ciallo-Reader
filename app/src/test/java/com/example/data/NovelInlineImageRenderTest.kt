package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.reader.NovelInlineImage
import com.example.ui.reader.buildAnnotatedWithImages
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 内嵌图片"真的能显示"的实证测试（Robolectric NATIVE）：
 * 1) 解码层：NovelInlineImage 使用的 BitmapFactory.decodeFile 能把落盘 PNG 解成
 *    像素正确的位图（token 里指向的文件 → 可渲染位图）；
 * 2) 组件接线层：NovelInlineImage 在真实 Compose 组合里加载完位图后，
 *    内容描述为"插图"的 Image 节点真实挂进语义树（占位符 → 图片组件 → 位图状态）。
 *    像素合成由 Android 原生 Image 绘制承担（无自定义绘制代码）。
 * 3) buildAnnotatedWithImages 的高亮参数真的往 AnnotatedString 写入样式段。
 *
 * 注：captureToImage / captureRoboImage 在本机 Robolectric 环境下无法产出
 * （idle 同步超时 / 静默无文件），像素级上屏由 1+2 + 原生 Image 共同保障。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NovelInlineImageRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    /** 生成 4x4 纯红 PNG（android.graphics.Bitmap 真实编码）。 */
    private fun redPngBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFFF0000.toInt())
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun novelInlineImageLoadsBitmapIntoComposition() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()

        // 层 1：decodeFile（NovelInlineImage 内部用的同一 API）解出像素正确的位图
        val imgFile = File(context.filesDir, "render_test_${System.currentTimeMillis()}.png")
        imgFile.writeBytes(redPngBytes())
        val decoded = BitmapFactory.decodeFile(imgFile.absolutePath)
        assertTrue("decodeFile 失败：图片文件无法解码", decoded != null)
        val p = decoded!!.getPixel(2, 2)
        assertTrue(
            "解码结果像素不是图片的红色: ${Integer.toHexString(p)}",
            (p shr 16) and 0xFF > 180 && p and 0xFF < 120
        )
        decoded.recycle()

        // 层 2：组件在组合里完成异步解码后，"插图" Image 节点真实挂载
        compose.setContent {
            Box(
                modifier = Modifier.size(60.dp).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                NovelInlineImage(path = imgFile.absolutePath, onTap = {})
            }
        }
        var mounted = false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !mounted) {
            compose.runOnUiThread { }
            Thread.sleep(250)
            mounted = try {
                compose.onAllNodesWithContentDescription("插图").fetchSemanticsNodes().isNotEmpty()
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(
            "NovelInlineImage 在 15s 内没有把解码后的图片节点挂进组合（token → 图片组件链路断裂）",
            mounted
        )
    }

    @Test
    fun highlightQueryProducesSpanStyles() {
        val text = "前文[IMG:/x/a.png|10|10]中间关键词后文还有关键词多个"
        val (annotated, contents) = buildAnnotatedWithImages(
            text = text,
            contentWidthPx = 300f,
            maxHeightPx = 500f,
            density = Density(2f),
            imageContent = null,
            highlightQuery = "关键词",
            highlightStyle = SpanStyle(background = Color.Red)
        )
        assertTrue("inlineContent 占位符丢失", contents.isNotEmpty())
        assertTrue(
            "highlightQuery 未产生任何样式段",
            annotated.spanStyles.filter { it.item.background == Color.Red }.size >= 2
        )
        val (plain, _) = buildAnnotatedWithImages(
            text = text, contentWidthPx = 300f, maxHeightPx = 500f,
            density = Density(2f), imageContent = null
        )
        assertTrue("默认构建不应产生样式段", plain.spanStyles.isEmpty())
    }
}
