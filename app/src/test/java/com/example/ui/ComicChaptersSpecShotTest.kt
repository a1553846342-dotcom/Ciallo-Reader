package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.favorite.ChapterReadState
import com.example.data.favorite.ChapterReadEntity
import com.example.data.favorite.ComicProgressEntity
import com.example.god.GodMomentEntity
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 重构后漫画详情页的截图自检：浅色一张（含神回卡、三态章节卡、多选模式），
 * 直接看图核对规格还原度。神回封面用空路径（占位底色）+ 本地路径两种都覆盖。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ComicChaptersSpecShotTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun book() = SearchBook(
        id = "d44c8d22",
        sourceId = "mdapi",
        title = "Mushoku Tensei: Roxy da tte Honki desu",
        author = "MangaDex",
        comicId = "d44c8d22",
        format = "comic",
    )

    private fun chapters() = (1..12).map { n ->
        ComicChapter(id = "c$n", title = if (n == 7) "Departure" else "第 $n 话", order = n.toFloat())
    }

    private fun states(): Map<String, ChapterReadEntity> =
        (1..6).associate { n ->
            "c$n" to ChapterReadEntity(
                sourceId = "mdapi", comicId = "d44c8d22", chapterId = "c$n",
                chapterIndex = n - 1, status = ChapterReadState.READ.code,
            )
        } + mapOf(
            "c7" to ChapterReadEntity(
                sourceId = "mdapi", comicId = "d44c8d22", chapterId = "c7",
                chapterIndex = 6, status = ChapterReadState.READING.code,
                pageIndex = 11, pageCount = 34,
            )
        )

    private fun progress() = ComicProgressEntity(
        sourceId = "mdapi", comicId = "d44c8d22",
        lastChapterId = "c7", lastChapterIndex = 6, lastPageIndex = 11, lastPageCount = 34,
        seenTopChapterId = "c9", seenChapterCount = 9,
    )

    private fun godMoments() = mapOf(
        "c7" to GodMomentEntity(
            id = 1, bookId = "mdapi::d44c8d22", chapterId = "c7",
            bookTitle = "Mushoku Tensei", chapterTitle = "Departure", chapterNumber = 7,
            titleIsCustom = true, title = "520", rating = 5f,
            coverPath = "/nonexistent/cover.jpg",
        ),
        "c5" to GodMomentEntity(
            id = 2, bookId = "mdapi::d44c8d22", chapterId = "c5",
            bookTitle = "Mushoku Tensei", chapterTitle = "第 5 话", chapterNumber = 5,
            titleIsCustom = false, rating = 4.5f, coverPath = null,
        ),
    )

    @Composable
    private fun host(dark: Boolean) {
        MyApplicationTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize().background(Color(0xFFEDEFF2))) {
                ComicChaptersScreen(
                    book = book(),
                    chapters = chapters(),
                    loading = false,
                    error = null,
                    downloadingChapters = setOf("c9"),
                    downloadProgress = mapOf("c9" to 0.45f),
                    pausedChapters = emptySet(),
                    onBack = {},
                    onRetry = {},
                    onChapterClick = {},
                    onDownloadChapter = {},
                    onDownloadAll = {},
                    onPauseDownload = {},
                    onResumeDownload = {},
                    onCancelDownload = {},
                    textMode = false,
                    favorite = true,
                    favoriteEnabled = true,
                    onToggleFavorite = {},
                    onFavoriteLongPress = {},
                    chapterStates = states(),
                    downloadedChapterIds = setOf("c1", "c2", "c3"),
                    progress = progress(),
                    bookmarkedChapterIds = setOf("c11"),
                    onSetChapterBookmark = { _, _, _ -> },
                    godMoments = godMoments(),
                    godMomentsReady = true,
                )
            }
        }
    }

    /** Robolectric 没有状态栏内嵌：用 30dp 假内嵌演示真机上 statusBarsPadding 的落点。 */
    private fun setContentWithFakeInset(dark: Boolean) {
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (dark) Color(0xFF101013) else Color(0xFFEDEFF2))
                    .padding(top = 30.dp)
            ) { host(dark) }
        }
        compose.waitForIdle()
        scrollToTop()
    }

    /** 页面会自动定位到续读章节；截图前先把列表拖回顶部，露出头部/信息行/工具行。 */
    private fun scrollToTop() {
        repeat(2) {
            compose.onRoot().performTouchInput {
                down(center)
                repeat(6) { moveBy(Offset(0f, 160f), delayMillis = 30) }
                up()
            }
            compose.waitForIdle()
        }
    }

    @Test
    fun spec_light() {
        setContentWithFakeInset(dark = false)
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun spec_dark() {
        setContentWithFakeInset(dark = true)
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun spec_multi() {
        setContentWithFakeInset(dark = false)
        compose.onNodeWithText("多选").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }
}
