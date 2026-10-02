package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomableWithScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.example.mangatranslate.LlmBubbleTranslator
import com.example.mangatranslate.OnlineFallbackTranslator
import com.example.mangatranslate.OrtSessions
import com.example.mangatranslate.TranslationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 目录条目（在线=章节；本地=页码） */
data class ComicTocEntry(val id: String, val title: String)

/** 面板层级 */
enum class ComicPanel { NONE, SETTINGS, TOC, PRESET, CROP }

/**
 * 漫画翻译烘焙代数（第十五轮）：每次译文位图替换缓存后 +1。
 * rememberPageBitmap 把它纳入 produceState 键——同 cacheKey 的位图被
 * 烘焙版本替换后重新播种（缓存命中即换图，不重解码）；CURL 引擎把它
 * 纳入预载 effect 键，重推纹理。
 */
val LocalComicTranslationEpoch = compositionLocalOf { 0 }

/**
 * GL 引擎（CURL/harism）是否激活：页面渲染在 GLSurfaceView 的独立 Surface 层，
 * 不参与 Compose 图层树（graphicsLayer alpha/layerBackdrop 均不可作用于其上；
 * 强行施加会引发 Surface 合成异常——如切换黑屏）。TTB+单页的 CURL 被既有
 * 语义强制走 Pager 路径（无 GL 视图）；MAGNETIC/WEBTOON/CONTINUOUS 恒走
 * Compose 路径（磁吸 Pager / LazyColumn），即便 pageAnim=CURL 也不存在 GL
 * 视图（第六轮 Agent C 补审 F5：此前误判导致这些组合的面板毛玻璃被无谓关闭）。
 * 与 ComicPagedReader 的引擎路由条件一致。
 */
internal fun curlEngineActive(pageAnim: ComicPageAnim, direction: ComicDirection, mode: ComicMode): Boolean =
    pageAnim == ComicPageAnim.CURL &&
        !(direction == ComicDirection.TTB && mode == ComicMode.SINGLE) &&
        mode != ComicMode.MAGNETIC &&
        mode != ComicMode.WEBTOON &&
        mode != ComicMode.CONTINUOUS

/**
 * 第 11 条毛玻璃采样开关（纯函数，单测钉死语义）：
 * - 仅面板打开期间采样：阅读期不挂 layerBackdrop，阅读内容零每帧重录开销；
 * - CURL 引擎页面在 GLSurfaceView（独立 Surface 层，Compose graphicsLayer 采不到，
 *   强行采样只会得到背景色而盖住页），回退纯半透明 PanelBg。
 */
internal fun panelGlassSamplingActive(
    pageAnim: ComicPageAnim,
    direction: ComicDirection,
    mode: ComicMode,
    panelOpen: Boolean,
): Boolean = panelOpen && !curlEngineActive(pageAnim, direction, mode)

/**
 * 音量键翻页桥（第 28 条）：阅读器组合期注册处理器，MainActivity 的
 * dispatchKeyEvent 优先咨询。handler 返回 true = 已消费（不弹系统音量条）；
 * 阅读器退出（onDispose）即注销——非阅读页音量键行为与系统原生完全一致。
 * 语义随阅读方向反转：LTR/TTB 下 VolDown=下一页；RTL（日漫物理方向）VolUp=下一页。
 */
object ComicVolumeKeyBridge {
    @Volatile
    var handler: ((isVolumeUp: Boolean, isDownAction: Boolean) -> Boolean)? = null

    /** Activity 层入口：返回 true 表示事件被阅读器消费 */
    fun dispatch(keyCode: Int, isDownAction: Boolean): Boolean {
        val h = handler ?: return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> h(true, isDownAction)
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> h(false, isDownAction)
            else -> false
        }
    }
}

/**
 * 统一漫画阅读引擎：本地 / 在线共用。
 * 沉浸式阅读 + 轻量控制栏 + 分层设置；五种阅读模式、方向、缩放手势、
 * 图像管线、场景、自动阅读、进度缩略图、目录与连续阅读全部集中于此。
 *
 * @param bookKey 每漫画配置键（配置 + 状态）
 * @param progressKey 每漫画进度键（在线场景细到章节；null 时不恢复/保存页级进度）
 */
