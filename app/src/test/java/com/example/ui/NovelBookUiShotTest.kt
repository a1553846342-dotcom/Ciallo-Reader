package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import com.example.library.NovelDetailContent
import com.example.library.NovelSearchCard
import com.example.library.novelActionColors
import com.example.source.NovelInfo
import com.example.source.SearchBook
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class NovelBookUiShotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun fixtureLoader(): ImageLoader {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                val bitmap = java.io.File(chain.request.data.toString()).inputStream().use {
                    requireNotNull(android.graphics.BitmapFactory.decodeStream(it))
                }
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(context.resources, bitmap),
                    chain.request, coil.decode.DataSource.DISK)
            })
        }.build()
    }
    private fun verifiedCover(name: String): String = java.io.File(javaClass.getResource(
        "/novel-covers/${if (name == "ixdzs") "ixdzs-571203" else "wenku-3765"}.jpg")!!.toURI()).absolutePath
    private fun book() = SearchBook("571203", "ixdzs8", "没钱修什么仙？", "熊狼狗", cover = verifiedCover("ixdzs"), format = "txt", language = "中文",
        novelInfo = NovelInfo(status = "连载中", category = "修真仙侠", latestChapter = "第1004章 昆墟的天黑了", updatedAt = "2026-10-01 00:44",
            wordCount = "449.1万字", tags = listOf("玄幻", "修仙", "穿越"), translation = "中文网文",
            synopsis = "老者：“你想报仇？”少年：“我被强者反复侮辱，我怎么可能不想报仇？”\n从一场法力贷开始的修仙故事。",
            notice = "原站下载包可能缺章或更新滞后；标称最新章节不代表下载包已有完整正文。"))
    @Test fun novelCardsShowSourceFactsAndOpenDetails() {
        var clicks = 0
        compose.setContent { MyApplicationTheme(darkTheme = false) {
            val loader = androidx.compose.runtime.remember { fixtureLoader() }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("小说", style = MaterialTheme.typography.headlineMedium)
                Text("连载网文与中文轻小说", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NovelSearchCard(book(), "爱下电子书", loader, onClick = { clicks++ })
                NovelSearchCard(book().copy(sourceId = "wenku8_library", title = "无职转生～蛇足篇～", author = "理不尽な孫の手", cover = verifiedCover("wenku"), format = "epub", language = "中文",
                    novelInfo = NovelInfo(status = "连载中", volumeCount = 2, translation = "中文译本", publisher = "MF Books")),
                    "中文轻小说文库", loader, downloaded = true, onClick = {})
                NovelSearchCard(book().copy(sourceId = "zlibrary", title = "三体", author = "刘慈欣", cover = null, format = "mobi", novelInfo = null),
                    "Z-Library", loader, onClick = { clicks++ })
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("TXT", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("EPUB", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle(); compose.onRoot().captureRoboImage()
        compose.onNodeWithContentDescription("加入我喜欢的").assertDoesNotExist()
        compose.onNodeWithText("没钱修什么仙？", substring = false).performClick()
        assertEquals(1, clicks)
    }
    @Test fun novelDetailHasReadableFactsAndWholeBookActionInDarkTheme() {
        compose.setContent { MyApplicationTheme(darkTheme = true) {
            val loader = androidx.compose.runtime.remember { fixtureLoader() }
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
                NovelDetailContent(book(), "爱下电子书", loader, modifier = Modifier.weight(1f))
                Button(onClick = {}, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) { Text("整本下载到书架") }
            } }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("TXT", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle(); compose.onRoot().captureRoboImage()
    }
}
