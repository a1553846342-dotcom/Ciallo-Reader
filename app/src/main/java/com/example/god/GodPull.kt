package com.example.god

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.feedback.HapticsGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 拉拽方向：决定光晕出现在哪条边。 */
enum class GodPullEdge { LEFT, RIGHT, TOP, BOTTOM }

/**
 * 「神回」拉拽状态机（翻页模式与条漫模式共用）。
 *
 * 阻尼：iOS UIScrollView 的橡胶带公式 `d = (1 - 1/(raw·c/span + 1))·(span/c)`，
 * c=0.55 —— 越拉越费力，永远不会拉出屏幕。
 *
 * 触发：阻尼后位移 ≥ 阈值（默认 96dp 等效）→ armed，过阈瞬间给一次强触觉；
 * 松手时 armed 则回调 onTriggered，否则弹簧回弹（与原有末页回弹完全一致）。
 */
@Stable
class GodPullState internal constructor(
    private val scope: CoroutineScope,
    /** 阻尼基准长度（屏幕宽或高，px） */
    var spanPx: Float,
) {
    /** 阻尼后的位移（px，≥0）；在 graphicsLayer lambda 里读，不引起重组 */
    val offset = Animatable(0f)

    /** 是否已过阈值（过阈瞬间触发一次强触觉） */
    var armed by mutableStateOf(false)
        private set

    /** 触发阈值（px） */
    var thresholdPx: Float = 1f

    /** 过阈回调（触觉） */
    internal var onArm: (() -> Unit)? = null

    /** 触发回调（松手过阈时调用；由 rememberGodPullState 随重组刷新） */
    internal var onTrigger: (() -> Unit)? = null

    private var raw: Float = 0f

    /** 0..1 进度（圆环用） */
    val progress: Float get() = if (thresholdPx <= 0f) 0f else (offset.value / thresholdPx).coerceIn(0f, 1f)

    private fun damp(rawPx: Float): Float {
        if (spanPx <= 0f) return 0f
        val c = GodMotion.PULL_DAMPING
        val maxY = spanPx / c
        return (1f - 1f / (rawPx * c / spanPx + 1f)) * maxY
    }

    /** 设置累计的原始越界量（px，≥0）。 */
    fun setRaw(rawPx: Float) {
        val next = rawPx.coerceAtLeast(0f)
        raw = next
        val d = damp(next)
        val nowArmed = d >= thresholdPx
        if (nowArmed && !armed) onArm?.invoke()
        armed = nowArmed
        scope.launch { offset.snapTo(d) }
    }

    fun addRaw(deltaPx: Float) = setRaw(raw + deltaPx)

    /** 松手：过阈就触发；随后弹簧回正。传 null 时用 [onTrigger]。 */
    fun release(onTriggered: (() -> Unit)? = null) {
        val wasArmed = armed
        armed = false
        raw = 0f
        if (wasArmed) (onTriggered ?: onTrigger)?.invoke()
        scope.launch { offset.animateTo(0f, GodMotion.springMain()) }
    }

    /** 直接归零（取消手势 / 页面切换） */
    fun reset() {
        armed = false
        raw = 0f
        scope.launch { offset.animateTo(0f, GodMotion.springMain()) }
    }
}

/**
 * 创建神回拉拽状态。
 * @param edgeIsVertical true = 纵向（条漫），false = 横向（翻页）
 */
@Composable
fun rememberGodPullState(
    edgeIsVertical: Boolean,
    threshold: Dp = GodMotion.PULL_THRESHOLD_DP.dp,
    onArmed: () -> Unit = {},
    onTriggered: () -> Unit,
): GodPullState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val latestTrigger by rememberUpdatedState(onTriggered)

    val state = remember(scope) {
        GodPullState(scope, 1f).apply {
            this.onArm = {
                if (HapticsGate.enabled) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
        }
    }
    val metrics = androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.runtime.SideEffect {
        val span = with(density) {
            val d = if (edgeIsVertical) metrics.screenHeightDp.dp else metrics.screenWidthDp.dp
            d.toPx()
        }
        state.spanPx = span
        state.thresholdPx = with(density) { threshold.toPx() }
    }
    // 触发回调随重组刷新（避免手势闭包捕获到旧的 lambda）
    state.onTrigger = latestTrigger
    return state
}

/**
 * 神回拉拽浮层：边缘渐进光晕 + 圆环进度 + 文案。
 * 全部走 Canvas / graphicsLayer lambda，不引起大范围重组。
 */
