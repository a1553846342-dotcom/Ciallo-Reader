package com.example

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import dev.liquidglass.compose.liquidGlassProvider
import dev.liquidglass.compose.rememberLiquidGlassProviderState
import com.example.ui.components.LocalLiquidGlassState
import com.example.ui.components.LocalGlassBackdrop
import com.example.ui.components.CardTweaks
import com.example.ui.components.LocalCardTweaks
import com.example.ui.components.readCardTweaks
import com.example.ui.components.AppBottomTabBar
import com.example.ui.components.AppTabItem
import com.example.ui.components.rememberTabBarCollapseState
import com.example.ui.components.showAppSnackbar
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.ui.*
import com.example.ui.theme.LocalAppBottomInset
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.luminance
import coil.compose.rememberAsyncImagePainter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AppToast

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 跨路由传递「继续阅读」的起始页（章节页 → 在线阅读器）。 */
private object ComicJumpState {
    var startPage: Int = 0
    /** 最近一次翻到的页（退出阅读器时强制保存用） */
    var lastPage: Int = -1
}
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
class MainActivity : ComponentActivity() {
    private var mainViewModel: MainViewModel? = null

    /**
     * 音量键翻页拦截（第 28 条）：仅在漫画阅读器存活且开关开启时消费音量键
     * （阅读器组合期注册 ComicVolumeKeyBridge.handler，退出即注销）——
     * 其余场景（其它页面 / 来电 / 开关关闭）返回 false，音量键走系统原生
     * 音量调节，行为完全一致。消费后不弹系统音量条。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val isDownAction = event.action == android.view.KeyEvent.ACTION_DOWN
            if (com.example.ui.comic.ComicVolumeKeyBridge.dispatch(event.keyCode, isDownAction)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // P2-1：安装系统启动窗口 —— 必须在 super.onCreate **之前**调用（官方要求：
        // 早于窗口创建注册 delegate，否则 Android 12+ 上系统可能已经自行移除启动窗口，
        // setKeepOnScreenCondition / 交接动画都会失效）。
        // 冷启动时 Application/DB/书源引擎的初始化都在下面这些行里，Compose 首帧之前
        // 窗口是空的；系统启动窗口由 SurfaceFlinger 画，点图标第一帧就有内容（见 themes.xml
        // Theme.MyApplication.Splash，底色与开屏页一致，交接无色差）。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        com.example.source.js.JsActivityTracker.register(this)
        // 启动看门狗：若"极致"画质在 20 秒内连续两次发生崩溃，自动降回"高"，
        // 防止实验性着色器效果导致"一崩就再也打不开"的死循环变砖。
        runCatching {
            val boot = getSharedPreferences("novel_reader_prefs", MODE_PRIVATE)
            if (boot.getInt("render_quality", 2) == 3) {
                val now = System.currentTimeMillis()
                val last = boot.getLong("boot_guard_last", 0L)
                var cnt = boot.getInt("boot_guard_cnt", 0)
                cnt = if (last > 0 && now - last < 20_000L) cnt + 1 else 1
                boot.edit().putLong("boot_guard_last", now).putInt("boot_guard_cnt", cnt).apply()
                if (cnt >= 2) {
                    boot.edit()
                        .putInt("render_quality", 2)
                        .putInt("boot_guard_cnt", 0)
                        .putLong("boot_guard_last", 0L)
                        .apply()
                } else {
                    // 存活满 20 秒即解除武装（正常使用不计入）
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        boot.edit().putLong("boot_guard_last", 0L).apply()
                    }, 20_000L)
                }
            }
        }
        com.example.library.ZLibraryNodeManager.restoreSelection(applicationContext)
        // 卡片微调 v2 默认档：一次性迁移（幂等）必须在 setContent 之前完成，
        // 避免在组合期执行持久化副作用
        com.example.data.PreferencesManager(this).migrateCardTweaksDefaultsV2()
        // 冷启动兜底：清空书架分享的"用完即焚"临时文件，防止异常残留堆积
        Thread {
            com.example.library.BookShareHelper.cleanupTempShareDir(applicationContext)
        }.start()
        // 第十轮：AniList 多语言标题库已改为 APK 内置（assets 预填充，
        // 见 AppDatabase.bundledAniListDb），用户零拉取、离线可用；
        // 运行时同步调度器已移除。
        enableEdgeToEdge()
        // ── B2 关掉 OEM 的导航栏对比度强制（Android 10+）──────────────────
        // 边缘到边之后，部分 ROM（尤其开了"导航栏对比度"策略的三星 OneUI、
        // 部分 ColorOS）会在透明导航栏上再糊一层 ~20% 灰的黑纱，
        // 导致同一个界面在不同机型上出现"底部一条脏边"的观感差异。
        // App 自己已经在 Compose 侧解决了对比度（背景不透明就不会压字），
        // 所以这里明确告诉系统别来插一脚。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching { window.isNavigationBarContrastEnforced = false }
        }
        setContent {
            val viewModel: MainViewModel = viewModel()
            val libraryViewModel: com.example.library.LibraryViewModel = viewModel()
            val sourceViewModel = remember(libraryViewModel) {
                com.example.source.SourceViewModel(
                    application = application,
                    sourceManager = libraryViewModel.sourceManager
                )
            }
            mainViewModel = viewModel

            // adb 触发逐源冒烟测试：adb shell am start -n com.aistudio.novelreader.kxmpzq/.MainActivity --ez smoke_test true
            LaunchedEffect(Unit) {
                if (intent?.getBooleanExtra("smoke_test", false) == true) {
                    libraryViewModel.runSourceSmokeTest(
                        filter = intent?.getStringExtra("smoke_source") ?: "",
                        keyword = intent?.getStringExtra("smoke_keyword") ?: "",
                        smokeUser = intent?.getStringExtra("smoke_user"),
                        smokePassword = intent?.getStringExtra("smoke_pass")
                    )
                }
            }

            val autoNightMode by viewModel.autoNightMode.collectAsStateWithLifecycle()

            // ── A5 系统栏图标对比度跟随 App 自己的主题 ────────────────────
            // enableEdgeToEdge() 裸调用后，状态栏/导航栏图标颜色跟随**系统** uimode，
            // 与 App 内的 autoNightMode 开关不同步：用户在 App 里开了夜间模式、
            // 系统是白天时，会出现「深色背景 + 深色图标」看不见的情况。
            val view = LocalView.current
            DisposableEffect(autoNightMode) {
                val controller = WindowCompat.getInsetsController(window, view)
                // 浅色背景 -> 图标用深色（isAppearanceLightStatusBars = true）
                controller.isAppearanceLightStatusBars = !autoNightMode
                controller.isAppearanceLightNavigationBars = !autoNightMode
                onDispose {}
            }
            val blueLightFilter by viewModel.blueLightFilter.collectAsStateWithLifecycle()
            val blueLightAlpha by viewModel.blueLightAlpha.collectAsStateWithLifecycle()
            val colorPrimaryIndex by viewModel.colorPrimaryIndex.collectAsStateWithLifecycle()
            val colorSecondaryIndex by viewModel.colorSecondaryIndex.collectAsStateWithLifecycle()
            val orientationLock by viewModel.screenOrientationLock.collectAsStateWithLifecycle()
            val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()

            // 卡片微调：设置页「自定义卡片参数」实时写入的共享状态，注入所有 GlassCard
            // （v2 默认档迁移已在 onCreate 中完成）
            val cardTweaks = remember { mutableStateOf(viewModel.prefs.readCardTweaks()) }
            // 滚动惯性倾斜：全 App 单一信号源（任务书「整卡倾斜」§2）
            val scrollTilt = remember { com.example.ui.components.ScrollTiltController() }

            MyApplicationTheme(
                darkTheme = autoNightMode,
                colorPrimaryIndex = colorPrimaryIndex,
                colorSecondaryIndex = colorSecondaryIndex
            ) {
                val liquidGlass = rememberLiquidGlassProviderState()

                // ── A1 系统字体缩放钳制 ────────────────────────────────────
                // 全 App 有 443 处硬编码 xx.sp，而大量容器高度是写死的
                // （底栏角标 16dp、日历格 44dp、封面 155dp、图表标签 20dp…）。
                // 小米「巨无霸字体」/华为大字体可把 sp 放大到 1.3~1.5 倍，
                // 直接撑爆这些容器 → 这就是「在自己手机上正常、别人手机上错位」的主因。
                // 这里把 fontScale 夹到 [0.85, 1.15]：放大不再撑爆布局，
                // 缩小也保留下限不至于看不清。
                //
                // ⚠️ 覆盖面澄清（勘误）：这个 CompositionLocalProvider 挂在**根**上，
                // 因此覆盖全部界面 —— **包括阅读页**。阅读页正文用的是 `fontSize.sp`，
                // sp 的解析同样走 LocalDensity.fontScale，所以阅读正文字号也会被夹住；
                // 「阅读页字号独立、不受系统字体缩放影响」指的是它有自己的
                // prefs.fontSize 设置项（用户可在阅读器里单独调到 12~40sp），
                // 而不是说它绕过了这个钳制。
                // 换算公式：实际正文 px = prefs.fontSize.sp × 系统 density × clamp(系统 fontScale)。
                // 维持现状不动：放开钳制会让大量写死高度的容器被撑爆（见上方排查结论），
                // 属于系统性返工，不适合在本次跨机型整改里顺手改。
                val systemDensity = LocalDensity.current
                val clampedDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceIn(0.85f, 1.15f)
                    )
                }

                CompositionLocalProvider(
                    LocalDensity provides clampedDensity,
                    LocalLiquidGlassState provides liquidGlass,
                    com.example.ui.components.LocalScrollTilt provides scrollTilt
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .liquidGlassProvider(liquidGlass)
                            .drawWithContent {
                            drawContent()
                            if (blueLightFilter) {
                                // Real warm-orange color filter overlay drawn on top of the entire application
                                // Maximum opacity of 0.65f to allow reading comfortably at 100% slider value
                                val maxOpacity = 0.65f
                                drawRect(
                                    color = Color(0xFFFF9E0D),
                                    alpha = blueLightAlpha * maxOpacity
                                )
                            }
                        },
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val bgConfig by AppBackgroundController.config.collectAsStateWithLifecycle()
                        val bgActive = bgConfig.mode == 1 && !bgConfig.uri.isNullOrBlank()
                        // 渲染画质档位：设置页可调，主要影响玻璃效果强度与动效数量。
                        // "高"为默认，与历史版本视觉完全一致；低于"高"不挂 backdrop 捕获层。
                        val renderQualityIdx by viewModel.renderQuality.collectAsStateWithLifecycle()
                        val renderQuality = com.example.ui.components.RenderQuality.of(renderQualityIdx)
                        val glassEnabled = renderQuality.realtimeGlass
                        // 滚动惯性倾斜帧循环：仅 MAX 档挂载（该档本就常驻极光等无限动效；
                        // 低档不再引入常驻 ticker，保住「流畅」档的帧空闲与省电）
                        if (renderQuality == com.example.ui.components.RenderQuality.MAX) {
                            com.example.ui.components.ScrollTiltHost(scrollTilt)
                        } else {
                            scrollTilt.reset()
                        }
                        // 页面有效背景平均亮度（已含遮罩）：标题文字据此实时取对比色
                        val bgTone: Float = if (bgActive) {
                            val appContext = LocalContext.current.applicationContext
                            val loaded by produceState(0.5f, bgConfig.uri, bgConfig.dim) {
                                value = loadBackgroundAvgLuminance(
                                    appContext,
                                    bgConfig.uri.orEmpty(),
                                    bgConfig.dim
                                )
                            }
                            loaded
                        } else {
                            MaterialTheme.colorScheme.background.luminance()
                        }
                        // 玻璃采样源：把背景层挂上 layerBackdrop（与底部 Tab 栏同机制的真实内容采样），
                        // 页面内容卡（GlassCard）据此复用 Tab 栏同一套 KMPLiquidGlass 模糊实现。
                        // 注：曾试验"整屏预烘焙模糊位图"方案（PreBlurredBackdrop），实机上导致玻璃
                        // 效果异常（疑似该机型快照管线不应用链式 RenderEffect / 背景图异步加载竞态），
                        // 已回退实时模糊路径；相关代码保留在 backdrop 库中但不再接线。
                        val bgBackdrop = rememberLayerBackdrop()
                        // ── A2 底部避让唯一事实来源 ──────────────────────────
                        // 悬浮 Tab 栏总高 = 12dp 上边距 + 68dp 栏体 + 12dp 下边距 = 92dp，
                        // 再叠上真实系统导航栏高度（手势导航 ≈0、三键导航 ≈48dp）。
                        // 此前各页各写 96/104/120/24/60.dp 去猜，三键导航机型必然压栏。
                        val navBarBottom =
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .then(
                                        if (glassEnabled) Modifier.layerBackdrop(bgBackdrop) else Modifier
                                    )
                            ) {
                                if (bgActive) {
                                    Image(
                                        painter = rememberAsyncImagePainter(bgConfig.uri),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (bgConfig.dim > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = bgConfig.dim / 100f))
                                        )
                                    }
                                }
                            }
                            // 触觉总开关同步到非组合环境：漫画翻页 / 图片裁切等直接调
                            // View.performHapticFeedback 的地方读不到 CompositionLocal，只能靠这个镜像。
                            androidx.compose.runtime.SideEffect {
                                com.example.ui.feedback.HapticsGate.enabled = hapticsEnabled
                            }
                            CompositionLocalProvider(
                                LocalAppBackgroundActive provides bgActive,
                                LocalAppBottomInset provides (navBarBottom + 92.dp + 8.dp),
                                // 底栏被隐藏时（书架多选 / 拖拽）只让开系统导航栏：
                                // 否则操作栏与放置坞会在底部凭空空出 92dp。
                                com.example.ui.theme.LocalAppBottomInsetNoTabBar provides (navBarBottom + 8.dp),
                                LocalBackgroundTone provides bgTone,
                                LocalGlassBackdrop provides (bgBackdrop.takeIf { glassEnabled }),
                                com.example.ui.components.LocalRenderQuality provides renderQuality,
                                LocalCardTweaks provides cardTweaks.value,
                                // 「我喜欢的」交互：动效与触觉统一开关（系统"减少动态效果"自动降级）
                                com.example.ui.feedback.LocalReduceMotion provides com.example.ui.feedback.systemReduceMotion(),
                                com.example.ui.feedback.LocalHapticsEnabled provides hapticsEnabled,
                                // 关掉开关 → 把 Compose 侧的震动实现整体换成空实现：
                                // 底栏切换、卡片波纹、滑块、开关等 10+ 处 LocalHapticFeedback.current
                                // 一次性全部静音，无需逐个加分支。
                                androidx.compose.ui.platform.LocalHapticFeedback provides
                                    (if (hapticsEnabled) androidx.compose.ui.platform.LocalHapticFeedback.current
                                    else com.example.ui.feedback.MutingHapticFeedback),
                            ) {
                        val navController = rememberNavController()
                        // 启动时用持久化配置初始化背景（设置页改动会通过 AppBackgroundController 实时更新）
                        LaunchedEffect(Unit) {
                            AppBackgroundController.update(
                                viewModel.prefs.appBackgroundMode,
                                viewModel.prefs.customAppBackgroundUri,
                                viewModel.prefs.appBackgroundDim
                            )
                        }

                    DisposableEffect(orientationLock) {
                        requestedOrientation = when (orientationLock) {
                            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        onDispose {}
                    }

                    val importMessage by viewModel.importStatusMessage.collectAsStateWithLifecycle()

                    LaunchedEffect(importMessage) {
                        importMessage?.let {
                            AppToast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                            viewModel.clearImportMessage()
                        }
                    }

                    var selectedCategoryForImport by remember { mutableStateOf("全部") }

                    val fileLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri: Uri? ->
                        uri?.let {
                            try {
                                contentResolver.takePersistableUriPermission(
                                    it,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: Exception) { e.printStackTrace() }

                            var fileName = "book.txt"
                            try {
                                contentResolver.query(it, null, null, null, null)?.use { cursor ->
                                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (cursor.moveToFirst() && nameIndex >= 0) {
                                        fileName = cursor.getString(nameIndex) ?: "book.txt"
                                    }
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                            if (fileName == "book.txt") {
                                fileName = it.lastPathSegment?.substringAfterLast('/') ?: "book.txt"
                            }
                            android.util.Log.d("BookImport", "[MainActivity] File selected: $fileName, uri: $it, category: $selectedCategoryForImport")
                            viewModel.importBook(it, fileName, selectedCategoryForImport)
                        }
                    }

                    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
                    val favoriteAddRequest by viewModel.favoriteAddRequest.collectAsStateWithLifecycle()
                    val favoriteActionMessage by viewModel.favoriteActionMessage.collectAsStateWithLifecycle()
                    val favoriteSourceNames by libraryViewModel.availableSources.collectAsStateWithLifecycle()
                    LaunchedEffect(favoriteActionMessage) {
                        favoriteActionMessage?.let { message ->
                            AppToast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            viewModel.clearFavoriteActionMessage()
                        }
                    }
                    favoriteAddRequest?.let { request ->
                        com.example.ui.favorite.DuplicateComicSheet(
                            request = request,
                            sourceName = { sourceId -> favoriteSourceNames.firstOrNull { it.id == sourceId }?.name ?: sourceId },
                            onDismiss = viewModel::dismissFavoriteAdd,
                            onConfirm = viewModel::confirmFavoriteAdd,
                        )
                    }
                    /* 书架当前分类：必须放在**这层**（HomeScreen 之外）。
                       打开书籍是 navigate 到另一个目的地，HomeScreen 会整体离开组合，
                       状态放它里面就会被重置成「默认」——用户看到的就是
                       「进书里看一眼再退出，分类跳回默认了」。 */
                    var shelfCategory by rememberSaveable { mutableStateOf(com.example.data.DEFAULT_CATEGORY) }

