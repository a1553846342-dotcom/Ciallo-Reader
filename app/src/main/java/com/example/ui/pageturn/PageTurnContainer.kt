package com.example.ui.pageturn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import me.trishiraj.shadowglow.consistentShadow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 卷页背面镜像内容的可见度。
 *
 * 旧值 0.42f 叠在 100% 不透明纸基上 → 文字几乎看不见，用户反馈"跟没渲染一样"。
 * 现在纸基本身已经半透，这一层再拉到 0.88f：背面看起来是真印着字
 * （纸浆把墨迹透过来那种质感），而不是一块空纸板上飘着鬼影。
 * 纸张色调与柱面明暗仍叠在它**上面**，所以纸面的温润质感和柱面立体感不受影响。
 */
private const val BACKSIDE_CONTENT_ALPHA = 0.88f

enum class PageTurnType(val id: Int, val title: String, val description: String) {
    SIMULATE(0, "仿真3D卷页", "真实书本折角弯曲与纸张阴影"),
    COVER(1, "覆盖翻页", "无缝推开上页，经典质感"),
    SLIDE(2, "平移翻页", "左右双页平滑滑移"),
    FADE(3, "渐变淡出", "优雅透明度切换"),
    SCROLL(4, "上下滚动", "连续纵向滚动阅读")
}

