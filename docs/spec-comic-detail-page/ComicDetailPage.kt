/**
 * 漫画详情页（漫画阅读软件）—— 单文件 Jetpack Compose 实现。
 *
 * 依赖：androidx.compose.material3、material-icons-extended（Sort/Checklist/Download）、
 *       coil-compose（神回封面；不传封面也能跑）。
 * 适配：深浅色走 MaterialTheme.colorScheme；主题色 accent 全部读取 [LocalDetailAccent]。
 *
 * 结构自上而下：顶栏 → 信息行 → 工具行 → 章节列表（神回卡在最上） → 底部固定栏。
 */
package com.example.comicdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Locale

/* ══════════════ 一、设计令牌 ══════════════ */

/** 主题色：由用户主题注入（默认橙 #D9622B）。所有"橙色"都读这里，不写死。 */
val LocalDetailAccent = staticCompositionLocalOf { Color(0xFFD9622B) }

/** 金色：仅神回卡片使用。 */
private val GodGold = Color(0xFFE8C56A)
private val GodGoldStroke = Color(0xFFE8C56A).copy(alpha = 0.55f)
/** 神回暗层基色 rgba(14,18,30,…) */
private val GodScrimBase = Color(0xFF0E121E)

/** 三级文字 + 面色（深浅色都从 colorScheme 取）。 */
private data class DetailColors(
    val page: Color,
    val card: Color,
    val hairline: Color,      // 0.5 细线
    val strongBorder: Color,  // 强调边框（1）
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

@Composable
private fun detailColors(): DetailColors {
    val s = MaterialTheme.colorScheme
    return DetailColors(
        page = s.background,
        card = s.surface,
        hairline = s.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.14f else 0.10f),
        strongBorder = s.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.26f else 0.20f),
        textPrimary = s.onBackground,
        textSecondary = s.onBackground.copy(alpha = if (isSystemInDarkTheme()) 0.62f else 0.55f),
        textMuted = s.onBackground.copy(alpha = if (isSystemInDarkTheme()) 0.45f else 0.42f),
    )
}

/* ══════════════ 数据模型（全部数据驱动） ══════════════ */

enum class ReadState { UNREAD, READING, READ }

data class ChapterItem(
    val id: String,
    val number: Int,          // 第 N 话
    val title: String,
    val state: ReadState,
    val lastPage: Int = 0,    // READING 时有效（1 起）
    val totalPages: Int = 0,
)

/** 神回：用户主动收藏的一话（收藏顺序即展示顺序）。 */
data class GodMoment(
    val chapterId: String,    // 对应章节 id
    val name: String?,        // 用户自定义名（可空；空 = 只显示「神回」）
    val coverUrl: String?,    // 用户自定义封面图
    val rating: Float,        // 该话评分
)

data class ComicDetailUiState(
    val title: String,
    val source: String,
    val chapters: List<ChapterItem>,
    val godMoments: List<GodMoment> = emptyList(),
)

/* ══════════════ 页面 ══════════════ */