@Composable
fun ComicReaderCore(
    pages: List<ComicPageRef>,
    title: String,
    chapterTitle: String?,
    bookKey: String?,
    initialPage: Int,
    modifier: Modifier = Modifier,
    toc: List<ComicTocEntry> = emptyList(),
    currentChapterIndex: Int = -1,
    onJumpToChapter: ((Int) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    chapterNavLabel: String = "章节",
    onPageChanged: (rawPage: Int, isFinished: Boolean) -> Unit = { _, _ -> },
    remoteImageLoader: ImageLoader? = null,
    /** 工具栏神回入口；未提供时不显示。 */
    onGodMoment: (() -> Unit)? = null,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ComicSettingsStore(context) }

    /* ── 配置与每漫画状态 ── */
    val effective = remember(bookKey) { store.effectiveConfig(bookKey) }
    var config by remember(bookKey) { mutableStateOf(effective.config) }
    var bookState by remember(bookKey) { mutableStateOf(store.loadBookState(bookKey ?: "")) }
    var perBookConfig by remember(bookKey) { mutableStateOf(effective.hasOverride) }

    // 配置防抖持久化：滑条拖动只在停止后写盘（退出时 flush）
    var persistJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            persistJob?.cancel()
            if (bookKey != null) {
                if (perBookConfig) store.saveBookConfig(bookKey, config)
                else store.saveGlobalConfig(config)
            } else store.saveGlobalConfig(config)
        }
    }
    fun persistConfig(newConfig: ComicReaderConfig, immediate: Boolean = false) {
        config = newConfig
        persistJob?.cancel()
        if (immediate) {
            if (perBookConfig && bookKey != null) store.saveBookConfig(bookKey, newConfig)
            else {
                store.saveGlobalConfig(newConfig)
                if (bookKey != null) store.clearBookConfig(bookKey)
            }
        } else {
            persistJob = scope.launch {
                delay(250)
                if (perBookConfig && bookKey != null) store.saveBookConfig(bookKey, config)
                else {
                    store.saveGlobalConfig(config)
                    if (bookKey != null) store.clearBookConfig(bookKey)
                }
            }
        }
    }

    fun updateConfig(transform: (ComicReaderConfig) -> ComicReaderConfig, immediate: Boolean = false) {
        persistConfig(transform(config), immediate)
    }

    // 滤镜滑条节流：拖动中每个 tick 都改 config 会让阅读页按新指纹逐次重解码+全管线
    // 重算（2800px×数十次）。displayConfig 落后 config 250ms——滑条拖动期间只有
    // 设置面板里的小图预览（loadPreview 540px）实时，松手静止后阅读页才重算一次
    var displayConfig by remember { mutableStateOf(config) }
    LaunchedEffect(Unit) {
        snapshotFlow { config }
            .debounce(250)
            .collect { displayConfig = it }
    }

    /* ── 加载器与布局 ── */
    val loader = remember(remoteImageLoader) { ComicPageLoader(context, remoteImageLoader) }
    DisposableEffect(loader) {
        onDispose {
            loader.clearProcessedCache()
            loader.shutdown()
        }
    }

    /* ── 漫画翻译协调器（第十五/十六/十八轮）：YOLO 气泡+离线 OCR → 用户选定引擎 → 形状烘焙回缓存 ── */
    OrtSessions.ensureCacheRoot(context)
    val translationCoordinator = remember {
        TranslationCoordinator(context, OnlineFallbackTranslator(context.applicationContext), LlmBubbleTranslator(context))
    }
    // 用户显式选择的引擎（第十八轮）随配置同步；换引擎同页自动重译（缓存 key 含引擎标识）
    translationCoordinator.selectedEngine = config.translationEngine
    val translationScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val translationEpoch by translationCoordinator.epoch.collectAsStateWithLifecycle()
    val translatingPages by translationCoordinator.busyKeys.collectAsStateWithLifecycle()
    DisposableEffect(translationCoordinator) {
        onDispose {
            // release = 取消任务 + 归还 ONNX 会话内存（det/rec/yolo 约 60-100MB 常驻）
            translationCoordinator.release()
            translationScope.cancel()
        }
    }
    // 开关/字号/语言/引擎变化：解除烘焙页驻留 → epoch+1 触发重播种（关闭=回原文；改字号=重烘焙）
    var lastTranslationSig by remember {
        mutableStateOf(
            listOf(config.translationEnabled, config.translationTextScale, config.translationLang, config.translationEngine)
        )
    }
    LaunchedEffect(config.translationEnabled, config.translationTextScale, config.translationLang, config.translationEngine) {
        val prev = lastTranslationSig
        val now = listOf(config.translationEnabled, config.translationTextScale, config.translationLang, config.translationEngine)
        lastTranslationSig = now
        if (prev == now) return@LaunchedEffect
        val wasOn = prev.firstOrNull() == true
        translationCoordinator.cancelAll()
        translationCoordinator.selectedEngine = config.translationEngine
        if (wasOn) {
            translationCoordinator.takeBakedKeys().forEach { loader.evictProcessed(it) }
            translationCoordinator.resetMarks()
            translationCoordinator.bumpEpoch()
            // 真正关闭翻译（非改语言/引擎）：立即归还 ONNX 会话内存（重开自动重建，秒级）
            if (!config.translationEnabled) {
                translationCoordinator.release()
            }
        }
    }
    /** 翻译窗口调度：与预载窗口同键，烘焙结果 replaceProcessed 同键覆盖。 */
    fun scheduleTranslationWindow(entries: List<ComicPageLoader.WindowEntry>, cfg: ComicReaderConfig) {
        if (!cfg.translationEnabled) return
        val forcedLang = cfg.translationLang.takeIf { it != "auto" }
        entries.forEach { entry ->
            translationCoordinator.schedule(
                scope = translationScope,
                cacheKey = entry.cacheKey,
                translationKey = entry.cacheKey,
                forcedLang = forcedLang,
                textScale = cfg.translationTextScale,
                loadBase = {
                    loader.loadForDisplay(ref = entry.ref, cacheKey = entry.cacheKey, geo = entry.geo, tone = toneOf(cfg), visible = entry.visible).bitmap
                },
                publishBaked = { loader.replaceProcessed(entry.cacheKey, it) },
            )
        }
    }

    // 尺寸到达防抖合批（在线逐页到达时避免整布局每页重建）
    val sizes by loader.sizes.debounce(120).produceState0(emptyMap())
    // 本地页批量预取原始尺寸（宽页拆分判定需要；顺序探测避免抢占 CPU）
    LaunchedEffect(pages) {
        loader.probeSizes(pages.take(600))
    }

    val verticalMode = config.mode == ComicMode.WEBTOON || config.mode == ComicMode.CONTINUOUS

    // 垂直模式忽略临时合页（垂直单项只渲染单槽，合页会导致丢页）
    val layoutBookState = if (verticalMode) bookState.copy(mergeAnchors = emptySet()) else bookState
    val layout = remember(config.mode, config.direction, config.doubleFirstAlone, config.splitWide,
        config.splitReverse, config.splitPosition, pages, sizes, layoutBookState.mergeAnchors) {
        ComicPageLayout.build(pages, sizes, config, layoutBookState)
    }

    /* ── 当前位置：布局重建时保持原始页（而非 spread 索引） ── */
    var lastRawPage by remember(pages) {
        mutableIntStateOf(initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }
    // 页级进度恢复：仅当页列表没变（同一章）时才恢复 lastPage；换章回 0
    val chapterSig = remember(pages) { pages.firstOrNull()?.id }
    val startRaw = remember(pages, bookKey) {
        val fromArg = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val sameChapter = bookState.lastChapterSig == null || bookState.lastChapterSig == chapterSig
        if (fromArg > 0) fromArg
        else if (sameChapter) bookState.lastPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        else 0
    }
    var currentSpread by remember(pages) {
        mutableIntStateOf(
            ComicPageLayout.build(pages, sizes, config, layoutBookState).spreadOfRawPage(startRaw)
        )
    }
    LaunchedEffect(layout) {
        // 直接以记录的原始页为准（spread 索引在布局变化后语义不同）
        currentSpread = layout.spreadOfRawPage(lastRawPage).coerceIn(0, (layout.spreadCount - 1).coerceAtLeast(0))
    }

    val spreadCount = layout.spreadCount
    val currentSpreadData = layout.spreads.getOrNull(currentSpread)
    val currentPageNum = (currentSpreadData?.firstRawIndex ?: 0) + 1
    val totalPages = pages.size

    // 进度持久化（页变化即保存；仅内存写盘由调用方决定）
    LaunchedEffect(currentSpread, layout) {
        val raw = layout.spreads.getOrNull(currentSpread)?.firstRawIndex ?: return@LaunchedEffect
        lastRawPage = raw
        onPageChanged(raw, raw >= totalPages - 1)
        // debug 探针信标：交互矩阵脚本用 logcat 瞬时读取当前页（比 uiautomator 稳定）
        if (com.example.BuildConfig.DEBUG) {
            android.util.Log.d("ComicProbePage", "raw=${raw + 1}")
        }
        if (bookKey != null) {
            bookState = bookState.copy(lastPage = raw, lastChapterSig = chapterSig)
            store.saveBookState(bookKey, bookState)
        }
    }

    /* ── 控制层 / 面板 ── */
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(ComicPanel.NONE) }
    var autoRead by remember { mutableStateOf(false) }
    // 无缝滚动像素级进度（第 6 条）：0..1，底栏百分比按此显示
    var scrollFraction by remember { mutableStateOf(0f) }
    val latestExit by rememberUpdatedState(onExit)

    var chapterNavigationPending by remember(pages) { mutableStateOf(false) }
    fun navigateChapter(next: Boolean): Boolean {
        val action = (if (next) onNextChapter else onPrevChapter) ?: return false
        if (chapterNavigationPending) return false
        chapterNavigationPending = true
        action()
        return true
    }

    // 沉浸式系统栏（退出阅读器时恢复，避免影响其它页面）
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(config.hideSystemBars, controlsVisible) {
        val window = (view.context as? android.app.Activity)?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (config.hideSystemBars && !controlsVisible) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    /* ── 翻页操作：首末页继续外翻进入相邻章节。 ── */
    fun goNext(): Boolean {
        if (currentSpread < spreadCount - 1) {
            currentSpread++
            return true
        }
        if (navigateChapter(next = true)) return true
        if (autoRead) autoRead = false
        return false
    }

    fun goPrev() {
        if (currentSpread > 0) currentSpread--
        else navigateChapter(next = false)
    }

    /* ── 手势回调（点按区方向感知） ── */
    var listTapSize by remember { mutableStateOf(Size(1f, 1f)) }

    /* ── 音量键翻页（第 28 条）：仅本阅读器存活期间接管；方向感知 ── */
    DisposableEffect(config.volumeKeyTurn, config.direction) {
        if (config.volumeKeyTurn) {
            ComicVolumeKeyBridge.handler = { isUp, isDownAction ->
                if (!isDownAction) {
                    true   // UP 事件一并吞掉（防系统音量条），但不重复翻页
                } else {
                    val next = if (config.direction == ComicDirection.RTL) isUp else !isUp
                    if (next) goNext() else goPrev()
                    true
                }
            }
        } else {
            ComicVolumeKeyBridge.handler = null
        }
        onDispose {
            if (ComicVolumeKeyBridge.handler != null) ComicVolumeKeyBridge.handler = null
        }
    }
    val gestureCallbacks = ComicGestureCallbacks(
        onTapZone = { pos, size ->
            when (resolveTapAction(pos, size, config)) {
                ComicGestureAction.PREV -> {
                    // 第 23 条：栏可见时点击优先隐藏栏，不直接翻页；
                    // 栏隐藏后点击才正常翻页（TOC/设置/退出等 UI 意图不受影响）
                    if (controlsVisible) controlsVisible = false else goPrev()
                }
                ComicGestureAction.NEXT -> {
                    if (controlsVisible) controlsVisible = false else goNext()
                }
                ComicGestureAction.TOGGLE_CONTROLS -> {
                    controlsVisible = !controlsVisible
                    if (!controlsVisible) panel = ComicPanel.NONE
                }
                ComicGestureAction.TOC -> { controlsVisible = true; panel = ComicPanel.TOC }
                ComicGestureAction.SETTINGS -> { controlsVisible = true; panel = ComicPanel.SETTINGS }
                ComicGestureAction.EXIT -> latestExit()
                ComicGestureAction.NONE -> Unit
            }
        },
        onLongPress = {
            controlsVisible = true
        },
        onPinchClose = { latestExit() },
        onEdgeBack = { latestExit() },
        onZoomedEdgeSwipe = { dir ->
            // dir>0：手指向右拖。LTR：回到上一页；RTL：读完全页（内容左缘）进入下一页
            if (config.direction == ComicDirection.RTL) {
                if (dir > 0) goNext() else goPrev()
            } else {
                if (dir > 0) goPrev() else goNext()
            }
        },
    )

    /* ── 动态背景主色 ── */
    var dynamicBg by remember { mutableStateOf(Color(0xFF101014)) }
    fun feedDynamicBg(bmp: Bitmap) {
        scope.launch(Dispatchers.Default) {
            val c = runCatching { ComicImagePipeline.dominantBackground(bmp) }.getOrNull()
            if (com.example.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "ComicDynBg",
                    "dominant=0x%06X bmp=%dx%d".format(c ?: 0, bmp.width, bmp.height),
                )
            }
            if (c != null) withContext(Dispatchers.Main) { dynamicBg = Color(c) }
        }
    }

    /* ── 相邻页预加载（页变化时执行，读取当下配置）──
     * 5.5 修复：整窗一次提交 preloadWindow。旧实现逐槽多次调用 preload，
     *  每次调用的"批次外取消"会取消同窗口其它槽位刚入队的任务（双页只剩
     *  下一跨最后槽位真正预载、后退几乎从不预载）+ 主 LRU 上限装不下窗口
     *  字节（增强页 ~29MB×6 槽）→ 翻页时重新解码 = 偶现加载圈。 */
    val translationModelState by com.example.mangatranslate.TranslateModelManager.state.collectAsStateWithLifecycle()
    LaunchedEffect(currentSpread, layout, displayConfig, translationModelState) {
        if (!verticalMode) {
            val entries = ArrayList<ComicPageLoader.WindowEntry>(6)
            // 驻留优先级序：当前 → 下一 → 上一（预算不足时当前/下一页优先保活）。
            // 读 displayConfig（防抖副本）与渲染/播种路径同源（Agent C 补审 F2 修正：
            // 旧读实时 config，滤镜拖动后 250ms 内翻页会以新指纹预载、旧指纹显示，
            // 同页双份处理；displayConfig 入 key 后指纹落定即自动重预载并重建驻留，
            // 旧指纹驻留项随之解除——同时消解 F4 的陈旧驻留残留）。
            val online = pages.any { it is ComicPageRef.Remote }
            val order = if (online) intArrayOf(currentSpread, currentSpread + 1, currentSpread + 2, currentSpread - 1)
                else intArrayOf(currentSpread, currentSpread + 1, currentSpread - 1)
            order.filter { it in layout.spreads.indices }.forEach { si ->
                layout.spreads[si].slots.forEach { slot ->
                    val rot = ((displayConfig.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360
                    entries.add(
                        ComicPageLoader.WindowEntry(
                            ref = slot.ref,
                            visible = si == currentSpread,
                            cacheKey = "${slot.ref.id}|${slot.half}|${displayConfig.imagePipelineFingerprint()}|r$rot",
                            geo = ComicImagePipeline.Geometry(
                                half = slot.half,
                                splitPosition = effectiveSplitPosition(displayConfig, loader.sizes.value[slot.ref.id]?.gutterPos),
                                rotationDeg = rot, cropMode = displayConfig.cropMode, manualCrop = displayConfig.manualCrop,
                            ),
                        ),
                    )
                }
            }
            loader.preloadWindow(entries, toneOf(displayConfig))
            scheduleTranslationWindow(entries, displayConfig)
        }
    }

    /* ── 模式渲染 ── */
    // 第 11 条毛玻璃采样源：面板打开期间才把模式渲染层挂上 layerBackdrop，
    // 供设置/目录/预设面板 drawBackdrop 高斯模糊采样（与底部 Tab 栏同机制）。
    // remember 无条件调用（Compose 规范），仅按需挂 modifier——阅读期零每帧重录开销
    val readerLayerBackdrop = rememberLayerBackdrop()
    val panelGlassBackdrop: LayerBackdrop? =
        if (panelGlassSamplingActive(config.pageAnim, config.direction, config.mode, panel != ComicPanel.NONE)) {
            readerLayerBackdrop
        } else null
    CompositionLocalProvider(LocalComicTranslationEpoch provides translationEpoch) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (panelGlassBackdrop != null) Modifier.layerBackdrop(panelGlassBackdrop) else Modifier)
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()

        ComicReaderBackground(
            bgType = config.bgType,
            paperIntensity = config.paperIntensity,
            dynamicColor = if (config.bgType == ComicBgType.DYNAMIC) dynamicBg else null,
        )

        // 补1：模式/方向/动画/布局切换平滑过渡——签名变化时 240ms 挂钟淡入替代瞬切。
        // 挂钟自算进度 + withFrameNanos 只作帧节拍（免疫模拟器假 vsync 时间戳直接落终值）；
        // 锚点由 lastRawPage 链路保证不变，此处纯视觉层，不影响手势与命中区域
        val engineSig = "${config.mode}|${config.direction}|${displayConfig.pageAnim}|${layout.spreadCount}"
        val engineIsGl = curlEngineActive(displayConfig.pageAnim, config.direction, config.mode)
        var engineFade by remember { mutableFloatStateOf(1f) }
        var lastEngineSig by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(engineSig, engineIsGl) {
            // 首次组合不淡入（进入即完整画面）；仅真实切换（模式/方向/动画/换章）才过渡。
            // GL 引擎（CURL）跳过：GLSurfaceView 不吃 Compose 层 alpha，施加反而引发
            // Surface 合成异常（实测方向切换黑屏）——淡入仅用于 Compose 渲染路径。
            // 帧计数上限是双保险：Robolectric 冻结 uptime 时挂钟永不超时，靠上限退出保空闲
            val prev = lastEngineSig
            lastEngineSig = engineSig
            if (prev == null || engineIsGl) return@LaunchedEffect
            val start = android.os.SystemClock.uptimeMillis()
            var ticks = 0
            while (ticks < 90) {
                engineFade = engineFadeAlpha(android.os.SystemClock.uptimeMillis() - start)
                if (android.os.SystemClock.uptimeMillis() - start >= 240) {
                    engineFade = 1f
                    break
                }
                ticks++
                withFrameNanos { }
            }
            engineFade = 1f
        }
        // GL 引擎完全不挂 graphicsLayer（连 alpha=1 的合成层也不给，规避 Surface 异常）
        Box(
            Modifier.fillMaxSize().then(
                if (engineIsGl) Modifier
                else Modifier.graphicsLayer { alpha = engineFade }
            )
        ) {
        when {
            verticalMode -> ComicVerticalList(
                layout = layout, config = displayConfig, loader = loader, bookState = bookState,
                currentSpread = currentSpread,
                onSpreadChanged = { currentSpread = it },
                translationModelState = translationModelState,
                onScheduleTranslation = ::scheduleTranslationWindow,
                tapZoneAction = gestureCallbacks.onTapZone,
                tapAreaSize = { listTapSize },
                onAreaSizeChanged = { listTapSize = it },
                onBitmapShown = ::feedDynamicBg,
                autoScrollActive = autoRead,
                autoScrollSpeedDp = config.autoScrollSpeedDp,
                onReachEnd = { goNext() },
                onScrollFraction = { scrollFraction = it },
                onPreviousChapter = { navigateChapter(next = false) },
                onNextChapter = { navigateChapter(next = true) },
            )
            config.mode == ComicMode.MAGNETIC -> ComicMagneticPager(
                layout = layout, config = displayConfig, loader = loader, bookState = bookState,
                currentSpread = currentSpread,
                onSpreadChanged = { currentSpread = it },
                gestureCallbacks = gestureCallbacks,
                containerW = containerW, containerH = containerH,
                onBitmapShown = ::feedDynamicBg,
                autoRead = autoRead,
                autoIntervalSec = config.autoPageIntervalSec,
                goNext = { goNext() },
                goPrev = { goPrev() },
            )
            else -> ComicPagedReader(
                layout = layout, config = displayConfig, loader = loader, bookState = bookState,
                currentSpread = currentSpread,
                onSpreadChanged = { currentSpread = it },
                gestureCallbacks = gestureCallbacks,
                onBitmapShown = ::feedDynamicBg,
                autoRead = autoRead,
                autoIntervalSec = config.autoPageIntervalSec,
                goNext = { goNext() },
                dynamicBgColor = if (config.bgType == ComicBgType.DYNAMIC) dynamicBg else null,
                goPrev = { goPrev() },
            )
        }
        }

        // 场景特效层（与声音独立）
        if (config.sceneEffect && config.scene != ComicScene.NONE) {
            ComicSceneEffectOverlay(scene = config.scene, modifier = Modifier.fillMaxSize())
        }

        // 翻译进行中角标（第十五轮）：当前/预取窗口任一页在译时轻提示
        if (config.translationEnabled && translatingPages.isNotEmpty() && panel == ComicPanel.NONE) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(Color(0xB3141416))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("正在翻译…", color = Color(0xE6FFFFFF), fontSize = 12.sp)
            }
        }
    }
    }   // CompositionLocalProvider(LocalComicTranslationEpoch)

    /* ── 场景环境音（第 21 条：真实录音素材循环播放，合成仅兜底） ── */
    val audio = remember { ComicAmbientAudio.create(context) }
    LaunchedEffect(config.scene, config.sceneSound) {
        if (config.scene != ComicScene.NONE && config.sceneSound) {
            audio.start(config.scene, config.sceneVolume / 100f * 0.6f)
        } else audio.stop()
    }
    LaunchedEffect(config.sceneVolume) {
        if (config.scene != ComicScene.NONE && config.sceneSound) {
            audio.setVolume(config.sceneVolume / 100f * 0.6f)
        }
    }
    DisposableEffect(Unit) { onDispose { audio.stop() } }

    /* ── 控制层与面板 ── */
    ComicReaderChrome(
        visible = controlsVisible,
        panel = panel,
        onPanelChange = { panel = it },
        panelGlassBackdrop = panelGlassBackdrop,
        title = title,
        chapterTitle = chapterTitle,
        currentPage = currentPageNum,
        totalPages = totalPages,
        spreadCount = spreadCount,
        currentSpread = currentSpread,
        config = config,
        store = store,
        perBookConfig = perBookConfig,
        onPerBookConfigChange = { enabled ->
            perBookConfig = enabled
            if (enabled && bookKey != null) store.saveBookConfig(bookKey, config)
            else if (bookKey != null) {
                store.clearBookConfig(bookKey)
                store.saveGlobalConfig(config)
            }
        },
        onConfigChange = { persistConfig(it, immediate = false) },
        onJumpToSpread = { target ->
            currentSpread = target.coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
        },
        spreadFirstRaw = { spreadIdx ->
            layout.spreads.getOrNull(spreadIdx)?.firstRawIndex ?: 0
        },
        onJumpToRawPage = { raw ->
            currentSpread = layout.spreadOfRawPage(raw.coerceIn(0, (totalPages - 1).coerceAtLeast(0)))
        },
        onGoPrev = { goPrev() },
        onGoNext = { goNext() },
        autoRead = autoRead,
        onAutoReadToggle = { autoRead = !autoRead },
        continuousFraction = if (config.mode == ComicMode.CONTINUOUS) scrollFraction else -1f,
        toc = toc,
        currentChapterIndex = currentChapterIndex,
        onJumpToChapter = onJumpToChapter,
        onPrevChapter = onPrevChapter?.let { { navigateChapter(next = false); Unit } },
        onNextChapter = onNextChapter?.let { { navigateChapter(next = true); Unit } },
        onGodMoment = onGodMoment?.let { open -> { autoRead = false; open() } },
        chapterNavLabel = chapterNavLabel,
        pages = pages,
        loader = loader,
        bookState = bookState,
        onBookStateChange = { bookState = it; if (bookKey != null) store.saveBookState(bookKey, it) },
        onToggleControls = { controlsVisible = !controlsVisible },
        onExit = { latestExit() },
    )
}

