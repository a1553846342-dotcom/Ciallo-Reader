@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.comic

import android.graphics.Bitmap
import com.example.mangatranslate.LlmBubbleTranslator
import com.example.mangatranslate.TranslationCache
import com.example.mangatranslate.TranslateModelManager
import com.example.mangatranslate.OnlineFallbackTranslator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch

/* ══════════════ 设置面板（第二层：分组展示） ══════════════ */

private val AccentColor = Color(0xFFF0D9C0)

// Tab 命名对齐规范第 1 节词汇：翻页（模式/方向/动画）、显示（缩放/间距）、
// 主题（背景/场景/沉浸式）；图像 Tab 内含"画质增强"与"滤镜"两个规范名段落，
// 自动/手势为 28 条修复演进出的超集能力（规范未设同名 Tab）。
// 第七轮第 3 条：Tab 增加简洁线性图标（书页/眼睛/图片/调色板/时钟/手掌），
// 作为完整导航区域设计——图标+文字+统一高度+明确但克制的选中反馈。
private val SettingsTabs = listOf(
    PanelTabData("翻页", Icons.AutoMirrored.Filled.MenuBook),
    PanelTabData("显示", Icons.Filled.Visibility),
    PanelTabData("图像", Icons.Filled.Image),
    PanelTabData("翻译", Icons.Filled.Translate),
    PanelTabData("主题", Icons.Filled.Palette),
    PanelTabData("自动", Icons.Filled.Schedule),
    PanelTabData("手势", Icons.Filled.PanTool),
)

@Composable
internal fun ComicSettingsSheet(
    config: ComicReaderConfig,
    store: ComicSettingsStore,
    perBookConfig: Boolean,
    onPerBookConfigChange: (Boolean) -> Unit,
    onConfigChange: (ComicReaderConfig) -> Unit,
    onOpenPreset: () -> Unit,
    onOpenCrop: () -> Unit,
    pages: List<ComicPageRef>,
    loader: ComicPageLoader,
    currentRawPage: Int,
    bookState: ComicBookState,
    onBookStateChange: (ComicBookState) -> Unit,
    onDismiss: () -> Unit,
    glassBackdrop: LayerBackdrop? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    val update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit = { onConfigChange(it(config)) }

    // 第 11 条：横屏响应式——高度占满更多（横屏纵向空间小）、放宽最大宽度，
    // 六个 Tab 行 + 底部预设管理固定可见，内容区独立滚动不被裁切
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sheetHeight = if (landscape) 0.94f else 0.72f
        val sheetMaxWidth = if (landscape) 840.dp else 560.dp
        val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

        // 面板三段结构：Tab 固定 + 内容滚动 + 配置区固定（不随内容滚走）；
        // 宽度与 ComicSheetContainer 一致钳到 sheetMaxWidth（宽屏居中，父容器 BottomCenter）
        Column(
            Modifier
                .widthIn(max = sheetMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight(sheetHeight)
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
            "阅读设置",
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        // 分组 Tab（固定）——第七轮第 3 条：图标 Tab 导航（完整导航区域，
        // 选中 = 半透明品牌填充 + 细边框，不再只靠文字变色）
        PanelTabRow(
            tabs = SettingsTabs,
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))
        // 分组内容（滚动）。必须用 Column：各 Tab 的 composable 直接 emits 多个根级
        // item（PanelSectionCard/SegmentRow/SwitchRow...），Box 会把它们全部堆叠在同一原点
        // ——这正是用户实机"全部选项都混在一行"的根因（六 Tab 重构时引入）
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            when (tab) {
                0 -> ModeTab(config, update)
                1 -> PageTab(config, update, store)
                2 -> ImageTab(config, update, onOpenCrop, pages, loader, currentRawPage, bookState, onBookStateChange)
                3 -> TranslationTab(config, update)
                4 -> EffectTab(config, update)
                5 -> AutoTab(config, update)
                6 -> GestureTab(config, update)
            }
            Spacer(Modifier.height(12.dp))
        }
        // 配置区（固定在底部；与滚动区分隔的细线增强层级）
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .height(0.5.dp)
                    .background(StrokeColor)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelChipBg)
                    .clickableNoRipple(onOpenPreset)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MintPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("预设管理", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "本漫画独立设置",
                subtitle = if (perBookConfig) "仅当前漫画生效" else "跟随全局设置",
                checked = perBookConfig,
                onChange = onPerBookConfigChange
            )
            Spacer(Modifier.height(6.dp))
        }
        }
    }
}

/* ── Tab 3：翻译（第十五轮：离线 OCR + 离线/兜底机翻 + 逐页缓存） ── */

