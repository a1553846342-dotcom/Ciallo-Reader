package com.example.ui.comic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-night")
class ComicPresetLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun narrowScreenLargeFontKeepsPresetNamesAndDirectionsVisible() {
        val store = ComicSettingsStore(compose.activity)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme { Box(Modifier.fillMaxSize()) {
                    ComicPresetSheet(store, ComicReaderConfig(), {}, {})
                } }
            }
        }
        compose.onNodeWithText("日漫").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("日漫").fetchSemanticsNode().boundsInRoot.width > 40f)
        compose.onNodeWithText("单页 · 右 → 左 · 仿真翻页").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("老漫画"))
        compose.onNodeWithText("老漫画").assertIsDisplayed()
        compose.onNodeWithText("单页 · 左 → 右 · 平移").performScrollTo()
        compose.onRoot().captureRoboImage("reader_feedback_presets_320dp_font160.png")
        compose.onNodeWithText("单页 · 左 → 右 · 平移").assertIsDisplayed()
        compose.onRoot().captureRoboImage("reader_feedback_presets_320dp_font160.png")
    }

    @Test fun rtlProgressGestureTargetsEarlierAndLaterPagesOnTheCorrectSide() {
        var last = -1f
        var finished = 0
        compose.setContent { MaterialTheme {
            PanelSlider(2f, { last = it }, 0f..9f, { finished++ },
                modifier = Modifier.testTag("progress"), reverse = true)
        } }
        compose.onNodeWithTag("progress").performTouchInput { click(percentOffset(0.05f, 0.5f)) }
        compose.runOnIdle { assertTrue(last > 8f); assertEquals(1, finished) }
        compose.onNodeWithTag("progress").performTouchInput { click(percentOffset(0.95f, 0.5f)) }
        compose.runOnIdle { assertTrue(last < 1f); assertEquals(2, finished) }
    }
}