@Composable
fun PageTurnContainer(
    pageTurnMode: Int,
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onClickCenter: () -> Unit,
    onClickLeft: () -> Unit,
    onClickRight: () -> Unit,
    isBookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    // 点击位置命中正文插图时回调（返回 true=已打开全屏，宿主跳过翻页分派）
    onImageTapAt: ((androidx.compose.ui.geometry.Offset) -> Boolean)? = null,
    pageKey: Any = Unit,
    /** 中间区域长按 1s：触发串珠快速翻页（PageScrubberOverlay）。 */
    onLongPressCenter: (() -> Unit)? = null,
    /** 菜单（顶/底栏）打开时不再响应点击翻页/切章，避免“想关菜单却切了章”。 */
    menuVisible: Boolean = false,
    /**
     * 卷页纸张的底色：应传入阅读区当页背景色（ReaderScreen 的 `bgColor`）。
     *
     * 此前函数体内写死 `0xFFF5F0E6`（米色铜版纸），夜间 / OLED 黑色主题下
     * 翻出来的纸背面还是米黄色，夜里一眼假。现在由它经 [rememberPaperPalette]
     * 推导出整套纸面 / 厚度高光 / 描边色。
     */
    paperColor: Color = Color(0xFFF5F0E6),
    modifier: Modifier = Modifier
) {
    val paper = rememberPaperPalette(paperColor)
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Stable animatables across page turns - never recreate/destroy on pageKey change
    val dragOffset = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }
    val pullDownOffset = remember { Animatable(0f) }
    var touchDownY by remember { mutableFloatStateOf(0f) }

    // Always keep latest references to callbacks for pointerInput gesture loop
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnPrevPage by rememberUpdatedState(onPrevPage)
    val latestOnClickCenter by rememberUpdatedState(onClickCenter)
    val latestOnClickLeft by rememberUpdatedState(onClickLeft)
    val latestOnClickRight by rememberUpdatedState(onClickRight)
    val latestOnToggleBookmark by rememberUpdatedState(onToggleBookmark)
    val latestOnLongPressCenter by rememberUpdatedState(onLongPressCenter)
    val latestMenuVisible by rememberUpdatedState(menuVisible)
    // 插图命中（按屏幕位置）：返回 true 表示已打开全屏，宿主跳过翻页分派
    val latestOnImageTapAt by rememberUpdatedState(onImageTapAt)
    var containerOffsetInWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Reset offsets when pageKey changes (page turned)
    LaunchedEffect(pageKey) {
        dragOffset.snapTo(0f)
        dragOffsetY.snapTo(0f)
        pullDownOffset.snapTo(0f)
    }

    val mode = PageTurnType.entries.firstOrNull { it.id == pageTurnMode } ?: PageTurnType.SIMULATE

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { containerOffsetInWindow = it.positionInWindow() }
            .pointerInput(pageTurnMode) {
                // 滚动阅读模式：手势完全交给阅读器自身的滚动容器处理，
                // 这里不再拦截点击/拖拽，避免“下滑误触菜单/误切章节”。
                if (mode == PageTurnType.SCROLL) {
                    return@pointerInput
                }
                // 在飞的翻页动画协程（点按/拖拽共用）：快速连点时旧协程被取消、
                // 新协程直接即时翻页——两个协程并发驱动同一 dragOffset 会互相
                // 抢占 Animatable，落败协程死在半路且永远不执行 snapTo(0)，
                // 页面停在非零偏移（屏幕显示滞后一页、点左无响应只能点右）。
                var turnJob: kotlinx.coroutines.Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchDownY = down.position.y
                    var totalX = 0f
                    var totalY = 0f
                    var isDrag = false
                    var activeMode = 0 // 0: uncommitted, 1: horizontal page turn, 2: pull-down bookmark
                    var longPressFired = false
                    var tapConsumedByChild = false

                    val touchSlop = viewConfiguration.touchSlop
                    val screenWidth = size.width.toFloat()
                    // 长按触发区扩展到整个页面：任意位置静置 1s 均可唤出串珠快速翻页
                    var longPressArmed = true
                    var lastUptime = down.uptimeMillis
                    val longPressDeadline = down.uptimeMillis + 1000L

                    while (true) {
                        val event = if (longPressArmed) {
                            withTimeoutOrNull((longPressDeadline - lastUptime).coerceAtLeast(1L)) {
                                awaitPointerEvent()
                            }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // 中间区域静置 1s：触发串珠快速翻页
                            longPressArmed = false
                            if (!isDrag && !latestMenuVisible) {
                                longPressFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnLongPressCenter?.invoke()
                                // 吞掉余下事件直到抬起：防止遮罩出现前底层误翻页
                                while (true) {
                                    val drain = awaitPointerEvent()
                                    drain.changes.forEach { it.consume() }
                                    if (drain.changes.any { !it.pressed }) break
                                }
                                break
                            }
                            continue
                        }

                        lastUptime = maxOf(lastUptime, event.changes.maxOf { it.uptimeMillis })
                        val currentChange = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!currentChange.pressed) {
                            // up 已被子层（正文内嵌图片的 clickable 等）消费：
                            // 该次触摸属于内容交互（如打开图片全屏），不再分派翻页/菜单
                            tapConsumedByChild = currentChange.isConsumed
                            break
                        }

                        val dragAmount = currentChange.positionChange()
                        totalX += dragAmount.x
                        totalY += dragAmount.y

                        if (!isDrag) {
                            if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                                isDrag = true
                                if (totalY > touchSlop && totalY > abs(totalX) * 1.1f) {
                                    activeMode = 2 // Pull-Down Bookmark
                                } else if (abs(totalX) > touchSlop) {
                                    if (mode != PageTurnType.SCROLL) {
                                        activeMode = 1 // Horizontal Page Turn
                                    }
                                }
                            }
                        }

                        if (isDrag) {
                            currentChange.consume()
                            if (activeMode == 1) {
                                coroutineScope.launch {
                                    dragOffset.snapTo(dragOffset.value + dragAmount.x)
                                    dragOffsetY.snapTo(dragOffsetY.value + dragAmount.y)
                                }
                            } else if (activeMode == 2) {
                                val rawY = totalY.coerceAtLeast(0f)
                                val dampedPx = (rawY * 0.5f).coerceIn(0f, 160f)
                                coroutineScope.launch {
                                    pullDownOffset.snapTo(dampedPx)
                                }
                            }
                        }
                    }

                    if (isDrag) {
                        if (activeMode == 2) {
                            val currentPull = pullDownOffset.value
                            if (currentPull >= 80f || totalY >= 160f) {
                                latestOnToggleBookmark?.invoke()
                            }
                            coroutineScope.launch {
                                pullDownOffset.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
                                )
                            }
                        } else if (activeMode == 1 && mode != PageTurnType.SCROLL) {
                            val currentVal = dragOffset.value
                            val threshold = screenWidth * 0.15f

                            coroutineScope.launch {
                                dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }

                            if (abs(currentVal) > threshold) {
                                turnJob = coroutineScope.launch {
                                    try {
                                        val target = if (currentVal < 0) -screenWidth else screenWidth
                                        dragOffset.animateTo(target, tween(180))
                                        if (currentVal < 0) {
                                            latestOnNextPage()
                                        } else {
                                            latestOnPrevPage()
                                        }
                                    } finally {
                                        withContext(NonCancellable) {
                                            dragOffset.snapTo(0f)
                                            dragOffsetY.snapTo(0f)
                                        }
                                    }
                                }
                            } else {
                                coroutineScope.launch {
                                    dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        }
                    } else if (!latestMenuVisible && !longPressFired) {
                        // 插图命中优先（按屏幕矩形注册表，不依赖事件消费——
                        // 与事件 pass 顺序无关，翻页引擎无关）：命中即打开全屏
                        val imageHandled = latestOnImageTapAt
                            ?.invoke(down.position + containerOffsetInWindow) == true
                        if (imageHandled || tapConsumedByChild) {
                            // 命中插图 / 子层已消费：不分派翻页
                        } else {
                        val tapX = down.position.x
                        val leftZone = screenWidth * 0.35f
                        val rightZone = screenWidth * 0.65f

                        when {
                            tapX < leftZone -> {
                                if (mode == PageTurnType.SCROLL) {
                                    latestOnClickLeft()
                                } else if (turnJob?.isActive == true) {
                                    // 上一次翻页动画未结束的快速连点：取消旧动画、即时翻页
                                    turnJob?.cancel()
                                    latestOnPrevPage()
                                } else {
                                    turnJob = coroutineScope.launch {
                                        try {
                                            dragOffset.animateTo(-screenWidth * 0.05f, tween(60))
                                            dragOffset.animateTo(screenWidth, tween(180))
                                            latestOnPrevPage()
                                        } finally {
                                            withContext(NonCancellable) {
                                                dragOffset.snapTo(0f)
                                                dragOffsetY.snapTo(0f)
                                            }
                                        }
                                    }
                                }
                            }
                            tapX > rightZone -> {
                                if (mode == PageTurnType.SCROLL) {
                                    latestOnClickRight()
                                } else if (turnJob?.isActive == true) {
                                    turnJob?.cancel()
                                    latestOnNextPage()
                                } else {
                                    turnJob = coroutineScope.launch {
                                        try {
                                            dragOffset.animateTo(screenWidth * 0.05f, tween(60))
                                            dragOffset.animateTo(-screenWidth, tween(180))
                                            latestOnNextPage()
                                        } finally {
                                            withContext(NonCancellable) {
                                                dragOffset.snapTo(0f)
                                                dragOffsetY.snapTo(0f)
                                            }
                                        }
                                    }
                                }
                            }
                            else -> latestOnClickCenter()
                        }
                        }
                    }
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val currentDrag = dragOffset.value
        val currentPull = pullDownOffset.value
        val pullProgress = (currentPull / 80f).coerceIn(0f, 1f)

        // Pull-down Indicator Charge Bar Header Overlay
        if (currentPull > 3f) {
            val isCharged = pullProgress >= 1f
            val iconScale by animateFloatAsState(
                targetValue = if (isCharged) 1.25f else 1.0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioHighBouncy),
                label = "iconScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((currentPull * 0.9f).dp.coerceAtMost(100.dp))
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    tonalElevation = 8.dp,
                    shadowElevation = 0.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isCharged) 2.dp else 1.dp,
                        color = if (isCharged) MintGold else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .consistentShadow(6.dp, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(32.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { pullProgress },
                                modifier = Modifier.fillMaxSize(),
                                color = if (isCharged) MintGold else MintPrimary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeWidth = 3.dp
                            )
                            Icon(
                                imageVector = if (isBookmarked) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdded,
                                contentDescription = null,
                                tint = if (isCharged) MintGold else MintPrimary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .scale(iconScale)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = when {
                                isCharged && isBookmarked -> "松开即可取消书签"
                                isCharged && !isBookmarked -> "松开即可保存书签"
                                isBookmarked -> "下拉取消书签"
                                else -> "下拉添加书签"
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isCharged) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCharged) MintGold else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Reader Content with Downward Damping Translation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = currentPull
                }
        ) {
            when (mode) {
                PageTurnType.SIMULATE -> {
                    Simulate3DCurlLayout(
                        dragPx = currentDrag,
                        dragPy = dragOffsetY.value,
                        touchDownY = touchDownY,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        paper = paper,
                        currentContent = currentContent,
                        nextContent = nextContent,
                        prevContent = prevContent
                    )
                }
                PageTurnType.COVER -> {
                    CoverPageTurnLayout(
                        dragPx = currentDrag,
                        widthPx = widthPx,
                        currentContent = currentContent,
                        nextContent = nextContent,
                        prevContent = prevContent
                    )
                }
                PageTurnType.SLIDE -> {
                    SlidePageTurnLayout(
                        dragPx = currentDrag,
                        widthPx = widthPx,
                        currentContent = currentContent,
                        nextContent = nextContent,
                        prevContent = prevContent
                    )
                }
                PageTurnType.FADE -> {
                    FadePageTurnLayout(
                        dragPx = currentDrag,
                        widthPx = widthPx,
                        currentContent = currentContent,
                        nextContent = nextContent,
                        prevContent = prevContent
                    )
                }
                PageTurnType.SCROLL -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        currentContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun Simulate3DCurlLayout(
    dragPx: Float,
    dragPy: Float,
    touchDownY: Float,
    widthPx: Float,
    heightPx: Float,
    paper: PaperPalette,
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (dragPx == 0f) {
            currentContent()
            return@Box
        }

        val isNext = dragPx < 0
        val absDragX = abs(dragPx)
        val progress = (absDragX / widthPx).coerceIn(0f, 1f)

        if (isNext) {
            // Next Page (Underneath)
            Box(modifier = Modifier.fillMaxSize()) {
                nextContent()
            }

            // Math model core: anchor point F and touch point P
            val isTopCorner = touchDownY < heightPx * 0.35f
            val isBottomCorner = touchDownY > heightPx * 0.65f
            val anchorY = when {
                isTopCorner -> 0f
                isBottomCorner -> heightPx
                else -> touchDownY.coerceIn(0f, heightPx)
            }
            val anchorF = Offset(widthPx, anchorY)
            val touchP = Offset(
                (widthPx + dragPx).coerceIn(0f, widthPx),
                (anchorY + dragPy).coerceIn(0f, heightPx)
            )

            val vectorV = Offset(touchP.x - anchorF.x, touchP.y - anchorF.y)
            val distanceV = kotlin.math.hypot(vectorV.x, vectorV.y).coerceAtLeast(0.1f)
            val midpointM = Offset((anchorF.x + touchP.x) / 2f, (anchorF.y + touchP.y) / 2f)

            // Fold line direction angle in degrees
            val foldLineAngleRad = kotlin.math.atan2(vectorV.y.toDouble(), vectorV.x.toDouble()) + (Math.PI / 2.0)
            val foldLineDirectionDeg = Math.toDegrees(foldLineAngleRad).toFloat()

            // Curl radius as a function of distance |V|
            val curlRadius = (32f + 0.16f * distanceV) * (0.65f + 0.35f * (1f - progress))
            val arcOffset = (curlRadius * 0.6f * (1f - progress)).coerceIn(4f, 40f)

            val creaseX = midpointM.x
            val touchX = touchP.x
            val flapWidth = creaseX - touchX
            val dy = touchP.y - anchorY

            // Calculate diagonal fold line endpoints
            val foldTopX = if (isTopCorner) (creaseX + dy * 0.15f).coerceIn(0f, widthPx) else creaseX
            val foldBottomX = if (isBottomCorner) (creaseX + dy * 0.15f).coerceIn(0f, widthPx) else creaseX
            val foldTop = Offset(foldTopX, 0f)
            val foldBottom = Offset(foldBottomX, heightPx)

            // Calculate touch line endpoints
            val touchTopX = if (isTopCorner) (touchX + dy * 0.15f).coerceIn(0f, widthPx) else touchX
            val touchBottomX = if (isBottomCorner) (touchX + dy * 0.15f).coerceIn(0f, widthPx) else touchX
            val touchTop = Offset(touchTopX, 0f)
            val touchBottom = Offset(touchBottomX, heightPx)

            // Build clip shape for current page (Flat Region)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                density: androidx.compose.ui.unit.Density
                            ): Outline {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(0f, heightPx)
                                    if (isTopCorner) {
                                        lineTo(widthPx, heightPx)
                                        lineTo(foldBottom.x, heightPx)
                                        cubicTo(
                                            creaseX - arcOffset, heightPx * 0.65f,
                                            creaseX - arcOffset, heightPx * 0.35f,
                                            foldTop.x, 0f
                                        )
                                    } else if (isBottomCorner) {
                                        lineTo(foldBottom.x, heightPx)
                                        cubicTo(
                                            creaseX - arcOffset, heightPx * 0.65f,
                                            creaseX - arcOffset, heightPx * 0.35f,
                                            foldTop.x, 0f
                                        )
                                        lineTo(widthPx, 0f)
                                    } else {
                                        // Standard vertical drag must NOT keep top-right or bottom-right corner!
                                        // It should clip strictly at the fold line crease!
                                        lineTo(foldBottom.x, heightPx)
                                        cubicTo(
                                            creaseX - arcOffset, heightPx * 0.65f,
                                            creaseX - arcOffset, heightPx * 0.35f,
                                            foldTop.x, 0f
                                        )
                                    }
                                    close()
                                }
                                return Outline.Generic(path)
                            }
                        }
                    }
            ) {
                currentContent()
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Drop shadow cast onto next page (aligned with the fold line)
                val shadowWidth = (curlRadius * 2.4f * (1f - progress)).coerceIn(0f, 140f)
                if (shadowWidth > 0f) {
                    val normal = curlNormal(foldTop, foldBottom, +1)
                    val midFold = midPoint(foldTop, foldBottom)
                    val shadowPath = shadowStripPath(foldTop, foldBottom, normal, shadowWidth)
                    drawPath(
                        path = shadowPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.26f * (1f - progress)),
                                Color.Black.copy(alpha = 0.09f * (1f - progress)),
                                Color.Transparent
                            ),
                            start = midFold,
                            end = midFold + normal * shadowWidth
                        )
                    )
                }

                // Inner crease shadow on current page (aligned with the fold line)
                val innerShadowWidth = (curlRadius * 1.0f).coerceIn(15f, 60f)
                if (creaseX > innerShadowWidth) {
                    val normal = curlNormal(foldTop, foldBottom, +1)
                    val midFold = midPoint(foldTop, foldBottom)
                    val innerPath = shadowStripPath(foldTop, foldBottom, normal, -innerShadowWidth)
                    drawPath(
                        path = innerPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.15f * (1f - progress))
                            ),
                            start = midFold - normal * innerShadowWidth,
                            end = midFold
                        )
                    )
                }

                // Paper edge highlight: a thin bright band where the lifted page catches
                // the light along the crease — separates "paper" from "cloth" visually.
                val edgeHighlightWidth = (curlRadius * 0.16f).coerceIn(2f, 6f)
                run {
                    val normal = curlNormal(foldTop, foldBottom, +1)
                    val midFold = midPoint(foldTop, foldBottom)
                    val edgePath = shadowStripPath(foldTop, foldBottom, normal, edgeHighlightWidth)
                    drawPath(
                        path = edgePath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.30f * (1f - progress)),
                                Color.Transparent
                            ),
                            start = midFold,
                            end = midFold + normal * edgeHighlightWidth
                        )
                    )
                }
            }

            // Turned Flap Backside: paper + mirrored page content + cylinder shading
            if (flapWidth > 1f) {
                CurlFlapBackside(
                    foldTop = foldTop,
                    foldBottom = foldBottom,
                    touchTop = touchTop,
                    touchBottom = touchBottom,
                    creaseX = creaseX,
                    touchX = touchX,
                    arcOffset = arcOffset,
                    flapWidth = flapWidth,
                    progress = progress,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    isCorner = isTopCorner || isBottomCorner,
                    bulgeSign = -1,
                    paper = paper,
                    content = currentContent
                )
            }
        } else {
            // Previous Page (Flipping Backward)
            Box(modifier = Modifier.fillMaxSize()) {
                currentContent()
            }

            val isTopCorner = touchDownY < heightPx * 0.35f
            val isBottomCorner = touchDownY > heightPx * 0.65f
            val anchorY = when {
                isTopCorner -> 0f
                isBottomCorner -> heightPx
                else -> touchDownY.coerceIn(0f, heightPx)
            }
            val anchorF = Offset(0f, anchorY)
            val touchP = Offset(
                dragPx.coerceIn(0f, widthPx),
                (anchorY + dragPy).coerceIn(0f, heightPx)
            )

            val vectorV = Offset(touchP.x - anchorF.x, touchP.y - anchorF.y)
            val distanceV = kotlin.math.hypot(vectorV.x, vectorV.y).coerceAtLeast(0.1f)
            val midpointM = Offset((anchorF.x + touchP.x) / 2f, (anchorF.y + touchP.y) / 2f)

            val foldLineAngleRad = kotlin.math.atan2(vectorV.y.toDouble(), vectorV.x.toDouble()) + (Math.PI / 2.0)
            val foldLineDirectionDeg = Math.toDegrees(foldLineAngleRad).toFloat()

            val curlRadius = (32f + 0.16f * distanceV) * (0.65f + 0.35f * (1f - progress))
            val arcOffset = (curlRadius * 0.6f * (1f - progress)).coerceIn(4f, 40f)

            val creaseX = midpointM.x
            val touchX = touchP.x
            val flapWidth = touchX - creaseX
            val dy = touchP.y - anchorY

            // Calculate diagonal fold line endpoints
            val foldTopX = if (isTopCorner) (creaseX + dy * 0.15f).coerceIn(0f, widthPx) else creaseX
            val foldBottomX = if (isBottomCorner) (creaseX + dy * 0.15f).coerceIn(0f, widthPx) else creaseX
            val foldTop = Offset(foldTopX, 0f)
            val foldBottom = Offset(foldBottomX, heightPx)

            // Calculate touch line endpoints
            val touchTopX = if (isTopCorner) (touchX + dy * 0.15f).coerceIn(0f, widthPx) else touchX
            val touchBottomX = if (isBottomCorner) (touchX + dy * 0.15f).coerceIn(0f, widthPx) else touchX
            val touchTop = Offset(touchTopX, 0f)
            val touchBottom = Offset(touchBottomX, heightPx)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                density: androidx.compose.ui.unit.Density
                            ): Outline {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(foldTop.x, 0f)
                                    cubicTo(
                                        creaseX + arcOffset, heightPx * 0.35f,
                                        creaseX + arcOffset, heightPx * 0.65f,
                                        foldBottom.x, heightPx
                                    )
                                    lineTo(0f, heightPx)
                                    close()
                                }
                                return Outline.Generic(path)
                            }
                        }
                    }
            ) {
                prevContent()
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Drop shadow cast onto the current page (on the right of the moving edge)
                val shadowWidth = (curlRadius * 2.4f * (1f - progress)).coerceIn(0f, 140f)
                if (shadowWidth > 0f) {
                    val normal = curlNormal(touchTop, touchBottom, +1)
                    val midTouch = midPoint(touchTop, touchBottom)
                    val shadowPath = shadowStripPath(touchTop, touchBottom, normal, shadowWidth)
                    drawPath(
                        path = shadowPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.26f * (1f - progress)),
                                Color.Black.copy(alpha = 0.09f * (1f - progress)),
                                Color.Transparent
                            ),
                            start = midTouch,
                            end = midTouch + normal * shadowWidth
                        )
                    )
                }
            }

            // Turned Flap Backside: paper + mirrored page content + cylinder shading
            if (flapWidth > 1f) {
                CurlFlapBackside(
                    foldTop = foldTop,
                    foldBottom = foldBottom,
                    touchTop = touchTop,
                    touchBottom = touchBottom,
                    creaseX = creaseX,
                    touchX = touchX,
                    arcOffset = arcOffset,
                    flapWidth = flapWidth,
                    progress = progress,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    isCorner = isTopCorner || isBottomCorner,
                    bulgeSign = +1,
                    paper = paper,
                    content = prevContent
                )
            }
        }
    }
}

