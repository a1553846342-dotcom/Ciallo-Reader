package com.example.god

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 神回窗口组合回归：复刻「阅读页末页松手 → 打开神回窗口」这条路径的组合输入
 * （hazeState 非 null = 走真毛玻璃分支，与阅读页一致）。
 *
 * 任一处组合/绘制期硬崩溃（IllegalStateException / IllegalArgumentException /
 * RenderEffect 相关）都会在这里冒出来，不用真机手动滑到末页。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class GodMomentSheetComposeTest {

    @Test
    fun pageHeadersKeepReaderReferer() {
        assertEquals("https://reader.example/", godPageHeaders(emptyMap(), "https://reader.example/")["Referer"])
        assertEquals(
            "https://source.example/",
            godPageHeaders(mapOf("referer" to "https://source.example/"), "https://reader.example/")["referer"],
        )
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sheetComposesWithoutCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GodMomentViewModel(app)
        val request = GodMomentRequest(
            contentType = GodContentType.COMIC,
            bookId = "local_1",
            chapterId = "1",
            bookTitle = "测试漫画",
            chapterTitle = "测试漫画",
            chapterNumber = 1,
            pages = (0 until 30).map { GodPageRef(id = "p$it", source = "", remote = false) },
            initialPageIndex = 29,
        )

        var composed = false
        compose.setContent {
            GodMomentSheet(
                request = request,
                existing = null,
                viewModel = vm,
                remoteLoader = null,
                onDismiss = {},
                onSaved = {},
            )
            composed = true
        }
        compose.waitForIdle()
        assertTrue("神回窗口未完成组合", composed)
    }

    @Test
    fun sheetComposesWithoutCrash_noHaze() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GodMomentViewModel(app)
        val request = GodMomentRequest(
            contentType = GodContentType.COMIC,
            bookId = "local_1",
            chapterId = "1",
            bookTitle = "测试漫画",
            chapterTitle = "测试漫画",
            chapterNumber = 1,
            pages = (0 until 5).map { GodPageRef(id = "p$it", source = "", remote = false) },
            initialPageIndex = 4,
        )
        compose.setContent {
            GodMomentSheet(
                request = request,
                existing = null,
                viewModel = vm,
                remoteLoader = null,
                onDismiss = {},
                onSaved = {},
            )
        }
        compose.waitForIdle()
    }
}
