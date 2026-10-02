@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    // WindowInsets.imeAnimationTarget 仍标记为实验性 API（用它的目标值替代逐帧 ime 值）
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.example.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
// 2026-09-21：本文件同时存在 LazyColumn 与 LazyVerticalStaggeredGrid，
// 两者各有一个同名 items。先前直接 import 两个同名 items，编译器无法确定
// lambda 的 receiver 是 LazyItemScope 还是 LazyStaggeredGridItemScope，
// 导致 animateItemPlacement() 无法解析（书架页列表重排动画因此一直没做）。
// 用别名区分调用点后 receiver 唯一确定，动画即可启用。
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.testTag
import com.example.ui.components.AppIconButton
import com.example.ui.components.ChasingDots
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import me.trishiraj.shadowglow.consistentShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlin.math.max
import kotlin.math.roundToInt
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import android.webkit.CookieManager
import android.app.Activity
import android.widget.Toast
import com.example.download.DownloadState
import com.example.source.SearchBook
import com.example.source.BookSource
import com.example.source.ComicSource
import com.example.source.LoginCredential
import com.example.source.SourceResult
import com.example.source.isNovelSource
import com.example.source.isComicSource
import com.example.ui.components.GlassCard
import com.example.ui.glasskit.GlassKitCard
import com.example.ui.components.GlassDialogWindowEffect
import com.example.ui.components.scrollTiltSource
import com.example.ui.components.AcrylicBottomOverlay
import com.example.ui.components.PlayPauseMorphButton
import com.example.ui.components.filmGrain
import com.example.ui.components.iridescentBorder
import com.example.ui.components.liquidGlass
import com.example.ui.components.radialGlassScrim
import com.example.ui.components.rememberGlassPanelBackdrop
import com.example.ui.components.rememberIridescentColors
import com.example.ui.components.rememberThemedGlassBackdrop
import com.example.ui.components.SourceAvatar
import com.example.ui.components.ShimmerBox
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppButtonVariant
import com.example.ui.theme.glassTitleColor
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import com.example.ui.source.ZLibraryLoginDialog
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.design.DesignTokens
import com.example.ui.theme.AppFonts
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookImported: () -> Unit,
    onOpenSourceManagement: () -> Unit = {},
    onImportLocalBook: () -> Unit = {},
    onOpenComic: (SearchBook) -> Unit = {},
    onOpenLocalNovel: (com.example.data.Book) -> Unit = {},
    /** "sourceId::comicId" 集合：搜索结果卡片上的 ♡ 是否已喜欢 */
    favoriteKeys: Set<String> = emptySet(),
    /** 搜索结果直接点 ♡ → 加入 / 取消「我喜欢的」 */
    onToggleFavorite: (SearchBook, Boolean) -> Unit = { _, _ -> },
    extraBottomPadding: Dp = 0.dp
) {
    val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
    val aggregateMode by viewModel.aggregateMode.collectAsStateWithLifecycle()
    // 聚合搜索类别（v1.0.1）："comic" 漫画源 / "novel" 小说源，书源弹层分区选择、互斥过滤
    val aggregateKind by viewModel.aggregateKind.collectAsStateWithLifecycle()
    // 第十一轮第 6 条：多语言搜索开关状态（书源选择弹层内可控）
    val multiLangSearch by viewModel.multiLanguageSearch.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val availableSources by viewModel.availableSources.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchResults = (uiState as? LibraryUiState.SearchResults)?.results ?: emptyList()
    val isSearching = uiState is LibraryUiState.Searching
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val downloadStatesState = viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadStates by downloadStatesState
    val downloadedNovelKeys by viewModel.downloadedNovelKeys.collectAsStateWithLifecycle()
    val comicDownloading by viewModel.comicDownloading.collectAsStateWithLifecycle()
    val comicDownloadProgress by viewModel.comicDownloadProgress.collectAsStateWithLifecycle()
    val comicPaused by viewModel.comicPaused.collectAsStateWithLifecycle()
    val comicDownloadTasks by viewModel.comicDownloadTasks.collectAsStateWithLifecycle()
    val comicBook by viewModel.comicBook.collectAsStateWithLifecycle()
    val comicChapters by viewModel.comicChapters.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val formatPickerBook by viewModel.formatPickerBook.collectAsStateWithLifecycle()
    val pendingFormats by viewModel.pendingFormats.collectAsStateWithLifecycle()
    val formatLoading by viewModel.formatLoading.collectAsStateWithLifecycle()
    // 封面加载器：ZLibrary 走专用 DoH/会话 Cookie 加载器，其余（MangaDex/JS 源/聚合）走通用加载器
    val imageLoader = remember(aggregateMode, currentSource?.id) {
        if (aggregateMode || currentSource?.id != "zlibrary") {
            GenericCoverLoader.get(context)
        } else {
            ZLibraryCoverLoader.get(context)
        }
    }

    // Z-Library 走隐藏 WebView 会话（原生书库），其余书源走 OkHttp。
    val isZlibSource = currentSource?.id == "zlibrary"

    var nativeBooks by remember { mutableStateOf<List<SearchBook>>(emptyList()) }
    var nativeStatus by remember { mutableStateOf("") }
    var nativeSearching by remember { mutableStateOf(false) }
    var activeDownloadBook by remember { mutableStateOf<SearchBook?>(null) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var loginMessage by remember { mutableStateOf("") }
    var loginLoading by remember { mutableStateOf(false) }
    var loginChecked by remember { mutableStateOf(false) }
    var showDownloadPanel by remember { mutableStateOf(false) }
    var novelDetailBook by remember { mutableStateOf<SearchBook?>(null) }
    var novelDetailLoading by remember { mutableStateOf(false) }
    var novelDetailError by remember { mutableStateOf<String?>(null) }
    val hazeState = remember { HazeState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(novelDetailBook?.sourceId, novelDetailBook?.id) {
        val book = novelDetailBook ?: return@LaunchedEffect
        novelDetailLoading = true
        novelDetailError = null
        try {
            when (val result = viewModel.novelDetail(book)) {
                is com.example.source.SourceResult.Success -> novelDetailBook = result.data
                is com.example.source.SourceResult.Error -> novelDetailError = result.exception.message ?: "详情暂时无法加载"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { novelDetailError = e.message ?: "详情暂时无法加载" }
        finally { novelDetailLoading = false }
    }

    val session = remember {
        ZLibraryNativeSession(
            onSearchResults = { books, status ->
                nativeBooks = books
                nativeStatus = status
                nativeSearching = false
            },
            onRealDownloadUrl = { url ->
                val book = activeDownloadBook
                val cookies = CookieManager.getInstance()
                    .getCookie("https://${ZLibraryNodeConfig.domain}/") ?: ""
                viewModel.startWebViewDownload(book, url, cookies)
            },
            onLoginResult = { ok, msg ->
                loginLoading = false
                loginMessage = msg
                if (ok) showLoginDialog = false
            }
        )
    }

    // 离开书库页面时释放隐藏 WebView，避免渲染进程常驻占用 CPU/内存
    DisposableEffect(Unit) {
        onDispose {
            session.destroy()
        }
    }

    // 进入书库自动检测登录状态；未登录时弹出软件内登录窗口
    LaunchedEffect(isZlibSource, aggregateMode) {
        if (isZlibSource && !aggregateMode && !loginChecked) {
            loginChecked = true
            kotlinx.coroutines.delay(1200)
            // 登录检测只读 Cookie，无需提前创建隐藏 WebView（首次搜索/登录时再创建）
            viewModel.checkSourceLoginStatus()
            if (!viewModel.isCurrentSourceLoggedIn.value) {
                showLoginDialog = true
                loginMessage = "检测到未登录，登录后可正常下载（搜索无需登录）"
            }
        }
    }

    // 下载超时提示：点击下载后 15 秒仍未捕获真实文件链接（可能需登录/中间页）时给出反馈
    LaunchedEffect(activeDownloadBook?.id) {
        val book = activeDownloadBook
        if (book != null) {
            kotlinx.coroutines.delay(15000)
            val st = viewModel.downloadStates.value[com.example.download.DownloadManager.taskId(book.sourceId, book.id)]
            if (st == null || st is DownloadState.Idle) {
                nativeStatus = "未能获取下载链接（可能需要登录），可稍后重试"
            }
        }
    }

    LaunchedEffect(comicDownloading) {
        if (comicDownloading.isNotEmpty()) {
            showDownloadPanel = true
        }
    }

    var searchFieldFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 搜索词/滚动位置跨导航保持（1.05 修复）：进详情页（comic_chapters 路由）时
    // LibraryScreen 被销毁，remember 会全丢——回来后搜索词清空、grid 跳回顶部。
    // LazyStaggeredGridState 内部是可 parcel 的，rememberSaveable 原生支持。
    val staggeredGridState = rememberSaveable(saver = LazyStaggeredGridState.Saver) {
        LazyStaggeredGridState()
    }
    // 书架双视图滚动 → 卡片惯性倾斜信号源（任务书「整卡倾斜」§2）
    listState.scrollTiltSource()
    staggeredGridState.scrollTiltSource()

    // 滚动联动渐进折叠头部（书库专属强化版）：滚动偏移在 0→150dp 行程内连续映射为
    // fraction 0→1（内容紧凑化→整卡高度归零），而非旧版布尔两态切换；
    // 搜索聚焦时强制完全收起，给搜索结果让出首屏。回顶自然恢复。
    val headerCollapsePx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }

    // ⚠️ 键盘收起瞬间卡顿的缓解：IME 关闭那一帧里，会同时发生
    //   ① 搜索框失焦 → 头部从"完全收起"重新展开（重排 + 重绘）
    //   ② imePadding 收缩 → 结果网格整体重排
    // 两件重活撞在同一帧 → 掉帧。这里把"头部恢复"延后 120ms，
    // 让输入法的收起动画先跑完，避开同一帧的峰值。聚焦时仍然**立即**收起（手感不变）。
    var headerForceCollapse by remember { mutableStateOf(false) }
    LaunchedEffect(searchFieldFocused) {
        if (searchFieldFocused) {
            headerForceCollapse = true
        } else {
            kotlinx.coroutines.delay(120)
            headerForceCollapse = false
        }
    }
    val searchFocusedNow by rememberUpdatedState(headerForceCollapse)
    // ⚠️ 刻意**不用** derivedStateOf 在页面体里读取滚动状态：那样「滚动」或「聚焦折叠动画」
    // 的每一帧都会重组整个 LibraryScreen（3000+ 行），这是搜索弹/收键盘以及滚动卡顿的
    // 最大单项来源。改成 lambda 交给头部组件，状态读取发生在它内部，
    // 重组范围就收缩到头部那一小块。
    val headerFractionSource: () -> Float = {
        if (searchFocusedNow) {
            1f
        } else {
            val gridIdx = staggeredGridState.firstVisibleItemIndex
            val listIdx = listState.firstVisibleItemIndex
            if (gridIdx > 0 || listIdx > 0) {
                1f
            } else {
                val off = max(
                    staggeredGridState.firstVisibleItemScrollOffset,
                    listState.firstVisibleItemScrollOffset
                )
                (off / headerCollapsePx).coerceIn(0f, 1f)
            }
        }
    }

    val performSearch: (String) -> Unit = { keyword ->
        if (keyword.isNotBlank()) {
            // 新搜索从顶部开始：结果首屏回到中上部（修复视觉重心下移）
            scope.launch {
                staggeredGridState.scrollToItem(0)
                listState.scrollToItem(0)
            }
            viewModel.recordSearch(keyword)
            if (aggregateMode) {
                viewModel.aggregateSearch(keyword)
            } else {
                viewModel.search(keyword)
            }
        }
    }

    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val isCurrentSourceLoggedIn by viewModel.isCurrentSourceLoggedIn.collectAsStateWithLifecycle()

    // Filter environment-only sources in production UI
    val visibleSources = remember(availableSources) {
        availableSources.filter { !it.capabilities.environmentOnly }
    }

    var loginDialogSource by remember { mutableStateOf<BookSource?>(null) }
    // 搜索词跨导航保持（1.05 修复）：进详情页回来后搜索结果原样还在
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val detailSearchRequest by viewModel.detailSearchRequest.collectAsStateWithLifecycle()
    LaunchedEffect(detailSearchRequest?.token) {
        detailSearchRequest?.let { request ->
            searchQuery = request.keyword
            performSearch(request.keyword)
            viewModel.consumeDetailSearch(request.token)
        }
    }
    var showSourceSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 页面滚动时同步收起搜索历史面板
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && searchFieldFocused) {
                    searchFieldFocused = false
                    focusManager.clearFocus()
                }
            }
    }
    LaunchedEffect(staggeredGridState) {
        snapshotFlow { staggeredGridState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && searchFieldFocused) {
                    searchFieldFocused = false
                    focusManager.clearFocus()
                }
            }
    }

    // 聚合结果「源速跳」：面板开关 / 当前所处源组 / 流式结果跳转防漂移目标
    var showJumpSheet by remember { mutableStateOf(false) }
    var pendingJumpSourceId by remember { mutableStateOf<String?>(null) }
    // 每源结果预览折叠状态（任务一）：默认 false=只显示前 6 条；速跳定位也依赖此状态。
    // 1.05 修复：rememberSaveable 跨导航保持——进详情页回来后已展开的组不再收起。
    val expandedGroups = rememberSaveable(
        saver = Saver(
            save = { map -> map.filterValues { v -> v == true }.keys.toList() },
            restore = { restored: List<String> -> mutableStateMapOf<String, Boolean>().apply { restored.forEach { k -> put(k, true) } } }
        )
    ) { mutableStateMapOf<String, Boolean>() }
    val aggResults = uiState as? LibraryUiState.AggregateResults
    val hasAggregateBooks = aggResults?.groups?.any { it.books.isNotEmpty() } == true
    // Only follow the first hit while waiting at the top. On navigation back, initialize
    // from the restored results so this effect preserves the reader's saved position.
    var hadAggregateBooks by remember { mutableStateOf(hasAggregateBooks) }
    var waitingForFirstHitAtTop by remember { mutableStateOf(false) }
    LaunchedEffect(hasAggregateBooks) {
        if (!hasAggregateBooks) {
            hadAggregateBooks = false
            snapshotFlow {
                staggeredGridState.firstVisibleItemIndex == 0 &&
                    staggeredGridState.firstVisibleItemScrollOffset == 0 &&
                    !staggeredGridState.isScrollInProgress
            }.collect { waitingForFirstHitAtTop = it }
        }
        if (!hadAggregateBooks && waitingForFirstHitAtTop && aggregateKind == "comic" &&
            pendingJumpSourceId == null && !staggeredGridState.isScrollInProgress &&
            hasAggregateBooks
        ) {
            staggeredGridState.scrollToItem(0)
        }
        hadAggregateBooks = hasAggregateBooks
    }
    val activeGroupIdx by remember(aggResults?.groups) {
        derivedStateOf {
            val groups = aggResults?.groups ?: return@derivedStateOf -1
            val first = staggeredGridState.firstVisibleItemIndex
            var acc = 0
            var result = groups.lastIndex
            groups.forEachIndexed { i, g ->
                if (first >= acc) result = i
                acc += 1 + aggregateGroupItemCount(g, expandedGroups[g.sourceId] == true)
            }
            result
        }
    }

    // 跳转后数据仍在流式刷新（加载组完成会改变 item 数）→ 数据一变就对准目标组头，直至该组加载完成
    LaunchedEffect(aggResults?.groups) {
        val targetId = pendingJumpSourceId ?: return@LaunchedEffect
        val groups = aggResults?.groups ?: return@LaunchedEffect
        val idx = groups.indexOfFirst { it.sourceId == targetId }
        if (idx < 0) {
            pendingJumpSourceId = null
            return@LaunchedEffect
        }
        staggeredGridState.scrollToItem(groupHeaderIndex(groups, idx, expandedGroups))
        if (!groups[idx].loading) pendingJumpSourceId = null
    }

    loginDialogSource?.let { src ->
        ZLibraryLoginDialog(hazeState = hazeState,
            source = src,
            onDismiss = { loginDialogSource = null },
            onSuccess = {
                loginDialogSource = null
                viewModel.checkSourceLoginStatus()
            },
            // vomic 支持站内注册：登录窗口下方出现「注册新账号」（弹窗链驱动）
            onRegister = if (src.id == "js_vomic") {
                {
                    val result = (src as? com.example.source.js.JsComicSource)?.register()
                        ?: throw IllegalStateException("源不可用")
                    if (result is com.example.source.SourceResult.Error) {
                        throw Exception(result.exception.message ?: "注册失败")
                    }
                }
            } else null
        )
    }

    if (!hasSeenWelcome) {
        LibraryWelcomeScreen(
            onImportLocal = {
                viewModel.markLocalBookImported()
                viewModel.markWelcomeSeen()
                onImportLocalBook()
            },
            onOnlineSearch = {
                val onlineSource = visibleSources.firstOrNull()
                if (onlineSource != null) {
                    viewModel.selectSource(onlineSource.id)
                    viewModel.markWelcomeSeen()
                } else {
                    if (visibleSources.isNotEmpty()) {
                        viewModel.selectSource(visibleSources.first().id)
                        viewModel.markWelcomeSeen()
                    } else {
                        onOpenSourceManagement()
                        viewModel.markWelcomeSeen()
                    }
                }
            },
            onSourceManage = {
                viewModel.markWelcomeSeen()
                onOpenSourceManagement()
            },
            hasOnlineSource = visibleSources.isNotEmpty()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // 点击搜索框外任意区域：收起历史面板并释放焦点
                            if (searchFieldFocused) {
                                searchFieldFocused = false
                                focusManager.clearFocus()
                            }
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // 仅在登录弹窗/下载卡片可见时才启用毛玻璃背板，平时滚动不产生模糊开销
                        if (showLoginDialog || showDownloadPanel) Modifier.haze(hazeState) else Modifier
                    )
            ) {
    // 滚动渐进折叠头部（书库强化版）：fraction 连续驱动副标题淡出→标题缩小→整卡高度归零，
    // 完全收起后零垂直占用；回顶或搜索失焦后按同一路径平滑恢复。
    // 折叠补间与「读取滚动状态」都下沉到 LibraryCollapsingHeader 内部，
    // 这样动画的每一帧只重组头部，不再牵动整个页面。
    LibraryCollapsingHeader(
        fractionSource = headerFractionSource,
        focusDriven = searchFieldFocused,
        modifier = Modifier.statusBarsPadding(),
        title = "书库",
        subtitle = "LIBRARY & SEARCH",
        titleColor = glassTitleColor(),
                trailing = {
                    val latestSt = activeDownloadBook?.let { downloadStates[com.example.download.DownloadManager.taskId(it.sourceId, it.id)] }
                    val comicActive = comicDownloading.isNotEmpty()
                    IconButton(onClick = { showDownloadPanel = !showDownloadPanel }) {
                        Box(contentAlignment = Alignment.Center) {
                            if (latestSt is DownloadState.Downloading || comicActive) {
                                CircularProgressIndicator(
                                    progress = {
                                        if (latestSt is DownloadState.Downloading) {
                                            latestSt.progress.coerceIn(0f, 1f)
                                        } else {
                                            val task = comicDownloading.firstOrNull()
                                            (if (task != null) comicDownloadProgress[task] else null)
                                                ?.coerceIn(0f, 1f) ?: 0f
                                        }
                                    },
                                    modifier = Modifier.size(30.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "下载任务",
                                tint = if (latestSt is DownloadState.Downloading || comicActive) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp)
                    // ── 键盘避让：用 imeAnimationTarget（动画的**目标值**）而不是 ime（逐帧插值）──
                    // ime 在输入法升起/收起的 ~250ms 里逐帧变化，于是下方内容（检索图书插图、
                    // 瀑布流网格）每帧都要重新测量与布局 —— 这正是"点搜索框弹键盘卡、点空白收
                    // 键盘也卡"的根因：卡的不是玻璃，是每帧重排。
                    // 目标值只在动画开始/结束各变一次，**最终布局与 imePadding 完全一致**
                    // （视觉零差异），代价仅是不再跟随键盘做逐帧位移。
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            ) {
                // 一体化搜索组件：书源入口整合进搜索框左侧（任务书「删除独立书源区域+搜索框重新设计」）
                Column(modifier = Modifier.padding(horizontal = DesignTokens.SpacePage)) {
                    UnifiedSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { performSearch(searchQuery) },
                        onFocusChanged = { searchFieldFocused = it },
                        onSourceClick = { showSourceSheet = true },
                        sourceLabel = if (aggregateMode)
                            if (aggregateKind == "novel") "全部小说" else "全部漫画"
                        else currentSource?.name ?: "书源",
                        searchFocused = searchFieldFocused,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        GlassCard(
                            shape = RoundedCornerShape(12.dp),
                            tint = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (currentSource?.capabilities?.downloadRequiresLogin == true && !isCurrentSourceLoggedIn) {
                                    TextButton(onClick = onOpenSourceManagement) {
                                        Text("去管理/登录")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // 面板显隐条件（玻璃外观与动画均保持原有实现，只做了两处不改视觉的提速：
                // 动画时长 320/280 → 200/180；聚焦期间禁用网格的 animateItemPlacement）
                val historyPanelVisible =
                    searchFieldFocused && searchQuery.isBlank() && searchHistory.isNotEmpty()
                var historyPanelSettled by remember { mutableStateOf(false) }
                LaunchedEffect(historyPanelVisible) {
                    if (historyPanelVisible) {
                        kotlinx.coroutines.delay(330)
                        historyPanelSettled = true
                    } else {
                        historyPanelSettled = false
                    }
                }

                // 搜索历史：点击搜索框获得焦点、输入为空且历史非空时，以“窗帘”动画展开/收起
                androidx.compose.animation.AnimatedVisibility(
                    visible = historyPanelVisible,
                    enter = expandVertically(
                        animationSpec = tween(320, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f))
                    ) + fadeIn(
                        animationSpec = tween(320, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f))
                    ),
                    exit = shrinkVertically(
                        animationSpec = tween(280, easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f))
                    ) + fadeOut(
                        animationSpec = tween(240, easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f))
                    )
                ) {
                    SearchHistoryPanel(
                        history = searchHistory,
                        lightweight = !historyPanelSettled,
                        onPick = { q ->
                            searchQuery = q
                            performSearch(q)
                        },
                        onDelete = { q -> viewModel.removeSearchHistory(q) },
                        onClearAll = { viewModel.clearSearchHistory() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (aggregateMode && uiState is LibraryUiState.AggregateResults) {
                    val agg = uiState as LibraryUiState.AggregateResults
                    // 每源结果预览折叠（任务一）：换搜索词才复位展开组。
                    // 1.05 修复：此前 LaunchedEffect(searchQuery) 首次组合必跑，
                    // 从详情页返回时把 rememberSaveable 恢复的展开状态全清了——
                    // 这就是“展开的会不见+页面跳位”的直接根因。
                    var lastQueryForExpandReset by rememberSaveable { mutableStateOf<String?>(null) }
                    LaunchedEffect(searchQuery) {
                        when (lastQueryForExpandReset) {
                            null -> lastQueryForExpandReset = searchQuery   // 恢复态：不清
                            searchQuery -> {}                                // 同词重组：不清
                            else -> {
                                expandedGroups.clear()                       // 真正换了词：复位
                                lastQueryForExpandReset = searchQuery
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(
                            // 多设备一致：手机2列 / 中屏3列 / 宽屏4列，避免平板上两列被拉伸过宽。
                            // 改用全 App 统一断点体系 AdaptiveSpec（此前此处自行判断 screenWidthDp，
                            // 与 AdaptiveSpec.rememberWindowWidthClass 的 600/840 断点重复定义）。
                            when (com.example.ui.adaptive.rememberWindowWidthClass()) {
                                com.example.ui.adaptive.WindowWidthClass.EXPANDED -> 4
                                com.example.ui.adaptive.WindowWidthClass.MEDIUM -> 3
                                else -> 2
                            }
                        ),
                        state = staggeredGridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 8.dp,
                            end = 16.dp,
                            bottom = 16.dp + extraBottomPadding
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp
                    ) {
                        agg.groups.forEach { group ->
                            item(
                                key = "agg_group_header_${group.sourceId}",
                                contentType = "fullspan",
                                span = StaggeredGridItemSpan.FullLine
                            ) {
                                AggregateSourceHeader(
                                    name = group.sourceName,
                                    loading = group.loading,
                                    resultCount = group.books.size,
                                    onClick = { showJumpSheet = true }
                                )
                            }
                            // 已有结果即显示，loading 仅表示后台仍在补充其它语言。
                            if (group.books.isNotEmpty()) {
                                val groupExpanded = expandedGroups[group.sourceId] == true
                                val visibleBooks = if (groupExpanded) group.books
                                    else group.books.take(AGGREGATE_PREVIEW_COUNT)
                                gridItems(visibleBooks, key = { "${group.sourceId}_${it.id}" }, contentType = { "book" },
                                    span = { book -> if (availableSources.any { it.id == book.sourceId && it.isNovelSource }) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane }) { book ->
                                    val bookSource = availableSources.firstOrNull { it.id == book.sourceId }
                                    if (bookSource?.isNovelSource == true) {
                                        NovelSearchCard(book, group.sourceName, if (book.sourceId == "zlibrary") ZLibraryCoverLoader.get(context) else imageLoader,
                                            state = downloadStates[com.example.download.DownloadManager.taskId(book.sourceId, book.id)] ?: DownloadState.Idle,
                                            downloaded = com.example.download.DownloadManager.taskId(book.sourceId, book.id) in downloadedNovelKeys,
                                            onClick = { novelDetailBook = book })
                                    } else {
                                    StaggeredComicCard(
                                        book = book,
                                        imageLoader = imageLoader,
                                        coverHeaders = rememberCoverHeaders(book, availableSources),
                                        sourceName = group.sourceName,
                                        novel = bookSource?.isNovelSource == true,
                                        favorite = com.example.data.favorite.favoriteKey(
                                            book.sourceId, book.id
                                        ) in favoriteKeys,
                                        onToggleFavorite = { next -> onToggleFavorite(book, next) },
                                        // 2026-09-21：书架瀑布流重排动画（此前因 items 同名
                                        // 导致 receiver 歧义一直没做）。折叠/展开分组、
                                        // 删除书籍后，其余卡片平滑归位而不是瞬间跳排。
                                        // 输入法弹出/收起时 IME insets 动画会持续改变网格高度，
                                        // 若此时保留 animateItemPlacement，每个可见卡片都会跟着做
                                        // 位移动画（几十个动画器同时跑）→ 掉帧。聚焦期间直接跳位。
                                        modifier = if (searchFieldFocused) Modifier
                                        else Modifier.animateItemPlacement(),
                                        onClick = {
                                            onOpenComic(book)
                                        }
                                    )
                                    }
                                }
                                // 展开按钮：还有隐藏结果时出现在第 6 条之后
                                if (!groupExpanded && group.books.size > AGGREGATE_PREVIEW_COUNT) {
                                    item(
                                        key = "agg_expand_${group.sourceId}",
                                        span = StaggeredGridItemSpan.FullLine
                                    ) {
                                        AggregateExpandButton(
                                            hiddenCount = group.books.size - visibleBooks.size,
                                            onExpand = { expandedGroups[group.sourceId] = true }
                                        )
                                    }
                                }
                            } else if (group.loading) {
                                gridItems(List(4) { it }, contentType = { "skeleton" }) { index ->
                                    ShimmerBox(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(STAGGER_RATIO_PALETTE[index % STAGGER_RATIO_PALETTE.size])
                                    )
                                }
                            } else {
                                item(
                                    key = "agg_group_error_${group.sourceId}",
                                    span = StaggeredGridItemSpan.FullLine
                                ) {
                                    AggregateSourceError(
                                        error = group.error
                                    )
                                }
                            }
                        }
                        if (!agg.running && agg.groups.none { it.books.isNotEmpty() }) {
                            item(
                                key = "agg_empty",
                                span = StaggeredGridItemSpan.FullLine
                            ) {
                                com.example.ui.components.MascotEmptyState(
                                    mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                                    title = "未找到结果",
                                    description = "所有${if (aggregateKind == "novel") "小说源" else "漫画源"}都没有匹配“$searchQuery”的内容",
                                    actionLabel = "管理与导入书源",
                                    onActionClick = onOpenSourceManagement,
                                    testTagPrefix = "aggregate_empty_state"
                                )
                            }
                        }
                    }
                    // 聚合结果回顶：滑过一屏后浮现，点击回到搜索顶部
                    val showTopFab by remember {
                        derivedStateOf { staggeredGridState.firstVisibleItemIndex > 8 }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showTopFab,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = extraBottomPadding + 16.dp),
                        enter = scaleIn(
                            initialScale = 0.6f,
                            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
                        ) + fadeIn(),
                        exit = scaleOut(targetScale = 0.6f) + fadeOut()
                    ) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { staggeredGridState.animateScrollToItem(0) } },
                            modifier = Modifier.consistentShadow(3.dp, CircleShape),
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MintPrimary,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp,
                                focusedElevation = 0.dp,
                                hoveredElevation = 0.dp
                            )
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "回到顶部")
                        }
                    }
                    }
                    if (showJumpSheet) {
                        AggregateJumpSheet(
                            groups = agg.groups,
                            activeGroupIdx = activeGroupIdx,
                            onDismiss = { showJumpSheet = false },
                            onJump = { idx ->
                                showJumpSheet = false
                                scope.launch {
                                    if (idx < 0) {
                                        pendingJumpSourceId = null
                                        staggeredGridState.animateScrollToItem(0)
                                    } else {
                                        pendingJumpSourceId = agg.groups[idx].sourceId
                                        staggeredGridState.animateScrollToItem(
                                            groupHeaderIndex(agg.groups, idx, expandedGroups)
                                        )
                                    }
                                }
                            }
                        )
                    }
                } else if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        ChasingDots(
                            size = 52.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else if (uiState is LibraryUiState.Error) {
                    val error = (uiState as LibraryUiState.Error).error
                    val (title, desc, action) = when (error) {
                        is LibraryError.NetworkUnavailable -> Triple("无网络连接", "请检查网络设置后重试", "重试")
                        // v1.0.1：网络失败展示真实原因（HTTP 状态码 / DNS / TLS / 超时），便于排查
                        is LibraryError.NetworkDetail -> Triple("请求失败", error.message, "重试")
                        is LibraryError.SourceUnavailable -> Triple("服务无响应", "当前书源站点暂无响应，请稍后重试或切换书源", "重试")
                        is LibraryError.AuthenticationRequired -> Triple("需要登录", "当前书源需要账号身份验证", "去登录")
                        is LibraryError.CloudflareBlocked -> Triple("安全验证拦截", "目标站点已启用安全防护，请稍后重试", "重试")
                        is LibraryError.ParseFailed -> Triple("数据解析失败", "返回数据格式异常，无法解析内容", "重试")
                        else -> Triple("请求超时", error.message ?: "网络请求超时，请重试", "重试")
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.ui.components.MascotEmptyState(
                            mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                            title = title,
                            description = desc,
                            actionLabel = action,
                            onActionClick = {
                                if (error is LibraryError.AuthenticationRequired) {
                                    onOpenSourceManagement()
                                } else {
                                    performSearch(searchQuery)
                                }
                            },
                            testTagPrefix = "search_error_state"
                        )
                    }
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.ui.components.MascotEmptyState(
                            mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                            title = if (searchQuery.isBlank()) "检索图书" else "未找到结果",
                            description = if (searchQuery.isBlank()) {
                                "在上方输入书名、作者或关键词"
                            } else {
                                "未找到与“$searchQuery”匹配的内容，请尝试更换关键词或书源"
                            },
                            actionLabel = "管理与导入书源",
                            onActionClick = onOpenSourceManagement,
                            testTagPrefix = "search_empty_state"
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp + extraBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        columnItems(searchResults, key = { it.id }) { book ->
                            val st by remember(book.id) {
                                derivedStateOf {
                                    downloadStatesState.value[com.example.download.DownloadManager.taskId(book.sourceId, book.id)] ?: DownloadState.Idle
                                }
                            }
                            if (currentSource?.isNovelSource == true) {
                                NovelSearchCard(book, currentSource?.name.orEmpty(), imageLoader, state = st,
                                    downloaded = com.example.download.DownloadManager.taskId(book.sourceId, book.id) in downloadedNovelKeys,
                                    onClick = { novelDetailBook = book })
                            } else LibraryBookCard(
                                book = book,
                                downloadState = st,
                                // 同上：输入法动画期间不做卡片位移动画，避免几十个动画器并发
                                modifier = if (searchFieldFocused) Modifier
                                else Modifier.animateItemPlacement(),
                                imageLoader = imageLoader,
                                coverHeaders = rememberCoverHeaders(book, availableSources),
                                comicMode = currentSource?.capabilities?.supportComic == true ||
                                        currentSource?.capabilities?.supportOnlineText == true,
                                favorite = com.example.data.favorite.favoriteKey(
                                    book.sourceId, book.id
                                ) in favoriteKeys,
                                onToggleFavorite = { next -> onToggleFavorite(book, next) },
                                onStartDownload = {
                                    if (currentSource?.capabilities?.supportComic == true ||
                                        currentSource?.capabilities?.supportOnlineText == true) {
                                        onOpenComic(book)
                                    } else if (currentSource?.capabilities?.downloadRequiresLogin == true && !isCurrentSourceLoggedIn) {
                                        loginDialogSource = currentSource
                                    } else {
                                        activeDownloadBook = book
                                        showDownloadPanel = true
                                        viewModel.startDownload(book)
                                    }
                                },
                                onPauseDownload = { viewModel.pauseDownload(com.example.download.DownloadManager.taskId(book.sourceId, book.id)) },
                                onResumeDownload = { viewModel.resumeDownload(com.example.download.DownloadManager.taskId(book.sourceId, book.id)) },
                                onCancelDownload = { viewModel.cancelDownload(com.example.download.DownloadManager.taskId(book.sourceId, book.id)) }
                            )
                        }
                    }
                }

                // 原生登录弹窗
                if (showLoginDialog) {
                    LibraryLoginDialog(
                        message = loginMessage,
                        loading = loginLoading,
                        onLogin = { email, pass ->
                            val src = currentSource
                            if (src == null) {
                                loginLoading = false
                                loginMessage = "当前书源不可用"
                                return@LibraryLoginDialog
                            }
                            loginLoading = true
                            loginMessage = "登录中…"
                            scope.launch {
                                when (val result = src.login(LoginCredential(username = email, password = pass))) {
                                    is SourceResult.Success -> {
                                        loginLoading = false
                                        loginMessage = "登录成功"
                                        showLoginDialog = false
                                        viewModel.checkSourceLoginStatus()
                                    }
                                    is SourceResult.Error -> {
                                        loginLoading = false
                                        loginMessage = result.exception.message ?: "登录失败，请检查账号密码"
                                    }
                                }
                            }
                        },
                        onDismiss = { if (!loginLoading) showLoginDialog = false },
                        hazeState = hazeState
                    )
                }

                // 居中悬浮下载面板（毛玻璃 + Q弹弹簧动画）
            }
        }

        novelDetailBook?.let { book ->
            val id = com.example.download.DownloadManager.taskId(book.sourceId, book.id)
            NovelDetailSheet(book, availableSources.firstOrNull { it.id == book.sourceId }?.name.orEmpty(),
                if (book.sourceId == "zlibrary") ZLibraryCoverLoader.get(context) else imageLoader,
                novelDetailLoading, novelDetailError, downloadStates[id] ?: DownloadState.Idle,
                hasLocal = id in downloadedNovelKeys,
                onDismiss = { novelDetailBook = null }, onDownload = {
                    scope.launch {
                        val source = availableSources.firstOrNull { it.id == book.sourceId }
                        if (source?.capabilities?.downloadRequiresLogin == true && !source.isLoggedIn()) {
                            loginDialogSource = source
                        } else if (book.sourceId == "zlibrary") viewModel.startDownload(book)
                        else viewModel.startDownload(book, book.format)
                    }
                },
                onRead = { scope.launch { viewModel.localNovel(book)?.let { local -> novelDetailBook = null; onOpenLocalNovel(local) } } },
                onPause = { viewModel.pauseDownload(id) }, onResume = { viewModel.resumeDownload(id) }, onCancel = { viewModel.cancelDownload(id) })
        }

        // 下载格式选择弹窗（Z-Library 多格式书源）
        formatPickerBook?.let { book ->
            FormatPickerDialog(
                bookTitle = book.title,
                formats = pendingFormats,
                loading = formatLoading,
                onPick = { fmt -> viewModel.startDownload(book, fmt.format) },
                onDismiss = { viewModel.dismissFormatPicker() },
                hazeState = hazeState
            )
        }

        // 下载管理中心：亚克力底部面板（Dialog + decorView 实时模糊 + 径向遮罩）
        if (showDownloadPanel) {
            AcrylicBottomOverlay(onDismissRequest = { showDownloadPanel = false }) {
                var appear by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appear = true }
                val panelScale by animateFloatAsState(
                    targetValue = if (appear) 1f else 0.55f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "downloadPanelSpring"
                )
                val panelAlpha by animateFloatAsState(
                    targetValue = if (appear) 1f else 0f,
                    animationSpec = tween(220),
                    label = "downloadPanelFade"
                )
                val book = activeDownloadBook
                val st = book?.let { downloadStates[com.example.download.DownloadManager.taskId(it.sourceId, it.id)] } ?: DownloadState.Idle
                val comicTasks = comicDownloadTasks.values.toList()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .graphicsLayer {
                            scaleX = panelScale
                            scaleY = panelScale
                            alpha = panelAlpha
                        }
                ) {
                    if (comicTasks.isNotEmpty()) {
                        ComicDownloadGlassCard(
                            tasks = comicTasks,
                            onPause = { viewModel.pauseComicChapter(it) },
                            onResume = { id ->
                                comicDownloadTasks[id]?.let { t ->
                                    viewModel.downloadComicChapter(t.book, t.chapter)
                                }
                            },
                            onRetry = { id ->
                                comicDownloadTasks[id]?.let { t ->
                                    viewModel.retryComicChapter(t.book, t.chapter)
                                }
                            },
                            onCancel = { viewModel.cancelComicChapter(it) },
                            onDismiss = { showDownloadPanel = false }
                        )
                    } else {
                        DownloadGlassCard(
                            book = book,
                            state = st,
                            hazeState = hazeState,
                            onDismiss = { showDownloadPanel = false },
                            onPause = { book?.let { viewModel.pauseDownload(com.example.download.DownloadManager.taskId(it.sourceId, it.id)) } },
                            onResume = { book?.let { viewModel.resumeDownload(com.example.download.DownloadManager.taskId(it.sourceId, it.id)) } },
                            onCancel = { book?.let { viewModel.cancelDownload(com.example.download.DownloadManager.taskId(it.sourceId, it.id)) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 书源选择 Liquid Glass 底部弹层
        if (showSourceSheet) {
            // v1.0.1：按源类型分区——聚合漫画（漫画源）/ 聚合小说（小说源）互斥展示
            val comicSources = remember(visibleSources) {
                visibleSources.filter { it.isComicSource }
            }
            val novelSources = remember(visibleSources) {
                visibleSources.filter { it.isNovelSource }
            }
            SourcePickerSheet(
                aggregateMode = aggregateMode,
                aggregateKind = aggregateKind,
                currentSource = currentSource,
                comicSources = comicSources,
                novelSources = novelSources,
                multiLanguageSearch = multiLangSearch,
                onToggleMultiLanguageSearch = { viewModel.setMultiLanguageSearch(it) },
                onSelectAggregate = { kind ->
                    viewModel.setAggregateMode(true)
                    viewModel.setAggregateKind(kind)
                    // 双状态残留修复：切模式后非活跃视图的滚动位置不清零会让折叠头部错误保持收起
                    scope.launch {
                        staggeredGridState.scrollToItem(0)
                        listState.scrollToItem(0)
                    }
                    showSourceSheet = false
                },
                onSelectSource = { id ->
                    viewModel.selectSource(id)
                    scope.launch {
                        staggeredGridState.scrollToItem(0)
                        listState.scrollToItem(0)
                    }
                    showSourceSheet = false
                },
                onManageSources = {
                    showSourceSheet = false
                    onOpenSourceManagement()
                },
                onDismiss = { showSourceSheet = false }
            )
        }
    }
}
}

/**
 * 书库渐进折叠头部（强化版收缩）——区别于四 Tab 共享的 [TabScreenHeader] 布尔两态：
 * [fraction] 由滚动偏移连续驱动（0=完全展开，1=完全收起零占位），
 * 三段式映射（区间重叠交叉淡化）保证全程连续无跳变：
 * - 0→0.5：内容紧凑化（副标题双通道淡出、标题 24→19sp、内外 padding 收紧）
 * - 0.4→0.8：整卡淡出（先于压高完成，压高裁切时内容已不可见，杜绝"半截字"伪影）
 * - 0.6→1：整卡高度按比例归零（自绘 layout 压缩高度，真正释放布局空间）
 * 滚动中每帧只重排一个 layout 节点，无监听器开销；回顶按同一路径反向恢复。
 */
@Composable
private fun LibraryCollapsingHeader(
    /**
     * 折叠目标值的**来源**（不在页面体里求值）。
     *
     * 传 lambda 而不是 Float：滚动位置、聚焦状态这些 State 的读取发生在
     * **本组件内部**，于是它们的每一帧变化只让头部重组，不会重组整个 LibraryScreen。
     */
    fractionSource: () -> Float,
    /** 目标来自搜索聚焦（状态跳变）：用 260ms 补间，否则用 120ms（滚动驱动）。 */
    focusDriven: Boolean = false,
    modifier: Modifier = Modifier,
    title: String = "书库",
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val target = fractionSource()
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (focusDriven || target == 1f) tween(260) else tween(120),
        label = "libHeaderFraction",
    )
    val f0 = fraction.coerceIn(0f, 1f)
    // 三段式：0→0.5 内容紧凑化；0.4→0.8 整卡淡出；0.6→1 压高归零——
    // 淡出先于压高完成，避免压高裁切把标题/图标拦腰截断的"半截字"伪影
    val compact = (f0 / 0.5f).coerceIn(0f, 1f)
    val fade = ((f0 - 0.4f) / 0.4f).coerceIn(0f, 1f)
    val collapse = ((f0 - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val titleSize = 24f - 5f * compact
    val subAlpha = (1f - f0 / 0.3f).coerceIn(0f, 1f)
    // 副标题槽位 20dp：12sp 字体自然行高约 17.6~19sp，17dp 会把英文降部
    // （"LIBRARY & SEARCH" 的 Y / & 下缘）裁掉 —— 厂商字体 descent 越大裁得越狠。
    // 槽位放大后配合 clipToBounds 只在折叠收起时裁切。
    val subH = (20 * subAlpha).dp
    val outerV = (10 * (1f - collapse) * (1f - collapse)).dp
    val innerV = (12 - 8 * compact).dp

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = outerV)
            // 高度按 (1-collapse) 等比压缩 + 淡出（淡出已完成，裁切不可见）：
            // 完全收起后高度 0、padding 0，真正零垂直占用。
            // placement 必须真正 place placeable——空 placement 块会让整卡不参与绘制
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * (1f - collapse)).roundToInt()
                layout(constraints.maxWidth, h) {
                    placeable.placeRelative(0, 0)
                }
            }
            .alpha(1f - fade)
            .clipToBounds(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = innerV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke(this)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = titleSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    fontFamily = AppFonts.Serif,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = titleColor.copy(alpha = 0.75f),
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .height(subH)
                            .alpha(subAlpha)
                            .clipToBounds()
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}

/**
 * 一体化搜索组件：书源入口 + 搜索输入合体（任务书「搜索框重新设计」）。
 * 左侧书源区（当前书源名 + 下拉箭头）点击展开 Liquid Glass 书源选择浮层，
 * 不触发搜索；右侧 BasicTextField 承载输入，IME 搜索键直接发起检索；
 * 保留原有清空按钮与焦点联动（搜索历史浮层依赖 onFocusChanged）。
 * [searchFocused]：聚焦态描边高亮信号。
 */
@Composable
private fun UnifiedSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSourceClick: () -> Unit,
    sourceLabel: String,
    modifier: Modifier = Modifier,
    searchFocused: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    // C2：原先硬编码 Color.White / DarkGray / Gray，并用 isSystemInDarkTheme() 判断，
    // 与 App 自己的 autoNightMode 主题开关不同步（用户在 App 内开夜间模式，搜索框仍是浅色）。
    // 改走 MaterialTheme 语义色，自动跟随 App 主题。
    val primary = MaterialTheme.colorScheme.primary
    val fieldTextColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val sourceTint = MaterialTheme.colorScheme.onSurface
    // 聚焦态视觉反馈：整条胶囊轻微放大 1.02 + 描边转 primary
    val focusScale by animateFloatAsState(
        targetValue = if (searchFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "searchFocusScale"
    )
    val capsuleShape = RoundedCornerShape(50)

    GlassCard(
        modifier = modifier
            .heightIn(min = 56.dp)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
            }
            // 玻璃描边：常态 = 1px 左上白 70% → 右下白 10% 渐变高光；聚焦 = 整体转 primary
            .glassEdge(capsuleShape, focused = searchFocused, accent = primary),
        shape = capsuleShape,
        tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
    ) {
        // fillMaxWidth 而非 fillMaxSize：fillMaxHeight 会吃掉父级剩余全部屏高，
        // 把 56dp 搜索卡撑成整屏大空面板（用户实机反馈"搜索框那么大"的根因）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ① 范围选择：点击展开书源选择浮层（复用 Liquid Glass SourcePickerSheet）；
            // 保留 ripple 按压反馈；垂直 padding 加大到 14dp 保证 ≥44dp 命中高度
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSourceClick)
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp)
            ) {
                Text(
                    text = sourceLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = sourceTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "选择书源",
                    tint = sourceTint.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
            // 范围区与输入区之间的竖线分隔。
            // 高度必须固定：fillMaxHeight 在 Row 内会把子项量到父级最大可用高度（~整屏），
            // Row 随之被撑满 → 56dp 搜索卡变成整屏大空面板（用户实机"搜索框那么大"的真根因）
            Box(
                modifier = Modifier
                    .padding(vertical = 14.dp)
                    .width(1.dp)
                    .height(24.dp)
                    .background(dividerColor)
            )
            // ② 输入区
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = fieldTextColor),
                cursorBrush = SolidColor(primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearch()
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (query.isEmpty()) {
                            // 占位字号与输入一致（15sp，避免首键入瞬间跳变）；单行防大字体溢出
                            Text(
                                text = "搜索书名或作者",
                                fontSize = 15.sp,
                                color = placeholderColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        tint = sourceTint.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }
            // ③ 圆形 primary 搜索按钮
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(primary)
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 玻璃描边：常态为 1px「左上白 70% → 右下白 10%」渐变高光；[focused] 时整条转为强调色。
 * 与内部全局玻璃语言一致（不新造玻璃实现，只复用现成的 border + Brush）。
 */
private fun Modifier.glassEdge(
    shape: Shape,
    focused: Boolean,
    accent: Color
): Modifier = this.border(
    // 规范：**未聚焦无彩色描边**（只用中性白渐变做边缘高光）；
    // 只有聚焦时才出现 **1.5px primary 描边**。
    width = if (focused) 1.5.dp else 1.dp,
    brush = if (focused) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.90f), accent))
    } else {
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.70f), Color.White.copy(alpha = 0.10f))
        )
    },
    shape = shape
)