@Composable
private fun TranslationTab(
    config: ComicReaderConfig,
    update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var modelsReady by remember { mutableStateOf(TranslateModelManager.isReady(context)) }
    var downloading by remember { mutableFloatStateOf(-1f) }   // -1=空闲，0..1=进度
    var downloadError by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf(TranslationCache.totalBytes(context)) }
    val onlineFallback = remember { OnlineFallbackTranslator(context.applicationContext) }

    fun startDownload() {
        if (downloading >= 0f) return
        downloading = 0f
        downloadError = null
        scope.launch {
            val err = TranslateModelManager.ensureDownloaded(context) { p -> downloading = p.coerceIn(0f, 1f) }
            downloading = -1f
            if (err == null) {
                modelsReady = true
            } else {
                downloadError = err
            }
        }
    }

    // 打开开关即自动补齐模型（已就绪则跳过）
    LaunchedEffect(config.translationEnabled) {
        if (config.translationEnabled && !TranslateModelManager.isReady(context)) startDownload()
    }

    val llmTranslator = remember { LlmBubbleTranslator(context) }
    var llmUrl by remember { mutableStateOf(llmTranslator.loadConfig().apiUrl) }
    var llmKey by remember { mutableStateOf(llmTranslator.loadConfig().apiKey) }
    var llmModel by remember { mutableStateOf(llmTranslator.loadConfig().modelName) }
    var llmGemini by remember { mutableStateOf(llmTranslator.loadConfig().geminiFormat) }

    // 第十八轮：引擎二级导航（null=引擎列表页；非 null=进入对应配置页）
    var engineSubPage by remember { mutableStateOf<String?>(null) }
    // 二级页时系统返回=回引擎列表（不关面板；后组合的 BackHandler 优先于 Chrome 关面板）
    androidx.activity.compose.BackHandler(enabled = engineSubPage != null) { engineSubPage = null }

    fun saveLlm(url: String, key: String, model: String, gemini: Boolean) {
        llmUrl = url; llmKey = key; llmModel = model; llmGemini = gemini
        runCatching { llmTranslator.saveConfig(LlmBubbleTranslator.LlmConfig(url, key, model, gemini)) }
            .onFailure { android.widget.Toast.makeText(context, it.message ?: "配置保存失败", android.widget.Toast.LENGTH_LONG).show() }
    }

    if (engineSubPage == "ai") {
        /* ── 二级页：自定义 AI 配置 ── */
        PanelSectionCard("自定义 AI 接口", Icons.Filled.Cloud) {
            PanelRow("返回引擎列表", Icons.AutoMirrored.Filled.ArrowBack) { engineSubPage = null }
            Spacer(Modifier.height(6.dp))
            PanelTextField("API 地址（如 https://api.deepseek.com）", llmUrl) {
                saveLlm(it, llmKey, llmModel, llmGemini)
            }
            PanelTextField("API Key", llmKey) {
                saveLlm(llmUrl, it, llmModel, llmGemini)
            }
            PanelTextField("模型名（如 deepseek-chat）", llmModel) {
                saveLlm(llmUrl, llmKey, it, llmGemini)
            }
            SwitchRow(
                title = "Gemini 接口格式",
                subtitle = if (llmGemini) "走 Google Gemini generateContent" else "走 OpenAI chat/completions",
                checked = llmGemini,
            ) { v -> saveLlm(llmUrl, llmKey, llmModel, v) }
        }
    } else if (engineSubPage == "online") {
        /* ── 二级页：在线翻译设置 ── */
        PanelSectionCard("在线翻译", Icons.Filled.Public) {
            PanelRow("返回引擎列表", Icons.AutoMirrored.Filled.ArrowBack) { engineSubPage = null }
            Spacer(Modifier.height(6.dp))
            val langIdx = when (config.translationLang) { "ja" -> 1; "en" -> 2; else -> 0 }
            SegmentRow(
                "页面文字",
                listOf(0 to "自动识别", 1 to "日文", 2 to "英文"),
                langIdx,
            ) { i ->
                val lang = when (i) { 1 -> "ja"; 2 -> "en"; else -> "auto" }
                update { it.copy(translationLang = lang) }
            }
        }
    } else {
        /* ── 一级页：引擎选择卡片 ── */
        PanelSectionCard("翻译引擎（点击选择）", Icons.Filled.Bolt) {
            val aiConfigured = llmUrl.isNotBlank() && llmModel.isNotBlank()
            EngineCard(
                title = "自定义 AI 接口",
                desc = "DeepSeek / GLM / Kimi 等 OpenAI 兼容接口",
                state = when {
                    config.translationEngine == "ai" && aiConfigured -> "当前使用 · 已配置"
                    aiConfigured -> "已配置 · 点击选用并配置"
                    else -> "未配置 · 点击选用并配置"
                },
                selected = config.translationEngine == "ai",
            ) {
                update { it.copy(translationEngine = "ai") }
                engineSubPage = "ai"
            }
            Spacer(Modifier.height(8.dp))
            EngineCard(
                title = "在线翻译",
                desc = "腾讯交互翻译，国内直连",
                state = if (config.translationEngine == "online") "当前使用" else "点击选用",
                selected = config.translationEngine == "online",
            ) {
                update { it.copy(translationEngine = "online") }
                engineSubPage = "online"
            }
        }

        PanelSectionCard("功能", Icons.Filled.Translate) {
            SwitchRow(
                title = "整页自动翻译",
                checked = config.translationEnabled,
            ) { v -> update { it.copy(translationEnabled = v) } }
            val langIdx = when (config.translationLang) { "ja" -> 1; "en" -> 2; else -> 0 }
            SegmentRow(
                "页面文字",
                listOf(0 to "自动识别", 1 to "日文", 2 to "英文"),
                langIdx,
            ) { i ->
                val lang = when (i) { 1 -> "ja"; 2 -> "en"; else -> "auto" }
                update { it.copy(translationLang = lang) }
            }
        }
    }

    PanelSectionCard("离线模型", Icons.Filled.CloudDownload) {
        if (modelsReady) {
            SwitchRow(
                title = "OCR 模型（约 31MB，已下载）",
                checked = true, onChange = { }
            )
        } else if (downloading >= 0f) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("正在下载模型 ${(downloading * 100).toInt()}%", color = TextPrimary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(downloading.coerceIn(0.01f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MintPrimary)
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "需要下载离线 OCR 模型（约 31MB，仅一次）",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelChipBg)
                        .clickableNoRipple { startDownload() }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (downloadError == null) "下载模型" else "重试下载",
                        color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                }
                downloadError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("下载失败：$it（可切换网络后重试）", color = Color(0xFFE58B8B), fontSize = 11.sp)
                }
            }
        }
    }

    PanelSectionCard("译文显示", Icons.Filled.FormatSize) {
        SliderRow(
            title = "译文字号",
            value = config.translationTextScale * 100f,
            range = 80f..140f,
            format = { "${it.toInt()}%" },
            onChange = { v -> update { it.copy(translationTextScale = (v / 100f).coerceIn(0.8f, 1.4f)) } },
        )
    }

    PanelSectionCard("译文缓存", Icons.Filled.CleaningServices) {
        SwitchRow(
            title = "已缓存译文（${cacheBytes / 1024 / 1024}MB / 64MB）",
            checked = cacheBytes > 0,
            onChange = { }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PanelChipBg)
                .clickableNoRipple {
                    TranslationCache.clear(context)
                    cacheBytes = 0
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFE58B8B), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("清空全部已翻译页面", color = TextPrimary, fontSize = 13.sp)
        }
    }
}

/* ── Tab 0：翻页（模式/方向/动画） ── */

