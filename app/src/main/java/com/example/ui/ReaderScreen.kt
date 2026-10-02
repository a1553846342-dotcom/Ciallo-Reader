@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui



import android.app.Activity

import android.net.Uri

import android.view.WindowManager

import android.widget.Toast

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.view.WindowCompat

import androidx.core.view.WindowInsetsCompat

import androidx.core.view.WindowInsetsControllerCompat

import androidx.compose.animation.*

import androidx.compose.animation.core.Animatable

import androidx.compose.animation.core.Spring

import androidx.compose.animation.core.spring

import androidx.compose.animation.core.tween

import androidx.compose.animation.core.EaseOut

import androidx.compose.animation.core.rememberInfiniteTransition

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.animation.core.infiniteRepeatable

import androidx.compose.animation.core.RepeatMode

import androidx.compose.animation.core.LinearEasing

import androidx.compose.foundation.Canvas

import androidx.compose.foundation.Image

import androidx.compose.ui.res.painterResource

import coil.compose.AsyncImage

import com.example.R

import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.draw.shadow
import me.trishiraj.shadowglow.consistentShadow


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.clickable

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.gestures.detectTransformGestures

import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.ui.draw.alpha

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.safeDrawingPadding

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.automirrored.filled.*

import androidx.compose.material.icons.filled.*

import androidx.compose.material3.*
import com.ramotion.fluidslider.FluidSlider

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clipToBounds

import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.Path

import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.input.pointer.positionChange

import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

import androidx.compose.ui.input.nestedscroll.NestedScrollSource

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.LocalFontFamilyResolver

import androidx.compose.ui.text.PlatformTextStyle

import androidx.compose.ui.text.Paragraph

import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.text.font.FontFamily

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.Constraints

import androidx.compose.ui.unit.IntSize

import androidx.compose.ui.unit.TextUnit

import androidx.compose.ui.unit.TextUnitType

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import com.example.data.*

import com.example.ui.pageturn.PageCurlReaderContainer
import com.example.ui.reader.NovelImageFullscreenViewer
import com.example.ui.reader.NovelInlineImage
import com.example.ui.reader.NovelInlineImages
import com.example.ui.reader.buildAnnotatedWithImages
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.withFrameNanos
import com.example.ui.pageturn.PageScrubberOverlay
import com.example.ui.pageturn.PageTurnContainer

import com.example.ui.pageturn.PageTurnType


import com.example.ui.mascot.MascotAnimationController

import com.example.ui.mascot.MascotEvent
import com.example.ui.mascot.MascotSpriteSheet

import com.example.ui.components.AppSwitch

import com.example.ui.theme.MintPrimary

import com.example.ui.theme.AppFonts

import com.example.ui.components.AppIconButton

import com.example.ui.components.AppActionButton

import com.example.ui.components.AppButtonSize

import com.example.ui.components.AppButtonVariant

import com.example.ui.theme.clickableWithFeedback
import com.example.ui.theme.animateAlpha

import com.example.ui.theme.MintGold

import com.example.ui.theme.MintPrimary

import com.example.ui.theme.MintSecondary

import com.example.ui.theme.onColor

import kotlinx.coroutines.delay

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat

import java.util.*

import kotlin.math.abs
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AppToast



@OptIn(ExperimentalMaterial3Api::class)

@Composable

@androidx.compose.animation.ExperimentalSharedTransitionApi

