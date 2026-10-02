@file:OptIn(ExperimentalPageCurlApi::class)

package com.example.ui.pageturn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintGold
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 卷页背面「内容的可见度」——pagecurl 引擎内部的换算关系是
 * `overlayAlpha = 1 - backPageContentAlpha`（overlay 即铺在最上面的纸色）。
 *
 * 旧值 0f → overlayAlpha = 1.0 → 背面被一层纯色完全盖死（用户报的那个 bug）。
 * 取 0.58f：背面保留 42% 的纸感 + 58% 的镜像字，读起来像真透过纸张看到墨迹。
 * 再高会喧宾夺主（看着像印错了正的），再低又退回"纯色板"。
 */
private const val BACK_PAGE_CONTENT_ALPHA = 0.58f

/**
 * C1 pagecurl 引擎容器（SIMULATE 翻页档）。
 *
 * 下拉书签：与 PageTurnContainer（COVER/SLIDE/FADE 档）完全同一套算法——
 * 方向仲裁(totalY>slop 且 totalY>|totalX|×1.1)、阻尼位移(raw×0.5≤160)、
 * 充能阈值(80px 阻尼或 160px 原始)、顶部充能卡视觉、松手 toggle。
 * 实现层差异仅一点：本容器在 PointerEventPass.Initial 消费 PULL 手势，
 * 使 pagecurl 的拖拽在 touch-slop 阶段被终止（其消费在 Main pass）。
 */