@Composable
private fun CurlFlapBackside(
    foldTop: Offset,
    foldBottom: Offset,
    touchTop: Offset,
    touchBottom: Offset,
    creaseX: Float,
    touchX: Float,
    arcOffset: Float,
    flapWidth: Float,
    progress: Float,
    widthPx: Float,
    heightPx: Float,
    isCorner: Boolean,
    bulgeSign: Int,
    paper: PaperPalette,
    content: @Composable () -> Unit
) {
    val flapPath = buildFlapPath(
        foldTop = foldTop,
        foldBottom = foldBottom,
        touchTop = touchTop,
        touchBottom = touchBottom,
        creaseX = creaseX,
        touchX = touchX,
        arcOffset = arcOffset,
        heightPx = heightPx,
        bulgeSign = bulgeSign
    )
    val flapShape = object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
            density: androidx.compose.ui.unit.Density
        ): Outline = Outline.Generic(flapPath)
    }
    val mirrorPivot = TransformOrigin(
        (foldBottom.x / widthPx).coerceIn(0f, 1f),
        (foldBottom.y / heightPx).coerceIn(0f, 1f)
    )
    val mirrorDeg = mirrorAngleDeg(foldTop, foldBottom)
    val fade = 1f - progress

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(flapShape)
    ) {
        // Translucent paper base — 真实纸张是透光的。
        // 此前这里铺的是 100% 不透明的米色底，底下那页一点都透不出来，
        // 再叠上仅有 0.42f alpha 的镜像内容，观感就是"一块奶油色板 + 若有若无的鬼影字"。
        // 现在留 ~7%~10% 让下一页透出，同时镜像内容的 alpha 也大幅提上来（见下）。
        // 纸色不再写死 0xFFF5F0E6，改用阅读主题推导的 [PaperPalette.face]。
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(paper.face.copy(alpha = paper.baseAlpha))
        }

        // Backside shows the page content mirrored across the fold (real paper feel)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = BACKSIDE_CONTENT_ALPHA
                    transformOrigin = mirrorPivot
                    scaleX = -1f
                }
                .graphicsLayer {
                    transformOrigin = mirrorPivot
                    rotationZ = mirrorDeg
                }
        ) {
            content()
        }

        // Paper tint + cylinder shading + moving-edge thickness
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(paper.face.copy(alpha = 0.15f))

            val shadeStart = midPoint(foldTop, foldBottom)
            val shadeEnd = midPoint(touchTop, touchBottom)
            drawPath(
                path = flapPath,
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Black.copy(alpha = 0.17f * fade),
                        0.18f to Color.Black.copy(alpha = 0.07f * fade),
                        0.40f to Color.White.copy(alpha = 0.09f * fade),
                        0.58f to Color.White.copy(alpha = 0.04f * fade),
                        0.82f to Color.Black.copy(alpha = 0.03f * fade),
                        1.0f to Color.Transparent
                    ),
                    start = shadeStart,
                    end = shadeEnd
                )
            )

            // Soft ambient occlusion near the fold corner
            if (isCorner) {
                val radius = (flapWidth * 1.25f).coerceAtLeast(8f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.10f * fade),
                            Color.Transparent
                        ),
                        center = shadeStart,
                        radius = radius
                    ),
                    radius = radius,
                    center = shadeStart
                )
            }

            // Paper thickness along the moving edge
            // 高光/描边同样换成主题推导色：夜里不再出现米黄色纸边。
            val touchMidX = touchX + bulgeSign * arcOffset * 0.5f
            val touchEdgePath = Path().apply {
                moveTo(touchTop.x, 0f)
                cubicTo(touchMidX, heightPx * 0.35f, touchMidX, heightPx * 0.65f, touchBottom.x, heightPx)
                close()
            }
            drawPath(
                path = touchEdgePath,
                color = paper.edgeHighlight.copy(alpha = 0.92f),
                style = Stroke(width = 2.4f)
            )
            drawPath(
                path = touchEdgePath,
                color = paper.edgeStroke.copy(alpha = 0.20f),
                style = Stroke(width = 0.9f)
            )
        }
    }
}