/** 配置 → 管线调色参数 */
internal fun toneOf(config: ComicReaderConfig) = ComicImagePipeline.Toning(
    brightness = config.filterBrightness,
    contrast = config.filterContrast,
    saturation = config.filterSaturation,
    hue = config.filterHue,
    gamma = config.filterGamma,
    sharpen = config.filterSharpen,
    shadow = config.filterShadow,
    bw = config.filterBW,
    enhanceMode = config.enhanceMode,
    enhanceStrength = config.enhanceStrength,
)

/**
 * 有效拆分位置（第 19 条）：以 detectCenterGutterDetail 检测到的装订缝位置为基准，
 * 用户的"拆分位置"滑条作为微调偏移（0.5=不偏移）——拆分点真正落在装订缝上，
 * 而非简单二分中点；无缝探测结果时基准回落 0.5。
 */
internal fun effectiveSplitPosition(config: ComicReaderConfig, detectedGutterPos: Float?): Float {
    val base = detectedGutterPos?.takeIf { !it.isNaN() } ?: 0.5f
    return (base + (config.splitPosition - 0.5f)).coerceIn(0.3f, 0.7f)
}

/** Flow → State 简写（初值即时，后续防抖） */
@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.produceState0(initial: T): State<T> =
    produceState(initial, this) {
        this@produceState0.collect { value = it }
    }

/* ══════════════ 点按区解析 ══════════════ */

/**
 * 方向感知点按区：水平模式左右三分（"上一页侧/下一页侧"随 RTL 翻转），
 * 垂直模式上下三分。双页+TTB 组合仍按水平解析（双页为横向排版）。
 */
fun resolveTapAction(pos: Offset, size: Size, config: ComicReaderConfig): ComicGestureAction {
    val vertical = config.mode == ComicMode.WEBTOON || config.mode == ComicMode.CONTINUOUS ||
        (config.direction == ComicDirection.TTB && config.mode != ComicMode.DOUBLE)
    return if (vertical) {
        val third = size.height / 3f
        when {
            pos.y < third -> config.gestureTapLeft   // 上 = 逻辑上一页侧
            pos.y > third * 2 -> config.gestureTapRight
            else -> config.gestureTapCenter
        }
    } else {
        val third = size.width / 3f
        val rtl = config.direction == ComicDirection.RTL
        when {
            pos.x < third -> if (rtl) config.gestureTapRight else config.gestureTapLeft
            pos.x > third * 2 -> if (rtl) config.gestureTapLeft else config.gestureTapRight
            else -> config.gestureTapCenter
        }
    }
}

/* ══════════════ 页面位图加载 ══════════════ */

sealed interface PageBitmapState {
    data object Loading : PageBitmapState
    data class Ready(val bitmap: Bitmap, val refining: Boolean = false, val error: Throwable? = null) : PageBitmapState
    data class Failed(val error: Throwable?) : PageBitmapState
}

@Composable
fun rememberPageBitmap(
    loader: ComicPageLoader,
    slot: ComicSlot?,
    config: ComicReaderConfig,
    bookState: ComicBookState,
    retry: Int = 0,
    visible: Boolean = true,
): State<PageBitmapState> {
    // 可空槽位：恒定返回 Loading 态（两个分支各占 1 个 remember 节点，组合结构稳定，
    // 供调用方以固定调用结构避免条件 composable 崩溃）
    if (slot == null) return remember { mutableStateOf<PageBitmapState>(PageBitmapState.Loading) }
    val pageRot = (bookState.pageRotations[slot.ref.id] ?: 0)
    val rotation = ((config.bookRotation + pageRot) % 360 + 360) % 360
    val cacheKey = "${slot.ref.id}|${slot.half}|${config.imagePipelineFingerprint()}|r$rotation"
    // 第 19 条：拆分位置 = 检测缝位置 + 用户微调（loader.sizes 有探测结论时）
    val splitPos = effectiveSplitPosition(config, loader.sizes.value[slot.ref.id]?.gutterPos)
    // 第六轮族 A 根治：初值同步读缓存——loader 缓存是页面位图状态的单一数据源，
    // 缓存命中时第一帧即 Ready，不存在"先 Loading 占位、加载完再纠正"的中间态。
    // （磁吸 for+key 窗口平移时 remember 实测全部重建——KeyMoveSemanticsTest——
    //   若初值恒 Loading，每次翻页三窗口齐回加载态，黑屏 0.7~1.2s。）
    fun seed(): PageBitmapState {
        val hit = loader.peekProcessed(cacheKey)
        // 5.5 验证信标（debug only）：组合期播种未命中 = 该页至少出现一帧 Loading。
        // 稳态顺序翻页时预载窗口应全部命中；此日志出现即"加载圈"发生的确切页位。
        if (hit == null && com.example.BuildConfig.DEBUG) {
            android.util.Log.d("ComicSeedMiss", "id=${slot.ref.id}")
        }
        // 第十九轮：翻译烘焙后种子应换烤图——命中即记录（对照 MTPerf bake 完成）。
        if (com.example.BuildConfig.DEBUG) {
            android.util.Log.d(
                "MTSeed",
                "seed=${cacheKey.take(44)} hit=${hit != null}",
            )
        }
        return hit?.let { PageBitmapState.Ready(it) }
            ?: loader.peekReadingPreview(cacheKey)?.let { PageBitmapState.Ready(it, refining = true) }
            ?: PageBitmapState.Loading
    }
    val translationEpoch = LocalComicTranslationEpoch.current
    // produceState retains its old value when only its producer keys change. Key the
    // state itself so a new page cannot display the previous page even for one frame.
    val state = remember(loader, slot.ref, cacheKey, retry, translationEpoch) { mutableStateOf(seed()) }
    LaunchedEffect(loader, slot.ref, cacheKey, retry, translationEpoch, visible) {
        val previews = launch {
            loader.previewEpoch.collect {
                if (loader.peekProcessed(cacheKey) == null) loader.peekReadingPreview(cacheKey)?.let {
                    state.value = PageBitmapState.Ready(it, refining = true)
                }
            }
        }
        try {
            val result = loader.loadForDisplay(
                ref = slot.ref,
                cacheKey = cacheKey,
                geo = ComicImagePipeline.Geometry(
                    half = slot.half,
                    splitPosition = splitPos,
                    rotationDeg = rotation,
                    cropMode = config.cropMode,
                    manualCrop = config.manualCrop,
                ),
                tone = toneOf(config),
                visible = visible,
            )
            previews.cancel()
            state.value = PageBitmapState.Ready(result.bitmap)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            state.value = (state.value as? PageBitmapState.Ready)?.copy(refining = false, error = error)
                ?: PageBitmapState.Failed(error)
        } finally {
            previews.cancel()
        }
    }
    return state
}

