package com.example.ui.comic

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary
import com.example.god.GodGold
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.example.ui.adaptive.adaptiveSheetWidth

// 第 11 条：半透明面板底。第七轮第 3 条：毛玻璃路径的暗纱从 0xD9（85%）
// 收敛到 0xCC（80%）+ 模糊加深——玻璃质感更通透、不再"接近纯黑的沉闷"；
// backdrop 为 null（CURL 的 GL 层采不到）时仍回退 0xD9 纯半透明
// （GL SurfaceView 叠层下避免更低透明度——透底鬼影会被损伤混叠放大）
internal val PanelBg = Color(0xD91A1A1E)
internal val PanelBgGlass = Color(0xCC1A1A1E)
internal val PanelChipBg = Color(0x16FFFFFF)
internal val PanelChipActiveBg = Color(0x30FFFFFF)
internal val StrokeColor = Color(0x1FFFFFFF)
internal val TextPrimary = Color(0xFFF2F2F4)
// 0xB3 alpha ≈ 70% 白，对 PanelBg 对比度 ≈ 5.9:1（旧 0x99 仅 ~4:1，12sp 标签低于 WCAG AA）
internal val TextSecondary = Color(0xB3FFFFFF)

/**
 * 第 11 条终审版 + 第七轮第 3 条质感升级：面板真毛玻璃背景——采样模式渲染层
 * 做高斯模糊（26dp）+ 轻提饱和（玻璃通透感），再叠 0xCC 暗纱（比旧 0xD9 通透一档，
 * 摆脱"纯黑沉闷"；白纸页最坏情况下文字对比度仍 ≥ AA）。backdrop 为 null
 * （CURL 的 GL 层采不到）时回退纯半透明 PanelBg。
 */
internal fun Modifier.comicPanelGlass(backdrop: Backdrop?, shape: Shape): Modifier =
    if (backdrop == null) background(PanelBg)
    else drawPlainBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.18f)
            blur(radius = 26.dp.toPx())
        },
    ).background(PanelBgGlass)

/** 面板/控件的统一过渡节奏 */
private const val ENTER_MS = 240
private const val EXIT_MS = 180

/**
 * 控制层 + 面板调度（顶层 chrome）。
 * 正常阅读状态只显示漫画；呼出后呈现轻量控制栏；再进入分层设置/目录/预设/裁边面板。
 */