@Composable
private fun ModeTab(config: ComicReaderConfig, update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit) {
    PanelSectionCard("阅读模式", Icons.Filled.AutoStories) {
        ModeGrid(config.mode) { m -> update { c -> c.copy(mode = m) } }
    }
    PanelSectionCard("阅读方向", Icons.Filled.SwapHoriz) {
        DirectionGrid(config.direction) { d -> update { c -> c.copy(direction = d) } }
    }
    PanelSectionCard("翻页动画", Icons.Filled.Animation) {
        SegmentRow(
            "动画",
            listOf(
                ComicPageAnim.NONE.ordinal to "无",
                ComicPageAnim.SLIDE.ordinal to "平移",
                ComicPageAnim.FADE.ordinal to "渐变",
                ComicPageAnim.CURL.ordinal to "仿真",
            ),
            config.pageAnim.ordinal
        ) { i -> update { it.copy(pageAnim = ComicPageAnim.entries[i]) } }
    }
    // 第 6/7 条：条漫模式磁吸到页边界（可关闭）；无缝滚动恒无磁吸且强制 0 间距
    if (config.mode == ComicMode.WEBTOON || config.mode == ComicMode.CONTINUOUS) {
        PanelSectionCard("垂直滚动", Icons.Filled.SwipeVertical) {
            if (config.mode == ComicMode.WEBTOON) {
                SwitchRow("磁吸到页边界", "松手自动对齐到最近页", config.webtoonSnap) { v ->
                    update { it.copy(webtoonSnap = v) }
                }
            } else {
                SwitchRow("无缝拼接", "页间距为 0，连续不断停", true) { }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeGrid(selected: ComicMode, onSelect: (ComicMode) -> Unit) {
    // FlowRow：3 个一行网格；大字体下放不下的项自动换行，文字永不被压缩截断
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 3
    ) {
        ComicMode.entries.forEach { m ->
            val active = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) PanelChipActiveBg else PanelChipBg)
                    .border(
                        0.5.dp,
                        if (active) MintPrimary.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .clickableNoRipple { onSelect(m) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    m.label,
                    color = if (active) MintPrimary else Color(0xAAFFFFFF),
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    // 极端 fontScale 下放不下时换行而非截断（weight 定宽 + 换行 = 任何缩放零截断）
                    maxLines = 2, softWrap = true,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionGrid(selected: ComicDirection, onSelect: (ComicDirection) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 3
    ) {
        ComicDirection.entries.forEach { d ->
            val active = d == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) PanelChipActiveBg else PanelChipBg)
                    .clickableNoRipple { onSelect(d) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    d.label,
                    color = if (active) MintPrimary else Color(0xAAFFFFFF),
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, softWrap = true,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/* ── Tab 1：显示（缩放/间距） ── */

/** 缩放档位展示序列（第 10 条五种数学定义 + 第 26 条自定义档） */
private val FitOptions = listOf(
    ComicFit.FIT_PAGE to "整页",
    ComicFit.FIT_HEIGHT to "高度",
    ComicFit.ORIGINAL to "原始",
    ComicFit.FILL to "铺满",
    ComicFit.STRETCH to "拉伸",
    ComicFit.CUSTOM to "自定义",
)

private fun fitLabel(fit: ComicFit): String = FitOptions.firstOrNull { it.first == fit }?.second
    ?: if (fit == ComicFit.FIT_WIDTH) "适应宽度" else fit.label

@Composable
private fun PageTab(
    config: ComicReaderConfig,
    update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit,
    store: ComicSettingsStore? = null,
) {
    PanelSectionCard("缩放方式", Icons.Filled.AspectRatio) {
        SegmentRow(
            "适配",
            FitOptions.mapIndexed { i, (_, label) -> i to label },
            FitOptions.indexOfFirst { it.first == config.fit }.coerceAtLeast(0),
        ) { i -> update { it.copy(fit = FitOptions[i].first) } }
    }

    if (config.fit == ComicFit.CUSTOM) {
        PanelSectionCard("自定义缩放", Icons.Filled.Tune) {
            SegmentRow(
                "基础适配",
                listOf(
                    ComicFit.FIT_PAGE to "整页", ComicFit.FIT_WIDTH to "适应宽度",
                    ComicFit.FIT_HEIGHT to "高度", ComicFit.ORIGINAL to "原始",
                ).mapIndexed { i, (_, label) -> i to label },
                listOf(ComicFit.FIT_PAGE, ComicFit.FIT_WIDTH, ComicFit.FIT_HEIGHT, ComicFit.ORIGINAL)
                    .indexOf(config.customFitBase).coerceAtLeast(0),
            ) { i ->
                update {
                    it.copy(customFitBase = listOf(ComicFit.FIT_PAGE, ComicFit.FIT_WIDTH, ComicFit.FIT_HEIGHT, ComicFit.ORIGINAL)[i])
                }
            }
            SliderRow(
                "缩放系数", config.customFitScale, 0.5f..2.5f,
                format = { "${(it * 100).toInt()}%" },
                onChange = { v -> update { it.copy(customFitScale = v) } }
            )
            if (store != null) {
                var presets by remember { mutableStateOf(store.loadCustomFitPresets()) }
                var nameDialog by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip("保存当前组合", Modifier.weight(1f)) { nameDialog = true }
                }
                presets.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${p.name}（${fitLabel(p.base)} × ${p.scalePct}%）",
                            color = TextPrimary, fontSize = 12.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PanelChipBg)
                                .clickableNoRipple {
                                    update {
                                        it.copy(
                                            fit = ComicFit.CUSTOM,
                                            customFitBase = p.base,
                                            customFitScale = p.scalePct / 100f,
                                        )
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = {
                            store.deleteCustomFitPreset(p.id)
                            presets = store.loadCustomFitPresets()
                        }) { Text("删除", color = TextSecondary, fontSize = 11.sp) }
                    }
                }
                if (nameDialog) {
                    PresetNameDialog(
                        title = "保存自定义缩放",
                        initial = "",
                        onConfirm = { name ->
                            store.saveCustomFitPreset(name, config.customFitBase, (config.customFitScale * 100).toInt())
                            presets = store.loadCustomFitPresets()
                            nameDialog = false
                        },
                        onDismiss = { nameDialog = false },
                    )
                }
            }
        }
    }

    PanelSectionCard("间距", Icons.Filled.Height) {
        SliderRow(
            "页面间距", config.pageSpacingDp, 0f..40f,
            format = { "${it.toInt()} dp" },
            onChange = { v -> update { it.copy(pageSpacingDp = v) } }
        )
        SliderRow(
            "双页间距", config.doubleGapDp, 0f..40f,
            format = { "${it.toInt()} dp" },
            onChange = { v -> update { it.copy(doubleGapDp = v) } }
        )
    }

    PanelSectionCard("双页排列", Icons.Filled.Bookmark) {
        SwitchRow("首页单独显示", "封面页不与正文配对", config.doubleFirstAlone) { v ->
            update { it.copy(doubleFirstAlone = v) }
        }
        SegmentRow(
            "对齐",
            listOf(
                ComicDoubleAlign.TOP.ordinal to "顶部",
                ComicDoubleAlign.CENTER.ordinal to "居中",
                ComicDoubleAlign.BOTTOM.ordinal to "底部",
            ),
            config.doubleAlign.ordinal
        ) { i -> update { it.copy(doubleAlign = ComicDoubleAlign.entries[i]) } }
        SliderRow(
            "位置修正 X", config.doubleShiftXDp, -40f..40f,
            format = { "${it.toInt()} dp" },
            onChange = { v -> update { it.copy(doubleShiftXDp = v) } }
        )
        SliderRow(
            "位置修正 Y", config.doubleShiftYDp, -40f..40f,
            format = { "${it.toInt()} dp" },
            onChange = { v -> update { it.copy(doubleShiftYDp = v) } }
        )
    }

    PanelSectionCard("缩放", Icons.Filled.ZoomIn) {
        SwitchRow("双击放大", "再次双击还原", config.doubleTapZoom) { v ->
            update { it.copy(doubleTapZoom = v) }
        }
        SwitchRow("长按放大", "按住临时放大，松手还原", config.longPressZoom) { v ->
            update { it.copy(longPressZoom = v) }
        }
        SwitchRow("放大状态翻页", "滑动到边缘继续翻页", config.zoomWhileTurn) { v ->
            update { it.copy(zoomWhileTurn = v) }
        }
    }
}

/* ── Tab 2：图像（裁边/拆分/旋转/增强/滤镜） ── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageTab(
    config: ComicReaderConfig,
    update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit,
    onOpenCrop: () -> Unit,
    pages: List<ComicPageRef>,
    loader: ComicPageLoader,
    currentRawPage: Int,
    bookState: ComicBookState,
    onBookStateChange: (ComicBookState) -> Unit,
) {
    PanelSectionCard("裁边", Icons.Filled.Crop) {
        SegmentRow(
            "自动裁边",
            listOf(
                ComicCropMode.OFF.ordinal to "关闭",
                ComicCropMode.WHITE.ordinal to "白边",
                ComicCropMode.BLACK.ordinal to "黑边",
                ComicCropMode.AUTO.ordinal to "自动",
            ),
            config.cropMode.ordinal
        ) { i -> update { it.copy(cropMode = ComicCropMode.entries[i]) } }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelChipBg)
                .clickableNoRipple(onOpenCrop)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (config.manualCrop != null) "手动裁边 · 已设置" else "手动裁边",
                color = TextPrimary, fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        if (config.manualCrop != null) {
            TextButton(onClick = { update { it.copy(manualCrop = null) } }) {
                Text("清除手动裁边", color = TextSecondary)
            }
        }
    }

    PanelSectionCard("大图拆分", Icons.Filled.VerticalSplit) {
        SwitchRow("宽页自动拆分", "跨页扫描图拆为左右两页", config.splitWide) { v ->
            update { it.copy(splitWide = v) }
        }
        SwitchRow("拆分左右反转", null, config.splitReverse) { v ->
            update { it.copy(splitReverse = v) }
        }
        SliderRow(
            "拆分位置", config.splitPosition, 0.3f..0.7f,
            format = { "%.0f%%".format(it * 100) },
            onChange = { v -> update { it.copy(splitPosition = v) } }
        )
    }

    PanelSectionCard("旋转", Icons.Filled.RotateRight) {
        SegmentRow(
            "整本旋转",
            listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°"),
            ((config.bookRotation % 360) + 360) % 360
        ) { deg -> update { it.copy(bookRotation = deg) } }
        val pageRef = pages.getOrNull(currentRawPage)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("旋转本页 +90°", Modifier.weight(1f)) {
                if (pageRef != null) {
                    val cur = bookState.pageRotations[pageRef.id] ?: 0
                    onBookStateChange(
                        bookState.copy(pageRotations = bookState.pageRotations + (pageRef.id to (cur + 90) % 360))
                    )
                }
            }
            ActionChip(
                if (currentRawPage in bookState.mergeAnchors) "取消临时合页" else "与下一页临时合页",
                Modifier.weight(1f)
            ) {
                onBookStateChange(
                    if (currentRawPage in bookState.mergeAnchors)
                        bookState.copy(mergeAnchors = bookState.mergeAnchors - currentRawPage)
                    else bookState.copy(mergeAnchors = bookState.mergeAnchors + currentRawPage)
                )
            }
        }
    }

    PanelSectionCard("画质增强", Icons.Filled.AutoAwesome) {
        SegmentRow(
            "增强引擎",
            ComicEnhanceMode.entries.map { it.ordinal to it.label },
            config.enhanceMode.ordinal
        ) { i -> update { it.copy(enhanceMode = ComicEnhanceMode.entries[i]) } }
        val enh = ComicEnhanceMode.entries[config.enhanceMode.ordinal]
        if (enh != ComicEnhanceMode.OFF) {
            Text(enh.desc, color = TextSecondary, fontSize = 11.sp)
            SliderRow(
                "增强强度", config.enhanceStrength.toFloat(), 0f..100f,
                format = { "${it.toInt()}" },
                onChange = { v -> update { it.copy(enhanceStrength = v.toInt()) } }
            )
        }
    }

    PanelSectionCard("滤镜", Icons.Filled.Tune) {
        FilterPreview(pages.getOrNull(currentRawPage), loader, config)
        SliderRow("亮度", config.filterBrightness.toFloat(), -100f..100f, format = { "${it.toInt()}" }) { v ->
            update { it.copy(filterBrightness = v.toInt()) }
        }
        SliderRow("对比度", config.filterContrast.toFloat(), -100f..100f, format = { "${it.toInt()}" }) { v ->
            update { it.copy(filterContrast = v.toInt()) }
        }
        SliderRow("饱和度", config.filterSaturation.toFloat(), -100f..100f, format = { "${it.toInt()}" }) { v ->
            update { it.copy(filterSaturation = v.toInt()) }
        }
        SliderRow("色调", config.filterHue.toFloat(), -180f..180f, format = { "${it.toInt()}°" }) { v ->
            update { it.copy(filterHue = v.toInt()) }
        }
        SliderRow("Gamma", config.filterGamma, 0.5f..2.2f, format = { "%.2f".format(it) }) { v ->
            update { it.copy(filterGamma = v) }
        }
        SliderRow("锐化", config.filterSharpen.toFloat(), 0f..100f, format = { "${it.toInt()}" }) { v ->
            update { it.copy(filterSharpen = v.toInt()) }
        }
        SliderRow("阴影", config.filterShadow.toFloat(), -100f..100f, format = { "${it.toInt()}" }) { v ->
            update { it.copy(filterShadow = v.toInt()) }
        }
        SwitchRow("黑白模式", null, config.filterBW) { v -> update { it.copy(filterBW = v) } }
        TextButton(onClick = {
            update {
                it.copy(
                    filterBrightness = 0, filterContrast = 0, filterSaturation = 0,
                    filterHue = 0, filterGamma = 1.0f, filterSharpen = 0, filterShadow = 0, filterBW = false
                )
            }
        }) { Text("重置全部滤镜", color = TextSecondary) }
    }
}

@Composable
private fun ActionChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelChipBg)
            .clickableNoRipple(onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextPrimary, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** 滤镜实时预览小图（独立小图管线，不重处理全尺寸页） */
@Composable
private fun FilterPreview(page: ComicPageRef?, loader: ComicPageLoader, config: ComicReaderConfig) {
    if (page == null) return
    val previewKey = "${page.id}|${config.imagePipelineFingerprint()}"
    val bitmap by produceState<Bitmap?>(null, previewKey) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                loader.loadPreview(
                    page,
                    ComicImagePipeline.Geometry(
                        cropMode = config.cropMode, manualCrop = config.manualCrop,
                        rotationDeg = config.bookRotation
                    ),
                    ComicImagePipeline.Toning(
                        brightness = config.filterBrightness, contrast = config.filterContrast,
                        saturation = config.filterSaturation, hue = config.filterHue,
                        gamma = config.filterGamma, sharpen = config.filterSharpen,
                        shadow = config.filterShadow, bw = config.filterBW,
                        enhanceMode = config.enhanceMode, enhanceStrength = config.enhanceStrength,
                    )
                )
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .width(64.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: com.example.ui.components.ChasingDots(size = 18.dp, color = MintPrimary)
        }
    }
}

/* ── Tab 3：主题（背景/场景/沉浸式） ── */

/** 背景类型的可视化色卡（选中 = 品牌描边环；内容直观看，无需解释文字） */
@Composable
private fun BgSwatchRow(selected: ComicBgType, onSelect: (ComicBgType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val swatches = listOf(
            Triple(ComicBgType.BLACK, "纯黑", Color(0xFF0A0A0C)),
            Triple(ComicBgType.WHITE, "纯白", Color(0xFFFAFAF7)),
            Triple(ComicBgType.GRAY, "深灰", Color(0xFF2A2A2E)),
            Triple(ComicBgType.PAPER, "纸张", Color(0xFFE9E2D0)),
            Triple(ComicBgType.DYNAMIC, "沉浸", Color(0xFF35323A)),
        )
        swatches.forEach { (type, label, fill) ->
            val active = type == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .border(
                            width = if (active) 2.dp else 0.5.dp,
                            color = if (active) MintPrimary else Color(0x40FFFFFF),
                            shape = CircleShape
                        )
                        .clickableNoRipple { onSelect(type) },
                    contentAlignment = Alignment.Center
                ) {
                    if (active) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (type == ComicBgType.BLACK || type == ComicBgType.GRAY || type == ComicBgType.DYNAMIC) {
                                MintPrimary
                            } else Color(0xFF44403A),
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (type == ComicBgType.DYNAMIC) {
                        // 沉浸式：小渐变示意（背景取自当前页主色调）
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xCCFFFFFF),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (active) MintPrimary else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** 场景图标卡（4 列 FlowRow；选中 = 品牌半透明填充 + 细边框） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneOptionFlow(selected: ComicScene, onSelect: (ComicScene) -> Unit) {
    val scenes = listOf(
        Triple(ComicScene.NONE, "关闭", Icons.Filled.Block),
        Triple(ComicScene.RAIN, "雨夜", Icons.Filled.WaterDrop),
        Triple(ComicScene.SNOW, "落雪", Icons.Filled.AcUnit),
        Triple(ComicScene.SAKURA, "樱花", Icons.Filled.LocalFlorist),
        Triple(ComicScene.FIREFLY, "萤火", Icons.Filled.AutoAwesome),
        Triple(ComicScene.OCEAN, "海边", Icons.Filled.Waves),
        Triple(ComicScene.CAMPFIRE, "篝火", Icons.Filled.LocalFireDepartment),
        Triple(ComicScene.NIGHT, "夏夜", Icons.Filled.NightsStay),
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 4
    ) {
        scenes.forEach { (s, label, icon) ->
            val active = s == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) panelSelectedBg() else PanelChipBg)
                    .border(
                        0.5.dp,
                        if (active) panelSelectedStroke() else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .clickableNoRipple { onSelect(s) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (active) MintPrimary else Color(0x99FFFFFF),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (active) MintPrimary else Color(0xAAFFFFFF),
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/** 引擎选择卡片（第十八轮二级导航一级列表）。 */
@Composable
internal fun EngineCard(
    title: String,
    desc: String,
    state: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MintPrimary.copy(alpha = 0.14f) else PanelChipBg)
            .border(
                1.dp,
                if (selected) MintPrimary.copy(alpha = 0.6f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                state,
                color = if (selected) MintPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = MintPrimary, modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 面板行返回按钮（二级页顶部）。 */
@Composable
internal fun PanelRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelChipBg)
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

/** 面板内轻量输入框（翻译 Tab AI 接口配置用）。 */
@Composable
internal fun PanelTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MintPrimary.copy(alpha = 0.6f),
                unfocusedBorderColor = Color(0x22FFFFFF),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EffectTab(config: ComicReaderConfig, update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit) {
    PanelSectionCard("阅读背景", Icons.Filled.Palette) {
        BgSwatchRow(config.bgType) { t -> update { it.copy(bgType = t) } }
        if (config.bgType == ComicBgType.PAPER) {
            Spacer(Modifier.height(8.dp))
            SliderRow(
                "纸纹强度", config.paperIntensity.toFloat(), 0f..100f,
                format = { "${it.toInt()}" }
            ) { v -> update { it.copy(paperIntensity = v.toInt()) } }
        }
    }

    PanelSectionCard("场景", Icons.Filled.MusicNote) {
        SceneOptionFlow(config.scene) { s -> update { it.copy(scene = s) } }
        if (config.scene != ComicScene.NONE) {
            Spacer(Modifier.height(6.dp))
            SwitchRow("环境声音", null, config.sceneSound) { v -> update { it.copy(sceneSound = v) } }
            if (config.sceneSound) {
                SliderRow(
                    "音量", config.sceneVolume.toFloat(), 0f..100f,
                    format = { "${it.toInt()}" }
                ) { v -> update { it.copy(sceneVolume = v.toInt()) } }
            }
            SwitchRow("阅读特效", null, config.sceneEffect) { v -> update { it.copy(sceneEffect = v) } }
        }
    }

    PanelSectionCard("显示", Icons.Filled.Visibility) {
        SwitchRow("进度缩略图", "拖动进度条时显示页面预览", config.showThumbPreview) { v ->
            update { it.copy(showThumbPreview = v) }
        }
        SwitchRow("沉浸式", "隐藏系统状态栏 / 导航栏", config.hideSystemBars) { v ->
            update { it.copy(hideSystemBars = v) }
        }
    }
}

/* ── Tab 4：自动阅读 ── */

@Composable
private fun AutoTab(config: ComicReaderConfig, update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit) {
    PanelSectionCard("自动翻页", Icons.Filled.Schedule) {
        SliderRow(
            "翻页间隔", config.autoPageIntervalSec, 2f..60f,
            format = { "${it.toInt()} 秒" }
        ) { v -> update { it.copy(autoPageIntervalSec = v) } }
    }

    PanelSectionCard("自动滚动", Icons.Filled.SwipeVertical) {
        SliderRow(
            "滚动速度", config.autoScrollSpeedDp, 10f..300f,
            format = { "${it.toInt()} dp/s" }
        ) { v -> update { it.copy(autoScrollSpeedDp = v) } }
    }
}

/* ── Tab 5：手势 ── */

@Composable
private fun GestureTab(config: ComicReaderConfig, update: ((ComicReaderConfig) -> ComicReaderConfig) -> Unit) {
    PanelSectionCard("点按区域动作", Icons.Filled.PanTool) {
        val actions = ComicGestureAction.entries.map { it.ordinal to it.label }
        SegmentRow("左 / 上区域", actions, config.gestureTapLeft.ordinal) { i ->
            update { it.copy(gestureTapLeft = ComicGestureAction.entries[i]) }
        }
        SegmentRow("中间区域", actions, config.gestureTapCenter.ordinal) { i ->
            update { it.copy(gestureTapCenter = ComicGestureAction.entries[i]) }
        }
        SegmentRow("右 / 下区域", actions, config.gestureTapRight.ordinal) { i ->
            update { it.copy(gestureTapRight = ComicGestureAction.entries[i]) }
        }
    }

    PanelSectionCard("手势开关", Icons.Filled.TouchApp) {
        SwitchRow("单指滑动翻页", null, config.gestureSwipe) { v -> update { it.copy(gestureSwipe = v) } }
        SwitchRow("双指合拢退出", "缩小到阈值时退出阅读", config.gesturePinchClose) { v ->
            update { it.copy(gesturePinchClose = v) }
        }
        SwitchRow("侧边滑动关闭", null, config.gestureEdgeSwipe) { v ->
            update { it.copy(gestureEdgeSwipe = v) }
        }
        SwitchRow("长按呼出控制层", "与长按放大互斥（放大优先）", config.gestureLongPressPanel) { v ->
            update { it.copy(gestureLongPressPanel = v) }
        }
        // 第 28 条：音量键翻页（方向感知：RTL 下音量上=下一页），仅阅读页拦截
        SwitchRow("音量键翻页", null, config.volumeKeyTurn) { v ->
            update { it.copy(volumeKeyTurn = v) }
        }
    }
}

/* ══════════════ 目录面板 ══════════════ */

@Composable
internal fun ComicTocSheet(
    toc: List<ComicTocEntry>,
    currentChapterIndex: Int,
    onJumpToChapter: ((Int) -> Unit)?,
    pages: List<ComicPageRef>,
    loader: ComicPageLoader,
    currentPage: Int,
    onJumpToRawPage: (Int) -> Unit,
    onDismiss: () -> Unit,
    glassBackdrop: LayerBackdrop? = null,
) {
    ComicSheetContainer(
        title = "目录", onDismiss = onDismiss, heightFraction = 0.7f, scrollable = false,
        glassBackdrop = glassBackdrop,
    ) {
        if (toc.isNotEmpty()) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIndex.coerceAtLeast(0))
            LaunchedEffect(Unit) {
                runCatching { listState.scrollToItem(currentChapterIndex.coerceAtLeast(0)) }
            }
            LazyColumn(state = listState) {
                // 2026-09-21：补 key（章节 id 稳定唯一），并挂重排动画。
                itemsIndexed(toc, key = { _, entry -> entry.id }) { i, entry ->
                    val active = i == currentChapterIndex
                    Row(
                        Modifier
                            .animateItemPlacement()
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) PanelChipActiveBg else Color.Transparent)
                            .clickableNoRipple { onJumpToChapter?.invoke(i) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(width = 3.dp, height = 18.dp)
                                .clip(CircleShape)
                                .background(if (active) MintPrimary else Color.Transparent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.title,
                            color = if (active) MintPrimary else Color(0xCCFFFFFF),
                            fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(Icons.Filled.Check, null, tint = MintPrimary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        } else {
            // 本地漫画：页缩略图网格
            LazyVerticalGrid(
                columns = GridCells.Adaptive(72.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(pages) { i, page ->
                    PageThumbCell(page, i, loader, i == currentPage - 1) { onJumpToRawPage(i) }
                }
            }
        }
    }
}

@Composable
private fun PageThumbCell(
    page: ComicPageRef,
    index: Int,
    loader: ComicPageLoader,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, page.id) {
        value = runCatching { loader.loadThumb(page) }.getOrNull()
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) PanelChipActiveBg else Color(0x14FFFFFF))
            .border(
                0.5.dp,
                if (active) MintPrimary.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .clickableNoRipple(onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x1FFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: com.example.ui.components.ChasingDots(size = 14.dp, color = MintPrimary, circleRatio = 0.3f)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "${index + 1}", color = if (active) MintPrimary else TextSecondary, fontSize = 11.sp
        )
    }
}

/* ══════════════ 预设面板 ══════════════ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComicPresetSheet(
    store: ComicSettingsStore,
    currentConfig: ComicReaderConfig,
    onApply: (ComicReaderConfig) -> Unit,
    onDismiss: () -> Unit,
    glassBackdrop: LayerBackdrop? = null,
) {
    var presets by remember { mutableStateOf(store.loadPresets()) }
    var defaultId by remember { mutableStateOf(store.defaultPresetId()) }
    var renameTarget by remember { mutableStateOf<ComicPreset?>(null) }
    var createDialog by remember { mutableStateOf(false) }

    fun refresh() {
        presets = store.loadPresets()
        defaultId = store.defaultPresetId()
    }

    ComicSheetContainer(
        title = "阅读预设", onDismiss = onDismiss, heightFraction = 0.62f, scrollable = false,
        glassBackdrop = glassBackdrop,
    ) {
        LazyColumn {
            // 收藏置顶（第 27 条）：列表按 favorite 降序稳定排序，收藏组永远在最前
            items(presets.sortedByDescending { it.favorite }, key = { it.id }) { preset ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (preset.favorite) PanelChipActiveBg else PanelChipBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 图标
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x2AF0D9C0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(preset.emoji, color = MintPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            FlowRow(verticalArrangement = Arrangement.Center) {
                                Text(
                                    preset.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (preset.favorite) {
                                    Spacer(Modifier.width(6.dp))
                                    // 收藏标记（区别于"默认预设"）：实心星 + 强调色
                                    Icon(Icons.Filled.Star, "已收藏", tint = Color(0xFFFFD27D), modifier = Modifier.size(13.dp))
                                }
                                if (preset.id == defaultId) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "默认", color = MintPrimary, fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x1FFFFFFF))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                                if (preset.builtIn) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "内置", color = TextSecondary, fontSize = 10.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x1FFFFFFF))
                                            .padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                            Text(
                                "${preset.config.mode.label} · ${preset.config.direction.label} · ${preset.config.pageAnim.label}",
                                color = TextSecondary, fontSize = 11.sp
                            )
                        }
                        // 应用
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFF0D9C0))
                                .clickableNoRipple { onApply(preset.config) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("应用", color = Color(0xFF0E1512), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    // Keep actions on their own wrapping row so names retain their width
                    // on small screens and when the system font size is increased.
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // 收藏开关（第 27 条：收藏后置顶 + 长按设置入口快捷应用）
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickableNoRipple {
                                    store.togglePresetFavorite(preset.id)
                                    refresh()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (preset.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                if (preset.favorite) "取消收藏" else "收藏",
                                tint = if (preset.favorite) Color(0xFFFFD27D) else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        // 设为默认
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickableNoRipple {
                                    store.setDefaultPreset(preset.id)
                                    refresh()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PushPin,
                                "设为默认",
                                tint = if (preset.id == defaultId) MintPrimary else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickableNoRipple {
                                    store.duplicatePreset(preset.id)
                                    refresh()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ContentCopy, "复制", tint = TextSecondary, modifier = Modifier.size(15.dp))
                        }
                        if (!preset.builtIn) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickableNoRipple { renameTarget = preset },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.DriveFileRenameOutline, "重命名", tint = TextSecondary, modifier = Modifier.size(15.dp))
                            }
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickableNoRipple {
                                        store.deletePreset(preset.id)
                                        refresh()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFFF9A9A), modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PanelChipActiveBg)
                        .clickableNoRipple { createDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = MintPrimary, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从当前设置新建预设", color = MintPrimary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (createDialog) {
        PresetNameDialog(
            title = "新建预设",
            initial = "",
            onConfirm = { name ->
                store.createPreset(name.ifBlank { "我的预设" }, name.take(2).ifBlank { "SC" }, currentConfig)
                refresh()
                createDialog = false
            },
            onDismiss = { createDialog = false }
        )
    }
    renameTarget?.let { target ->
        PresetNameDialog(
            title = "重命名预设",
            initial = target.name,
            onConfirm = { name ->
                store.updatePreset(target.id, name = name.ifBlank { target.name })
                refresh()
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}


@Composable
private fun PresetNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(12) },
                singleLine = true,
                placeholder = { Text("预设名称", color = TextSecondary) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = MintPrimary,
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    cursorColor = MintPrimary
                )
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("确定", color = MintPrimary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Color(0xFF232327)
    )
}

/* ══════════════ 手动裁边编辑器 ══════════════ */

@Composable
internal fun ComicCropSheet(
    page: ComicPageRef?,
    loader: ComicPageLoader,
    config: ComicReaderConfig,
    onDismiss: () -> Unit,
    onApply: (Float, Float, Float, Float) -> Unit,
) {
    if (page == null) return
    val bitmap by produceState<Bitmap?>(null, page.id) {
        value = withContext(Dispatchers.Default) {
            // 预览管线加载小图（不把全尺寸原图塞进主缓存）
            runCatching { loader.loadPreview(page, ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()) }
                .getOrNull()
        }
    }
    var crop by remember(config.manualCrop) {
        mutableStateOf(
            config.manualCrop?.let { floatArrayOf(it[0], it[1], it[2], it[3]) }
                ?: floatArrayOf(0.06f, 0.06f, 0.94f, 0.94f)
        )
    }
    var layoutSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size(1f, 1f)) }
    var imgRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    // 第 6 节：裁边框贴边轻触觉（组合期取 view，lambda 内使用）
    val cropHapticView = androidx.compose.ui.platform.LocalView.current

    fun onCropChangeWithHaptic(view: android.view.View): (FloatArray) -> Unit = { raw ->
        // 限制最小裁剪区 5%；贴边（0/1 边界被钳制）轻触觉反馈
        raw[0] = raw[0].coerceIn(0f, raw[2] - 0.05f)
        raw[1] = raw[1].coerceIn(0f, raw[3] - 0.05f)
        raw[2] = raw[2].coerceIn(raw[0] + 0.05f, 1f)
        raw[3] = raw[3].coerceIn(raw[1] + 0.05f, 1f)
        if (raw[0] == 0f || raw[1] == 0f || raw[2] == 1f || raw[3] == 1f) {
            if (com.example.ui.feedback.HapticsGate.enabled) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        crop = raw.copyOf()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手动裁边", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    crop = floatArrayOf(0.06f, 0.06f, 0.94f, 0.94f)
                }) { Text("重置", color = TextSecondary) }
                TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
                TextButton(onClick = { onApply(crop[0], crop[1], crop[2], crop[3]) }) {
                    Text("保存", color = MintPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("拖动四角调整裁剪区域（不修改原文件）", color = TextSecondary, fontSize = 11.sp)

            Spacer(Modifier.height(12.dp))
            BoxWithImageLayout(
                bitmap = bitmap,
                modifier = Modifier.weight(1f),
                onLayout = { size, rect -> layoutSize = size; imgRect = rect },
            ) { canvasModifier ->
                CropCanvas(
                    canvasModifier = canvasModifier,
                    crop = crop,
                    onCropChange = onCropChangeWithHaptic(cropHapticView),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "L %.0f%% · T %.0f%% · R %.0f%% · B %.0f%%".format(
                    crop[0] * 100, crop[1] * 100, (1 - crop[2]) * 100, (1 - crop[3]) * 100
                ),
                color = TextSecondary, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun BoxWithImageLayout(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    onLayout: (androidx.compose.ui.geometry.Size, Rect) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            val image = bmp.asImageBitmap()
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().aspectRatio(0.75f)
            ) {
                val boxW = maxWidth.value * density.density
                val boxH = maxHeight.value * density.density
                val ratio = image.width.toFloat() / image.height.toFloat()
                val drawW: Float
                val drawH: Float
                if (boxW / boxH > ratio) { drawH = boxH; drawW = boxH * ratio }
                else { drawW = boxW; drawH = boxW / ratio }
                LaunchedEffect(drawW, drawH) {
                    onLayout(androidx.compose.ui.geometry.Size(drawW, drawH), Rect(0f, 0f, drawW, drawH))
                }
                // 图片 + 裁剪层叠加
                Box(
                    Modifier.size(
                        with(density) { drawW.toDp() },
                        with(density) { drawH.toDp() }
                    )
                ) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    content(Modifier.fillMaxSize())
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.example.ui.components.ChasingDots(size = 36.dp, color = MintPrimary)
            }
        }
    }
}

/** 裁剪框画布：四角拖动 + 边缘拖动 */
@Composable
private fun CropCanvas(
    canvasModifier: Modifier,
    crop: FloatArray,
    onCropChange: (FloatArray) -> Unit,
) {
    val handleR = 14.dp
    var active by remember { mutableStateOf(-1) } // 0..3 角 4..7 边
    // 手势协程以 Unit 为 key 只启动一次——闭包内必须读最新 crop：
    // 直接捕获参数会把初始矩形固化进手势（第 18 条"移动左框再移右框，
    // 左框跳回原位"的根因：每次拖动都从初始矩形重新派生）
    val currentCrop by rememberUpdatedState(crop)
    val currentOnChange by rememberUpdatedState(onCropChange)
    Box(
        canvasModifier
            .pointerInput(Unit) {
                val hr = handleR.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // 命中判定基于「上一次调整后」的最新框位置
                    val x0 = currentCrop[0] * w; val y0 = currentCrop[1] * h
                    val x1 = currentCrop[2] * w; val y1 = currentCrop[3] * h
                    val corners = listOf(
                        Offset(x0, y0), Offset(x1, y0), Offset(x0, y1), Offset(x1, y1)
                    )
                    val edges = listOf(
                        Offset((x0 + x1) / 2, y0), Offset((x0 + x1) / 2, y1),
                        Offset(x0, (y0 + y1) / 2), Offset(x1, (y0 + y1) / 2)
                    )
                    active = -1
                    corners.forEachIndexed { i, c -> if ((down.position - c).getDistance() < hr * 1.6f) active = i }
                    if (active == -1) edges.forEachIndexed { i, c ->
                        if ((down.position - c).getDistance() < hr * 1.6f) active = i + 4
                    }
                    if (active >= 0) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val px = (ch.position.x / w).coerceIn(0f, 1f)
                            val py = (ch.position.y / h).coerceIn(0f, 1f)
                            // 派生基准 = 最新 crop（连续交替拖动四边互不回退）
                            val n = currentCrop.copyOf()
                            when (active) {
                                0 -> { n[0] = px; n[1] = py }
                                1 -> { n[2] = px; n[1] = py }
                                2 -> { n[0] = px; n[3] = py }
                                3 -> { n[2] = px; n[3] = py }
                                4 -> n[1] = py
                                5 -> n[3] = py
                                6 -> n[0] = px
                                7 -> n[2] = px
                            }
                            currentOnChange(n)
                            ch.consume()
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val x0 = crop[0] * w; val y0 = crop[1] * h
            val x1 = crop[2] * w; val y1 = crop[3] * h
            // 外部半透明遮罩
            val dim = Color(0x99000000)
            drawRect(dim, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, y0))
            drawRect(dim, topLeft = Offset(0f, y1), size = androidx.compose.ui.geometry.Size(w, h - y1))
            drawRect(dim, topLeft = Offset(0f, y0), size = androidx.compose.ui.geometry.Size(x0, y1 - y0))
            drawRect(dim, topLeft = Offset(x1, y0), size = androidx.compose.ui.geometry.Size(w - x1, y1 - y0))
            // 裁剪框
            drawRect(
                Color(0xFFF0D9C0), topLeft = Offset(x0, y0),
                size = androidx.compose.ui.geometry.Size(x1 - x0, y1 - y0),
                style = Stroke(width = 2.dp.toPx())
            )
            // 三分线
            for (i in 1..2) {
                val gx = x0 + (x1 - x0) * i / 3
                val gy = y0 + (y1 - y0) * i / 3
                drawLine(Color(0x66FFFFFF), Offset(gx, y0), Offset(gx, y1), strokeWidth = 1f)
                drawLine(Color(0x66FFFFFF), Offset(x0, gy), Offset(x1, gy), strokeWidth = 1f)
            }
            // 手柄
            listOf(
                Offset(x0, y0), Offset(x1, y0), Offset(x0, y1), Offset(x1, y1),
                Offset((x0 + x1) / 2, y0), Offset((x0 + x1) / 2, y1),
                Offset(x0, (y0 + y1) / 2), Offset(x1, (y0 + y1) / 2)
            ).forEach { c ->
                drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
                drawCircle(AccentColor, radius = 3.dp.toPx(), center = c)
            }
        }
    }
}