/* ══════════════ 分页阅读（单页/双页） ══════════════ */

/**
 * FADE 渐变模式的位移抵消（第 5 条，纯函数可单测）。
 * pager 布局位移方向随引擎镜像：LTR 横向 = -off·w（旧页左移）、RTL
 * reverseLayout 水平轴镜像 = +off·w（旧页右移）、TTB 纵向 = -off·h；
 * 抵消平移必须逐引擎取反向号——统一 +off·w 在 RTL 下会加倍漂移
 * （第三轮逐帧复审实测旧页 +2/+14/+60px 递增即此因）。
 */
internal fun fadeCancelOffset(pageOffset: Float, rtl: Boolean, ttb: Boolean, w: Float, h: Float): Pair<Float, Float> =
    when {
        ttb -> 0f to pageOffset * h
        rtl -> -pageOffset * w to 0f
        else -> pageOffset * w to 0f
    }

/**
 * FADE 真交叉淡化 alpha（第 5 条，纯函数可单测）：离场页（off≥0）保持
 * 不透明作底，进场页（off<0）alpha=1+off 自 0 淡入；进场页层序在上，
 * 合成恒为 (1-p)·旧页 + p·新页，中点不下陷发黑（旧版两页同衰减到 0.08，
 * 拖拽中段整屏发黑即此病）。
 */
internal fun fadeCrossAlpha(pageOffset: Float): Float =
    if (pageOffset >= 0f) 1f else (1f + pageOffset).coerceIn(0f, 1f)

@Composable
private fun ComicPagedReader(
    layout: ComicLayout,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    currentSpread: Int,
    onSpreadChanged: (Int) -> Unit,
    gestureCallbacks: ComicGestureCallbacks,
    onBitmapShown: (Bitmap) -> Unit,
    autoRead: Boolean,
    autoIntervalSec: Float,
    goNext: () -> Unit,
    goPrev: () -> Unit,
    dynamicBgColor: Color? = null,
) {
    val rtl = config.direction == ComicDirection.RTL
    val ttb = config.direction == ComicDirection.TTB

    // TTB 竖向阅读与 CURL 横向卷页语义冲突（卷页手势只识别横向拖动，
    // 组合下滑动翻页会完全失灵）——TTB 单页强制走 Pager 路径。
    // 引擎判定与 curlEngineActive 单一数据源（第六轮 Agent C 补审：消除双份条件漂移）
    if (curlEngineActive(config.pageAnim, config.direction, config.mode)) {
        // harism/android-pagecurl 原版 GL 引擎（用户指示：照搬开源不手写）
        ComicHarismCurlReader(
            layout = layout, config = config, loader = loader, bookState = bookState,
            currentSpread = currentSpread, onSpreadChanged = onSpreadChanged,
            gestureCallbacks = gestureCallbacks, onBitmapShown = onBitmapShown,
            autoRead = autoRead, autoIntervalSec = autoIntervalSec, goNext = goNext,
            dynamicBgColor = dynamicBgColor,
            goPrev = goPrev,
        )
        return
    }

    val pagerState = rememberPagerState(initialPage = currentSpread) { layout.spreadCount }
    // 第 6 节：翻页落定轻触觉（VIRTUAL_KEY 轻击感，无震动马达噪声）
    val hapticView = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                if (com.example.ui.feedback.HapticsGate.enabled) {
                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                onSpreadChanged(settled)
            }
    }
    LaunchedEffect(currentSpread) {
        if (pagerState.settledPage != currentSpread && !pagerState.isScrollInProgress) {
            // 大跨度（目录/进度条跳转、设置变更锚点恢复）：瞬切，不逐页动画
            //（动画滚过数十页既慢于 200ms 要求、又造成"跳变串场"观感）
            if (abs(currentSpread - pagerState.settledPage) > 1) {
                pagerState.scrollToPage(currentSpread)
            } else if (config.pageAnim == ComicPageAnim.NONE) {
                pagerState.scrollToPage(currentSpread)
            } else {
                pagerState.animateScrollToPage(currentSpread)
            }
        }
    }
    if (autoRead) {
        LaunchedEffect(autoRead, autoIntervalSec, currentSpread) {
            delay((autoIntervalSec * 1000).toLong())
            if (currentSpread < layout.spreadCount - 1) onSpreadChanged(currentSpread + 1) else goNext()
        }
    }

    /* ── 第 5 条：渐变 = 纯交叉淡入淡出（抵消位移），平移 = 真实位移 + 越界回弹 ── */
    val isFade = config.pageAnim == ComicPageAnim.FADE
    val isSlide = config.pageAnim == ComicPageAnim.SLIDE
    // 第 9 条：单/双页模式页间距——每页两侧各留 spacing/2，翻页拖动时页间露出设定间距
    val spacingHalf = (config.pageSpacingDp / 2f).dp

    val childZoomed = remember { mutableStateOf(false) }
    val cell: @Composable (Int) -> Unit = { idx ->
        val spread = layout.spreads.getOrNull(idx)
        if (spread != null) {
            if (isFade) {
                // 渐变：graphicsLayer 读 pager 偏移（draw 阶段，零重组）——
                // translation 全额抵消 pager 布局位移，页面全程静止在原位槽。
                // 布局位移方向随引擎镜像：LTR 横向 = -off·w（旧页左移），RTL
                // reverseLayout 水平轴镜像 = +off·w（旧页右移），TTB 纵向 = -off·h；
                // 抵消平移必须逐引擎取反向号（统一 +off·w 在 RTL 下会加倍漂移——
                // 第三轮逐帧复审实测旧页 +2/+14/+60px 递增漂移即此因）。
                // alpha 走真交叉淡化：离场页（off≥0）保持不透明作底，进场页
                // （off<0）alpha=1+off 自 0 淡入；层序上进场页叠于其上，合成
                // 恒为 (1-p)·旧页 + p·新页，中点不下陷发黑（旧版两页同衰减到
                // 0.08，逐帧实测拖拽中段暗像素 8k→140k 全黑即是此病）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = spacingHalf)
                        .graphicsLayer {
                            val pageOffset = (pagerState.currentPage + pagerState.currentPageOffsetFraction - idx)
                                .coerceIn(-1.5f, 1.5f)
                            val (tx, ty) = fadeCancelOffset(pageOffset, rtl, ttb, size.width, size.height)
                            translationX = tx
                            translationY = ty
                            alpha = fadeCrossAlpha(pageOffset)
                        }
                ) {
                    ComicSpreadCell(
                        spread = spread, config = config, loader = loader, bookState = bookState,
                        gestureCallbacks = gestureCallbacks, onBitmapShown = onBitmapShown,
                        onZoomChanged = { if (pagerState.currentPage == idx) childZoomed.value = it },
                        isCurrentPage = pagerState.currentPage == idx,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(horizontal = spacingHalf)) {
                    ComicSpreadCell(
                        spread = spread, config = config, loader = loader, bookState = bookState,
                        gestureCallbacks = gestureCallbacks, onBitmapShown = onBitmapShown,
                        onZoomChanged = { if (pagerState.currentPage == idx) childZoomed.value = it },
                        isCurrentPage = pagerState.currentPage == idx,
                    )
                }
            }
        }
    }

    val chapterEdgeModifier = Modifier.comicChapterEdgeSwipe(
        enabled = config.gestureSwipe,
        direction = config.direction,
        atStart = { pagerState.settledPage == 0 && !pagerState.isScrollInProgress },
        atEnd = { pagerState.settledPage == layout.spreadCount - 1 && !pagerState.isScrollInProgress },
        zoomed = { childZoomed.value },
        bounce = isSlide,
        onPrevious = goPrev,
        onNext = goNext,
    )

    if (ttb) {
        VerticalPager(
            state = pagerState,
            userScrollEnabled = config.gestureSwipe,
            modifier = Modifier.fillMaxSize().then(chapterEdgeModifier),
        ) { idx -> cell(idx) }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            userScrollEnabled = config.gestureSwipe,
            modifier = Modifier.fillMaxSize().then(chapterEdgeModifier),
            beyondViewportPageCount = 1,
        ) { idx -> cell(idx) }
    }
}

/* ══════════════ 磁吸模式 ══════════════ */

/**
 * 边缘橡胶带阻尼（iOS UIScrollView c=0.55 公式）：首/末页越界拖动渐近衰减。
 * drag 语义与翻页一致：负值 = 阅读方向推进，正值 = 回退。
 */
fun dampEdgeDrag(raw: Float, page: Int, count: Int, span: Float, c: Float = 0.55f): Float {
    if (span <= 0f) return 0f
    val maxY = span / c
    if (page <= 0 && raw > 0f) {
        return (1f - 1f / (raw * c / span + 1f)) * maxY
    }
    if (page >= count - 1 && raw < 0f) {
        return -((1f - 1f / (-raw * c / span + 1f)) * maxY)
    }
    return raw
}

/**
 * 松手目标页判定（对齐 Compose Pager 官方参数）：
 * 速度 ≥ vTh（400dp/s）按速度方向翻页；否则按位置（过半即翻）。
 */
fun magneticTargetShift(
    dragValue: Float,
    velocityPxPerSec: Float,
    span: Float,
    dirSign: Float,
    vTh: Float,
): Int {
    val shiftFloat = -dragValue / span * dirSign
    if (abs(velocityPxPerSec) >= vTh) {
        val vShift = -velocityPxPerSec / span * dirSign
        return if (vShift > 0f) 1 else -1
    }
    return when {
        shiftFloat > 0.5f -> 1
        shiftFloat < -0.5f -> -1
        else -> 0
    }
}

