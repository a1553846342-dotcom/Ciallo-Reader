package com.example.ui.comic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 漫画缩放状态：单指拖动 / 双指缩放平移 / 双击放大 / 双击按住拖动(QuickZoom) / 长按放大共用。
 * 渲染模型：screen = center + (content - center) * scale + offset
 *
 * 手感算法移植自成熟开源实现（详见 docs/WHEEL_EVALUATION.md）：
 * - 缩放阻尼回弹（rubber band）：panpf/zoomimage `limitScaleWithRubberBand`（Apache-2.0）
 * - 双击动态档位：zoomimage `DynamicScalesCalculator`（Apache-2.0）
 * - 惯性 fling：splineBasedDecay + 撞墙即停（zoomimage / telephoto 同款）
 * - 双击按住上下拖缩放（QuickZoom）：saket/telephoto（Apache-2.0），dy × 0.004/px
 */
@Stable
class ComicZoomState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
) {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var containerSize by mutableStateOf(Size.Zero)
    /** 内容适配后（scale=1 时）的显示尺寸（px） */
    var contentSize by mutableStateOf(Size.Zero)
    /** 原始位图像素尺寸（双击 1:1 档位计算用；未设置时不提供该档） */
    var intrinsicSize by mutableStateOf(Size.Zero)

    /** 长按放大激活中（跟随手指平移，松手还原） */
    var holdZoomActive by mutableStateOf(false)

    /** 手势进行中（区域重解码等重活暂停的信号） */
    var gestureActive by mutableStateOf(false)
    /** 每次手势结束递增（触发松手后的懒刷新） */
    var gestureEpoch by mutableLongStateOf(0L)

    /** 松手动画（fling/回弹）任务，新手势开始时取消 */
    internal var releaseJob: Job? = null

    /**
     * 松手动画进行中（snapshot 可观察）：区域重解码等空闲触发逻辑用它判断
     * "真正静止"。releaseJob 本身是普通 Job 引用，生命周期变化不触发重组，
     * 需要 observe 的地方读这个状态。
     */
    var releaseAnimating by mutableStateOf(false)

    /** 双击检测（per-cell，避免跨页误判） */
    internal var lastTapTime by mutableLongStateOf(0L)
    internal var lastTapPos by mutableStateOf(Offset.Zero)
    internal var pendingTapJob: Job? = null

    /** 是否存在可平移空间（放大或内容超出容器） */
    val canPan: Boolean
        get() = panLimitX() > 0.5f || panLimitY() > 0.5f

    val isZoomed: Boolean
        get() = scale > 1.02f || canPan

    /** 是否已到水平平移边界（direction >0 表示正方向边界） */
    fun horizontalPanEdge(direction: Int): Boolean {
        val limit = panLimitX()
        if (limit <= 0f) return true
        return if (direction > 0) offsetX >= limit - 1f else offsetX <= -limit + 1f
    }

    fun panLimitX(): Float {
        val w = contentSize.width * scale - containerSize.width
        return max(0f, w / 2f)
    }

    fun panLimitY(): Float {
        val h = contentSize.height * scale - containerSize.height
        return max(0f, h / 2f)
    }

    /* ── 动态双击档位（zoomimage DynamicScalesCalculator 思路） ── */

    /** 中档：至少 2.5x、能填满容器短边、必要时更高；上限不超过 4x */
    fun mediumScale(): Float {
        val fill = if (contentSize.width > 1f && contentSize.height > 1f && containerSize.width > 1f) {
            max(
                containerSize.width / contentSize.width,
                containerSize.height / contentSize.height
            )
        } else 2.5f
        return max(2.5f, min(fill, 4f)).coerceIn(minScale, maxScale)
    }

    /** 1:1 原始像素档（仅当原始图比适配显示更大时有意义） */
    fun oneToOneScale(): Float {
        if (intrinsicSize.width < 1f || contentSize.width < 1f) return Float.NaN
        val s = intrinsicSize.width / contentSize.width
        return if (s > mediumScale() * 1.1f && s <= maxScale) s else Float.NaN
    }

    /** 双击循环档位序列（去重、升序） */
    fun stepScales(): List<Float> {
        val base = mutableListOf(minScale, mediumScale())
        oneToOneScale().let { if (!it.isNaN()) base.add(it) }
        return base.distinctBy { (it * 100).toLong() }.sorted()
    }

    /**
     * 下一个双击目标（zoomimage 0.35 容差）：从低到高找第一个
     * `step > current + 0.35 × 与下一低档的间距` 的档位；到顶回 min。
     */
    fun nextStepScale(): Float {
        val steps = stepScales()
        for (i in 1 until steps.size) {
            val gap = steps[i] - steps[i - 1]
            if (scale < steps[i] - 0.35f * gap) return steps[i]
        }
        return steps.first()
    }

    /* ── 变换 ── */

    fun updateTransform(zoom: Float, pan: Offset, focal: Offset) {
        val raw = if (scale <= 0f) zoom else scale * zoom
        val newScale = limitScaleWithRubberBand(scale, raw, minScale, maxScale)
        if (scale <= 0f) {
            scale = newScale
            offsetX = pan.x
            offsetY = pan.y
            return
        }
        val cx = containerSize.width / 2f
        val cy = containerSize.height / 2f
        val pX = (focal.x - cx - offsetX) / scale
        val pY = (focal.y - cy - offsetY) / scale
        scale = newScale
        offsetX = focal.x - cx - pX * newScale + pan.x
        offsetY = focal.y - cy - pY * newScale + pan.y
        clampOffsets()
    }

    fun panBy(delta: Offset) {
        // 橡胶带平移（zoomimage dragRubberBand 思路）：界内全额跟手，
        // 越界部分 0.35 衰减——拖到边界是渐进阻力而非硬撞墙，
        // 松手后由 settle() 的弹簧把越界量拉回（否则弹簧回弹永不可达）
        val lx = panLimitX()
        val ly = panLimitY()
        offsetX = rubberPan(offsetX, delta.x, lx)
        offsetY = rubberPan(offsetY, delta.y, ly)
    }

    /**
     * 越界平移的增量式橡胶带（对照 limitScaleWithRubberBand 的 cur+over×f 形式）：
     * - 界内：全额跟手
     * - 越界：cur + d×0.35（越界增量衰减）——持续拖动越界量持续增长，
     *   硬顶 1.6×limit；旧版基于 (target−limit) 差值会让越界量收敛到与单帧
     *   速度相关的固定点（拖久顶死、减速反向回缩），属公式性错误
     * - 无余量轴：0.3 阻尼漂移 ±96（跟手感），松手回弹
     */
    private fun rubberPan(cur: Float, d: Float, limit: Float): Float {
        if (limit <= 0.5f) return (cur + d * 0.3f).coerceIn(-96f, 96f)
        val target = cur + d
        return if (target >= -limit && target <= limit) {
            target
        } else {
            (cur + d * 0.35f).coerceIn(-limit * 1.6f, limit * 1.6f)
        }
    }

    private fun clampOffsets() {
        val lx = panLimitX()
        val ly = panLimitY()
        offsetX = offsetX.coerceIn(-lx, lx)
        offsetY = offsetY.coerceIn(-ly, ly)
    }

    /** 双击/长按放大到指定倍数，保持 focal 点位置不动 */
    suspend fun animateZoomTo(targetScale: Float, focal: Offset? = null) {
        val startScale = scale
        val startOffX = offsetX
        val startOffY = offsetY
        val tScale = targetScale.coerceIn(minScale, maxScale)
        val cx = containerSize.width / 2f
        val cy = containerSize.height / 2f
        val f = focal ?: Offset(cx, cy)
        val pCX = if (startScale > 0f) (f.x - cx - startOffX) / startScale else 0f
        val pCY = if (startScale > 0f) (f.y - cy - startOffY) / startScale else 0f
        val targetLimitX = max(0f, (contentSize.width * tScale - containerSize.width) / 2f)
        val targetLimitY = max(0f, (contentSize.height * tScale - containerSize.height) / 2f)
        val tOffX = (f.x - cx - pCX * tScale).coerceIn(-targetLimitX, targetLimitX)
        val tOffY = (f.y - cy - pCY * tScale).coerceIn(-targetLimitY, targetLimitY)
        val anim = Animatable(0f)
        // 只约束最终位置，和缩放倍率共用一个进度：逐帧按当时的边界夹紧，
        // 会让非中心焦点漂移，并把回弹途中被双击打断的偏移瞬间拉回边界。
        anim.animateTo(1f, tween(220)) {
            val v = this.value
            scale = startScale + (tScale - startScale) * v
            offsetX = startOffX + (tOffX - startOffX) * v
            offsetY = startOffY + (tOffY - startOffY) * v
        }
        clampOffsets()
    }

    suspend fun animateReset() = animateZoomTo(1f)

    /** 瞬时缩放到目标（无动画，长按放大用——避免与跟手 panBy 并发互写） */
    fun snapZoomTo(targetScale: Float, focal: Offset? = null) {
        val tScale = targetScale.coerceIn(minScale, maxScale)
        val cx = containerSize.width / 2f
        val cy = containerSize.height / 2f
        val f = focal ?: Offset(cx, cy)
        val pX = if (scale > 0f) (f.x - cx - offsetX) / scale else 0f
        val pY = if (scale > 0f) (f.y - cy - offsetY) / scale else 0f
        scale = tScale
        offsetX = f.x - cx - pX * tScale
        offsetY = f.y - cy - pY * tScale
        clampOffsets()
    }

    fun snapReset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        holdZoomActive = false
    }

    /* ── 松手行为：fling 惯性 / 越界回弹 ── */

    /**
     * 惯性滑动：splineBasedDecay + 撞墙即停（zoomimage/telephoto 模式）。
     * 返回是否真的执行了 fling（速度过低直接结束）。
     */
    suspend fun fling(velocity: Offset, density: Density): Boolean {
        // 速度阈值密度无关化：150dp/s 起步（贴近平台 minimumFling 手感，旧 300dp/s 显著发沉）、
        // 8000dp/s 硬帽（与磁吸/卷页的 400dp/s 同一标准系）
        val minSpeed = with(density) { 150.dp.toPx() }
        val vCap = with(density) { 8000.dp.toPx() }
        val speed = velocity.getDistance()
        if (speed < minSpeed || !canPan) return false
        // 按模长截断（保持方向）：逐轴独立 clamp 会把对角高速 fling 扭向轴对齐
        val v = if (speed > vCap) Offset(velocity.x * vCap / speed, velocity.y * vCap / speed) else velocity
        var hitWall = false
        val state = AnimationState(
            typeConverter = Offset.VectorConverter,
            initialValue = Offset(offsetX, offsetY),
            initialVelocity = v,
        )
        try {
            state.animateDecay(splineBasedDecay(density)) {
                val lx = panLimitX()
                val ly = panLimitY()
                if (value.x < -lx || value.x > lx || value.y < -ly || value.y > ly) {
                    offsetX = value.x.coerceIn(-lx, lx)
                    offsetY = value.y.coerceIn(-ly, ly)
                    hitWall = true
                    cancelAnimation()
                } else {
                    offsetX = value.x
                    offsetY = value.y
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 布局变化导致 limit 异常时直接钳制收场
            clampOffsets()
        }
        // 撞墙即停后：把 panBy 橡胶带留下的越界量用弹簧拉回（无跳变收尾）
        if (hitWall) settleOffsets()
        return true
    }

    /** 松手收尾：scale 越界 → 动画回边界；offset 越界 → 弹簧回边界 */
    suspend fun settle() {
        val target = scale.coerceIn(minScale, maxScale)
        if (abs(target - scale) > 0.004f) {
            animateZoomTo(target)
        } else {
            // scale 在界内但可能贴边，直接对齐避免浮点残留
            if (abs(scale - target) > 0f) scale = target
        }
        settleOffsets()
    }

    private suspend fun settleOffsets() {
        val lx = panLimitX()
        val ly = panLimitY()
        if (offsetX < -lx - 0.5f || offsetX > lx + 0.5f ||
            offsetY < -ly - 0.5f || offsetY > ly + 0.5f
        ) {
            val anim = Animatable(Offset(offsetX, offsetY), Offset.VectorConverter)
            anim.animateTo(
                Offset(offsetX.coerceIn(-lx, lx), offsetY.coerceIn(-ly, ly)),
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ) {
                offsetX = value.x
                offsetY = value.y
            }
        }
    }
}

/**
 * 缩放阻尼回弹（移植自 panpf/zoomimage `limitScaleWithRubberBand`，Apache-2.0）。
 * 界内原样通过；越界部分按 (1−progress)/2 衰减，硬顶 ratio 倍。
 */
fun limitScaleWithRubberBand(
    current: Float,
    target: Float,
    min: Float,
    max: Float,
    ratio: Float = 2f,
): Float {
    if (target in min..max) return target
    val over = target - current
    return if (target > max) {
        val hardMax = max * ratio
        if (target < hardMax) current + over * (1 - (target - max) / (hardMax - max)) * 0.5f
        else hardMax
    } else {
        val hardMin = min / ratio
        if (target > hardMin) current + over * (1 - (min - target) / (min - hardMin)) * 0.5f
        else hardMin
    }
}

/** 手势回调集合 */
class ComicGestureCallbacks(
    val onTapZone: (Offset, Size) -> Unit,
    val onLongPress: (Offset) -> Unit,
    val onPinchClose: () -> Unit,
    val onEdgeBack: () -> Unit,
    /** 放大状态下滑到边缘继续翻页（dir>0 屏幕向右拖） */
    val onZoomedEdgeSwipe: ((Int) -> Unit)? = null,
)

/**
 * 核心手势修饰符：缩放/平移/点按区/双击放大/双击按住拖缩放/长按放大/双指合拢退出/惯性 fling。
 *
 * 事件仲裁：
 * - 未放大时单指拖动不消费 → 交给 Pager/滚动容器翻页
 * - 放大（或内容超界可平移）时单指拖动消费平移；触边继续拖 → onZoomedEdgeSwipe 翻页；
 *   松手带速度 → 惯性 fling（撞墙即停）
 * - 双指手势始终消费（缩放+平移）；从 1x 合拢 < 0.62 → 退出请求；松手越界弹簧回弹
 * - 双击（可配置）→ 动态档位循环（1x → 填满 → 1:1）
 * - 双击第二击按住上下拖 → QuickZoom 连续缩放（telephoto dy×0.004）
 * - 长按（未移动）→ 长按放大（可配置关闭），期间消费全部事件；松手动画还原
 */
fun Modifier.comicZoomable(
    state: ComicZoomState,
    config: ComicReaderConfig,
    callbacks: ComicGestureCallbacks,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val latestConfig by rememberUpdatedState(config)
    val latestCallbacks by rememberUpdatedState(callbacks)
    DisposableEffect(state) {
        onDispose {
            state.pendingTapJob?.cancel()
            state.releaseJob?.cancel()
        }
    }
    this
        .onSizeChanged { state.containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(state) {
            val slop = viewConfiguration.touchSlop
            val longPressTimeout = 380L
            val tapMaxDuration = 340L
            val doubleTapWindow = 320L
            val doubleTapRadius = 48.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                if (down.isConsumed) return@awaitEachGesture
                state.pendingTapJob?.cancel()
                // 新手势打断上一次松手动画（fling/回弹）
                state.releaseJob?.cancel()
                state.releaseJob = null
                state.releaseAnimating = false
                state.gestureActive = true

                var pointers = 1
                var holdZoomFired = false
                var holdZoomConsuming = false
                var panelLongPressed = false
                var transformMode = false
                var pinchCloseFired = false
                var edgeSwipeFired = false
                var edgeSwipeAccum = 0f
                var moved = false
                var totalDragX = 0f
                var totalDragY = 0f
                var lastPos = down.position
                // QuickZoom：第二击按住上下拖
                var quickZoomCandidate = latestConfig.doubleTapZoom &&
                    state.lastTapTime > 0L &&
                    down.uptimeMillis - state.lastTapTime in 0..doubleTapWindow &&
                    (down.position - state.lastTapPos).getDistance() < doubleTapRadius
                var quickZoomActive = false
                if (quickZoomCandidate) {
                    state.lastTapTime = 0L
                    state.pendingTapJob?.cancel()
                }
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                // All tap timing uses pointer event timestamps, including injected/queued events.
                val downTime = down.uptimeMillis
                var gestureEndTime = downTime

                // 长按计时（外部 scope，避免受限挂起限制）
                var longPressJob: Job? = if (quickZoomCandidate) null else scope.launch {
                    delay(longPressTimeout)
                    if (!moved && !transformMode && pointers == 1) {
                        if (latestConfig.longPressZoom) {
                            holdZoomFired = true
                            holdZoomConsuming = true
                            state.holdZoomActive = true
                            // 瞬时定位（不做 220ms 动画）：动画期间逐帧写 scale/offset 会与
                            // 跟手 panBy 并发互写（前 220ms 手指位移被动画覆盖，抖动+落点漂移）
                            state.snapZoomTo(minOf(2.8f, state.mediumScale()), down.position)
                        } else if (latestConfig.gestureLongPressPanel) {
                            panelLongPressed = true
                            holdZoomConsuming = true
                            latestCallbacks.onLongPress(down.position)
                        }
                    }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        gestureEndTime = event.changes.firstOrNull()?.uptimeMillis ?: gestureEndTime
                        pointers = event.changes.count { it.pressed }
                        if (pointers == 0) {
                            event.changes.firstOrNull()?.let { lastPos = it.position }
                            break
                        }

                        if (pointers >= 2) {
                            state.lastTapTime = 0L
                            transformMode = true
                            quickZoomCandidate = false
                            quickZoomActive = false
                            longPressJob?.cancel()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            event.changes.firstOrNull { it.id == down.id }?.let {
                                velocityTracker.addPosition(it.uptimeMillis, it.position)
                            }
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val focal = event.calculateCentroid(true)
                                state.updateTransform(zoomChange, panChange, focal)
                                if (latestConfig.gesturePinchClose && !state.isZoomed &&
                                    state.scale < 0.62f && !pinchCloseFired
                                ) {
                                    pinchCloseFired = true
                                    latestCallbacks.onPinchClose()
                                }
                                if (pinchCloseFired && state.scale < 0.62f) state.scale = 0.62f
                            }
                            event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        // 首末页外翻等父层已接管拖动：positionChange() 会变成 0，
                        // 仍须取消长按计时，避免拖了 380ms 后误开长按放大。
                        if (change.isConsumed && !holdZoomConsuming && !transformMode) {
                            moved = true
                            state.lastTapTime = 0L
                            longPressJob?.cancel()
                            continue
                        }

                        if (holdZoomConsuming) {
                            // 长按放大/长按面板期间消费全部指针（多指同持不漏事件）
                            event.changes.forEach { c ->
                                val d = c.positionChange()
                                if (d != Offset.Zero) {
                                    if (holdZoomFired) state.panBy(d)
                                    c.consume()
                                }
                            }
                            continue
                        }

                        if (transformMode) {
                            // 双指抬掉一指后的剩余手势仍由本手势持有到底：
                            // 不消费会把同一手势的后半段交给 Pager 突然翻页
                            val d = change.positionChange()
                            if (d != Offset.Zero) {
                                if (state.isZoomed) state.panBy(d)
                                change.consume()
                            }
                            continue
                        }

                        val delta = change.positionChange()
                        totalDragX += delta.x
                        totalDragY += delta.y
                        if (!moved && (abs(totalDragX) > slop || abs(totalDragY) > slop)) {
                            moved = true
                            state.lastTapTime = 0L
                            longPressJob?.cancel()
                        }

                        // QuickZoom 判定窗口（第二击按下后、竖/横主导判定前）消费事件：
                        // 否则 TTB VerticalPager 会先吃掉头几 px 竖直位移导致页面抢跑滚动
                        if (quickZoomCandidate && !quickZoomActive) {
                            if (delta != Offset.Zero) change.consume()
                        }

                        // QuickZoom 判定：竖直方向先过 slop 进入；水平主导则放弃（交给翻页）
                        if (quickZoomCandidate && !quickZoomActive && moved) {
                            if (abs(totalDragY) > slop * 1.2f && abs(totalDragY) > abs(totalDragX)) {
                                quickZoomActive = true
                                longPressJob?.cancel()
                            } else if (abs(totalDragX) > abs(totalDragY)) {
                                quickZoomCandidate = false
                            }
                        }
                        if (quickZoomActive) {
                            // telephoto：向下拖放大，每 px +0.4%，焦点锁定双击点
                            if (delta.y != 0f) {
                                state.updateTransform(
                                    zoom = kotlin.math.exp(delta.y * 0.004f),
                                    pan = Offset.Zero,
                                    focal = down.position,
                                )
                            }
                            change.consume()
                            continue
                        }

                        // 旧版放大边缘翻页回调：仅磁吸/卷页模式需要（它们没有 Pager
                        // 接管 yield 交还的手势）；普通分页模式下方向仲裁已把边缘手势
                        // 交还 Pager 自然拖动翻页，两机制并存会与 Pager 竞争导致页码失步
                        val edgeTurnMode = latestConfig.mode == ComicMode.MAGNETIC ||
                            latestConfig.pageAnim == ComicPageAnim.CURL
                        if (edgeTurnMode && state.isZoomed && moved && latestConfig.zoomWhileTurn && !edgeSwipeFired) {
                            // 仅水平主导且有实际水平位移才判方向（纯竖直平移不触发）；
                            // 每次手势至多触发一次（防止边缘连拖一帧翻一页）；
                            // 触发需到边后累计再拖 12dp（单帧 0.5px 抖动误触）
                            val dx = delta.x
                            if (abs(dx) > 0.5f && abs(dx) > abs(delta.y)) {
                                val dir = if (dx > 0) 1 else -1
                                if (state.horizontalPanEdge(dir)) {
                                    edgeSwipeAccum += abs(dx)
                                    if (edgeSwipeAccum > 12.dp.toPx()) {
                                        edgeSwipeFired = true
                                        latestCallbacks.onZoomedEdgeSwipe?.invoke(dir)
                                        change.consume()
                                        continue
                                    }
                                }
                            }
                        }

                        if (state.isZoomed && moved) {
                            // 方向感知平移仲裁（修复 FIT_HEIGHT/FILL 布局滑动翻页失效）：
                            // 该方向无平移余量、或已拖到边界 → 翻页主轴此帧零位移且不消费，
                            // 手势自然交还 Pager（平移→翻页无跳变过渡）；非翻页主轴保持
                            // rubberPan 阻尼漂移（无接管者时仍有到边橡皮手感，松手回弹）。
                            // 注意"该轴完全无余量"时翻页主轴也必须交还（FIT_WIDTH 竖向溢出时
                            // lx=0，继续消费会让水平翻页永久失效）
                            val lx = state.panLimitX()
                            val ly = state.panLimitY()
                            val realZoom = state.scale > 1.02f
                            val pageAxisIsX = latestConfig.direction != ComicDirection.TTB
                            val atHEdge = lx > 0.5f && (
                                (delta.x > 0 && state.offsetX >= lx - 0.5f) ||
                                    (delta.x < 0 && state.offsetX <= -lx + 0.5f)
                                )
                            val atVEdge = ly > 0.5f && (
                                (delta.y > 0 && state.offsetY >= ly - 0.5f) ||
                                    (delta.y < 0 && state.offsetY <= -ly + 0.5f)
                                )
                            // 放大态到边是否交还翻页由 zoomWhileTurn 决定；
                            // 未放大(布局溢出)时到边/无余量必须交还
                            val yieldH = (atHEdge || lx <= 0.5f) && (latestConfig.zoomWhileTurn || !realZoom)
                            val yieldV = (atVEdge || ly <= 0.5f) && (latestConfig.zoomWhileTurn || !realZoom)
                            if (lx > 0.5f || ly > 0.5f) {
                                // yield 的翻页主轴零位移（内容定住，页动）；非翻页主轴仍阻尼跟手
                                val pX = if (yieldH && pageAxisIsX) 0f else delta.x
                                val pY = if (yieldV && !pageAxisIsX) 0f else delta.y
                                if (pX != 0f || pY != 0f) state.panBy(Offset(pX, pY))
                                // 任一轴真正接管了位移即消费（防 Pager 与平移双响应）；
                                // 仅当"消费位移为零"或"翻页主轴 yield"时不消费交还
                                val consumedSome = (pX != 0f || pY != 0f) &&
                                    !((yieldH && pageAxisIsX) || (yieldV && !pageAxisIsX))
                                if (consumedSome) change.consume()
                            }
                            // 两轴均无余量（普通 fit 未放大）：不消费 → Pager 翻页
                        }
                        // 未放大时不消费 → pager 翻页
                    }
                } finally {
                    longPressJob?.cancel()
                    state.gestureActive = false
                    state.gestureEpoch += 1
                    val wasQuickZoom = quickZoomActive
                    val wasTransform = transformMode
                    val panVelocity = runCatching { velocityTracker.calculateVelocity() }
                        .getOrNull()?.let { Offset(it.x, it.y) } ?: Offset.Zero
                    // 松手动画包装：releaseAnimating 是 snapshot 可观察的"动画进行中"信号，
                    // 开始置 true、结束（含取消）置 false——空闲等待者（hi-res 重解码）依赖它唤醒
                    fun launchRelease(block: suspend () -> Unit) {
                        state.releaseAnimating = true
                        state.releaseJob = scope.launch {
                            try {
                                block()
                            } finally {
                                state.releaseAnimating = false
                            }
                        }
                    }
                    if (holdZoomFired) {
                        state.holdZoomActive = false
                        launchRelease { state.animateReset() }
                    } else if (wasQuickZoom || wasTransform) {
                        // QuickZoom / 双指结束：越界回弹；双指平移带速度松手同样给惯性
                        // （此前 transform 结束直接 settle，双指拖动的惯性被丢弃）
                        launchRelease {
                            if (wasTransform && state.isZoomed) {
                                val flew = state.fling(panVelocity, this@pointerInput)
                                if (!flew) state.settle()
                            } else {
                                state.settle()
                            }
                        }
                    } else if (state.isZoomed && moved) {
                        // 放大态平移松手：先尝试惯性，速度不足或不可平移则回弹
                        launchRelease {
                            val flew = state.fling(panVelocity, this@pointerInput)
                            if (!flew) state.settle()
                        }
                    } else {
                        state.releaseJob = null
                        state.releaseAnimating = false
                    }
                }

                // 手指抬起：tap / double-tap 判定
                val pressDuration = gestureEndTime - downTime
                val tapEligible = !moved && !transformMode && !holdZoomConsuming && !quickZoomActive
                // 双击第二击按住较久后抬起仍应完成双击档位切换（tapMaxDuration 只约束单击）
                if (tapEligible && (pressDuration < tapMaxDuration || quickZoomCandidate)) {
                    val now = gestureEndTime
                    val isDouble = quickZoomCandidate ||
                        (state.lastTapTime > 0L && now - state.lastTapTime in 0..doubleTapWindow &&
                            (lastPos - state.lastTapPos).getDistance() < doubleTapRadius)
                    if (isDouble) {
                        state.pendingTapJob?.cancel()
                        state.lastTapTime = 0L
                        state.lastTapPos = Offset.Zero
                        if (latestConfig.doubleTapZoom) {
                            // 动态档位：1x → 填满 → 1:1 → …；已在高档则回 1x
                            state.releaseJob?.cancel()
                            state.releaseAnimating = true
                            state.releaseJob = scope.launch {
                                try {
                                    state.animateZoomTo(state.nextStepScale(), lastPos)
                                } finally {
                                    state.releaseAnimating = false
                                }
                            }
                        } else {
                            latestCallbacks.onTapZone(lastPos, Size(size.width.toFloat(), size.height.toFloat()))
                        }
                    } else {
                        state.lastTapTime = now
                        state.lastTapPos = lastPos
                        val tapPos = lastPos
                        val tapSize = Size(size.width.toFloat(), size.height.toFloat())
                        if (latestConfig.doubleTapZoom) {
                            state.pendingTapJob?.cancel()
                            state.pendingTapJob = scope.launch {
                                delay(doubleTapWindow)
                                latestCallbacks.onTapZone(tapPos, tapSize)
                            }
                        } else latestCallbacks.onTapZone(tapPos, tapSize)
                    }
                }
            }
        }
}

