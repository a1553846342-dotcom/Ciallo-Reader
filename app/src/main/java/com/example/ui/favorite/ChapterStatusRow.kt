package com.example.ui.favorite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.favorite.ChapterReadState
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.example.ui.theme.MintPrimary

/** 章节的视觉三态（读状态与下载态是两个独立维度，互不冲突）。 */
enum class ChapterVisual { UNREAD, READING, READ }

fun ChapterReadState.toVisual(): ChapterVisual = when (this) {
    ChapterReadState.READ -> ChapterVisual.READ
    ChapterReadState.READING -> ChapterVisual.READING
    ChapterReadState.UNREAD -> ChapterVisual.UNREAD
}

data class ChapterRowState(
    val visual: ChapterVisual = ChapterVisual.UNREAD,
    /** 上次读到第几页（0 起），阅读中时显示「上次读到第 N 页」 */
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    /** 上次打开之后新增的章节 → 右侧「新」小红点 */
    val isNew: Boolean = false,
    /** 已下载到本地 */
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val external: Boolean = false,
) {
    val progressFloat: Float
        get() = if (pageCount > 0) (pageIndex + 1).toFloat() / pageCount.toFloat() else 0f
}

/**
 * 章节列表行：阅读状态 + 下载状态（漫画主页面与阅读器章节抽屉复用同一组件）。
 *
 * 已读置灰的可访问性约束：
 * - 用**中性灰**而不是低透明度，保证标题对比度 ≥ 4.5:1；
 * - 不只靠颜色区分：右侧对勾是第二线索。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterStatusRow(
    title: String,
    state: ChapterRowState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    onDownload: (() -> Unit)? = null,
    highlighted: Boolean = false,
    /**
     * 该话是否已标注书签（左滑/右滑卡片切换，位置即原封面位）。
     *
     * ⚠️ 这是书签的唯一数据源。曾几何时 `ChapterRowState` 里还有一个同名字段，
     * 调用方只填了那一个、本组件却只读这一个 → 书签永远显示为未标注
     * （"书签挂不上去"）。重复字段已删除，别再加回来。
     */
    bookmarked: Boolean = false,
    /** 滑动切读书签回调；null 时该行不启用手势 */
    onToggleBookmark: (() -> Unit)? = null,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.4f
    val readGray = if (isDark) Color(0xFFB3B3B3) else Color(0xFF6B6B6B)
    val titleColor = when (state.visual) {
        ChapterVisual.READ -> readGray
        ChapterVisual.READING -> MintPrimary
        ChapterVisual.UNREAD -> MaterialTheme.colorScheme.onSurface
    }
    val highlight by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(320),
        label = "chapter_highlight",
    )
    val grayed = state.visual == ChapterVisual.READ

    // 左滑/右滑切读书签：卡片跟手平移（阻尼限幅），越过阈值松手即切换，否则回弹。
    // 只消费水平位移，纵向滚动/长按/点击互不干扰。
    // 手势内写 dragActive/dragPreviewBookmark 会触发重组——pointerInput 的 key
    // 绝不能是会变的 lambda（否则手势协程在滑动中途被取消重启，书签永远提交不了），
    // 这里用 Unit + rememberUpdatedState 持有最新回调（与 PageTurnContainer 宿主同款）。
    val dragAnim = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val currentOnToggle by rememberUpdatedState(onToggleBookmark)
    // 拖动激活与方向预览：滑动时丝带实时跟手出现/消失，松手按最终状态提交
    var dragActive by remember { mutableStateOf(false) }
    var dragPreviewBookmark by remember { mutableStateOf(false) }
    val bookmarkScale by animateFloatAsState(
        targetValue = if (bookmarked) 1f else 0.82f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chapter_bookmark_scale",
    )
    val bookmarkAlpha by animateFloatAsState(
        targetValue = if (bookmarked) 1f else 0.4f,
        animationSpec = tween(240),
        label = "chapter_bookmark_alpha",
    )

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .graphicsLayer { translationX = dragAnim.value }
                .background(
                    if (highlight > 0f) MintPrimary.copy(alpha = 0.10f * highlight)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                )
                .border(
                    1.dp,
                    if (grayed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    RoundedCornerShape(14.dp),
                )
                .semantics {
                contentDescription = buildString {
                    append(title)
                    append("，")
                    append(
                        when (state.visual) {
                            ChapterVisual.READ -> "已读"
                            ChapterVisual.READING -> "阅读中"
                            ChapterVisual.UNREAD -> "未读"
                        }
                    )
                    if (state.downloaded) append("，已下载")
                    if (state.isNew) append("，新章节")
                    if (bookmarked) append("，已加书签")
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .pointerInput(Unit) {
                if (currentOnToggle == null) return@pointerInput
                // 方向仲裁：必须**横向明显占主导**才接管为书签滑动。
                // 旧判据是 abs(totalX) >= abs(totalY) * 0.3f —— 意思是"只要横向
                // 有纵向三成分量就算横滑"。下滑列表时手指必然带一点横向抖动，
                // 于是纵向滚动被劫持成卡片左右滑（用户报"向下滑很容易触发到卡片
                // 左右滑动"）。改成 |x| >= |y|：横向分量必须不小于纵向才算横滑。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    // 卡片位移的本地累计量：松手判定只读它。
                    // 旧写法读 dragAnim.value —— Animatable 由 snapTo 协程异步推进，
                    // 最后一个 move 事件的 snapTo 可能还没落定就被读取，阈值判定
                    // 时快时慢（同一段滑动有时提交、有时不提交）。
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
                            // 拖动即预览切换后的书签状态（松手提交）
                            dragPreviewBookmark = !bookmarked
                            dragX = totalX.coerceIn(-140f, 140f)
                            justActivated = true
                        }
                        if (isDrag) {
                            change.consume()
                            // 激活那一帧的 delta 已经算进 totalX，别再加一次
                            if (justActivated) justActivated = false
                            else dragX = (dragX + delta.x).coerceIn(-140f, 140f)
                            val target = dragX
                            dragScope.launch { dragAnim.snapTo(target) }
                        }
                    }
                    if (isDrag && abs(dragX) > 40f) currentOnToggle?.invoke()
                    dragActive = false
                    dragPreviewBookmark = false
                    dragScope.launch {
                        dragAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 书签让位槽：原封面位空白处固定留出丝带宽度，文字位置不随丝带出现/消失跳动。
        // 丝带本体挂在外层宿主的顶边（见下方 overlay）。
        Spacer(modifier = Modifier.width(40.dp))
        Spacer(modifier = Modifier.width(12.dp))

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (state.visual == ChapterVisual.READ) FontWeight.Normal else FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.isNew) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFF4D4F), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text("新", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            when {
                state.visual == ChapterVisual.READING && state.pageCount > 0 -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "上次读到第 ${state.pageIndex + 1} 页 · 共 ${state.pageCount} 页",
                        fontSize = 11.sp,
                        color = MintPrimary.copy(alpha = 0.85f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progressFloat.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                        color = MintPrimary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    )
                }
                state.visual == ChapterVisual.READ -> {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("已读", fontSize = 11.sp, color = readGray)
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MintPrimary,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            // 已下载图标在"已读置灰"状态下仍保持清晰可见（不用灰化图标）
            if (state.downloaded) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "已下载",
                    tint = MintPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else if (onDownload != null && !state.external) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MintPrimary.copy(alpha = 0.12f))
                        .combinedClickable(onClick = onDownload),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载本章",
                        tint = MintPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (state.visual == ChapterVisual.READ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已读",
                    tint = readGray,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    // 书签丝带 overlay：挂在卡片顶边垂下来（真正"夹在书页里"的姿态——
    // 旧实现缩在行中间像一枚贴纸），跟手拖动时与卡片同位移（同一 dragAnim），
    // 拖动实时预览、松手提交。
    val markShown = bookmarked || dragPreviewBookmark
    val markProgress by animateFloatAsState(
        targetValue = if (markShown) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chapter_bookmark_mark",
    )
    if (markProgress > 0.01f) {
        BookmarkRibbon(
            progress = markProgress,
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { translationX = dragAnim.value }
                .padding(start = 16.dp)
                .size(width = 26.dp, height = 46.dp),
            contentDescription = if (bookmarked) "已加书签" else null,
        )
    }
    } // Box（丝带宿主）闭合
}

/**
 * 章节书签丝带。
 *
 * 为什么不用 `Icons.Filled.Bookmark`：Material 的扁平书签图标是纯色实心块，
 * 放在已成型的卡片里像贴了个占位符，与全 App「有材质、有厚度」的控件语言不搭。
 * 这里手绘一条丝带：圆角顶 + 底部 V 形燕尾缺口 + 主色竖向渐变（顶部受光、
 * 尾部压暗）+ 左缘窄高光 + 接触阴影，读起来是"夹在书页里的一条真丝带"。
 *
 * ⚠️ 配色不能用 `primaryContainer` 打底：它太浅，叠加高光后整条丝带被洗成
 * 半透明白，在浅色卡片上几乎隐形（实测截图确认）。以 `primary` 为基色，
 * 亮部/暗部都从它向白/黑收敛，任何主题下都保得住色相。
 *
 * @param progress 出场进度（0~1）：从顶边垂落展开，与拖动预览同一条动画
 */
@Composable
internal fun BookmarkRibbon(
    progress: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = progress
                scaleY = progress
                alpha = progress
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = w * 0.20f
                val notch = h * 0.24f
                fun ribbonPath(dy: Float) = androidx.compose.ui.graphics.Path().apply {
                    moveTo(r, dy)
                    lineTo(w - r, dy)
                    quadraticTo(w, dy, w, r + dy)
                    lineTo(w, h + dy)
                    lineTo(w / 2f, h - notch + dy)
                    lineTo(0f, h + dy)
                    lineTo(0f, r + dy)
                    quadraticTo(0f, dy, r, dy)
                    close()
                }
                val path = ribbonPath(0f)
                val shadowPath = ribbonPath(1.5.dp.toPx())
                val body = Brush.verticalGradient(
                    listOf(
                        lerp(primary, Color.White, 0.16f),
                        primary,
                        lerp(primary, Color.Black, 0.26f),
                    ),
                    startY = 0f,
                    endY = h,
                )
                // 左缘窄高光：丝绸的受光面在左棱（与 acrylicPanel 顶棱同源的材质暗示），
                // 不再整面刷白
                val sheen = Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0.04f),
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = w * 0.45f,
                )
                onDrawBehind {
                    // 接触阴影：同形状下沉 1.5dp 的暗色副本（Canvas 无法直接投影）
                    drawPath(shadowPath, Color.Black.copy(alpha = 0.12f))
                    drawPath(path, body)
                    drawPath(path, sheen)
                }
            },
    )
}

/** 供外部复用的「阅读中」高亮底色（进入页面自动滚动到该行并闪一下）。 */
@Composable
fun rememberChapterHighlight(key: String?, targetKey: String?): Boolean {
    var shown by remember(key) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(key) {
        shown = key != null && key == targetKey
        if (shown) {
            kotlinx.coroutines.delay(900)
            shown = false
        }
    }
    return shown
}

/** 亮度工具（避免依赖主题私有常量）。 */
private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue
