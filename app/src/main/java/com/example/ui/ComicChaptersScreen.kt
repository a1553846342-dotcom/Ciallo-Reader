package com.example.ui

import android.widget.Toast
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.example.data.favorite.ChapterReadState
import com.example.data.favorite.ComicProgressEntity
import com.example.data.favorite.ComicReadingLogic
import com.example.data.favorite.OrderedChapter
import com.example.god.GodMomentEntity
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.source.isKnownComicAuthor
import com.example.ui.components.AppIconButton
import com.example.ui.components.ChasingDots
import com.example.ui.components.PlayPauseMorphButton
import com.example.ui.components.AppLiquidButton
import com.example.ui.favorite.BookmarkRibbon
import com.example.ui.favorite.FavoriteHeartIcon
import com.example.ui.favorite.SourceUnavailableBanner
import com.example.ui.favorite.toVisual
import com.example.ui.theme.MintPrimary
import me.trishiraj.shadowglow.consistentShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.example.ui.components.AppToast
import androidx.compose.ui.platform.LocalView
import com.example.ui.feedback.HapticsGate
import kotlin.math.abs

/** 神回金色（仅神回卡片使用）。 */
private val GodGold = Color(0xFFE8C56A)
private val GodGoldStroke = Color(0xFFE8C56A).copy(alpha = 0.55f)
/** 神回暗层基色 rgba(14,18,30,…) */
private val GodScrimBase = Color(0xFF0E121E)

/** 列表头部的固定 item 数：hero + 信息行 + 工具行（滚动下标补偿用）。 */
private const val LIST_HEADER_ITEMS = 3

