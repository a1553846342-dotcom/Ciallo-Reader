package com.example.god

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.feedback.HapticsGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* ══════════════ 交互状态（三种风格共用） ══════════════ */

/** 排行榜交互状态：长按菜单 / 删除确认 / 随笔原文。三种风格共用同一份实现。 */
class GodRankingInteractions {
    var menuFor: GodMomentEntity? by mutableStateOf(null)
    var deleteFor: GodMomentEntity? by mutableStateOf(null)

    /** 非空的随笔 → 弹纯展示弹窗看原文（各风格里显示的随笔小字都可点） */
    var noteFor: GodMomentEntity? by mutableStateOf(null)
}

/**
 * 神回排行榜主体：按 [style] 渲染，三种风格共享同一份数据与同一套交互。
 *
 * @param compact true = 统计页卡片内的前 10 项预览；false = 全屏完整版
 */
@Composable
fun GodRankingBody(
    items: List<GodMomentItem>,
    style: GodRankingStyle,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    gyroEnabled: Boolean = true,
    reduceMotion: Boolean = false,
    interactions: GodRankingInteractions = remember { GodRankingInteractions() },
    onItemClick: (GodMomentEntity) -> Unit,
    onEdit: (GodMomentEntity) -> Unit,
    onDelete: (GodMomentEntity) -> Unit,
) {
    val tilt = rememberGodTilt(enabled = gyroEnabled && !reduceMotion)

    Box(modifier = modifier) {
        when (style) {
            GodRankingStyle.PODIUM -> GodPodiumStyle(
                items = items, compact = compact, tilt = tilt,
                reduceMotion = reduceMotion, interactions = interactions,
                onItemClick = onItemClick,
            )
            GodRankingStyle.VINYL_SHELF -> GodVinylStyle(
                items = items, compact = compact, tilt = tilt,
                reduceMotion = reduceMotion, interactions = interactions,
                onItemClick = onItemClick,
            )
            GodRankingStyle.POLAROID_WALL -> GodPolaroidStyle(
                items = items, compact = compact, tilt = tilt,
                reduceMotion = reduceMotion, interactions = interactions,
                onItemClick = onItemClick,
            )
        }

        // 长按浮层菜单（编辑 / 删除）
        // ⚠️ 不加黑色遮罩：全屏排行榜里 GodRankingBody 在 LazyColumn item 内，
        // fillMaxSize 只是 item 的 bounds —— 遮罩会变成一块灰色矩形（用户实测难看）。
        // 保留透明点击层用于点外部关闭菜单。
        interactions.menuFor?.let { target ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { interactions.menuFor = null },
                contentAlignment = Alignment.Center,
            ) {
                GodItemMenu(
                    onEdit = { interactions.menuFor = null; onEdit(target) },
                    onDelete = { interactions.menuFor = null; interactions.deleteFor = target },
                    onDismiss = { interactions.menuFor = null },
                )
            }
        }

        // 删除确认
        interactions.deleteFor?.let { target ->
            AlertDialog(
                onDismissRequest = { interactions.deleteFor = null },
                title = { Text("确定移除这个神回吗？") },
                text = { Text("随笔和评分将一并删除", fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        interactions.deleteFor = null
                        onDelete(target)
                    }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { interactions.deleteFor = null }) { Text("取消") }
                },
                shape = RoundedCornerShape(24.dp),
            )
        }

        // 随笔原文：排行榜各处显示的随笔小字点开即看全文。
        // 按用户要求做成"什么提示都没有"的纯展示窗口 —— 没有任何按钮，
        // 点窗外或返回键关闭。长文可滚动。
        interactions.noteFor?.let { target ->
            AlertDialog(
                onDismissRequest = { interactions.noteFor = null },
                title = {
                    Text(
                        text = target.effectiveTitle(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = target.note,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        )
                    }
                },
                confirmButton = {},
                shape = RoundedCornerShape(24.dp),
            )
        }
    }
}

/** 条目通用手势：点击跳转 + 长按弹出菜单 + 按压 3D 倾斜。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GodItemCard(
    moment: GodMomentEntity,
    reduceMotion: Boolean,
    interactions: GodRankingInteractions,
    onItemClick: (GodMomentEntity) -> Unit,
    modifier: Modifier = Modifier,
    /** 点击行为的覆盖（唱片架：点旁边的 = 滚到该项，而非跳转） */
    onTap: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }

    val gesture = if (reduceMotion) Modifier else Modifier.pointerInput(moment.id) {
        detectDragGestures(
            onDragStart = { scope.launch { press.animateTo(0.97f, GodMotion.springLight<Float>()) } },
            onDragEnd = {
                scope.launch {
                    press.animateTo(1f, GodMotion.springMain<Float>())
                    rotX.animateTo(0f, GodMotion.springMain<Float>())
                    rotY.animateTo(0f, GodMotion.springMain<Float>())
                }
            },
            onDragCancel = {
                scope.launch {
                    press.animateTo(1f, GodMotion.springMain<Float>())
                    rotX.animateTo(0f, GodMotion.springMain<Float>())
                    rotY.animateTo(0f, GodMotion.springMain<Float>())
                }
            },
        ) { _, drag ->
            val maxTilt = 10f
            scope.launch {
                rotY.snapTo((drag.x / with(density) { 4.dp.toPx() }).coerceIn(-maxTilt, maxTilt))
                rotX.snapTo((-drag.y / with(density) { 6.dp.toPx() }).coerceIn(-maxTilt, maxTilt))
            }
        }
    }

    Box(
        modifier = modifier
            .then(gesture)
            .graphicsLayer {
                rotationX = rotX.value
                rotationY = rotY.value
                cameraDistance = 16f * density.density * 100f
                scaleX = press.value
                scaleY = press.value
            }
            .combinedClickable(
                onClick = { (onTap ?: { onItemClick(moment) }).invoke() },
                onLongClick = {
                    if (HapticsGate.enabled) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    interactions.menuFor = moment
                },
                onClickLabel = "打开书籍详情",
                onLongClickLabel = "编辑或删除神回",
            ),
    ) {
        content(Modifier.fillMaxSize())
    }
}