/**
 * 侧边滑动快速关闭：从屏幕左/右边缘向内滑动。挂在阅读器根部（Initial pass 抢先消费）。
 * [zoomed]：放大状态信号——放大平移时从屏幕边缘起手不应退出阅读器（手势归属缩放）。
 */
fun Modifier.comicEdgeSwipe(enabled: Boolean, zoomed: () -> Boolean = { false }, onTrigger: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val latestZoomed by rememberUpdatedState(zoomed)
        val latestTrigger by rememberUpdatedState(onTrigger)
        pointerInput(Unit) {
            val edge = 24.dp.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (latestZoomed() || down.isConsumed) return@awaitEachGesture
                if (down.position.x > edge && down.position.x < size.width - edge) return@awaitEachGesture
                val fromLeft = down.position.x <= edge
                var dx = 0f
                var dy = 0f
                var active = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1 || latestZoomed()) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // 放大态（双指/长按开始后）由缩放手势接管，边缘关闭让位
                    if (active && latestZoomed()) {
                        active = false
                        return@awaitEachGesture
                    }
                    dx += change.positionChange().x
                    dy += change.positionChange().y
                    val inward = if (fromLeft) dx > 0 else dx < 0
                    if (!active && abs(dx) > slop && inward && abs(dx) > abs(dy) * 1.5f) {
                        active = true
                    }
                    if (active) change.consume()
                }
                if (active && abs(dx) > 64.dp.toPx() && !latestZoomed()) latestTrigger()
            }
        }
    }
