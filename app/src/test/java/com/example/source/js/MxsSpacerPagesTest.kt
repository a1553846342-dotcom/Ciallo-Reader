package com.example.source.js

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MxsSpacerPagesTest {
    private fun page(height: Int = 307) = Bitmap.createBitmap(720, height, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.WHITE) }

    @Test fun pureWhiteShortMarginIsASpacer() { assertTrue(isMxsWhiteSpacer(page())) }

    @Test fun aSingleInkPixelKeepsShortArtwork() {
        val image = page().apply { setPixel(359, 153, Color.BLACK) }
        assertFalse(isMxsWhiteSpacer(image))
    }

    @Test fun tallWhitePagesAndBlackPagesArePreserved() {
        assertFalse(isMxsWhiteSpacer(page(1000)))
        assertFalse(isMxsWhiteSpacer(page().apply { eraseColor(Color.BLACK) }))
    }

    @Test fun stripsOnlyLeadingSpacersAndStopsAtFirstRealPage() = runBlocking {
        val white = page()
        val artwork = page().apply { setPixel(10, 10, Color.BLACK) }
        val loaded = mutableListOf<String>()
        val urls = listOf("spacer", "artwork", "middleSpacer", "last")
        val result = stripLeadingMxsSpacers(urls) { url ->
            loaded.add(url); if (url == "artwork") artwork else white
        }
        assertEquals(listOf("artwork", "middleSpacer", "last"), result)
        assertEquals(listOf("spacer", "artwork"), loaded)
    }

    @Test fun failedProbeKeepsThePageForTheReadersRetry() = runBlocking {
        val urls = listOf("first", "second")
        assertEquals(urls, stripLeadingMxsSpacers(urls) { null })
    }

    @Test fun prefixProbeIsBoundedToThreePages() = runBlocking {
        val urls = (1..10).map(Int::toString)
        var calls = 0
        assertEquals(urls.drop(3), stripLeadingMxsSpacers(urls) { calls++; page() })
        assertEquals(3, calls)
    }
}
