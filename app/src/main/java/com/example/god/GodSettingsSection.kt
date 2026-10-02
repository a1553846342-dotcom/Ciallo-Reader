package com.example.god

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AppSwitch
import kotlinx.coroutines.launch

/**
 * 设置页「神回」分组。
 *
 * - 排行榜展示风格：三选一，每张卡片都是该风格的**小型示意（Compose 现画）**，
 *   选中时弹簧缩放 + 描边动画；
 * - 排行榜陀螺仪视差开关（默认开；设备无旋转向量传感器时整行隐藏）。
 */
@Composable
fun GodSettingsSection(
    store: GodMomentSettingsStore,
    modifier: Modifier = Modifier,
    onOpenRanking: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasGyro = remember(context) { context.hasRotationSensor() }

    val style by store.rankingStyle.collectAsStateWithLifecycle(initialValue = GodRankingStyle.PODIUM)
    val gyro by store.gyroParallaxEnabled.collectAsStateWithLifecycle(initialValue = true)
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "神回",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        Spacer(Modifier.height(8.dp))

        com.example.ui.components.GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "神回排行榜展示风格",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GodRankingStyle.entries.forEach { s ->
                        GodStyleCard(
                            style = s,
                            selected = style == s,
                            gold = gold,
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { store.setRankingStyle(s) } },
                        )
                    }
                }

                if (hasGyro) {
                    GodSettingSwitchRow(
                        title = "排行榜陀螺仪视差",
                        desc = "倾斜手机时封面与光环反向位移，形成景深",
                        checked = gyro,
                        onCheckedChange = { scope.launch { store.setGyroParallaxEnabled(it) } },
                    )
                }
                if (onOpenRanking != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f))
                            .clickable { onOpenRanking() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "查看神回排行榜 ✦",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (dark) GodGold.DarkMid else GodGold.LightEnd,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GodSettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 风格单选卡片：上半部分是该风格的小型示意（Compose 现画，不引资源），
 * 选中有弹簧缩放 + 描边。
 */
@Composable
private fun GodStyleCard(
    style: GodRankingStyle,
    selected: Boolean,
    gold: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = GodMotion.springMain<Float>(),
        label = "styleScale",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 0.95f else 0.12f,
        animationSpec = GodMotion.springMain<Float>(),
        label = "styleBorder",
    )
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.55f else 0.32f)

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(surface)
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                brush = if (selected) Brush.horizontalGradient(gold)
                else Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = borderAlpha),
                        MaterialTheme.colorScheme.onSurface.copy(alpha = borderAlpha),
                    ),
                ),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(width = 62.dp, height = 46.dp)) {
            when (style) {
                GodRankingStyle.PODIUM -> {
                    // 三个台阶 + 中间最高
                    val w = size.width
                    val h = size.height
                    val bw = w * 0.22f
                    drawRoundRectStyleStep(w * 0.12f, h * 0.62f, bw, h * 0.38f, gold[1])
                    drawRoundRectStyleStep(w * 0.39f, h * 0.42f, bw, h * 0.58f, gold[0])
                    drawRoundRectStyleStep(w * 0.66f, h * 0.70f, bw, h * 0.30f, gold[2])
                }
                GodRankingStyle.VINYL_SHELF -> {
                    val w = size.width
                    val h = size.height
                    // 三张唱片套 + 一张抽出的黑胶
                    drawRoundRectStyleStep(w * 0.06f, h * 0.24f, w * 0.16f, h * 0.62f, Color(0xFF5A5F6B))
                    drawRoundRectStyleStep(w * 0.30f, h * 0.18f, w * 0.18f, h * 0.68f, gold[0])
                    drawRoundRectStyleStep(w * 0.58f, h * 0.24f, w * 0.16f, h * 0.62f, Color(0xFF5A5F6B))
                    drawCircle(Color(0xFF121316), radius = h * 0.24f, center = Offset(w * 0.84f, h * 0.52f))
                    drawCircle(gold[0], radius = h * 0.08f, center = Offset(w * 0.84f, h * 0.52f))
                    // 搁板
                    drawRect(Color(0x33666666), topLeft = Offset(0f, h * 0.88f), size = Size(w, h * 0.08f))
                }
                GodRankingStyle.POLAROID_WALL -> {
                    val w = size.width
                    val h = size.height
                    // 麻绳
                    val path = Path().apply {
                        moveTo(0f, h * 0.16f)
                        quadraticBezierTo(w / 2f, h * 0.34f, w, h * 0.16f)
                    }
                    drawPath(path, Color(0xFFB79B72), style = Stroke(width = 2f))
                    // 两张拍立得
                    drawRoundRectStyleStep(w * 0.10f, h * 0.30f, w * 0.32f, h * 0.52f, Color.White)
                    drawRoundRectStyleStep(w * 0.52f, h * 0.34f, w * 0.32f, h * 0.52f, Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = style.label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) gold[0] else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectStyleStep(
    x: Float, y: Float, w: Float, h: Float, color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
    )
}