@Composable
fun ComicReaderChrome(
    visible: Boolean,
    panel: ComicPanel,
    onPanelChange: (ComicPanel) -> Unit,
    panelGlassBackdrop: LayerBackdrop? = null,
    title: String,
    chapterTitle: String?,
    currentPage: Int,
    totalPages: Int,
    spreadCount: Int,
    currentSpread: Int,
    config: ComicReaderConfig,
    store: ComicSettingsStore,
    perBookConfig: Boolean,
    onPerBookConfigChange: (Boolean) -> Unit,
    onConfigChange: (ComicReaderConfig) -> Unit,
    onJumpToSpread: (Int) -> Unit,
    spreadFirstRaw: (Int) -> Int,
    onJumpToRawPage: (Int) -> Unit,
    onGoPrev: () -> Unit,
    onGoNext: () -> Unit,
    autoRead: Boolean,
    onAutoReadToggle: () -> Unit,
    continuousFraction: Float = -1f,
    toc: List<ComicTocEntry>,
    currentChapterIndex: Int,
    onJumpToChapter: ((Int) -> Unit)?,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    onGodMoment: (() -> Unit)? = null,
    chapterNavLabel: String,
    pages: List<ComicPageRef>,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    onBookStateChange: (ComicBookState) -> Unit,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
) {
    val onDismissPanel = { onPanelChange(ComicPanel.NONE) }
    val currentRaw = (currentPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    // 进度条拖动中的缩略图预览目标页（-1 = 关闭）
    var thumbPreviewRaw by remember { mutableStateOf(-1) }

    // 面板打开时系统返回先关面板
    BackHandler(enabled = panel != ComicPanel.NONE) { onDismissPanel() }

    // 顶部控制栏
    AnimatedVisibility(
        visible = visible && panel == ComicPanel.NONE,
        enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { -it },
        exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { -it },
    ) {
        ComicTopBar(
            title = title, chapterTitle = chapterTitle,
            config = config,
            store = store,
            onApplyPresetConfig = { onConfigChange(it) },
            onExit = onExit,
            onGodMoment = onGodMoment,
            onOpenPanel = { onPanelChange(it) },
            // 新反馈条目6：三点菜单"旋转本页 90°"入口已移除（按用户要求）；
            // 旋转能力本体保留在设置面板·图像 Tab（"整本旋转/旋转本页 +90°"），
            // pageRotations 状态模型不受影响（手动裁边/管线指纹仍引用）
            onToggleMerge = {
                val anchors = bookState.mergeAnchors
                onBookStateChange(
                    if (currentRaw in anchors) bookState.copy(mergeAnchors = anchors - currentRaw)
                    else bookState.copy(mergeAnchors = anchors + currentRaw)
                )
            },
        )
    }

    // 底部控制栏 + 悬浮缩略图气泡（气泡由 Chrome 层渲染，不占底栏布局）
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible && panel == ComicPanel.NONE,
            enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it },
            exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { it },
        ) {
            ComicBottomBar(
                currentPage = currentPage, totalPages = totalPages,
                spreadCount = spreadCount, currentSpread = currentSpread,
                config = config,
                onThumbPreview = { raw -> thumbPreviewRaw = raw },
                autoRead = autoRead, onAutoReadToggle = onAutoReadToggle,
                onJumpToSpread = onJumpToSpread,
                spreadFirstRaw = spreadFirstRaw,
                onGoPrev = onGoPrev, onGoNext = onGoNext,
                onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
                chapterNavLabel = chapterNavLabel,
                continuousFraction = continuousFraction,
            )
        }
        if (visible && panel == ComicPanel.NONE && config.showThumbPreview &&
            thumbPreviewRaw in pages.indices && spreadCount > 1
        ) {
            // 气泡水平位置跟随进度滑块拇指（±130dp 内偏移，避免贴边裁切）
            val fraction = (thumbPreviewRaw.toFloat() / (pages.size - 1).coerceAtLeast(1))
                .coerceIn(0f, 1f)
            val visualFraction = if (config.direction == ComicDirection.RTL &&
                config.mode != ComicMode.WEBTOON && config.mode != ComicMode.CONTINUOUS) 1f - fraction else fraction
            ComicThumbPreview(
                page = pages[thumbPreviewRaw],
                rawShown = thumbPreviewRaw,
                loader = loader,
                modifier = Modifier
                    .padding(bottom = 316.dp)
                    .offset(x = ((visualFraction - 0.5f) * 260f).dp),
            )
        }
    }

    // 面板遮罩（与面板同节奏淡入淡出）
    AnimatedVisibility(
        visible = panel != ComicPanel.NONE,
        enter = fadeIn(tween(ENTER_MS)),
        exit = fadeOut(tween(EXIT_MS)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickableNoRipple(onDismissPanel)
        )
    }

    AnimatedVisibility(
        visible = panel != ComicPanel.NONE,
        enter = slideInVertically(tween(ENTER_MS + 40)) { it } + fadeIn(tween(ENTER_MS)),
        exit = slideOutVertically(tween(EXIT_MS)) { it } + fadeOut(tween(EXIT_MS - 20)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            when (panel) {
                ComicPanel.SETTINGS -> ComicSettingsSheet(
                    config = config, store = store, perBookConfig = perBookConfig,
                    onPerBookConfigChange = onPerBookConfigChange,
                    onConfigChange = onConfigChange,
                    onOpenPreset = { onPanelChange(ComicPanel.PRESET) },
                    onOpenCrop = { onPanelChange(ComicPanel.CROP) },
                    pages = pages, loader = loader,
                    currentRawPage = currentRaw,
                    bookState = bookState, onBookStateChange = onBookStateChange,
                    onDismiss = onDismissPanel,
                    glassBackdrop = panelGlassBackdrop,
                )
                ComicPanel.TOC -> ComicTocSheet(
                    toc = toc, currentChapterIndex = currentChapterIndex,
                    onJumpToChapter = onJumpToChapter,
                    pages = pages, loader = loader,
                    currentPage = currentPage,
                    onJumpToRawPage = onJumpToRawPage,
                    onDismiss = onDismissPanel,
                    glassBackdrop = panelGlassBackdrop,
                )
                ComicPanel.PRESET -> ComicPresetSheet(
                    store = store, currentConfig = config,
                    onApply = { onConfigChange(it) },
                    onDismiss = onDismissPanel,
                    glassBackdrop = panelGlassBackdrop,
                )
                ComicPanel.CROP -> ComicCropSheet(
                    page = pages.getOrNull(currentRaw),
                    loader = loader, config = config,
                    onDismiss = onDismissPanel,
                    onApply = { l, t, r, b ->
                        onConfigChange(config.copy(manualCrop = listOf(l, t, r, b)))
                        onDismissPanel()
                    },
                )
                ComicPanel.NONE -> Unit
            }
        }
    }
}

/* ══════════════ 顶栏 ══════════════ */

