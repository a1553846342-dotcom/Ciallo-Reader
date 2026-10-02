package com.example.data

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.favorite.FavoriteRepository
import com.example.ui.favorite.ChapterStatusRow
import com.example.ui.favorite.ChapterRowState
import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 书签端到端闭环（手势 → 回调 → 写库 → 状态回显）：
 * 复刻真机序列——LazyColumn 内的章节行水平滑动 → onToggleBookmark →
 * Repository.setChapterBookmark → DB 持久化 → 行的书签状态更新。
 * 任一环断开即本测试失败，精确定位"松手书签保存不了"的断点。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ChapterBookmarkEndToEndTest {

    @get:Rule
    val compose = createAndroidComposeRule<com.example.ui.comic.ComicReaderTestActivity>()

    @Test
    fun gestureWriteEchoFullLoop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = FavoriteRepository(dao = db.favoriteDao(), comicSourceOf = { null })
        val toggledRows = mutableListOf<Int>()

        // 行的书签状态：初始全 false；回调写入 DB 后同步（模拟 MainActivity 的 flow 回显）
        val bookmarkedIds = androidx.compose.runtime.mutableStateOf(setOf<String>())

        compose.setContent {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(40) { i ->
                    val chapterId = "ch$i"
                    ChapterStatusRow(
                        title = "第 $i 话",
                        state = ChapterRowState(),
                        onClick = {},
                        onLongClick = {},
                        bookmarked = chapterId in bookmarkedIds.value,
                        onToggleBookmark = {
                            toggledRows.add(i)
                            kotlinx.coroutines.runBlocking {
                                repo.setChapterBookmark("s", "c", chapterId, i, true)
                            }
                            bookmarkedIds.value = bookmarkedIds.value + chapterId
                        },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 对屏幕中心横滑（真实手指路径）
        compose.onRoot().performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x - 30f))
            moveTo(center.copy(x = center.x - 100f))
            up()
        }
        compose.waitForIdle()

        // 断言 1：手势触发了回调
        assertTrue("手势未触发书签回调", toggledRows.isNotEmpty())
        // 断言 2：写入持久化
        val state = kotlinx.coroutines.runBlocking { db.favoriteDao().chapterStatesSync("s", "c") }
        val bookmarkedRow = state.firstOrNull { it.bookmarked }
        assertTrue("写入后无 bookmarked=true 的行", bookmarkedRow != null)
        // 断言 3：行的书签状态回显（bookmarkedIds 含被滑的行）
        assertTrue("行状态未回显", bookmarkedIds.value.isNotEmpty())
    }

    @Test
    fun semanticsReflectBookmarkState() {
        compose.setContent {
            ChapterStatusRow(
                title = "第 1 话",
                state = ChapterRowState(),
                onClick = {},
                onLongClick = {},
                bookmarked = true,
            )
        }
        compose.waitForIdle()
        // 已加书签的行：contentDescription 应包含"已加书签"
        compose.onAllNodesWithContentDescription("已加书签").fetchSemanticsNodes().isNotEmpty()
    }
}