@Composable
private fun ComicMagneticPager(
    layout: ComicLayout,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    currentSpread: Int,
    onSpreadChanged: (Int) -> Unit,
    gestureCallbacks: ComicGestureCallbacks,
    containerW: Float,
    containerH: Float,
    onBitmapShown: (Bitmap) -> Unit,
    autoRead: Boolean,
    autoIntervalSec: Float,
    goNext: () -> Unit,
    goPrev: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rtl = config.direction == ComicDirection.RTL
    val ttb = config.direction == ComicDirection.TTB
    val dirSign = if (rtl) -1f else 1f
    val drag = remember { Animatable(0f) }
    val childZoomed = remember { mutableStateOf(false) }
    // 第 6 节：磁吸吸附成功的轻触觉
    val hapticView = androidx.compose.ui.platform.LocalView.current
    // 磁吸拖拽进行中（auto-read 避让 + spread 变化不复位，与卷页 dragActive 同语义）
    val magDragActive = remember { mutableStateOf(false) }
    // 手势协程内始终读取最新页码/配置
    val latestCurrentSpread by rememberUpdatedState(currentSpread)
    val latestConfig by rememberUpdatedState(config)

    // 翻页提交后重置（拖拽进行中不重置——防外部页码变化打断跟手）
    LaunchedEffect(currentSpread) {
        if (abs(drag.value) > 1f && !magDragActive.value) drag.snapTo(0f)
    }

    if (autoRead) {
        LaunchedEffect(autoRead, autoIntervalSec, currentSpread) {
            delay((autoIntervalSec * 1000).toLong())
            // 用户拖拽中不抢页（与卷页引擎同款避让）。等待而非直接放弃：
            // 直接 return 时若拖拽最终未改页（回弹/双指中止），三个 key 均不变、
            // 本 effect 不会重启，自动阅读将静默冻结
            if (magDragActive.value) {
                snapshotFlow { magDragActive.value }.first { !it }
            }
            if (currentSpread < layout.spreadCount - 1) onSpreadChanged(currentSpread + 1) else goNext()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .comicChapterEdgeSwipe(
                enabled = config.gestureSwipe,
                direction = config.direction,
                atStart = { latestCurrentSpread == 0 && !magDragActive.value },
                atEnd = { latestCurrentSpread == layout.spreadCount - 1 && !magDragActive.value },
                zoomed = { childZoomed.value },
                bounce = true,
                onPrevious = goPrev,
                onNext = goNext,
            )
            .pointerInput(layout.spreadCount, rtl, ttb) {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val span = if (ttb) h else w
                val slop = viewConfiguration.touchSlop
                val vTh = 400.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var total = 0f
                    var dragging = false
                    // 回弹动画中途被抓住时的页面既有位移——续拖基准，
                    // 否则 snapTo(≈slop) 会把页面从回弹中途瞬跳回起点
                    var resumeBase = 0f
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = if (ttb) change.positionChange().y else change.positionChange().x
                        total += delta
                        if (!dragging && abs(total) > slop && latestConfig.gestureSwipe &&
                            !childZoomed.value && !event.changes.any { it.isConsumed }
                        ) {
                            dragging = true
                            magDragActive.value = true
                            resumeBase = drag.value
                            scope.launch { drag.stop() }
                        }
                        if (dragging) {
                            // 拖拽期间复查子层状态：双指捏合/长按放大接管（或其事件被消费）
                            // 时中止磁吸并回弹——对照卷页引擎的每事件复查
                            if (childZoomed.value || event.changes.any { it.isConsumed && it.id != down.id }) {
                                scope.launch {
                                    drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                                }
                                magDragActive.value = false
                                return@awaitEachGesture
                            }
                            val damped = resumeBase + dampEdgeDrag(total, latestCurrentSpread, layout.spreadCount, span)
                            scope.launch { drag.snapTo(damped) }
                            change.consume()

                        }
                    }
                    if (dragging) {
                        magDragActive.value = false
                        val v = runCatching { tracker.calculateVelocity() }.getOrNull()
                        val vAxis = if (ttb) (v?.y ?: 0f) else (v?.x ?: 0f)
                        val targetShift = magneticTargetShift(drag.value, vAxis, span, dirSign, vTh)
                            // 边界页快速回甩：目标夹到合法页范围（越界 → 回弹到当前页）
                            .coerceIn(-latestCurrentSpread, layout.spreadCount - 1 - latestCurrentSpread)
                        val targetPage = latestCurrentSpread + targetShift
                        val targetOffset = -targetShift * span * dirSign
                        // 欠阻尼弹簧直接吃未钳制的甩动速度会对目标过冲再回摆
                        // （±1 页窗口会露出页间底色）——初速度钳到 ±2·span/s
                        val vClamped = vAxis.coerceIn(-2f * span, 2f * span)
                        scope.launch {
                            drag.animateTo(
                                targetOffset,
                                spring(dampingRatio = 0.85f, stiffness = 380f),
                                initialVelocity = vClamped,
                            )
                            // 提交前校验动画未被中断（被新手势 stop 的 animateTo 会"正常返回"）
                            if (abs(drag.value - targetOffset) < span * 0.02f) {
                                drag.snapTo(0f)
                                onSpreadChanged(targetPage)
                                // 第 6 节：磁吸吸附成功轻触觉
                                if (com.example.ui.feedback.HapticsGate.enabled) {
                                    hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                            }
                        }
                    } else if (magDragActive.value) {
                        // 兜底复位：本手势从未进入拖拽但 magDragActive 为 true，是前一个被
                        // 打断手势的残留（异常事件流无 UP 时复位随旧 lambda 一起被取消）。
                        // 残留会让 auto-read 避让的 snapshotFlow 永久等待（自动阅读静默冻结）
                        // 与 spread 变化复位被跳过，这里拉平并复位
                        scope.launch {
                            drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
                        }
                        magDragActive.value = false
                    }
                }
            }
    ) {
        // 新反馈条目3b（磁吸翻页后白屏一闪）：三窗口按位置组合，落定瞬间窗口 0
        // 绑定的 spread 变更 → remember(slot.ref.id) 键控重置 → Loading 占位
        // （无底色，白背景透出）闪现 2-4 帧。改用 key(idx)：落定时内容块随页
        // "移动"到新窗口位（remember 状态随行），offset 数学上同帧无缝，零闪帧。
        for (window in intArrayOf(-1, 0, 1)) {
            val idx = currentSpread + window
            val spread = layout.spreads.getOrNull(idx) ?: continue
            val base = window * (if (ttb) containerH else containerW) * dirSign
            val x = if (ttb) 0f else base + drag.value
            val y = if (ttb) base + drag.value else 0f
            androidx.compose.runtime.key(idx) {
                Box(Modifier.fillMaxSize().offset { IntOffset(x.roundToInt(), y.roundToInt()) }) {
                    ComicSpreadCell(
                        spread = spread, config = config, loader = loader, bookState = bookState,
                        gestureCallbacks = gestureCallbacks, onBitmapShown = onBitmapShown,
                        onZoomChanged = { childZoomed.value = it },
                        isCurrentPage = window == 0,
                    )
                }
            }
        }
    }
}

/* ══════════════ 垂直列表（条漫 / 无缝滚动） ══════════════ */

@OptIn(net.engawapg.lib.zoomable.ExperimentalZoomableApi::class)
@Composable
private fun ComicVerticalList(
    layout: ComicLayout,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    currentSpread: Int,
    onSpreadChanged: (Int) -> Unit,
    tapZoneAction: (Offset, Size) -> Unit,
    tapAreaSize: () -> Size,
    onAreaSizeChanged: (Size) -> Unit,
    onBitmapShown: (Bitmap) -> Unit,
    autoScrollActive: Boolean,
    autoScrollSpeedDp: Float,
    onReachEnd: () -> Unit,
    onScrollFraction: ((Float) -> Unit)? = null,
    translationModelState: com.example.mangatranslate.TranslateModelManager.DownloadState =
        com.example.mangatranslate.TranslateModelManager.DownloadState.NotDownloaded,
    onScheduleTranslation: (List<ComicPageLoader.WindowEntry>, ComicReaderConfig) -> Unit = { _, _ -> },
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentSpread)
    val density = LocalDensity.current
    // 条漫 vs 无缝滚动独立策略（第 6 条）：间距/磁吸/预取窗口/进度语义
    val strategy = remember(config.mode, config.pageSpacingDp, config.webtoonSnap) {
        ComicScrollStrategy.forConfig(config)
    }

    // 当前列表项 = 屏幕上可见面积最大的项（非首可见项）——
    // 垂直模式进度/旋转/临时合页的目标页跟随用户正在看的页，
    // 而不是视口顶部恰好裁到的上一页尾（第 2 条"旋转了别的页"垂直场景根因）
    LaunchedEffect(listState, layout) {
        snapshotFlow {
            val info = listState.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty()) listState.firstVisibleItemIndex
            else vis.maxByOrNull { item ->
                val top = item.offset.coerceAtLeast(0)
                val bottom = (item.offset + item.size).coerceAtMost(info.viewportEndOffset)
                (bottom - top).coerceAtLeast(0)
            }?.index ?: listState.firstVisibleItemIndex
        }
            .distinctUntilChanged()
            .collect { idx -> onSpreadChanged(idx.coerceIn(0, (layout.spreadCount - 1).coerceAtLeast(0))) }
    }
    LaunchedEffect(currentSpread) {
        // 外部跳转同步：仅当目标项完全不在视口内才滚动定位。
        // （进度上报是"最可见项"，与 firstVisibleItemIndex 不同步——若用旧守卫
        //   firstVisible != current 就 scrollToItem，会与上报互相驱动成自动滚屏死循环）
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentSpread }
        if (!visible) {
            listState.scrollToItem(currentSpread.coerceIn(0, (layout.spreadCount - 1).coerceAtLeast(0)))
        }
    }

    // 前瞻预载差异化（第 6 条 prefetchWindow 真正接线）：无缝滚动（窗口 4）比条漫
    // （窗口 2）更远地提前解码+处理，滚动进入前纹理已在缓存——"完全连续无停顿"
    // 的预加载策略腿；LazyColumn 自身组合窗口只覆盖可见±少量，两者叠加生效
    LaunchedEffect(currentSpread, strategy, layout, config, translationModelState) {
        // 5.5 修复：整窗一次提交（旧逐槽调用会互相取消预载任务，见水平路径注释）。
        // verticalPreloadIndices 已返回驻留优先级序（当前 → 前瞻近→远 → 上一页）。
        // config（=防抖 displayConfig）入 key：指纹落定自动重预载并重建驻留。
        val entries = ArrayList<ComicPageLoader.WindowEntry>(8)
        verticalPreloadIndices(currentSpread, strategy.prefetchWindow, layout.spreadCount).forEach { si ->
            layout.spreads[si].slots.forEach { slot ->
                val rot = ((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360
                entries.add(
                    ComicPageLoader.WindowEntry(
                        ref = slot.ref,
                        visible = si == currentSpread,
                        cacheKey = "${slot.ref.id}|${slot.half}|${config.imagePipelineFingerprint()}|r$rot",
                        geo = ComicImagePipeline.Geometry(
                            half = slot.half,
                            splitPosition = effectiveSplitPosition(config, loader.sizes.value[slot.ref.id]?.gutterPos),
                            rotationDeg = rot, cropMode = config.cropMode, manualCrop = config.manualCrop,
                        ),
                    ),
                )
            }
        }
        loader.preloadWindow(entries, toneOf(config))
        onScheduleTranslation(entries, config)
    }

    // 连续自动滚动：逐帧 scrollBy（真实连续滚动）；到底后进入下一章/停止
    if (autoScrollActive) {
        LaunchedEffect(autoScrollActive, autoScrollSpeedDp) {
            val pxPerSec = with(density) { autoScrollSpeedDp.dp.toPx() }
            var last = 0L
            var stallFrames = 0
            var lastReachEndAt = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    val scrolled = listState.scrollBy(pxPerSec * dt)
                    if (scrolled < 0.1f && listState.firstVisibleItemIndex >= layout.spreadCount - 1) {
                        stallFrames++
                        // 换章节流：3 秒内不重复触发，避免换章失败时反复跳章
                        if (stallFrames > 30 && System.currentTimeMillis() - lastReachEndAt > 3000) {
                            onReachEnd()
                            lastReachEndAt = System.currentTimeMillis()
                            stallFrames = 0
                        }
                    } else stallFrames = 0
                }
                last = now
            }
        }
    }

    val zoomState = rememberZoomState()
    val onTap: (Offset) -> Unit = remember(tapZoneAction) {
        { pos -> tapZoneAction(pos, tapAreaSize()) }
    }

    // 无缝滚动像素级进度（第 6 条：按累计像素高度而非页数）：
    // fraction = (首可见项索引 + 项内像素偏移比例) / (总项数-1)，
    // 项内偏移在相邻项之间连续插值，滚动全程单调无跳变
    if (onScrollFraction != null && strategy.pixelProgress) {
        LaunchedEffect(listState, layout) {
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()
                if (first == null || info.totalItemsCount <= 1) 0f
                else {
                    val within = (-first.offset).toFloat() / first.size.coerceAtLeast(1)
                    ((first.index + within.coerceIn(0f, 1f)) / (info.totalItemsCount - 1)).coerceIn(0f, 1f)
                }
            }.collect { onScrollFraction(it) }
        }
    }

    // 条漫磁吸（第 6/7 条）：SnapFlingBehavior 官方 item-snap——松手连续衰减滚动
    // 至最近页边界对齐（速度自然衰减，无"到阈值硬切"）；无缝滚动保持自由 fling
    val snapFling = if (strategy.snapToPage) {
        androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(
            lazyListState = listState,
        )
    } else androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()

    val chapterEdge = Modifier.fillMaxSize().comicChapterEdgeSwipe(
        enabled = config.gestureSwipe,
        direction = ComicDirection.TTB,
        atStart = { !listState.canScrollBackward && !listState.isScrollInProgress },
        atEnd = { !listState.canScrollForward && !listState.isScrollInProgress },
        zoomed = { zoomState.scale > 1.01f },
        onPrevious = onPreviousChapter,
        onNext = onNextChapter,
    )

    Box(modifier = chapterEdge) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { onAreaSizeChanged(Size(it.width.toFloat(), it.height.toFloat())) }
                .zoomableWithScroll(zoomState = zoomState, onTap = onTap),
            verticalArrangement = Arrangement.spacedBy(strategy.spacingDp.dp),
            flingBehavior = snapFling,
        ) {
            itemsIndexed(layout.spreads, key = { _, s -> "spread_${s.index}_${s.firstRawIndex}" }) { _, spread ->
                ComicVerticalItem(
                    spread = spread, config = config, loader = loader, bookState = bookState,
                    // 主色喂入门控：仅"最可见项"（currentSpread 上报源）可喂，多可见项
                    // 并存时不允许后组合项覆盖当前页色调（第 25 条）
                    onBitmapShown = onBitmapShown,
                    isCurrentPage = spread.index == currentSpread,
                )
            }
        }
    }
}

