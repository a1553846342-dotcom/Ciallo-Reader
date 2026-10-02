package com.example.god

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.example.ui.components.BottomSheetEntrance
import com.example.ui.components.ScrimEntrance
import com.example.ui.components.crystalInnerBevel
import com.example.ui.components.filmGrain
import com.example.ui.components.iridescentBorder
import com.example.ui.components.radialGlassScrim
import me.trishiraj.shadowglow.consistentShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* ══════════════ 亚克力面板（与全 App 弹层同一套视觉词汇） ══════════════ */

/**
 * 神回的虹彩棱镜描边配色：由金调三色 + 珍珠白高光组成。
 *
 * 用 `rememberCrystalPrismColors` 那套「主色调相 ±32°」的算法会算出绿/紫，
 * 与神回的金色身份冲突；这里改为在金调内部做色散（浅金→金→深金→珍珠白），
 * 既保留亚克力折光质感，又不引入无关色相。
 */
@Composable
fun rememberGodPrismColors(darkTheme: Boolean): List<Color> {
    val gold = GodGold.goldGradient(darkTheme)
    return remember(gold) {
        listOf(gold[0], gold[1], Color(0xFFEAF5F8), gold[2], gold[1], gold[0])
    }
}

/**
 * 神回亚克力面板。
 *
 * **为什么不继续用 Haze 真毛玻璃**：
 * 1. 默认预设是 CURL，阅读页主体是 GLSurfaceView —— Haze 的 `haze()` 会把这层
 *    内容录进 Compose GraphicsLayer 采样，正是项目明令禁止的「Surface 合成异常」
 *    （神回窗口一弹出就开始采样 → 闪退，2026-09-29 已修过一次）；
 * 2. 视觉上也不搭：全 App 的弹层/面板是 `acrylicPanel` 那套亚克力词汇，
 *    单独一块 Haze 玻璃像是另一个 App 里搬来的。
 *
 * 这里逐层复刻 `acrylicPanel`：双层 consistentShadow（金调环境光 + 黑色接触影）
 * → 裁剪 → 半透明基底 → 顶棱抛光聚光带 → 金调对角光束 → 噪点 → 虹彩描边
 * → 水晶倒角。金调用作环境光/光束的着色，保留神回身份。
 */
@Composable
fun Modifier.godAcrylicPanel(
    shape: Shape,
    darkTheme: Boolean,
    surfaceAlpha: Float = 0.74f,
): Modifier {
    val gold = GodGold.goldGradient(darkTheme)
    val prism = rememberGodPrismColors(darkTheme)
    val surface = MaterialTheme.colorScheme.surface
    // 不用"一块平铺半透明面"：上实下虚的微渐变 + 底部一丝金调，
    // 面板才有从光源方向被照到的层次，否则在浅背景上是一块发灰的板
    val bodyGradient = Brush.verticalGradient(
        listOf(
            surface.copy(alpha = (surfaceAlpha + 0.10f).coerceAtMost(1f)),
            surface.copy(alpha = surfaceAlpha),
            surface.copy(alpha = (surfaceAlpha - 0.06f).coerceAtLeast(0.5f)),
        ),
    )
    return this
        .consistentShadow(
            elevation = 34.dp,
            shape = shape,
            ambientColor = gold[1].copy(alpha = 0.16f),
            spotColor = gold[2].copy(alpha = 0.20f),
        )
        .consistentShadow(
            elevation = 10.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.16f),
            spotColor = Color.Black.copy(alpha = 0.22f),
        )
        .clip(shape)
        .background(bodyGradient)
        .drawBehind {
            // 顶棱抛光聚光带（与 acrylicPanel 同款，压到 2.5dp 的发丝亮边）
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.66f),
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.06f),
                    )
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, 2.5.dp.toPx()),
            )
            // 125° 对角镜面光束 + 底部金调微晕染
            drawRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.04f),
                        Color.Transparent,
                        gold[0].copy(alpha = 0.05f),
                        gold[1].copy(alpha = 0.07f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
        }
        .filmGrain(alpha = 0.03f)
        .iridescentBorder(shape = shape, colors = prism, width = 1.5.dp, alpha = 0.34f)
        .crystalInnerBevel(shape = shape, width = 1.dp)
}