/**
 * 搜索历史玻璃卡。
 * · 流式胶囊（FlowRow，13sp / 内边距 7×14 / 淡染底）
 * · 点按直接搜索 + 按压回弹；长按进入编辑（胶囊轻微抖动 + × 单条删除），点空白退出
 * · 超过两行自动折叠，底部「展开 / 收起」带高度动画
 * · 进场时每个胶囊依次弹出（延迟 50ms，上移 8px + 渐显）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    /** 展开/收起动画进行中：玻璃只跑 blur，不跑折射（见 GlassTokens.BlurLightweight）。 */
    lightweight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val error = MaterialTheme.colorScheme.error

    var editing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // ⚠️ 长按进入编辑态后，**系统返回键必须先退出编辑态**再交给页面处理：
    // 否则用户按返回会直接离开搜索页（或收起键盘），编辑态卡住消不掉。
    // 用 BackHandler 在编辑期间拦截，第一次返回 = 退出编辑（与"点空白退出"同一语义）。
    BackHandler(enabled = editing) { editing = false }

    // 编辑态抖动：0→1→0 往复。仅 editing 期间运行，退出即停（不留常驻循环动画）
    val shake = remember { Animatable(0f) }
    LaunchedEffect(editing) {
        if (editing) {
            while (true) {
                shake.animateTo(1f, animationSpec = tween(170, easing = LinearEasing))
                shake.animateTo(0f, animationSpec = tween(170, easing = LinearEasing))
            }
        } else {
            shake.snapTo(0f)
        }
    }

    // 折叠交给 FlowRow 自己的溢出处理：它只会渲染**能完整放下**的胶囊，
    // 因此不会再出现"被切掉半截的胶囊"（此前固定高度 + clipToBounds 的病根）。
    val items = history.take(10)

    // 上一版的搜索历史卡：GlassKit（背板库实时采样 + 连续曲率 + 边缘折射）。
    // 卡内一律平涂，不再各自做玻璃或渐变。
    GlassKitCard(
        modifier = modifier.fillMaxWidth(),
        lightweight = lightweight
    ) {
        Column(
            modifier = Modifier
                // 点空白退出编辑态（子胶囊会先消费点击，不会误触）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { editing = false }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "搜索历史",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 危险色清空：小垃圾桶 +「清空」文字，点击先弹确认
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { confirmClear = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = error,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            HistoryChips(
                items = items,
                editing = editing,
                shakeValue = shake.value,
                expanded = expanded,
                onToggleExpanded = { expanded = it },
                onPick = onPick,
                onDelete = onDelete,
                onEnterEdit = { editing = true }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = {
                Text(
                    text = "清空搜索历史？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "将删除全部 ${history.size} 条历史搜索词，此操作不可撤销。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    editing = false
                    onClearAll()
                }) {
                    Text(text = "清空", color = error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 单条历史胶囊：淡染底 + 13sp + 内边距 7×14；按压回弹；编辑态抖动并显示 × 删除。
 * 进场动画：按 [index] 每个延迟 50ms，上移 8px + 渐显（只跑一次）。
 */
/**
 * 胶囊的流式排布 + 折叠。
 *
 * 关键：折叠交给 FlowRow 的 `maxLines` + `expandOrCollapseIndicator` ——
 * 它只会渲染**能完整放下**的胶囊，因此永远不会出现被切掉半截的胶囊
 * （这是上一版"测量高度 → 固定 height + clipToBounds"的病根）。
 *
 * 需要 Foundation 1.7+ 的 FlowRow 溢出 API，本项目的 composeBom(2024.11) 满足。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryChips(
    items: List<String>,
    editing: Boolean,
    shakeValue: Float,
    expanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEnterEdit: () -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(280, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f))
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = FlowRowOverflow.expandOrCollapseIndicator(
            expandIndicator = {
                HistoryChip(
                    text = "更多 ⌄",
                    index = 0,
                    muted = true,
                    editing = false,
                    shakeValue = 0f,
                    onPick = { onToggleExpanded(true) },
                    onDelete = {},
                    onEnterEdit = {}
                )
            },
            collapseIndicator = {
                HistoryChip(
                    text = "收起 ⌃",
                    index = 0,
                    muted = true,
                    editing = false,
                    shakeValue = 0f,
                    onPick = { onToggleExpanded(false) },
                    onDelete = {},
                    onEnterEdit = {}
                )
            },
            minRowsToShowCollapse = 3
        )
    ) {
        items.forEachIndexed { index, q ->
            HistoryChip(
                text = q,
                index = index,
                editing = editing,
                shakeValue = shakeValue,
                onPick = { onPick(q) },
                onDelete = { onDelete(q) },
                onEnterEdit = onEnterEdit
            )
        }
    }
}

/**
 * 单条历史胶囊：全圆、高 32、水平内边距 14、13sp、primary 平涂（普通 10% / 弱化 6%）。
 * 按压回弹；编辑态抖动并显示 × 删除；进场按 [index] 每个延迟 40ms（上移 8dp + 渐显）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryChip(
    text: String,
    index: Int,
    editing: Boolean,
    shakeValue: Float,
    onPick: () -> Unit,
    onDelete: () -> Unit,
    onEnterEdit: () -> Unit,
    muted: Boolean = false
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 进场：每个延迟 40ms（文档值），上限 400ms 避免长列表尾部等太久
        kotlinx.coroutines.delay((index * 40L).coerceAtMost(400L))
        enter.animateTo(
            1f,
            animationSpec = tween(260, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f))
        )
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chipPress"
    )

    val enterShiftPx = with(density) { 8.dp.toPx() }
    val shakePx = with(density) { 1.6.dp.toPx() }
    val shakeDir = if (index % 2 == 0) 1f else -1f
    val chipShape = RoundedCornerShape(50)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * enterShiftPx
                scaleX = pressScale
                scaleY = pressScale
                translationX = if (editing) (shakeValue - 0.5f) * 2f * shakePx * shakeDir else 0f
                rotationZ = if (editing) (shakeValue - 0.5f) * 1.5f * shakeDir else 0f
            }
            .clip(chipShape)
            // 规范：胶囊高 32、水平内边距 14、字号 13、**平涂**无描边无渐变
            .height(32.dp)
            .background(primary.copy(alpha = if (muted) 0.06f else 0.10f))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (!editing) onPick() },
                onLongClick = { onEnterEdit() }
            )
            .padding(horizontal = 14.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 140.dp)
        )
        if (editing) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = onSurfaceVariant,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePickerSheet(
    aggregateMode: Boolean,
    aggregateKind: String,
    currentSource: BookSource?,
    comicSources: List<BookSource>,
    novelSources: List<BookSource>,
    multiLanguageSearch: Boolean,
    onToggleMultiLanguageSearch: (Boolean) -> Unit,
    onSelectAggregate: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onManageSources: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val dismiss = {
        if (!dismissed) {
            dismissed = true
            visible = false
        }
    }
    LaunchedEffect(dismissed) {
        if (dismissed) {
            kotlinx.coroutines.delay(280)
            onDismiss()
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val backdrop = rememberGlassPanelBackdrop()
    val iridescentColors = rememberIridescentColors()
    val blurPx = with(density) { 24.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 系统"减少透明度/关闭动画"开启时降级：不再用折射等重效果，面板更实
    val sheetContext = androidx.compose.ui.platform.LocalContext.current
    val reduceEffects = remember {
        val resolver = sheetContext.contentResolver
        val reduceTransparency = try {
            android.provider.Settings.Global.getInt(resolver, "reduce_transparency", 0) == 1
        } catch (_: Exception) {
            false
        }
        val animationsOff = try {
            android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
        reduceTransparency || animationsOff
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        // 透明窗口 + 实时模糊宿主窗口（decorView RenderEffect）
        GlassDialogWindowEffect(activity = activity, blurRadiusPx = blurPx)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)) +
                slideInVertically(tween(340), initialOffsetY = { it }),
            exit = fadeOut(tween(150)) +
                slideOutVertically(tween(260), targetOffsetY = { it })
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 径向渐变遮罩：中心亮、四周暗，聚光灯打在立牌上
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .radialGlassScrim()
                )
                // 点击空白处关闭
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = dismiss
                        )
                )
                // 液态玻璃弹窗本体：半透明 + 折射 + 高光 + 投影
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                        .zIndex(1f)
                        // 双层阴影：环境阴影（品牌色）+ 贴地接触阴影
                        .consistentShadow(
                            elevation = 32.dp,
                            shape = sheetShape,
                            ambientColor = iridescentColors.first().copy(alpha = 0.12f),
                            spotColor = iridescentColors.first().copy(alpha = 0.12f)
                        )
                        .consistentShadow(
                            elevation = 8.dp,
                            shape = sheetShape,
                            ambientColor = Color.Black.copy(alpha = 0.20f),
                            spotColor = Color.Black.copy(alpha = 0.20f)
                        )
                        // 整个弹窗只有「一层」毛玻璃：blur 24 + surface 62~70%，
                        // 内部分区一律不设独立背景（色差的根因）。
                        .liquidGlass(
                            backdrop = backdrop,
                            shape = sheetShape,
                            surfaceColor = MaterialTheme.colorScheme.surface.copy(
                                alpha = if (reduceEffects) 0.70f else 0.66f
                            ),
                            blurRadius = 24.dp,
                            refraction = false
                        )
                        .clip(sheetShape)
                        // 极细噪点（可选项，不改变分区底色）
                        .filmGrain(alpha = 0.035f)
                        // 1px 顶部渐变高光描边（左上白 70% → 右下白 10%）
                        .glassEdge(focused = false, shape = sheetShape, accent = Color.Transparent)
                        .navigationBarsPadding()
                ) {
                    // 拖拽手柄（弹窗内容最顶部）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 6.dp)
                            .pointerInput(Unit) {
                                val dismissThreshold = with(density) { 120.dp.toPx() }
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragOffsetY > dismissThreshold) {
                                            dismiss()
                                        } else {
                                            dragOffsetY = 0f
                                        }
                                    },
                                    onDragCancel = { dragOffsetY = 0f },
                                    onVerticalDrag = { _, dragAmount ->
                                        dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        )
                    }
                    // 标题栏：左「选择书源」20 加粗；右「管理书源」纯 primary 文字按钮（不加底、不加框）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "选择书源",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                        val manageInteraction = remember { MutableInteractionSource() }
                        val managePressed by manageInteraction.collectIsPressedAsState()
                        val manageScale by animateFloatAsState(
                            targetValue = if (managePressed) 0.96f else 1f,
                            animationSpec = tween(200, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)),
                            label = "managePress"
                        )
                        // 规范：标题右侧「管理书源」= **primary 10% 底的小胶囊，高 28**
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = manageScale
                                    scaleY = manageScale
                                }
                                .clip(RoundedCornerShape(50))
                                .height(28.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .clickable(
                                    interactionSource = manageInteraction,
                                    indication = null,
                                    onClick = onManageSources
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "管理书源",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            // A7：原写死 420.dp，小屏或横屏（可用高可能只有 ~360dp）
                            // 会溢出且无法滚动。改为"最多 420dp"，空间不足时收缩。
                            .heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            // 分区标题（12px 灰色 + 0.04em 字距）—— 层次只靠 间距 / 分组标题 / 细分割线
                            SourceSectionLabel("聚合搜索")
                        }
                        item {
                            // v1.0.1：聚合搜索按类别分区——聚合漫画 / 聚合小说 互斥选择
                            AggregateOptionItem(
                                title = "聚合漫画（全部）",
                                subtitle = "同时搜索所有已启用的漫画源",
                                selected = aggregateMode && aggregateKind == "comic",
                                onClick = { onSelectAggregate("comic") }
                            )
                        }
                        item {
                            AggregateOptionItem(
                                title = "聚合小说（全部）",
                                subtitle = "同时搜索所有已启用的小说源（网文/电子书）",
                                selected = aggregateMode && aggregateKind == "novel",
                                onClick = { onSelectAggregate("novel") }
                            )
                        }
                        // 第十一轮第 6 条：多语言搜索开关——搜索词自动扩展各语言标题变体
                        //（如"无职转生"→ 無職転生 / Mushoku Tensei / Jobless Reincarnation）
                        item {
                            SourceSheetDivider()
                        }
                        item {
                            ListItem(
                                // 规范：行高 56；**彻底清掉这一行的纯白底**（用户点名）
                                modifier = Modifier.height(56.dp),
                                colors = ListItemDefaults.colors(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                                ),
                                headlineContent = {
                                    Text(
                                        text = "多语言搜索",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        if (multiLanguageSearch) "开启：自动用各语言译名扩展搜索"
                                        else "关闭：仅使用输入的原始关键词"
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = null,
                                        tint = if (multiLanguageSearch) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    com.example.ui.components.AppSwitch(
                                        checked = multiLanguageSearch,
                                        onCheckedChange = onToggleMultiLanguageSearch
                                    )
                                }
                                // 无独立底色：分区一律透明，唯一允许填充的是选中态（primary 12%）
                            )
                        }
                        item {
                            SourceSheetDivider()
                        }
                        // v1.0.1：书源按类型分区展示——漫画源 / 小说源
                        if (comicSources.isNotEmpty()) {
                            item { SourceSectionLabel("漫画源") }
                            columnItems(comicSources, key = { it.id }) { source ->
                                SourceOptionItem(
                                    source = source,
                                    selected = !aggregateMode && currentSource?.id == source.id,
                                    onClick = { onSelectSource(source.id) }
                                )
                            }
                        }
                        if (novelSources.isNotEmpty()) {
                            item { SourceSectionLabel("小说源") }
                            columnItems(novelSources, key = { it.id }) { source ->
                                SourceOptionItem(
                                    source = source,
                                    selected = !aggregateMode && currentSource?.id == source.id,
                                    onClick = { onSelectSource(source.id) }
                                )
                            }
                        }
                        // 末尾不再单独画一条线（最后一行与底部间距已足够区分层次）
                    }
                }
            }
        }
    }
}

/**
 * 弹窗内统一细分割线：0.5px、`onSurface` 8%、左右各缩进 20。
 * 全弹窗共用同一条实现，避免各分区线宽/颜色/缩进不一致造成的"色差"。
 */
@Composable
private fun SourceSheetDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp)
    )
}

/** 书源分区小标题（聚合搜索 / 漫画源 / 小说源）：12px 灰色 + 0.04em 字距。 */
@Composable
private fun SourceSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.48.sp,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** 聚合搜索选项行（聚合漫画/聚合小说）：按下缩放 + 选中弹入对勾，与弹层整体 Liquid Glass 风格一致。 */
@Composable
private fun AggregateOptionItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "press"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "check"
    )
    ListItem(
        headlineContent = {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            )
        },
        supportingContent = { Text(subtitle, fontSize = 12.sp) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "当前选择",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        }
                )
            }
        },
        // 弹窗内唯一允许的填充：选中态 primary 12%；未选中必须完全透明（否则出现色差）
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                Color.Transparent
            }
        ),
        modifier = Modifier
            .animateContentSize(
                animationSpec = tween(220, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f))
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    )
}