/* ══════════════ 单项渲染 ══════════════ */

/** 垂直模式单项：占满宽度，图片自然高度；失败可重试；整列表缩放由 zoomableWithScroll 提供 */
@Composable
private fun ComicVerticalItem(
    spread: ComicSpread,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    onBitmapShown: (Bitmap) -> Unit,
    isCurrentPage: Boolean,
) {
    val slot = spread.slots.firstOrNull() ?: return
    var retry by remember(slot.ref.id) { mutableIntStateOf(0) }
    val state by rememberPageBitmap(loader, slot, config, bookState, retry, visible = isCurrentPage)
    // 位图状态按 cacheKey 键控：换页/换管线指纹时旧页位图立即失效——
    // 无 key 的 remember 会在 Loading 期间残留上一页画面（快速滚动闪错帧的根因）。
    // 第六轮族 A：初值同步取播种后的 state（缓存命中 ⇒ 第一帧即内容）。
    val imageBitmap = (state as? PageBitmapState.Ready)?.bitmap?.asImageBitmap()

    // effect 键含 isCurrentPage：item 晋升为最可见项时位图早已 Ready 也会补喂主色
    LaunchedEffect(state, isCurrentPage) {
        if (state is PageBitmapState.Ready) {
            val bmp = (state as PageBitmapState.Ready).bitmap
            if (isCurrentPage) onBitmapShown(bmp)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (config.mode == ComicMode.WEBTOON) 0.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            is PageBitmapState.Loading -> Box(
                Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ComicLoadingFeedback(slot.ref) { retry++ }
                    enhanceHintFor(
                        config,
                        loader.sizes.value[slot.ref.id]?.let { maxOf(it.width, it.height) } ?: 2400,
                    )?.let { Text(it, color = Color(0xAAFFFFFF), fontSize = 12.sp) }
                }
            }
            is PageBitmapState.Failed -> Column(
                Modifier.fillMaxWidth().height(240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("图片加载失败", color = Color(0xFFFF9A9A))
                TextButton(onClick = { retry++ }) { Text("点击重试", color = Color.White.copy(alpha = 0.8f)) }
            }
            is PageBitmapState.Ready -> {
                imageBitmap?.let {
                    Box(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.Image(
                        bitmap = it,
                        contentDescription = "第 ${spread.firstRawIndex + 1} 页",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                    ComicRefinementFeedback(s.refining, s.error, Modifier.align(Alignment.BottomCenter)) { retry++ }
                    }
                }
            }
        }
    }
}

/**
 * 分页模式跨页单元：单页或双页（阅读顺序排列、间距/对齐/位置修正），
 * 整跨页统一缩放平移。mirrorX 用于 RTL 仿真翻页容器内坐标还原。
 */
@Composable
fun ComicSpreadCell(
    spread: ComicSpread,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    gestureCallbacks: ComicGestureCallbacks,
    onBitmapShown: (Bitmap) -> Unit,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    mirrorX: Boolean = false,
    isCurrentPage: Boolean = true,
) {
    val zoomState = remember(spread.index, spread.slots.joinToString { it.ref.id }) { ComicZoomState() }
    val rtl = config.direction == ComicDirection.RTL

    // 渐变翻页动画：磁吸模式到达页内容从 0.45 淡入到 1（真实作用于画面）。
    // 分页模式的 FADE 由 Pager 层偏移驱动交叉淡化（见 ComicPagedReader），此处不叠加。
    // 第七轮第 2 条：初始值随"是否将播放淡入"在组合期确定——磁吸提交是整树重建，
    // 新单元格首帧就是当前页，remember{Animatable(1f)}+下一拍 snapTo(0.45) 会先满亮
    // 一帧再压暗（亮度闪帧，与缩放闪帧同族的"先错后纠"中间态）
    val playMagneticFade = isCurrentPage && config.pageAnim == ComicPageAnim.FADE &&
        config.mode == ComicMode.MAGNETIC
    val fadeAlpha = remember(playMagneticFade) { Animatable(if (playMagneticFade) 0.45f else 1f) }
    LaunchedEffect(playMagneticFade) {
        if (playMagneticFade) {
            fadeAlpha.animateTo(1f, tween(260))
        } else {
            fadeAlpha.snapTo(1f)
        }
    }

    LaunchedEffect(zoomState.isZoomed) { onZoomChanged?.invoke(zoomState.isZoomed) }

    // 镜像容器内的局部坐标还原为物理坐标（点按区方向语义不被镜像破坏）
    val physicalCallbacks = if (mirrorX) {
        val origin = gestureCallbacks
        ComicGestureCallbacks(
            onTapZone = { pos, size -> origin.onTapZone(Offset(size.width - pos.x, pos.y), size) },
            onLongPress = origin.onLongPress,
            onPinchClose = origin.onPinchClose,
            onEdgeBack = origin.onEdgeBack,
            onZoomedEdgeSwipe = { dir -> origin.onZoomedEdgeSwipe?.invoke(-dir) },
        )
    } else gestureCallbacks

    // mirrorX（RTL 仿真翻页外层镜像容器）：内容再镜像一次抵消，净效果 = 画面正向 + 卷曲方向随书本
    val contentModifier = if (mirrorX) {
        Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }
    } else {
        Modifier.fillMaxSize()
    }

    // 主色喂入门控（第 25 条）：仅当前页的位图可喂沉浸式主色——pager 会组合
    // 相邻预取页，其 onBitmapShown 若不门控会覆盖当前页色调（静止在第 1 页时
    // 背景显示第 2 页颜色，第三轮 ComicDynBg 日志实测 20ms 内被覆盖）。
    // 门控下沉到内容组件内部（effect 键含 isCurrentPage）：页晋升为当前时
    // 即便位图早已 Ready 也会补喂一次。
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeAlpha.value }
            .comicZoomable(state = zoomState, config = config, callbacks = physicalCallbacks)
            .comicEdgeSwipe(
                enabled = config.gestureEdgeSwipe,
                zoomed = { zoomState.isZoomed },
                onTrigger = gestureCallbacks.onEdgeBack,
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(contentModifier) {
            if (spread.isDouble) {
                DoubleSpreadContent(
                    spread = spread, config = config, loader = loader, bookState = bookState,
                    zoomState = zoomState, onBitmapShown = onBitmapShown, rtl = rtl,
                    isCurrentPage = isCurrentPage,
                )
            } else {
                spread.slots.firstOrNull()?.let { slot ->
                    SinglePageContent(
                        slot = slot, config = config, loader = loader, bookState = bookState,
                        zoomState = zoomState, onBitmapShown = onBitmapShown,
                        isCurrentPage = isCurrentPage,
                    )
                }
            }
        }
    }
}

/** 计算适配后的显示尺寸（fit 决定基准缩放） */
fun fittedSize(intrinsic: Size, container: Size, fit: ComicFit): Size =
    fittedSize(intrinsic, container, fit, 1f, ComicFit.FIT_PAGE)

/**
 * 适配计算（第 10 条五种数学定义 + 第 26 条自定义档）。
 * customScale/customBase 仅对 CUSTOM 档生效（基础档缩放 × 系数）。
 */
