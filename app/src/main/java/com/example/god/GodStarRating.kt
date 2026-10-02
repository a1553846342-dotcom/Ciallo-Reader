package com.example.god

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.ui.feedback.HapticsGate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 评分范围与步长（不允许 0 分：打了神回却零分自相矛盾）。 */
const val GOD_RATING_MIN = 0.5f
const val GOD_RATING_MAX = 5f
const val GOD_RATING_STEP = 0.5f

fun Float.coerceRating(): Float =
    ((this / GOD_RATING_STEP).roundToInt() * GOD_RATING_STEP)
        .coerceIn(GOD_RATING_MIN, GOD_RATING_MAX)

/**
 * 神回评分条：5 颗星、0.5 步长、点击 + 左右滑动划星。
 *
 * - 半星用 [clipRect] 只绘制左半边（避免两个 path 拼接的错位）；
 * - 每跨过一个半星步进触发一次轻触觉；
 * - 点亮的星弹性缩放 + 微光扩散，数值变化时撒几颗小金粒。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GodStarRating(
    rating: Float,
    onRatingChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 34.dp,
    starGap: Dp = 6.dp,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
    showScore: Boolean = true,
) {
    val view = LocalView.current
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    // 上一次触发触觉的步进值（避免同一步进反复震动）
    var lastHaptic by remember { mutableFloatStateOf(rating) }
    // 金粒爆发钥匙：评分变化时自增
    var sparkKey by remember { mutableStateOf(0) }
    var lastRating by remember { mutableFloatStateOf(rating) }
    LaunchedEffect(rating) {
        if (rating != lastRating) {
            lastRating = rating
            sparkKey++
        }
    }

    fun commit(raw: Float) {
        val v = raw.coerceRating()
        if (v == rating) return
        if (HapticsGate.enabled && v != lastHaptic) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        lastHaptic = v
        onRatingChange(v)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(starGap),
    ) {
        Box(
            modifier = Modifier
                .pointerInput(enabled, rating) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        if (w <= 0f) return@detectTapGestures
                        commit(((offset.x / w) * 5f).coerceIn(GOD_RATING_MIN, GOD_RATING_MAX))
                    }
                }
                .pointerInput(enabled, rating) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {},
                        onDragEnd = {},
                        onDragCancel = {},
                        onHorizontalDrag = { change, _ ->
                            val w = size.width.toFloat()
                            if (w > 0f) {
                                commit(((change.position.x / w) * 5f).coerceIn(GOD_RATING_MIN, GOD_RATING_MAX))
                            }
                        },
                    )
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(starGap)) {
                for (i in 1..5) {
                    val filled = (rating - (i - 1)).coerceIn(0f, 1f)
                    GodStar(
                        filled = filled,
                        gold = gold,
                        trackColor = trackColor,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.size(starSize),
                        sparkKey = if (filled >= 1f) sparkKey else -1,
                    )
                }
            }
        }

        if (showScore) {
            AnimatedContent(
                targetState = rating,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    } else {
                        (slideInVertically(tween(260)) { -it } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(tween(260)) { it } + fadeOut(tween(180)))
                    }.using(SizeTransform(clip = false))
                },
                label = "godScore",
            ) { value ->
                Text(
                    text = String.format(java.util.Locale.ROOT, "%.1f", value),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dark) GodGold.DarkMid else GodGold.LightEnd,
                )
            }
        }
    }
}

/** 单颗星：底轨 + 金色填充（半星用 clipRect 只画左半边）+ 弹性缩放 + 微光 + 金粒。 */
@Composable
private fun GodStar(
    filled: Float,
    gold: List<Color>,
    trackColor: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    sparkKey: Int = -1,
) {
    val target = if (filled > 0f) 1f else 0.82f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = GodMotion.springBouncy<Float>(),
        label = "starScale",
    )
    val glow by animateFloatAsState(
        targetValue = filled,
        animationSpec = GodMotion.springMain<Float>(),
        label = "starGlow",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = starPath(w * 0.5f * scale, Offset(w / 2f, h / 2f))

            // 未点亮底轨
            drawPath(path, trackColor)

            // 点亮部分（半星 = 只裁左半边）
            if (filled > 0f) {
                // 微光扩散
                if (glow > 0.02f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(gold.first().copy(alpha = 0.34f * glow), Color.Transparent),
                            center = Offset(w / 2f, h / 2f),
                            radius = w * 0.95f,
                        ),
                        radius = w * 0.95f,
                        center = Offset(w / 2f, h / 2f),
                    )
                }
                clipRect(right = w * filled.coerceIn(0f, 1f)) {
                    drawPath(
                        path,
                        Brush.linearGradient(
                            gold,
                            start = Offset(0f, 0f),
                            end = Offset(w, h),
                        ),
                    )
                }
            }
            // 细描边（深色模式下让星形边界更清晰）
            drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
        }

        // 金粒：点亮瞬间撒几颗（一次性动画，非常驻）
        if (sparkKey >= 0 && !reduceMotion) {
            GodSparks(key = sparkKey, color = gold.first())
        }
    }
}

/** 小金粒：从星星中心向外扩散并淡出（约 520ms，非无限循环）。 */
@Composable
private fun GodSparks(key: Int, color: Color) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(key) {
        progress = 0f
        androidx.compose.animation.core.animate(
            0f, 1f,
            animationSpec = tween(520),
        ) { value, _ -> progress = value }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (progress <= 0f || progress >= 1f) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (i in 0 until 6) {
            val angle = (-PI / 2) + i * (2 * PI / 6)
            val dist = size.width * 0.55f * progress
            val alpha = (1f - progress).coerceIn(0f, 1f) * 0.9f
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = size.width * 0.055f * (1f - progress * 0.5f),
                center = Offset(cx + (cos(angle) * dist).toFloat(), cy + (sin(angle) * dist).toFloat()),
            )
        }
    }
}

/** 五角星路径（外半径 R，内半径 0.46R，起始角 -90°）。 */
private fun starPath(radius: Float, center: Offset): Path {
    val inner = radius * 0.46f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else inner
        val angle = (-PI / 2) + i * (PI / 5)
        val x = center.x + (cos(angle) * r).toFloat()
        val y = center.y + (sin(angle) * r).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** 只读的小星级（榜单/卡片用）。 */
@Composable
fun GodStarsReadonly(
    rating: Float,
    modifier: Modifier = Modifier,
    starSize: Dp = 14.dp,
    starGap: Dp = 2.dp,
) {
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(starGap),
    ) {
        for (i in 1..5) {
            val filled = (rating - (i - 1)).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.size(starSize)) {
                val w = size.width
                val h = size.height
                val path = starPath(min(w, h) * 0.5f, Offset(w / 2f, h / 2f))
                drawPath(path, track)
                if (filled > 0f) {
                    clipRect(right = w * filled) {
                        drawPath(
                            path,
                            Brush.linearGradient(gold, start = Offset(0f, 0f), end = Offset(w, h)),
                        )
                    }
                }
            }
        }
    }
}