private fun midPoint(a: Offset, b: Offset): Offset =
    Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun curlNormal(fromTop: Offset, fromBottom: Offset, sign: Int): Offset {
    val dx = fromBottom.x - fromTop.x
    val dy = fromBottom.y - fromTop.y
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
    var n = Offset(-dy / len, dx / len)
    if ((sign > 0) != (n.x > 0f)) n = Offset(-n.x, -n.y)
    return n
}

private fun shadowStripPath(
    fromTop: Offset,
    fromBottom: Offset,
    normal: Offset,
    width: Float
): Path = Path().apply {
    moveTo(fromTop.x, fromTop.y)
    lineTo(fromBottom.x, fromBottom.y)
    lineTo(fromBottom.x + normal.x * width, fromBottom.y + normal.y * width)
    lineTo(fromTop.x + normal.x * width, fromTop.y + normal.y * width)
    close()
}

private fun mirrorAngleDeg(foldTop: Offset, foldBottom: Offset): Float {
    val dx = foldTop.x - foldBottom.x
    val dy = foldTop.y - foldBottom.y
    val angle = Math.PI - 2.0 * kotlin.math.atan2(dy.toDouble(), dx.toDouble())
    return Math.toDegrees(angle).toFloat()
}

private fun buildFlapPath(
    foldTop: Offset,
    foldBottom: Offset,
    touchTop: Offset,
    touchBottom: Offset,
    creaseX: Float,
    touchX: Float,
    arcOffset: Float,
    heightPx: Float,
    bulgeSign: Int
): Path {
    val foldMidX = creaseX + bulgeSign * arcOffset
    val touchMidX = touchX + bulgeSign * arcOffset * 0.5f
    return Path().apply {
        moveTo(foldTop.x, 0f)
        cubicTo(foldMidX, heightPx * 0.35f, foldMidX, heightPx * 0.65f, foldBottom.x, heightPx)
        lineTo(touchBottom.x, heightPx)
        cubicTo(touchMidX, heightPx * 0.65f, touchMidX, heightPx * 0.35f, touchTop.x, 0f)
        close()
    }
}