                    SharedTransitionLayout { CompositionLocalProvider(LocalSharedTransitionScope provides this) { NavHost(
                        navController = navController,
                        startDestination = "splash",
                        enterTransition = { fadeIn(tween(250)) },
                        exitTransition = { fadeOut(tween(250)) },
                        popEnterTransition = { fadeIn(tween(250)) },
                        popExitTransition = { fadeOut(tween(250)) }
                    ) {
                        composable("splash") {
                            SplashScreen(
                                prefs = viewModel.prefs,
                                onSplashFinished = {
                                    // 冷启动 TTFD：真正进入可用界面的这一刻才上报 fully drawn，
                                    // 这样系统启动窗口 / 开屏海报的耗时不会被算进「启动完成」之前。
                                    runCatching { reportFullyDrawn() }
                                    val nextDest = if (viewModel.prefs.hasSeenOnboarding) "home" else "onboarding"
                                    navController.navigate(nextDest) {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("onboarding") {
                            com.example.ui.OnboardingScreen(
                                onFinished = {
                                    viewModel.prefs.hasSeenOnboarding = true
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("home") { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val books by viewModel.allBooks.collectAsStateWithLifecycle()
                            val categories by viewModel.allCategories.collectAsStateWithLifecycle()
                            val readingRecords by viewModel.allReadingRecords.collectAsStateWithLifecycle()
                            val readingSessions by viewModel.allReadingSessions.collectAsStateWithLifecycle()
                            /* 神回的三个订阅**刻意不放这里**：统计页/设置页才需要，
                               放到 home 顶部会让冷启动就去读 DataStore + 查库 ——
                               一旦神回侧有任何问题，表现就是"一打开就闪退"。
                               挪进各 Tab 内部后，默认 Tab（书架）完全不碰神回。 */
                            val tabBarCollapseState = rememberTabBarCollapseState()
                            // Tab 栏专用背景采样（书源选择弹窗同款手法）：
                            // layerBackdrop 捕获页面真实内容，Tab 栏 drawBackdrop 模糊它。
                            // 画质档位低于"高"时不捕获（底栏走半透明底），滚动零捕获开销。
                            val tabBackdrop = rememberLayerBackdrop()
                            // 底栏玻璃只采样底部条带，捕获层裁剪到该区域（topLeft 保持坐标系不变），
                            // 滚动时不再对整页内容做全屏重录与重栅格化。
                            // 高度覆盖底栏最大高度(68dp)+导航栏内缩+模糊/阴影外扩，取 150dp 富余。
                            val stripDensity = LocalDensity.current
                            SideEffect {
                                if (renderQuality.realtimeGlass) {
                                    tabBackdrop.captureStripHeightPx = with(stripDensity) { 150.dp.toPx().toInt() }
                                }
                            }

                            val tabItems = remember {
                                listOf(
                                    AppTabItem("书库", Icons.Outlined.Search, Icons.Filled.Search),
                                    AppTabItem("书架", Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
                                    AppTabItem("统计", Icons.Outlined.BarChart, Icons.Filled.BarChart),
                                    AppTabItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings)
                                )
                            }

                            val libraryErrorMessage by libraryViewModel.errorMessage.collectAsStateWithLifecycle()
                            val mainImportMessage by viewModel.importStatusMessage.collectAsStateWithLifecycle()
                            val snackbarHostState = remember { SnackbarHostState() }
                            // 登记为全局提示宿主：主页在场时，全站 Toast 统一走 App 自己的 snackbar 皮肤
                            com.example.ui.components.BindAppToastHost(snackbarHostState)

                            /* ── 「我喜欢的」：书源注入 + 收藏数据 ── */
                            LaunchedEffect(Unit) {
                                viewModel.comicSourceProvider = { id ->
                                    libraryViewModel.sourceManager.availableSources.value
                                        .firstOrNull { it.id == id } as? com.example.source.ComicSource
                                }
                            }
                            val favoriteItems by viewModel.favoriteItems.collectAsStateWithLifecycle()
                            val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
                            val favoriteCategoryEntities by viewModel.favoriteCategories.collectAsStateWithLifecycle()
                            val favoriteCategories = remember(favoriteCategoryEntities) {
                                favoriteCategoryEntities.map { it.name }
                            }
                            val homeScope = rememberCoroutineScope()

                            /**
                             * 删除下载的"撤销窗口"：先软删除（立刻从界面移除，DB 与文件都还在），
                             * 8 秒后仍未撤销才真正删除 —— 撤销窗口内文件绝不会被删掉。
                             */
                            var pendingDeleteBooks by remember {
                                mutableStateOf<List<com.example.data.Book>>(emptyList())
                            }
                            val visibleBooks = remember(books, pendingDeleteBooks) {
                                val ids = pendingDeleteBooks.map { it.id }.toHashSet()
                                books.filter { it.id !in ids }
                            }
                            fun showUndo(message: String, undo: () -> Unit) {
                                homeScope.launch {
                                    // ⚠️ 这是**成功 + 撤销**提示，不是错误。
                                    // 走 TOAST 语义，否则会被渲染成红色「操作出错」卡 ——
                                    // 用户原话：「操作总是出错，问题是操作根本没有出错」。
                                    val result = snackbarHostState.showAppSnackbar(
                                        message = message,
                                        kind = com.example.ui.components.AppSnackKind.TOAST,
                                        actionLabel = "撤销",
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) undo()
                                }
                            }

                            fun formatShelfBytes(bytes: Long): String = when {
                                bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
                                bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1024f / 1024f)
                                bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024f)
                                bytes > 0 -> "$bytes B"
                                else -> "0 KB"
                            }

                            LaunchedEffect(libraryErrorMessage) {
                                libraryErrorMessage?.let {
                                    snackbarHostState.showAppSnackbar(
                                        message = it,
                                        kind = com.example.ui.components.AppSnackKind.ERROR,
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            }

                            LaunchedEffect(mainImportMessage) {
                                mainImportMessage?.let {
                                    if (it.contains("失败") || it.contains("出错")) {
                                        snackbarHostState.showAppSnackbar(
                                            message = it,
                                            kind = com.example.ui.components.AppSnackKind.ERROR,
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (renderQuality.realtimeGlass) Modifier.layerBackdrop(tabBackdrop)
                                        else Modifier
                                    )
                            ) {
                            Scaffold(
                                containerColor = if (LocalAppBackgroundActive.current) {
                                    Color.Transparent
                                } else {
                                    MaterialTheme.colorScheme.background
                                },
                                snackbarHost = {
                                    SnackbarHost(
                                        hostState = snackbarHostState,
                                        // A2：原先固定 60dp，三键导航机型会被 Tab 栏+导航栏一起盖住
                                        modifier = Modifier.padding(bottom = LocalAppBottomInset.current)
                                    ) { data ->
                                        // ⚠️ 按语义挑皮肤，不能一律渲染成红色错误卡：
                                        // 「已移动 2 本到『悬疑』」曾经顶着「操作出错」弹出来，
                                        // 而操作其实是成功的。
                                        val kind = (data.visuals as? com.example.ui.components.AppSnackbarVisuals)
                                            ?.kind ?: com.example.ui.components.AppSnackKind.ERROR
                                        when (kind) {
                                            com.example.ui.components.AppSnackKind.ERROR ->
                                                com.example.ui.components.AppErrorSnackbar(
                                                    message = data.visuals.message,
                                                    onDismissClick = { data.dismiss() },
                                                )
                                            com.example.ui.components.AppSnackKind.TOAST ->
                                                com.example.ui.components.AppToastSnackbar(
                                                    message = data.visuals.message,
                                                    actionLabel = data.visuals.actionLabel,
                                                    onActionClick = { data.performAction() },
                                                    onDismissClick = { data.dismiss() },
                                                )
                                        }
                                    }
                                },
                            ) { innerPadding ->
                                // 内容区向下延伸到屏幕底部（底栏后方），滚动时直接没入底栏，
                                // 底栏上方不再存在任何固定不动的空白带。
                                Box(
                                    modifier = Modifier
                                        .padding(top = innerPadding.calculateTopPadding())
                                        .nestedScroll(tabBarCollapseState.connection())
                                ) {
                                    AnimatedContent(
                                        targetState = selectedTab,
                                        transitionSpec = {
                                            // Material 3 的 fade-through：出场先走（90ms），
                                            // 入场等 90ms 再淡入（210ms）。
                                            // 关键收益：**两页永远不会同时可见**，因此不会出现
                                            // 「两张半透明页面叠在一起发灰」的中间帧
                                            // （旧实现是横向各滑 1/3 屏 + 交叉淡化，中间帧必然重影）。
                                            fadeIn(
                                                animationSpec = tween(durationMillis = 210, delayMillis = 90),
                                            ).togetherWith(
                                                fadeOut(animationSpec = tween(durationMillis = 90)),
                                            )
                                        },
                                        label = "TabSwitch"
                                    ) { tab ->
                                        when (tab) {
                                                0 -> com.example.library.LibraryScreen(
                                                    viewModel = libraryViewModel,
                                                    onBookImported = { 
                                                        selectedTab = 1 
                                                    },
                                                    onOpenSourceManagement = {
                                                        navController.navigate("source_management")
                                                    },
                                                    onImportLocalBook = {
                                                        selectedCategoryForImport = "全部"
                                                        fileLauncher.launch("*/*")
                                                    },
                                                    /* 搜索结果卡片上的 ♡：直接收进「我喜欢的」 */
                                                    favoriteKeys = favoriteKeys,
                                                    onToggleFavorite = { book, next ->
                                                        viewModel.toggleFavorite(book, next)
                                                    },
                                                    onOpenComic = { book ->
                                                        libraryViewModel.openComic(book)
                                                        navController.navigate("comic_chapters")
                                                    },
                                                    onOpenLocalNovel = { book -> viewModel.selectBook(book); navController.navigate("reader") },
                                                    extraBottomPadding = LocalAppBottomInset.current
                                                )
                                            1 -> HomeScreen(
                                                books = visibleBooks,
                                                categories = categories,
                                                shelfCategory = shelfCategory,
                                                onShelfCategoryChange = { shelfCategory = it },
                                                onBookClick = { book ->
                                                    viewModel.selectBook(book)
                                                    if (book.isComic) {
                                                        navController.navigate("comic_reader")
                                                    } else {
                                                        navController.navigate("reader")
                                                    }
                                                },
                                                onImportClick = { category ->
                                                    selectedCategoryForImport = category
                                                    fileLauncher.launch("*/*")
                                                },
                                                onAddCategory = { name ->
                                                    viewModel.addCategory(name)
                                                },
                                                onSettingsClick = {
                                                    // 第十一轮第 2 条修复：设置页是
                                                    // Tab 3（2=统计）——旧值跳错页，长按分类
                                                    // 的"需先开启隐私模式"引导落不到设置页
                                                    selectedTab = 3
                                                },
                                                onNavigateToShelf = {
                                                    selectedTab = 0
                                                },
                                                onNavigateToStats = {
                                                    selectedTab = 2
                                                },
                                                todayReadSecondsFlow = viewModel.todayReadSeconds,
                                                streakDaysFlow = viewModel.streakDays,
                                                onDeleteBook = { book ->
                                                    viewModel.deleteBook(book)
                                                    com.example.ui.mascot.MascotAnimationController.play(com.example.ui.mascot.MascotEvent.DeleteBook)
                                                },
                                                onMoveBook = { book, newCategory ->
                                                    viewModel.moveBookToCategory(book, newCategory)
                                                    com.example.ui.mascot.MascotAnimationController.play(com.example.ui.mascot.MascotEvent.MoveBook)
                                                },
                                                onDeleteCategory = { category, onResult ->
                                                    viewModel.deleteCategory(category) { onResult(it) }
                                                },
                                                /* ── 隐私模式（第七轮第 6 条）── */
                                                privacyModeEnabled = viewModel.privacyModeEnabled.collectAsStateWithLifecycle().value,
                                                protectedCategoryNames = viewModel.protectedCategoryNames.collectAsStateWithLifecycle().value,
                                                unlockedCategoryIds = viewModel.unlockedCategoryIds.collectAsStateWithLifecycle().value,
                                                onUnlockCategory = { cat, pin -> viewModel.unlockCategory(cat.id, pin) },
                                                onToggleCategoryProtected = { cat, protected ->
                                                    viewModel.setCategoryProtected(cat.id, protected)
                                                },
                                                /* ── 「我喜欢的」栏 ── */
                                                favoriteItems = favoriteItems,
                                                favoriteKeys = favoriteKeys,
                                                onOpenFavorite = { item ->
                                                    libraryViewModel.openComic(
                                                        com.example.source.SearchBook(
                                                            id = item.favorite.comicId,
                                                            sourceId = item.favorite.sourceId,
                                                            title = item.favorite.title,
                                                            author = item.favorite.author,
                                                            cover = item.favorite.coverUrl,
                                                        )
                                                    )
                                                    navController.navigate("comic_chapters")
                                                },
                                                onCheckFavoriteUpdates = { viewModel.checkFavoriteUpdates(false) },
                                                onMoveFavoritesToCategory = { keys, cat ->
                                                    viewModel.moveFavoritesToCategory(keys, cat)
                                                },
                                                onRemoveFavorites = { keys -> viewModel.removeFavorites(keys) },
                                                onFavoriteSelectedBooks = { selected ->
                                                    val withSource = selected.filter {
                                                        !it.sourceId.isNullOrBlank() && !it.comicId.isNullOrBlank()
                                                    }
                                                    withSource.forEach {
                                                        viewModel.toggleFavorite(
                                                            com.example.source.SearchBook(
                                                                id = it.comicId!!,
                                                                sourceId = it.sourceId!!,
                                                                title = it.title,
                                                                author = it.author,
                                                                cover = it.coverUri,
                                                            ),
                                                            true,
                                                        )
                                                    }
                                                     withSource.size to (selected.size - withSource.size)
                                                 },
                                                 onFavoriteSelectedBooksToCategory = { selected, cat ->
                                                     val withSource = selected.filter {
                                                         !it.sourceId.isNullOrBlank() && !it.comicId.isNullOrBlank()
                                                     }
                                                     withSource.forEach {
                                                         viewModel.toggleFavorite(
                                                             com.example.source.SearchBook(
                                                                 id = it.comicId!!,
                                                                 sourceId = it.sourceId!!,
                                                                 title = it.title,
                                                                 author = it.author,
                                                                 cover = it.coverUri,
                                                             ),
                                                             true,
                                                             cat,
                                                         )
                                                     }
                                                     withSource.size to (selected.size - withSource.size)
                                                 },
                                                onDeleteDownloads = { list ->
                                                    val token = java.util.UUID.randomUUID().toString()
                                                    // 多批次并存：每批独立撤销/提交，后一批不能覆盖前一批
                                                    pendingDeleteBooks = pendingDeleteBooks + list
                                                    viewModel.scheduleBooksDeletion(list, token) {
                                                        pendingDeleteBooks = pendingDeleteBooks.filter { b ->
                                                            list.none { it.id == b.id }
                                                        }
                                                    }
                                                    homeScope.launch {
                                                        val bytes = viewModel.booksDiskBytes(list)
                                                        showUndo(
                                                            "已删除 ${list.size} 本，释放 ${formatShelfBytes(bytes)}（阅读进度保留）"
                                                        ) {
                                                            viewModel.cancelBooksDeletion(token)
                                                            pendingDeleteBooks = pendingDeleteBooks.filter { b ->
                                                                list.none { it.id == b.id }
                                                            }
                                                        }
                                                    }
                                                },
                                                onShowUndo = { message, undo -> showUndo(message, undo) },
                                                onMarkFinished = { book ->
                                                    viewModel.updateProgress(book.id, book.totalChapters, 0, true)
                                                },
                                                /* 「我喜欢的」的分类：与书架分类完全独立 */
                                                favoriteCategories = favoriteCategories,
                                                /* 隐私：与受保护分类同一套 PIN */
                                                favoritesProtected = viewModel.favoritesProtected.collectAsStateWithLifecycle().value,
                                                onVerifyPrivacyPin = { pin -> viewModel.verifyPrivacyPin(pin) },
                                                onAddFavoriteCategory = { viewModel.addFavoriteCategory(it) },
                                                onRenameFavoriteCategory = { old, new ->
                                                    viewModel.renameFavoriteCategory(old, new)
                                                },
                                                onDeleteFavoriteCategory = { viewModel.deleteFavoriteCategory(it) },
                                            )
                                            2 -> {
                                                var dailyGoalState by remember { mutableIntStateOf(viewModel.prefs.dailyGoalMinutes) }
                                                /* 神回订阅只在这一 Tab 内：切到统计页才读 DataStore / 查库 */
                                                val godMoments by viewModel.godMoments.collectAsStateWithLifecycle()
                                                val godStyle by viewModel.godSettings.rankingStyle
                                                    .collectAsStateWithLifecycle(initialValue = com.example.god.GodRankingStyle.PODIUM)
                                                val godGyro by viewModel.godSettings.gyroParallaxEnabled
                                                    .collectAsStateWithLifecycle(initialValue = true)
                                                StatisticsScreen(
                                                    books = books,
                                                    totalReadTimeSecondsFlow = viewModel.totalReadTimeSeconds,
                                                    readingRecords = readingRecords,
                                                    readingSessions = readingSessions,
                                                    dailyGoalMinutes = dailyGoalState,
                                                    onGoalChange = {
                                                        viewModel.prefs.dailyGoalMinutes = it
                                                        dailyGoalState = it  // 触发重组刷新目标环
                                                    },
                                                onGoToShelf = { selectedTab = 1 },
                                                onDeleteRecord = { viewModel.deleteReadingRecord(it.id) },
                                                recordCovers = libraryViewModel.recordCovers.collectAsStateWithLifecycle().value,
                                                recordBooks = libraryViewModel.recordBooks.collectAsStateWithLifecycle().value,
                                                recordCoverHeaders = libraryViewModel.recordCoverHeaders.collectAsStateWithLifecycle().value,
                                                onResolveRecordCoverHeaders = libraryViewModel::resolveRecordCoverHeaders,
                                                onResolveRecordCovers = { libraryViewModel.resolveMissingRecordCovers(it) },
                                                onOpenRecordDetail = { book ->
                                                    libraryViewModel.openComic(book)
                                                    navController.navigate("comic_chapters")
                                                },
                                                onOpenBook = { book ->
                                                    viewModel.selectBook(book)
                                                    if (book.isComic) {
                                                        navController.navigate("comic_reader")
                                                    } else {
                                                        navController.navigate("reader")
                                                    }
                                                },
                                                /* ── 神回排行榜 ── */
                                                godMoments = godMoments,
                                                godStyle = godStyle,
                                                godGyroEnabled = godGyro,
                                                onOpenGodRanking = { navController.navigate("god_ranking") },
                                                onGodMomentClick = { m ->
                                                    // 排行榜条目 → 漫画主页（自动定位到该话 + 金色高亮）。
                                                    // ⚠️ 漫画主页的头部横幅直接吃这里传入的 SearchBook：
                                                    // 只给 id 的话封面、模糊背景、作者全空，还会把原始
                                                    // "#mdapi:…" id 当作者显示出来（用户报"从排行榜进
                                                    // 详情丢了封面"）。收藏表里有这本书的快照
                                                    // （title/author/coverUrl），按 key 取出来补齐。
                                                    val parts = m.bookId.split("::")
                                                    val sid = parts.getOrNull(0).orEmpty()
                                                    val cid = parts.getOrNull(1).orEmpty()
                                                    if (sid.isNotBlank() && cid.isNotBlank()) {
                                                        com.example.god.GodMomentJumpState.chapterId = m.chapterId
                                                        val fav = viewModel.favoriteItems.value.firstOrNull {
                                                            it.favorite.sourceId == sid && it.favorite.comicId == cid
                                                        }?.favorite
                                                        libraryViewModel.openComic(
                                                            com.example.source.SearchBook(
                                                                id = cid,
                                                                sourceId = sid,
                                                                title = m.bookTitle,
                                                                author = fav?.author.orEmpty(),
                                                                cover = fav?.coverUrl,
                                                                comicId = cid,
                                                                format = "comic",
                                                            )
                                                        )
                                                        navController.navigate("comic_chapters")
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            this@MainActivity, "这本书已不在书库中",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                },
                                                onEditGodMoment = { m ->
                                                    com.example.god.GodMomentEditTarget.entity = m
                                                    navController.navigate("god_editor")
                                                },
                                                onDeleteGodMoment = { m ->
                                                    viewModel.deleteGodMoment(m)
                                                }
                                                )
                                            }
                                            3 -> SettingsTabScreen(
                                                    prefs = viewModel.prefs,
                                                    backupManager = viewModel.backupManager,
                                                    categories = categories,
                                                    extraBottomPadding = LocalAppBottomInset.current,
                                                    onAdultSourcesChange = { sourceViewModel.setAdultSourcesEnabled(it) },
                                                    onAddCategory = { name ->
                                                        viewModel.addCategory(name)
                                                    },
                                                onOpenSourceManager = {
                                                    navController.navigate("source_management")
                                                },
                                                autoNightModeVal = autoNightMode,
                                                onAutoNightModeChange = { viewModel.updateAutoNightMode(it) },
                                                blueLightFilterVal = blueLightFilter,
                                                onBlueLightFilterChange = { viewModel.updateBlueLightFilter(it) },
                                                blueLightAlphaVal = blueLightAlpha,
                                                onBlueLightAlphaChange = { viewModel.updateBlueLightAlpha(it) },
                                                colorPrimaryIndexVal = colorPrimaryIndex,
                                                colorSecondaryIndexVal = colorSecondaryIndex,
                                                onColorThemeChange = { p, s -> viewModel.updateColorTheme(p, s) },
                                                orientationLockVal = orientationLock,
                                                onOrientationLockChange = { viewModel.updateScreenOrientationLock(it) },
                                                hapticsEnabledVal = hapticsEnabled,
                                                onHapticsChange = { viewModel.updateHapticsEnabled(it) },
                                                renderQualityVal = renderQualityIdx,
                                                onRenderQualityChange = { viewModel.updateRenderQuality(it) },
                                                cardTweaksState = cardTweaks,
                                                /* ── 隐私模式（第七轮第 6.4 条；第八轮审查修复：
                                                    底部 Tab 的设置页此前漏传隐私参数，全部落到默认
                                                    { false }——隐私模式在 Tab 设置页永远无法开启） ── */
                                                privacyModeEnabled = viewModel.privacyModeEnabled.collectAsStateWithLifecycle().value,
                                                onEnablePrivacyMode = { pin -> viewModel.enablePrivacyMode(pin) },
                                                onDisablePrivacyMode = { pin -> viewModel.disablePrivacyMode(pin) },
                                                onVerifyPrivacyPin = { pin -> viewModel.verifyPrivacyPin(pin) },
                                                onChangePrivacyPin = { old, new -> viewModel.changePrivacyPin(old, new) },
                                                onToggleCategoryProtected = { cat, protected ->
                                                    viewModel.setCategoryProtected(cat.id, protected)
                                                },
                                                incognitoBrowsingEnabled = viewModel.incognitoBrowsingEnabled.collectAsStateWithLifecycle().value,

                                                onSetIncognitoBrowsing = { viewModel.setIncognitoBrowsing(it) },

                                                favoritesProtected = viewModel.favoritesProtected.collectAsStateWithLifecycle().value,
                                                onSetFavoritesProtected = { viewModel.setFavoritesProtected(it) },
                                                /* ── 神回设置分组 ── */
                                                godSettings = viewModel.godSettings,
                                                onOpenGodRanking = { navController.navigate("god_ranking") },
                                            )
                                        }
                                    }
                                }
                            }
                            }
                            // 底部 Tab 栏：多选态下被悬浮操作栏"替换"（弹簧滑出）
                            val tabBarVisible by com.example.ui.shelf.ShelfChrome.tabBarVisible.collectAsStateWithLifecycle()
                            androidx.compose.animation.AnimatedVisibility(
                                visible = tabBarVisible,
                                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) +
                                    fadeIn(tween(220)),
                                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) +
                                    fadeOut(tween(180)),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                AppBottomTabBar(
                                    items = tabItems,
                                    selectedIndex = selectedTab,
                                    onTabSelected = { selectedTab = it },
                                    collapseState = tabBarCollapseState,
                                    backdrop = tabBackdrop.takeIf { renderQuality.realtimeGlass },
                                )
                            }
                            }
                        }
 }
                        composable("settings") {
                            val categories by viewModel.allCategories.collectAsStateWithLifecycle()
                            // 排行榜陈列方式按钮的跳转信标：读一次即清空
                            val focusGodSettings = remember {
                                com.example.god.GodStyleSettingsJump.pending.also {
                                    com.example.god.GodStyleSettingsJump.pending = false
                                }
                            }
                            SettingsTabScreen(
                                focusGodSettings = focusGodSettings,
                                onOpenSourceManager = {
                                    navController.navigate("source_management")
                                },
                                prefs = viewModel.prefs,
                                backupManager = viewModel.backupManager,
                                categories = categories,
                                onAdultSourcesChange = { sourceViewModel.setAdultSourcesEnabled(it) },
                                onAddCategory = { name ->
                                    viewModel.addCategory(name)
                                },
                                onBack = {
                                    navController.popBackStack()
                                },
                                autoNightModeVal = autoNightMode,
                                onAutoNightModeChange = { viewModel.updateAutoNightMode(it) },
                                blueLightFilterVal = blueLightFilter,
                                onBlueLightFilterChange = { viewModel.updateBlueLightFilter(it) },
                                blueLightAlphaVal = blueLightAlpha,
                                onBlueLightAlphaChange = { viewModel.updateBlueLightAlpha(it) },
                                colorPrimaryIndexVal = colorPrimaryIndex,
                                colorSecondaryIndexVal = colorSecondaryIndex,
                                onColorThemeChange = { p, s -> viewModel.updateColorTheme(p, s) },
                                orientationLockVal = orientationLock,
                                onOrientationLockChange = { viewModel.updateScreenOrientationLock(it) },
                                hapticsEnabledVal = hapticsEnabled,
                                onHapticsChange = { viewModel.updateHapticsEnabled(it) },
                                renderQualityVal = renderQualityIdx,
                                onRenderQualityChange = { viewModel.updateRenderQuality(it) },
                                cardTweaksState = cardTweaks,
                                /* ── 隐私模式（第七轮第 6.4 条） ── */
                                privacyModeEnabled = viewModel.privacyModeEnabled.collectAsStateWithLifecycle().value,
                                onEnablePrivacyMode = { pin -> viewModel.enablePrivacyMode(pin) },
                                onDisablePrivacyMode = { pin -> viewModel.disablePrivacyMode(pin) },
                                onVerifyPrivacyPin = { pin -> viewModel.verifyPrivacyPin(pin) },
                                onChangePrivacyPin = { old, new -> viewModel.changePrivacyPin(old, new) },
                                onToggleCategoryProtected = { cat, protected ->
                                    viewModel.setCategoryProtected(cat.id, protected)
                                },
                                incognitoBrowsingEnabled = viewModel.incognitoBrowsingEnabled.collectAsStateWithLifecycle().value,

                                onSetIncognitoBrowsing = { viewModel.setIncognitoBrowsing(it) },

                                favoritesProtected = viewModel.favoritesProtected.collectAsStateWithLifecycle().value,
                                onSetFavoritesProtected = { viewModel.setFavoritesProtected(it) },
                                /* ── 神回设置分组 ── */
                                godSettings = viewModel.godSettings,
                                onOpenGodRanking = { navController.navigate("god_ranking") },
                            )
                        }

                        /* ── 神回排行榜（全屏完整版） ── */
                        composable("god_ranking") {
                            com.example.god.GodRankingScreen(
                                moments = viewModel.godMoments.collectAsStateWithLifecycle().value,
                                style = viewModel.godSettings.rankingStyle
                                    .collectAsStateWithLifecycle(initialValue = com.example.god.GodRankingStyle.PODIUM).value,
                                gyroEnabled = viewModel.godSettings.gyroParallaxEnabled
                                    .collectAsStateWithLifecycle(initialValue = true).value,
                                onBack = { navController.popBackStack() },
                                onItemClick = { m ->
                                    val parts = m.bookId.split("::")
                                    val sid = parts.getOrNull(0).orEmpty()
                                    val cid = parts.getOrNull(1).orEmpty()
                                    if (sid.isNotBlank() && cid.isNotBlank()) {
                                        com.example.god.GodMomentJumpState.chapterId = m.chapterId
                                        // 同统计页入口：从收藏快照补齐 author/cover，
                                        // 否则漫画主页头部丢封面还把原始 id 当作者
                                        val fav = viewModel.favoriteItems.value.firstOrNull {
                                            it.favorite.sourceId == sid && it.favorite.comicId == cid
                                        }?.favorite
                                        libraryViewModel.openComic(
                                            com.example.source.SearchBook(
                                                id = cid, sourceId = sid, title = m.bookTitle,
                                                author = fav?.author.orEmpty(),
                                                cover = fav?.coverUrl,
                                                comicId = cid, format = "comic",
                                            )
                                        )
                                        navController.popBackStack()
                                        navController.navigate("comic_chapters")
                                    }
                                },
                                onEdit = { m ->
                                    com.example.god.GodMomentEditTarget.entity = m
                                    navController.navigate("god_editor")
                                },
                                onDelete = { m -> viewModel.deleteGodMoment(m) },
                                onOpenStyleSettings = {
                                    // 置位跳转信标：设置页打开后动画滚动到神回设置分区
                                    com.example.god.GodStyleSettingsJump.pending = true
                                    navController.navigate("settings")
                                },
                            )
                        }

                        /* ── 神回编辑窗口（从排行榜 / 设置进入的独立路由） ── */
                        composable("god_editor") {
                            val binding = com.example.god.rememberGodEditBinding()
                            var started by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                val e = com.example.god.GodMomentEditTarget.entity
                                if (e == null) {
                                    navController.popBackStack()
                                } else {
                                    binding.openEdit(e)
                                    started = true
                                }
                            }
                            LaunchedEffect(binding.request) {
                                // 窗口关闭（保存或取消）→ 退回上一个界面
                                if (started && binding.request == null) {
                                    com.example.god.GodMomentEditTarget.entity = null
                                    navController.popBackStack()
                                }
                            }
                            androidx.activity.compose.BackHandler { binding.request = null }
                            com.example.god.GodMomentEditHost(binding = binding, onSaved = { })
                        }

                        composable(
                            "reader",
                            enterTransition = {
                                // 2026-09-21：原来是 EnterTransition.None —— 打开书籍时
                                // 页面瞬间跳出来，是全局最生硬的一处转场。
                                // 改为 iOS 的 push 转场：新页面自右侧滑入并淡入。
                                // 只滑 1/3 屏宽而非整屏，配合旧页面淡出，既有方向感又不拖沓。
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth / 3 },
                                    animationSpec = tween(280, easing = com.example.ui.theme.IosMotion.EaseOut)
                                ) + fadeIn(tween(200))
                            },
                            exitTransition = { fadeOut(tween(220)) },
                            popEnterTransition = {
                                fadeIn(tween(240))
                            },
                            popExitTransition = {
                                fadeOut(tween(220)) +
                                    scaleOut(
                                        targetScale = 0.96f,
                                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                                    )
                            }
                        ) { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val selectedBook by viewModel.selectedBook.collectAsStateWithLifecycle()
                            val chapters by viewModel.chapters.collectAsStateWithLifecycle()
                            val readerLoading by viewModel.readerLoading.collectAsStateWithLifecycle()
                            val loadedChapterIndices by viewModel.loadedChapterIndices.collectAsStateWithLifecycle()
                            val readerLoadError by viewModel.readerLoadError.collectAsStateWithLifecycle()
                            val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
                            val highlights by viewModel.highlights.collectAsStateWithLifecycle()
                            val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
                            val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()

                            val currentShelf by viewModel.allBooks.collectAsStateWithLifecycle()
                            val revisedBook = currentShelf.firstOrNull { it.id == selectedBook?.id }
                            LaunchedEffect(revisedBook?.filePath) {
                                if (selectedBook != null && com.example.source.WholeBookNovelSources.contains(selectedBook?.sourceId) &&
                                    revisedBook != null && revisedBook.filePath != selectedBook?.filePath) viewModel.retrySelectedBook()
                            }

                            ReaderScreen(
                                book = selectedBook,
                                bookTitle = selectedBook?.title ?: "本地阅读",
                                chapters = chapters,
                                readerLoading = readerLoading,
                                loadedChapterIndices = loadedChapterIndices,
                                readerLoadError = readerLoadError,
                                onRetryLoad = viewModel::retrySelectedBook,
                                onEnsureChapterLoaded = viewModel::ensureActiveChapter,
                                onBack = { navController.popBackStack() },
                                onUpdateProgress = { id, chapterIdx, offset, isFinished ->
                                    viewModel.updateProgress(id, chapterIdx, offset, isFinished)
                                },
                                prefs = viewModel.prefs,
                                ttsManager = viewModel.ttsManager,
                                highlights = highlights,
                                bookmarks = bookmarks,
                                onAddBookmark = { bookId, chIdx, offset, title, snippet ->
                                    viewModel.addBookmark(bookId, chIdx, offset, title, snippet)
                                    com.example.ui.mascot.MascotAnimationController.play(com.example.ui.mascot.MascotEvent.AddBookmark)
                                },
                                onDeleteBookmark = { id ->
                                    viewModel.deleteBookmark(id)
                                },
                                onAddHighlight = { bookId, chIdx, text, note, color ->
                                    viewModel.addHighlight(bookId, chIdx, text, note, color)
                                },
                                onDeleteHighlight = { id ->
                                    viewModel.deleteHighlight(id)
                                },
                                searchResults = searchResults,
                                isSearching = isSearching,
                                onSearch = { query ->
                                    viewModel.searchFullText(query)
                                },
                                onRecordTime = { seconds ->
                                    viewModel.recordTime(seconds)
                                },
                                onSessionEnd = { session ->
                                    viewModel.addReadingSession(session)
                                },
                                onCheckNovelUpdate = if (com.example.source.WholeBookNovelSources.contains(selectedBook?.sourceId)) ({
                                    selectedBook?.let(libraryViewModel::checkNovelUpdate)
                                }) else null,
                            )
                            com.example.library.NovelUpdatePanel(libraryViewModel, selectedBook)
                        }
 }
                        composable(
                            "comic_reader",
                            enterTransition = {
                                fadeIn(tween(320)) +
                                    scaleIn(
                                        initialScale = 0.96f,
                                        animationSpec = tween(320, easing = FastOutSlowInEasing)
                                    )
                            },
                            exitTransition = { fadeOut(tween(220)) },
                            popEnterTransition = {
                                fadeIn(tween(300)) +
                                    scaleIn(
                                        initialScale = 0.98f,
                                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                                    )
                            },
                            popExitTransition = {
                                fadeOut(tween(220)) +
                                    scaleOut(
                                        targetScale = 0.96f,
                                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                                    )
                            }
                        ) { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val selectedBook by viewModel.selectedBook.collectAsStateWithLifecycle()
                            val chapters by viewModel.chapters.collectAsStateWithLifecycle()
                            val libraryBooks by viewModel.allBooks.collectAsStateWithLifecycle()

                            ComicReaderScreen(
                                book = selectedBook,
                                chapters = chapters,
                                libraryBooks = libraryBooks,
                                onOpenBook = { nextBook ->
                                    viewModel.selectBook(nextBook)
                                },
                                onBack = { navController.popBackStack() },
                                onUpdateProgress = { id, pageIdx, offset, isFinished ->
                                    viewModel.updateProgress(id, pageIdx, offset, isFinished)
                                },
                                onRecordTime = { seconds ->
                                    // 阅读统计修复：本地漫画阅读器走 selectedBook 自动关联，无需传书名
                                    viewModel.recordTime(seconds)
                                },
                                onSessionEnd = { session ->
                                    viewModel.addReadingSession(session)
                                },
                                // 本地漫画没有"话"的概念（一章 = 一页），整本视为一话
                                godContext = selectedBook?.takeIf { it.isComic }?.let { b ->
                                    com.example.god.GodMomentContext(
                                        bookId = "local_${b.id}",
                                        chapterId = b.id.toString(),
                                        bookTitle = b.title,
                                        chapterTitle = b.title,
                                        chapterNumber = 1,
                                    )
                                },
                                onGodMomentSaved = {
                                    android.widget.Toast.makeText(
                                        this@MainActivity, "已加入神回排行榜 ✦", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        } }

                        composable(
                            "comic_chapters",
                            enterTransition = {
                                fadeIn(tween(280)) + slideInHorizontally { it / 4 }
                            },
                            exitTransition = { fadeOut(tween(200)) },
                            popEnterTransition = { fadeIn(tween(260)) },
                            popExitTransition = { fadeOut(tween(200)) }
                        ) { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val comicBook by libraryViewModel.comicBook.collectAsStateWithLifecycle()
                            val comicChapters by libraryViewModel.comicChapters.collectAsStateWithLifecycle()
                            val comicChaptersLoading by libraryViewModel.comicChaptersLoading.collectAsStateWithLifecycle()
                            val comicChaptersError by libraryViewModel.comicChaptersError.collectAsStateWithLifecycle()
                            val comicDownloading by libraryViewModel.comicDownloading.collectAsStateWithLifecycle()
                            val comicDownloadProgress by libraryViewModel.comicDownloadProgress.collectAsStateWithLifecycle()
                            val comicPaused by libraryViewModel.comicPaused.collectAsStateWithLifecycle()
                            val comicMessage by libraryViewModel.comicMessage.collectAsStateWithLifecycle()
                            val comicIsTextMode by libraryViewModel.comicIsTextMode.collectAsStateWithLifecycle()
                            val comicContext = androidx.compose.ui.platform.LocalContext.current

                            /* ── 「我喜欢的」与阅读进度（与是否下载无关） ── */
                            val comicSourceId = comicBook?.sourceId ?: ""
                            val comicId = comicBook?.id ?: ""
                            val chapterReadEntities by remember(comicSourceId, comicId) {
                                viewModel.favoriteRepository.chapterStatesFlow(comicSourceId, comicId)
                            }.collectAsStateWithLifecycle(emptyList())
                            val chapterReadStates = remember(chapterReadEntities) {
                                chapterReadEntities.associateBy { it.chapterId }
                            }
                            val comicProgress by remember(comicSourceId, comicId) {
                                viewModel.favoriteRepository.progressFlow(comicSourceId, comicId)
                            }.collectAsStateWithLifecycle(null)
                            val comicFavoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
                            val comicFavorite = comicFavoriteKeys.contains("$comicSourceId::$comicId")
                            val comicFavoriteCategories by viewModel.favoriteCategories.collectAsStateWithLifecycle()
                            val allBooksForChapters by viewModel.allBooks.collectAsStateWithLifecycle()
                            // 已下载章节：本地章节书由 ComicLocalImporter 落库，
                            // 书名 = "{漫画标题} · {章节名}"（经文件名非法字符清洗 + 120 字截断）。
                            // 判定必须圈定同一部漫画：优先 sourceId+comicId 精确匹配；
                            // 旧版本下载的书没记来源，回退到书名相等。此前的全库 contains
                            // 模糊匹配会把别的书里同名的章节（"第1话"满地都是）误判成已下载。
                            val downloadedChapterIds = remember(comicBook, comicChapters, allBooksForChapters) {
                                val comicTitle = comicBook?.title.orEmpty()
                                fun sanitize(s: String) = s
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    .trim()
                                    .take(120)
                                comicChapters.filter { ch ->
                                    if (comicTitle.isBlank()) return@filter false
                                    val expected = sanitize("$comicTitle · ${ch.title}")
                                    allBooksForChapters.any { b ->
                                        b.isComic && b.title == expected && (
                                            (b.sourceId == comicSourceId && b.comicId == comicId) ||
                                                (b.sourceId.isNullOrBlank() && b.comicId.isNullOrBlank())
                                            )
                                    }
                                }.map { it.id }.toSet()
                            }
                            /** 继续阅读 / 点击章节时的起始页 */
                            var startPage by rememberSaveable { mutableIntStateOf(0) }
                            var favoriteCategorySheet by remember { mutableStateOf(false) }

                            /* ── 神回：本话是否被标记为神回 + 排行榜跳转定位 ── */
                            // Room flow 首帧前 collectAsState 的初始值是空 map：这几百毫秒里
                            // 神回话会先渲染成普通章节行（滑动书签手势活着），一滑就写库+弹
                            // 提示，随后金卡顶替行——看起来"卡片没反应但弹了书签提示"。
                            // 所以随 map 一起跟踪"已加载"，加载前不武装书签手势、写入回调也拦。
                            val godMomentsState by produceState(
                                initialValue = emptyMap<String, com.example.god.GodMomentEntity>() to false,
                                key1 = comicSourceId, key2 = comicId,
                            ) {
                                if (comicSourceId.isBlank() || comicId.isBlank()) {
                                    value = emptyMap<String, com.example.god.GodMomentEntity>() to true
                                } else {
                                    viewModel.godRepository.observeChapterMap("$comicSourceId::$comicId")
                                        .collect { value = it to true }
                                }
                            }
                            val godMomentsForBook = godMomentsState.first
                            val godMomentsReady = godMomentsState.second
                            // 信标读一次即清空（避免二次进入误触发定位）
                            val godFocusChapterId = remember(comicSourceId, comicId) {
                                val v = com.example.god.GodMomentJumpState.chapterId
                                com.example.god.GodMomentJumpState.chapterId = null
                                v
                            }

                            /* ── 换源：候选来源 + 迁移确认 ── */
                            var migrateSheet by remember { mutableStateOf(false) }
                            var migrateLoading by remember { mutableStateOf(false) }
                            var migrateTitle by remember { mutableStateOf("") }
                            var migrateCandidates by remember {
                                mutableStateOf<List<com.example.ui.favorite.SourceCandidate>>(emptyList())
                            }
                            val migrateScope = rememberCoroutineScope()

                            if (migrateSheet) {
                                com.example.ui.favorite.SourceMigrateSheet(
                                    title = migrateTitle,
                                    candidates = migrateCandidates,
                                    loading = migrateLoading,
                                    onPick = { candidate ->
                                        migrateSheet = false
                                        val target = candidate.book
                                        migrateScope.launch {
                                            // 先取新源章节列表（用于按序号映射已读状态），再整体迁移
                                            val newSource = libraryViewModel.sourceManager.availableSources.value
                                                .firstOrNull { it.id == candidate.sourceId } as? com.example.source.ComicSource
                                            val chapters = runCatching {
                                                when (val r = newSource?.getChapters(target.id)) {
                                                    is com.example.source.SourceResult.Success -> r.data
                                                    else -> emptyList<com.example.source.ComicChapter>()
                                                }
                                            }.getOrDefault(emptyList())
                                            viewModel.migrateComic(
                                                fromSourceId = comicSourceId,
                                                fromComicId = comicId,
                                                toSourceId = candidate.sourceId,
                                                toComicId = target.id,
                                                newChapters = chapters,
                                            )
                                        }
                                        // 迁移后直接切到新源的这本（页面内刷新，不返回书库）
                                        libraryViewModel.openComic(target)
                                        AppToast.makeText(
                                            comicContext, "已换到「${candidate.sourceName}」，进度已迁移",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    onDismiss = { migrateSheet = false },
                                )
                            }

                            if (favoriteCategorySheet) {
                                val putInFavoriteCategory: (String) -> Unit = { name ->
                                    comicBook?.let { book ->
                                        if (comicFavorite) {
                                            viewModel.moveFavoritesToCategory(
                                                listOf(com.example.data.favorite.favoriteKey(book.sourceId, book.id)), name,
                                            )
                                        } else {
                                            viewModel.toggleFavorite(book, true, name, comicChapters)
                                        }
                                    }
                                    favoriteCategorySheet = false
                                }
                                com.example.ui.shelf.CategoryPickerSheet(
                                    categories = (listOf(com.example.data.favorite.FAV_DEFAULT_CATEGORY) +
                                        comicFavoriteCategories.map { it.name }
                                            .filter { it != com.example.data.favorite.ALL_FAV_CATEGORY_NAME }).distinct(),
                                    title = "放入「我喜欢的」哪个分类？",
                                    onPick = putInFavoriteCategory,
                                    onDismiss = { favoriteCategorySheet = false },
                                    onCreate = { name ->
                                        viewModel.addFavoriteCategory(name)
                                        putInFavoriteCategory(name)
                                    },
                                )
                            }

                            LaunchedEffect(comicMessage) {
                                comicMessage?.let {
                                    AppToast.makeText(comicContext, it, android.widget.Toast.LENGTH_LONG).show()
                                    libraryViewModel.clearComicMessage()
                                }
                            }

                            ComicChaptersScreen(
                                book = comicBook,
                                sourceName = favoriteSourceNames.firstOrNull { it.id == comicBook?.sourceId }?.name,
                                onSearchText = { keyword ->
                                    libraryViewModel.requestAggregateSearch(keyword, comicIsTextMode)
                                    selectedTab = 0
                                    if (!navController.popBackStack("home", false)) {
                                        navController.navigate("home") { launchSingleTop = true }
                                    }
                                },
                                chapters = comicChapters,
                                loading = comicChaptersLoading,
                                error = comicChaptersError,
                                bookmarkedChapterIds = chapterReadEntities.filter { it.bookmarked }.map { it.chapterId }.toSet(),
                                onSetChapterBookmark = { chapterId, chapterIndex, bookmarked ->
                                    // 神回章节不可加书签（定稿）：写入回调这层兜底拦掉
                                    // 神回 map 加载窗口期的滑动，不写库也不弹提示
                                    if (godMomentsForBook[chapterId] == null) {
                                        viewModel.setChapterBookmark(comicSourceId, comicId, chapterId, chapterIndex, bookmarked)
                                        AppToast.makeText(
                                            comicContext,
                                            if (bookmarked) "已添加书签" else "已取消书签",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                downloadingChapters = comicDownloading,
                                downloadProgress = comicDownloadProgress,
                                pausedChapters = comicPaused,
                                textMode = comicIsTextMode,
                                onDownloadNovel = if (comicBook?.sourceId == "auto_novel") ({
                                    comicBook?.let { libraryViewModel.startDownload(it, "epub") }
                                    AppToast.makeText(comicContext, "已请求整本下载，可在下载管理查看进度", Toast.LENGTH_SHORT).show()
                                }) else null,
                                onBack = { navController.popBackStack() },
                                onRetry = { comicBook?.let { libraryViewModel.openComic(it) } },
                                onChapterClick = { chapter ->
                                    if (comicIsTextMode) {
                                        libraryViewModel.loadChapterText(chapter)
                                        navController.navigate("novel_reader_online")
                                    } else {
                                        libraryViewModel.loadChapterImages(chapter)
                                        navController.navigate("comic_reader_online")
                                    }
                                },
                                onDownloadChapter = { chapter ->
                                    comicBook?.let { libraryViewModel.downloadComicChapter(it, chapter) }
                                },
                                onDownloadAll = {
                                    comicChapters.forEach { chapter ->
                                        comicBook?.let { libraryViewModel.downloadComicChapter(it, chapter) }
                                    }
                                },
                                /* ── 喜欢 / 阅读进度（三态解耦） ── */
                                favorite = comicFavorite,
                                // 没有来源信息的书不能被喜欢（手动导入的本地文件）
                                favoriteEnabled = comicSourceId.isNotBlank() && comicId.isNotBlank(),
                                onToggleFavorite = { next ->
                                    comicBook?.let { b -> viewModel.toggleFavorite(b, next, chapters = comicChapters) }
                                },
                                onFavoriteLongPress = { favoriteCategorySheet = true },
                                onFavoriteDisabledClick = {
                                    AppToast.makeText(
                                        comicContext, "这本书没有来源信息，无法加入「我喜欢的」",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                chapterStates = chapterReadStates,
                                downloadedChapterIds = downloadedChapterIds,
                                progress = comicProgress,
                                onReadChapterAt = { chapter, page ->
                                    startPage = page
                                    ComicJumpState.startPage = page
                                    if (comicIsTextMode) {
                                        libraryViewModel.loadChapterText(chapter)
                                        navController.navigate("novel_reader_online")
                                    } else {
                                        libraryViewModel.loadChapterImages(chapter)
                                        navController.navigate("comic_reader_online")
                                    }
                                },
                                onMarkChapterRead = { chapter, index, read ->
                                    viewModel.markComicChapterRead(comicSourceId, comicId, chapter.id, index, read)
                                },
                                onMarkReadUpTo = { _, index ->
                                    viewModel.markComicChaptersReadUpTo(comicSourceId, comicId, index)
                                },
                                onChangeSource = {
                                    // 换源：先在各书源里找同一本书，确认后再按章节序号迁移进度
                                    val b = comicBook
                                    if (b == null || comicSourceId.isBlank()) {
                                        AppToast.makeText(
                                            comicContext, "这本书没有来源信息，无法换源",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        migrateTitle = b.title
                                        migrateSheet = true
                                        migrateLoading = true
                                        migrateCandidates = emptyList()
                                        migrateScope.launch {
                                            migrateCandidates =
                                                com.example.ui.favorite.ComicSourceMigration.findCandidates(
                                                    title = b.title,
                                                    excludeSourceId = comicSourceId,
                                                    sources = libraryViewModel.sourceManager.availableSources.value
                                                        .mapNotNull { it as? com.example.source.ComicSource },
                                                )
                                            migrateLoading = false
                                        }
                                    }
                                },
                                onMarkSeen = {
                                    if (comicSourceId.isNotBlank() && comicId.isNotBlank()) {
                                        viewModel.markComicSeen(comicSourceId, comicId, comicChapters)
                                    }
                                },
                                /* ── 神回态章节卡片 + 排行榜跳转定位 ── */
                                godMoments = godMomentsForBook,
                                godMomentsReady = godMomentsReady,
                                godFocusChapterId = godFocusChapterId,
                                onPauseDownload = { chapter ->
                                    libraryViewModel.pauseComicChapter(chapter.id)
                                },
                                onResumeDownload = { chapter ->
                                    comicBook?.let { libraryViewModel.downloadComicChapter(it, chapter) }
                                },
                                onCancelDownload = { chapter ->
                                    libraryViewModel.cancelComicChapter(chapter.id)
                                }
                            )
                        } }

                        composable(
                            "comic_reader_online",
                            enterTransition = {
                                fadeIn(tween(300)) +
                                    scaleIn(
                                        initialScale = 0.96f,
                                        animationSpec = tween(300)
                                    )
                            },
                            exitTransition = { fadeOut(tween(220)) },
                            popEnterTransition = { fadeIn(tween(280)) },
                            popExitTransition = { fadeOut(tween(220)) }
                        ) { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val comicBook by libraryViewModel.comicBook.collectAsStateWithLifecycle()
                            val activeChapter by libraryViewModel.activeComicChapter.collectAsStateWithLifecycle()
                            val images by libraryViewModel.comicChapterImages.collectAsStateWithLifecycle()
                            val imageHeaders by libraryViewModel.comicChapterHeaders.collectAsStateWithLifecycle()
                            val loading by libraryViewModel.comicChapterLoading.collectAsStateWithLifecycle()
                            val error by libraryViewModel.comicChapterError.collectAsStateWithLifecycle()
                            val comicChaptersList by libraryViewModel.comicChapters.collectAsStateWithLifecycle()

                            val activeChapterIdx = comicChaptersList.indexOfFirst { it.id == activeChapter?.id }
                            val prevChapter = if (activeChapterIdx > 0) comicChaptersList[activeChapterIdx - 1] else null
                            val nextChapter = if (activeChapterIdx in 0 until (comicChaptersList.size - 1))
                                comicChaptersList[activeChapterIdx + 1] else null

                            OnlineComicReaderScreen(
                                title = activeChapter?.title ?: comicBook?.title ?: "在线漫画",
                                imageUrls = images,
                                loading = loading,
                                error = error,
                                imageHeaders = imageHeaders,
                                resolveImage = { url -> libraryViewModel.resolveComicImage(url) },
                                resolveImageHeaders = { url -> libraryViewModel.resolveComicImageHeaders(url) },
                                onRecordTime = { seconds ->
                                    viewModel.recordTime(seconds, comicBook?.title ?: activeChapter?.title, comicBook)
                                },
                                onSessionEnd = { session ->
                                    viewModel.addReadingSession(session)
                                },
                                onBack = {
                                    // 退出阅读器：强制落库一次（防抖窗口里未提交的页码不能丢）
                                    val ch = activeChapter
                                    comicBook?.let { b ->
                                        if (ch != null && ComicJumpState.lastPage >= 0) {
                                            viewModel.saveComicProgress(
                                                sourceId = b.sourceId,
                                                comicId = b.id,
                                                chapterId = ch.id,
                                                chapterIndex = comicChaptersList.indexOfFirst { it.id == ch.id },
                                                pageIndex = ComicJumpState.lastPage,
                                                pageCount = images.size,
                                                force = true,
                                            )
                                        }
                                    }
                                    navController.popBackStack()
                                },
                                onRetry = { activeChapter?.let { libraryViewModel.loadChapterImages(it) } },
                                bookKey = comicBook?.let { "online_${it.sourceId}_${it.id}" },
                                bookTitle = comicBook?.title,
                                chapters = comicChaptersList.map { com.example.ui.comic.ComicTocEntry(it.id, it.title) },
                                currentChapterIndex = activeChapterIdx,
                                onJumpToChapter = { idx ->
                                    comicChaptersList.getOrNull(idx)?.let { ch ->
                                        if (ch.id != activeChapter?.id) {
                                            ComicJumpState.startPage = 0
                                            libraryViewModel.loadChapterImages(ch)
                                        }
                                    }
                                },
                                // 翻页即记录进度（防抖 400ms 落库；退出时强制保存一次）
                                onPageChanged = { page, total ->
                                    val ch = activeChapter ?: return@OnlineComicReaderScreen
                                    ComicJumpState.lastPage = page
                                    // 读到章尾自动预取下一章（对齐 Mihon/Kotatsu）：
                                    // 剩 3 页时开始拉下一章图片列表 + 预热前两页
                                    if (total - page <= 3) {
                                        nextChapter?.let { libraryViewModel.prefetchNextComicChapter(it) }
                                    }
                                    val idx = comicChaptersList.indexOfFirst { it.id == ch.id }
                                    comicBook?.let { b ->
                                        viewModel.saveComicProgress(
                                            sourceId = b.sourceId,
                                            comicId = b.id,
                                            chapterId = ch.id,
                                            chapterIndex = idx,
                                            pageIndex = page,
                                            pageCount = total,
                                        )
                                    }
                                },
                                initialPage = ComicJumpState.startPage,
                                onPrevChapter = prevChapter?.let { ch -> {
                                    ComicJumpState.startPage = Int.MAX_VALUE // 回退进入上一章末页
                                    libraryViewModel.loadChapterImages(ch)
                                } },
                                onNextChapter = nextChapter?.let { ch -> {
                                    ComicJumpState.startPage = 0
                                    libraryViewModel.loadChapterImages(ch)
                                } },
                                // 在线漫画：bookId = "sourceId::comicId"，chapterId = 源章节 id
                                godContext = run {
                                    val b = comicBook
                                    val ch = activeChapter
                                    if (b == null || ch == null || b.sourceId.isBlank()) null
                                    else com.example.god.GodMomentContext(
                                        bookId = "${b.sourceId}::${b.id}",
                                        chapterId = ch.id,
                                        bookTitle = b.title,
                                        chapterTitle = ch.title,
                                        chapterNumber = (activeChapterIdx + 1).coerceAtLeast(1),
                                    )
                                },
                                onGodMomentSaved = {
                                    android.widget.Toast.makeText(
                                        this@MainActivity, "已加入神回排行榜 ✦", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        } }

                        composable(
                            "novel_reader_online",
                            enterTransition = {
                                fadeIn(tween(300)) + slideInHorizontally { it / 4 }
                            },
                            exitTransition = { fadeOut(tween(200)) }
                        ) { CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            val comicBook by libraryViewModel.comicBook.collectAsStateWithLifecycle()
                            val novelChapter by libraryViewModel.activeNovelChapter.collectAsStateWithLifecycle()
                            val novelText by libraryViewModel.novelChapterText.collectAsStateWithLifecycle()
                            val novelLoading by libraryViewModel.novelChapterLoading.collectAsStateWithLifecycle()
                            val novelError by libraryViewModel.novelChapterError.collectAsStateWithLifecycle()
                            val chapters by libraryViewModel.comicChapters.collectAsStateWithLifecycle()
                            val idx = chapters.indexOfFirst { it.id == novelChapter?.id }

                            com.example.ui.NovelReaderScreen(
                                bookTitle = comicBook?.title,
                                chapter = novelChapter,
                                text = novelText,
                                loading = novelLoading,
                                error = novelError,
                                hasPrevChapter = idx > 0,
                                hasNextChapter = idx >= 0 && idx < chapters.size - 1,
                                onBack = { navController.popBackStack() },
                                onRetry = { novelChapter?.let { libraryViewModel.loadChapterText(it) } },
                                onLoadPrev = {
                                    if (idx > 0) {
                                        libraryViewModel.loadChapterText(chapters[idx - 1])
                                    }
                                },
                                onLoadNext = {
                                    if (idx >= 0 && idx < chapters.size - 1) {
                                        libraryViewModel.loadChapterText(chapters[idx + 1])
                                    }
                                },
                                onRecordTime = { seconds ->
                                    // 阅读统计修复：传书名——此前在线小说阅读从不写阅读记录
                                    viewModel.recordTime(seconds, comicBook?.title ?: novelChapter?.title, comicBook)
                                },
                                onSessionEnd = { session ->
                                    viewModel.addReadingSession(session)
                                }
                            )
                        } }

                        composable("source_management") {
                            com.example.ui.source.SourceManagementScreen(
                                viewModel = sourceViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }

                    }
                }
            }
                        com.example.ui.mascot.MascotOverlay()
                    }
                }
            }
        }
    }
}
}
}