@Composable
private fun ComicTopBar(
    title: String,
    chapterTitle: String?,
    config: ComicReaderConfig,
    store: ComicSettingsStore,
    onApplyPresetConfig: (ComicReaderConfig) -> Unit,
    onExit: () -> Unit,
    onOpenPanel: (ComicPanel) -> Unit,
    onToggleMerge: () -> Unit,
    onGodMoment: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 收藏预设快捷应用（第 27 条）：长按"阅读设置"按钮直接弹出收藏列表一键应用
    var favMenuOpen by remember { mutableStateOf(false) }
    val favorites by remember { mutableStateOf(store.favoritePresets()) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(Color(0xD9101012))
            .border(0.5.dp, StrokeColor, RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onExit)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    chapterTitle ?: ("${config.mode.label} · ${config.direction.label}"),
                    color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            ChromeIconButton(Icons.Filled.AutoStories, "目录") { onOpenPanel(ComicPanel.TOC) }
            if (onGodMoment != null) {
                ChromeIconButton(Icons.Filled.StarOutline, "标记神回", tint = GodGold.DarkStart, onClick = onGodMoment)
            }
            // 长按 = 收藏预设快捷应用（第 27 条）；点击 = 打开设置面板
            Box {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .pointerInput(favorites.isNotEmpty()) {
                            detectTapGestures(
                                onLongPress = { if (favorites.isNotEmpty()) favMenuOpen = true },
                                onTap = { onOpenPanel(ComicPanel.SETTINGS) },
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Settings, "阅读设置",
                        tint = TextPrimary.copy(alpha = 0.92f), modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = favMenuOpen,
                    onDismissRequest = { favMenuOpen = false },
                    containerColor = Color(0xFF232327),
                ) {
                    Text(
                        "收藏预设",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    favorites.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name, color = TextPrimary) },
                            leadingIcon = { Text(p.emoji, color = MintPrimary, fontSize = 12.sp) },
                            onClick = {
                                favMenuOpen = false
                                onApplyPresetConfig(p.config)
                            },
                        )
                    }
                }
            }
            Box {
                ChromeIconButton(Icons.Filled.MoreHoriz, "更多") { menuOpen = true }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Color(0xFF232327),
                ) {
                    DropdownMenuItem(
                        text = { Text("临时合页 / 取消", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.AutoStories, null, tint = TextSecondary) },
                        onClick = { menuOpen = false; onToggleMerge() }
                    )
                    DropdownMenuItem(
                        text = { Text("手动裁边", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Settings, null, tint = TextSecondary) },
                        onClick = { menuOpen = false; onOpenPanel(ComicPanel.CROP) },
                    )
                    DropdownMenuItem(
                        text = { Text("阅读预设", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.ChevronLeft, null, tint = TextSecondary) },
                        onClick = { menuOpen = false; onOpenPanel(ComicPanel.PRESET) },
                    )
                }
            }
        }
    }
}

/* ══════════════ 底栏 ══════════════ */

@Composable
private fun ComicBottomBar(
    currentPage: Int,
    totalPages: Int,
    spreadCount: Int,
    currentSpread: Int,
    config: ComicReaderConfig,
    onThumbPreview: (Int) -> Unit,
    spreadFirstRaw: (Int) -> Int,
    autoRead: Boolean,
    onAutoReadToggle: () -> Unit,
    onJumpToSpread: (Int) -> Unit,
    onGoPrev: () -> Unit,
    onGoNext: () -> Unit,
    onPrevChapter: (() -> Unit)?,
    onNextChapter: (() -> Unit)?,
    chapterNavLabel: String,
    continuousFraction: Float = -1f,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableFloatStateOf(currentSpread.toFloat()) }
    val sliderEnabled = spreadCount > 1
    val reverse = config.direction == ComicDirection.RTL &&
        config.mode !in setOf(ComicMode.WEBTOON, ComicMode.CONTINUOUS)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color(0xD9101012))
            .border(0.5.dp, StrokeColor, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$currentPage / $totalPages",
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // 无缝滚动（第 6 条）：百分比按累计像素高度（连续无跳变）；
                // 其余模式按页数
                "· ${
                    if (continuousFraction in 0f..1f) (continuousFraction * 100).toInt()
                    else (currentPage.toFloat() / totalPages.coerceAtLeast(1) * 100).toInt()
                }%",
                color = TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (autoRead) "自动阅读中" else config.mode.label,
                // 状态文字默认次级色；自动阅读激活态才用强调色（强调色留给可交互/激活语义）
                color = if (autoRead) MintPrimary else TextSecondary,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            ChromeIconButton(if (reverse) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft, "上一页") { onGoPrev() }
            ChromeIconButton(
                if (autoRead) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (autoRead) "暂停自动阅读" else "开始自动阅读"
            ) { onAutoReadToggle() }
            ChromeIconButton(if (reverse) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, "下一页") { onGoNext() }
        }

        // 第七轮第 3 条：底栏进度滑条同步迁移到面板统一滑条（同一套视觉语言）
        PanelSlider(
            value = if (dragging) dragTarget else currentSpread.toFloat(),
            onValueChange = {
                dragging = true
                dragTarget = it
                val spreadIdx = it.toInt().coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
                onThumbPreview(spreadFirstRaw(spreadIdx))
            },
            onValueChangeFinished = {
                onJumpToSpread(dragTarget.toInt())
                dragging = false
                onThumbPreview(-1)
            },
            enabled = sliderEnabled,
            reverse = reverse,
            valueRange = 0f..(spreadCount - 1).coerceAtLeast(1).toFloat(),
        )

        if (onPrevChapter != null || onNextChapter != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChapterNavButton(
                    "上一$chapterNavLabel", enabled = onPrevChapter != null,
                    modifier = Modifier.weight(1f)
                ) { onPrevChapter?.invoke() }
                ChapterNavButton(
                    "下一$chapterNavLabel", enabled = onNextChapter != null,
                    modifier = Modifier.weight(1f)
                ) { onNextChapter?.invoke() }
            }
        }
    }
}

