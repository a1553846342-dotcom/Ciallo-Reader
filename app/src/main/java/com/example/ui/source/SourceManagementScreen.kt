@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.ui.source

import android.net.Uri
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.example.library.LibraryLoginDialog
import com.example.library.ZLibraryNodeConfig
import com.example.source.BookSource
import com.example.source.LoginCredential
import com.example.source.SourceResult
import com.example.source.SourceViewModel
import com.example.source.importer.SourceImporter
import com.example.source.isNovelSource
import com.example.source.isComicSource
import com.example.source.zlibrary.ZLibrarySource
import com.example.ui.components.AcrylicDialog
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppSwitch
import com.example.ui.components.GradientActionButton
import com.example.ui.components.SourceAvatar
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.glasskit.GlassCardShape
import com.example.ui.glasskit.GlassKitCard
import com.example.ui.glasskit.GlassKitHost
import com.example.ui.glasskit.GlassTokens
import com.example.ui.glasskit.GlassTopBarStrip
import com.example.ui.shelf.pressScale
import com.example.ui.theme.AppFonts
import com.example.ui.theme.LocalAppBottomInset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AppToast

/* ══════════════════════════════════════════════════════════════════════════
 * 书源管理 · 设计令牌（2026-09-24 重排版）
 *
 * 圆角：页面上**只允许三种** —— 玻璃卡 24 / 小元素（头像·图标底）12·10 / 胶囊全圆。
 *      玻璃卡一律 24，禁止药丸形、禁止 >28。
 * 间距：页面左右 16、卡片之间 12、卡片内左右 16。
 * 字号：大标题 28 → 折叠 20；分组标题 14 semi-bold；条目名 15 semi-bold；
 *      快捷入口名 13；ID / 状态 11；小胶囊 12；导入行 14。
 * 文字：主文字 onSurface 100%；次要文字（ID / 状态）onSurface **70%**（下限）；
 *      标签文字 = primary 同色系加深 20%，底 = primary 12%。
 * 可读：本页是功能页 —— 壁纸先经 sigma≈16 模糊 + 全屏遮罩（亮 50% 白 / 深 52% 黑），
 *      再叠一层顶部渐变（背景色 85% → 0）保护大标题与返回箭头。
 *      除大标题外，页面上没有任何文字直接落在壁纸上。
 * ══════════════════════════════════════════════════════════════════════════ */

/* ── 圆角 ────────────────────────────────────────────────────────────── */
private val SourceAvatarRadius = 12.dp        // 书源行头像
private val SourceIconRadius = 10.dp          // 快捷入口的淡染图标底
private val SourceImportRadius = 20.dp        // D 导入行（虚线）
private val PillShape: Shape = RoundedCornerShape(50)

/* ── 规范硬性尺寸 ────────────────────────────────────────────────────── */
private val SourcePageMargin = 16.dp          // 页面左右边距
private val SourceCardGap = 12.dp             // 卡片垂直间距
private val SourceHeaderHeight = 48.dp        // 分组卡头部
private val SourceRowHeight = 60.dp           // 书源行
private val SourceAvatarSize = 36.dp          // 行头像
/** 行分割线的左缩进：卡片内边距 16 + 头像 36 + 头像与文字间距 12 = 64（对齐文字起点）。 */
private val SourceRowDividerIndent = 64.dp
private val SourceQuickCardMinHeight = 84.dp  // A 快捷入口卡（minHeight，内容撑开）
private val SourceQuickIconBox = 40.dp        // A 卡内的淡染圆角方形底
private val SourceQuickIconSize = 28.dp       // A 卡内的图标
private val SourceChipHeight = 26.dp          // 「登录 / 节点」小胶囊
private val SourceExpandRowHeight = 44.dp     // 「展开其余 N 个」
private val SourceImportRowHeight = 48.dp     // D 导入行
private val SourceDividerWidth = 0.5.dp       // 细分割线（卡内）
/** Material3 LargeTopAppBar 展开态高度（用于顶部渐变遮罩的高度计算）。 */
private val SourceLargeTitleHeight = 152.dp
/** 页面级壁纸模糊半径（sigma ≈ 16，功能页规格：壁纸只留氛围色、看不出线条）。 */

private const val TITLE_EXPANDED_SP = 28f
private const val TITLE_COLLAPSED_SP = 20f

/** 漫画组折叠态默认露出前 N 个，其余走「展开其余 N 个」。 */
private const val COMIC_GROUP_PREVIEW_COUNT = 6

/** 表格数字对齐：`tnum` 让 0-9 等宽，X 由 9 变 10 时后面的文字不会横向抖一下。 */
private const val NUMERIC_FEATURE = "tnum"

/** 「分组标题」14sp 对应的 0.02em 字距（标题越大字距越小，避免松散）。 */
private val GROUP_LETTER_SPACING = (14 * 0.02f).sp

/**
 * 名称样式（15sp / 行高 20）。
 *
 * ⚠️ `includeFontPadding = false` + 居中行高是**修复 ID 被裁掉半截的关键**：
 * 默认的 includeFontPadding 会在小字号上下各塞 2~3dp 的字体内边距，
 * 行高一写死就被这段 padding 顶掉，等宽 ID 的下半截正好落在可视区外。
 */
