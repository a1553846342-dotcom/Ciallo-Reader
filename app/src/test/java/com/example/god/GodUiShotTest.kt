package com.example.god

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.favorite.ChapterRowState
import com.example.ui.favorite.ChapterStatusRow
import com.example.ui.favorite.ChapterVisual
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 神回 / 书签 UI 的截图自检。
 *
 * 目的：改完视觉能**真的看到结果**，而不是靠想象。NATIVE 渲染 + 手动存 PNG，
 * 直接打开核对配色、层次与排版。
 *
 * ⚠️ 时钟处理：神回窗口里有 `rememberInfiniteTransition`（金色流光 / 星点漂移），
 * 组合测试时钟永远等不到 idle，`waitForIdle()` 会 2s 超时。因此冻结自动推进、
 * 手动把时钟推过入场动画后再抓图。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 按真机尺寸出图（Robolectric 默认视口只有 320dp 宽，看不到窗口下半截）
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class GodUiShotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * 关掉系统动画缩放。
     *
     * 神回窗口里有 `rememberInfiniteTransition`（金色流光 / 星点漂移），时钟永远
     * 等不到 idle，而 `captureToImage()` 内部的 `forceRedraw` 会 waitUntil 等绘制
     * 就绪 —— 必然 2s 超时。把 ANIMATOR_DURATION_SCALE 置 0 后
     * `rememberReduceMotion()` 返回 true，窗口走 reduce 分支（静态金渐变、无星点
     * 漂移），时钟可收敛，截图才能落盘。
     * 抓的是「减少动态效果」下的静态版：配色、材质层次、排版都能如实核对。
     */
    @org.junit.Before
    fun disableAnimations() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Global.putFloat(
            app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
        android.provider.Settings.Global.putFloat(
            app.contentResolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 0f,
        )
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        shotSetup(content)
        // 本地示例封面经 Coil 异步解码；等位图进入组合后再录排行榜，避免
        // 只截到占位帧（Compose idle 不包含图片解码器的后台工作）。
        if (name == "god_ranking_full") Thread.sleep(900)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }

    /** 只 setContent 不抓图：对话框等独立窗口场景自己选根节点抓 */
    private fun shotSetup(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.waitForIdle()
    }

    private fun request() = GodMomentRequest(
        contentType = GodContentType.COMIC,
        bookId = "local_1",
        chapterId = "1",
        bookTitle = "进击的巨人",
        chapterTitle = "第 13 话",
        chapterNumber = 13,
        pages = (0 until 12).map { GodPageRef(id = "p$it", source = samplePagePath(it % 3), remote = false) },
        initialPageIndex = 11,
    )

    /** 截图用的抽象示例画面，不依赖网络或受版权保护的漫画封面。 */
    private fun samplePagePath(seed: Int): String {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(app.cacheDir, "god_sample_page_$seed.png")
        if (!file.exists()) {
            val colors = listOf(
                intArrayOf(0xFF283558.toInt(), 0xFFD08164.toInt()),
                intArrayOf(0xFF0C5557.toInt(), 0xFFF2BB6B.toInt()),
                intArrayOf(0xFF503250.toInt(), 0xFFDCA2B6.toInt()),
            )[seed % 3]
            val bitmap = android.graphics.Bitmap.createBitmap(480, 720, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 480f, 720f, colors[0], colors[1], android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, 480f, 720f, paint)
            paint.shader = null
            paint.color = 0x66FFFFFF
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3f
            for (i in 0..5) canvas.drawCircle(350f, 255f, 75f + i * 53f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFF9E7C7.toInt()
            paint.textSize = 37f
            paint.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            canvas.drawText("MOMENT  /  0${seed + 1}", 38f, 610f, paint)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file.absolutePath
    }

    @Composable
    private fun sheetHost(dark: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GodMomentViewModel(app)
        MyApplicationTheme(darkTheme = dark) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (dark) Color(0xFF101013) else Color(0xFFEDEFF2)),
            ) {
                GodMomentSheet(
                    request = request(),
                    existing = null,
                    viewModel = vm,
                    remoteLoader = null,
                    onDismiss = {},
                    onSaved = {},
                )
            }
        }
    }

    @Test
    fun sheet_light() = shot("god_sheet_light") { sheetHost(dark = false) }

    @Test
    fun sheet_dark() = shot("god_sheet_dark") { sheetHost(dark = true) }

    @Test
    fun rankingCard() {
        shot("god_ranking") {
            rankingHost(style = GodRankingStyle.PODIUM)
        }
    }

    @Test
    fun rankingFullScreen() {
        val moments = listOf(
            GodMomentEntity(id = 1, bookId = "b1", chapterId = "c1", bookTitle = "进击的巨人",
                chapterNumber = 13, rating = 5f, titleIsCustom = true,
                title = "城墙上的那一抹夕阳", note = "一整话都舍不得翻过去",
                coverPath = samplePagePath(0)),
            GodMomentEntity(id = 2, bookId = "b2", chapterId = "c2", bookTitle = "海贼王",
                chapterNumber = 1015, rating = 4.5f, coverPath = samplePagePath(1)),
            GodMomentEntity(id = 3, bookId = "b3", chapterId = "c3", bookTitle = "咒术回战",
                chapterNumber = 236, rating = 4f, coverPath = samplePagePath(2)),
        )
        shot("god_ranking_full") {
            MyApplicationTheme(darkTheme = false) {
                GodRankingScreen(
                    moments = moments,
                    style = GodRankingStyle.PODIUM,
                    gyroEnabled = false,
                    onBack = {}, onItemClick = {}, onEdit = {}, onDelete = {},
                    onOpenStyleSettings = {},
                )
            }
        }
    }

    @Test
    fun pullArmed() {
        var pull: GodPullState? = null
        shotSetup {
            MyApplicationTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF17130F))) {
                    val state = rememberGodPullState(edgeIsVertical = false, onTriggered = {})
                    androidx.compose.runtime.SideEffect { pull = state }
                    GodPullOverlay(state = state, edge = GodPullEdge.RIGHT)
                }
            }
        }
        compose.runOnIdle { pull?.setRaw(700f) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun rankingVinyl() {
        shot("god_ranking_vinyl") {
            rankingHost(style = GodRankingStyle.VINYL_SHELF)
        }
    }

    @Test
    fun rankingPolaroid() {
        shot("god_ranking_polaroid") {
            rankingHost(style = GodRankingStyle.POLAROID_WALL)
        }
    }

    /** 三种排行榜样式共用的样例数据 + 宿主（统计页卡片内的 compact 预览）。 */
    @Composable
    private fun rankingHost(style: GodRankingStyle) {
        val moments = listOf(
            GodMomentEntity(
                id = 1, bookId = "b1", chapterId = "c1", bookTitle = "进击的巨人",
                chapterTitle = "第 13 话", chapterNumber = 13, rating = 5f,
                titleIsCustom = true, title = "城墙上的那一抹夕阳", note = "看到这里直接起鸡皮疙瘩",
            ),
            GodMomentEntity(
                id = 2, bookId = "b2", chapterId = "c2", bookTitle = "海贼王",
                chapterTitle = "第 1015 话", chapterNumber = 1015, rating = 4.5f,
            ),
            GodMomentEntity(
                id = 3, bookId = "b3", chapterId = "c3", bookTitle = "咒术回战",
                chapterTitle = "第 236 话", chapterNumber = 236, rating = 4f,
            ),
        )
        MyApplicationTheme(darkTheme = false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFEDEFF2))
                    .padding(16.dp),
            ) {
                GodRankingCard(
                    moments = moments,
                    style = style,
                    gyroEnabled = false,
                    reduceMotion = true,
                    onOpenAll = {},
                    onItemClick = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun cropOverlay() {
        // 480x720 渐变测试图：验证裁剪框覆盖层挂在舞台坐标里 ——
        // 曾经 CropOverlay 挂在根 Box 上用全屏尺寸换算舞台归一化坐标，
        // 框和手柄整体错开舞台内边距、手柄不在框角上（"改不了尺寸"观感的一半）。
        val bmp = android.graphics.Bitmap.createBitmap(480, 720, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(
            0f, 0f, 480f, 720f,
            0xFF24365E.toInt(), 0xFFD2783C.toInt(), android.graphics.Shader.TileMode.CLAMP,
        ) }
        c.drawRect(0f, 0f, 480f, 720f, p)
        shot("god_crop") {
            MyApplicationTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
                    GodCoverCropScreen(
                        source = bmp,
                        initialCrop = CropParams.DEFAULT,
                        onCancel = {},
                        onConfirm = {},
                    )
                }
            }
        }
    }

    @Test
    fun noteDialog() {
        // 随笔原文弹窗：点排行榜各处的随笔小字弹出，纯展示、无按钮。
        // ⚠️ AlertDialog 是独立窗口（第二个根节点），不能走 onRoot() 抓图。
        val interactions = GodRankingInteractions().apply {
            noteFor = GodMomentEntity(
                id = 1, bookId = "b1", chapterId = "c1", bookTitle = "进击的巨人",
                chapterTitle = "第 13 话", chapterNumber = 13, rating = 5f,
                titleIsCustom = true, title = "城墙上的那一抹夕阳",
                note = "看到这里直接起鸡皮疙瘩。兵长转身的那一格分镜，\n配合停电前最后一盏灯灭掉，气氛拉满。",
            )
        }
        val moments = listOf(
            GodMomentEntity(
                id = 1, bookId = "b1", chapterId = "c1", bookTitle = "进击的巨人",
                chapterTitle = "第 13 话", chapterNumber = 13, rating = 5f,
                titleIsCustom = true, title = "城墙上的那一抹夕阳",
                note = "看到这里直接起鸡皮疙瘩。",
            ),
        )
        shotSetup {
            MyApplicationTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEDEFF2))
                        .padding(16.dp),
                ) {
                    GodRankingBody(
                        items = moments.mapIndexed { i, e -> GodMomentItem(e, i + 1) },
                        style = GodRankingStyle.PODIUM,
                        compact = false,
                        gyroEnabled = false,
                        reduceMotion = true,
                        interactions = interactions,
                        onItemClick = {},
                        onEdit = {},
                        onDelete = {},
                    )
                }
            }
        }
        // 对话框窗口 = 第二个根节点；用"根 + 弹窗独有的文本片段（子串）"唯一匹配
        //（摘要小字在活动窗口里也有，hasText 默认全等，不能用作锚点）
        compose.onNode(
            androidx.compose.ui.test.hasAnyDescendant(
                androidx.compose.ui.test.hasText("兵长转身的那一格分镜", substring = true),
            ).and(androidx.compose.ui.test.isRoot()),
        ).captureRoboImage()
    }

    @Test
    fun chapterBookmarkRows() {
        shot("god_bookmark") {
            MyApplicationTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEDEFF2))
                        .padding(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ChapterStatusRow(
                            title = "第 13 话 · 城墙上的那一抹夕阳",
                            state = ChapterRowState(visual = ChapterVisual.READING, pageIndex = 8, pageCount = 20),
                            onClick = {},
                            onLongClick = {},
                            bookmarked = true,
                            onToggleBookmark = {},
                        )
                        ChapterStatusRow(
                            title = "第 14 话 · 地下街的灯火",
                            state = ChapterRowState(visual = ChapterVisual.UNREAD, isNew = true),
                            onClick = {},
                            onLongClick = {},
                            bookmarked = false,
                            onToggleBookmark = {},
                        )
                        ChapterStatusRow(
                            title = "第 15 话 · 最后一次出阵",
                            state = ChapterRowState(visual = ChapterVisual.READ, downloaded = true),
                            onClick = {},
                            onLongClick = {},
                            bookmarked = true,
                            onToggleBookmark = {},
                        )
                    }
                }
            }
        }
    }
}
