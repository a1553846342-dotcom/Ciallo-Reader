package com.example.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.BackupManager
import com.example.data.CategoryEntity
import com.example.data.PreferencesManager
import com.example.ui.pageturn.PageTurnType
import com.example.ui.components.AppButton
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.scrollTiltSource
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.TabScreenHeader
import com.example.ui.components.rememberHeaderCollapsedSource
import com.example.ui.components.ColorMorphSwatch
import com.example.ui.components.AppLiquidButton
import com.example.ui.components.SegmentedPillSelector
import com.example.ui.components.DialogLiquidGlass
import com.example.ui.components.GlassCard
import com.ramotion.fluidslider.FluidSlider
import com.example.ui.components.readCardTweaks
import com.example.ui.components.writeCardTweaks
import com.example.ui.components.AppSwitch
import com.example.ui.components.PageTurnSelectorRow
import com.example.ui.components.CustomMinutesDialog
import com.example.ui.components.AppIconButton
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.clickableWithFeedback
import com.example.ui.theme.onColor
import com.example.ui.theme.glassTitleColor
import com.example.ui.help.LibraryHelpBottomSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabScreen(
    prefs: PreferencesManager,
    backupManager: BackupManager,
    categories: List<CategoryEntity>,
    onAddCategory: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenSourceManager: (() -> Unit)? = null,
    extraBottomPadding: Dp = 0.dp,
    autoNightModeVal: Boolean = prefs.autoNightMode,
    onAutoNightModeChange: (Boolean) -> Unit = { prefs.autoNightMode = it },
    blueLightFilterVal: Boolean = prefs.blueLightFilter,
    onBlueLightFilterChange: (Boolean) -> Unit = { prefs.blueLightFilter = it },
    blueLightAlphaVal: Float = prefs.blueLightAlpha,
    onBlueLightAlphaChange: (Float) -> Unit = { prefs.blueLightAlpha = it },
    colorPrimaryIndexVal: Int = prefs.colorPrimaryIndex,
    colorSecondaryIndexVal: Int = prefs.colorSecondaryIndex,
    onColorThemeChange: (Int, Int) -> Unit = { p, s -> prefs.colorPrimaryIndex = p; prefs.colorSecondaryIndex = s },
    onAdultSourcesChange: (Boolean) -> Unit = {},
    orientationLockVal: Int = prefs.screenOrientationLock,
    onOrientationLockChange: (Int) -> Unit = { prefs.screenOrientationLock = it },
    hapticsEnabledVal: Boolean = prefs.hapticsEnabled,
    onHapticsChange: (Boolean) -> Unit = { prefs.hapticsEnabled = it },
    renderQualityVal: Int = prefs.renderQuality,
    onRenderQualityChange: (Int) -> Unit = { prefs.renderQuality = it },
    /** 卡片微调共享状态：由 MainActivity 持有并注入 LocalCardTweaks；滑块实时改写。 */
    cardTweaksState: MutableState<com.example.ui.components.CardTweaks>? = null,
    /* ── 神回（GodMoment）设置分组 ── */
    godSettings: com.example.god.GodMomentSettingsStore? = null,
    onOpenGodRanking: (() -> Unit)? = null,
    /** true = 从排行榜陈列方式按钮跳入：打开后动画滚动到神回设置分区 */
    focusGodSettings: Boolean = false,
    /* ── 隐私模式（第七轮第 6.4 条）：全部带默认值，未接线时不出现该能力 ── */
    privacyModeEnabled: Boolean = false,
    onEnablePrivacyMode: suspend (String) -> Boolean = { false },
    onDisablePrivacyMode: suspend (String) -> Boolean = { false },
    onVerifyPrivacyPin: suspend (String) -> Boolean = { false },
    onChangePrivacyPin: suspend (String, String) -> Boolean = { _, _ -> false },
    onToggleCategoryProtected: (CategoryEntity, Boolean) -> Unit = { _, _ -> },
    /* ── 第九轮：全局无痕浏览开关（在线/不受保护分类的阅读也可不计统计） ── */
    incognitoBrowsingEnabled: Boolean = false,
    onSetIncognitoBrowsing: (Boolean) -> Unit = {},
    /* ── 「我喜欢的」密码保护（与受保护分类同一套 PIN） ── */
    favoritesProtected: Boolean = false,
    onSetFavoritesProtected: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val exportBackupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null && !backupBusy) scope.launch {
            backupBusy=true
            val temp=java.io.File(context.cacheDir,"backup_${java.util.UUID.randomUUID()}.zip")
            try {
                backupManager.exportBackupArchive(temp)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out,64*1024) } }
                        ?: error("无法写入选择的位置")
                }
                AppToast.makeText(context,"备份已保存",Toast.LENGTH_SHORT).show()
            } catch(e:Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                AppToast.makeText(context,"备份失败：${e.message}",Toast.LENGTH_LONG).show()
            } finally { temp.delete(); backupBusy=false }
        }
    }
    val importBackupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && !backupBusy) restoreUri=uri
    }
    if(restoreUri!=null) AlertDialog(
        onDismissRequest={ if(!backupBusy) restoreUri=null },
        title={ Text("恢复备份") },
        text={ Text("恢复将替换当前书架、标注、收藏和阅读记录，暂停正在下载的任务，并重新打开应用。") },
        confirmButton={ TextButton(enabled=!backupBusy,onClick={
            val uri=restoreUri ?: return@TextButton
            scope.launch {
                backupBusy=true
                val temp=java.io.File(context.cacheDir,"restore_${java.util.UUID.randomUUID()}.zip")
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                            val buffer=ByteArray(64*1024); var total=0L
                            while(true) {
                                val read=input.read(buffer); if(read<0) break
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                total+=read; require(total<=4L*1024*1024*1024) { "备份文件过大" }
                                output.write(buffer,0,read)
                            }
                        } } ?: error("无法打开备份")
                    }
                    check(backupManager.restoreBackupArchive(temp)) { "备份恢复失败" }
                    restoreUri=null
                    AppToast.makeText(context,"恢复完成",Toast.LENGTH_SHORT).show()
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(it)
                    }
                } catch(e:Exception) {
                    if(e is kotlinx.coroutines.CancellationException) throw e
                    AppToast.makeText(context,"恢复失败：${e.message}",Toast.LENGTH_LONG).show()
                } finally { temp.delete(); backupBusy=false }
            }
        }) { Text(if(backupBusy) "正在恢复…" else "恢复并重新打开") } },
        dismissButton={ TextButton(enabled=!backupBusy,onClick={ restoreUri=null }) { Text("取消") } },
    )

    var restReminderMinutes by remember { mutableStateOf(prefs.restReminderMinutes) }
    var showCustomMinutes by remember { mutableStateOf(false) }
    var autoNightMode by remember(autoNightModeVal) { mutableStateOf(autoNightModeVal) }
    var blueLightFilter by remember(blueLightFilterVal) { mutableStateOf(blueLightFilterVal) }
    var blueLightAlpha by remember(blueLightAlphaVal) { mutableStateOf(blueLightAlphaVal) }
    var pageTurnMode by remember { mutableStateOf(prefs.pageTurnMode) }
    var colorPrimaryIndex by remember(colorPrimaryIndexVal) { mutableStateOf(colorPrimaryIndexVal) }
    var colorSecondaryIndex by remember(colorSecondaryIndexVal) { mutableStateOf(colorSecondaryIndexVal) }
    var liveButtonTapCount by remember { mutableStateOf(0) }
    var liveButtonLastTapMs by remember { mutableStateOf(0L) }
    var showAdultSourceCard by remember { mutableStateOf(false) }
    var showAdultSources by remember { mutableStateOf(prefs.showAdultSources) }

    var splashPosterUri by remember { mutableStateOf(prefs.customSplashPosterUri) }
    var splashPureMode by remember { mutableStateOf(prefs.splashPureMode) }
    var appBgMode by remember { mutableIntStateOf(prefs.appBackgroundMode) }
    var appBgUri by remember { mutableStateOf(prefs.customAppBackgroundUri) }
    var appBgDim by remember { mutableIntStateOf(prefs.appBackgroundDim) }

    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    var showHelpBottomSheet by remember { mutableStateOf(false) }
    var showCacheManager by remember { mutableStateOf(false) }

    /* ── 隐私模式窗口状态（第七轮第 6.4 条） ── */
    var showPinSetup by remember { mutableStateOf(false) }
    var showManageVerify by remember { mutableStateOf(false) }
    var showManageWindow by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var showDisableVerify by remember { mutableStateOf(false) }
    var changePinOld by remember { mutableStateOf("") }
    // 2026-09-21 新增：隐私开关的“乐观选中”态。原先开关把 checked 硬编码成 false，
    // 用户点下去开关纹丝不动、只弹出密码框，看起来像没点上；现在点击后立即置为选中，
    // 若密码设置被取消/失败则回弹为关闭。
    var privacySwitchPending by remember { mutableStateOf(false) }

    // 卡片参数自定义：折叠栏展开态（跨重建保留）+ 实时写回 prefs / 共享状态
    var showCardPanel by rememberSaveable { mutableStateOf(false) }
    val tweaksState = cardTweaksState
    fun updateCardTweaks(transform: (com.example.ui.components.CardTweaks) -> com.example.ui.components.CardTweaks) {
        val next = transform(tweaksState?.value ?: prefs.readCardTweaks())
        prefs.writeCardTweaks(next)
        // 回读经过 setter coerce 的真值 —— 共享状态永不持有越界 raw 值（重启后不跳变）
        tweaksState?.value = prefs.readCardTweaks()
    }

    val posterLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                // 换新文件名：固定路径下 URI 不变会让图片加载器命中旧缓存（同背景图 bug）
                val file = java.io.File(context.filesDir, "custom_poster_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // 删除旧海报文件
                runCatching {
                    prefs.customSplashPosterUri?.let { old ->
                        val oldFile = java.io.File(Uri.parse(old).path ?: "")
                        if (oldFile.exists() && oldFile.name.startsWith("custom_poster")) oldFile.delete()
                    }
                }
                val localUriStr = Uri.fromFile(file).toString()
                splashPosterUri = localUriStr
                prefs.customSplashPosterUri = localUriStr
                AppToast.makeText(context, "海报已设置", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppToast.makeText(context, "图片设置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 软件背景：选择图片后复制到私有目录（横竖屏统一 Crop 填充，不拉伸变形）。
    // 必须每次换新文件名：固定路径下 URI 字符串不变 → AppBackgroundController 的
    // StateFlow 因配置相等不会重发（页面零重组），Coil 也会按相同 URI 命中旧缓存，
    // 表现为“更换照片后背景未变”。
    val bgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val file = java.io.File(context.filesDir, "custom_app_bg_${System.currentTimeMillis()}.jpg")
                inputStream?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                // 删除旧背景文件，避免私有目录残留堆积
                runCatching {
                    prefs.customAppBackgroundUri?.let { old ->
                        val oldFile = java.io.File(Uri.parse(old).path ?: "")
                        if (oldFile.exists() && oldFile.name.startsWith("custom_app_bg")) oldFile.delete()
                    }
                }
                val localUriStr = Uri.fromFile(file).toString()
                appBgUri = localUriStr
                prefs.customAppBackgroundUri = localUriStr
                prefs.appBackgroundMode = 1
                appBgMode = 1
                AppBackgroundController.update(1, localUriStr, appBgDim)
                AppToast.makeText(context, "背景已设置（横竖屏自动裁剪填充）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppToast.makeText(context, "背景设置失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (LocalAppBackgroundActive.current) Color.Transparent
                else MaterialTheme.colorScheme.background
            )
    ) {
    // 滚动联动折叠头部：state 提前声明供 topBar 与列表共用
    val settingsListState = rememberLazyListState()

    // 排行榜「陈列方式」按钮跳入：动画滚动到神回设置分区。
    // 神回 item 下标 = 前 14 个固定 item + 条件显示的成人源卡片（1/0）。
    // ⚠️ 在神回分区之前增删设置项时，同步更新这里的固定计数。
    LaunchedEffect(focusGodSettings) {
        if (!focusGodSettings) return@LaunchedEffect
        val godIndex = 14 + if (showAdultSourceCard) 1 else 0
        settingsListState.animateScrollToItem(godIndex)
    }
    settingsListState.scrollTiltSource()
    val settingsHeaderCollapsedSource = rememberHeaderCollapsedSource(settingsListState)
    val backAction = onBack
    val settingsLeading: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        if (backAction != null) {
            {
                AppIconButton(onClick = backAction) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else null
    Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabScreenHeader(
                    collapsedSource = settingsHeaderCollapsedSource,
                    modifier = Modifier.statusBarsPadding(),
                    title = "设置",
                    subtitle = "SETTINGS & PREFERENCES",
                    titleColor = glassTitleColor(),
                    leading = settingsLeading
                )
            }
        ) { padding ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
LazyColumn(
                state = settingsListState,
                modifier = Modifier
                .widthIn(max = AdaptiveSpec.pageContentMaxWidth)
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + extraBottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {


                // Section 1: Splash Poster
                item {
                    SettingsSectionHeader("开屏海报设置")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自定义开屏海报", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                Row {
                                    if (!splashPosterUri.isNullOrEmpty()) {
                                        TextButton(
                                            onClick = {
                                                splashPosterUri = null
                                                prefs.customSplashPosterUri = null
                                                AppToast.makeText(context, "已还原默认海报", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Text("清除")
                                        }
                                    }
                                    AppActionButton(
                                        text = "选择相册图片",
                                        onClick = { posterLauncher.launch("image/*") },
                                        variant = AppButtonVariant.Primary,
                                        buttonSize = AppButtonSize.Small
                                    )
                                }
                            }

                            if (!splashPosterUri.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = splashPosterUri,
                                        contentDescription = "海报预览",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("纯净模式", fontWeight = FontWeight.SemiBold)
                                AppSwitch(
                                    checked = splashPureMode,
                                    onCheckedChange = {
                                        splashPureMode = it
                                        prefs.splashPureMode = it
                                    }
                                )
                            }
                        }
                    }
                }

                // Section: 软件背景
                item {
                    SettingsSectionHeader("软件背景设置")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("软件背景", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            SegmentedPillSelector(
                                options = listOf(0 to "默认", 1 to "自定义"),
                                selected = appBgMode,
                                onSelect = { mode ->
                                    appBgMode = mode
                                    prefs.appBackgroundMode = mode
                                    AppBackgroundController.update(
                                        mode,
                                        if (mode == 1) appBgUri else null,
                                        if (mode == 1) appBgDim else 0
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (appBgMode == 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                                if (!appBgUri.isNullOrEmpty()) {
                                    // 预览模拟卡：文字颜色实时按卡片底色取对比色
                                    val previewCardColor = Color.White.copy(alpha = 0.92f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        // 实时预览：背景图 + 遮罩 + 模拟内容卡片，直观感受可读性
                                        AsyncImage(
                                            model = appBgUri,
                                            contentDescription = "背景预览",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = appBgDim / 100f))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .fillMaxWidth()
                                                .padding(20.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(previewCardColor)
                                                .padding(12.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    "预览",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = previewCardColor.onColor()
                                                )
                                                Text(
                                                    "背景遮罩 ${appBgDim}%",
                                                    fontSize = 11.sp,
                                                    color = previewCardColor.onColor().copy(alpha = 0.65f)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("背景遮罩强度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text("$appBgDim%", fontSize = 12.sp, color = MintPrimary, fontWeight = FontWeight.Bold)
                                    }
                                    // 原版 FluidSlider（基线样式零改动）；纵向让位由滑条内部方向仲裁承担
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                    FluidSlider(
                                        position = appBgDim / 50f,
                                        onPositionChange = {
                                            appBgDim = (it * 50).toInt().coerceIn(0, 50)
                                            prefs.appBackgroundDim = appBgDim
                                            if (appBgMode == 1 && !appBgUri.isNullOrEmpty()) {
                                                AppBackgroundController.update(1, appBgUri, appBgDim)
                                            }
                                        },
                                        barHeightDp = 32,
                                        bubbleText = "$appBgDim%",
                                        startText = "0%",
                                        // C4：原先左端 "0"、右端 "50"、气泡 "$appBgDim%"，
                                        // 单位与量纲混用；统一成 0%~50%
                                        endText = "50%",
                                        colorBar = MintPrimary,
                                        colorBubble = Color.White,
                                        colorBubbleText = MintPrimary,
                                        colorBarText = glassTitleColor()
                                    )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    AppActionButton(
                                        text = if (appBgUri.isNullOrEmpty()) "选择背景图片" else "更换图片",
                                        onClick = { bgLauncher.launch("image/*") },
                                        variant = AppButtonVariant.Primary,
                                        buttonSize = AppButtonSize.Small,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!appBgUri.isNullOrEmpty()) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        AppActionButton(
                                            text = "恢复默认",
                                            onClick = {
                                                appBgUri = null
                                                prefs.customAppBackgroundUri = null
                                                prefs.appBackgroundMode = 0
                                                appBgMode = 0
                                                AppBackgroundController.update(0, null, appBgDim)
                                                AppToast.makeText(context, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                                            },
                                            variant = AppButtonVariant.Secondary,
                                            buttonSize = AppButtonSize.Small,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section: 外观与主题自定义
                item {
                    SettingsSectionHeader("外观与主题")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Palette, contentDescription = null, tint = MintPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("主题配色方案", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            Text("主色调 (Primary)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                // C3：此处曾声明 val colorNames（"蓝/紫/绿/粉/橙"）但从未使用，已删除
                                com.example.ui.theme.BasePrimaryColors.forEachIndexed { index, color ->
                                    val selected = colorPrimaryIndex == index
                                    ColorMorphSwatch(
                                        color = color,
                                        selected = selected,
                                        onClick = {
                                            colorPrimaryIndex = index
                                            onColorThemeChange(index, colorSecondaryIndex)
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text("强调色 / 副色 (Secondary)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                val colorNames = listOf("蓝", "紫", "绿", "粉", "橙")
                                com.example.ui.theme.BaseSecondaryColors.forEachIndexed { index, color ->
                                    val selected = colorSecondaryIndex == index
                                    ColorMorphSwatch(
                                        color = color,
                                        selected = selected,
                                        onClick = {
                                            colorSecondaryIndex = index
                                            onColorThemeChange(colorPrimaryIndex, index)
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text("实时配色预览卡片", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
            Text("Ciallo 阅览室", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.secondary
                                        ) {
                                            Text("副色标签", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    AppLiquidButton(
                                        text = "主色按钮实时联动效果",
                                        onClick = {
                                            val now = System.currentTimeMillis()
                                            liveButtonTapCount = if (now - liveButtonLastTapMs < 3000) {
                                                liveButtonTapCount + 1
                                            } else {
                                                1
                                            }
                                            liveButtonLastTapMs = now
                                            if (liveButtonTapCount >= 6) {
                                                showAdultSourceCard = true
                                                liveButtonTapCount = 0
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                if (showAdultSourceCard) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("高级内容", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("带你登大郎~~~", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                    AppSwitch(
                                        checked = showAdultSources,
                                        onCheckedChange = {
                                            showAdultSources = it
                                            prefs.showAdultSources = it
                                            onAdultSourcesChange(it)
                                            AppToast.makeText(context, if (it) "正在更新成人源…" else "成人源已隐藏", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 2: Orientation Lock
                item {
                    SettingsSectionHeader("屏幕方向")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ScreenRotation, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("方向锁定", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            SegmentedPillSelector(
                                options = listOf(0 to "跟随系统", 1 to "锁定竖屏", 2 to "锁定横屏"),
                                selected = orientationLockVal,
                                onSelect = onOrientationLockChange
                            )
                        }
                    }
                }

                // Section 2.5: 画面与性能（渲染画质四档）
                item {
                    SettingsSectionHeader("画面与性能")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MintPrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("渲染画质", fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            SegmentedPillSelector(
                                options = listOf(
                                    0 to "流畅",
                                    1 to "均衡",
                                    2 to "高",
                                    3 to "极致"
                                ),
                                selected = renderQualityVal.coerceIn(0, 3),
                                onSelect = onRenderQualityChange
                            )
                        }
                    }
                }

                // Section 2.55: 触控反馈（触觉）总开关
                // 此前 LocalHapticsEnabled 在 MainActivity 里恒为 true，设置页根本没有这个入口：
                // 马达坏了 / 开会不想震动 / 省电的用户只能去系统层关触感，或者改代码。
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Vibration, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("触控反馈", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "点按与滑动时的震动确认",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AppSwitch(
                                    checked = hapticsEnabledVal,
                                    onCheckedChange = { onHapticsChange(it) }
                                )
                            }
                        }
                    }
                }

                // Section 2.6: 卡片视觉微调（自定义卡片参数折叠栏）
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Palette, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("自定义卡片参数", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                AppSwitch(
                                    checked = showCardPanel,
                                    onCheckedChange = { showCardPanel = it }
                                )
                            }

                            AnimatedVisibility(
                                visible = showCardPanel,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val tw = tweaksState?.value ?: prefs.readCardTweaks()

                                    // 防误触提示 + 一键恢复默认
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(onClick = {
                                            updateCardTweaks { com.example.ui.components.CardTweaks() }
                                            AppToast.makeText(context, "已恢复默认卡片参数", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("重置默认", color = MintPrimary, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    CardTweakSlider(
                                        label = "毛玻璃模糊强度",
                                        valueText = "${tw.blurRadiusDp.toInt()}dp",
                                        position = (tw.blurRadiusDp - 8f) / 32f,
                                        startText = "8",
                                        endText = "40",
                                    ) { v -> updateCardTweaks { it.copy(blurRadiusDp = 8f + v * 32f) } }
                                    CardTweakSlider(
                                        label = "卡片圆角",
                                        valueText = "${tw.cornerRadiusDp.toInt()}dp",
                                        position = (tw.cornerRadiusDp - 2f) / 46f,
                                        startText = "2",
                                        endText = "48",
                                    ) { v -> updateCardTweaks { it.copy(cornerRadiusDp = 2f + v * 46f) } }
                                    CardTweakSlider(
                                        label = "3D 倾斜最大角度",
                                        valueText = "%.1f°".format(tw.tiltMaxDeg),
                                        position = kotlin.math.sqrt(tw.tiltMaxDeg / 15f),
                                        startText = "0°",
                                        endText = "15°",
                                    ) { v -> updateCardTweaks { it.copy(tiltMaxDeg = v * v * 15f) } }
                                    CardTweakSlider(
                                        label = "立体透视强度（越大越立体）",
                                        valueText = "${((12f - tw.cameraDistMult) / 9f * 100).toInt()}%",
                                        position = (12f - tw.cameraDistMult) / 9f,
                                        startText = "平面",
                                        endText = "立体",
                                    ) { v -> updateCardTweaks { it.copy(cameraDistMult = 12f - v * 9f) } }
                                    // 「涟漪颜色透明度」滑条已随触点涟漪层整体移除
                                    // （按压反馈只保留静态压痕，不再有任何扩大圆环）
                                    CardTweakSlider(
                                        label = "主题色叠加浓度",
                                        valueText = "${(tw.tintMix * 100).toInt()}%",
                                        position = tw.tintMix / 0.3f,
                                        startText = "0%",
                                        endText = "30%",
                                    ) { v -> updateCardTweaks { it.copy(tintMix = v * 0.3f) } }
                                    CardTweakSlider(
                                        label = "压力形变强度",
                                        valueText = "${(tw.pressStrength * 100).toInt()}%",
                                        position = tw.pressStrength / 2f,
                                        startText = "0%",
                                        endText = "200%",
                                    ) { v -> updateCardTweaks { it.copy(pressStrength = v * 2f) } }
                                    CardTweakSlider(
                                        label = "压力形变半径",
                                        valueText = "×%.1f".format(tw.pressRadius),
                                        position = (tw.pressRadius - 0.5f) / 2.0f,
                                        startText = "0.5×",
                                        endText = "2.5×",
                                    ) { v -> updateCardTweaks { it.copy(pressRadius = 0.5f + v * 2.0f) } }
                                    CardTweakSlider(
                                        label = "卡片不透明度",
                                        valueText = "${(tw.cardAlpha * 100).toInt()}%",
                                        position = (tw.cardAlpha - 0.4f) / 0.6f,
                                        startText = "40%",
                                        endText = "100%",
                                    ) { v -> updateCardTweaks { it.copy(cardAlpha = 0.4f + v * 0.6f) } }

                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }

                // Section 3: Page Turn Effect
                item {
                    SettingsSectionHeader("翻页效果")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PageTurnType.entries.forEach { turnType ->
                                PageTurnSelectorRow(
                                    title = turnType.title,
                                    description = turnType.description,
                                    type = turnType,
                                    selected = pageTurnMode == turnType.id,
                                    onClick = {
                                        pageTurnMode = turnType.id
                                        prefs.pageTurnMode = turnType.id
                                    }
                                )
                            }
                        }
                    }
                }

                // 神回（GodMoment）设置分组
                // ⚠️ key 固定为 "god_settings"；focusGodSettings 滚动定位依赖它排查。
                if (godSettings != null) {
                    item(key = "god_settings") {
                        com.example.god.GodSettingsSection(
                            store = godSettings,
                            onOpenRanking = onOpenGodRanking,
                        )
                    }
                }

                // Section 5: Health & Eye Protection
                item {
                    SettingsSectionHeader("护眼与提醒")
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("定时休息", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            SegmentedPillSelector(
                                options = listOf(0 to "关", 15 to "15分", 30 to "30分", 45 to "45分", 60 to "60分"),
                                selected = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) restReminderMinutes else -1,
                                onSelect = {
                                    restReminderMinutes = it
                                    prefs.restReminderMinutes = it
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) {
                                        "自定义提醒时间"
                                    } else {
                                        "当前自定义：$restReminderMinutes 分钟"
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                AppActionButton(
                                    text = "自定义",
                                    onClick = { showCustomMinutes = true },
                                    variant = AppButtonVariant.Secondary,
                                    buttonSize = AppButtonSize.Small
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Nightlight, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("夜间模式", fontWeight = FontWeight.SemiBold)
                                }
                                AppSwitch(
                                    checked = autoNightMode,
                                    onCheckedChange = {
                                        autoNightMode = it
                                        onAutoNightModeChange(it)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.RemoveRedEye, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("护眼滤镜", fontWeight = FontWeight.SemiBold)
                                }
                                AppSwitch(
                                    checked = blueLightFilter,
                                    onCheckedChange = {
                                        blueLightFilter = it
                                        onBlueLightFilterChange(it)
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            // 在线漫画预加载策略：开启后移动网络不提前下载后面的页（省流量）
                            var preloadWifiOnly by remember { mutableStateOf(prefs.preloadWifiOnly) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MintPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("仅 Wi-Fi 预加载漫画", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "开启后移动网络不会提前下载后面的页，更省流量",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AppSwitch(
                                    checked = preloadWifiOnly,
                                    onCheckedChange = {
                                        preloadWifiOnly = it
                                        prefs.preloadWifiOnly = it
                                    }
                                )
                            }

                            if (blueLightFilter) {
                                Spacer(modifier = Modifier.height(10.dp))
                                // 静止态也可读：当前强度常驻显示（与遮罩强度行同款式）
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("当前强度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${(blueLightAlpha * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        color = MintPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(modifier = Modifier.fillMaxWidth()) {
                                FluidSlider(
                                    position = blueLightAlpha,
                                    onPositionChange = {
                                        blueLightAlpha = it
                                        onBlueLightAlphaChange(it)
                                    },
                                    bubbleText = "${(blueLightAlpha * 100).toInt()}%",
                                    startText = "0",
                                    endText = "100",
                                    colorBar = MintPrimary,
                                    colorBubble = Color.White,
                                    colorBubbleText = MintPrimary,
                                    colorBarText = glassTitleColor(),
                                    barHeightDp = 28
                                )
                                }
                            }
                        }
                    }
                }

                // Section 5.5: 隐私与安全（第七轮第 6.4 条）
                item {
                    SettingsSectionHeader("隐私与安全")
                }
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MintPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("隐私模式", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    if (privacyModeEnabled) "已开启 · 分类密码保护与无痕阅读"
                                    else "设置 6 位密码，为分类加锁",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 第十一轮第 4 条：隐私模式开启/关闭的行内过渡动画——
                            // 尾部控件（开关 ↔ "管理"入口）与副标题随状态淡入淡出切换，不再硬跳变
                            AnimatedContent(
                                targetState = privacyModeEnabled,
                                transitionSpec = {
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                                        .togetherWith(fadeOut(tween(160)))
                                },
                                label = "privacyRowSwitch"
                            ) { enabled ->
                                if (!enabled) {
                                // 首次开启：先走 6 位密码设置流程（输入 + 二次确认）
                                com.example.ui.components.AppSwitch(
                                    // 2026-09-21 修复：原为硬编码 false（点了开关不动、只弹窗，
                                    // 用户以为没点上）。改为反映点击意图，取消时再回弹。
                                    checked = privacySwitchPending,
                                    onCheckedChange = { wantOn ->
                                        if (wantOn) {
                                            privacySwitchPending = true
                                            showPinSetup = true
                                        } else {
                                            privacySwitchPending = false
                                        }
                                    }
                                )
                            } else {
                                // 已开启：验证密码后进入隐私管理悬浮窗口
                                Text(
                                    "管理",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MintPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickableWithFeedback { showManageVerify = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            }
                        }
                    }
                }

                // Section 6: Cache Management
                item {
                    SettingsSectionHeader("存储管理")
                }
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableWithFeedback { showCacheManager = true },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("缓存管理", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text("查看应用占用，清理缓存与离线数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }

                item(key="user_backup") {
                    SettingsSectionHeader("数据备份")
                    GlassCard(modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(com.example.ui.design.DesignTokens.SpaceLg),verticalArrangement=Arrangement.spacedBy(com.example.ui.design.DesignTokens.SpaceMd)) {
                            Text("备份书籍、原文件、标注、收藏、阅读记录与设置。登录凭据不包含在备份中。")
                            Row(horizontalArrangement=Arrangement.spacedBy(com.example.ui.design.DesignTokens.SpaceMd)) {
                                AppActionButton(text=if(backupBusy) "正在处理…" else "导出备份",enabled=!backupBusy,
                                    onClick={ exportBackupLauncher.launch("Ciallo-backup.zip") },buttonSize=AppButtonSize.Small)
                                AppActionButton(text="恢复备份",enabled=!backupBusy,
                                    onClick={ importBackupLauncher.launch(arrayOf("application/zip","application/octet-stream")) },
                                    variant=AppButtonVariant.Secondary,buttonSize=AppButtonSize.Small)
                            }
                        }
                    }
                }

                // Section 7: Library Help & Manual
                item {
                    SettingsSectionHeader("帮助手册")
                }

                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableWithFeedback { showHelpBottomSheet = true }
                            .testTag("library_help_entry_card"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.HelpCenter,
                                    contentDescription = "书库帮助中心",
                                    tint = MintPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("书库使用手册", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("书源说明、下载管理与常见问题", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = "打开帮助",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // Section 8: About
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Ciallo 阅读",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val versionName = remember {
                                try {
                                    context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: "1.0"
                                } catch (_: Exception) { "1.0" }
                            }
                            Text(
                                "版本 $versionName",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "轻量级本地 / 在线小说阅读器",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(14.dp))
                            val githubUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                            ) {
                                Row(
                                    modifier = Modifier.clickable {
                                        runCatching { githubUriHandler.openUri("https://github.com/a1553846342-dotcom/EASYREADER") }
                                            .onFailure { AppToast.makeText(context, "暂时打不开 GitHub，稍后再来喵～", Toast.LENGTH_SHORT).show() }
                                    }.padding(horizontal = 16.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text("狗修金，给颗 Star 好不好喵～", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("点这里，去 GitHub 摸摸小项目 ฅ^•ﻌ•^ฅ", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            }
        }

    if (showCacheManager) {
        CacheManagementScreen(onBack = { showCacheManager = false })
        return
    }

    if (showHelpBottomSheet) {
            LibraryHelpBottomSheet(
                onDismissRequest = { showHelpBottomSheet = false },
                onOpenSourceManager = { onOpenSourceManager?.invoke() }
            )
        }
    }

    if (showCustomMinutes) {
        CustomMinutesDialog(
            current = if (restReminderMinutes in listOf(0, 15, 30, 45, 60)) 15 else restReminderMinutes,
            onConfirm = {
                restReminderMinutes = it
                prefs.restReminderMinutes = it
                showCustomMinutes = false
            },
            onDismiss = { showCustomMinutes = false }
        )
    }

    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("新建分类", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newCategoryText,
                    onValueChange = { newCategoryText = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                DialogLiquidGlass(fillMaxSize = false) {
                    AppActionButton(
                        text = "新建",
                        onClick = {
                            if (newCategoryText.isNotBlank()) {
                                onAddCategory(newCategoryText.trim())
                                newCategoryText = ""
                                showAddCategoryDialog = false
                                AppToast.makeText(context, "新建成功", Toast.LENGTH_SHORT).show()
                            }
                        },
                        variant = AppButtonVariant.Primary,
                        buttonSize = AppButtonSize.Small
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) {
                    Text("取消")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    /* ── 隐私模式窗口组（第七轮第 6.4 条）：设置 PIN / 验证进入 / 管理悬浮窗 ══ */
    // 首次开启：6 位 PIN 设置流程（输入 + 二次确认），完成后直接跳转隐私管理窗口
    if (showPinSetup) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.SETUP,
            onPinSet = { pin ->
                if (onEnablePrivacyMode(pin)) {
                    showPinSetup = false
                    privacySwitchPending = false
                    showManageWindow = true
                } else {
                    // PIN 非法 / 持久化失败：开关回弹，不要停在“已开启”的假象上
                    privacySwitchPending = false
                }
            },
            onPinVerified = { false },
            onDismiss = {
                // 取消设置：开关回弹到关闭
                showPinSetup = false
                privacySwitchPending = false
            },
        )
    }

    // 已开启后进入管理窗口前的 PIN 验证
    if (showManageVerify) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.VERIFY,
            onPinSet = { },
            onPinVerified = { pin ->
                // 第九轮修复①：验证通过必须真正打开管理窗（此前 onDismiss 只关了
                // 验证层，showManageWindow 从未置位——"输入密码后没有管理"根因）
                val ok = onVerifyPrivacyPin(pin)
                if (ok) {
                    showManageVerify = false
                    showManageWindow = true
                }
                ok
            },
            onDismiss = { showManageVerify = false },
        )
    }

    // 修改密码：旧密码验证（记录）→ 新密码
    if (showChangePin) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.CHANGE_OLD,
            onPinSet = { newPin ->
                if (onChangePrivacyPin(changePinOld, newPin)) {
                    showChangePin = false
                    AppToast.makeText(context, "密码已更新", Toast.LENGTH_SHORT).show()
                }
            },
            onPinVerified = { pin ->
                changePinOld = pin
                onVerifyPrivacyPin(pin)
            },
            onDismiss = { showChangePin = false },
        )
    }

    // 隐私管理悬浮毛玻璃窗口（6.4.3：分类密码保护总开关管理）
    if (showManageWindow) {
        com.example.ui.privacy.PrivacyManageOverlay(
            categories = categories,
            privacyModeEnabled = privacyModeEnabled,
            onToggleProtected = { cat, protected -> onToggleCategoryProtected(cat, protected) },
            onToggleIncognito = { enabled -> onSetIncognitoBrowsing(enabled) },
            incognitoBrowsingEnabled = incognitoBrowsingEnabled,
            favoritesProtected = favoritesProtected,
            onToggleFavoritesProtected = { onSetFavoritesProtected(it) },
            onChangePin = { showChangePin = true },
            onDisablePrivacy = {
                // 第九轮修复③：不再先收起管理窗——验证层覆盖其上，
                // 验证成功后双双关闭；取消则回到管理窗（"看起来关了其实没关"的困惑源）
                showDisableVerify = true
            },
            onDismiss = { showManageWindow = false },
        )
    }

    // 关闭隐私模式的 PIN 验证
    if (showDisableVerify) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.VERIFY,
            onPinSet = { },
            onPinVerified = { pin ->
                val ok = onDisablePrivacyMode(pin)
                if (ok) {
                    showDisableVerify = false
                    showManageWindow = false
                    AppToast.makeText(context, "隐私模式已关闭", Toast.LENGTH_SHORT).show()
                }
                ok
            },
            onDismiss = { showDisableVerify = false },
        )
    }
}

/**
 * 设置页分区标题（统一 14sp/Bold/adaptiveTitleColor）。
 * 抽取自此前散落 8 处的相同 Text 模式；其中两处原为 16sp，已按多数派统一为 14sp。
 */
@Composable
private fun SettingsSectionHeader(text: String) {
    // 2026-09-21：改为 iOS 分组标题规格 —— 13sp 半粗、次级色、轻微正字距。
    // 原来是 14sp Bold + 主色，字号比行标题还大、颜色还重，和下方内容抢视觉重心，
    // 一屏十几个分组时整页“全是标题”，层级糊成一片。
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
        color = adaptiveTitleColor().copy(alpha = 0.62f)
    )
}

/**
 * 卡片微调滑块行（基线原版 FluidSlider 样式，零改动）：
 *  1. 标签行与滑条容器零间距、共用同一左右内边距，视觉强关联；
 *  2. 防误触由滑条内部的方向仲裁独自承担（纵向起手静默让位给页面滚动）。
 */
@Composable
private fun CardTweakSlider(
    label: String,
    valueText: String,
    position: Float,
    startText: String,
    endText: String,
    onChange: (Float) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    /*
     * FluidSlider 容器 = 2.5×barH：底部 1×barH 轨道 + 顶部 1.5×barH 气泡预留空区。
     * 标签行与气泡同处预留空区：静止时标签可见；拖动开始整行淡出、
     * 让数值气泡独占该区域（不遮任何文字），松手后淡回 —— 两态互斥永不叠印。
     */
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val labelAlpha by animateFloatAsState(
            targetValue = if (dragging) 0f else 1f,
            animationSpec = tween(140),
            label = "tweakLabelAlpha"
        )
        // 标签行先组合 → 绘制在滑条下层；拖动时 alpha→0 交还区域给数值气泡
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 4.dp)
                .offset(y = (-30).dp)   // 抬到轨道上沿之上
                .graphicsLayer { alpha = labelAlpha },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                valueText,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                color = MintPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        FluidSlider(
            position = position.coerceIn(0f, 1f),
            onPositionChange = onChange,
            bubbleText = valueText,
            startText = startText,
            endText = endText,
            colorBar = MintPrimary,
            colorBubble = Color.White,
            colorBubbleText = MintPrimary,
            colorBarText = glassTitleColor(),
            barHeightDp = 28,
            onDraggingChange = { dragging = it }
        )
    }
}