fun fittedSize(
    intrinsic: Size,
    container: Size,
    fit: ComicFit,
    customScale: Float,
    customBase: ComicFit,
): Size {
    if (intrinsic.width <= 0f || intrinsic.height <= 0f) return Size.Zero
    return when (fit) {
        ComicFit.FIT_PAGE ->
            // 整页：fit-inside（完整可见，比例保持，短边贴合留白在长边）
            Size(
                intrinsic.width * min(container.width / intrinsic.width, container.height / intrinsic.height),
                intrinsic.height * min(container.width / intrinsic.width, container.height / intrinsic.height),
            )
        ComicFit.FIT_WIDTH -> {
            // 宽度贴合容器 + 竖向自然溢出（可平移/滚动）——与 FIT_HEIGHT 语义对称；
            // 旧实现 min(...) 是 fit-center（两侧留黑边），名为"适应宽度"却永不贴合
            val s = container.width / intrinsic.width
            Size(intrinsic.width * s, intrinsic.height * s)
        }
        ComicFit.FIT_HEIGHT -> {
            val s = container.height / intrinsic.height
            Size(intrinsic.width * s, intrinsic.height * s)
        }
        ComicFit.ORIGINAL -> intrinsic
        ComicFit.FILL -> {
            val s = max(container.width / intrinsic.width, container.height / intrinsic.height)
            Size(intrinsic.width * s, intrinsic.height * s)
        }
        ComicFit.STRETCH -> container
        ComicFit.CUSTOM -> {
            // 自定义：用户选定的基础档 × 缩放系数（0.5..2.5）
            val base = fittedSize(
                intrinsic, container,
                if (customBase == ComicFit.CUSTOM) ComicFit.FIT_PAGE else customBase,
            )
            Size(base.width * customScale, base.height * customScale)
        }
    }
}

/**
 * 可视内容区域（原始图像素坐标）。渲染模型反解：
 * screen = containerCenter + (content − contentCenter) × scale + offset。
 * 返回 null 表示状态未就绪。纯函数，可单测。
 */
fun visibleIntrinsicRect(z: ComicZoomState): RectF? {
    if (z.contentSize.width <= 0f || z.contentSize.height <= 0f ||
        z.containerSize.width <= 0f || z.containerSize.height <= 0f || z.scale <= 0f
    ) return null
    if (z.intrinsicSize.width <= 0f || z.intrinsicSize.height <= 0f) return null
    val cx = z.containerSize.width / 2f
    val cy = z.containerSize.height / 2f
    val ccx = z.contentSize.width / 2f
    val ccy = z.contentSize.height / 2f
    fun toContentX(px: Float) = (px - cx - z.offsetX) / z.scale + ccx
    fun toContentY(py: Float) = (py - cy - z.offsetY) / z.scale + ccy
    val l = toContentX(0f).coerceIn(0f, z.contentSize.width)
    val r = toContentX(z.containerSize.width).coerceIn(0f, z.contentSize.width)
    val t = toContentY(0f).coerceIn(0f, z.contentSize.height)
    val b = toContentY(z.containerSize.height).coerceIn(0f, z.contentSize.height)
    if (r - l < 1f || b - t < 1f) return null
    val sx = z.intrinsicSize.width / z.contentSize.width
    val sy = z.intrinsicSize.height / z.contentSize.height
    return RectF(l * sx, t * sy, r * sx, b * sy)
}

@Composable
private fun SinglePageContent(
    slot: ComicSlot,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    zoomState: ComicZoomState,
    onBitmapShown: (Bitmap) -> Unit,
    isCurrentPage: Boolean = true,
) {
    var retry by remember(slot.ref.id) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = Size(maxWidth.value * density.density, maxHeight.value * density.density)
        val state by rememberPageBitmap(loader, slot, config, bookState, retry, visible = isCurrentPage)
        // 位图/内禀尺寸按槽位身份键控：换页瞬间旧内容立即清空（Loading 占位），
        // 不允许旧页位图在新页解码期间残留覆盖——快速翻页闪错帧的根因。
        // 第六轮族 A：初值同步取自播种后的 state（缓存命中 ⇒ 第一帧即内容），
        // 不再等 LaunchedEffect 下一拍赋值（那一拍 = 闪烁帧）。
        val ready = state as? PageBitmapState.Ready
        val imageBitmap = ready?.bitmap?.asImageBitmap()
        val intrinsic = ready?.let { Size(it.bitmap.width.toFloat(), it.bitmap.height.toFloat()) } ?: Size.Zero
        val cacheKey = "${slot.ref.id}|${slot.half}|${config.imagePipelineFingerprint()}|" +
            "r${((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360}"

        // effect 键含 isCurrentPage：页晋升为当前时位图早已 Ready 也会补喂主色；
        // 非当前页（相邻预取组合）不喂——防止覆盖当前页色调（第 25 条）
        LaunchedEffect(state, isCurrentPage) {
            if (state is PageBitmapState.Ready) {
                val bmp = (state as PageBitmapState.Ready).bitmap
                // 1:1 档基准用「原始文件像素」（loader.sizes 记录、未 capEdge）——
                // cap 到 2800 的位图会让大扫描图的 1:1 档算出偏低值被丢弃，双击循环缺档
                val rawSize = loader.sizes.value[slot.ref.id]
                zoomState.intrinsicSize = if (rawSize != null && rawSize.width > 0 && rawSize.height > 0) {
                    Size(rawSize.width.toFloat(), rawSize.height.toFloat())
                } else intrinsic
                if (isCurrentPage) onBitmapShown(bmp)
            }
        }
        // 适配尺寸在组合期同步求值（SideEffect 帧前落值）：容器尺寸变化（旋转屏幕/
        // 分栏）后首帧即用新值——旧 LaunchedEffect 写法晚一帧，旋转瞬间显示残影错位
        val fittedNow = remember(container, config.fit, config.customFitScale, config.customFitBase, intrinsic) {
            fittedSize(intrinsic, container, config.fit, config.customFitScale, config.customFitBase)
        }
        androidx.compose.runtime.SideEffect {
            if (intrinsic != Size.Zero) zoomState.contentSize = fittedNow
        }

        // 高倍缩放区域重解码：显示比例超出已解码分辨率时，对可视区按原始像素重解（仅本地文件）。
        // 触发 = 手势结束（gestureEpoch）+ 缩放/偏移分桶变化（避免松手动画期间每帧重启）。
        // 坐标空间：intrinsic 是处理后位图（capEdge 2800），decodeRegion 是原始文件像素——
        // 用 loader.sizes 的原始尺寸换算；且仅在管线无处理（无裁边/拆片/旋转/调色）时启用，
        // 保证 hiRes 内容与底层视觉一致。
        var hiRes by remember(slot.ref.id) { mutableStateOf<ImageBitmap?>(null) }
        var hiResRect by remember(slot.ref.id) { mutableStateOf(RectF()) }
        val geoForRegion = ComicImagePipeline.Geometry(
            half = slot.half,
            splitPosition = effectiveSplitPosition(config, loader.sizes.value[slot.ref.id]?.gutterPos),
            rotationDeg = ((config.bookRotation + (bookState.pageRotations[slot.ref.id] ?: 0)) % 360 + 360) % 360,
            cropMode = config.cropMode,
            manualCrop = config.manualCrop,
        )
        val pipelineIdle = !ComicImagePipeline.hasWork(geoForRegion, toneOf(config))
        // 触发改用 snapshotFlow + 空闲等待：offset bucket 只在「手势与松手动画全部结束后」
        // 才真正发起解码——修复旧版 LaunchedEffect 键含 offsetBucket 时，fling 每移动
        // 48px 就重启 effect 导致的连环重解码风暴（decodeRegion 不可取消，纯烧 CPU）
        LaunchedEffect(cacheKey, intrinsic) {
            snapshotFlow {
                Triple(
                    zoomState.gestureEpoch,
                    (zoomState.scale * 50f).toInt(),
                    (zoomState.offsetX / 96f).toInt() to (zoomState.offsetY / 96f).toInt(),
                )
            }.distinctUntilChanged().collectLatest {
                val local = slot.ref as? ComicPageRef.Local
                if (local == null || !pipelineIdle || intrinsic == Size.Zero) return@collectLatest
                // 等待手势与松手动画（fling/回弹）全部结束：
                // releaseAnimating 是 snapshot 可观察的——fling/settle 结束时它翻转为
                // false 会唤醒这里（releaseJob 的 Job 引用本身不可观察）
                snapshotFlow { zoomState.gestureActive || zoomState.releaseAnimating }
                    .first { !it }
                // 显示比例相对「原始文件像素」：fitScale×scale 是相对处理后位图（capEdge 2800），
                // 再乘 downsample（处理后/原始）——否则 4000px 原图在 0.8x 显示就误触发重解码
                val rawSize = loader.sizes.value[local.id] ?: return@collectLatest
                val fitScale = if (intrinsic.width > 0f) zoomState.contentSize.width / intrinsic.width else 1f
                val downsample = if (rawSize.width > 0) intrinsic.width / rawSize.width.toFloat() else 1f
                val dispPerOrig = fitScale * zoomState.scale * downsample
                if (dispPerOrig < 1.15f) return@collectLatest
                if (rawSize.width <= intrinsic.width && rawSize.height <= intrinsic.height) {
                    hiRes = null // 原图未降采样，重解码无收益
                    return@collectLatest
                }
                val rect = visibleIntrinsicRect(zoomState) ?: return@collectLatest
                // zoomState.intrinsicSize 已是「原始文件像素」基准（1:1 档修复）——
                // visibleIntrinsicRect 返回的 rect 直接就是原始坐标，不再乘换算比；
                // 处理后位图（overlay 定位基准）的换算只发生在 hiResRect 记录处
                val sx = rawSize.width / intrinsic.width
                val sy = rawSize.height / intrinsic.height
                // 外扩 12% 缓冲，平移小幅抖动不重复解码
                val padX = rect.width() * 0.12f
                val padY = rect.height() * 0.12f
                val expanded = RectF(
                    (rect.left - padX).coerceAtLeast(0f),
                    (rect.top - padY).coerceAtLeast(0f),
                    (rect.right + padX).coerceAtMost(rawSize.width.toFloat()),
                    (rect.bottom + padY).coerceAtMost(rawSize.height.toFloat()),
                )
                val bmp = loader.decodeRegion(local, expanded, cacheTag = cacheKey)
                if (bmp != null) {
                    hiRes = bmp.asImageBitmap()
                    // 记录处理后台坐标（overlay 定位用），内容严格对应 expanded 区域
                    hiResRect = RectF(
                        expanded.left / sx, expanded.top / sy,
                        expanded.right / sx, expanded.bottom / sy,
                    )
                }
            }
        }

        ZoomableImageLayer(zoomState, config.fit, fallbackSize = fittedNow) {
            Box(Modifier.fillMaxSize()) {
                imageBitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it,
                        contentDescription = "第 ${slot.rawIndex + 1} 页",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                } ?: PagePlaceholder(
                    state,
                    { retry++ },
                    enhanceHintFor(config, loader.sizes.value[slot.ref.id]?.let { maxOf(it.width, it.height) } ?: 2400),
                    slot.ref,
                )
                ComicRefinementFeedback(ready?.refining == true, ready?.error, Modifier.align(Alignment.BottomCenter)) { retry++ }
                // 区域高清层：严格覆盖 hiResRect 对应的内容坐标区域（与底层同变换，天然对齐）
                hiRes?.let { bmp ->
                    val sx = if (intrinsic.width > 0f) zoomState.contentSize.width / intrinsic.width else 1f
                    val sy = if (intrinsic.height > 0f) zoomState.contentSize.height / intrinsic.height else 1f
                    val l = hiResRect.left * sx
                    val t = hiResRect.top * sy
                    val w = hiResRect.width() * sx
                    val h = hiResRect.height() * sy
                    Box(
                        Modifier
                            .offset(with(density) { l.toDp() }, with(density) { t.toDp() })
                            .width(with(density) { w.toDp() })
                            .height(with(density) { h.toDp() })
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }
            }
        }
    }
}