@Composable
private fun CoverPageTurnLayout(
    dragPx: Float,
    widthPx: Float,
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (dragPx < 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = (1f + dragPx / widthPx.coerceAtLeast(1f)) * (-0.15f * widthPx) }
            ) {
                nextContent()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx }
            ) {
                currentContent()
            }
        } else if (dragPx > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx * 0.15f }
            ) {
                currentContent()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx - widthPx }
            ) {
                prevContent()
            }
        } else {
            currentContent()
        }
    }
}

@Composable
private fun SlidePageTurnLayout(
    dragPx: Float,
    widthPx: Float,
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (dragPx < 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx }
            ) {
                currentContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = widthPx + dragPx }
            ) {
                nextContent()
            }
        } else if (dragPx > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx }
            ) {
                currentContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragPx - widthPx }
            ) {
                prevContent()
            }
        } else {
            currentContent()
        }
    }
}

@Composable
private fun FadePageTurnLayout(
    dragPx: Float,
    widthPx: Float,
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (dragPx < 0) {
            val progress = (abs(dragPx) / (widthPx * 0.5f)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - progress }
            ) {
                currentContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
            ) {
                nextContent()
            }
        } else if (dragPx > 0) {
            val progress = (abs(dragPx) / (widthPx * 0.5f)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - progress }
            ) {
                currentContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
            ) {
                prevContent()
            }
        } else {
            currentContent()
        }
    }
}