/* ══════════════ 神回窗口 ══════════════ */

private const val TITLE_MAX = 30
private const val NOTE_MAX = 500
private const val SHEET_HEIGHT_FRACTION = 0.92f

/**
 * 神回窗口（占屏约 90%）。
 *
 * 做成**自定义 Sheet**而不是 ModalBottomSheet：ModalBottomSheet 走 Popup 子窗口，
 * 背后内容不受控；自绘 Sheet 与阅读页同处一棵图层树，转场与遮罩节奏才能和
 * 全 App 的弹层一致。表面材质用 [godAcrylicPanel]（与 AcrylicDialog /
 * AcrylicBottomOverlay 同一套亚克力词汇），不再单独依赖 Haze 采样。
 */
@Composable
fun GodMomentSheet(
    request: GodMomentRequest,
    existing: GodMomentEntity?,
    viewModel: GodMomentViewModel,
    remoteLoader: ImageLoader? = null,
    onDismiss: () -> Unit,
    onSaved: (GodMomentEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduce = rememberReduceMotion()
    val dark = godIsDark()
    val editing = existing != null

    /* ── 表单状态（旋转/进程重建不丢） ──
       coverSource / crop 是自定义对象，rememberSaveable 无法直接存 —— 用它们的
       序列化 tag 作为唯一真值（String 可存），读取时解析回来。 */
    var coverSourceTag by rememberSaveable {
        mutableStateOf(
            (existing?.source ?: CoverSource.ComicPage(request.initialPageIndex.coerceAtLeast(0))).toTag(),
        )
    }
    // 局部变量不能带 getter —— 直接每次重组时从 tag 解析（解析是纯函数、成本可忽略）
    val coverSource: CoverSource = parseCoverSource(coverSourceTag)
    fun setCoverSource(s: CoverSource) { coverSourceTag = s.toTag() }

    var cropTag by rememberSaveable { mutableStateOf((existing?.crop ?: CropParams.DEFAULT).toTag()) }
    val crop: CropParams = CropParams.parse(cropTag)
    fun setCrop(c: CropParams) { cropTag = c.toTag() }

    var rating by rememberSaveable {
        mutableFloatStateOf((existing?.rating ?: 5f).coerceIn(GOD_RATING_MIN, GOD_RATING_MAX))
    }
    var titleCustom by rememberSaveable { mutableStateOf(existing?.titleIsCustom ?: false) }
    var titleText by rememberSaveable {
        mutableStateOf(if (titleCustom) (existing?.title ?: "") else "")
    }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }

    val defaultTitle = "${request.bookTitle} 第${request.chapterNumber}话"
    val shownTitle = if (titleCustom && titleText.isNotBlank()) titleText else defaultTitle

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loadingCover by remember { mutableStateOf(false) }
    var showCrop by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    /* ── 载入当前封面来源（后台线程解码） ──
       从详情页编辑时没有页列表（图片未加载）→ 退化为直接以已合成封面作为底图，
       仍可"从相册选择"与裁剪（裁剪的是已合成封面，尺寸恒定不会错）。 */
    LaunchedEffect(coverSource, request.pages, existing?.coverPath) {
        loadingCover = true
        val bmp = withContext(Dispatchers.Default) {
            when (val s = coverSource) {
                is CoverSource.ComicPage -> {
                    val ref = request.pages.getOrNull(s.pageIndex)
                        ?: request.pages.firstOrNull()
                    ref?.let { GodCoverEngine.loadPage(context, it, GodCoverEngine.DECODE_MAX_EDGE, remoteLoader) }
                        ?: existing?.coverPath?.let { android.graphics.BitmapFactory.decodeFile(it) }
                }
                is CoverSource.Album -> runCatching {
                    GodCoverEngine.loadUri(context, android.net.Uri.parse(s.uri))
                }.getOrNull()
                is CoverSource.NovelExcerpt -> null
            }
        }
        sourceBitmap = bmp
        loadingCover = false
    }

    /* ── 预览合成（450×600，保证拖动裁剪时流畅） ── */
    LaunchedEffect(sourceBitmap, crop) {
        val src = sourceBitmap
        preview = if (src == null) null
        else withContext(Dispatchers.Default) {
            runCatching {
                GodCoverEngine.compose(
                    GodCoverEngine.applyCrop(src, crop),
                    GodCoverEngine.PREVIEW_W,
                    GodCoverEngine.PREVIEW_H,
                )
            }.getOrNull()
        }
    }

    /* ── 相册（Photo Picker，无需权限） ── */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, "未选择图片", Toast.LENGTH_SHORT).show()
        } else {
            setCoverSource(CoverSource.Album(uri.toString()))
            setCrop(CropParams.DEFAULT)
        }
    }

    val dirty = remember(editing, rating, titleCustom, titleText, note, coverSource, crop) {
        val base = existing
        if (base == null) {
            rating != 5f || titleCustom || note.isNotBlank() || coverSource != CoverSource.ComicPage(request.initialPageIndex)
        } else {
            rating != base.rating || titleCustom != base.titleIsCustom ||
                titleText != (if (base.titleIsCustom) base.title else "") ||
                note != base.note || coverSource.toTag() != base.coverSource || crop.toTag() != base.cropParams
        }
    }

    /* ── 保存（防抖：saving 期间忽略重复点击） ── */
    fun doSave() {
        if (saving) return
        saving = true
        scope.launch {
            val cropped = withContext(Dispatchers.Default) {
                sourceBitmap?.let { GodCoverEngine.applyCrop(it, crop) }
            }
            val path = cropped?.let {
                GodCoverEngine.composeAndSave(context, request.bookId, request.chapterId, it)
            } ?: existing?.coverPath
            val now = System.currentTimeMillis()
            val entity = GodMomentEntity(
                id = existing?.id ?: 0L,
                contentType = request.contentType.code,
                bookId = request.bookId,
                chapterId = request.chapterId,
                bookTitle = request.bookTitle,
                chapterTitle = request.chapterTitle,
                chapterNumber = request.chapterNumber,
                title = if (titleCustom) titleText else defaultTitle,
                titleIsCustom = titleCustom,
                rating = rating,
                note = note,
                coverPath = path,
                coverSource = coverSource.toTag(),
                cropParams = crop.toTag(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            val id = viewModel.repository.save(entity)
            burst = true
            kotlinx.coroutines.delay(620)
            onSaved(entity.copy(id = id))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景遮罩：改用全 App 弹层同款的径向聚光遮罩（中心亮四周暗），
        // 不再是"一块均匀的黑布"，面板像被聚光灯打在立牌上
        ScrimEntrance {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .radialGlassScrim()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!saving) {
                            if (dirty) confirmClose = true else onDismiss()
                        }
                    },
            )
        }

        // Sheet 表面：底部面板入场（淡入 + iOS 弹簧上推），面板走亚克力词汇
        val shape = RoundedCornerShape(
            topStart = GodMotion.SHEET_CORNER.dp,
            topEnd = GodMotion.SHEET_CORNER.dp,
        )
        BottomSheetEntrance(modifier = Modifier.align(Alignment.BottomCenter)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                // 键盘弹出：整张 Sheet 平滑上移，输入框与吸底栏都不被遮挡
                .imePadding()
                .godAcrylicPanel(shape = shape, darkTheme = dark),
        ) {
            // 下拉把手：与底部弹层同规格（44×5 胶囊），拖拽时轻微变金
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    // 底部渐隐：滚动内容在吸底操作栏上方柔和淡出。
                    // 实现是"内容层自身淡出"（Offscreen 层 + DstIn 把尾部 alpha 压到 0，
                    // 露出的就是亚克力面板本色）；旧实现叠一块比面板更不透明的
                    // surface 色带，在半透明面板上会显出一圈更浅的硬边。
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val band = 44.dp.toPx()
                        val f = (band / size.height.coerceAtLeast(1f)).coerceIn(0.04f, 0.4f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black,
                                    1f - f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // ① 标题
                GodSheetBlock(index = 0, reduce = reduce) {
                    GodSheetTitle(
                        editing = editing,
                        reduce = reduce,
                        dark = dark,
                        bookTitle = request.bookTitle,
                        chapterTitle = request.chapterTitle,
                    )
                }

                Spacer(Modifier.height(14.dp))
                // ② 封面
                GodSheetBlock(index = 1, reduce = reduce) {
                    GodCoverSection(
                        preview = preview,
                        selectedBitmap = sourceBitmap,
                        loading = loadingCover,
                        pages = request.pages,
                        selected = (coverSource as? CoverSource.ComicPage)?.pageIndex ?: -1,
                        albumSelected = coverSource is CoverSource.Album,
                        initialPage = request.initialPageIndex,
                        remoteLoader = remoteLoader,
                        reduce = reduce,
                        onPickPage = { idx ->
                            setCoverSource(CoverSource.ComicPage(idx))
                            setCrop(CropParams.DEFAULT)
                        },
                        onPickAlbum = {
                            runCatching {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.onFailure {
                                Toast.makeText(context, "无法打开相册", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCrop = {
                            if (sourceBitmap != null) showCrop = true
                            else Toast.makeText(context, "封面尚未就绪", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                Spacer(Modifier.height(18.dp))
                // ③ 评分
                GodSheetBlock(index = 2, reduce = reduce) {
                    GodFormSection(number = "01", title = "为这一话打分", caption = "半星也能准确表达喜欢的程度") {
                        GodStarRating(
                            rating = rating,
                            onRatingChange = { rating = it },
                            reduceMotion = reduce,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                // ④ 名称
                GodSheetBlock(index = 3, reduce = reduce) {
                    GodFormSection(number = "02", title = "留下它的名字", caption = "留空时自动使用书名和章节号") {
                        GodTitleField(
                            value = if (titleCustom) titleText else "",
                            placeholder = defaultTitle,
                            custom = titleCustom,
                            onValueChange = { raw ->
                                val v = raw.take(TITLE_MAX)
                                titleText = v
                                // 清空 = 放弃自定义 → 自动回退默认标题
                                titleCustom = v.isNotBlank()
                            },
                            onResetDefault = {
                                titleCustom = false
                                titleText = ""
                            },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                // ⑤ 随笔
                GodSheetBlock(index = 4, reduce = reduce) {
                    GodFormSection(number = "03", title = "这一刻的想法", caption = "写给下次重读的自己") {
                        GodNoteField(
                            value = note,
                            onValueChange = { note = it.take(NOTE_MAX) },
                            dark = dark,
                        )
                    }
                }

                // 底部多留一段：滚到底时内容不被渐隐带吃掉
                Spacer(Modifier.height(34.dp))
            }
            }

            // ⑥ 吸底操作栏
            GodActionBar(
                editing = editing,
                saving = saving,
                enabled = !saving,
                reduce = reduce,
                dark = dark,
                title = shownTitle,
                rating = rating,
                onCancel = { if (dirty) confirmClose = true else onDismiss() },
                onConfirm = ::doSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        }

        // 保存成功：星光/礼花 + 封面上浮
        if (burst) {
            GodBurstOverlay(preview = preview, reduce = reduce)
        }

        // 未保存修改二次确认
        if (confirmClose) {
            GodConfirmCloseDialog(
                onKeep = { confirmClose = false },
                onDiscard = { confirmClose = false; onDismiss() },
            )
        }
    }

    if (showCrop) {
        GodCoverCropScreen(
            source = sourceBitmap,
            initialCrop = crop,
            onCancel = { showCrop = false },
            onConfirm = { newCrop ->
                setCrop(newCrop)
                showCrop = false
            },
        )
    }
}

/* ══════════════ ① 标题区 ══════════════ */

@Composable
private fun GodSheetTitle(
    editing: Boolean,
    reduce: Boolean,
    dark: Boolean,
    bookTitle: String,
    chapterTitle: String,
) {
    val infinite = rememberInfiniteTransition(label = "godTitle")
    val gold = GodGold.goldGradient(dark)
    val spanPx = with(LocalDensity.current) { 260.dp.toPx() }
    val shift by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(GodMotion.SHINE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "goldShine",
    )
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "glowDrift",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp)
            .drawBehind {
                if (reduce) return@drawBehind
                // 缓慢漂移的渐变光斑（比原来更淡：0.20 → 0.13，不与金色标题抢焦点）
                val cx = size.width * (0.25f + 0.5f * drift)
                val cy = size.height * (0.3f + 0.4f * (1f - drift))
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(gold.first().copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(cx, cy),
                )
                // 几颗闪烁星点（收在标题右侧空区，不压到文字上）
                for (i in 0 until 4) {
                    val a = (drift * 2f * Math.PI + i * 1.7f).toFloat()
                    val alpha = (0.22f + 0.22f * sin(a)).coerceIn(0.04f, 0.44f)
                    val x = size.width * (0.62f + i * 0.11f)
                    val y = size.height * (0.22f + 0.5f * abs(cos(a)))
                    drawCircle(gold.first().copy(alpha = alpha), radius = 2.2.dp.toPx(), center = Offset(x, y))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 流光：金色渐变沿水平方向缓慢平移（reduce 时退化为静态渐变）
        val brush = if (reduce) {
            Brush.linearGradient(gold)
        } else {
            Brush.linearGradient(
                colors = listOf(gold[0], gold[1], gold[2], gold[1], gold[0]),
                start = Offset(shift * spanPx, 0f),
                end = Offset(shift * spanPx + spanPx, spanPx * 0.22f),
            )
        }
        Column {
            Text(
                text = if (editing) "MOMENT / EDIT" else "MOMENT / NEW",
                color = gold[1],
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = if (editing) "重写这一刻 ✦" else "收藏这一刻 ✦",
                // 字号 40 → 32，字距 +1 → -0.6：跟随全 App 的 iOS 字阶
                //（字号越大字距越紧），40sp 配正字距在中文上显松散
                style = MaterialTheme.typography.displayMedium.copy(
                    brush = brush,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$bookTitle  /  $chapterTitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Sheet 内部区块的错峰入场：淡入 + 上浮 26dp，每块延迟 [GodMotion.STAGGER_MS]。
 * 「减少动态效果」时退化为一次 120ms 淡入。
 */
@Composable
private fun GodSheetBlock(
    index: Int,
    reduce: Boolean,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reduce) {
            visible = true
        } else {
            delay(staggerDelay(index, 60).toLong())
            visible = true
        }
    }
    val f by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (reduce) 120 else 320),
        label = "sheetBlock",
    )
    val density = LocalDensity.current
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = f
            translationY = (1f - f) * with(density) { 26.dp.toPx() }
        },
    ) { content() }
}

/* ══════════════ ② 封面区 ══════════════ */

@Composable
private fun GodCoverSection(
    preview: Bitmap?,
    selectedBitmap: Bitmap?,
    loading: Boolean,
    pages: List<GodPageRef>,
    selected: Int,
    albumSelected: Boolean,
    initialPage: Int,
    remoteLoader: ImageLoader?,
    reduce: Boolean,
    onPickPage: (Int) -> Unit,
    onPickAlbum: () -> Unit,
    onCrop: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(pages.size, selected) {
        val idx = selected.takeIf { it >= 0 } ?: initialPage
        if (idx in pages.indices) runCatching { listState.animateScrollToItem(idx) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // 封面预览（3D 倾斜）
        GodTiltCover(
            bitmap = preview,
            loading = loading,
            reduce = reduce,
            modifier = Modifier.width(158.dp),
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GodChipButton(text = "裁剪", icon = {
                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
            }, onClick = onCrop)
            GodChipButton(text = "从相册选择", icon = {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
            }, onClick = onPickAlbum)
        }

        if (pages.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(pages) { index, ref ->
                    GodPageThumb(
                        ref = ref,
                        selected = !albumSelected && index == selected,
                        selectedBitmap = selectedBitmap,
                        remoteLoader = remoteLoader,
                        indexLabel = "${index + 1}",
                        onClick = { onPickPage(index) },
                    )
                }
            }
        }
    }
}

/** 按压拖动 → 轻微 3D 倾斜，松手弹簧回正。 */
@Composable
private fun GodTiltCover(
    bitmap: Bitmap?,
    loading: Boolean,
    reduce: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .aspectRatio(GodCoverEngine.COVER_RATIO)
            .then(
                if (reduce) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            scope.launch { press.animateTo(0.97f, GodMotion.springLight()) }
                        },
                        onDragEnd = {
                            scope.launch {
                                press.animateTo(1f, GodMotion.springMain())
                                rotX.animateTo(0f, GodMotion.springMain())
                                rotY.animateTo(0f, GodMotion.springMain())
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                press.animateTo(1f, GodMotion.springMain())
                                rotX.animateTo(0f, GodMotion.springMain())
                                rotY.animateTo(0f, GodMotion.springMain())
                            }
                        },
                    ) { _, drag ->
                        val maxTilt = 12f
                        scope.launch {
                            rotY.snapTo((drag.x / with(density) { 4.dp.toPx() }).coerceIn(-maxTilt, maxTilt))
                            rotX.snapTo((-drag.y / with(density) { 6.dp.toPx() }).coerceIn(-maxTilt, maxTilt))
                        }
                    }
                },
            )
            .graphicsLayer {
                rotationX = rotX.value
                rotationY = rotY.value
                cameraDistance = 16f * density.density * 100f
                scaleX = press.value
                scaleY = press.value
            }
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            // 金调细描边：神回封面是"被装裱的高光时刻"，一圈金边把它从面板上
            // 抬起来，同时与下方星标/标题的金色呼应（用低 alpha，不喧宾夺主）
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(GodGold.goldGradient(godIsDark())),
                shape = RoundedCornerShape(16.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 切换来源/裁剪结果时封面交叉淡入（不硬切）
        androidx.compose.animation.Crossfade(
            targetState = bitmap,
            animationSpec = GodMotion.normal(),
            label = "coverCrossfade",
        ) { bmp ->
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "神回封面预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = GodGold.LightMid,
                )
                else -> Text(
                    "封面不可用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 页面缩略图：超长条漫自动截取中段，避免缩略图被压成一条线。 */
private object GodThumbCache {
    private val bitmaps = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(key: String): Bitmap? = bitmaps.get(key)
    fun put(key: String, bitmap: Bitmap) { bitmaps.put(key, bitmap) }
}

@Composable
private fun GodPageThumb(
    ref: GodPageRef,
    selected: Boolean,
    selectedBitmap: Bitmap?,
    remoteLoader: ImageLoader?,
    indexLabel: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val cacheKey = remember(ref.source, ref.headers) { "${ref.source}|${ref.headers.hashCode()}" }
    var bmp by remember(cacheKey) { mutableStateOf(GodThumbCache.get(cacheKey)) }
    LaunchedEffect(cacheKey, selected) {
        if (selected || bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.Default) {
            val raw = GodCoverEngine.loadPage(context, ref, 320, remoteLoader) ?: return@withContext null
            val aspect = raw.height.toFloat() / raw.width.toFloat()
            if (aspect > 3f) {
                // 条漫超长图：按可视比例取一段（默认中段）
                val segH = (raw.width * 1.6f).roundToInt().coerceAtMost(raw.height)
                val top = ((raw.height - segH) / 2f).roundToInt().coerceIn(0, (raw.height - segH).coerceAtLeast(0))
                runCatching { Bitmap.createBitmap(raw, 0, top, raw.width, segH) }.getOrDefault(raw)
            } else raw
        }
        bmp?.let { GodThumbCache.put(cacheKey, it) }
    }
    val displayBitmap = if (selected) selectedBitmap ?: bmp else bmp
    val dark = godIsDark()
    val borderColor = if (selected) GodGold.LightMid else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 70.dp)
            .clip(RoundedCornerShape(8.dp))
            // 底色原为 surfaceVariant@55%：在浅色面板上几乎与背景同色，缩略图未就绪时
            // 整排只剩几个黑色数字徽章浮着（看着像渲染坏了）。改成可见的中性底 + 常态描边。
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (dark) 0.45f else 0.85f),
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (displayBitmap != null) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 缩略图未就绪：给一个页码文本占位，明确"这格是在的，只是还没解码"
            Text(
                text = indexLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (displayBitmap != null) Text(
            text = indexLabel,
            fontSize = 9.sp,
            color = Color.White,
            modifier = Modifier
                .padding(3.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

/* ══════════════ ③④⑤ 分区标题 / 输入 ══════════════ */

/** 分区小标题：走全 App 的 labelLarge 槽位（14sp / 600 / 负字距），不再手写字号。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
    )
}

@Composable
private fun GodFormSection(
    number: String,
    title: String,
    caption: String,
    content: @Composable () -> Unit,
) {
    val dark = godIsDark()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (dark) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.48f))
            .border(
                1.dp,
                GodGold.LightMid.copy(alpha = if (dark) 0.25f else 0.19f),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number,
                color = GodGold.LightMid,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.width(9.dp))
            SectionLabel(title)
        }
        Text(
            caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 30.dp, top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun GodTitleField(
    value: String,
    placeholder: String,
    custom: Boolean,
    onValueChange: (String) -> Unit,
    onResetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            textStyle = TextStyle(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = GodGold.LightMid,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
            trailingIcon = {
                if (custom) {
                    IconButton(onClick = onResetDefault) {
                        Icon(Icons.Filled.Refresh, contentDescription = "还原默认", modifier = Modifier.size(18.dp))
                    }
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${value.length}/$TITLE_MAX",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 随笔：便签/手账纸质感（轻微纸质底色 + 细横线）。 */
@Composable
private fun GodNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    dark: Boolean,
) {
    val paper = if (dark) Color(0xFF26282E) else Color(0xFFFBF6E9)
    val line = if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFD9CDB4)
    val lineHeight = 26.dp
    val lineHeightPx = with(LocalDensity.current) { lineHeight.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(paper)
            .drawBehind {
                var y = lineHeightPx
                while (y < size.height) {
                    drawLine(line, Offset(12f, y), Offset(size.width - 12f, y), 1f)
                    y += lineHeightPx
                }
            }
            .padding(4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("写下这一话为什么是神回……", fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().height(132.dp),
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
        Text(
            text = "${value.length}/$NOTE_MAX",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
        )
    }
}

/* ══════════════ ⑥ 吸底操作栏 ══════════════ */

@Composable
private fun GodActionBar(
    editing: Boolean,
    saving: Boolean,
    enabled: Boolean,
    reduce: Boolean,
    dark: Boolean,
    title: String,
    rating: Float,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("★ $rating", color = GodGold.LightMid, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(9.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = enabled) {
            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        GodShineButton(
            text = if (editing) "保存" else "添加到神回排行榜",
            loading = saving,
            enabled = enabled,
            reduce = reduce,
            dark = dark,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        )
    }
    }
}

/** 金色渐变主按钮 + 定时扫过的光泽。 */
@Composable
fun GodShineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
    reduce: Boolean = false,
    dark: Boolean = false,
) {
    val infinite = rememberInfiniteTransition(label = "shine")
    val sweep by infinite.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(GodMotion.SHINE_MS, easing = LinearEasing)),
        label = "sweep",
    )
    val gold = GodGold.goldGradient(dark)
    val shape = RoundedCornerShape(GodMotion.BUTTON_CORNER.dp)
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    androidx.compose.material3.Button(
        onClick = { if (!loading && enabled) onClick() },
        modifier = modifier
            .height(52.dp)
            .godPress(press, enabled = enabled && !loading),
        shape = shape,
        enabled = enabled,
        interactionSource = press,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(gold), shape)
                .then(
                    if (reduce) Modifier else Modifier.drawBehind {
                        val w = size.width
                        drawRect(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.38f),
                                    Color.Transparent,
                                ),
                                start = Offset(sweep * w, 0f),
                                end = Offset(sweep * w + w * 0.45f, size.height),
                            ),
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 顶部受光带：金色按钮在玻璃面板上需要一点"凸起"的材质暗示，
            // 否则大面积纯金像一块贴纸
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                                startY = 0f,
                                endY = size.height * 0.5f,
                            )
                        )
                    },
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = Color(0xFF3B2A08),
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B2A08),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 小胶囊按钮（裁剪 / 相册）。
 *
 * 走全 App 的亚克力小控件观感：半透明填色 + 1dp 描边 + 内侧顶部高光，
 * 不再是"一块实心灰面"，在玻璃面板上才有悬浮层次。
 */
@Composable
private fun GodChipButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dark = godIsDark()
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.30f else 0.62f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
        modifier = Modifier.godPress(press),
        interactionSource = press,
    ) {
        Box(
            modifier = Modifier.drawBehind {
                // 内侧顶部高光（与 acrylicPanel 顶棱同源，缩到胶囊尺度）
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.45f,
                    )
                )
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon()
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/* ══════════════ 保存成功动效 / 二次确认 ══════════════ */

@Composable
private fun GodBurstOverlay(preview: Bitmap?, reduce: Boolean) {
    val infinite = rememberInfiniteTransition(label = "burst")
    val p by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "burstP",
    )
    val gold = GodGold.goldGradient(godIsDark())
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.42f
            val r = size.width * (if (reduce) 0.18f else 0.18f + 0.55f * p)
            for (i in 0 until 18) {
                val a = i * (2 * Math.PI / 18)
                val d = r * (0.6f + 0.4f * ((i % 3) / 3f))
                val alpha = ((1f - p) * 0.9f).coerceIn(0f, 1f)
                drawCircle(
                    color = gold[i % gold.size].copy(alpha = alpha),
                    radius = 5f * (1f - p * 0.4f),
                    center = Offset(
                        cx + (cos(a) * d).toFloat(),
                        cy + (sin(a) * d).toFloat(),
                    ),
                )
            }
        }
        preview?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(GodCoverEngine.COVER_RATIO)
                    .graphicsLayer {
                        scaleX = 1f - p * 0.55f
                        scaleY = 1f - p * 0.55f
                        translationY = -p * 220f
                        alpha = (1f - p).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun GodConfirmCloseDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("放弃这次编辑？") },
        text = { Text("已经改过的内容不会保存。", fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text("放弃", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("继续编辑") } },
        shape = RoundedCornerShape(24.dp),
    )
}