@Composable
fun ComicDetailPage(
    state: ComicDetailUiState,
    modifier: Modifier = Modifier,
    estimatedMbPerChapter: Double = 2.0,
    onBack: () -> Unit = {},
    onReadChapter: (String) -> Unit = {},
    onDownloadChapter: (String) -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onGodRename: (String, String?) -> Unit = { _, _ -> },
    onGodUncollect: (String) -> Unit = {},
    onDownloadHint: () -> Unit = {},   // 多选底栏 N=0 时点了给提示
) {
    val c = detailColors()
    val accent = LocalDetailAccent.current

    var multi by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var godMenuFor by remember { mutableStateOf<GodMoment?>(null) }
    var renaming by remember { mutableStateOf<GodMoment?>(null) }

    // 全量可勾选池：普通章节 + 神回对应章节（按 id 去重）
    val allIds = remember(state) {
        (state.chapters.map { it.id } + state.godMoments.map { it.chapterId }).distinct()
    }
    val godByChapter = remember(state) { state.godMoments.associateBy { it.chapterId } }

    fun exitMulti() { multi = false; selected = emptySet() }

    Column(modifier = modifier.fillMaxSize().background(c.page)) {
        if (!multi) {
            // ── 顶栏：返回 + 标题（单行省略，17/500） ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = c.textPrimary, modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = state.title,
                    fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // ── 信息行（13，secondary；「已读 N 话」accent） ──
            val readCount = state.chapters.count { it.state == ReadState.READ }
            Text(
                text = buildAnnotatedString {
                    append("${state.source} · 共 ${state.chapters.size} 话 · ")
                    withStyle(SpanStyle(color = accent)) { append("已读 $readCount 话") }
                },
                fontSize = 13.sp, fontWeight = FontWeight.Normal,
                color = c.textSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp),
            )

            // ── 工具行（高 32） ──
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { /* 预留：切换正/倒序 */ }
                        .padding(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort, contentDescription = null,
                        tint = c.textSecondary, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("正序", fontSize = 13.sp, color = c.textSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .clickable {
                            state.chapters.lastOrNull()?.let { onReadChapter(it.id) }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("跳到最新章", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { multi = true }
                        .padding(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Checklist, contentDescription = null,
                        tint = c.textPrimary, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("多选", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
                }
            }
        } else {
            // ── 多选顶栏：✕ | 已选 N 话 | 全选 ──
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::exitMulti, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "退出多选",
                        tint = c.textPrimary, modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = "已选 ${selected.size} 话",
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selected = allIds.toSet() }) {
                    Text("全选", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = accent)
                }
            }
            // ── 快捷胶囊（高 30，圆角 16，0.5 边框） ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickPill("选未读", c) {
                    selected = state.chapters.filter { it.state == ReadState.UNREAD }.map { it.id }.toSet()
                }
                QuickPill("选后 10 话", c) {
                    selected = state.chapters.takeLast(10).map { it.id }.toSet()
                }
                QuickPill("反选", c) {
                    selected = allIds.filter { it !in selected }.toSet()
                }
            }
        }

        // ── 章节列表（神回在最上，卡片间距 8） ──
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.godMoments.forEach { g ->
                item(key = "god_${g.chapterId}") {
                    val chapter = state.chapters.firstOrNull { it.id == g.chapterId }
                    GodCard(
                        god = g,
                        chapterTitle = chapter?.title ?: "第 ${chapter?.number ?: "-"} 话",
                        selecting = multi,
                        checked = g.chapterId in selected,
                        onOpen = { onReadChapter(g.chapterId) },
                        onDownload = { onDownloadChapter(g.chapterId) },
                        onToggleCheck = {
                            selected = if (g.chapterId in selected) selected - g.chapterId
                            else selected + g.chapterId
                        },
                        onLongPress = { if (!multi) godMenuFor = g },
                    )
                }
            }
            items(state.chapters, key = { it.id }) { ch ->
                val god = godByChapter[ch.id]   // 神回话本体不再重复出卡（神回卡已代表它）
                if (god == null) {
                    ChapterCard(
                        chapter = ch,
                        selecting = multi,
                        checked = ch.id in selected,
                        onClick = {
                            if (multi) {
                                selected = if (ch.id in selected) selected - ch.id
                                else selected + ch.id
                            } else onReadChapter(ch.id)
                        },
                        onDownload = { onDownloadChapter(ch.id) },
                        onLongPress = {
                            if (!multi) { multi = true; selected = setOf(ch.id) }
                        },
                    )
                }
            }
        }

        // ── 底部固定栏 ──
        if (!multi) {
            val cur = state.chapters.lastOrNull { it.state == ReadState.READING }
                ?: state.chapters.firstOrNull { it.state != ReadState.READ }
            Row(
                modifier = Modifier.fillMaxWidth().background(c.page)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp, 48.dp)
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Favorite, contentDescription = "收藏",
                        tint = accent, modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { cur?.let { onReadChapter(it.id) } },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(
                        text = buildAnnotatedString {
                            append("继续阅读 ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.85f))) {
                                if (cur != null) {
                                    append("第 ${cur.number} 话 · P${cur.lastPage.coerceAtLeast(1)}")
                                }
                            }
                        },
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            val n = selected.size
            Button(
                onClick = { if (n == 0) onDownloadHint() else { /* 执行批量下载 */ } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = buildAnnotatedString {
                        if (n == 0) {
                            append("选择要下载的章节")
                        } else {
                            append("下载 $n 话")
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.85f))) {
                                append("  约 ${String.format(Locale.US, "%.1f", n * estimatedMbPerChapter)} MB")
                            }
                        }
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ── 神回长按菜单：重命名 / 取消收藏 ──
    godMenuFor?.let { g ->
        DropdownMenu(expanded = true, onDismissRequest = { godMenuFor = null }) {
            DropdownMenuItem(
                text = { Text("重命名", fontSize = 15.sp) },
                onClick = { godMenuFor = null; renaming = g },
            )
            DropdownMenuItem(
                text = { Text("取消收藏", fontSize = 15.sp) },
                onClick = { godMenuFor = null; onGodUncollect(g.chapterId) },
            )
        }
    }

    // ── 重命名弹窗：预填当前名，可清空（清空 = 只显示「神回」） ──
    renaming?.let { g ->
        var text by remember(g.chapterId) { mutableStateOf(g.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名神回", fontSize = 17.sp, fontWeight = FontWeight.Medium) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("神回", fontSize = 15.sp) },
                    textStyle = TextStyle(fontSize = 15.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onGodRename(g.chapterId, text.trim().ifEmpty { null })
                    renaming = null
                }) { Text("确定", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }
}

/* ══════════════ 普通章节卡片 ══════════════ */

@Composable
private fun ChapterCard(
    chapter: ChapterItem,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = detailColors()
    val accent = LocalDetailAccent.current
    val shape = RoundedCornerShape(14.dp)

    val isCurrent = chapter.state == ReadState.READING
    val isSelected = selecting && checked
    // 多选模式下「当前阅读」不加 accent 描边/底色，避免与「被选中」混淆
    val emphasized = isCurrent && !selecting

    val bg = if (emphasized || isSelected) {
        Modifier.background(c.card).then(Modifier.background(accent.copy(alpha = 0.08f)))
    } else {
        Modifier.background(c.card)
    }
    val borderColor = when {
        emphasized || isSelected -> accent
        else -> c.hairline
    }
    val borderWidth = if (emphasized || isSelected) 1.dp else 0.5.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(bg)
            .border(borderWidth, SolidColor(borderColor), shape)
            .then(
                if (selecting) Modifier.clickable(onClick = onClick)
                else Modifier.longPressClick(onClick = onClick, onLongPress = onLongPress)
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左列：书签槽（宽 14，每张卡都保留，保证标题左对齐）
            Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(10.dp))

            // 中列
            Column(modifier = Modifier.weight(1f)) {
                val caption = when (chapter.state) {
                    ReadState.READ -> "第 ${chapter.number} 话 · 已读"
                    ReadState.READING ->
                        if (chapter.totalPages > 0) {
                            "第 ${chapter.number} 话 · 读到 ${chapter.lastPage} / ${chapter.totalPages} 页"
                        } else {
                            "第 ${chapter.number} 话"
                        }
                    ReadState.UNREAD -> "第 ${chapter.number} 话"
                }
                Text(
                    text = caption,
                    fontSize = 12.sp, fontWeight = FontWeight.Normal,
                    color = if (isCurrent && !selecting) accent else c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chapter.title,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = when {
                        chapter.state == ReadState.READ -> c.textSecondary
                        else -> c.textPrimary
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))

            // 右列：下载图标（多选时换成勾选框），40×40 触摸区
            Box(
                modifier = Modifier.size(40.dp)
                    .then(
                        if (selecting) Modifier.clickable(onClick = onClick)
                        else Modifier.clickable(onClick = onDownload)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selecting) {
                    CheckCircle(checked = checked, accent = accent, uncheckedStroke = c.strongBorder)
                } else {
                    Icon(
                        Icons.Filled.Download, contentDescription = "下载",
                        tint = c.textMuted, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 书签：紧贴卡片上边缘向下挂出（顶部外扩 12 抵消内边距）
        // 当前阅读 = 14×30 实心（多选模式保留书签，只是不加 accent 描边）
        val markHeight = if (isCurrent) 30.dp else 22.dp
        val markColor = when {
            isCurrent -> accent                                  // 实心
            chapter.state == ReadState.READ -> accent.copy(alpha = 0.40f)
            else -> Color.Transparent                            // 未读：空槽
        }
        if (chapter.state != ReadState.UNREAD) {
            BookmarkMark(
                color = markColor,
                height = markHeight,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        // 当前阅读：底边 3dp 进度线（被圆角裁剪，不加行高）
        if (isCurrent && chapter.totalPages > 0) {
            val fraction = (chapter.lastPage.toFloat() / chapter.totalPages).coerceIn(0f, 1f)
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                    .background(accent.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(fraction).height(3.dp)
                        .background(accent),
                )
            }
        }
    }
}

/* ══════════════ 神回卡片（同尺寸同结构，只换视觉） ══════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GodCard(
    god: GodMoment,
    chapterTitle: String,
    selecting: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleCheck: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = detailColors()
    val accent = LocalDetailAccent.current
    val shape = RoundedCornerShape(14.dp)
    val isSelected = selecting && checked

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(0.5.dp, SolidColor(GodGoldStroke), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        // 背景：自定义封面填满 + 左深右浅暗层
        AsyncImage(
            model = god.coverUrl,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    listOf(
                        GodScrimBase.copy(alpha = 0.9f),
                        GodScrimBase.copy(alpha = 0.6f),
                        GodScrimBase.copy(alpha = 0.2f),
                    ),
                    startX = 0f,
                    endX = Float.POSITIVE_INFINITY,
                ),
            ),
        )

        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (god.name.isNullOrBlank()) "神回" else {
                        val n = god.name
                        "神回 · $n"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Normal,
                    color = GodGold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chapterTitle,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))

            // 右列：多选=勾选框；普通=金星 14 + 评分（白 90%）+ 下载（白 70%）
            Box(
                modifier = Modifier.size(40.dp)
                    .then(if (selecting) Modifier.clickable(onClick = onToggleCheck) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (selecting) {
                    CheckCircle(
                        checked = checked,
                        accent = accent,
                        uncheckedStroke = Color.White.copy(alpha = 0.70f),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star, contentDescription = null,
                            tint = GodGold, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f", god.rating),
                            fontSize = 13.sp, fontWeight = FontWeight.Normal,
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
                        Icons.Filled.Download, contentDescription = "下载",
                        tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 书签：白色 40%（14×22）
        BookmarkMark(
            color = Color.White.copy(alpha = 0.40f),
            height = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/* ══════════════ 小组件 ══════════════ */

/** 书签（14 宽，从卡片上边缘向下挂出；顶部外扩 12 抵消内边距）。 */
@Composable
private fun BookmarkMark(color: Color, height: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(width = 14.dp, height = height)) {
        drawBookmark(color)
    }
}

private fun DrawScope.drawBookmark(color: Color) {
    val w = size.width
    val h = size.height
    val notch = w * 0.45f
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(w, 0f)
        lineTo(w, h)
        lineTo(w / 2f, h - notch)
        lineTo(0f, h)
        close()
    }
    drawPath(path, color)
}

/** 多选圆形勾选框：未选中 = 1.5 空心圆；选中 = accent 实心 + 白对勾。 */
@Composable
private fun CheckCircle(checked: Boolean, accent: Color, uncheckedStroke: Color) {
    val stroke = 1.5.dp
    Box(
        modifier = Modifier
            .size(22.dp)
            .semantics { contentDescription = if (checked) "已选中" else "未选中" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(stroke, uncheckedStroke, CircleShape),
            )
        }
    }
}

/** 多选快捷胶囊（高 30，圆角 16，0.5 边框）。 */
@Composable
private fun QuickPill(text: String, c: DetailColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, SolidColor(c.hairline), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = c.textPrimary)
    }
}

/**
 * 点击 + 450ms 长按（规格要求的 450ms；combinedClickable 的系统长按不可调）。
 * 移动超过 touch slop 视为滚动，不拦截。
 */
private enum class PressResult { RELEASED, MOVED, TIMED_OUT }

private fun Modifier.longPressClick(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 450ms 内抬起 → 点击；等到时 → 长按；先滑动超 slop → 放手给列表滚动
        val result = withTimeoutOrNull(450L) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) {
                    change.consume()
                    return@withTimeoutOrNull PressResult.RELEASED
                }
                if ((change.position - down.position).getDistance() > slop) {
                    return@withTimeoutOrNull PressResult.MOVED
                }
            }
            PressResult.MOVED
        } ?: PressResult.TIMED_OUT
        when (result) {
            PressResult.RELEASED -> onClick()
            PressResult.TIMED_OUT -> {
                onLongPress()
                // 长按成立后消费完余下事件，避免滚动手势跟着触发
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break
                    change.consume()
                    if (!change.pressed) break
                }
            }
            PressResult.MOVED -> Unit
        }
    }
}

/* ══════════════ 主题 & 示例（可运行） ══════════════ */

/** 深浅色壳：页面色/面色/文字色都走 colorScheme，accent 由调用方给。 */
@Composable
fun ComicDetailTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
@Composable
fun ComicDetailPageSample() {
    val state = remember {
        ComicDetailUiState(
            title = "无职转生：洛琪希尔也要拿出真本事",
            source = "mangadex",
            chapters = (1..29).map { n ->
                ChapterItem(
                    id = "c$n",
                    number = n,
                    title = if (n == 7) "Departure（出发）" else "第 $n 话标题占位，可能很长需要省略号处理一下标题",
                    state = when {
                        n < 7 -> ReadState.READ
                        n == 7 -> ReadState.READING
                        else -> ReadState.UNREAD
                    },
                    lastPage = if (n == 7) 12 else 0,
                    totalPages = if (n == 7) 34 else 0,
                )
            },
            godMoments = listOf(
                GodMoment(chapterId = "c7", name = "520", coverUrl = null, rating = 5.0f),
                GodMoment(chapterId = "c5", name = null, coverUrl = null, rating = 4.5f),
            ),
        )
    }
    ComicDetailPage(state = state)
}