fun ReaderScreen(

    book: Book?,

    bookTitle: String,

    chapters: List<Chapter>,
    readerLoading: Boolean,
    loadedChapterIndices: Set<Int>,
    readerLoadError: String?,
    onRetryLoad: () -> Unit,
    onEnsureChapterLoaded: (Int, Int) -> Unit,

    onBack: () -> Unit,

    onUpdateProgress: (Int, Int, Int, Boolean) -> Unit,

    prefs: PreferencesManager,

    ttsManager: TtsManager,

    highlights: List<Highlight>,

    bookmarks: List<Bookmark>,

    onAddBookmark: (Int, Int, Int, String, String) -> Unit,

    onDeleteBookmark: (Int) -> Unit,

    onAddHighlight: (Int, Int, String, String, String) -> Unit,

    onDeleteHighlight: (Int) -> Unit,

    searchResults: List<SearchResultItem>,

    isSearching: Boolean,

    onSearch: (String) -> Unit,

    onRecordTime: (Long) -> Unit,

    onSessionEnd: (ReadingSession) -> Unit = {},
    onCheckNovelUpdate: (() -> Unit)? = null

) {

    val context = LocalContext.current

    val sharedTransitionScope = com.example.LocalSharedTransitionScope.current

    val animatedVisibilityScope = com.example.LocalNavAnimatedVisibilityScope.current

    val scope = rememberCoroutineScope()

    val transitionSlidePx = with(LocalDensity.current) { 20.dp.toPx() }



    var showTocSheet by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }

    var showSearchDialog by remember { mutableStateOf(false) }

    var showAnnotationsSheet by remember { mutableStateOf(false) }

    var showEasterEgg by remember { mutableStateOf(false) }

    var showTtsBar by remember { mutableStateOf(false) }

    var showRestDialog by remember { mutableStateOf(false) }



    // 自动滚屏（解放双手模式）

    var isAutoScrolling by remember { mutableStateOf(false) }

    var autoScrollSpeed by remember { mutableFloatStateOf(1f) } // 0.5=慢 / 1=中 / 2=快



    // 键必须是 book.id 而非 book 对象：进度保存会让 ViewModel 发出新的 book 副本，
    // 若以对象为键，异步时序差会把 currentChapterIndex 重置回副本携带的值 ——
    // 表现就是"拖动条松手后被弹回原章节"。以 id 为键仅在换书时重建，进度竞态免疫。
    var currentChapterIndex by remember(book?.id, chapters.isNotEmpty()) {
        mutableIntStateOf(book?.currentChapterIndex ?: 0)
    }
    var reachedBookEnd by remember(book?.id) { mutableStateOf(false) }

    var showBars by remember { mutableStateOf(false) }



    var previousPosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var showReturnChip by remember { mutableStateOf(false) }

    // 返回上次处气泡：出现后 5 秒无操作自动消失（用户反馈"跳页后一直不消失"）
    LaunchedEffect(showReturnChip) {
        if (showReturnChip) {
            delay(5000)
            showReturnChip = false
        }
    }

    // 串珠快速翻页遮罩（长按阅读区中间 1s 触发）
    var scrubberVisible by remember { mutableStateOf(false) }
    // 小说内嵌图片：当前全屏查看的图片路径
    var fullscreenNovelImage by remember { mutableStateOf<String?>(null) }

    // 全屏查看器（Dialog 自带独立窗口，覆盖在阅读器之上）
    fullscreenNovelImage?.let { imgPath ->
        NovelImageFullscreenViewer(
            path = imgPath,
            onDismiss = { fullscreenNovelImage = null }
        )
    }



    var fontSize by remember { mutableFloatStateOf(prefs.fontSize) }

    var lineHeight by remember { mutableFloatStateOf(prefs.lineHeight) }

    var marginHorizontal by remember { mutableIntStateOf(prefs.marginHorizontal) }

    var firstLineIndent by remember { mutableStateOf(prefs.firstLineIndent) }

    var readerTheme by remember { mutableIntStateOf(prefs.readerTheme) }

    var fontFamilyIndex by remember { mutableIntStateOf(prefs.fontFamilyIndex) }

    var pageTurnMode by remember { mutableIntStateOf(prefs.pageTurnMode) }

    var readerBrightness by remember { mutableFloatStateOf(prefs.readerBrightness) }

    val isScrollMode = pageTurnMode == PageTurnType.SCROLL.id

    var currentSubPageIndex by remember { mutableIntStateOf(0) }

    var currentCharOffset by remember { mutableIntStateOf(0) }



    val isTtsPlaying by ttsManager.isPlaying.collectAsStateWithLifecycle()

    var searchKeyword by remember { mutableStateOf("") }



    var customPosterUri by remember { mutableStateOf(prefs.customSplashPosterUri) }

    // 自定义字体文件选择器
    val fontFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val input = context.contentResolver.openInputStream(it)
                        val file = java.io.File(context.filesDir, "custom_font.ttf")
                        input?.use { inp -> file.outputStream().use { out -> inp.copyTo(out) } }
                        prefs.customFontPath = file.absolutePath
                        withContext(Dispatchers.Main) {
                            AppToast.makeText(context, "自定义字体已导入", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            AppToast.makeText(context, "字体导入失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val readerPosterLauncher = rememberLauncherForActivityResult(

        contract = ActivityResultContracts.GetContent()

    ) { uri: Uri? ->

        uri?.let {

            try {

                val inputStream = context.contentResolver.openInputStream(it)

                // 换新文件名：固定路径下 URI 不变会让图片加载器命中旧缓存（同软件背景 bug）

                val file = java.io.File(context.filesDir, "custom_poster_${System.currentTimeMillis()}.jpg")

                inputStream?.use { input ->

                    file.outputStream().use { output ->

                        input.copyTo(output)

                    }

                }

                // 删除旧海报文件

                runCatching {

                    prefs.customSplashPosterUri?.let { old ->

                        val oldFile = java.io.File(android.net.Uri.parse(old).path ?: "")

                        if (oldFile.exists() && oldFile.name.startsWith("custom_poster")) oldFile.delete()

                    }

                }

                val localUriStr = Uri.fromFile(file).toString()

                customPosterUri = localUriStr

                prefs.customSplashPosterUri = localUriStr

                AppToast.makeText(context, "开屏海报已更新", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {

                AppToast.makeText(context, "图片设置失败", Toast.LENGTH_SHORT).show()

            }

        }

    }



    val currentChapter = chapters.getOrNull(currentChapterIndex)

    val scrollState = rememberScrollState()



    // ── 自动滚屏引擎（仅滚动模式生效，逐帧平滑推进）──

    LaunchedEffect(isAutoScrolling, autoScrollSpeed, isScrollMode) {

        if (!isAutoScrolling || !isScrollMode) return@LaunchedEffect

        while (isAutoScrolling && scrollState.value < scrollState.maxValue) {

            scrollState.dispatchRawDelta(1.2f * autoScrollSpeed)

            delay(16) // ~60fps

        }

        // 滚到底自动关闭

        if (scrollState.value >= scrollState.maxValue) {

            isAutoScrolling = false

        }

    }



    // 滚动阅读：章末/章首继续拖拽切章的阈值与状态

    val scrollThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    var overscrollPx by remember { mutableFloatStateOf(0f) }

    var overscrollDirection by remember { mutableIntStateOf(0) } // 1=下一章 -1=上一章 0=无

    // 越界拉动时正文的实时位移（跟手反馈），松手未达阈值时弹簧归零

    val overscrollOffset = remember { Animatable(0f) }

    // 切章动画进行中：期间不再累积越界，避免连跳两章

    var switchingChapter by remember { mutableStateOf(false) }

    // 新章节入场动画（淡入 + 从对应方向滑入），记录上次切章方向决定滑动方向

    var lastChapterSwitchDir by remember { mutableIntStateOf(1) }

    val chapterEntryAlpha = remember { Animatable(1f) }

    val chapterEntryOffsetY = remember { Animatable(0f) }

    var skipFirstChapterEntry by remember { mutableStateOf(true) }

    val chapterEntryPullPx = with(LocalDensity.current) { 42.dp.toPx() }

    // 翻页<->滚动模式切换时的位置锚点

    var pendingScrollRatio by remember { mutableFloatStateOf(-1f) }

    var pendingCharTarget by remember { mutableIntStateOf(-1) }



    // 切章触发（只在“松手且拉满阈值”时调用一次）：

    // 拉穿动画 → 切换章节 → 新章节入场动画

    val triggerSwitch: (Int) -> Unit = { dir ->

        if (!switchingChapter) {

            overscrollPx = 0f

            overscrollDirection = 0

            switchingChapter = true

            lastChapterSwitchDir = dir

            scope.launch {

                // 拉穿动画：正文先跟着手指方向冲一段（有翻页的“拉动感”）

                val pullSign = if (dir == 1) -1f else 1f

                overscrollOffset.animateTo(pullSign * 130f, tween(110))

                when (dir) {

                    1 -> if (currentChapterIndex < chapters.size - 1) {

                        currentChapterIndex++

                        scrollState.scrollTo(0)

                    }

                    -1 -> if (currentChapterIndex > 0) {

                        currentChapterIndex--

                        scrollState.scrollTo(0)

                    }

                }

                switchingChapter = false

            }

        }

    }



    // 松手/方向归零后，正文位移用弹簧平滑归零（拉穿切章时由 switchingChapter 延迟到切章后）

    LaunchedEffect(overscrollDirection, switchingChapter) {

        if (overscrollDirection == 0 && !switchingChapter) {

            overscrollOffset.animateTo(

                0f,

                spring(

                    dampingRatio = Spring.DampingRatioMediumBouncy,

                    stiffness = Spring.StiffnessMediumLow

                )

            )

        }

    }



    // 新章节入场：淡入 + 按切章方向滑入（复用全软件统一弹簧）

    LaunchedEffect(currentChapterIndex, isScrollMode) {

        if (!isScrollMode) return@LaunchedEffect

        if (skipFirstChapterEntry) {

            skipFirstChapterEntry = false

            return@LaunchedEffect

        }

        chapterEntryAlpha.snapTo(0f)

        chapterEntryOffsetY.snapTo(if (lastChapterSwitchDir == 1) chapterEntryPullPx else -chapterEntryPullPx)

        launch {

            chapterEntryAlpha.animateTo(1f, tween(220))

        }

        launch {

            chapterEntryOffsetY.animateTo(

                0f,

                spring(

                    dampingRatio = Spring.DampingRatioMediumBouncy,

                    stiffness = Spring.StiffnessMediumLow

                )

            )

        }

    }



    DisposableEffect(prefs.keepScreenOn) {

        val activity = context as? Activity

        if (prefs.keepScreenOn) {

            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        } else {

            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        }

        onDispose {

            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        }

    }



    // 沉浸式阅读：整个阅读过程隐藏系统状态栏（松手/滑动都不会再“弹出状态栏”），

    // 阅读菜单自带 safeDrawing 顶部留白，不需要系统状态栏；退出阅读器时恢复。

    DisposableEffect(Unit) {

        onDispose {

            val w = (context as? Activity)?.window ?: return@onDispose

            WindowCompat.getInsetsController(w, w.decorView)

                .show(WindowInsetsCompat.Type.statusBars())

        }

    }



    // 阅读计时：只在 App 前台 + 屏幕亮着时累计（修复后台/锁屏虚增时长 bug），

    // 每 30 秒上报，离开时写一条阅读会话供日历时段/高峰时段统计。

    ReadingTimerEffect(

        bookId = book?.id,

        bookTitle = currentChapter?.title ?: bookTitle,

        onFlush = { seconds -> onRecordTime(seconds) },

        onSessionEnd = { session -> onSessionEnd(session) },

        onRestTick = { elapsedSec ->

            val restMins = prefs.restReminderMinutes

            if (restMins > 0 && elapsedSec > 0 && elapsedSec % (restMins * 60) == 0L) {

                showRestDialog = true

            }

        }

    )



    LaunchedEffect(book, chapters) {

        if (book != null && chapters.isNotEmpty() && book.scrollOffset > 0) {

            if (currentChapterIndex == book.currentChapterIndex) {

                if (isScrollMode) {

                    scrollState.scrollTo(book.scrollOffset)

                } else {

                    currentCharOffset = book.scrollOffset

                }

            }

        }

    }



    // 切章立即读取正文，进度写库与正文读取各自独立。
    LaunchedEffect(book?.id, currentChapterIndex, chapters.size) {
        if (book != null && !book.isComic && chapters.isNotEmpty()) {
            onEnsureChapterLoaded(book.id, currentChapterIndex)
        }
    }

    // 翻页/模式切换：立即保存进度

    LaunchedEffect(currentChapterIndex, isScrollMode) {

        if (book != null && chapters.isNotEmpty()) {

            val offsetToSave = if (isScrollMode) scrollState.value else currentCharOffset

            val isFinished = book.isFinished || reachedBookEnd

            onUpdateProgress(book.id, currentChapterIndex, offsetToSave, isFinished)

        }

    }



    // 滚动模式：1.5 秒防抖保存，避免滚动时每帧写数据库导致掉帧

    LaunchedEffect(scrollState.value) {

        if (isScrollMode && book != null && chapters.isNotEmpty()) {

            delay(1500)

            val offsetToSave = scrollState.value

            val isFinished = book.isFinished || reachedBookEnd ||
                (currentChapterIndex == chapters.lastIndex && currentChapter?.content?.isNotEmpty() == true &&
                    scrollState.maxValue > 0 && offsetToSave >= scrollState.maxValue)

            onUpdateProgress(book.id, currentChapterIndex, offsetToSave, isFinished)

        }

    }



    // 翻页模式：字符偏移变化时保存（离散翻页，非连续滚动）

    LaunchedEffect(currentCharOffset) {

        if (!isScrollMode && book != null && chapters.isNotEmpty()) {

            val offsetToSave = currentCharOffset

            val isFinished = book.isFinished || reachedBookEnd

            onUpdateProgress(book.id, currentChapterIndex, offsetToSave, isFinished)

        }

    }



    // 退出阅读页时兜底保存一次最新进度

    DisposableEffect(Unit) {

        onDispose {

            if (book != null && chapters.isNotEmpty()) {

                val offsetToSave = if (isScrollMode) scrollState.value else currentCharOffset

                val isFinished = book.isFinished || reachedBookEnd ||
                    (isScrollMode && currentChapterIndex == chapters.lastIndex && currentChapter?.content?.isNotEmpty() == true &&
                        scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue)

                onUpdateProgress(book.id, currentChapterIndex, offsetToSave, isFinished)

            }

        }

    }



    // 书签切换（滚动模式停用下拉手势后，菜单按钮作为唯一入口）

    val toggleBookmark: () -> Unit = {

        val currentBookId = book?.id ?: 0

        val existingBookmark = bookmarks.find {

            (it.bookId == currentBookId || it.bookId == 0) && it.chapterIndex == currentChapterIndex

        }

        if (existingBookmark != null) {

            onDeleteBookmark(existingBookmark.id)

            AppToast.makeText(context, "已取消书签", Toast.LENGTH_SHORT).show()

        } else {

            currentChapter?.let { ch ->

                onAddBookmark(currentBookId, currentChapterIndex, scrollState.value, ch.title, ch.content.take(60))

            }

        }

    }



    // 切换阅读模式：翻页 <-> 滚动时按比例映射当前阅读位置，切完后由 LaunchedEffect 锚定

    val switchPageMode: (Int) -> Unit = { newModeId ->

        val oldScroll = pageTurnMode == PageTurnType.SCROLL.id

        val newScroll = newModeId == PageTurnType.SCROLL.id

        if (oldScroll != newScroll) {

            val contentLen = currentChapter?.content?.length ?: 0

            if (newScroll) {

                val ratio = if (contentLen > 0) currentCharOffset.toFloat() / contentLen else 0f

                pendingScrollRatio = ratio

            } else {

                val ratio = if (scrollState.maxValue > 0) {

                    scrollState.value.toFloat() / scrollState.maxValue

                } else {

                    0f

                }

                pendingCharTarget = (contentLen * ratio).toInt()

            }

        }

        pageTurnMode = newModeId

        prefs.pageTurnMode = newModeId

        // 切换翻页模式时关闭自动滚屏（仅滚动模式支持）
        isAutoScrolling = false

    }



    val (bgColor, textColor) = when (readerTheme) {

        0 -> Color.White to Color(0xFF18191C) // Pure Light

        1 -> Color.White to Color(0xFF18191C) // Default White

        2 -> Color(0xFFFBF0D9) to Color(0xFF5F4B32) // Sepia

        3 -> Color(0xFF18191C) to Color(0xFFD4D4D4) // Dark

        4 -> Color(0xFFE8F5E9) to Color(0xFF1B5E20) // Eye Green

        5 -> Color.Black to Color(0xFFE0E0E0) // OLED Black

        else -> Color.White to Color(0xFF18191C)

    }



    // 自定义字体加载（TTF 文件）
    var customTypeface by remember { mutableStateOf<android.graphics.Typeface?>(null) }
    LaunchedEffect(prefs.customFontPath) {
        val path = prefs.customFontPath
        if (path.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try { customTypeface = android.graphics.Typeface.createFromFile(path) } catch (_: Exception) {}
            }
        }
    }

    // 字体族统一走 AppFonts（theme 层唯一出口），不再在本文件硬编码通用字族：
    // 那些字面量会解析到各厂商系统字体（MIUI→MiSans、HarmonyOS→HarmonyOS Sans…），
    // 是"同一份设计稿不同机型长得不一样"的一条主因。收敛后换字体只改 AppFonts 一处。
    val selectedFontFamily = remember(fontFamilyIndex, customTypeface) {
        AppFonts.readingFontFamily(fontFamilyIndex, customTypeface?.let { FontFamily(it) })
    }



    // 小章节同步准备（秒开无闪烁）；超大章节后台准备，避免主线程被几 MB 文本卡住

    // 搜索结果点击后的精确跳转与页内高亮：
    // pendingSearchJump 携带结果条目（含逻辑章内字符偏移 logicalPosition），跳转后
    // 在目标页/目标块高亮关键词；翻页离开目标页自动清除（见 BoxWithConstraints 内的监听）。
    var pendingSearchJump by remember { mutableStateOf<SearchResultItem?>(null) }
    var searchHighlightQuery by remember { mutableStateOf<String?>(null) }
    val searchHighlightStyle = SpanStyle(
        background = MintPrimary.copy(alpha = 0.45f),
        fontWeight = FontWeight.Bold
    )
    var searchHighlightPage by remember { mutableIntStateOf(-1) }
    var searchHighlightChunk by remember { mutableIntStateOf(-1) }
    // 跳转时刻：大章渐进分页期间页数未稳定，清除判定加保护期防误清
    var searchHighlightAt by remember { mutableLongStateOf(0L) }

    val smallChapter = (currentChapter?.content?.length ?: 0) <= LARGE_CHAPTER_THRESHOLD

    var formattedContent by remember(currentChapter, firstLineIndent) {

        mutableStateOf(

            if (smallChapter) formatForReader(currentChapter?.content ?: "", firstLineIndent) else ""

        )

    }

    var scrollChunks by remember(currentChapter, firstLineIndent) {

        val formatted = if (smallChapter) formatForReader(currentChapter?.content ?: "", firstLineIndent) else ""

        mutableStateOf(if (smallChapter) chunkForScroll(formatted) else emptyList())

    }

    // 搜索跳转用：全局文本块的 TextLayoutResult 与容器内 y 坐标。
    // 滚动正文按块驱动渲染（文本块/图片块），key = 跨 chunk 累计的全局块序号
    val chunkTextLayouts = remember(scrollChunks.size) { mutableStateMapOf<Int, TextLayoutResult>() }
    val chunkTopYs = remember(scrollChunks.size) { mutableStateMapOf<Int, Float>() }

    var contentReady by remember(currentChapter, firstLineIndent) { mutableStateOf(smallChapter) }

    LaunchedEffect(currentChapter, firstLineIndent) {

        if (smallChapter) return@LaunchedEffect

        contentReady = false

        val (formatted, chunks) = withContext(Dispatchers.Default) {

            val text = currentChapter?.content ?: ""

            val formatted = formatForReader(text, firstLineIndent)

            formatted to chunkForScroll(formatted)

        }

        formattedContent = formatted

        scrollChunks = chunks

        contentReady = true

    }



    // 翻页 <-> 滚动模式切换后，把当前阅读位置按比例映射到新模式（同一章内锚定）

    LaunchedEffect(isScrollMode, contentReady, scrollState.maxValue) {

        if (isScrollMode && contentReady && scrollState.maxValue > 0 && pendingScrollRatio >= 0f) {

            val ratio = pendingScrollRatio.coerceIn(0f, 1f)

            pendingScrollRatio = -1f

            val target = (scrollState.maxValue * ratio).toInt().coerceIn(0, scrollState.maxValue)

            scrollState.scrollTo(target)

        }

    }

    LaunchedEffect(isScrollMode, contentReady) {

        if (!isScrollMode && contentReady && pendingCharTarget >= 0) {

            currentCharOffset = pendingCharTarget

            pendingCharTarget = -1

        }

    }



    // 共享封面转场：封面先随共享元素缩放进场，随后正文从右侧轻轻滑入叠在封面上，

    // 封面再缓慢淡成水印，避免“加载完正文瞬间盖掉封面”的生硬感。

    var transitionStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {

        kotlinx.coroutines.delay(140)

        transitionStarted = true

    }

    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(

        targetValue = if (transitionStarted) 1f else 0f,

        animationSpec = tween(420, easing = com.example.ui.theme.IosMotion.EaseOut),

        label = "readerContentAlpha"

    )

    val coverAlpha by androidx.compose.animation.core.animateFloatAsState(

        targetValue = if (transitionStarted) 0.06f else 1f,

        // 2026-09-21：原来 900ms —— 打开书籍后封面要近 1 秒才淡干净，等待感很重，
        // 且与内容(420ms)、背景(380ms)的节奏完全打架。缩到 480ms：
        // 内容先到位，封面稍后收尾，形成层次又不拖沓。
        animationSpec = tween(480, easing = com.example.ui.theme.IosMotion.EaseOut),

        label = "readerCoverAlpha"

    )

    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(

        targetValue = if (transitionStarted) 1f else 0f,

        animationSpec = tween(380, easing = com.example.ui.theme.IosMotion.EaseOut),

        label = "readerBgAlpha"

    )



    Box(

        modifier = Modifier

            .fillMaxSize()

            .background(bgColor.copy(alpha = bgAlpha))

    ) {

        if (book != null && sharedTransitionScope != null && animatedVisibilityScope != null) {

            with(sharedTransitionScope) {

                val imageRequest = if (!book.coverUri.isNullOrEmpty() && book.isCoverValid) {

                    if (book.coverUri!!.startsWith("content://")) android.net.Uri.parse(book.coverUri)

                    else java.io.File(book.coverUri!!)

                } else null

                if (imageRequest != null) {

                    coil.compose.AsyncImage(

                        model = imageRequest,

                        contentDescription = "Shared Cover",

                        modifier = Modifier

                            .fillMaxSize()

                            .sharedElement(

                                state = rememberSharedContentState(key = "book_cover_${book.id}"),

                                animatedVisibilityScope = animatedVisibilityScope,

                                boundsTransform = { _, _ ->

                                    tween(420, easing = com.example.ui.theme.IosMotion.EaseOut)

                                }

                            )

                            .clip(

                                RoundedCornerShape(

                                    topStart = 4.dp,

                                    bottomStart = 4.dp,

                                    topEnd = 12.dp,

                                    bottomEnd = 12.dp

                                )

                            )

                            .alpha(coverAlpha),

                        contentScale = androidx.compose.ui.layout.ContentScale.Crop

                    )

                }

            }

        }

        BoxWithConstraints(

            modifier = Modifier

                .fillMaxSize()

                .graphicsLayer {

                    alpha = contentAlpha

                    translationX = (1f - contentAlpha) * transitionSlidePx

                }

        ) {

            var pageContainerSize by remember { mutableStateOf<IntSize?>(null) }

            val density = LocalDensity.current

            // 安全区：stateBars/navigationBars 与 displayCutout(挖孔/刘海) 取**较大值**。
            // 多数竖屏机型上 statusBars 已经把顶部刘海算进去，两者相等 → 什么都不变；
            // 但横屏（刘海移到侧边）或底部挖孔的机型上 cutout 可能比系统栏更大，
            // 只取系统栏会让正文滑到挖孔底下被裁掉。取 max 保证「只会更安全，不会更小」。
            val cutoutInsets = WindowInsets.displayCutout
            val statusBarsInsets = WindowInsets.statusBars
            val navigationBarsInsets = WindowInsets.navigationBars

            val navBarsBottomPx = maxOf(
                navigationBarsInsets.getBottom(density),
                cutoutInsets.getBottom(density)
            )

            val statusBarsTopPx = maxOf(
                statusBarsInsets.getTop(density),
                cutoutInsets.getTop(density)
            )



            val fallbackWidthPx = with(density) { (maxWidth - marginHorizontal.dp * 2).toPx().toInt() }.coerceAtLeast(100)

            val headerFooterPaddingPx = with(density) {

                var pad = 24.dp.toPx().toInt()

                if (prefs.showOverlayHeaderFooter) {

                    pad += 48.dp.toPx().toInt()

                }

                pad

            }

            val fallbackHeightPx = with(density) {

                (maxHeight.toPx().toInt() - statusBarsTopPx - navBarsBottomPx - headerFooterPaddingPx).coerceAtLeast(100)

            }



            val containerWidthPx = pageContainerSize?.let {

                (it.width - with(density) { (marginHorizontal.dp * 2).toPx().toInt() }).coerceAtLeast(100)

            } ?: fallbackWidthPx



            val containerHeightPx = pageContainerSize?.let {

                (it.height - with(density) { (PAGE_VERTICAL_PADDING_DP * 2).dp.toPx().toInt() }).coerceAtLeast(100)

            } ?: fallbackHeightPx



            val bodyTextStyle = MaterialTheme.typography.bodyLarge.copy(

                fontSize = fontSize.sp,

                lineHeight = lineHeight.sp,

                fontFamily = selectedFontFamily,

                color = textColor.copy(alpha = 0.92f),

                platformStyle = PlatformTextStyle(includeFontPadding = false)

            )



            val titleStyle = MaterialTheme.typography.headlineSmall.copy(

                fontWeight = FontWeight.Bold,

                color = textColor,

                platformStyle = PlatformTextStyle(includeFontPadding = false)

            )

            val textWidthPx = containerWidthPx

            val textHeightPx = containerHeightPx - with(density) { PAGE_TEXT_BOTTOM_PADDING_DP.dp.toPx().toInt() }.coerceAtLeast(16)

            android.util.Log.d("ScrubDebug", "pagination: pageContainer=${pageContainerSize?.width}x${pageContainerSize?.height} textW=${textWidthPx} textH=${textHeightPx}")

            val fontFamilyResolver = LocalFontFamilyResolver.current

            val currentTitleReservePx = remember(currentChapter?.title, textWidthPx, titleStyle, density, fontFamilyResolver) {

                measureTitleReservePx(

                    title = currentChapter?.title,

                    widthPx = textWidthPx,

                    maxHeightPx = textHeightPx,

                    titleStyle = titleStyle,

                    density = density,

                    fontFamilyResolver = fontFamilyResolver

                )

            }



            val pagesList = rememberChapterPages(

                content = formattedContent,

                widthPx = textWidthPx,

                heightPx = textHeightPx,

                bodyStyle = bodyTextStyle,

                titleReservePx = currentTitleReservePx,

                isScrollMode = isScrollMode

            )



            // 只在用户主动向前翻到书末时庆祝。进度恢复和渐进分页会改写页索引，不能据此触发动画。
            var celebratedBookComplete by remember(book?.id) { mutableStateOf(false) }

            val nextChapter = chapters.getOrNull(currentChapterIndex + 1)

            val nextTitleReservePx = remember(nextChapter?.title, textWidthPx, titleStyle, density, fontFamilyResolver) {

                measureTitleReservePx(

                    title = nextChapter?.title,

                    widthPx = textWidthPx,

                    maxHeightPx = textHeightPx,

                    titleStyle = titleStyle,

                    density = density,

                    fontFamilyResolver = fontFamilyResolver

                )

            }

            val nextChapterFormattedContent = remember(nextChapter, firstLineIndent) {

                val text = nextChapter?.content ?: ""

                if (firstLineIndent) {

                    text.split("\n").joinToString("\n") { line ->

                        if (line.isNotBlank() && !line.startsWith("\u3000\u3000")) "\u3000\u3000$line" else line

                    }

                } else {

                    text

                }

            }

            val nextChapterPages = rememberChapterPages(

                content = nextChapterFormattedContent,

                widthPx = textWidthPx,

                heightPx = textHeightPx,

                bodyStyle = bodyTextStyle,

                titleReservePx = nextTitleReservePx,

                isScrollMode = isScrollMode

            )



            val prevChapter = chapters.getOrNull(currentChapterIndex - 1)

            val prevTitleReservePx = remember(prevChapter?.title, textWidthPx, titleStyle, density, fontFamilyResolver) {

                measureTitleReservePx(

                    title = prevChapter?.title,

                    widthPx = textWidthPx,

                    maxHeightPx = textHeightPx,

                    titleStyle = titleStyle,

                    density = density,

                    fontFamilyResolver = fontFamilyResolver

                )

            }

            val prevChapterFormattedContent = remember(prevChapter, firstLineIndent) {

                val text = prevChapter?.content ?: ""

                if (firstLineIndent) {

                    text.split("\n").joinToString("\n") { line ->

                        if (line.isNotBlank() && !line.startsWith("\u3000\u3000")) "\u3000\u3000$line" else line

                    }

                } else {

                    text

                }

            }

            val prevChapterPages = rememberChapterPages(

                content = prevChapterFormattedContent,

                widthPx = textWidthPx,

                heightPx = textHeightPx,

                bodyStyle = bodyTextStyle,

                titleReservePx = prevTitleReservePx,

                isScrollMode = isScrollMode

            )



            val activeSubPageIndex = currentSubPageIndex.coerceIn(0, (pagesList.size - 1).coerceAtLeast(0))



            // Re-anchor subpage index based on currentCharOffset when pagesList recalculates (e.g. screen rotation / layout change)

            LaunchedEffect(pagesList) {

                if (!isScrollMode && pagesList.isNotEmpty()) {

                    var accumulated = 0

                    var targetPageIndex = 0

                    for (index in pagesList.indices) {

                        val pageLen = pagesList[index].length

                        if (accumulated + pageLen > currentCharOffset || index == pagesList.lastIndex) {

                            targetPageIndex = index

                            break

                        }

                        accumulated += pageLen

                    }

                    currentSubPageIndex = targetPageIndex

                }

            }



            val updateSubPage = { newIndex: Int ->

                val clamped = newIndex.coerceIn(0, (pagesList.size - 1).coerceAtLeast(0))

                currentSubPageIndex = clamped

                var offset = 0

                for (i in 0 until clamped) {

                    offset += pagesList.getOrNull(i)?.length ?: 0

                }

                currentCharOffset = offset

            }

            // 搜索结果点击后的精确跳转 + 页内高亮（pendingSearchJump / searchHighlightQuery
            // 由搜索对话框点击处设置）。防竞态：必须等 currentChapterIndex 切到目标章、
            // 文本/分页就绪后才执行；大章异步准备时由 key 变化自然重触发。

            LaunchedEffect(pendingSearchJump, formattedContent, scrollChunks, currentChapterIndex) {

                val jump = pendingSearchJump ?: return@LaunchedEffect

                if (!isScrollMode) return@LaunchedEffect

                if (currentChapterIndex != jump.chapterIndex) return@LaunchedEffect

                if (scrollChunks.isEmpty() || formattedContent.isEmpty()) return@LaunchedEffect

                val q = searchHighlightQuery ?: ""

                // formatted 文本 = 物理章按顺序拼接（与搜索统计 occurrence 同一基准）：
                // 取结果条目对应的第 N 处出现 —— 免疫缩进/清洗造成的偏移漂移
                val pos = com.example.data.SearchLocator.nthOccurrence(formattedContent, q, jump.occurrence)

                if (pos < 0) { pendingSearchJump = null; return@LaunchedEffect }

                // 定位 pos 所在的全局块（与渲染同源的块遍历：块 raw 拼接 == formattedContent）。
                // 文本块精确到块内偏移；图片块滚到块顶。
                var gb = 0

                var acc = 0

                var targetBlock = -1

                var targetIsText = false

                var localInBlock = 0

                blockLoop@ for (chunk in scrollChunks) {

                    for (b in NovelInlineImages.splitIntoBlocks(chunk, textWidthPx.toFloat(), Float.MAX_VALUE)) {

                        val len = b.raw.length

                        if (pos < acc + len) {

                            targetBlock = gb

                            targetIsText = b is com.example.ui.reader.NovelInlineImages.InlineBlock.Text

                            localInBlock = (pos - acc).coerceAtLeast(0)

                            break@blockLoop

                        }

                        acc += len

                        gb++

                    }

                }

                if (targetBlock < 0) { targetBlock = (gb - 1).coerceAtLeast(0); targetIsText = false }

                searchHighlightChunk = targetBlock

                // 等待目标块完成组合与文本布局（onTextLayout 填充），最多让出几帧
                var foundLayout: TextLayoutResult? = chunkTextLayouts[targetBlock]

                var foundTop: Float? = chunkTopYs[targetBlock]

                var tries = 0

                while ((foundTop == null || (targetIsText && foundLayout == null)) && tries < 6) {

                    withFrameNanos { }

                    foundLayout = chunkTextLayouts[targetBlock]

                    foundTop = chunkTopYs[targetBlock]

                    tries++

                }

                val layout = foundLayout

                val top = foundTop

                if (top != null) {

                    val lineTop = if (targetIsText && layout != null && layout.layoutInput.text.text.isNotEmpty()) {

                        val line = layout.getLineForOffset(localInBlock.coerceIn(0, layout.layoutInput.text.text.length - 1))

                        layout.getLineTop(line)

                    } else 0f

                    scrollState.scrollTo((top + lineTop).toInt().coerceIn(0, scrollState.maxValue))

                } else {

                    scrollState.scrollTo(0)

                }

                pendingSearchJump = null

            }

            // 相邻页插图预加载：翻页前把上一页/下一页的图解码进缓存，
            // 翻页动画期间下一页层组合即可命中位图 —— 消除"翻页背后灰白、翻完才出图"
            LaunchedEffect(activeSubPageIndex, pagesList) {

                if (!isScrollMode && pagesList.isNotEmpty()) {

                    val upcoming = listOfNotNull(
                        pagesList.getOrNull(activeSubPageIndex + 1),
                        pagesList.getOrNull(activeSubPageIndex - 1)
                    ).flatMap { p ->
                        NovelInlineImages.TOKEN_REGEX.findAll(p).map { m -> m.groupValues[1] }
                    }

                    NovelInlineImages.NovelImageCache.prewarm(upcoming)

                }

            }

            LaunchedEffect(pendingSearchJump, pagesList, currentChapterIndex) {

                val jump = pendingSearchJump ?: return@LaunchedEffect

                if (isScrollMode || pagesList.isEmpty()) return@LaunchedEffect

                if (currentChapterIndex != jump.chapterIndex) return@LaunchedEffect

                val q = searchHighlightQuery ?: ""

                // 页文本拼接 = formattedContent：取结果条目对应的第 N 处出现，再按累计页长度换算目标页
                val pos = com.example.data.SearchLocator.nthOccurrence(formattedContent, q, jump.occurrence)

                var acc = 0

                var page = -1

                for ((i, p) in pagesList.withIndex()) {

                    if (pos < acc + p.length) { page = i; break }

                    acc += p.length

                }

                if (page < 0) page = pagesList.lastIndex

                searchHighlightPage = page

                searchHighlightAt = System.currentTimeMillis()

                updateSubPage(page)

                pendingSearchJump = null

            }

            // 翻页离开目标页后清除高亮（跳转本身落到目标页则保留）；
            // 跳转后 1.5s 内是渐进分页期，页数未稳定，不判定"离开"
            LaunchedEffect(activeSubPageIndex) {

                if (searchHighlightQuery != null && activeSubPageIndex != searchHighlightPage &&
                    System.currentTimeMillis() - searchHighlightAt > 1500
                ) {

                    searchHighlightQuery = null

                }

            }



            val handleNextPage = {

                if (isScrollMode) {

                    if (currentChapterIndex < chapters.size - 1) {

                        currentChapterIndex++

                        scope.launch { scrollState.scrollTo(0) }

                    }

                } else {

                    if (!celebratedBookComplete && shouldCelebrateAfterForwardTurn(
                            currentChapterIndex, chapters.size, activeSubPageIndex,
                            pagesList, formattedContent.length
                        )) {
                        celebratedBookComplete = true
                        reachedBookEnd = true
                        MascotAnimationController.play(MascotEvent.BookComplete)
                    }

                    if (activeSubPageIndex < pagesList.size - 1) {

                        updateSubPage(activeSubPageIndex + 1)

                    } else if (currentChapterIndex < chapters.size - 1) {

                        currentChapterIndex++

                        currentCharOffset = 0

                        currentSubPageIndex = 0

                        scope.launch { scrollState.scrollTo(0) }

                    }

                }

            }



            val handlePrevPage = {

                if (isScrollMode) {

                    if (currentChapterIndex > 0) {

                        currentChapterIndex--

                        scope.launch { scrollState.scrollTo(0) }

                    }

                } else {

                    if (activeSubPageIndex > 0) {

                        updateSubPage(activeSubPageIndex - 1)

                    } else if (currentChapterIndex > 0) {

                        currentChapterIndex--

                        currentCharOffset = Int.MAX_VALUE

                        currentSubPageIndex = 9999

                        scope.launch { scrollState.scrollTo(0) }

                    }

                }

            }



            if (readerLoadError != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("章节加载失败：$readerLoadError", color = textColor)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetryLoad) { Text("重试加载") }
                }
            } else if (readerLoading || chapters.isEmpty() ||
                (book?.isComic == false && currentChapterIndex !in loadedChapterIndices) ||
                !contentReady ||
                (!isScrollMode && pagesList.isEmpty())) {

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

                    CircularProgressIndicator(color = MintPrimary)

                }

            } else if (currentChapter?.title == UNSUPPORTED_CHAPTER_TITLE &&

                currentChapter?.content?.isBlank() == true

            ) {

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

                    Text(

                        text = "该文件格式暂不支持阅读（KFX / DJVU / DOC / RTF / CHM 等）\n请导入 EPUB、PDF、TXT 或漫画格式",

                        color = textColor.copy(alpha = 0.75f),

                        fontSize = 14.sp,

                        modifier = Modifier.padding(32.dp)

                    )

                }

            } else if (currentChapter?.content.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("本章暂无正文", color = textColor.copy(alpha = 0.75f))
                }
            } else {

                currentChapter?.let { chapter ->

                    Box(

                        modifier = Modifier.fillMaxSize()

                    ) {

                            Column(

                                modifier = Modifier

                                    .fillMaxSize()

                                    // 状态栏隐藏后仍保留刘海/导航栏留白，菜单与正文都不贴边

                                    .safeDrawingPadding()

                            ) {

                            // 常驻占位版页眉：尺寸永不随菜单开合变化（只切透明度）——
                            // 正文容器高度与 showBars 彻底解耦，分页缓存键不再失配触发整章重算
                            if (prefs.showOverlayHeaderFooter) {

                                Row(

                                    modifier = Modifier

                                        .fillMaxWidth()

                                        .padding(horizontal = marginHorizontal.dp, vertical = 6.dp)

                                        // 此链禁止添加 clickable/pointerInput：
                                        // graphicsLayer alpha=0 不豁免 Compose 命中测试，会变成幽灵热区
                                        // B5：原先 alpha 直接取 0/1，页眉页脚是"瞬间闪没"，
                                        // 与顶/底栏的 slideInVertically 完全不同步。改为透明度过渡。
                                        .animateAlpha(if (showBars) 0f else 1f),

                                    horizontalArrangement = Arrangement.SpaceBetween

                                ) {

                                    Text(chapter.title, fontSize = 10.sp, color = textColor.copy(alpha = 0.5f), maxLines = 1)

                                    val timeStr = remember(showBars) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }

                                    Text(timeStr, fontSize = 10.sp, color = textColor.copy(alpha = 0.5f))

                                }

                            }



                            Box(

                                modifier = Modifier

                                    .weight(1f)

                                    .fillMaxWidth()

                                    .onSizeChanged { size ->

                                        if (size.width > 0 && size.height > 0) {

                                            pageContainerSize = size

                                        }

                                    }

                            ) {

                                // C1 pagecurl 引擎插槽化：三个页面槽提取为局部 Composable，

                                // SIMULATE 档走 pagecurl 卷页引擎，其余档位保持原容器

                                val currentSlot: @Composable () -> Unit = {

                                        if (isScrollMode) {

                                            Column(

                                                modifier = Modifier

                                                    .fillMaxSize()

                                                    .graphicsLayer {

                                                        // 越界拉动跟手位移 + 新章节入场动画叠加

                                                        translationY = overscrollOffset.value + chapterEntryOffsetY.value

                                                        alpha = chapterEntryAlpha.value

                                                    }

                                                    // 滚动模式统一手势（与 PageTurnContainer 拉下书签手势同款 awaitEachGesture 写法）：

                                                    //  - 章节中部拖拽一律不消费，完全交给 verticalScroll，互不干扰；

                                                    //  - 仅在章首/章尾继续越界拖动时接管（消费增量），累积到阈值切章；

                                                    //  - 纯点击（无位移）唤起/关闭菜单。

                                                    // 注意：本手势必须放在 verticalScroll 之前（外层），

                                                    // 才能先于滚动组件接管边界拖拽，避免两套手势互相打架。

                                                    .pointerInput(scrollState, currentChapterIndex, chapters.size) {

                                                        awaitEachGesture {

                                                            val down = awaitFirstDown(requireUnconsumed = false)

                                                            var boundaryPulling = false

                                                            while (true) {

                                                                val event = awaitPointerEvent()

                                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                                                if (!change.pressed) break

                                                                val deltaY = change.positionChange().y

                                                                val atBottom = scrollState.value >= scrollState.maxValue

                                                                val atTop = scrollState.value <= 0

                                                                val pullNext = atBottom && deltaY < 0 &&

                                                                    currentChapterIndex < chapters.size - 1 && !switchingChapter

                                                                val pullPrev = atTop && deltaY > 0 &&

                                                                    currentChapterIndex > 0 && !switchingChapter

                                                                if (pullNext || pullPrev) {

                                                                    boundaryPulling = true

                                                                    change.consume()

                                                                    overscrollPx += abs(deltaY)

                                                                    overscrollDirection = if (pullNext) 1 else -1

                                                                    scope.launch {

                                                                        overscrollOffset.snapTo(

                                                                            if (pullNext) -overscrollPx * 0.35f

                                                                            else overscrollPx * 0.35f

                                                                        )

                                                                    }

                                                                    // 只累积，不在这里切章——等松手再判定（避免“没松手就翻页”）

                                                                } else if (boundaryPulling) {

                                                                    // 手指反向离开边界：取消越界，交还滚动

                                                                    boundaryPulling = false

                                                                    overscrollPx = 0f

                                                                    overscrollDirection = 0

                                                                }

                                                            }

                                                            // 手势结束（松手）：边界拉满阈值 → 切章（拉穿动画 + 新章入场）。

                                                            // 点击唤菜单交给独立的 detectTapGestures，滑动绝不会被误判成点击。

                                                            if (boundaryPulling &&

                                                                overscrollPx >= scrollThresholdPx &&

                                                                !switchingChapter

                                                            ) {

                                                                triggerSwitch(overscrollDirection)

                                                            }

                                                            overscrollPx = 0f

                                                            overscrollDirection = 0

                                                        }

                                                    }

                                                    // 标准点击判定（自带 touchSlop 过滤）：只有真正的点按才唤起/关闭菜单

                                                    .pointerInput(Unit) {

                                                        detectTapGestures(onTap = { showBars = !showBars })

                                                    }

                                                    .verticalScroll(scrollState)

                                                    .padding(horizontal = marginHorizontal.dp, vertical = 16.dp)

                                            ) {

                                                if (currentChapterIndex > 0) {

                                                    Text(

                                                        text = "↓ 已到本章开头 · 继续下拉返回上一章",

                                                        fontSize = 12.sp,

                                                        color = textColor.copy(alpha = 0.45f),

                                                        modifier = Modifier

                                                            .fillMaxWidth()

                                                            .padding(bottom = 12.dp)

                                                    )

                                                }

                                                Text(

                                                    text = chapter.title,

                                                    style = MaterialTheme.typography.headlineSmall,

                                                    fontWeight = FontWeight.Bold,

                                                    color = textColor,

                                                    modifier = Modifier.padding(bottom = 20.dp, top = 12.dp)

                                                )



                                                SelectionContainer {

                                                    Column {

                                                        // 大章节块驱动渲染：文本块 BasicText、图片块独立占空间。
                                                        // 全局块序号（跨 chunk 累计）供搜索跳转定位；图片块不走
                                                        // 文本流内联占位，占位失效/叠绘/点击错位一并根除。
                                                        var globalBlockIndex = 0

                                                        scrollChunks.forEach { chunk ->

                                                            val scrollDensity = LocalDensity.current

                                                            NovelInlineImages.splitIntoBlocks(
                                                                text = chunk,
                                                                contentWidthPx = textWidthPx.toFloat(),
                                                                // 滚动模式无页高限制，长图自然延展
                                                                maxHeightPx = Float.MAX_VALUE
                                                            ).forEach { block ->
                                                                val gb = globalBlockIndex
                                                                globalBlockIndex++
                                                                when (block) {
                                                                    is com.example.ui.reader.NovelInlineImages.InlineBlock.Image -> {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .height(with(scrollDensity) { block.heightPx.toDp() })
                                                                                .clipToBounds()
                                                                                .onGloballyPositioned { coords ->
                                                                                    chunkTopYs[gb] = coords.positionInParent().y
                                                                                }
                                                                        ) {
                                                                            NovelInlineImage(
                                                                                path = block.path,
                                                                                onTap = { fullscreenNovelImage = block.path }
                                                                            )
                                                                        }
                                                                    }
                                                                    is com.example.ui.reader.NovelInlineImages.InlineBlock.Text -> {
                                                                        // 文本块：搜索关键词高亮标注（坐标精确，无 token 混杂）
                                                                        val blockAnnotated = buildAnnotatedString {
                                                                            append(block.text)
                                                                            if (searchHighlightChunk == gb && searchHighlightQuery != null && searchHighlightStyle != null) {
                                                                                var at = block.text.indexOf(searchHighlightQuery!!, ignoreCase = true)
                                                                                while (at >= 0) {
                                                                                    addStyle(searchHighlightStyle!!, at, at + searchHighlightQuery!!.length)
                                                                                    at = block.text.indexOf(searchHighlightQuery!!, at + searchHighlightQuery!!.length, ignoreCase = true)
                                                                                }
                                                                            }
                                                                        }
                                                                        BasicText(
                                                                            text = blockAnnotated,
                                                                            onTextLayout = { chunkTextLayouts[gb] = it },
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .onGloballyPositioned { coords ->
                                                                                    chunkTopYs[gb] = coords.positionInParent().y
                                                                                },
                                                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                                                fontSize = fontSize.sp,
                                                                                lineHeight = lineHeight.sp,
                                                                                fontFamily = selectedFontFamily,
                                                                                color = textColor.copy(alpha = 0.92f),
                                                                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                                                                            )
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                        }

                                                    }

                                                }



                                                Spacer(modifier = Modifier.height(20.dp))

                                                Text(

                                                    text = if (currentChapterIndex < chapters.size - 1) {

                                                        "—— 本章完 · 继续上滑进入下一章 ——"

                                                    } else {

                                                        "—— 全书完 ——"

                                                    },

                                                    fontSize = 13.sp,

                                                    color = textColor.copy(alpha = 0.5f),

                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,

                                                    modifier = Modifier.fillMaxWidth()

                                                )

                                            }

                                        } else {

                                            RenderSinglePage(
                                            interactive = false,

                                                pageIndex = activeSubPageIndex,

                                                pageText = pagesList.getOrNull(activeSubPageIndex) ?: "",

                                                chapterTitle = currentChapter?.title,

                                                // 只有当前页的插图注册命中矩形 —— 翻页容器的
                                                // next/prev 层与快速翻页预览层同屏叠放（PageCurl
                                                // 三页同位），全注册会让点击命中前/后一层的图
                                                registerImageHit = true,

                                                bgColor = bgColor,

                                                textColor = textColor,

                                                bodyStyle = bodyTextStyle,

                                                titleStyle = titleStyle,

                                                titleReservePx = currentTitleReservePx,

                                                marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                showBars = showBars,
                                                highlightQuery = searchHighlightQuery,
                                                highlightStyle = searchHighlightStyle,

                                            )

                                        }

                                    }



                                    val nextSlot: @Composable () -> Unit = {

                                        if (!isScrollMode) {

                                            if (activeSubPageIndex < pagesList.size - 1) {

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = activeSubPageIndex + 1,

                                                    pageText = pagesList.getOrNull(activeSubPageIndex + 1) ?: "",

                                                    chapterTitle = currentChapter?.title,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = currentTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            } else if (nextChapter != null) {

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = 0,

                                                    pageText = nextChapterPages.firstOrNull() ?: "",

                                                    chapterTitle = nextChapter.title,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = nextTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            } else {

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = 0,

                                                    pageText = "已经是最后一页了",

                                                    chapterTitle = null,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = currentTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            }

                                        }

                                    }



                                    val prevSlot: @Composable () -> Unit = {

                                        if (!isScrollMode) {

                                            if (activeSubPageIndex > 0) {

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = activeSubPageIndex - 1,

                                                    pageText = pagesList.getOrNull(activeSubPageIndex - 1) ?: "",

                                                    chapterTitle = currentChapter?.title,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = currentTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            } else if (prevChapter != null) {

                                                val lastIdx = (prevChapterPages.size - 1).coerceAtLeast(0)

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = lastIdx,

                                                    pageText = prevChapterPages.getOrNull(lastIdx) ?: "",

                                                    chapterTitle = prevChapter.title,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = prevTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            } else {

                                                RenderSinglePage(
                                                interactive = false,

                                                    pageIndex = 0,

                                                    pageText = "已经是第一页了",

                                                    chapterTitle = null,

                                                    bgColor = bgColor,

                                                    textColor = textColor,

                                                    bodyStyle = bodyTextStyle,

                                                    titleStyle = titleStyle,

                                                    titleReservePx = currentTitleReservePx,

                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },

                                                    showBars = showBars,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle,

                                                )

                                            }

                                        }

                                    }



                                    if (!isScrollMode && pageTurnMode == PageTurnType.SIMULATE.id) {

                                        // C1 pagecurl 引擎（SIMULATE 专用）：三页窗口 + 中央点击唤出菜单

                                        PageCurlReaderContainer(

                                            currentContent = currentSlot,

                                            nextContent = nextSlot,

                                            prevContent = prevSlot,

                                            onNextPage = handleNextPage,

                                            onPrevPage = handlePrevPage,

                                            onClickCenter = { showBars = !showBars },

                                            onToggleBookmark = { toggleBookmark() },

                                            isCurrentBookmarked = bookmarks.any {
                                                (it.bookId == (book?.id ?: 0) || it.bookId == 0) &&
                                                        it.chapterIndex == currentChapterIndex
                                            },

                                            onLongPressCenter = {
                                                if (pagesList.size >= 2) scrubberVisible = true
                                            },

                                            menuVisible = showBars,

                                            // 卷页背面的纸：跟随阅读主题（夜间/OLED 下自动变深），
                                            // 修掉"翻页背面永远是米黄色不透明纯色"的老问题。
                                            paperColor = bgColor,

                                            // 点击位置命中正文插图 → 打开全屏并跳过翻页分派
                                            onImageTapAt = { pos ->
                                                val hitPath = NovelInlineImages.ImageHitRegistry.hit(pos)
                                                if (hitPath != null) {
                                                    fullscreenNovelImage = hitPath
                                                    true
                                                } else {
                                                    // 未命中即清除非当前页残留：旧页注册在第一次
                                                    // 点击后失效，不可能再被二次误命中
                                                    NovelInlineImages.ImageHitRegistry.purgeStale()
                                                    false
                                                }
                                            }

                                        )

                                    } else {

                                        PageTurnContainer(

                                            pageTurnMode = pageTurnMode,

                                            pageKey = "$currentChapterIndex-$activeSubPageIndex",

                                            menuVisible = showBars,

                                            currentContent = currentSlot,

                                            nextContent = nextSlot,

                                            prevContent = prevSlot,

                                            onNextPage = handleNextPage,

                                            onPrevPage = handlePrevPage,

                                            onClickCenter = { showBars = !showBars },

                                            onClickLeft = handlePrevPage,

                                            onClickRight = handleNextPage,

                                            isBookmarked = bookmarks.any { (it.bookId == (book?.id ?: 0) || it.bookId == 0) && it.chapterIndex == currentChapterIndex },

                                            onToggleBookmark = toggleBookmark,

                                            onLongPressCenter = {
                                                if (pagesList.size >= 2) scrubberVisible = true
                                            },

                                            // 点击位置命中正文插图 → 打开全屏并跳过翻页分派
                                            onImageTapAt = { pos ->
                                                val hitPath = NovelInlineImages.ImageHitRegistry.hit(pos)
                                                if (hitPath != null) {
                                                    fullscreenNovelImage = hitPath
                                                    true
                                                } else {
                                                    // 未命中即清除非当前页残留：旧页注册在第一次
                                                    // 点击后失效，不可能再被二次误命中
                                                    NovelInlineImages.ImageHitRegistry.purgeStale()
                                                    false
                                                }
                                            },

                                            // 卷页背面的纸：跟随阅读主题，纸面/厚度高光/描边一并自适应。
                                            paperColor = bgColor
                                        )

                                    }


                                    // 串珠快速翻页：本章页面缩至 75% 排开，滑动掠页（振动+翻纸声），松手跳转
                                    if (!isScrollMode && scrubberVisible && pagesList.size >= 2) {
                                        PageScrubberOverlay(
                                            pageCount = pagesList.size,
                                            initialPage = activeSubPageIndex,
                                            pageContent = { idx ->
                                                RenderSinglePage(
                                                interactive = false,
                                                    pageIndex = idx,
                                                    pageText = pagesList.getOrNull(idx) ?: "",
                                                    chapterTitle = currentChapter?.title,
                                                    bgColor = bgColor,
                                                    textColor = textColor,
                                                    bodyStyle = bodyTextStyle,
                                                    titleStyle = titleStyle,
                                                    titleReservePx = currentTitleReservePx,
                                                    marginHorizontal = marginHorizontal,

                                                    contentWidthPx = textWidthPx.toFloat(),

                                                    maxImageHeightPx = textHeightPx.toFloat(),

                                                    onImageClick = { p -> fullscreenNovelImage = p },
                                                    showBars = false,
                                                    highlightQuery = searchHighlightQuery,
                                                    highlightStyle = searchHighlightStyle
                                                )
                                            },
                                            onDismiss = { scrubberVisible = false },
                                            onPageSelected = { idx -> updateSubPage(idx) }
                                        )
                                    }



                                // 滚动模式：章首/章尾继续拖拽时的切章提示

                                // （方向修正：章尾手指上滑→下一章；章首手指下拉→上一章）

                                androidx.compose.animation.AnimatedVisibility(

                                    visible = isScrollMode && overscrollDirection != 0 && !switchingChapter,

                                    enter = fadeIn(tween(120)) + slideInVertically(

                                        initialOffsetY = { if (overscrollDirection == 1) it / 2 else -it / 2 }

                                    ),

                                    exit = fadeOut(tween(100)),

                                    modifier = Modifier

                                        .align(if (overscrollDirection == 1) Alignment.BottomCenter else Alignment.TopCenter)

                                ) {

                                    val isNext = overscrollDirection == 1

                                    val pullProgress = (overscrollPx / scrollThresholdPx).coerceIn(0f, 1f)

                                    Surface(

                                        shape = RoundedCornerShape(16.dp),

                                        color = MintPrimary.copy(alpha = 0.92f),

                                        shadowElevation = 0.dp,

                                        modifier = Modifier.padding(16.dp)
                                            .consistentShadow(6.dp, RoundedCornerShape(16.dp))

                                    ) {

                                        Column(

                                            horizontalAlignment = Alignment.CenterHorizontally,

                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)

                                        ) {

                                            Row(verticalAlignment = Alignment.CenterVertically) {

                                                Icon(

                                                    imageVector = if (isNext) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,

                                                    contentDescription = null,

                                                    tint = Color.White,

                                                    modifier = Modifier.size(16.dp)

                                                )

                                                Spacer(modifier = Modifier.width(6.dp))

                                                Text(

                                                    text = when {

                                                        pullProgress >= 1f && isNext -> "松开切换下一章"

                                                        pullProgress >= 1f -> "松开返回上一章"

                                                        isNext -> "继续上滑进入下一章"

                                                        else -> "继续下拉返回上一章"

                                                    },

                                                    color = Color.White,

                                                    fontSize = 13.sp,

                                                    fontWeight = FontWeight.Bold

                                                )

                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            // 拉取进度条：跟手填充，拉满 100% 后松手即切章

                                            Box(

                                                modifier = Modifier

                                                    .width(120.dp)

                                                    .height(3.dp)

                                                    .clip(RoundedCornerShape(2.dp))

                                                    .background(Color.White.copy(alpha = 0.3f))

                                            ) {

                                                Box(

                                                    modifier = Modifier

                                                        .fillMaxHeight()

                                                        .fillMaxWidth(pullProgress)

                                                        .background(Color.White.copy(alpha = 0.9f))

                                                )

                                            }

                                        }

                                    }

                                }



                                val isCurrentBookmarked = bookmarks.any { (it.bookId == (book?.id ?: 0) || it.bookId == 0) && it.chapterIndex == currentChapterIndex }

                                androidx.compose.animation.AnimatedVisibility(

                                    visible = isCurrentBookmarked,

                                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),

                                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),

                                    modifier = Modifier

                                        .align(Alignment.TopEnd)

                                        .padding(end = 20.dp)

                                ) {

                                    BookmarkHangingRibbon()

                                }

                            }



                            if (prefs.showOverlayHeaderFooter) {

                                val pct = if (chapters.isNotEmpty()) ((currentChapterIndex + 1).toFloat() / chapters.size * 100).toInt() else 0

                                // 常驻占位版页脚：同页眉原理 —— 菜单开合只改透明度，绝不挤压正文
                                // 此链禁止添加 clickable/pointerInput：alpha=0 不豁免命中测试
                                Row(

                                    modifier = Modifier

                                        .fillMaxWidth()

                                        .padding(horizontal = marginHorizontal.dp, vertical = 6.dp)

                                        // B5：原先 alpha 直接取 0/1，页眉页脚是"瞬间闪没"，
                                        // 与顶/底栏的 slideInVertically 完全不同步。改为透明度过渡。
                                        .animateAlpha(if (showBars) 0f else 1f),

                                    horizontalArrangement = Arrangement.SpaceBetween

                                ) {

                                    val pageProgressText = if (isScrollMode) {

                                        "第 ${currentChapterIndex + 1}/${chapters.size} 章"

                                    } else {

                                        "第 ${activeSubPageIndex + 1}/${pagesList.size} 页 · 第 ${currentChapterIndex + 1}/${chapters.size} 章"

                                    }

                                    Text(pageProgressText, fontSize = 10.sp, color = textColor.copy(alpha = 0.5f))

                                    Text("$pct%", fontSize = 10.sp, color = textColor.copy(alpha = 0.5f))

                                }

                            }

                        }



                        // B5：TTS 播放器原先直接显隐、无任何进出场，
                        // 与其它浮层（目录/设置面板都有动画）观感不一致。
                        AnimatedVisibility(
                            visible = showTtsBar,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = EaseOut)) { it / 3 },
                            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 3 }
                        ) {

                            Card(

                                modifier = Modifier

                                    .padding(16.dp)

                                    .fillMaxWidth()

                                    .consistentShadow(8.dp, RoundedCornerShape(20.dp)),

                                shape = RoundedCornerShape(20.dp),

                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),

                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

                            ) {

                                Column(modifier = Modifier.padding(16.dp)) {

                                    Row(

                                        modifier = Modifier.fillMaxWidth(),

                                        horizontalArrangement = Arrangement.SpaceBetween,

                                        verticalAlignment = Alignment.CenterVertically

                                    ) {

                                        Text("朗读播放器", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                                        AppIconButton(onClick = { showTtsBar = false }) {

                                            Icon(Icons.Filled.Close, contentDescription = "关闭")

                                        }

                                    }



                                    Row(

                                        modifier = Modifier.fillMaxWidth(),

                                        horizontalArrangement = Arrangement.SpaceEvenly,

                                        verticalAlignment = Alignment.CenterVertically

                                    ) {

                                        AppIconButton(onClick = { ttsManager.previousParagraph() }) {

                                            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一段")

                                        }



                                        Box(

                                            modifier = Modifier

                                                .size(56.dp)

                                                .background(MintPrimary, CircleShape)

                                                .clickableWithFeedback {

                                                    if (isTtsPlaying) {

                                                        ttsManager.pause()

                                                    } else {

                                                        currentChapter?.let { ch ->

                                                            ttsManager.startReading(NovelInlineImages.stripTokens(ch.content), speed = prefs.ttsSpeed, pitch = prefs.ttsPitch)

                                                        }

                                                    }

                                                },

                                            contentAlignment = Alignment.Center

                                        ) {

                                            Icon(

                                                if (isTtsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,

                                                contentDescription = "播放暂停",

                                                tint = Color.White

                                            )

                                        }



                                        AppIconButton(onClick = { ttsManager.nextParagraph() }) {

                                            Icon(Icons.Filled.SkipNext, contentDescription = "下一段")

                                        }

                                    }

                                }

                            }

                        }



                        AnimatedVisibility(
                            visible = showReturnChip && previousPosition != null,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                // B5：原 80.dp 是拍脑袋的魔法数字，刘海/挖孔更高的机型
                                // 会把浮层顶到状态栏里。改为真实状态栏高度 + 视觉间距。
                                .statusBarsPadding()
                                .padding(top = 56.dp),
                            enter = fadeIn(tween(240)) + slideInVertically(
                                initialOffsetY = { -it / 2 },
                                animationSpec = tween(280, easing = EaseOut)
                            ),
                            exit = fadeOut(tween(420)) + slideOutVertically(
                                targetOffsetY = { -it / 2 },
                                animationSpec = tween(360, easing = EaseOut)
                            )
                        ) {
                            Surface(
                                modifier = Modifier.clickableWithFeedback {
                                    previousPosition?.let { (ch, offset) ->
                                        currentChapterIndex = ch
                                        scope.launch { scrollState.scrollTo(offset) }
                                    }
                                    showReturnChip = false
                                }
                                    .consistentShadow(6.dp, RoundedCornerShape(20.dp)),
                                shape = RoundedCornerShape(20.dp),
                                color = MintPrimary,
                                shadowElevation = 0.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("返回上次处", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                    }

                }

            }



        // 自动滚屏运行指示器（点击停止）

        androidx.compose.animation.AnimatedVisibility(

            visible = isAutoScrolling,

            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },

            exit = fadeOut(tween(150)),

            // B5：同上，三键导航机型（导航栏 ≈48dp）会把浮层压在导航栏后面
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 56.dp)

        ) {

            Surface(

                onClick = { isAutoScrolling = false },

                shape = RoundedCornerShape(20.dp),

                color = MintPrimary.copy(alpha = 0.9f),

                shadowElevation = 0.dp,

                modifier = Modifier.consistentShadow(4.dp, RoundedCornerShape(20.dp))

            ) {

                Row(

                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))

                    Spacer(modifier = Modifier.width(6.dp))

                    Text("自动滚屏中 · 点击停止", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                }

            }

        }



        // 滚动模式：滚过 20% 时显示"回到顶部"按钮

        androidx.compose.animation.AnimatedVisibility(

            visible = isScrollMode && !isAutoScrolling && scrollState.value > scrollState.maxValue * 0.2f,

            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),

            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),

            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 80.dp)

        ) {

            Surface(

                onClick = { scope.launch { scrollState.animateScrollTo(0) } },

                shape = CircleShape,

                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),

                border = androidx.compose.foundation.BorderStroke(1.dp, MintPrimary.copy(alpha = 0.5f)),

                shadowElevation = 0.dp,

                modifier = Modifier.size(44.dp)
                    .consistentShadow(4.dp, CircleShape)

            ) {

                Box(contentAlignment = Alignment.Center) {

                    Icon(

                        Icons.Filled.KeyboardArrowUp,

                        contentDescription = "回到顶部",

                        tint = MintPrimary,

                        modifier = Modifier.size(22.dp)

                    )

                }

            }

        }



        // 亮度遮罩：夜间阅读降低刺眼感（不拦截触摸，不影响菜单栏）

        // B5：拖亮度滑块时遮罩 alpha 原本逐帧硬跳（拖动过程一卡一卡的），
        // 改为平滑过渡；阈值判断也改用动画值，避免从"无遮罩"到"有遮罩"瞬现。
        val dimAlpha by animateFloatAsState(
            targetValue = (1f - readerBrightness) * 0.6f,
            animationSpec = tween(durationMillis = 160),
            label = "readerDim"
        )
        if (dimAlpha > 0.002f) {

            Box(

                modifier = Modifier

                    .fillMaxSize()

                    .background(Color.Black.copy(alpha = dimAlpha))

            )

        }



        // Reader Overlay (Background Mask)

        AnimatedVisibility(

            visible = showBars,

            enter = fadeIn(tween(300)),

            exit = fadeOut(tween(300)),

            modifier = Modifier.fillMaxSize()

        ) {

            Box(

                modifier = Modifier

                    .fillMaxSize()

                    .background(Color.Black.copy(alpha = 0.6f))

                    // 菜单打开时点击空白处只关闭菜单，绝不触发翻页/切章

                    .clickable(

                        interactionSource = remember { MutableInteractionSource() },

                        indication = null

                    ) {

                        showBars = false

                    }

            )

        }



        // Top and Bottom Bars

        // 栏内文字/图标按栏背景实时取对比色（bgColor.onColor()），

        // 无论选什么阅读主题/自定义背景都不会出现“黑字压黑底”。

        val barContentColor = bgColor.onColor()

        Box(modifier = Modifier.fillMaxSize()) {

            Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {

                AnimatedVisibility(

                                    visible = showBars,

                                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),

                                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()

                                ) {

                                    // ── 定制阅读器顶栏：圆角浮层 + 章节进度 + 主题自适应色 ──

                                    Column(

                                        modifier = Modifier

                                            .fillMaxWidth()

                                            .background(bgColor.copy(alpha = 0.97f))

                                            .drawBehind {

                                                drawLine(

                                                    color = barContentColor.copy(alpha = 0.08f),

                                                    start = Offset(0f, size.height - 0.5f),

                                                    end = Offset(size.width, size.height - 0.5f),

                                                    strokeWidth = 0.8f

                                                )

                                            }

                                            .statusBarsPadding()

                                    ) {

                                        Row(

                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),

                                            verticalAlignment = Alignment.CenterVertically

                                        ) {

                                            AppIconButton(onClick = onBack) {

                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = barContentColor, modifier = Modifier.size(22.dp))

                                            }

                                            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {

                                                Text(

                                                    currentChapter?.title ?: bookTitle,

                                                    maxLines = 1,

                                                    fontWeight = FontWeight.SemiBold,

                                                    fontSize = 16.sp,

                                                    color = barContentColor,

                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis

                                                )

                                                Spacer(modifier = Modifier.height(1.dp))

                                                Text(

                                                    text = "${currentChapterIndex + 1}/${chapters.size} 章",

                                                    fontSize = 11.sp,

                                                    color = barContentColor.copy(alpha = 0.55f)

                                                )

                                            }

                                            val currentBookmarked = bookmarks.any {

                                                (it.bookId == (book?.id ?: 0) || it.bookId == 0) &&

                                                    it.chapterIndex == currentChapterIndex

                                            }

                                            AppIconButton(onClick = { toggleBookmark() }) {

                                                Icon(

                                                    imageVector = if (currentBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,

                                                    contentDescription = "书签",

                                                    tint = if (currentBookmarked) MintGold else barContentColor,

                                                    modifier = Modifier.size(20.dp)

                                                )

                                            }

                                            AppIconButton(onClick = {

                                                showTtsBar = true

                                                if (isAutoScrolling) isAutoScrolling = false // 与 TTS 互斥

                                                if (!isTtsPlaying) {

                                                    currentChapter?.let { ch ->

                                                        ttsManager.startReading(NovelInlineImages.stripTokens(ch.content), speed = prefs.ttsSpeed, pitch = prefs.ttsPitch)

                                                        AppToast.makeText(context, "已开启语音听书", Toast.LENGTH_SHORT).show()

                                                    }

                                                }

                                            }) {

                                                Icon(

                                                    imageVector = if (isTtsPlaying) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Headphones,

                                                    contentDescription = "听书",

                                                    tint = if (isTtsPlaying) MintGold else barContentColor,

                                                    modifier = Modifier.size(20.dp)

                                                )

                                            }

                                            AppIconButton(onClick = { showTocSheet = true }) {
                                                Icon(Icons.Filled.Menu, "目录", tint = barContentColor, modifier = Modifier.size(20.dp))
                                            }

                                            // ── 更多菜单：低频操作收纳，顶栏只留高频 4 键 ──
                                            var showReaderMoreMenu by remember { mutableStateOf(false) }
                                            Box {
                                                AppIconButton(onClick = { showReaderMoreMenu = true }) {
                                                    Icon(Icons.Filled.MoreVert, "更多", tint = barContentColor, modifier = Modifier.size(20.dp))
                                                }
                                                DropdownMenu(
                                                    expanded = showReaderMoreMenu,
                                                    onDismissRequest = { showReaderMoreMenu = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("全文搜索") },
                                                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                                                        onClick = { showReaderMoreMenu = false; showSearchDialog = true }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("书签列表") },
                                                        leadingIcon = { Icon(Icons.Filled.Bookmarks, null, tint = MintPrimary, modifier = Modifier.size(18.dp)) },
                                                        onClick = { showReaderMoreMenu = false; showAnnotationsSheet = true }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(if (isAutoScrolling) "停止自动滚屏" else "自动滚屏") },
                                                        leadingIcon = { Icon(Icons.Filled.UnfoldMore, null, tint = if (isAutoScrolling) MintGold else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                                                        onClick = {
                                                            showReaderMoreMenu = false
                                                            if (!isScrollMode) {
                                                                AppToast.makeText(context, "自动滚屏需切换到滚动模式", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                isAutoScrolling = !isAutoScrolling
                                                                if (isAutoScrolling && isTtsPlaying) ttsManager.pause()
                                                            }
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("排版设置") },
                                                        leadingIcon = { Icon(Icons.Filled.Settings, null, modifier = Modifier.size(18.dp)) },
                                                        onClick = { showReaderMoreMenu = false; showSettingsSheet = true }
                                                    )
                                                }
                                            }

                                        }

                                    }

                                }

            }

            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {

                AnimatedVisibility(

                                    visible = showBars && chapters.isNotEmpty(),

                                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),

                                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()

                                ) {

                                    // ── 定制阅读器底栏：与顶栏同风格 ──

                                    Column(

                                        modifier = Modifier

                                            .fillMaxWidth()

                                            .background(bgColor.copy(alpha = 0.97f))

                                            .drawBehind {

                                                drawLine(

                                                    color = barContentColor.copy(alpha = 0.08f),

                                                    start = Offset(0f, 0f),

                                                    end = Offset(size.width, 0f),

                                                    strokeWidth = 0.8f

                                                )

                                            }

                                            .navigationBarsPadding()

                                    ) {

                                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {

                                            // 章节拖动预览：拖动中只更新显示，松手才真正切章
                                            var dragPos by remember { mutableStateOf<Float?>(null) }
                                            val previewIdx = dragPos?.let {
                                                Math.round(it * (chapters.size - 1).coerceAtLeast(1).toFloat())
                                            }?.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)) ?: currentChapterIndex

                                            Row(verticalAlignment = Alignment.CenterVertically) {

                                                TextButton(

                                                    onClick = {

                                                        if (currentChapterIndex > 0) {

                                                            currentChapterIndex--

                                                            scope.launch { scrollState.scrollTo(0) }

                                                        }

                                                    },

                                                    enabled = currentChapterIndex > 0

                                                ) {

                                                    Text("上一章", fontWeight = FontWeight.Bold, color = barContentColor)

                                                }

                

                                                // 基线原版 FluidSlider 视觉（用户设计零改动）；
                                                // 横向拖动实时预览，松手(onPositionChangeFinished)才真正切章
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        if (!isScrollMode && pagesList.size >= 2) {
                                                            FluidSlider(
                                                                position = dragPos
                                                                    ?: activeSubPageIndex.toFloat() /
                                                                    (pagesList.size - 1).coerceAtLeast(1).toFloat(),
                                                                onPositionChange = { dragPos = it },
                                                                onPositionChangeFinished = {
                                                                    val p = dragPos
                                                                    dragPos = null
                                                                    if (p != null) {
                                                                        val targetPage = Math.round(p * (pagesList.size - 1).coerceAtLeast(1).toFloat())
                                                                            .coerceIn(0, pagesList.size - 1)
                                                                        if (targetPage != activeSubPageIndex) {
                                                                            updateSubPage(targetPage)
                                                                        }
                                                                    }
                                                                },
                                                                bubbleText = "${(dragPos?.let { Math.round(it * (pagesList.size - 1).coerceAtLeast(1)) } ?: activeSubPageIndex) + 1}",
                                                                barHeightDp = 26,
                                                                startText = null,
                                                                endText = null,
                                                                colorBar = barContentColor
                                                            )
                                                        } else if (isScrollMode) {
                                                            FluidSlider(
                                                                position = dragPos
                                                                    ?: scrollState.value.toFloat() / scrollState.maxValue.coerceAtLeast(1),
                                                                onPositionChange = { dragPos = it },
                                                                onPositionChangeFinished = {
                                                                    val p = dragPos
                                                                    dragPos = null
                                                                    if (p != null) {
                                                                        scope.launch {
                                                                            scrollState.scrollTo((p * scrollState.maxValue).toInt())
                                                                        }
                                                                    }
                                                                },
                                                                bubbleText = "${((dragPos ?: (scrollState.value.toFloat() / scrollState.maxValue.coerceAtLeast(1))) * 100).toInt()}%",
                                                                barHeightDp = 26,
                                                                startText = null,
                                                                endText = null,
                                                                colorBar = barContentColor
                                                            )
                                                        } else {
                                                            FluidSlider(
                                                                position = 0f,
                                                                onPositionChange = { },
                                                                onPositionChangeFinished = { },
                                                                bubbleText = "1",
                                                                barHeightDp = 26,
                                                                startText = null,
                                                                endText = null,
                                                                colorBar = barContentColor
                                                            )
                                                        }
                                                    }

                

                                                TextButton(

                                                    onClick = {

                                                        if (currentChapterIndex < chapters.size - 1) {

                                                            currentChapterIndex++

                                                            scope.launch { scrollState.scrollTo(0) }

                                                        }

                                                    },

                                                    enabled = currentChapterIndex < chapters.size - 1

                                                ) {

                                                    Text("下一章", fontWeight = FontWeight.Bold, color = barContentColor)

                                                }

                                            }

                

                                            Row(

                                                modifier = Modifier.fillMaxWidth(),

                                                horizontalArrangement = Arrangement.SpaceBetween,

                                                verticalAlignment = Alignment.CenterVertically

                                            ) {

                                                Text(

                                                    text = if (dragPos != null && !isScrollMode && pagesList.size >= 2) {
                                                        val targetPage = Math.round(dragPos!!.toFloat() * (pagesList.size - 1)).coerceIn(0, pagesList.size - 1)
                                                        "跳转 → 本章第 ${targetPage + 1}/${pagesList.size} 页"
                                                    } else if (dragPos != null && isScrollMode) {
                                                        val frac = dragPos!!
                                                        "跳转 → 本章 ${(frac * 100).toInt()}%"
                                                    } else if (chapters.isNotEmpty()) {
                                                        "第 ${currentChapterIndex + 1} / ${chapters.size} 章 · ${currentChapter?.title.orEmpty()}"
                                                    } else {
                                                        "第 ${currentChapterIndex + 1} / ${chapters.size} 章"
                                                    },
                                                    fontSize = 12.sp,
                                                    color = barContentColor,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)

                                                )

                

                                                FilledTonalButton(

                                                    onClick = {

                                                        showTtsBar = true

                                                        if (isAutoScrolling) isAutoScrolling = false // 与 TTS 互斥

                                                        if (!isTtsPlaying) {

                                                            currentChapter?.let { ch ->

                                                                ttsManager.startReading(NovelInlineImages.stripTokens(ch.content), speed = prefs.ttsSpeed, pitch = prefs.ttsPitch)

                                                                AppToast.makeText(context, "开启语音听书：${ch.title}", Toast.LENGTH_SHORT).show()

                                                            }

                                                        }

                                                    },

                                                    colors = ButtonDefaults.filledTonalButtonColors(

                                                        containerColor = if (isTtsPlaying) MintGold.copy(alpha = 0.25f) else barContentColor.copy(alpha = 0.10f),

                                                        contentColor = if (isTtsPlaying) MintGold else barContentColor

                                                    ),

                                                    shape = RoundedCornerShape(20.dp),

                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)

                                                ) {

                                                    Icon(

                                                        imageVector = if (isTtsPlaying) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Headphones,

                                                        contentDescription = "听书模式",

                                                        modifier = Modifier.size(18.dp)

                                                    )

                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    Text(if (isTtsPlaying) "朗读中..." else "听书模式", fontSize = 13.sp, fontWeight = FontWeight.Bold)

                                                }

                                            }

                                        }

                                    }

                                }

            }

        }



        }

    }



    if (showTocSheet) {

        var tocFilter by remember { mutableStateOf("") }

        val filteredChapters = remember(chapters, tocFilter) {

            if (tocFilter.isBlank()) chapters else chapters.filter { it.title.contains(tocFilter, ignoreCase = true) }

        }

        // 打开目录时自动定位到当前章节

        val tocListState = rememberLazyListState()

        LaunchedEffect(showTocSheet, tocFilter) {

            if (tocFilter.isBlank() && filteredChapters.isNotEmpty()) {

                val targetIdx = filteredChapters.indexOfFirst { it.chapterOrder == currentChapterIndex }

                if (targetIdx > 0) tocListState.scrollToItem(index = targetIdx)

            }

        }



        ModalBottomSheet(

            onDismissRequest = { showTocSheet = false },

            containerColor = MaterialTheme.colorScheme.surface

        ) {

                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("目录 (${chapters.size}章)", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (onCheckNovelUpdate != null) TextButton(onClick = { showTocSheet = false; onCheckNovelUpdate() }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp)); Text("检查更新")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))



                OutlinedTextField(

                    value = tocFilter,

                    onValueChange = { tocFilter = it },

                    placeholder = { Text("搜索章节...") },

                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },

                    singleLine = true,

                    modifier = Modifier.fillMaxWidth()

                )



                Spacer(modifier = Modifier.height(12.dp))



                LazyColumn(state = tocListState) {

                    // 2026-09-21：补 key。animateItemPlacement 依赖 key 追踪元素身份，
                    // 没有 key 时 Compose 只能用下标当身份，筛选/重排时动画会错位。
                    itemsIndexed(
                        filteredChapters,
                        key = { _, chapter -> chapter.chapterOrder }
                    ) { _, chapter ->

                        val index = chapter.chapterOrder

                        TextButton(

                            onClick = {

                                previousPosition = currentChapterIndex to scrollState.value

                                showReturnChip = true

                                currentChapterIndex = index

                                showTocSheet = false

                                scope.launch { scrollState.scrollTo(0) }

                            },

                            modifier = Modifier.animateItemPlacement().fillMaxWidth()

                        ) {

                            Text(

                                text = chapter.title,

                                color = if (index == currentChapterIndex) MintPrimary else MaterialTheme.colorScheme.onSurface,

                                fontWeight = if (index == currentChapterIndex) FontWeight.Bold else FontWeight.Normal,

                                maxLines = 1,

                                modifier = Modifier.fillMaxWidth(),

                                textAlign = androidx.compose.ui.text.style.TextAlign.Start

                            )

                        }

                    }

                }

            }
            }

        }

    }



    if (showSettingsSheet) {

        ModalBottomSheet(

            onDismissRequest = { showSettingsSheet = false },

            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)

        ) {

                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {

                /* ── 头部 ── */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MintPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("阅读排版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        fontSize = 18f
                        lineHeight = 28f
                        marginHorizontal = 16
                        readerBrightness = 1f
                        prefs.fontSize = fontSize
                        prefs.lineHeight = lineHeight
                        prefs.marginHorizontal = marginHorizontal
                        prefs.readerBrightness = readerBrightness
                        AppToast.makeText(context, "排版参数已重置", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("重置", fontWeight = FontWeight.Bold)
                    }
                }

                /* ── 分组：显示（亮度 + 首行缩进）── */
                Text(
                    "显 示",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WbSunny,
                                contentDescription = null,
                                tint = MintGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("亮度", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${(readerBrightness * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MintGold)
                        }
                        Slider(
                            value = ((readerBrightness - 0.2f) / 0.8f).coerceIn(0f, 1f),
                            onValueChange = { readerBrightness = 0.2f + it * 0.8f; prefs.readerBrightness = readerBrightness },
                            colors = SliderDefaults.colors(
                                thumbColor = MintGold,
                                activeTrackColor = MintGold,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("首行缩进", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "段落首行自动空两格",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                            AppSwitch(
                                checked = firstLineIndent,
                                onCheckedChange = { firstLineIndent = it; prefs.firstLineIndent = it }
                            )
                        }
                    }
                }

                /* ── 分组：文字（字号 / 字体 / 行间距 / 页边距）── */
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    "文 字",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        // 字号：A− / 当前 / A+ 紧凑步进器
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("字号", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            val next = (Math.round(fontSize) - 1).coerceAtLeast(12).toFloat()
                                            if (next != fontSize) { fontSize = next; prefs.fontSize = fontSize }
                                        },
                                        enabled = fontSize > 12f,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) { Text("A−", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                    Box(
                                        modifier = Modifier.widthIn(min = 30.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${fontSize.toInt()}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MintPrimary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            val next = (Math.round(fontSize) + 1).coerceAtMost(36).toFloat()
                                            if (next != fontSize) { fontSize = next; prefs.fontSize = fontSize }
                                        },
                                        enabled = fontSize < 36f,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) { Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )

                        // 字体：当前字体一行 + 展开列表（示例文字直接用对应字体渲染）
                        var fontPanelOpen by remember { mutableStateOf(false) }
                        val currentFontName = when (fontFamilyIndex) {
                            1 -> "衬线体"; 2 -> "黑体"; 3 -> "等宽体"
                            4 -> if (customTypeface != null) "自定义字体" else "自定义字体（未导入）"
                            else -> "默认字体"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable { fontPanelOpen = !fontPanelOpen },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("字体", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                currentFontName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MintPrimary,
                                fontFamily = selectedFontFamily,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Icon(
                                if (fontPanelOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (fontPanelOpen) "收起字体列表" else "展开字体列表",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = fontPanelOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // 字体档位表由 AppFonts 提供（id 与 prefs.fontFamilyIndex 一一对应，勿重排）；
                                // 「自定义字体」档在下方单独渲染（要带「+导入」按钮）。
                                AppFonts.readingFontFamilies()
                                    .forEach { (idx, name, family) ->
                                    ReaderFontOptionRow(
                                        name = name,
                                        sample = "永字八法，安静阅读 Aa 123",
                                        family = family,
                                        selected = fontFamilyIndex == idx,
                                        onClick = { fontFamilyIndex = idx; prefs.fontFamilyIndex = idx }
                                    )
                                }
                                val customLoaded = prefs.customFontPath.isNotEmpty() && customTypeface != null
                                ReaderFontOptionRow(
                                    name = "自定义字体",
                                    sample = if (customLoaded) "已导入字体效果预览 Aa" else "选择 TTF 文件后可在此预览",
                                    family = customTypeface?.let { FontFamily(it) } ?: AppFonts.Default,
                                    selected = fontFamilyIndex == 4 && customLoaded,
                                    onClick = {
                                        if (customLoaded) {
                                            fontFamilyIndex = 4; prefs.fontFamilyIndex = 4
                                        } else {
                                            fontFileLauncher.launch("font/ttf")
                                        }
                                    },
                                    trailing = {
                                        TextButton(
                                            onClick = { fontFileLauncher.launch("font/ttf") },
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(
                                                if (customLoaded) "换字体" else "+导入",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MintPrimary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )

                        // 行间距 / 页边距：紧凑滑杆行
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("行间距", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${lineHeight.toInt()} sp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MintPrimary)
                        }
                        Slider(
                            value = ((lineHeight - 20f) / 28f).coerceIn(0f, 1f),
                            onValueChange = { lineHeight = 20f + it * 28f; prefs.lineHeight = lineHeight },
                            colors = SliderDefaults.colors(
                                thumbColor = MintPrimary,
                                activeTrackColor = MintPrimary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("页边距", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${marginHorizontal} dp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MintPrimary)
                        }
                        Slider(
                            value = ((marginHorizontal - 8f) / 40f).coerceIn(0f, 1f),
                            onValueChange = { marginHorizontal = Math.round(8f + it * 40f); prefs.marginHorizontal = marginHorizontal },
                            colors = SliderDefaults.colors(
                                thumbColor = MintPrimary,
                                activeTrackColor = MintPrimary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }

                /* ── 分组：阅读主题（真实底色预览卡）── */
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    "阅读主题",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                // 与阅读区 when(readerTheme) 的真实配色一一对应，选中态用描边+角标
                val themePreviews = listOf(
                    ReaderThemePreview(1, "白底", Color.White, Color(0xFF18191C)),
                    ReaderThemePreview(2, "羊皮", Color(0xFFFBF0D9), Color(0xFF5F4B32)),
                    ReaderThemePreview(3, "夜间", Color(0xFF18191C), Color(0xFFD4D4D4)),
                    ReaderThemePreview(4, "护眼", Color(0xFFE8F5E9), Color(0xFF1B5E20)),
                    ReaderThemePreview(5, "纯黑", Color.Black, Color(0xFFE0E0E0))
                )
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        themePreviews.take(3).forEach { preview ->
                            ReaderThemePreviewCard(
                                preview = preview,
                                selected = readerTheme == preview.id,
                                onClick = { readerTheme = preview.id; prefs.readerTheme = preview.id },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        themePreviews.drop(3).forEach { preview ->
                            ReaderThemePreviewCard(
                                preview = preview,
                                selected = readerTheme == preview.id,
                                onClick = { readerTheme = preview.id; prefs.readerTheme = preview.id },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                /* ── 分组：翻页（选中态明显的紧凑选择卡）── */
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    "翻 页",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageTurnType.entries.take(3).forEach { modeType ->
                            ReaderPageModeChip(
                                label = modeType.title.replace("翻页", "").replace("卷页", "").replace("渐变", ""),
                                selected = pageTurnMode == modeType.id,
                                onClick = { switchPageMode(modeType.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageTurnType.entries.drop(3).forEach { modeType ->
                            ReaderPageModeChip(
                                label = modeType.title.replace("翻页", "").replace("卷页", "").replace("渐变", ""),
                                selected = pageTurnMode == modeType.id,
                                onClick = { switchPageMode(modeType.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
            }

        }

    }



    // 搜索结果点击后的精确跳转：切章完成、新章文本/分页就绪后定位到匹配行（滚动）/匹配页（翻页）。
    // 跳转的 LaunchedEffect 放在 BoxWithConstraints 作用域内（那里才能读到 pagesList / updateSubPage）。

    if (showSearchDialog) {

        AlertDialog(

            onDismissRequest = { showSearchDialog = false },

            title = { Text("全文搜索") },

            text = {

                Column {

                    OutlinedTextField(

                        value = searchKeyword,

                        onValueChange = {

                            searchKeyword = it

                            onSearch(it)

                        },

                        placeholder = { Text("输入关键词...") },

                        singleLine = true,

                        modifier = Modifier.fillMaxWidth()

                    )



                    Spacer(modifier = Modifier.height(12.dp))



                    if (isSearching) {

                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

                    } else if (searchResults.isEmpty() && searchKeyword.isNotBlank()) {

                        Text("未找到匹配", color = MaterialTheme.colorScheme.onSurfaceVariant)

                    } else {

                        LazyColumn(modifier = Modifier.height(240.dp)) {

                            // key 不能用 chapterIndex：大章拆出的"续N"物理章合并回同一逻辑章后，
                            // 多条结果 logicalIndex 相同 → LazyColumn "Key was already used" 直接闪退
                            itemsIndexed(
                                searchResults,
                                key = { index, _ -> index }
                            ) { _, item ->

                                Card(

                                    modifier = Modifier

                                        .animateItemPlacement()

                                        .fillMaxWidth()

                                        .padding(vertical = 4.dp)

                                        .clickableWithFeedback {

                                            previousPosition = currentChapterIndex to scrollState.value

                                            showReturnChip = true

                                            currentChapterIndex = item.chapterIndex

                                            // 记下关键词并携带精确偏移跳转：等新章文本/分页就绪后
                                            // 定位到匹配位置并高亮（见 BoxWithConstraints 内的 LaunchedEffect）
                                            searchHighlightQuery = searchKeyword

                                            pendingSearchJump = item

                                            showSearchDialog = false

                                        },

                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

                                ) {

                                    Column(modifier = Modifier.padding(8.dp)) {

                                        Text(item.chapterTitle, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MintPrimary)

                                        // 预览里高亮关键词（大小写不敏感的首处匹配）；颜色在组合期取好再进 remember
                                        val accentColor = MintPrimary

                                        val highlightedSnippet = remember(item.snippet, searchKeyword) {
                                            buildAnnotatedString {
                                                append(item.snippet)
                                                if (searchKeyword.isNotBlank()) {
                                                    val p = item.snippet.indexOf(searchKeyword, ignoreCase = true)
                                                    if (p >= 0) {
                                                        addStyle(
                                                            SpanStyle(color = accentColor, fontWeight = FontWeight.Bold),
                                                            p,
                                                            p + searchKeyword.length
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Text(highlightedSnippet, fontSize = 11.sp, maxLines = 2)

                                    }

                                }

                            }

                        }

                    }

                }

            },

            confirmButton = {

                TextButton(onClick = { showSearchDialog = false }) {

                    Text("关闭")

                }

            }

        )

    }



    if (showAnnotationsSheet) {

        ModalBottomSheet(

            onDismissRequest = { showAnnotationsSheet = false },

            containerColor = MaterialTheme.colorScheme.surface

        ) {

                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {

                Text("书签记录 (${bookmarks.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(12.dp))



                if (bookmarks.isEmpty()) {

                    Text("无书签", color = MaterialTheme.colorScheme.onSurfaceVariant)

                } else {

                    LazyColumn(modifier = Modifier.height(280.dp)) {

                        // 书签会增删，key 用数据库主键：删除某条后其余条目平滑上移，
                        // 而不是整列瞬间跳位。
                        itemsIndexed(bookmarks, key = { _, bm -> bm.id }) { _, bm ->

                            Row(

                                modifier = Modifier

                                    .animateItemPlacement()

                                    .fillMaxWidth()

                                    .clickableWithFeedback {

                                        previousPosition = currentChapterIndex to scrollState.value

                                        showReturnChip = true

                                        currentChapterIndex = bm.chapterIndex

                                        showAnnotationsSheet = false

                                        scope.launch { scrollState.scrollTo(bm.scrollOffset) }

                                    }

                                    .padding(vertical = 8.dp),

                                horizontalArrangement = Arrangement.SpaceBetween,

                                verticalAlignment = Alignment.CenterVertically

                            ) {

                                Column(modifier = Modifier.weight(1f)) {

                                    Text(bm.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                                    Text(bm.snippet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

                                }

                                AppIconButton(onClick = { onDeleteBookmark(bm.id) }) {

                                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)

                                }

                            }

                        }

                    }

                }

            }
            }

        }

    }



    AnimatedVisibility(

        visible = showRestDialog,

        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),

        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),

        modifier = Modifier

            .fillMaxWidth()

            .padding(16.dp)

            .statusBarsPadding()

    ) {

        val infiniteTransition = rememberInfiniteTransition(label = "glow")

        val glowAlpha by infiniteTransition.animateFloat(

            initialValue = 0.4f,

            targetValue = 0.9f,

            animationSpec = infiniteRepeatable(

                animation = tween(1500, easing = LinearEasing),

                repeatMode = RepeatMode.Reverse

            ),

            label = "glowAlpha"

        )

        Surface(

            modifier = Modifier

                .fillMaxWidth()

                .consistentShadow(12.dp, RoundedCornerShape(16.dp))

                .border(2.dp, MintPrimary.copy(alpha = glowAlpha), RoundedCornerShape(16.dp)),

            shape = RoundedCornerShape(16.dp),

            color = MaterialTheme.colorScheme.surface

        ) {

            Row(

                modifier = Modifier.padding(16.dp),

                verticalAlignment = Alignment.CenterVertically,

                horizontalArrangement = Arrangement.SpaceBetween

            ) {

                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {

                    Icon(

                        imageVector = Icons.Filled.Timer,

                        contentDescription = null,

                        tint = MintPrimary,

                        modifier = Modifier.size(28.dp)

                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {

                        Text(

                            text = "定时休息提醒",

                            fontWeight = FontWeight.Bold,

                            fontSize = 15.sp,

                            color = MaterialTheme.colorScheme.onSurface

                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(

                            text = "已持续阅读 ${prefs.restReminderMinutes} 分钟，建议远眺片刻！",

                            fontSize = 13.sp,

                            color = MaterialTheme.colorScheme.onSurfaceVariant

                        )

                    }

                }

                Spacer(modifier = Modifier.width(8.dp))

                AppActionButton(

                    text = "好的",

                    onClick = { showRestDialog = false },

                    variant = AppButtonVariant.Secondary,

                    buttonSize = AppButtonSize.Small

                )

            }

        }

    }



    // Cute Anime Easter Egg UI Overlay

    AnimatedVisibility(

        visible = showEasterEgg,

        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(animationSpec = tween(400, easing = EaseOut)),

        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(animationSpec = tween(400, easing = EaseOut)),

        modifier = Modifier.fillMaxSize()

    ) {

        Box(modifier = Modifier.fillMaxSize()) {

            Image(

                painter = painterResource(id = MascotSpriteSheet.celebrateDrawable),

                contentDescription = "Anime Mascot Easter Egg",

                modifier = Modifier

                    .align(Alignment.BottomEnd)

                    .padding(end = 24.dp, bottom = 48.dp)

                    .size(160.dp),

                contentScale = ContentScale.Inside

            )

            // Speech bubble

            Surface(

                shape = RoundedCornerShape(16.dp),

                color = MaterialTheme.colorScheme.primaryContainer,

                modifier = Modifier

                    .align(Alignment.BottomEnd)

                    .padding(end = 120.dp, bottom = 160.dp)

                    .consistentShadow(8.dp, RoundedCornerShape(16.dp))

            ) {

                Text(

                    text = "Ciallo～(∠・ω< )⌒★",

                    modifier = Modifier.padding(16.dp, 8.dp),

                    color = MaterialTheme.colorScheme.onPrimaryContainer,

                    fontWeight = FontWeight.Bold,

                    fontSize = 14.sp

                )

            }

        }

    }

}



@Composable

private fun BookmarkHangingRibbon(

    modifier: Modifier = Modifier

) {

    Canvas(

        modifier = modifier

            .width(20.dp)

            .height(34.dp)

            .graphicsLayer {

                shadowElevation = 6f

            }

    ) {

        val w = size.width

        val h = size.height



        val ribbonPath = Path().apply {

            moveTo(0f, 0f)

            lineTo(w, 0f)

            lineTo(w, h)

            lineTo(w / 2f, h - 8.dp.toPx()) // Triangular V-notch

            lineTo(0f, h)

            close()

        }



        drawPath(

            path = ribbonPath,

            brush = Brush.verticalGradient(

                colors = listOf(

                    MintGold,

                    Color(0xFFFFB300),

                    Color(0xFFE65100)

                )

            )

        )



        drawPath(

            path = ribbonPath,

            color = Color.White.copy(alpha = 0.6f),

            style = Stroke(width = 1.dp.toPx())

        )

    }

}





/** 首行缩进格式化（与旧逻辑一致）。 */

private fun formatForReader(text: String, firstLineIndent: Boolean): String {

    return if (firstLineIndent) {

        text.split("\n").joinToString("\n") { line ->

            if (line.isNotBlank() && !line.startsWith("\u3000\u3000")) "\u3000\u3000$line" else line

        }

    } else {

        text

    }

}



/** 把超大章节切成不超过 maxChars 的文本块（优先在换行处切），保持滚动位置语义不变。 */

private fun chunkForScroll(content: String, maxChars: Int = 40000): List<String> {

    if (content.length <= maxChars) return listOf(content)

    val chunks = mutableListOf<String>()

    var start = 0

    while (start < content.length) {

        val end = minOf(content.length, start + maxChars)

        if (end < content.length) {

            val newline = content.lastIndexOf('\n', end)

            if (newline > start + maxChars / 2) {

                chunks.add(content.substring(start, newline))

                start = newline + 1

                continue

            }

        }

        chunks.add(content.substring(start, end))

        start = end

    }

    return chunks

}



/**

 * Measures how much vertical space the chapter title occupies on the first page so the

 * pagination and RenderSinglePage agree on the same first-page height. Returns 0 when

 * there is no title, and is capped so a very long title can never starve the body text.

 */

private fun measureTitleReservePx(

    title: String?,

    widthPx: Int,

    maxHeightPx: Int,

    titleStyle: TextStyle,

    density: androidx.compose.ui.unit.Density,

    fontFamilyResolver: androidx.compose.ui.text.font.FontFamily.Resolver

): Int {

    if (title.isNullOrEmpty() || widthPx <= 0) return 0

    val fontSizePx = with(density) { titleStyle.fontSize.toPx() }.coerceAtLeast(8f)

    val lineHeightPx = with(density) { titleStyle.lineHeight.toPx() }.coerceAtLeast(fontSizePx * 1.2f)

    val style = titleStyle.copy(

        fontSize = TextUnit(fontSizePx, TextUnitType.Sp),

        lineHeight = TextUnit(lineHeightPx, TextUnitType.Sp)

    )

    val paragraph = Paragraph(

        text = title,

        style = style,

        constraints = Constraints(maxWidth = widthPx),

        density = density,

        fontFamilyResolver = fontFamilyResolver

    )

    val titleHeight = if (paragraph.lineCount == 0) {

        lineHeightPx

    } else {

        paragraph.getLineBottom(paragraph.lineCount - 1) - paragraph.getLineTop(0)

    }.toInt()

    val blockPaddingPx = with(density) { TITLE_BLOCK_PADDING_DP.dp.toPx().toInt() }

    return (titleHeight + blockPaddingPx).coerceIn(0, (maxHeightPx * 0.4f).toInt().coerceAtLeast(60))

}



@Composable

private fun RenderSinglePage(

    pageIndex: Int,

    pageText: String,

    chapterTitle: String?,

    bgColor: Color,

    contentWidthPx: Float,

    maxImageHeightPx: Float,

    onImageClick: (String) -> Unit,

    textColor: Color,

    bodyStyle: TextStyle,

    titleStyle: TextStyle,

    titleReservePx: Int,

    marginHorizontal: Int,

    showBars: Boolean,

    // 是否把本页插图注册进命中表（仅当前页；next/prev/scrubber 层不注册，
    // 否则 PageCurl 同屏叠放的多层会互相干扰，点击打开前面某层的图）
    registerImageHit: Boolean = false,
    // 本页插图是否可点击（仅当前页；PageCurl 把 prev 页组合在最顶层，其
    // clickable 会拦截所有点击 —— 非当前层必须禁用让触摸穿透）
    interactive: Boolean = true,
    highlightQuery: String? = null,
    highlightStyle: SpanStyle? = null

) {

    // 本页成为"当前页"时更新命中表的页标识：翻页后残留矩形（引擎保留的旧层）
    // 自动失效 —— 修复"图片下一页相同位置点击仍打开前面那张图"
    if (registerImageHit) {
        NovelInlineImages.ImageHitRegistry.setActivePage(pageText)
    }

    Column(

        modifier = Modifier

            .fillMaxSize()

            .background(bgColor)

            .padding(horizontal = marginHorizontal.dp, vertical = PAGE_VERTICAL_PADDING_DP.dp)

    ) {

        if (pageIndex == 0 && !chapterTitle.isNullOrEmpty()) {

            Box(

                modifier = Modifier

                    .fillMaxWidth()

                    .height(with(LocalDensity.current) { titleReservePx.toDp() })

                    .clipToBounds()

            ) {

                Text(

                    text = chapterTitle,

                    style = titleStyle,

                    modifier = Modifier

                        .fillMaxSize()

                        .clipToBounds()

                )

            }

        }



        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {

            // 块驱动渲染：文本块用 BasicText（含搜索高亮标注），图片块按长宽高
            // 独立占空间（宽=内容宽、高=按长宽比收敛）—— 不再走文本流内联占位，
            // 彻底避开占位失效导致的叠绘 / novel_img_N 字面 / 点击命中错位
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = PAGE_TEXT_BOTTOM_PADDING_DP.dp)
                    .clipToBounds()
            ) {
                val blockDensity = LocalDensity.current

                fun annotatedBlock(text: String) = buildAnnotatedString {
                    append(text)
                    if (!highlightQuery.isNullOrBlank() && highlightStyle != null) {
                        var at = text.indexOf(highlightQuery, ignoreCase = true)
                        while (at >= 0) {
                            addStyle(highlightStyle, at, at + highlightQuery.length)
                            at = text.indexOf(highlightQuery, at + highlightQuery.length, ignoreCase = true)
                        }
                    }
                }

                NovelInlineImages.splitIntoBlocks(pageText, contentWidthPx, maxImageHeightPx).forEachIndexed { blockIndex, block ->
                    when (block) {
                        is com.example.ui.reader.NovelInlineImages.InlineBlock.Image -> {
                            if (registerImageHit) {
                                // 注册真实屏幕矩形：翻页手势宿主按位置命中插图（点击打开全屏而非翻页）。
                                // id 用稳定随机串 —— 同一张图在正文多处引用/多层叠放时互不覆盖
                                val regId = remember(pageText, blockIndex) {
                                    "img_${blockIndex}_${java.util.UUID.randomUUID()}"
                                }
                                androidx.compose.runtime.DisposableEffect(regId) {
                                    onDispose { NovelInlineImages.ImageHitRegistry.unregister(regId) }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(blockDensity) { block.heightPx.toDp() })
                                        .clipToBounds()
                                        .onGloballyPositioned { coords ->
                                            NovelInlineImages.ImageHitRegistry.register(
                                                regId, block.path, coords.boundsInWindow(), pageText
                                            )
                                        }
                                ) {
                                    NovelInlineImage(path = block.path, onTap = { onImageClick(block.path) }, enabled = interactive)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(blockDensity) { block.heightPx.toDp() })
                                        .clipToBounds()
                                ) {
                                    NovelInlineImage(path = block.path, onTap = { onImageClick(block.path) }, enabled = interactive)
                                }
                            }
                        }
                        is com.example.ui.reader.NovelInlineImages.InlineBlock.Text -> {
                            BasicText(
                                text = annotatedBlock(block.text),
                                style = bodyStyle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                            )
                        }
                    }
                }
            }

        }

    }

}

/** 阅读排版面板：主题预览数据（id 与阅读区 readerTheme 配置一致）。 */
private data class ReaderThemePreview(val id: Int, val name: String, val bg: Color, val fg: Color)

/**
 * 阅读排版面板：主题预览卡。
 * 直接用该主题的真实底色/文字色渲染一小段中文示例，选中态为克制的主色描边 + 角标。
 */
@Composable
private fun ReaderThemePreviewCard(
    preview: ReaderThemePreview,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = preview.bg,
        border = if (selected) {
            BorderStroke(2.dp, MintPrimary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        },
        modifier = modifier.height(66.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(
                "永远相信美好的\n事情正在发生",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = preview.fg,
                maxLines = 2,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                preview.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = preview.fg.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomStart)
            )
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已选择",
                    tint = MintPrimary,
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}

/**
 * 阅读排版面板：翻页方式选择卡。
 * 选中态 = 主色淡底 + 主色描边 + 勾选角标，未选中为低对比中性底，整行紧凑。
 */
@Composable
private fun ReaderPageModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MintPrimary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = if (selected) BorderStroke(1.dp, MintPrimary.copy(alpha = 0.8f)) else null,
        modifier = modifier.heightIn(min = 40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MintPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * 阅读排版面板：字体候选项行。
 * 名称与示例文字都用目标字体渲染，用户选中前即可直览字体效果。
 */
@Composable
private fun ReaderFontOptionRow(
    name: String,
    sample: String,
    family: FontFamily,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MintPrimary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = family)
            Text(
                sample,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = family,
                maxLines = 1
            )
        }
        trailing?.invoke()
    }
}