/** 双页内容：两页按可用宽度适配（等高约束），阅读顺序排列 + 间距 + 对齐 + 位置修正 */
@Composable
private fun DoubleSpreadContent(
    spread: ComicSpread,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    zoomState: ComicZoomState,
    onBitmapShown: (Bitmap) -> Unit,
    rtl: Boolean,
    isCurrentPage: Boolean = true,
) {
    var retry by remember(spread.slots.joinToString { it.ref.id }) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = Size(maxWidth.value * density.density, maxHeight.value * density.density)
        val gapPx = with(density) { config.doubleGapDp.dp.toPx() }
        val shiftXPx = with(density) { config.doubleShiftXDp.dp.toPx() }
        val shiftYPx = with(density) { config.doubleShiftYDp.dp.toPx() }

        val state0 by rememberPageBitmap(loader, spread.slots[0], config, bookState, retry, visible = isCurrentPage)
        val state1 by rememberPageBitmap(loader, spread.slots.getOrNull(1) ?: spread.slots[0], config, bookState, retry, visible = isCurrentPage)
        val ready0 = state0 as? PageBitmapState.Ready
        val ready1 = state1 as? PageBitmapState.Ready
        val failed0 = state0 as? PageBitmapState.Failed
        val failed1 = state1 as? PageBitmapState.Failed

        fun fitOne(intrinsic: Size): Size {
            if (intrinsic == Size.Zero) return Size.Zero
            val availW = (container.width - gapPx) / 2f
            val s = min(availW / intrinsic.width, container.height / intrinsic.height)
            return Size(intrinsic.width * s, intrinsic.height * s)
        }

        val size0 = fitOne(ready0?.bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero)
        val size1 = fitOne(ready1?.bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero)

        LaunchedEffect(container, config.doubleGapDp, state0, state1, isCurrentPage) {
            val total = Size(
                size0.width + size1.width + gapPx,
                max(size0.height, size1.height)
            )
            if (total.width > 0f && total.height > 0f) zoomState.contentSize = total
            // 双页拼合的"原始像素"基准：优先用 loader.sizes 的原始文件像素（1:1 档语义
            // 与单页一致），缺失时退回位图像素和
            ready0?.bitmap?.let { b0 ->
                val w1 = ready1?.bitmap?.width?.toFloat() ?: 0f
                val h1 = ready1?.bitmap?.height?.toFloat() ?: 0f
                val r0 = loader.sizes.value[spread.slots[0].ref.id]
                val r1 = spread.slots.getOrNull(1)?.let { loader.sizes.value[it.ref.id] }
                zoomState.intrinsicSize = if (r0 != null && r0.width > 0) {
                    val rw1 = r1?.width?.toFloat() ?: w1
                    val rh1 = r1?.height?.toFloat() ?: h1
                    Size(r0.width + rw1, maxOf(r0.height.toFloat(), rh1))
                } else {
                    Size(b0.width + w1, maxOf(b0.height.toFloat(), h1))
                }
            }
            // 主色喂入门控：仅当前页可喂（第 25 条，同 SinglePageContent）
            if (isCurrentPage) ready0?.bitmap?.let { onBitmapShown(it) }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 与渲染模型一致：offset 不乘 scale；位移修正只做平移
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = zoomState.offsetX + shiftXPx
                    translationY = zoomState.offsetY + shiftYPx
                },
            horizontalArrangement = Arrangement.spacedBy(config.doubleGapDp.dp, Alignment.CenterHorizontally),
            verticalAlignment = when (config.doubleAlign) {
                ComicDoubleAlign.TOP -> Alignment.Top
                ComicDoubleAlign.CENTER -> Alignment.CenterVertically
                ComicDoubleAlign.BOTTOM -> Alignment.Bottom
            },
        ) {
        // 展示顺序：阅读顺序排列，RTL 首读页在右
        val displaySlots = if (rtl) spread.slots.reversed() else spread.slots
        displaySlots.forEachIndexed { _, slot ->
            val slotIdx = spread.slots.indexOf(slot)
            val ready = if (slotIdx == 0) ready0 else ready1
            val failed = if (slotIdx == 0) failed0 else failed1
            val sz = if (slotIdx == 0) size0 else size1
                if (ready != null && sz != Size.Zero) {
                    androidx.compose.foundation.Image(
                        bitmap = ready.bitmap.asImageBitmap(),
                        contentDescription = "第 ${slot.rawIndex + 1} 页",
                        modifier = Modifier
                            .width(with(density) { sz.width.toDp() })
                            .height(with(density) { sz.height.toDp() }),
                        contentScale = ContentScale.FillBounds
                    )
                } else if (failed != null) {
                    Box(
                        Modifier
                            .width(with(density) { ((container.width - gapPx) / 2f).toDp() })
                            .height(320.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(Color(0x14FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { retry++ }) { Text("加载失败·重试", color = Color(0xFFFF9A9A), fontSize = 12.sp) }
                    }
                } else {
                    Box(
                        Modifier
                            .width(with(density) { ((container.width - gapPx) / 2f).toDp() })
                            .height(320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ComicLoadingFeedback(slot.ref) { retry++ }
                            enhanceHintFor(
                                config,
                                loader.sizes.value[slot.ref.id]?.let { maxOf(it.width, it.height) } ?: 2400,
                            )?.let { Text(it, color = Color(0xAAFFFFFF), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        ComicRefinementFeedback(ready0?.refining == true || ready1?.refining == true,
            ready0?.error ?: ready1?.error, Modifier.align(Alignment.BottomCenter)) { retry++ }
    }
}

@Composable
internal fun ComicRefinementFeedback(refining: Boolean, error: Throwable?, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    if (!refining && error == null) return
    Column(modifier.background(Color(0x99000000)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (error == null) Text("高清图加载中…", color = Color.White, fontSize = 12.sp)
        else TextButton(onClick = onRetry) { Text("高清图加载失败 · 重试", color = Color.White) }
    }
}

/** 缩放层：graphicsLayer 应用 scale/offset；内容按 fit 自动定尺寸。
 *  [fallbackSize]：contentSize 尚未落值（SideEffect 晚一拍）时的组合期兜底尺寸——
 *  第七轮第 2 条：磁吸翻页提交 = 三窗口整树重建（key 集平移），新 ComicZoomState
 *  的 contentSize 首帧恒 Zero，旧逻辑回退 fillMaxSize()+FillBounds 会把位图拉满
 *  整屏一帧再被 SideEffect 纠正成 fitted——即"铺满→自适应"的翻页闪烁根因。
 *  兜底值直接用组合期算好的 fittedNow，任何一帧的缩放表现都与当前 fit 设置一致。 */
@Composable
internal fun ZoomableImageLayer(
    zoomState: ComicZoomState,
    fit: ComicFit,
    fallbackSize: Size = Size.Zero,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = zoomState.scale
                scaleY = zoomState.scale
                translationX = zoomState.offsetX
                translationY = zoomState.offsetY
            },
        contentAlignment = Alignment.Center
    ) {
        val w = zoomState.contentSize.width.takeIf { it > 0f } ?: fallbackSize.width
        val h = zoomState.contentSize.height.takeIf { it > 0f } ?: fallbackSize.height
        Box(
            Modifier.then(
                if (fit == ComicFit.STRETCH || w <= 0f || h <= 0f) Modifier.fillMaxSize()
                else Modifier
                    // requiredWidth/Height：FIT_HEIGHT/FILL 档内容宽/高可超容器
                    // （居中裁切语义）。普通 width/height 会被父约束钳回容器宽，
                    // 超尺寸档被压扁成拉伸渲染（第 10 条 bug 根因）
                    .requiredWidth(with(LocalDensity.current) { w.toDp() })
                    .requiredHeight(with(LocalDensity.current) { h.toDp() })
            )
        ) { content() }
    }
}

@Composable
private fun PagePlaceholder(state: PageBitmapState, onRetry: () -> Unit, enhanceHint: String? = null, ref: ComicPageRef? = null) {
    when (state) {
        is PageBitmapState.Loading -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 第七轮第 1 条子问题 C：统一加载指示——原样复用书库搜索的
                // ChasingDots 组件（同一 Composable、同一套动画曲线与配色参数），
                // 保持全 App 加载态视觉语言一致，不使用系统默认转圈圈
                ComicLoadingFeedback(ref, onRetry)
                // 第六轮第 5 条：增强引擎开启时的耗时预期提示——用户可区分
                // "AI 处理中（有明确预期）"与"卡死"
                enhanceHint?.let {
                    Text(it, color = Color(0xAAFFFFFF), fontSize = 12.sp)
                }
            }
        }
        is PageBitmapState.Failed -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("图片加载失败", color = Color(0xFFFF9A9A))
            TextButton(onClick = onRetry) { Text("点击重试", color = Color.White.copy(alpha = 0.8f)) }
        }
        is PageBitmapState.Ready -> Unit
    }
}

/** Real byte progress; unknown response length remains indeterminate. */
@Composable
internal fun ComicLoadingFeedback(ref: ComicPageRef?, onRetry: (() -> Unit)? = null) {
    var elapsed by remember(ref?.id) { mutableIntStateOf(0) }
    LaunchedEffect(ref?.id) { while (true) { delay(1000); elapsed++ } }
    val transfer = if (ref is ComicPageRef.Remote) {
        val flow = remember(ref.url) { ComicTransfers.state(ref.url) }
        val progress by flow.collectAsStateWithLifecycle()
        progress
    } else null
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        com.example.ui.components.ChasingDots(size = 40.dp, color = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
        val fraction = transfer?.fraction
        if (fraction != null && fraction < 1f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(144.dp))
        val label = when {
            fraction == 1f -> "正在展开页面…"
            fraction != null -> "正在加载 · ${(fraction * 100).toInt()}%"
            (transfer?.bytes ?: 0) > 0 -> "已接收 ${transfer!!.bytes / 1024} KB"
            elapsed >= 5 -> "连接较慢，正在加载…"
            else -> "正在加载页面…"
        }
        Text(label, color = Color(0xBBFFFFFF), fontSize = 12.sp)
        if (elapsed >= 8 && onRetry != null) TextButton(onClick = onRetry) {
            Text("重新加载", color = Color.White)
        }
    }
}

/** 增强处理耗时提示文案（null = 未开启增强，不显示） */
internal fun enhanceHintFor(config: ComicReaderConfig, longEdge: Int): String? {
    if (config.enhanceMode == ComicEnhanceMode.OFF) return null
    val sec = ComicImagePipeline.enhanceEstimateSec(config.enhanceMode, config.enhanceStrength, longEdge)
    return "AI 增强处理中 · 约 ${if (sec == sec.toLong().toDouble()) sec.toInt().toString() else sec}s"
}