@Composable
private fun ChapterNavButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) PanelChipBg else Color(0x10FFFFFF))
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) TextPrimary else Color(0x55FFFFFF), fontSize = 12.sp)
    }
}

/** 滑条拖动时的页面缩略图气泡（悬浮，不占布局） */
@Composable
internal fun ComicThumbPreview(
    page: ComicPageRef?,
    rawShown: Int,
    loader: ComicPageLoader,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, page?.id) {
        page?.let { value = runCatching { loader.loadThumb(it) }.getOrNull() }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelBg)
            .border(0.5.dp, StrokeColor, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: com.example.ui.components.ChasingDots(
                size = 20.dp,
                color = MintPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("第 ${rawShown + 1} 页", color = TextPrimary, fontSize = 11.sp)
    }
}

/* ══════════════ 通用控件 ══════════════ */

@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    desc: String,
    tint: Color = TextPrimary.copy(alpha = 0.92f),
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

/** 分段选择器（id 由调用方定义）。
 *  FlowRow 流式布局：每项按文字实际宽度占位，一行放不下自动换行——
 *  修复"7 个手势选项/5 个长 label 挤同一行全部截断"的实机 bug（weight 均分压缩所致）。
 *  第七轮第 3 条：选中态 = 品牌薄荷半透明填充 + 细边框 + SemiBold 文字
 *  （不再只靠文字变色），与 Tab/场景/色卡同一套选中语言。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SegmentRow(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChipBg)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEach { (id, label) ->
                val active = id == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) panelSelectedBg() else Color.Transparent)
                        .border(
                            0.5.dp,
                            if (active) panelSelectedStroke() else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .clickableNoRipple { onSelect(id) }
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) MintPrimary else Color(0xAAFFFFFF),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/** 滑条设置行（trailing lambda = onChange，实时预览；持久化由上层防抖）。
 *  第七轮第 3 条：M3 Slider → 面板统一自绘滑条（细玻璃轨道 + 薄荷填充 +
 *  白色圆钮），全面板唯一滑条样式。 */
@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(format(value), color = MintPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        PanelSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
        )
    }
}

/** 开关行。第七轮第 3 条：M3 Switch → 面板统一玻璃胶囊开关。 */
@Composable
internal fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            subtitle?.let { Text(it, color = TextSecondary, fontSize = 11.sp) }
        }
        PanelSwitch(checked = checked, onChange = onChange)
    }
}

/** 通用底部面板容器（贴底、圆角、毛玻璃；横屏限宽居中避免占满全屏） */
@Composable
internal fun ComicSheetContainer(
    title: String,
    onDismiss: () -> Unit,
    heightFraction: Float = 0.66f,
    scrollable: Boolean = true,
    glassBackdrop: LayerBackdrop? = null,
    content: @Composable () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier
            // adaptiveSheetWidth = widthIn 必须在 fillMaxWidth 之前：先钳制最大宽度再填充——
            // 反序会让 fillMaxWidth 先撑满屏宽，560dp 背景钉在左侧留出无背景空区。
            // 横屏放宽到 840dp，与阅读设置面板（第 11 条）同一套自适应规范
            .adaptiveSheetWidth()
            .fillMaxHeight(heightFraction)
            .clip(sheetShape)
            .comicPanelGlass(glassBackdrop, sheetShape)
            .border(0.5.dp, StrokeColor, sheetShape)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0x2EFFFFFF))
            )
        }
        Text(
            title,
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 20.dp)
        ) {
            content()
        }
    }
}

/** 面板小节标题（次级色：强调色收敛到激活态控件与滑条数值） */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}