/** 单个书源选项行。 */
@Composable
private fun SourceOptionItem(
    source: BookSource,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "press"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "check"
    )
    ListItem(
        headlineContent = {
            Text(
                text = source.name,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            )
        },
        supportingContent = {
            // ID 类信息：11sp 等宽 + 灰色
            Text(
                text = source.id,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            SourceAvatar(
                sourceId = source.id,
                sourceName = source.name,
                size = 32.dp
            )
        },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        }
                )
            }
        },
        // 弹窗内唯一允许的填充：选中态 primary **8%**；未选中必须完全透明（否则出现色差）
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                Color.Transparent
            }
        ),
        modifier = modifier
            // 规范：行高 56；选中底**左右各留 12、圆角、不通栏**
            .height(56.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    )
}

@Composable
private fun ComicDownloadGlassCard(
    tasks: List<ComicDownloadTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "漫画下载",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (tasks.isEmpty()) {
                Text(
                    text = "暂无漫画下载任务",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tasks.take(4).forEach { task ->
                    ComicDownloadTaskRow(
                        task = task,
                        onPause = onPause,
                        onResume = onResume,
                        onRetry = onRetry,
                        onCancel = onCancel
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (tasks.size > 4) {
                    Text(
                        text = "还有 ${tasks.size - 4} 个任务…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicDownloadTaskRow(
    task: ComicDownloadTask,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = task.chapter.title.ifBlank { "漫画章节" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            when (task.status) {
                ComicDownloadStatus.DOWNLOADING -> {
                    Text(
                        text = "${(task.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MintPrimary
                    )
                }
                ComicDownloadStatus.PAUSED -> {
                    Text("已暂停", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ComicDownloadStatus.FAILED -> {
                    Text("失败", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                ComicDownloadStatus.SUCCESS -> {
                    Text(
                        "已完成",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            // 同一槽位的播放/暂停形态按钮：状态切换时形变动画不重置
            if (task.status == ComicDownloadStatus.DOWNLOADING || task.status == ComicDownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.width(8.dp))
                PlayPauseMorphButton(
                    isPlaying = task.status == ComicDownloadStatus.DOWNLOADING,
                    onClick = if (task.status == ComicDownloadStatus.DOWNLOADING) {
                        { onPause(task.chapterId) }
                    } else {
                        { onResume(task.chapterId) }
                    },
                    sizeDp = 36
                )
            }
            if (task.status == ComicDownloadStatus.FAILED) {
                Spacer(modifier = Modifier.width(8.dp))
                AppIconButton(onClick = { onRetry(task.chapterId) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重新下载",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            AppIconButton(onClick = { onCancel(task.chapterId) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消任务",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (task.status == ComicDownloadStatus.DOWNLOADING) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MintPrimary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (task.status == ComicDownloadStatus.FAILED && !task.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = task.error,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LibraryWelcomeScreen(
    onImportLocal: () -> Unit,
    onOnlineSearch: () -> Unit,
    onSourceManage: () -> Unit,
    hasOnlineSource: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = com.example.ui.mascot.MascotSpriteSheet.idleDrawable),
            contentDescription = "Roxy",
            modifier = Modifier.size(120.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "欢迎使用书库",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "在这里，您可以轻松导入本地电子书，或开启云端在线书源自由搜索。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Option 1: Local Reading
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onImportLocal() }
                .testTag("welcome_import_local_card"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "本地图书",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地阅读", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("直接导入手机中的 EPUB / TXT 电子书开始阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Option 2: Online Search
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOnlineSearch() }
                .testTag("welcome_online_search_card"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "在线搜索",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("在线搜索", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        if (hasOnlineSource) "开启云端搜索，检索图书资源" else "连接 Z-Library 云端书源检索图书",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Option 3: Advanced Source Management
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSourceManage() }
                .testTag("welcome_source_manage_card"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "高级设置",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("高级设置", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("管理书源或导入自定义 JSON 配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun rememberCoverHeaders(
    book: SearchBook,
    sources: List<BookSource>
): Map<String, String> {
    val cover = book.cover
    var headers by remember(cover, book.sourceId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(cover, book.sourceId) {
        if (cover.isNullOrBlank()) return@LaunchedEffect
        val source = sources.firstOrNull { it.id == book.sourceId } as? ComicSource
        headers = source?.getCoverHeaders(cover) ?: emptyMap()
    }
    return headers
}

/**
 * 搜索结果卡片上的 ♡ —— 不用点进详情就能把书收进「我喜欢的」。
 *
 * 卡片本身可点（进详情），所以这里只吞掉自己的点击，不参与卡片的手势。
 */
@Composable
private fun SearchResultFavoriteButton(
    favorite: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "searchFavPress",
    )
    val tint = if (favorite) MintPrimary else Color.White
    Box(
        modifier = modifier
            .size(30.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onToggle(!favorite) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "取消喜欢" else "加入我喜欢的",
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
fun LibraryBookCard(
    book: SearchBook,
    downloadState: DownloadState,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String> = emptyMap(),
    comicMode: Boolean = false,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onToggleFavorite: (Boolean) -> Unit = {},
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Cover：无封面或加载失败时显示占位（书名首字 + 格式）
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Gray.copy(alpha = 0.2f))
            ) {
                if (book.cover.isNullOrBlank()) {
                    BookCoverPlaceholder(book)
                } else {
                    val coverModel: Any = if (coverHeaders.isEmpty()) {
                        book.cover
                    } else {
                        ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(book.cover)
                            .apply { coverHeaders.forEach { (k, v) -> addHeader(k, v) } }
                            .build()
                    }
                    SubcomposeAsyncImage(
                        model = coverModel,
                        imageLoader = imageLoader,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                            else -> BookCoverPlaceholder(book)
                        }
                    }
                }
                // ♡ 收藏入口：不进详情也能加入「我喜欢的」
                SearchResultFavoriteButton(
                    favorite = favorite,
                    onToggle = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.author,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (!book.description.isNullOrBlank()) {
                    Text(
                        text = book.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val extras = buildList {
                        book.comicId?.takeIf { it.isNotBlank() }?.let { add("#$it") }
                        book.language?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    Text(
                        text = "格式：${book.displayFormat()}${if (extras.isNotEmpty()) " · " + extras.joinToString(" · ") else ""}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MintPrimary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                AnimatedContent(
                    targetState = downloadState,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)))
                            .togetherWith(fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)))
                    },
                    label = "download_state_transition"
                ) { state ->
                    when (state) {
                        is DownloadState.Idle -> {
                            AppActionButton(
                                text = if (comicMode) "阅读" else "下载",
                                onClick = onStartDownload,
                                variant = AppButtonVariant.Primary,
                                buttonSize = AppButtonSize.Small,
                                icon = if (comicMode) Icons.Filled.MenuBook else Icons.Default.Download,
                                modifier = Modifier.testTag("download_button_idle")
                            )
                        }
                        is DownloadState.Error -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppActionButton(
                                    text = "重试",
                                    onClick = onStartDownload,
                                    variant = AppButtonVariant.Primary,
                                    buttonSize = AppButtonSize.Small,
                                    icon = Icons.Default.Refresh,
                                    modifier = Modifier.testTag("download_button_retry")
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = "错误",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "下载失败: ${state.message}",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        is DownloadState.Pending -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在准备...", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(6.dp))
                                AppIconButton(onClick = onCancelDownload, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        is DownloadState.Downloading, is DownloadState.Paused -> {
                            val isPaused = state is DownloadState.Paused
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isPaused) {
                                            Icon(Icons.Default.Pause, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                                        } else {
                                            CircularProgressIndicator(
                                                progress = {
                                                    (state as? DownloadState.Downloading)?.progress ?: 0f
                                                },
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isPaused) "已暂停" else {
                                                val p = (state as? DownloadState.Downloading)?.progress ?: 0f
                                                "下载中: ${(p * 100).toInt()}%"
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPaused) Color.Gray else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // 同一槽位：暂停↔继续时形变动画不重置
                                        PlayPauseMorphButton(
                                            isPlaying = !isPaused,
                                            onClick = if (isPaused) onResumeDownload else onPauseDownload,
                                            sizeDp = 30
                                        )
                                        AppIconButton(onClick = onCancelDownload, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                if (!isPaused) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            (state as? DownloadState.Downloading)?.progress ?: 0f
                                        },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.secondary,
                                        trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                        is DownloadState.Success -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "已完成",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "已存入书架",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCoverPlaceholder(book: SearchBook) {
    // 按书名哈希取柔和渐变背景色：同一本书颜色稳定，加载失败态也有区分度
    val gradientColors = remember(book.title) {
        val hue = ((book.title.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
        listOf(
            Color.hsv(hue, 0.32f, 0.92f),
            Color.hsv((hue + 42f) % 360f, 0.30f, 0.78f)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(gradientColors)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = book.title.trim().firstOrNull()?.uppercase() ?: "书",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book.displayFormat(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MintPrimary
            )
        }
    }
}

private fun SearchBook.displayFormat(): String {
    val raw = format.ifBlank {
        downloadUrl
            ?.substringBefore('?')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf {
                it.length in 2..5 && it.all(Char::isLetterOrDigit) && it != "html" && it != "php"
            }
            ?: ""
    }
    return raw.uppercase().ifBlank { "EPUB" }
}

/**
 * 瀑布流节奏调色板：卡片高度从第一帧起就固定，图片解码完成后不再改尺寸，
 * 避免 LazyVerticalStaggeredGrid 已排好的条目被后续高度变化拉扯（滚动卡死根因）。
 */
private val STAGGER_RATIO_PALETTE = listOf(0.62f, 0.70f, 0.78f, 0.90f, 3f / 4f)

/** 聚合搜索每源默认预览条数（任务一）：超出部分折叠到「展开全部」按钮之后。 */
private const val AGGREGATE_PREVIEW_COUNT = 6

/** 用 书源+书ID 稳定映射到一个瀑布流比例：同一本书任何时候算出来都一样。 */
private fun SearchBook.staggerRatio(): Float {
    val seed = "${sourceId}_$id"
    val hash = (seed.hashCode().toLong() and 0x7fffffffL)
    return STAGGER_RATIO_PALETTE[(hash % STAGGER_RATIO_PALETTE.size).toInt()]
}

@Composable
private fun StaggeredComicCard(
    book: SearchBook,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String>,
    sourceName: String,
    novel: Boolean = false,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onToggleFavorite: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    // 固定比例：不再依赖图片解码结果，卡片测量高度全程不变
    val coverRatio = book.staggerRatio()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "gridPress"
    )
    // 按下时阴影同步加深（联动反馈）
    val shadowAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.20f else 0.12f,
        animationSpec = tween(120),
        label = "gridShadow"
    )
    // 入场动画：淡入 + 轻微上移（只动透明度/位移，不影响测量尺寸）
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(260),
        label = "cardEnterAlpha"
    )
    val enterSlide by animateFloatAsState(
        targetValue = if (entered) 0f else 1f,
        animationSpec = tween(260),
        label = "cardEnterSlide"
    )
    val cardShape = RoundedCornerShape(14.dp)
    val formatBadge = remember(book, novel) {
        listOfNotNull(
            book.comicId?.takeIf { it.isNotBlank() }?.let { "#$it" },
            book.format?.takeIf { it.isNotBlank() && !it.equals("epub", true) }?.uppercase()
        ).firstOrNull() ?: if (novel) "小说" else "漫画"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = enterAlpha
                translationY = enterSlide * size.height / 8f
            }
            .consistentShadow(
                elevation = 8.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = shadowAlpha),
                spotColor = Color.Black.copy(alpha = shadowAlpha)
            )
            .clip(cardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverRatio)
                .clip(cardShape)
                .background(Color.Gray.copy(alpha = 0.15f))
        ) {
            if (book.cover.isNullOrBlank()) {
                BookCoverPlaceholder(book)
            } else {
                val coverModel: Any = if (coverHeaders.isEmpty()) {
                    book.cover
                } else {
                    ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(book.cover)
                        .apply { coverHeaders.forEach { (k, v) -> addHeader(k, v) } }
                        .build()
                }
                SubcomposeAsyncImage(
                    model = coverModel,
                    imageLoader = imageLoader,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (painter.state) {
                        // 图片加载完成只负责渲染（ContentScale.Crop 自动裁切填满），
                        // 绝不修改卡片尺寸，避免已排版条目高度跳变
                        is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        else -> BookCoverPlaceholder(book)
                    }
                }
            }
            // 底部渐变遮罩（黑→透明，占封面 40%），标题永远可读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.40f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
            )
            // 标题 + 作者压在遮罩上
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = book.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.consistentShadow(2.dp, RoundedCornerShape(4.dp))
                )
                if (book.author.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // ♡ 收藏入口（左上，与右上的格式角标错开）：不进详情也能加入「我喜欢的」
            SearchResultFavoriteButton(
                favorite = favorite,
                onToggle = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
            // 格式角标（右上）
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.40f)
            ) {
                Text(
                    text = formatBadge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // 来源角标（左上，16dp 圆形头像）
            SourceAvatar(
                sourceId = book.sourceId,
                sourceName = sourceName,
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun AggregateSourceHeader(
    name: String,
    loading: Boolean,
    resultCount: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            // 24dp 圆形源头像（哈希取色 + 首字母）
            SourceAvatar(
                sourceId = "agg_$name",
                sourceName = name,
                size = 24.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (loading) {
                ChasingDots(size = 18.dp, color = MintPrimary)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!loading && resultCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MintPrimary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "$resultCount",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MintPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            if (onClick != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Filled.UnfoldMore,
                    contentDescription = "打开源组跳转面板",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    }
}

/**
 * 「展开全部 N 条」按钮（任务一）：聚合结果每源默认只展示前 6 条，
 * 点击后展开该书源全部搜索结果。整行胶囊样式与 [AggregateSourceHeader] 同语言。
 */
@Composable
private fun AggregateExpandButton(
    hiddenCount: Int,
    onExpand: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "aggExpandPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MintPrimary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .background(MintPrimary.copy(alpha = 0.08f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onExpand
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "展开全部 $hiddenCount 条",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MintPrimary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "展开该书源全部结果",
            tint = MintPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 每个源组在 StaggeredGrid 占用的 item 数：组头 + (shimmer×4 | 书卡[+展开按钮] | 错误卡)，
 * 与网格 emit 逻辑一一对应。折叠态只占前 [AGGREGATE_PREVIEW_COUNT] 张书卡 + 1 个展开按钮。
 */
internal fun aggregateGroupItemCount(
    group: LibraryUiState.AggregateGroup,
    expanded: Boolean = false
): Int = when {
    group.books.isNotEmpty() -> {
        val cards = if (expanded) group.books.size
            else minOf(group.books.size, AGGREGATE_PREVIEW_COUNT)
        if (!expanded && group.books.size > AGGREGATE_PREVIEW_COUNT) cards + 1 else cards
    }
    group.loading -> 4
    else -> 1
}

/** 目标源组组头的 item index（跳转定位用）。 */
private fun groupHeaderIndex(
    groups: List<LibraryUiState.AggregateGroup>,
    target: Int,
    expandedGroups: Map<String, Boolean> = emptyMap()
): Int {
    var index = 0
    for (i in 0 until target.coerceIn(0, groups.lastIndex)) {
        index += 1 + aggregateGroupItemCount(groups[i], expandedGroups[groups[i].sourceId] == true)
    }
    return index
}

/**
 * 「源速跳」面板：列出全部源组（头像 + 名称 + 数量/状态 + 当前位置标记）与「回到搜索顶部」。
 * 由组头胶囊点击唤起，选中后弹性滚动直达对应组头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AggregateJumpSheet(
    groups: List<LibraryUiState.AggregateGroup>,
    activeGroupIdx: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight(0.6f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = DesignTokens.SpacePage),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "跳转到源组",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                item(key = "jump_top") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onJump(-1) },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "回到搜索顶部",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                itemsIndexed(groups, key = { _, g -> "jump_${g.sourceId}" }) { idx, group ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onJump(idx) },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SourceAvatar(
                                sourceId = "agg_${group.sourceName}",
                                sourceName = group.sourceName,
                                size = 28.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                group.sourceName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                group.loading -> ChasingDots(size = 14.dp, color = MintPrimary)
                                group.books.isNotEmpty() -> Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MintPrimary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        "${group.books.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MintPrimary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                else -> Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = "无结果",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (idx == activeGroupIdx) {
                                Icon(Icons.Filled.CheckCircle, "当前位置", tint = MintPrimary, modifier = Modifier.size(18.dp))
                            } else {
                                Spacer(Modifier.width(18.dp))
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun AggregateSourceError(
    error: String?,
    modifier: Modifier = Modifier
) {
    val timedOut = error?.contains("timeout", ignoreCase = true) == true ||
        error?.contains("timed out", ignoreCase = true) == true ||
        error?.contains("超时") == true
    // 源正常返回空列表（error=null）才是真正的「无结果」；任何真实错误都翻译成可行动的提示
    val message = when {
        error.isNullOrBlank() -> "无结果"
        timedOut -> "链接超时"
        else -> com.example.source.js.friendlyJsSourceError(error)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (timedOut) Icons.Default.Info else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