@Composable
fun PageCurlReaderContainer(
    currentContent: @Composable () -> Unit,
    nextContent: @Composable () -> Unit,
    prevContent: @Composable () -> Unit,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    onClickCenter: () -> Unit,
    onToggleBookmark: () -> Unit = {},
    isCurrentBookmarked: Boolean = false,
    /** 中间区域长按 2s：触发串珠快速翻页（PageScrubberOverlay）。 */
    onLongPressCenter: (() -> Unit)? = null,
    menuVisible: Boolean,
    /**
     * 卷页背面的纸色：传阅读区当页背景色（ReaderScreen 的 `bgColor`）。
     *
     * 此前这里写死 `Color(0xFFE8E4DC)` 且 `backPageContentAlpha = 0f`——
     * pagecurl 引擎内部会用 `backPageColor.copy(alpha = 1 - backPageContentAlpha)`
     * 盖一层，alpha 取 0 就是 **100% 不透明纯色**：背面的镜像内容被完全糊掉，
     * 用户看到的"仿真翻页背面不真实，渲染成不透明纯色"正是这一行造成的。
     * 而且它不跟随阅读主题，夜间模式下依然是米黄色纸。
     */
    paperColor: Color = Color(0xFFE8E4DC),
    // 点击位置命中正文插图时回调（返回 true=已打开全屏，宿主跳过翻页分派）。
    // Initial-pass（父先于子）下"子层消费检测"不可用，必须按屏幕矩形几何命中。
    onImageTapAt: ((androidx.compose.ui.geometry.Offset) -> Boolean)? = null,
    modifier: Modifier = Modifier
) {
    val state = rememberPageCurlState(initialCurrent = 1)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var containerOffsetInWindow by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    }
    val latestOnImageTapAt by androidx.compose.runtime.rememberUpdatedState(onImageTapAt)

    // 纸面色由阅读主题推导，夜里自动变深，不再出现米黄纸。
    val paper = rememberPaperPalette(paperColor)

    // ── 下拉充能状态（与 PageTurnContainer 同参数）──
    var pullValue by remember { mutableFloatStateOf(0f) }
    val charged = pullValue >= 80f
    val iconScale by animateFloatAsState(
        targetValue = if (charged) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "pullIconScale"
    )

    val config = PageCurlConfig(
        // 纸面改用主题推导色；深色主题下自动变深。
        backPageColor = paper.face,
        // 「背面内容的可见度」。pagecurl 内部会铺一层 alpha = 1 - 该值的纸色，
        // 取 0 就是完全不透明（旧 bug）。0.58f → 纸占 42%、镜像字透出 58%，
        // 观感接近 iBooks/掌阅：背面确实"印着字"，而不是一块空色板。
        backPageContentAlpha = BACK_PAGE_CONTENT_ALPHA,
        shadowColor = Color.Black,
        shadowAlpha = 0.25f,
        shadowRadius = 18.dp,
        shadowOffset = androidx.compose.ui.unit.DpOffset((-6).dp, 2.dp),
        dragForwardEnabled = !menuVisible,
        dragBackwardEnabled = !menuVisible,
        // 左右点按翻页改由本容器手势仲裁器（Initial pass）处理：
        // 1) 连点时可"即时翻页"（不与在飞的卷页动画互抢同一 Animatable）；
        // 2) 库内 tapGesture → next()/prev() 的 animateJob 僵尸 finally 会与
        //    重置 LaunchedEffect 竞态（快速连点后卡死、无法回翻的根因）。
        tapForwardEnabled = false,
        tapBackwardEnabled = false,
        tapCustomEnabled = true,
        dragInteraction = PageCurlConfig.StartEndDragInteraction(),
        tapInteraction = PageCurlConfig.TargetTapInteraction(),
        onCustomTap = { sz, pos ->
            val third = sz.width / 3f
            if (pos.x >= third && pos.x <= third * 2f) {
                onClickCenter()
                true
            } else {
                false
            }
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 回调始终取最新值：pointerInput(Unit) 只捕获首帧闭包，
        // 若直接捕获 onToggleBookmark 会拿到初始空 bookmarks 列表导致"只会添加"。
        val latestOnToggleBookmark by androidx.compose.runtime.rememberUpdatedState(onToggleBookmark)
        val latestOnNextPage by androidx.compose.runtime.rememberUpdatedState(onNextPage)
        val latestOnPrevPage by androidx.compose.runtime.rememberUpdatedState(onPrevPage)
        val latestOnClickCenter by androidx.compose.runtime.rememberUpdatedState(onClickCenter)
        val latestOnLongPressCenter by androidx.compose.runtime.rememberUpdatedState(onLongPressCenter)
        val latestMenuVisible by androidx.compose.runtime.rememberUpdatedState(menuVisible)

        LaunchedEffect(state.current) {
            when (state.current) {
                2 -> { latestOnNextPage(); state.snapTo(1) }
                0 -> { latestOnPrevPage(); state.snapTo(1) }
            }
        }

        // 点按翻页：动画进行中（连点）→ 取消在飞动画并即时翻页；
        // 空闲 → 交给库的 next()/prev() 播放完整卷页动画。
        // cancel+join 确保僵尸 finally 先落定，再由重置 LaunchedEffect 收敛回 1。
        fun tapTurn(forward: Boolean) {
            scope.launch {
                val job = state.internalState?.animateJob
                if (job != null && job.isActive) {
                    job.cancel()
                    job.join()
                    if (forward) latestOnNextPage() else latestOnPrevPage()
                    state.snapTo(1)
                } else {
                    if (forward) state.next() else state.prev()
                }
            }
        }

        // 页面内容跟随下拉阻尼位移（与 PageTurnContainer 的 translationY 一致）
        // 手势仲裁器挂在同一个 Box 上（同节点路径，pagecurl 仍可正常命中）——
        // 严禁做成覆盖式兄弟节点：那会让 pagecurl 不在命中链上而完全无法翻页。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pullValue }
                .onGloballyPositioned { containerOffsetInWindow = it.positionInWindow() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val slop = viewConfiguration.touchSlop
                        var totalX = 0f
                        var totalY = 0f
                        var isDrag = false
                        var activeMode = 0 // 0=未定 1=水平翻页 2=下拉书签
                        var fired = false
                        var longPressFired = false
                        var tapOnImage = false
                        // 长按触发区扩展到整个页面：任意位置静置 1s 均可唤出串珠快速翻页
                        var longPressArmed = true
                        var lastUptime = down.uptimeMillis
                        val longPressDeadline = down.uptimeMillis + 1000L

                        while (true) {
                            val ev = if (longPressArmed) {
                                withTimeoutOrNull((longPressDeadline - lastUptime).coerceAtLeast(1L)) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                            } else {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (ev == null) {
                                // 中间区域静置 1s：触发串珠快速翻页
                                longPressArmed = false
                                if (!isDrag && !latestMenuVisible) {
                                    longPressFired = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnLongPressCenter?.invoke()
                                    // 吞掉余下事件直到抬起：防止遮罩出现前 pagecurl 抢手势
                                    while (true) {
                                        val drain = awaitPointerEvent(PointerEventPass.Initial)
                                        drain.changes.forEach { it.consume() }
                                        if (drain.changes.any { !it.pressed }) break
                                    }
                                    break
                                }
                                continue
                            }

                            lastUptime = maxOf(lastUptime, ev.changes.maxOf { it.uptimeMillis })
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                // 纯点按且命中正文插图（屏幕矩形注册表）：Initial pass 消费 up，
                                // 子 clickable（Main pass）与 pagecurl 都不会再触发 → 不翻页
                                if (!isDrag && !longPressFired && !latestMenuVisible) {
                                    tapOnImage = latestOnImageTapAt
                                        ?.invoke(down.position + containerOffsetInWindow) == true
                                    if (tapOnImage) ch.consume()
                                }
                                break
                            }
                            val delta = ch.positionChange()
                            totalX += delta.x
                            totalY += delta.y

                            if (!isDrag && (abs(totalX) > slop || abs(totalY) > slop)) {
                                isDrag = true
                                activeMode = when {
                                    totalY > slop && totalY > abs(totalX) * 1.1f -> 2
                                    abs(totalX) > slop -> 1
                                    else -> 0
                                }
                            }

                            if (isDrag && activeMode == 2) {
                                // 独占事件：pagecurl 的 slop 检测在此处被掐断
                                ch.consume()
                                pullValue = (totalY.coerceAtLeast(0f) * 0.5f).coerceIn(0f, 160f)
                            }
                            // activeMode==1：不消费，放行给同节点上 pagecurl 的 Main-pass 手势
                        }

                        // 松手结算（与 PageTurnContainer.kt:158-169 相同逻辑；单次触发保护）
                        if (isDrag && activeMode == 2 && !fired) {
                            fired = true
                            if (pullValue >= 80f || totalY >= 160f) {
                                latestOnToggleBookmark()
                            }
                        }
                        if (activeMode == 2) {
                            val startVal = pullValue
                            scope.launch {
                                val durationMs = 260
                                val stepMs = 16L
                                var elapsed = stepMs
                                while (elapsed <= durationMs) {
                                    val t = elapsed.toFloat() / durationMs
                                    pullValue = startVal * (1f - t) * (1f - t)
                                    kotlinx.coroutines.delay(stepMs)
                                    elapsed += stepMs.toInt()
                                }
                                pullValue = 0f
                            }
                        }

                        // 纯点按（未越过 touchSlop）：命中插图已在 up 时消费（tapOnImage），
                        // 此处只做左右三分之一分区翻页（与 onCustomTap 中间三分之一互补）
                        if (!isDrag && !longPressFired && !latestMenuVisible && !tapOnImage) {
                            val x = down.position.x
                            val w = size.width.toFloat()
                            when {
                                x > w * 2f / 3f -> tapTurn(forward = true)
                                x < w / 3f -> tapTurn(forward = false)
                            }
                        }
                    }
                }
        ) {
            PageCurl(
                count = 3,
                state = state,
                config = config,
                modifier = Modifier.fillMaxSize()
            ) { idx ->
                Box(modifier = Modifier.fillMaxSize()) {
                    when (idx) {
                        0 -> prevContent()
                        1 -> currentContent()
                        else -> nextContent()
                    }
                }
            }
        }

        // ── 顶部充能卡（纯绘制无 pointerInput，不会拦截触摸）──
        if (pullValue > 3f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        tonalElevation = 6.dp,
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (charged) 2.dp else 1.dp,
                            color = if (charged) MintGold else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { (pullValue / 80f).coerceIn(0f, 1f) },
                                    strokeWidth = 3.dp,
                                    color = if (charged) MintGold else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Icon(
                                    imageVector = if (isCurrentBookmarked) Icons.Filled.BookmarkBorder else Icons.Filled.Bookmark,
                                    contentDescription = null,
                                    tint = if (charged) MintGold else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = when {
                                    charged && isCurrentBookmarked -> "松开即可取消书签"
                                    charged -> "松开即可保存书签"
                                    isCurrentBookmarked -> "下拉取消书签"
                                    else -> "下拉添加书签"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