/**
 * 漫画主页面（从书库 / 我喜欢的点进）—— 2026-09-29 按新规格重构：
 * 紧凑顶栏（返回 + 标题）→ 信息行 → 工具行（正序/跳到最新章/多选）→ 章节列表
 * （神回卡在最上，按收藏顺序）→ 底部固定栏（♥ + 继续阅读）。
 *
 * 保留的全部既有接线：缓存秒开与错误重试/换源、下载管理与悬浮窗、长按标记菜单、
 * 滑动书签、神回编辑、排行榜跳转定位、文本小说模式、自动定位续读。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComicChaptersScreen(
    book: SearchBook?,
    chapters: List<ComicChapter>,
    loading: Boolean,
    error: String?,
    downloadingChapters: Set<String>,
    downloadProgress: Map<String, Float>,
    pausedChapters: Set<String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onChapterClick: (ComicChapter) -> Unit,
    onDownloadChapter: (ComicChapter) -> Unit,
    onDownloadAll: () -> Unit,
    onPauseDownload: (ComicChapter) -> Unit,
    onResumeDownload: (ComicChapter) -> Unit,
    onCancelDownload: (ComicChapter) -> Unit,
    /** 文本小说模式：隐藏图片下载/多选 UI，章节点击直接阅读正文 */
    textMode: Boolean = false,
    onDownloadNovel: (() -> Unit)? = null,
    favorite: Boolean = false,
    /** 无来源的书不能被喜欢 → 置灰并说明原因 */
    favoriteEnabled: Boolean = true,
    onToggleFavorite: (Boolean) -> Unit = {},
    /** 长按 ♡ = 直接弹出分类选择 Sheet */
    onFavoriteLongPress: () -> Unit = {},
    onFavoriteDisabledClick: () -> Unit = {},
    /** 章节级状态（含 pageIndex / pageCount）：chapterId → 实体 */
    chapterStates: Map<String, com.example.data.favorite.ChapterReadEntity> = emptyMap(),
    /** 已下载到本地书架的章节 id */
    downloadedChapterIds: Set<String> = emptySet(),
    progress: ComicProgressEntity? = null,
    /** 继续阅读：精确回到上次章节与页码 */
    onReadChapterAt: (ComicChapter, Int) -> Unit = { c, _ -> onChapterClick(c) },
    onMarkChapterRead: (ComicChapter, Int, Boolean) -> Unit = { _, _, _ -> },
    onMarkReadUpTo: (ComicChapter, Int) -> Unit = { _, _ -> },
    onChangeSource: () -> Unit = {},
    onMarkSeen: () -> Unit = {},
    /** 各话书签标注（持久化，重进详情页仍显示） */
    bookmarkedChapterIds: Set<String> = emptySet(),
    onSetChapterBookmark: ((chapterId: String, chapterIndex: Int, bookmarked: Boolean) -> Unit)? = null,
    /* ── 神回：chapterId → 神回实体（被标记的话渲染成神回态卡片） ── */
    godMoments: Map<String, GodMomentEntity> = emptyMap(),
    /** 神回 map 是否已从数据库加载完成（Room flow 首帧前是空 map，加载前不武装书签手势） */
    godMomentsReady: Boolean = true,
    /** 从排行榜跳转过来要定位 + 高亮的章节 id */
    godFocusChapterId: String? = null,
    /** 「下载 N 话 约 X MB」的每话体积估算（MB） */
    estimatedChapterSizeMb: Double = 2.0,
    sourceName: String? = null,
    onSearchText: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var textActions by remember(book?.sourceId, book?.id) { mutableStateOf<Pair<String, String>?>(null) }
    textActions?.let { (label, text) ->
        ComicTextActions(label, text, onDismiss = { textActions = null }, onSearch = onSearchText)
    }
    val ordered = remember(chapters) { ComicReadingLogic.ordered(chapters) }
    // 纯逻辑层只认状态枚举，这里把实体降维成 state
    val stateOnly = remember(chapterStates) { chapterStates.mapValues { it.value.state } }
    val target = remember(chapters, chapterStates, progress) {
        ComicReadingLogic.resolveContinue(chapters, stateOnly, progress)
    }
    val newIds = remember(chapters, progress) { ComicReadingLogic.newChapterIds(chapters, progress) }

    var sortDesc by rememberSaveable { mutableStateOf(false) }
    val displayChapters = remember(ordered, sortDesc) {
        if (sortDesc) ordered.asReversed() else ordered
    }

    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedChapterIds = remember { mutableStateListOf<String>() }

    /* ── 神回：长按神回卡片 → 打开编辑窗口（本页自持宿主，无需回传 Activity） ── */
    val godBinding = com.example.god.rememberGodEditBinding()
    val reduceMotion = com.example.god.rememberReduceMotion()

    /* ── 列表行模型：神回卡（按收藏顺序）排最上，普通章节跟随（神回话不重复出卡） ── */
    val godList = remember(godMoments) { godMoments.values.sortedBy { it.createdAt } }
    val godChapterIds = remember(godList) { godList.map { it.chapterId }.toSet() }
    val normalChapters = remember(displayChapters, godChapterIds) {
        displayChapters.filter { it.chapter.id !in godChapterIds }
    }

    /** 章节 id → 列表行下标（神回卡在前） */
    fun rowIndexOf(chapterId: String): Int {
        val godIdx = godList.indexOfFirst { it.chapterId == chapterId }
        if (godIdx >= 0) return godIdx
        val normalIdx = normalChapters.indexOfFirst { it.chapter.id == chapterId }
        if (normalIdx >= 0) return godList.size + normalIdx
        return -1
    }

    // 从排行榜跳转：滚动定位到该话 + 金色高亮脉冲
    var godFocus by remember { mutableStateOf(godFocusChapterId) }
    LaunchedEffect(godFocus, godList.size, normalChapters.size) {
        val id = godFocus ?: return@LaunchedEffect
        val idx = rowIndexOf(id) + LIST_HEADER_ITEMS
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            highlightId = id
            delay(1600)
            highlightId = null
            godFocus = null
        }
    }

    // 进入页面：自动滚动到"阅读中"或"下一话"，并轻微高亮闪一下
    LaunchedEffect(ordered.size, target) {
        if (ordered.isEmpty()) return@LaunchedEffect
        val focusIndex = when (target) {
            is ComicReadingLogic.ContinueTarget.Resume -> target.chapterIndex
            is ComicReadingLogic.ContinueTarget.Next -> target.chapterIndex
            else -> ordered.indexOfFirst { chapterStates[it.chapter.id]?.state == ChapterReadState.READING }
                .takeIf { it >= 0 } ?: 0
        }
        val focusId = ordered.getOrNull(focusIndex)?.chapter?.id ?: return@LaunchedEffect
        val idx = rowIndexOf(focusId) + LIST_HEADER_ITEMS
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            highlightId = focusId
            delay(1000)
            highlightId = null
        }
    }

    // 记录"已见"快照：之后新增的章节才会显示「新」
    LaunchedEffect(book?.id, ordered.size) {
        if (ordered.isNotEmpty()) onMarkSeen()
    }

    val scope = rememberCoroutineScope()
    val readCount = ordered.count { chapterStates[it.chapter.id]?.state == ChapterReadState.READ }

    // 继续阅读落点（底部按钮文案与行为同源）
    fun continueTargetChapter(): Pair<ComicChapter, Int>? = when (target) {
        ComicReadingLogic.ContinueTarget.Start ->
            ordered.firstOrNull()?.chapter?.let { it to 0 }
        is ComicReadingLogic.ContinueTarget.Resume ->
            ordered.getOrNull(target.chapterIndex)?.chapter?.let { it to target.pageIndex }
        is ComicReadingLogic.ContinueTarget.Next ->
            ordered.getOrNull(target.chapterIndex)?.chapter?.let { it to 0 }
        ComicReadingLogic.ContinueTarget.UpToDate ->
            ordered.firstOrNull()?.chapter?.let { it to 0 }
    }

    fun exitSelection() {
        selectedChapterIds.clear()
        selectionMode = false
    }

    // ⚠️ 多选模式下按返回：先退出多选回到详情页，而不是把整个详情页关掉
    //（用户实测：长按进多选后按返回直接关了页面）。
    androidx.activity.compose.BackHandler(enabled = selectionMode && !textMode) {
        exitSelection()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode && !textMode) {
                // ── 多选顶栏：✕ | 已选 N 话 | 全选 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::exitSelection, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "退出多选",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = "已选 ${selectedChapterIds.size} 话",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        selectedChapterIds.clear()
                        selectedChapterIds.addAll(ordered.map { it.chapter.id })
                    }) {
                        Text(
                            "全选",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MintPrimary,
                        )
                    }
                }
            } else {
                // ── 顶栏：返回 + 标题（17/500 单行省略） ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = book?.title ?: "漫画章节",
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                            book?.title?.takeIf { it.isNotBlank() }?.let { textActions = "漫画名" to it }
                        }),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        },
        bottomBar = {
            when {
                // ── 多选底部：通栏「下载 N 话」 ──
                selectionMode && !textMode -> {
                    val n = selectedChapterIds.size
                    Button(
                        onClick = {
                            if (n == 0) {
                                AppToast.makeText(context, "先勾选要下载的章节", Toast.LENGTH_SHORT).show()
                            } else {
                                chapters.filter { it.id in selectedChapterIds }.forEach(onDownloadChapter)
                                exitSelection()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MintPrimary),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                if (n == 0) {
                                    append("选择要下载的章节")
                                } else {
                                    append("下载 $n 话")
                                    withStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            fontWeight = FontWeight.Normal,
                                            color = Color.White.copy(alpha = 0.85f),
                                        )
                                    ) {
                                        val mb = n * estimatedChapterSizeMb
                                        append("  约 ${String.format(java.util.Locale.US, "%.1f", mb)} MB")
                                    }
                                }
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // ── 普通底部：♥ + 继续阅读 ──
                else -> {
                    val cont = continueTargetChapter()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 48.dp)
                                .longPressDetector(onLongPress = onFavoriteLongPress),
                            contentAlignment = Alignment.Center,
                        ) {
                            FavoriteHeartIcon(
                                favorite = favorite,
                                enabled = favoriteEnabled,
                                onToggle = onToggleFavorite,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val pair = cont
                                if (pair == null) {
                                    AppToast.makeText(context, "暂无可阅读章节", Toast.LENGTH_SHORT).show()
                                } else {
                                    onReadChapterAt(pair.first, pair.second)
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MintPrimary),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    append("继续阅读 ")
                                    withStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            fontWeight = FontWeight.Normal,
                                            color = Color.White.copy(alpha = 0.85f),
                                        )
                                    ) {
                                        if (cont != null) {
                                            val num = ComicReadingLogic.chapterNumber(cont.first.title)
                                                ?: (ordered.indexOfFirst { it.chapter.id == cont.first.id } + 1)
                                            append("第 $num 话 · P${cont.second + 1}")
                                        } else {
                                            append("第 1 话")
                                        }
                                    }
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && chapters.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ChasingDots(size = 52.dp, color = MintPrimary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("正在加载章节…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                error != null && chapters.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            AppLiquidButton(text = "重试", onClick = onRetry)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onChangeSource) { Text("换源") }
                        }
                    }
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 缓存信息仍在 → 顶部提示条（保留已读状态，可重试/换源）
                        if (error != null) {
                            SourceUnavailableBanner(
                                message = "来源暂不可用（已保留缓存信息与阅读进度）",
                                onRetry = onRetry,
                                onChangeSource = onChangeSource,
                            )
                        }

                        // ── 章节列表：头部/信息行/工具行随内容滚动（旧版页面结构） ──
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // ── 头部：封面模糊背景 + 封面卡 + 标题 + 徽章 + 作者 + 简介（旧版样式） ──
                            item(key = "hero") { ComicHeroHeader(book = book, textMode = textMode,
                                onTextLongPress = { label, text -> textActions = label to text }) }

                            // ── 信息行（13，secondary；「已读 N 话」accent） ──
                            item(key = "info") {
                            Text(
                            text = buildAnnotatedString {
                                append("${sourceName ?: book?.sourceId.orEmpty()} · 当前源共 ${chapters.size} 话 · ")
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        color = MintPrimary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                ) { append("已读 $readCount 话") }
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            }

                            // ── 工具行（高 32）：正序 | 跳到最新章 | 多选 ──
                            item(key = "toolbar") {
                            Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { sortDesc = !sortDesc }
                                    .padding(horizontal = 4.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (sortDesc) "倒序" else "正序",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MintPrimary.copy(alpha = 0.12f))
                                    .clickable {
                                        val idx = if (sortDesc) 0 else (normalChapters.size - 1).coerceAtLeast(0)
                                        scope.launch {
                                            listState.animateScrollToItem(godList.size + idx + LIST_HEADER_ITEMS)
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "跳到最新章",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MintPrimary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (textMode && onDownloadNovel != null) {
                                TextButton(onClick = onDownloadNovel) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("整本下载", fontSize = 13.sp)
                                }
                            }
                            if (!textMode) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectionMode = true }
                                        .padding(horizontal = 4.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Checklist,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "多选",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            }
                        }

                        // ── 多选快捷胶囊（高 30，圆角 16，0.5 边框） ──
                        if (selectionMode && !textMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                QuickPill("选未读") {
                                    selectedChapterIds.clear()
                                    selectedChapterIds.addAll(
                                        ordered.filter {
                                            chapterStates[it.chapter.id]?.state != ChapterReadState.READ
                                        }.map { it.chapter.id }
                                    )
                                }
                                QuickPill("选后 10 话") {
                                    selectedChapterIds.clear()
                                    selectedChapterIds.addAll(
                                        ordered.takeLast(10).map { it.chapter.id }
                                    )
                                }
                                QuickPill("反选") {
                                    val pool = ordered.map { it.chapter.id }
                                    val inverted = pool.filter { it !in selectedChapterIds }
                                    selectedChapterIds.clear()
                                    selectedChapterIds.addAll(inverted)
                                }
                            }
                        }

                            } // toolbar item 收尾

                            items(godList, key = { "god_${it.chapterId}" }) { g ->
                                val chapter = chapters.firstOrNull { it.id == g.chapterId }
                                GodCardSpec(
                                    god = g,
                                    chapterTitle = chapter?.title
                                        ?: g.chapterTitle.ifBlank { "第 ${g.chapterNumber} 话" },
                                    highlighted = highlightId == g.chapterId,
                                    selecting = selectionMode && !textMode,
                                    checked = g.chapterId in selectedChapterIds,
                                    onOpen = {
                                        if (selectionMode && !textMode) {
                                            // 多选：整卡任意位置都可勾选/取消（不只右边圆圈）
                                            if (g.chapterId in selectedChapterIds) {
                                                selectedChapterIds.remove(g.chapterId)
                                            } else {
                                                selectedChapterIds.add(g.chapterId)
                                            }
                                        } else {
                                            val oc = ordered.firstOrNull { it.chapter.id == g.chapterId }
                                            if (oc == null) {
                                                AppToast.makeText(context, "该话暂不可读", Toast.LENGTH_SHORT).show()
                                            } else {
                                                onReadChapterAt(oc.chapter, 0)
                                            }
                                        }
                                    },
                                    onDownload = {
                                        val chapter2 = chapters.firstOrNull { it.id == g.chapterId }
                                        if (chapter2 == null || chapter2.external) {
                                            AppToast.makeText(context, "站外链接章节暂不支持下载", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onDownloadChapter(chapter2)
                                        }
                                    },
                                    onToggleCheck = {
                                        if (g.chapterId in selectedChapterIds) {
                                            selectedChapterIds.remove(g.chapterId)
                                        } else {
                                            selectedChapterIds.add(g.chapterId)
                                        }
                                    },
                                    onLongPress = { godBinding.openEdit(g) },
                                )
                            }
                            items(normalChapters, key = { it.chapter.id }) { entry ->
                                val chapter = entry.chapter
                                val st = chapterStates[chapter.id]
                                val visual = st?.state?.toVisual() ?: com.example.ui.favorite.ChapterVisual.UNREAD
                                val pageIndex = st?.pageIndex
                                    ?: if (progress?.lastChapterId == chapter.id) progress.lastPageIndex else 0
                                val pageCount = st?.pageCount?.takeIf { it > 0 }
                                    ?: if (progress?.lastChapterId == chapter.id) progress.lastPageCount else 0
                                ChapterCardSpec(
                                    number = ComicReadingLogic.chapterNumber(chapter.title)
                                        ?: (entry.order + 1),
                                    title = chapter.title,
                                    visual = visual,
                                    lastPage = pageIndex,
                                    pageCount = pageCount,
                                    isNew = chapter.id in newIds,
                                    downloaded = chapter.id in downloadedChapterIds,
                                    downloading = chapter.id in downloadingChapters,
                                    downloadProgress = downloadProgress[chapter.id] ?: 0f,
                                    external = chapter.external,
                                    highlighted = highlightId == chapter.id,
                                    selecting = selectionMode && !textMode,
                                    checked = chapter.id in selectedChapterIds,
                                    textMode = textMode,
                                    bookmarked = chapter.id in bookmarkedChapterIds,
                                    // 神回章节不可加书签：神回 map 加载完成前不武装手势（写入侧另有兜底）
                                    bookmarkEnabled = if (godMomentsReady) {
                                        godMoments[chapter.id] == null && onSetChapterBookmark != null
                                    } else {
                                        false
                                    },
                                    onClick = {
                                        if (selectionMode && !textMode) {
                                            if (chapter.id in selectedChapterIds) {
                                                selectedChapterIds.remove(chapter.id)
                                            } else {
                                                selectedChapterIds.add(chapter.id)
                                            }
                                        } else if (chapter.external) {
                                            AppToast.makeText(context, "站外链接章节暂不支持在线阅读", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onReadChapterAt(chapter, if (visual == com.example.ui.favorite.ChapterVisual.UNREAD) 0 else pageIndex)
                                        }
                                    },
                                    onDownload = {
                                        if (chapter.external) {
                                            AppToast.makeText(context, "站外链接章节暂不支持下载", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onDownloadChapter(chapter)
                                        }
                                    },
                                    onToggleBookmark = {
                                        onSetChapterBookmark?.invoke(
                                            chapter.id,
                                            entry.order,
                                            chapter.id !in bookmarkedChapterIds,
                                        )
                                    },
                                    onLongPress = {
                                        // 长按普通章节 = 进入多选并自动选中该话（顶栏随之切换）
                                        selectionMode = true
                                        if (entry.chapter.id !in selectedChapterIds) {
                                            selectedChapterIds.add(entry.chapter.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 下载进度悬浮窗
            val activeDownloadChapter = chapters.firstOrNull { it.id in downloadingChapters }
            if (activeDownloadChapter != null) {
                DownloadProgressOverlay(
                    chapter = activeDownloadChapter,
                    progress = downloadProgress[activeDownloadChapter.id] ?: 0f,
                    paused = pausedChapters.contains(activeDownloadChapter.id),
                    onPause = { onPauseDownload(activeDownloadChapter) },
                    onResume = { onResumeDownload(activeDownloadChapter) },
                    onCancel = { onCancelDownload(activeDownloadChapter) }
                )
            }

            // ⚠️ 神回编辑窗必须在这个**带底栏内边距**的 Box 里：放外面的话
            // 窗体 92% 高度会铺到 Scaffold 底栏后面 —— 取消/保存按钮被
            // 「继续阅读」底栏盖住（用户实测）。
            com.example.god.GodMomentEditHost(
                binding = godBinding,
                onSaved = {
                    AppToast.makeText(context, "已更新神回 ✦", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/* ══════════════ 快捷胶囊 ══════════════ */

@Composable
private fun QuickPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

/* ══════════════ 普通章节卡片（规格三） ══════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterCardSpec(
    number: Int,
    title: String,
    visual: com.example.ui.favorite.ChapterVisual,
    lastPage: Int,
    pageCount: Int,
    isNew: Boolean,
    downloaded: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    external: Boolean,
    highlighted: Boolean,
    selecting: Boolean,
    checked: Boolean,
    textMode: Boolean,
    bookmarked: Boolean,
    bookmarkEnabled: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onLongPress: () -> Unit,
) {
    val isCurrent = visual == com.example.ui.favorite.ChapterVisual.READING
    val isRead = visual == com.example.ui.favorite.ChapterVisual.READ
    val shape = RoundedCornerShape(14.dp)
    val onSurface = MaterialTheme.colorScheme.onBackground
    val textPrimary = onSurface
    val textSecondary = onSurface.copy(alpha = if (isCurrent) 0.75f else 0.55f)
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surface

    val emphasized = isCurrent && !selecting
    val isSelected = selecting && checked
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(320),
        label = "chapter_highlight",
    )

    // 滑动书签：跟手平移 + 丝带预览（与 ChapterStatusRow 同一套判定）
    val dragAnim = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val currentToggle by rememberUpdatedState(onToggleBookmark)
    var dragActive by remember { mutableStateOf(false) }
    var dragPreviewBookmark by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragActive) 1f else 0f)
            .graphicsLayer { translationX = dragAnim.value }
            .clip(shape)
            .background(cardBg)
            .then(
                if (emphasized || isSelected) {
                    Modifier.background(MintPrimary.copy(alpha = 0.08f))
                } else {
                    Modifier
                }
            )
            .then(
                if (highlightAlpha > 0.01f) {
                    Modifier.background(MintPrimary.copy(alpha = 0.10f * highlightAlpha))
                } else {
                    Modifier
                }
            )
            .then(
                if (emphasized || isSelected || highlightAlpha > 0.01f) {
                    Modifier.border(1.dp, MintPrimary.copy(alpha = if (emphasized || isSelected) 1f else highlightAlpha), shape)
                } else {
                    Modifier.border(0.5.dp, SolidColor(onSurface.copy(alpha = 0.18f)), shape)
                }
            )
            .semantics {
                contentDescription = buildString {
                    append(title)
                    append("，")
                    append(
                        when (visual) {
                            com.example.ui.favorite.ChapterVisual.READ -> "已读"
                            com.example.ui.favorite.ChapterVisual.READING -> "阅读中"
                            com.example.ui.favorite.ChapterVisual.UNREAD -> "未读"
                        }
                    )
                    if (downloaded) append("，已下载")
                    if (bookmarked) append("，已加书签")
                }
            }
            .then(
                if (selecting) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            if (HapticsGate.enabled) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            }
                            onLongPress()
                        },
                    )
                }
            )
            .then(
                if (bookmarkEnabled && !selecting) {
                    Modifier.pointerInput(Unit) {
                        // 与 ChapterStatusRow 同款方向仲裁：横向明显占主导才接管
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var dragX = 0f
                            var isDrag = false
                            var justActivated = false
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y
                                if (!isDrag && abs(totalX) > slop && abs(totalX) >= abs(totalY)) {
                                    isDrag = true
                                    dragActive = true
                                    dragPreviewBookmark = !bookmarked
                                    dragX = totalX.coerceIn(-140f, 140f)
                                    justActivated = true
                                }
                                if (isDrag) {
                                    change.consume()
                                    if (justActivated) justActivated = false
                                    else dragX = (dragX + delta.x).coerceIn(-140f, 140f)
                                    val targetX = dragX
                                    dragScope.launch { dragAnim.snapTo(targetX) }
                                }
                            }
                            if (isDrag && abs(dragX) > 40f) currentToggle.invoke()
                            dragActive = false
                            dragPreviewBookmark = false
                            dragScope.launch {
                                dragAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            // 左列：书签槽（宽 14，固定保留）
            Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(10.dp))

            // 中列
            Column(modifier = Modifier.weight(1f)) {
                val caption = when {
                    isCurrent && pageCount > 0 -> "第 $number 话 · 读到 ${lastPage + 1} / $pageCount 页"
                    isRead -> "第 $number 话 · 已读"
                    else -> "第 $number 话" + if (isNew) " · 新" else ""
                }
                Text(
                    text = caption,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isCurrent && !selecting) MintPrimary else textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isRead) textSecondary else textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))

            // 右列（40×40）：多选=勾选框；下载中=环形进度；已下载/可下载=下载图标
            // 多选点击时整块 40×40 是波纹区，裁成圆形（否则点勾选框四周有灰色矩形波纹）
            Box(
                modifier = Modifier.size(40.dp)
                    .then(
                        when {
                            selecting -> Modifier.clip(CircleShape).clickable(onClick = onClick)
                            downloading || downloaded || external || textMode -> Modifier
                            else -> Modifier.clickable(onClick = onDownload)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selecting -> CheckCircleSpec(checked = checked)
                    downloading -> CircularProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MintPrimary,
                        trackColor = MintPrimary.copy(alpha = 0.18f),
                    )
                    downloaded -> Icon(
                        Icons.Filled.Download,
                        contentDescription = "已下载",
                        tint = MintPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    external || textMode -> Unit
                    else -> Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载",
                        tint = textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 用户添加的书签及滑动预览，挂在卡片上边缘。
        val showUserRibbon = bookmarked || dragPreviewBookmark
        val markProgress by animateFloatAsState(
            targetValue = if (showUserRibbon) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "spec_ribbon",
        )
        if (markProgress > 0.01f) {
            BookmarkRibbon(
                progress = markProgress,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 22.dp, height = 36.dp),
                contentDescription = if (bookmarked) "已加书签" else null,
            )
        }

        // 当前阅读：底边 3dp 进度线（轨道 18%，圆角裁剪，不加行高）
        if (isCurrent && pageCount > 0) {
            val fraction = ((lastPage + 1).toFloat() / pageCount).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MintPrimary.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MintPrimary),
                )
            }
        }
    }
}

/* ══════════════ 神回卡片（规格四，与普通卡片同结构） ══════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GodCardSpec(
    god: GodMomentEntity,
    chapterTitle: String,
    highlighted: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleCheck: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val view = LocalView.current
    val isSelected = selecting && checked

    // 排行榜跳转定位脉冲
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        pulse = true
        delay(900)
        pulse = false
    }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (pulse) 0.28f else 0f,
        animationSpec = tween(if (pulse) 240 else 520),
        label = "god_pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (isSelected) Modifier.background(MintPrimary.copy(alpha = 0.08f)) else Modifier
            )
            .border(
                0.5.dp,
                SolidColor(if (isSelected) MintPrimary else GodGoldStroke),
                shape,
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    if (HapticsGate.enabled) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    onLongPress()
                },
            ),
    ) {
        // 背景：神回封面填满 + 左深右浅暗层
        if (!god.coverPath.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(java.io.File(god.coverPath!!))
                    .memoryCacheKey("god_cover_${god.coverPath}_${god.updatedAt}")
                    .crossfade(220)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF242833), Color(0xFF3A3140))
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            GodScrimBase.copy(alpha = 0.9f),
                            GodScrimBase.copy(alpha = 0.6f),
                            GodScrimBase.copy(alpha = 0.2f),
                        ),
                        startX = 0f,
                        endX = Float.POSITIVE_INFINITY,
                    )
                )
        )
        if (pulseAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(GodGold.copy(alpha = pulseAlpha))
            )
        }

        Row(
            modifier = Modifier
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 说明行：神回 · 用户自定义名（可空；过长省略；无前置图标）
                val name = god.title.takeIf { god.titleIsCustom && god.title.isNotBlank() }
                Text(
                    text = if (name.isNullOrBlank()) "神回" else "神回 · $name",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = GodGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chapterTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))

            // 右列：多选=勾选框（波纹裁圆，避免灰色矩形）；普通=金星 14 + 评分（白 90%）+ 下载（白 70%，40×40）
            Box(
                modifier = Modifier.size(40.dp)
                    .then(
                        if (selecting) {
                            Modifier.clip(CircleShape).clickable(onClick = onToggleCheck)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selecting) {
                    CheckCircleSpec(
                        checked = checked,
                        uncheckedStroke = Color.White.copy(alpha = 0.70f),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = GodGold,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", god.rating),
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.90f),
                        )
                    }
                }
            }
            if (!selecting) {
                Box(
                    modifier = Modifier.size(40.dp).clickable(onClick = onDownload),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 用户定稿：神回卡片上不出现书签标志（书签只属于普通章节卡）。
    }
}

/* ══════════════ 勾选框 ══════════════ */

@Composable
private fun CheckCircleSpec(checked: Boolean, uncheckedStroke: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)) {
    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MintPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(1.5.dp, uncheckedStroke, CircleShape),
            )
        }
    }
}

/* ══════════════ 页面头部（旧版样式：封面模糊背景 + 封面卡 + 标题 + 徽章 + 作者 + 简介） ══════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicHeroHeader(book: SearchBook?, textMode: Boolean, onTextLongPress: (String, String) -> Unit) {
    if (book == null) return
    var descriptionExpanded by remember(book.sourceId, book.id) { mutableStateOf(false) }
    val scrimSurface = MaterialTheme.colorScheme.surface

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            if (!book.cover.isNullOrBlank()) {
                AsyncImage(
                    model = book.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .graphicsLayer {
                            // RenderEffect 仅 API 31+；低版本直接调用会 VerifyError
                            renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                android.graphics.RenderEffect
                                    .createBlurEffect(30f, 30f, android.graphics.Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            } else {
                                null
                            }
                        }
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier
                            } else {
                                Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(
                                        Brush.verticalGradient(
                                            listOf(
                                                scrimSurface.copy(alpha = 0.40f),
                                                scrimSurface.copy(alpha = 0.30f),
                                            )
                                        )
                                    )
                                    drawRect(
                                        Brush.linearGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.10f),
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.08f),
                                            )
                                        )
                                    )
                                }
                            }
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f),
                                )
                            )
                        )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.82f))
                        )
                    )
            )
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(170.dp)
                        .consistentShadow(12.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Gray.copy(alpha = 0.3f))
                ) {
                    if (!book.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = book.cover,
                            contentDescription = book.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.MenuBook,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                            onTextLongPress(if (textMode) "书名" else "漫画名", book.title)
                        }),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                        Text(
                            text = if (textMode) "小说" else "漫画",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    if (book.author.isKnownComicAuthor()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "作者：${book.author}",
                            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                                onTextLongPress("作者名", book.author)
                            }),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!book.author.isKnownComicAuthor()) {
                        Spacer(Modifier.height(6.dp))
                        Text("源站未提供作者", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }

        if (!textMode) ComicMetadataBlock(book, onTextLongPress = onTextLongPress)
        // 简介：默认 3 行，可展开
        val desc = book.description?.takeIf { it.isNotBlank() }
        if (desc != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc.length > 60) {
                TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                    Text(if (descriptionExpanded) "收起" else "展开")
                }
            }
        } else if (!textMode) {
            Text("源站暂未提供简介", modifier = Modifier.padding(top = 12.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadProgressOverlay(
    chapter: ComicChapter,
    progress: Float,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .consistentShadow(10.dp, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        )
        {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = MintPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (paused) "已暂停：${chapter.title}" else "正在下载：${chapter.title}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MintPrimary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                if (!paused) {
                    Text(
                        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MintPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                PlayPauseMorphButton(
                    isPlaying = !paused,
                    onClick = if (paused) onResume else onPause,
                    sizeDp = 36
                )
                AppIconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消下载",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 500ms 长按检测（不消费 down/up：与内部点击共存，只接管长按分支）。 */
private fun Modifier.longPressDetector(onLongPress: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val fired = withTimeoutOrNull(500L) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) return@withTimeoutOrNull false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull false
                }
            }
            false
        } ?: true
        if (fired) {
            onLongPress()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                change.consume()
                if (!change.pressed) break
            }
        }
    }
}
