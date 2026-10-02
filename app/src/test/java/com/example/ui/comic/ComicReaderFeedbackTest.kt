package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComicReaderFeedbackTest {
    @Test fun missingBuiltinsAreRestoredWithoutRecursionAndCustomDataIsPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("comic_reader_store", Context.MODE_PRIVATE)
        val custom = ComicPreset("custom", "保留设置", "ME", ComicReaderConfig(direction = ComicDirection.TTB))
        val old = ComicPreset(ComicSettingsStore.PRESET_CLASSIC, "老漫画", "📚",
            ComicReaderConfig(direction = ComicDirection.RTL), builtIn = true, favorite = true)
        prefs.edit().clear().putString("presets", JSONArray().put(old.toJson()).put(custom.toJson()).toString())
            .putString("default_preset", "custom").apply()
        val store = ComicSettingsStore(context)
        store.saveBookConfig("book", custom.config)
        val migrated = store.loadPresets()
        assertEquals(4, migrated.size)
        assertEquals(custom, migrated.single { it.id == "custom" })
        assertEquals(ComicDirection.RTL, migrated.single { it.id == ComicSettingsStore.PRESET_MANGA }.config.direction)
        val classic = migrated.single { it.id == ComicSettingsStore.PRESET_CLASSIC }
        assertEquals(ComicDirection.LTR, classic.config.direction)
        assertEquals("RZ", classic.emoji)
        assertTrue(classic.favorite)
        assertEquals("custom", store.defaultPresetId())
        assertEquals(custom.config, store.loadBookConfig("book"))
        assertEquals(migrated, store.loadPresets())
    }

    @Test fun rtlArtworkCompensationAndZoomDoNotSwapWidePageHalves() {
        val ref = ComicPageRef.Local("wide", "/unused")
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = android.graphics.Paint()
        paint.color = Color.RED; canvas.drawRect(0f, 0f, 200f, 200f, paint)
        paint.color = Color.BLUE; canvas.drawRect(200f, 0f, 400f, 200f, paint)
        val right = ComicSlot(ref, 0, ComicSplitHalf.RIGHT)
        val left = ComicSlot(ref, 0, ComicSplitHalf.LEFT)
        val config = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.RTL,
            fit = ComicFit.STRETCH, doubleGapDp = 0f)
        val layout = ComicLayout(listOf(ComicSpread(0, listOf(right, left))), mapOf(0 to 0))
        val controller = ComicHarismController().apply {
            this.layout = layout; this.config = config; reversed = true; twoPage = true
            flatUnits = buildCurlFlatUnits(layout)
        }
        listOf(right, left).forEach { slot ->
            val crop = ComicImagePipeline.process(source, ComicImagePipeline.Geometry(half = slot.half), ComicImagePipeline.Toning())
            controller.putCache(slotCacheKey(slot, config, ComicBookState()), crop)
        }
        val native = controller.composeSpread(1, 400, 200)!!
        val zoom = controller.composeSpread(1, 400, 200, mirrorForRenderer = false)!!
        assertTrue(Color.blue(native.getPixel(50, 100)) > 200)
        assertTrue(Color.red(native.getPixel(350, 100)) > 200)
        assertTrue(Color.red(zoom.getPixel(50, 100)) > 200)
        assertTrue(Color.blue(zoom.getPixel(350, 100)) > 200)
        assertEquals(0, controller.toOur(1))
        assertEquals(1, controller.toHarism(0))
    }
}
