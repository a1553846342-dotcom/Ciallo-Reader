package com.example.god

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.max

/* ══════════════ 陀螺仪视差 ══════════════ */

/**
 * 设备倾斜（度，已低通滤波并夹紧）。
 *
 * 字段是 snapshot state：**只能在 graphicsLayer / draw 阶段读取** ——
 * 这样传感器每帧更新只失效绘制，不会整块排行榜重组。
 */
class GodTilt {
    var x: Float by mutableFloatStateOf(0f)
        internal set
    var y: Float by mutableFloatStateOf(0f)
        internal set
}

/** 设备是否支持旋转向量传感器（不支持时 UI 隐藏陀螺仪开关）。 */
fun Context.hasRotationSensor(): Boolean =
    (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
        ?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

/**
 * 旋转向量传感器 → 倾斜角（低通滤波，幅度 ≤ [maxDeg] 度）。
 * 用于排行榜的景深视差：封面与光环做反向位移。
 */
@Composable
fun rememberGodTilt(enabled: Boolean, maxDeg: Float = 8f): GodTilt {
    val context = LocalContext.current
    val state = remember { GodTilt() }
    if (!enabled) return state
    DisposableEffect(enabled, maxDeg) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var lastX = 0f
        var lastY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val rawY = Math.toDegrees(orientation[1].toDouble()).toFloat()  // pitch
                val rawX = Math.toDegrees(orientation[2].toDouble()).toFloat()  // roll
                // 一阶低通（α=0.15）：去掉手抖，保留缓慢的倾斜意图
                lastX += 0.15f * (rawX - lastX)
                lastY += 0.15f * (rawY - lastY)
                state.x = lastX.coerceIn(-maxDeg, maxDeg)
                state.y = lastY.coerceIn(-maxDeg, maxDeg)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

/* ══════════════ 封面加载（缓存 key 含 updatedAt） ══════════════ */

/**
 * 神回封面。
 * 缓存 key 含 [GodMomentEntity.updatedAt]：编辑保存后 key 变化，
 * 图片加载库会重新解码，不会显示旧封面。
 */
@Composable
fun GodCoverImage(
    moment: GodMomentEntity,
    modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
) {
    val context = LocalContext.current
    val path = moment.coverPath
    Box(modifier = modifier) {
        // 本地封面也可能需要解码；等待或解码失败时保留明确的占位，避免整块留白。
        GodCoverPlaceholder(Modifier.fillMaxSize())
        if (!path.isNullOrBlank()) {
            val model = remember(path, moment.updatedAt) {
                ImageRequest.Builder(context)
                    .data(java.io.File(path))
                    .memoryCacheKey("god_cover_${path}_${moment.updatedAt}")
                    .diskCacheKey("god_cover_${path}_${moment.updatedAt}")
                    .crossfade(220)
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = moment.effectiveTitle(),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GodCoverPlaceholder(modifier: Modifier = Modifier) {
    // 中性底色和极轻的暖调，避免领奖台并排封面抢过内容本身。
    val dark = godIsDark()
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
                .background(
                    Brush.verticalGradient(
                        listOf(base.copy(alpha = if (dark) 0.55f else 0.85f), base),
                    ),
                )
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            listOf(GodGold.LightMid.copy(alpha = 0.20f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.42f),
                            radius = size.maxDimension * 0.6f,
                        ),
                    )
                }
                .border(
                    width = 1.dp,
                    color = GodGold.LightMid.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(12.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✦",
            fontSize = 22.sp,
            color = GodGold.LightEnd.copy(alpha = 0.65f),
        )
    }
}

/* ══════════════ 长按浮层菜单（编辑 / 删除） ══════════════ */

/**
 * 长按封面后的小浮层菜单：仅「编辑」「删除」两项，带触觉反馈。
 * 用 Surface 自绘（不打断当前页面的 SharedTransition 层级）。
 */
@Composable
fun GodItemMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            GodMenuItem(text = "编辑", onClick = onEdit)
            GodMenuItem(text = "删除", danger = true, onClick = onDelete)
            GodMenuItem(text = "取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun GodMenuItem(text: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 42.dp)
            .clickableItem(onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/* ══════════════ 空状态 ══════════════ */

/**
 * 神回空状态：手绘风小插画（Compose 绘制）+ 轻微浮动。
 * 插画 = 一页卷起的漫画 + 三颗小星，笔触用圆头描边模拟手绘。
 */
@Composable
fun GodEmptyState(
    modifier: Modifier = Modifier,
    message: String = "还没有神回，读完一话向后滑试试",
    reduceMotion: Boolean = false,
) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "emptyFloat")
    val float by infinite.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween<Float>(
                2600,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "float",
    )
    val gold = GodGold.goldGradient(godIsDark())
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Column(
        modifier = modifier.padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .width(108.dp)
                .height(84.dp)
                .graphicsLayer { translationY = if (reduceMotion) 0f else float * 3f },
        ) {
            val w = size.width
            val h = size.height
            // 一页卷起的纸
            val pageW = w * 0.56f
            val pageH = h * 0.66f
            val left = (w - pageW) / 2f
            val top = (h - pageH) / 2f
            val stroke = Stroke(width = 3.2.dp.toPx())
            drawRoundRect(
                color = ink,
                topLeft = Offset(left, top),
                size = Size(pageW, pageH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = stroke,
            )
            // 卷角
            drawLine(
                color = ink,
                start = Offset(left + pageW, top + pageH * 0.62f),
                end = Offset(left + pageW * 0.74f, top + pageH),
                strokeWidth = 3.2.dp.toPx(),
            )
            // 三颗小星（手绘感：略随机大小）
            val stars = listOf(
                Offset(w * 0.16f, h * 0.22f) to 5.5f,
                Offset(w * 0.86f, h * 0.34f) to 4.2f,
                Offset(w * 0.74f, h * 0.78f) to 6.4f,
            )
            stars.forEach { (c, r) ->
                drawCircle(gold.first(), radius = r.dp.toPx(), center = c)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 名次圆章（第 4 名及以后的小号序号）。 */
@Composable
fun GodRankStamp(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(50),
            )
            .border(1.dp, GodGold.LightMid.copy(alpha = 0.6f), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GodGold.LightEnd)
    }
}

/** 给长图 / 普通图算"居中裁切"的辅助（黑胶中心标签用）。 */
fun centerCropRatio(w: Int, h: Int): Float = if (h > 0) w.toFloat() / h else 1f

/** 稳定随机：由 id 决定的固定值，保证重组后不跳变。 */
fun stableRandom(id: Long, salt: Int = 0): Float {
    var x = id * 31L + salt * 7919L
    x = x xor (x shr 13)
    x = x * 0x5DEECE66DL
    x = x xor (x shr 17)
    val v = abs((x % 10000L).toInt()) / 10000f
    return max(0f, v.coerceAtMost(1f))
}

/** 稳定倾角：-6° ~ +6°，由 id 决定。 */
fun stableTiltDeg(id: Long, salt: Int = 0): Float = (stableRandom(id, salt) * 12f) - 6f