private val SourceNameStyle = TextStyle(
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeight = 20.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/** ID 样式（11sp 等宽 / 行高 15）：同上，去字体 padding、行高居中。 */
private val SourceIdStyle = TextStyle(
    fontFamily = AppFonts.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/* ── 文字层级（本页唯一入口，禁止各写各的 alpha）───────────────────── */

/** 主文字：onSurface 100%。 */
@Composable
private fun primaryText(): Color = MaterialTheme.colorScheme.onSurface

/** 次要文字（ID / 状态）：onSurface 70% —— 规范下限，任何地方都不得低于此值。 */
@Composable
private fun secondaryText(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)

/**
 * 标签文字：`primary` **同色系加深 20%**（HSL 明度 ×0.8）。
 *
 * 不用 `lerp(primary, Black, .2f)` —— 那会顺带把色相往灰里拖，浅色主题下标签会发脏；
 * 只压明度能保持"同一个色的深色版"，与 12% 的淡染底配对后正文对比度 ≥4.5:1。
 */
@Composable
private fun accentText(): Color {
    val primary = MaterialTheme.colorScheme.primary
    return remember(primary) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(primary.toArgb(), hsl)
        hsl[2] = (hsl[2] * 0.8f).coerceIn(0f, 1f)
        Color(ColorUtils.HSLToColor(hsl))
    }
}

/** 标签 / 徽标 / 小胶囊的底色：`primary` 12%（规范值）。 */
@Composable
private fun accentContainer(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

/** 卡内分割线：onSurface 10%。 */
@Composable
private fun dividerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

/* ══════════════════════════════════════════════════════════════════════════
 * ② 页面骨架
 * ══════════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SourceManagementScreen(
    viewModel: SourceViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allSources by viewModel.allSources.collectAsStateWithLifecycle()
    val activeSource by viewModel.activeSource.collectAsStateWithLifecycle()
    val enabledStates by viewModel.enabledStates.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()

    var showPasteDialog by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var showImportPicker by remember { mutableStateOf(false) }
    var loginSource by remember { mutableStateOf<BookSource?>(null) }
    var showNodeManagement by remember { mutableStateOf(false) }
    var pasteJsonText by remember { mutableStateOf("") }
    var networkUrl by remember { mutableStateOf("") }
    var showDebugLog by remember { mutableStateOf(false) }
    var debugLogText by remember { mutableStateOf("") }

    if (showNodeManagement) {
        ZLibraryNodeManagementScreen(onBack = { showNodeManagement = false })
        return
    }

    // 书源管理进入动画：与登录卡片一致的 Q 弹缩放 + 上滑 + 淡入
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }

    // 登录状态检测：负责提示用户进入书库前是否已配置账号
    var loggedIn by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var loginMessage by remember { mutableStateOf("") }
    var loginLoading by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }

    val zlibSource = allSources.firstOrNull { it.id == "zlibrary" } as? ZLibrarySource

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit, zlibSource) {
        val cookies = CookieManager.getInstance().getCookie("https://${ZLibraryNodeConfig.domain}/") ?: ""
        loggedIn = cookies.contains("remix_userid") || cookies.contains("remix_userkey") ||
            zlibSource?.credentialStorage?.isLoggedIn() == true
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importSourceFromUri(it)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // 登记为全局提示宿主（本页 Toast 已迁移到 AppToast；宿主离开组合时自动解绑回退到系统 Toast）
    com.example.ui.components.BindAppToastHost(snackbarHostState)

    LaunchedEffect(importStatus) {
        importStatus?.let {
            if (it.contains("失败") || it.contains("错误")) {
                snackbarHostState.showSnackbar(
                    message = it,
                    duration = SnackbarDuration.Short
                )
            } else {
                AppToast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
            viewModel.clearImportStatus()
        }
    }

    // 显式标注类型：`allSources` 是 StateFlow 里的 List<BookSource>，但一旦这里靠推断，
    // 后面任何一处写法改变都会把类型错误一路传染到几十行之外（.size / ?.id 全部误报）。
    val builtinSources: List<BookSource> = allSources.filter { !viewModel.isCustomSource(it.id) }
    val customSources: List<BookSource> = allSources.filter { viewModel.isCustomSource(it.id) }

    // 按类型分组：小说 / 漫画 / 自定义。空组不出现，避免无意义的空玻璃卡。
    // 标题即卡头文案（分组摘要「已启用 X / Y」已并入卡头徽标，页面上不再有裸文字行）。
    val groups: List<SourceGroup> = remember(builtinSources, customSources) {
        buildList {
            val novels = builtinSources.filter { it.isNovelSource }
            val comics = builtinSources.filter { it.isComicSource }
            val others = builtinSources.filter { !it.isNovelSource && !it.isComicSource }
            if (novels.isNotEmpty()) add(SourceGroup("小说", SourceGroupKind.NOVEL, novels))
            if (comics.isNotEmpty()) add(SourceGroup("漫画", SourceGroupKind.COMIC, comics))
            if (others.isNotEmpty()) add(SourceGroup("其他", SourceGroupKind.OTHER, others))
            if (customSources.isNotEmpty()) {
                add(SourceGroup("自定义", SourceGroupKind.CUSTOM, customSources))
            }
        }
    }

    // 折叠 / 「展开其余」状态：以组标题为 key，旋转屏幕后保留
    var collapsedGroupKeys by remember { mutableStateOf(emptySet<String>()) }
    var expandedGroupKeys by remember { mutableStateOf(emptySet<String>()) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    AnimatedVisibility(
        visible = contentVisible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(260)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) +
            slideInVertically(
                initialOffsetY = { it / 12 },
                animationSpec = tween(280)
            )
    ) {
        // ── 可读性由 GlassKitHost 一次提供 ──────────────────────────────
        // ① 壁纸采样模糊 + 全屏遮罩、② 顶部渐变，都画在**被录制的层**里；
        //    因此玻璃卡采样到的是「已压暗、已保护标题」的背景，而不是原始壁纸；
        // ③ 页面内容作为这层的**兄弟节点**画在上层（放进录制层会采样到自己 → 递归）。
        GlassKitHost(
            topGradientHeight = statusBarTop + SourceLargeTitleHeight + 24.dp
        ) {
            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                // ⚠️ M3 Scaffold 默认 containerColor = colorScheme.surface（**不透明**），
                // 会把 MainActivity 根层那张全局背景（colorScheme.background + 壁纸图）
                // 整个盖住 → 书源管理页看起来就不是主界面的背景。设为透明后背景自动跟随主界面
                // （换壁纸/调暗度也会实时跟上，因为背景画在这一层之下）。
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        // 按语义挑皮肤（与主页 SnackbarHost 同一规则）：本页 Toast 迁过来后，
                        // 中性提示不能再一律套红色「操作出错」卡。
                        val kind = (data.visuals as? com.example.ui.components.AppSnackbarVisuals)
                            ?.kind ?: com.example.ui.components.AppSnackKind.ERROR
                        when (kind) {
                            com.example.ui.components.AppSnackKind.ERROR ->
                                com.example.ui.components.AppErrorSnackbar(
                                    message = data.visuals.message,
                                    onDismissClick = { data.dismiss() }
                                )
                            com.example.ui.components.AppSnackKind.TOAST ->
                                com.example.ui.components.AppToastSnackbar(
                                    message = data.visuals.message,
                                    actionLabel = data.visuals.actionLabel,
                                    onActionClick = { data.performAction() },
                                    onDismissClick = { data.dismiss() }
                                )
                        }
                    }
                },
                topBar = {
                    Box {
                        // 折叠顶栏：blur(8) + 页面背景色 60%，随 collapsedFraction 淡入，
                        // 底边一条 0.5dp 线 —— 顶栏区域不会是实色块，也没有硬切边
                        GlassTopBarStrip(
                            collapsedFraction = scrollBehavior.state.collapsedFraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(statusBarTop + GlassTokens.TopBarHeight)
                        )
                        SourceCollapsingTopBar(
                            scrollBehavior = scrollBehavior,
                            onBack = onBack,
                        )
                    }
                },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = SourcePageMargin)
                        .then(
                            if (showLoginDialog) Modifier.haze(hazeState) else Modifier
                        ),
                    verticalArrangement = Arrangement.spacedBy(SourceCardGap),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        // D 导入行之后：底部安全区（含悬浮 Tab 栏与系统导航栏）+ 24
                        bottom = LocalAppBottomInset.current + 24.dp
                    )
                ) {
                    // ── A 快捷入口卡：Z-Library 账号 / Venera 源仓库 / 调试日志 ──
                    item(key = "quick_tiles") {
                        SourceQuickTilesRow(
                            hasZLibrary = zlibSource != null,
                            loggedIn = loggedIn,
                            onLoginClick = {
                                loginMessage = ""
                                showLoginDialog = true
                            },
                            onRefreshVenera = { viewModel.refreshJsSources() },
                            onOpenDebugLog = {
                                debugLogText = com.example.source.SourceLog.dump()
                                showDebugLog = true
                            }
                        )
                    }

                    // ── B / C（+ 自定义）分组：每张卡 = 头部 + 行列表 ──
                    groups.forEach { group ->
                        item(key = "body_${group.title}") {
                            SourceGroupCard(
                                group = group,
                                expanded = group.title !in collapsedGroupKeys,
                                showAll = group.title in expandedGroupKeys,
                                onToggleExpand = {
                                    collapsedGroupKeys = if (group.title in collapsedGroupKeys) {
                                        collapsedGroupKeys - group.title
                                    } else {
                                        collapsedGroupKeys + group.title
                                    }
                                },
                                onToggleShowAll = {
                                    expandedGroupKeys = if (group.title in expandedGroupKeys) {
                                        expandedGroupKeys - group.title
                                    } else {
                                        expandedGroupKeys + group.title
                                    }
                                },
                                activeSourceId = activeSource?.id,
                                enabledStates = enabledStates,
                                onToggleEnable = { source, enabled ->
                                    if (enabled) viewModel.enableSource(source.id)
                                    else viewModel.disableSource(source.id)
                                },
                                onOpenLogin = { source ->
                                    loginSource = source
                                },
                                onOpenNodeManager = { showNodeManagement = true },
                                onDelete = { source -> viewModel.removeSource(source.id) }
                            )
                        }
                    }

                    // ── D 导入行：虚线胶囊「＋ 导入 JSON 书源」 ──
                    item(key = "import_row") {
                        SourceImportEntryRow(
                            onClick = { showImportPicker = true }
                        )
                    }
                }
            }
        }
    }

    if (showDebugLog) {
        val clipboard = LocalClipboardManager.current
        AcrylicDialog(
            onDismissRequest = { showDebugLog = false },
            title = { Text("书源调试日志") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "每次搜索/目录/正文请求的完整记录。反馈问题时请点击「复制全部」并把内容发给开发者。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = debugLogText,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                GradientActionButton(
                    text = "复制全部",
                    onClick = {
                        clipboard.setText(AnnotatedString(debugLogText))
                        AppToast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        com.example.source.SourceLog.clear()
                        debugLogText = "（已清空）"
                    }) {
                        Text("清空")
                    }
                    TextButton(onClick = { showDebugLog = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }

    // 「＋ 导入 JSON 书源」的三条路径收敛到一个面板：
    // 页面上只保留一行虚线入口（规范：结构里只有 3 张玻璃卡 + 1 个导入行），
    // 但「粘贴 JSON / 网络导入」是功能而不是装饰，不能因为排版被删掉。
    if (showImportPicker) {
        AcrylicDialog(
            onDismissRequest = { showImportPicker = false },
            title = { Text("导入书源") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "支持「阅读」书源 JSON：单源文件、粘贴配置、或直接拉取社区合集。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            showImportPicker = false
                            fileLauncher.launch("*/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从本地文件导入") }
                    TextButton(
                        onClick = {
                            showImportPicker = false
                            showPasteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("粘贴 JSON 配置") }
                    TextButton(
                        onClick = {
                            showImportPicker = false
                            showNetworkDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("网络导入书源") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportPicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showPasteDialog) {
        AcrylicDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴 JSON 书源配置") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = pasteJsonText,
                    onValueChange = { pasteJsonText = it },
                    placeholder = { Text("{\n  \"name\": \"我的书源\",\n  \"search\": { ... }\n}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    shape = RoundedCornerShape(SourceAvatarRadius)
                )
            },
            confirmButton = {
                GradientActionButton(
                    text = "确定导入",
                    onClick = {
                        if (pasteJsonText.isNotBlank()) {
                            viewModel.importSourceFromJsonString(pasteJsonText)
                            pasteJsonText = ""
                            showPasteDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showNetworkDialog) {
        AcrylicDialog(
            onDismissRequest = { showNetworkDialog = false },
            title = { Text("网络导入书源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "粘贴任意「阅读」书源合集 JSON 地址（shuyuan 文件），系统会自动转换并批量导入，不兼容的会跳过。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = networkUrl,
                        onValueChange = { networkUrl = it },
                        placeholder = { Text("https://.../shuyuan") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(SourceAvatarRadius),
                        singleLine = true
                    )
                    Text("快速选择社区源：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    SourceImporter.PRESET_SOURCE_URLS.forEach { (url, label) ->
                        TextButton(
                            onClick = { networkUrl = url },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                GradientActionButton(
                    text = "开始导入",
                    onClick = {
                        if (networkUrl.isNotBlank()) {
                            viewModel.importSourceFromUrl(networkUrl.trim())
                            networkUrl = ""
                            showNetworkDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showNetworkDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showLoginDialog) {
        LibraryLoginDialog(
            message = loginMessage,
            loading = loginLoading,
            onLogin = { email, pass ->
                loginLoading = true
                loginMessage = "登录中…"
                scope.launch {
                    val src = zlibSource
                    if (src == null) {
                        loginLoading = false
                        loginMessage = "Z-Library 源不可用"
                        return@launch
                    }
                    when (val result = src.login(LoginCredential(username = email, password = pass))) {
                        is SourceResult.Success -> {
                            loginLoading = false
                            loginMessage = "登录成功"
                            loggedIn = true
                            showLoginDialog = false
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

    loginSource?.let { src ->
        ZLibraryLoginDialog(
            source = src,
            onDismiss = { loginSource = null },
            onSuccess = { loginSource = null },
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
}

/* ══════════════════════════════════════════════════════════════════════════
 * ③ 大标题：随滚动缩放，折叠后顶栏带毛玻璃
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * 「书源管理」折叠大标题（Compose 侧的 SliverAppBar + FlexibleSpaceBar 等价实现）。
 *
 * 大→小的折叠行为与高度插值直接复用 Material3 的 [LargeTopAppBar] +
 * `exitUntilCollapsedScrollBehavior`（不自己算），只额外做两件事：
 * - 标题字号 28sp → 20sp 跟着 `collapsedFraction` 连续过渡；
 * - 一旦开始折叠（`collapsedFraction > 0`）就把顶栏挂上毛玻璃：
 *   sigma 16 的背景采样模糊 + **页面背景色 60%** 薄底（不再用 surface 72%，
 *   否则折叠后的顶栏与页面底色不是一个色，整页会出现一条明显的"色带"）。
 *
 * 「展开时有大标题没有玻璃」与「折叠后有玻璃没有大标题」是互斥的，
 * 所以这块玻璃不会与页面级遮罩长期叠加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceCollapsingTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val collapsedFraction = scrollBehavior.state.collapsedFraction

    // ⚠️ 顶栏自身**完全透明**：折叠后的那层玻璃由外层的 [GlassTopBarStrip] 提供。
    // 顶栏不再自己挂背景，避免出现"玻璃条 + 顶栏底"两层叠加导致的重复着色与硬切边。
    LargeTopAppBar(
        title = {
            Text(
                text = "书源管理",
                fontWeight = FontWeight.Bold,
                // 主文字 onSurface 100%：大标题是整页唯一压在"背景"上的文字，
                // 由顶部渐变遮罩保护，这里不再降透明度。
                color = primaryText(),
                fontSize = (TITLE_EXPANDED_SP -
                    (TITLE_EXPANDED_SP - TITLE_COLLAPSED_SP) * collapsedFraction).sp
            )
        },
        navigationIcon = {
            AppIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        modifier = modifier
    )
}

/* ══════════════════════════════════════════════════════════════════════════
 * ④ A 快捷入口卡
 * ══════════════════════════════════════════════════════════════════════════ */

/** Venera 砖的状态机：0 待机 / 1 更新中 / 2 刚完成。 */
private const val VENERA_IDLE = 0
private const val VENERA_BUSY = 1
private const val VENERA_DONE = 2

/**
 * 一张玻璃卡内的三等分：Z-Library 账号 / Venera 源仓库 / 调试日志。
 *
 * ⚠️ 规范：三个入口**合并为一张玻璃卡内的三等分区域**，格间用 0.5px 细竖线分隔，
 * 卡高由内容撑开（minHeight 84）。格子本身完全透明 —— 全页只有这 1 层玻璃，
 * 禁止玻璃套玻璃。
 */
@Composable
private fun SourceQuickTilesRow(
    hasZLibrary: Boolean,
    loggedIn: Boolean,
    onLoginClick: () -> Unit,
    onRefreshVenera: () -> Unit,
    onOpenDebugLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A 卡是可点击的入口卡 → 开启「高光跟随手指」（列表大卡不开，会和滚动抢按压）
    GlassKitCard(
        shape = GlassCardShape,
        interactiveHighlight = true,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SourceQuickCardMinHeight)
                // 内边距：上下 14（左右由每格自己撑，保证三格视觉等宽）
                .padding(vertical = 14.dp)
        ) {
            SourceQuickTile(
                icon = {
                    Icon(
                        imageVector = if (loggedIn) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SourceQuickIconSize)
                    )
                },
                name = "Z-Library",
                status = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loggedIn) {
                            BreathingDot()
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        SourceTileStatusText(
                            text = when {
                                loggedIn -> "已登录"
                                hasZLibrary -> "未登录"
                                else -> "未内置"
                            }
                        )
                    }
                },
                // Z-Library 未内置时这颗砖只作状态展示，不该把人带进一个必然失败的登录弹窗
                onClick = onLoginClick,
                enabled = hasZLibrary,
                modifier = Modifier.weight(1f)
            )

            SourceTileDivider()
            SourceVeneraTile(
                onRefresh = onRefreshVenera,
                modifier = Modifier.weight(1f)
            )

            SourceTileDivider()
            SourceQuickTile(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SourceQuickIconSize)
                    )
                },
                name = "调试日志",
                status = { SourceTileStatusText(text = "查看日志") },
                onClick = onOpenDebugLog,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 快捷入口的状态行：11sp、onSurface 70%、单行截断。 */
@Composable
private fun SourceTileStatusText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = secondaryText(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 三等分格之间的细竖线（玻璃卡内部的层次只靠它，不再靠第二层玻璃）。 */
@Composable
private fun SourceTileDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 12.dp)
            .width(SourceDividerWidth)
            .background(dividerColor())
    )
}

/**
 * 一块「快捷入口格」：淡染圆角方形图标底（28px 图标 / 底圆角 10）+ 名称 13 + 状态 11。
 *
 * 外层那张卡是唯一的 1 层玻璃（GlassKitCard，含 blur + lens + 高光），
 * 格子本身**不再自带玻璃**，只用 primary 12% 平涂做图标底 —— 禁止玻璃套玻璃。
 */
@Composable
private fun SourceQuickTile(
    icon: @Composable () -> Unit,
    name: String,
    status: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .pressScale(scale = 0.96f, enabled = enabled, onTap = onClick)
            .fillMaxHeight()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(SourceQuickIconBox)
                    .clip(RoundedCornerShape(SourceIconRadius))
                    .background(accentContainer()),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = primaryText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            status()
        }
    }
}

/**
 * Venera 砖：点击触发更新，更新中图标旋转，完成后短暂显示 ✓。
 *
 * 转圈是本页唯一允许的「进行中」循环动画（它是有明确语义的进度反馈，不是装饰），
 * 并且只在 busy 分支里才创建 [rememberInfiniteTransition] ——
 * 待机时屏幕上一个空闲动画器都没有。
 */
@Composable
private fun SourceVeneraTile(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var phase by rememberSaveable { mutableIntStateOf(VENERA_IDLE) }
    val scope = rememberCoroutineScope()

    SourceQuickTile(
        icon = {
            Crossfade(
                targetState = phase,
                animationSpec = tween(AppMotion.TRANSITION_MS),
                label = "venera_icon"
            ) { state ->
                when (state) {
                    VENERA_BUSY -> VeneraSpinningIcon()
                    VENERA_DONE -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SourceQuickIconSize)
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SourceQuickIconSize)
                    )
                }
            }
        },
        name = "Venera 源",
        status = {
            SourceTileStatusText(
                text = when (phase) {
                    VENERA_BUSY -> "更新中…"
                    VENERA_DONE -> "已更新"
                    else -> "点击更新"
                }
            )
        },
        onClick = {
            // 更新中重复点击无效，避免连开两个「完成」计时器互相打架
            if (phase != VENERA_BUSY) {
                phase = VENERA_BUSY
                onRefresh()
                scope.launch {
                    // 至少转 900ms，避免「闪一下就停」让人怀疑到底有没有点中；
                    // 之后收 ✓ 停 1200ms 再回到常态。
                    delay(900)
                    phase = VENERA_DONE
                    delay(1200)
                    phase = VENERA_IDLE
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun VeneraSpinningIcon() {
    val reduceMotion = LocalReduceMotion.current
    val rotation = if (reduceMotion) {
        0f
    } else {
        rememberInfiniteTransition(label = "venera_spin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing)),
                label = "venera_spin_angle"
            ).value
    }
    Icon(
        imageVector = Icons.Filled.Refresh,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(SourceQuickIconSize)
            .graphicsLayer { rotationZ = rotation }
    )
}

/**
 * 登录状态呼吸圆点（6px 强调色）—— 本页**唯一**允许的常驻循环动画，且有明确语义
 * （「这个账号现在是通的」），不是装饰。
 *
 * 尊重系统「减少动态效果」：关掉动画时退化为常亮实心点，信息不丢。
 */
@Composable
private fun BreathingDot(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val alpha = if (reduceMotion) {
        1f
    } else {
        rememberInfiniteTransition(label = "login_breath")
            .animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = AppMotion.easeOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "login_breath_alpha"
            ).value
    }
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

/* ══════════════════════════════════════════════════════════════════════════
 * ⑤ B / C 分组卡（结构完全一致）
 * ══════════════════════════════════════════════════════════════════════════ */

private enum class SourceGroupKind { NOVEL, COMIC, CUSTOM, OTHER }

private class SourceGroup(
    val title: String,
    val kind: SourceGroupKind,
    val sources: List<BookSource>,
)

/** 卡内分割线：0.5px、onSurface 10%。是否缩进由调用方给 padding 决定。 */
@Composable
private fun SourceLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SourceDividerWidth)
            .background(dividerColor())
    )
}

/**
 * 一组书源的主体玻璃卡：**头部 + 行列表**。
 *
 * 原「内置书源 / 已启用 X / Y」那行裸在壁纸上的摘要已删除，数量信息并入卡头徽标
 * （「已启用 / 总数」）—— 页面上不再有任何裸文字。
 *
 * **只有这一层做模糊**：卡内的每一行都是普通 Row，不做各自的背景采样，
 * 满足「列表内的行不要各自做模糊，只在分组卡上做一次」。
 */
@Composable
private fun SourceGroupCard(
    group: SourceGroup,
    expanded: Boolean,
    showAll: Boolean,
    onToggleExpand: () -> Unit,
    onToggleShowAll: () -> Unit,
    activeSourceId: String?,
    enabledStates: Map<String, Boolean>,
    onToggleEnable: (BookSource, Boolean) -> Unit,
    onOpenLogin: (BookSource) -> Unit,
    onOpenNodeManager: () -> Unit,
    onDelete: (BookSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewLimit = if (group.kind == SourceGroupKind.COMIC) {
        COMIC_GROUP_PREVIEW_COUNT
    } else {
        Int.MAX_VALUE
    }
    val hidden = (group.sources.size - previewLimit).coerceAtLeast(0)
    val enabledCount = remember(group.sources, enabledStates) {
        group.sources.count { enabledStates[it.id] ?: true }
    }

    // 列表大卡：只有这一层玻璃（blur + lens）。卡内的行、标签、按钮一律不得再做玻璃，
    // 否则边缘会出现第二圈轮廓（玻璃套玻璃）。
    GlassKitCard(
        shape = GlassCardShape,
        modifier = modifier.fillMaxWidth()
    ) {
        SourceGroupHeader(
            title = group.title,
            badge = "$enabledCount / ${group.sources.size}",
            expanded = expanded,
            onToggleExpand = onToggleExpand
        )
        // 头部与列表之间：0.5px 分割线，左右**不缩进**
        SourceLine()

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(AppMotion.TRANSITION_MS)) +
                expandVertically(animationSpec = tween(AppMotion.TRANSITION_MS)),
            exit = fadeOut(tween(AppMotion.TRANSITION_MS)) +
                shrinkVertically(animationSpec = tween(AppMotion.TRANSITION_MS)),
            modifier = Modifier.fillMaxWidth()
        ) {
            // AnimatedSize：展开/收起「其余 N 个」时高度与内容同步过渡，不跳变
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(AppMotion.TRANSITION_MS))
            ) {
                val visibleSources = if (showAll || hidden == 0) {
                    group.sources
                } else {
                    group.sources.take(previewLimit)
                }
                visibleSources.forEachIndexed { index, source ->
                    SourceRow(
                        source = source,
                        isActive = activeSourceId == source.id,
                        isEnabled = enabledStates[source.id] ?: true,
                        isCustom = group.kind == SourceGroupKind.CUSTOM,
                        onToggleEnable = { onToggleEnable(source, it) },
                        onOpenLogin = if (
                            source.capabilities.requiresLogin && source.id != "zlibrary"
                        ) {
                            { onOpenLogin(source) }
                        } else null,
                        onOpenNodeManager = if (source.id == "zlibrary") {
                            onOpenNodeManager
                        } else null,
                        onDelete = { onDelete(source) }
                    )
                    // 行分割线：左侧从文字起点开始（缩进 64），右侧到卡片边缘；最后一行不画
                    if (index < visibleSources.lastIndex) {
                        SourceLine(modifier = Modifier.padding(start = SourceRowDividerIndent))
                    }
                }
                if (hidden > 0 || showAll) {
                    SourceLine()
                    SourceExpandRestRow(
                        rest = hidden,
                        showAll = showAll,
                        onClick = onToggleShowAll
                    )
                }
            }
        }
    }
}

/**
 * 卡片头部（高 48，水平内边距 16）：标题 14 semi-bold + 数量徽标 + 右侧 chevron。
 *
 * 点击整行折叠 / 展开（chevron 旋转 180°）。徽标即原先那行摘要：「已启用 / 总数」。
 */
@Composable
private fun SourceGroupHeader(
    title: String,
    badge: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -180f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "group_chevron"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SourceHeaderHeight)
            .pressScale(scale = 0.96f, onTap = onToggleExpand)
            .padding(horizontal = SourcePageMargin),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = GROUP_LETTER_SPACING,
            color = primaryText()
        )
        Spacer(modifier = Modifier.width(8.dp))
        SourceCountBadge(text = badge)
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "折叠分组" else "展开分组",
            tint = secondaryText(),
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = chevron }
        )
    }
}

/** 卡头的数量徽标（primary 12% 底 + 加深 20% 的主色文字，数字等宽 + tnum）。 */
@Composable
private fun SourceCountBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(accentContainer())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = AppFonts.Monospace,
            color = accentText(),
            style = TextStyle(fontFeatureSettings = NUMERIC_FEATURE)
        )
    }
}

/**
 * 「展开其余 N 个」（行高 44，居中，12sp primary，chevron 随展开态旋转）。
 *
 * 上方有一条分割线；点击后文字变「收起」，高度与宽度变化交给外层 [animateContentSize]。
 */
@Composable
private fun SourceExpandRestRow(
    rest: Int,
    showAll: Boolean,
    onClick: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (showAll) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rest_chevron"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SourceExpandRowHeight)
            .pressScale(scale = 0.96f, onTap = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (showAll) "收起" else "展开其余 $rest 个",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentText()
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = accentText(),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevron }
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * ⑥ 单个书源行
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * 一行书源：36×36 圆角方形头像（圆角 12）+ 12 间距 + 文字块 + [可选] 小胶囊 + 8 + 开关。
 *
 * - 行高固定 60（垂直 padding 10，行高不再随内容浮动）；
 * - 头像沿用 [SourceAvatar]，**保留每个书源自己的既定配色**（由 sourceId 哈希出的
 *   稳定 HSV 色，换形状不改色），本页显式传 `RoundedCornerShape(12)` 走圆角方形，
 *   禁用时再叠一层 [desaturate]；
 * - 开关用全项目统一的 [AppSwitch]，本页按规范缩到 44×26 / 滑块 20（弹簧动效与
 *   触觉反馈原样保留，只是尺寸变小）；
 * - 禁用态：整行 0.55 透明 + 头像去饱和。
 */
@Composable
private fun SourceRow(
    source: BookSource,
    isActive: Boolean,
    isEnabled: Boolean,
    isCustom: Boolean,
    onToggleEnable: (Boolean) -> Unit,
    onOpenLogin: (() -> Unit)?,
    onOpenNodeManager: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSourceInfo by remember(source.id) { mutableStateOf(false) }
    if (showSourceInfo) SourceInfoDialog(source = source, enabled = isEnabled, onDismiss = { showSourceInfo = false })
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 文档硬性：只用 minHeight，**禁止固定 height** ——
            // 固定高度 + 上下内边距会把 ID 那一行压出可视区（此前的"ID 被裁掉半截"）。
            .heightIn(min = SourceRowHeight)
            .graphicsLayer { alpha = if (isEnabled) 1f else 0.55f }
            .padding(horizontal = SourcePageMargin, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceAvatar(
            sourceId = source.id,
            sourceName = source.name,
            size = SourceAvatarSize,
            // 36px **圆角方形**（小元素圆角 12）。
            // SourceAvatar 默认是 CircleShape，这里显式传圆角方形；
            // 其它调用点不传 => 仍是圆形，零回归。
            shape = RoundedCornerShape(SourceAvatarRadius),
            modifier = if (isEnabled) Modifier else Modifier.desaturate()
        )
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = SourceNameStyle.copy(color = primaryText()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isActive && isEnabled) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ActiveDot()
                }
                if (source.capabilities.requiresLogin) {
                    Spacer(modifier = Modifier.width(6.dp))
                    SourceMiniLabel(text = "需登录")
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                // 规范：ID 不带「ID:」前缀
                text = source.id,
                style = SourceIdStyle.copy(color = secondaryText()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (source.isComicSource) {
                TextButton(onClick = { showSourceInfo = true }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.heightIn(min = 28.dp)) {
                    Text("漫画源 · 查看资料", style = SourceIdStyle.copy(color = MaterialTheme.colorScheme.primary))
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (onOpenLogin != null) {
                SourceMiniActionChip(text = "登录", onClick = onOpenLogin)
            }
            if (onOpenNodeManager != null) {
                SourceMiniActionChip(text = "节点", onClick = onOpenNodeManager)
            }
            AppSwitch(
                checked = isEnabled,
                onCheckedChange = { onToggleEnable(it) },
                // 规范：开关缩到 44×26、滑块 20（内边距 3 = (26−20)/2）
                containerWidth = 44,
                containerHeight = 26,
                circleSize = 20,
                padding = 3
            )
            if (isCustom) {
                AppIconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除书源",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 「当前使用」的实心小圆点（纯 primary，用于选中态）。 */
@Composable
private fun ActiveDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

/** 名称后面的小标签（「需登录」）：primary 12% 底 + 加深 20% 的主色文字，11sp。 */
@Composable
private fun SourceMiniLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(accentContainer())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentText()
        )
    }
}

/** 开关左侧的淡色小胶囊（登录 / 节点）：高 26、水平内边距 12、字号 12。 */
@Composable
private fun SourceMiniActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .height(SourceChipHeight)
            .background(accentContainer())
            .pressScale(scale = 0.96f, onTap = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentText()
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * ⑦ D 导入行
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * 自定义书源的入口：**不要插画、不要空状态大卡**，只有一条虚线描边的行
 * （圆角 20、高 48、居中「＋ 导入 JSON 书源」、14sp primary）。
 *
 * 点击后弹出导入方式面板（本地文件 / 粘贴 JSON / 网络导入）—— 三条导入路径都是
 * 功能而不是装饰，收敛进弹窗是为了让页面结构里只有「3 张玻璃卡 + 1 个导入行」。
 */
@Composable
private fun SourceImportEntryRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SourceImportRowHeight)
            .dashedOutline(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                radius = SourceImportRadius
            )
            .pressScale(scale = 0.96f, onTap = onClick)
            .padding(horizontal = SourcePageMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = accentText(),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "导入 JSON 书源",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentText()
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════════
 * 通用小工具
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * 虚线描边（用于「＋ 导入」这类"还没有内容"的占位行）。
 *
 * `drawWithCache` + `dashPathEffect`：路径与笔触只在尺寸变化时重建，滚动期零分配。
 */
private fun Modifier.dashedOutline(
    color: Color,
    radius: Dp,
    strokeWidth: Dp = 1.dp,
    dash: Dp = 6.dp,
    gap: Dp = 6.dp,
): Modifier = this.drawWithCache {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            intervals = floatArrayOf(dash.toPx(), gap.toPx()),
            phase = 0f
        )
    )
    val corner = CornerRadius(radius.toPx(), radius.toPx())
    onDrawBehind {
        drawRoundRect(color = color, cornerRadius = corner, style = stroke)
    }
}

/**
 * 去饱和：在不改动 [SourceAvatar] 的前提下把它那份既定配色抽成灰。
 *
 * 先画完内容，再用 [BlendMode.Saturation] 铺一层灰：结果是取「源的饱和度(0)」
 * 配「目标的色相与明度」，正好等于"保留明暗层次、抽掉颜色"。
 * 必须挂在 Offscreen 合成层上，否则会和下层内容共用 alpha 通道导致整块变灰一块。
 */
private fun Modifier.desaturate(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(color = Color.Gray, blendMode = BlendMode.Saturation)
    }