@Composable
fun GodPullOverlay(
    state: GodPullState,
    edge: GodPullEdge,
    modifier: Modifier = Modifier,
) {
    val p = state.progress
    val px = state.offset.value
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)
    val alpha by animateFloatAsState(
        targetValue = if (p > 0.01f) 1f else 0f,
        animationSpec = GodMotion.fast(),
        label = "pullAlpha",
    )
    // 系统关闭动画时，alpha 动画可能在首帧仍为 0；拉拽进度已非零就立即绘制反馈。
    if (p <= 0.01f && alpha <= 0.01f) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 边缘光晕
            val glow = Brush.radialGradient(
                listOf(gold.first().copy(alpha = 0.34f * p), Color.Transparent),
                center = when (edge) {
                    GodPullEdge.LEFT -> Offset(0f, size.height / 2f)
                    GodPullEdge.RIGHT -> Offset(size.width, size.height / 2f)
                    GodPullEdge.TOP -> Offset(size.width / 2f, 0f)
                    GodPullEdge.BOTTOM -> Offset(size.width / 2f, size.height)
                },
                radius = max(size.width, size.height) * 0.75f,
            )
            drawRect(glow)

            // 从被拉动的边缘向内生长的金色刻度，进度越高越亮。
            val edgeX = if (edge == GodPullEdge.LEFT) 0f else size.width
            val side = if (edge == GodPullEdge.LEFT) 1f else -1f
            if (edge == GodPullEdge.LEFT || edge == GodPullEdge.RIGHT) {
                val halfSpan = size.height * (0.12f + 0.32f * p)
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, gold[0].copy(alpha = 0.90f * p), Color.Transparent),
                        startY = size.height / 2f - halfSpan,
                        endY = size.height / 2f + halfSpan,
                    ),
                    start = Offset(edgeX + side * 3.dp.toPx(), size.height / 2f - halfSpan),
                    end = Offset(edgeX + side * 3.dp.toPx(), size.height / 2f + halfSpan),
                    strokeWidth = (2f + p * 5f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
                for (i in -3..3) {
                    val y = size.height / 2f + i * 34.dp.toPx()
                    val reach = (12f + 38f * p * (1f - abs(i) * 0.14f)).dp.toPx()
                    drawLine(
                        gold[0].copy(alpha = p * (0.40f + (3 - abs(i)) * 0.12f)),
                        Offset(edgeX, y), Offset(edgeX + side * reach, y),
                        strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }

            // 圆环进度（居中偏内，随拉拽位移轻微跟手）
            val r = min(size.width, size.height) * 0.13f
            val center = Offset(
                size.width / 2f,
                size.height / 2f + when (edge) {
                    GodPullEdge.BOTTOM -> -px * 0.18f
                    GodPullEdge.TOP -> px * 0.18f
                    else -> 0f
                },
            )
            drawCircle(Color.White.copy(alpha = 0.10f * alpha), radius = r * 1.25f, center = center)
            drawCircle(gold[0].copy(alpha = 0.10f * p), radius = r * (1.1f + 0.22f * p), center = center)
            drawCircle(
                gold[0].copy(alpha = 0.28f * p), radius = r * 1.42f, center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.28f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(gold + gold.first()),
                startAngle = -90f,
                sweepAngle = 360f * p,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val textShiftDp = with(LocalDensity.current) { (-px * 0.18f).toDp() }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = if (edge == GodPullEdge.BOTTOM) textShiftDp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.armed) "✦" else "◇",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = gold[0],
            )
            Text(
                text = if (state.armed) "松手，收藏这一刻" else "继续滑动",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = if (state.armed) "神回已就绪" else "标记为神回",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

/** 末页提示胶囊（淡入淡出，几秒后自动消失）。 */
@Composable
fun GodHintCapsule(
    visible: Boolean,
    text: String = "继续滑动 · 标记神回",
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = GodMotion.normal(),
        label = "hintAlpha",
    )
    if (alpha <= 0.01f) return
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        shape = RoundedCornerShape(50),
        color = Color(0xCC111318),
        contentColor = Color.White,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * 条漫 / 无缝滚动模式：用 [NestedScrollConnection] 接收列表**没能消费**的剩余量。
 *
 * - onPostScroll：手指继续上拉（available.y < 0）且列表已到底 → 累计进拉拽；
 * - onPreScroll：反向滚动先把拉拽收回，再交给列表；
 * - onPostFling：惯性结束 → 判定触发 / 弹簧回弹。
 */
fun godPullConnection(
    state: GodPullState,
    canPullForward: () -> Boolean,
    /**
     * 触发回调；**留空（null）才是最常用的用法** —— 此时松手走 [GodPullState.onTrigger]
     * （由 rememberGodPullState 随重组刷新的那个打开神回窗口的回调）。
     * ⚠️ 千万别随手塞一个 `{}`：release 的判空是 `onTriggered ?: onTrigger`，
     * 空 lambda 非 null，会把它当成"宿主自己处理"而**永远打不开神回窗口**。
     */
    onTriggered: (() -> Unit)? = null,
): NestedScrollConnection = object : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val cur = state.offset.value
        if (cur > 0.5f && available.y > 0f) {
            val consume = min(available.y, cur)
            // 反向消化：把已阻尼的位移按原路退回（近似用线性映射回 raw）
            state.setRaw(state.dampedToRaw((cur - consume).coerceAtLeast(0f)))
            return Offset(0f, consume)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!canPullForward()) return Offset.Zero
        val dy = available.y
        if (dy < 0f) {
            state.addRaw(-dy)
            return Offset(0f, dy)
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.offset.value > 0f) state.release(onTriggered)
        return Velocity.Zero
    }
}

/** 给滚动容器挂上神回拉拽（条漫模式）。 */
@Composable
fun Modifier.godPullScroll(
    state: GodPullState,
    canPullForward: () -> Boolean,
    onTriggered: (() -> Unit)? = null,
): Modifier {
    // 只以 state 为 key：canPullForward 每次重组都是新 lambda，进 key 会让
    // NestedScroll 连接被重建（条漫滚动中途重启 → 拉拽量清零，永远过不了阈值）
    val latestCanPull by rememberUpdatedState(canPullForward)
    val connection = remember(state) { godPullConnection(state, { latestCanPull() }, onTriggered) }
    return this.nestedScroll(connection)
}

/** 阻尼值 → 原始越界量（反向推导，用于 onPreScroll 回退）。 */
private fun GodPullState.dampedToRaw(damped: Float): Float {
    val c = GodMotion.PULL_DAMPING
    val span = spanPx
    if (span <= 0f) return 0f
    val ratio = (damped / (span / c)).coerceIn(0f, 0.999f)
    return (1f / (1f - ratio) - 1f) * span / c
}

/** 供阅读器判断"是否在末页"用的小工具。 */
fun isAtEnd(current: Int, count: Int): Boolean = count > 0 && current >= count - 1

/** 保留：绝对值工具（阅读器计算拉拽方向时避免符号错误）。 */
fun godAbs(f: Float): Float = abs(f)
