package com.example.god

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 书籍详情页 · 神回态章节卡片。
 *
 * - 背景 = 该神回的合成封面（本身已含"居中原图 + 模糊铺底"）→ ContentScale.Crop 铺满，
 *   再叠一层半透明渐变保证文字可读；
 * - 金色流光描边：绕卡片旋转的渐变描边（[GodMotion.BORDER_SWEEP_MS] 一圈，
 *   仅在卡片可见时随组合存在；系统"移除动画"时退化为静态描边）；
 * - 右上角「神回 ✦」徽章；点击进入阅读，长按提供「编辑神回」；
 * - 从排行榜跳转过来时 [highlighted] = true → 播放一次金色高亮脉冲。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GodChapterCard(
    moment: GodMomentEntity,
    chapterTitle: String,
    subtitle: String?,
    highlighted: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)
    val shape = RoundedCornerShape(GodMotion.CARD_CORNER.dp)

    /* 高亮脉冲：排行榜跳转定位后播一次 */
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        pulse = true
        delay(900)
        pulse = false
    }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (pulse) 0.42f else 0f,
        animationSpec = tween(if (pulse) 240 else 520),
        label = "godPulse",
    )

    /* 流光描边角度 */
    val infinite = rememberInfiniteTransition(label = "godBorder")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(GodMotion.BORDER_SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val angle = if (reduceMotion) 0f else sweep

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "阅读",
                onLongClickLabel = "编辑神回",
            )
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // 封面底图
        if (!moment.coverPath.isNullOrBlank()) {
            AsyncImage(
                model = java.io.File(moment.coverPath!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(gold)),
            )
        }
        // 可读性渐变
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xCC0B0C0E),
                            Color(0x660B0C0E),
                            Color(0x220B0C0E),
                        ),
                    ),
                ),
        )
        // 高亮脉冲
        if (pulseAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gold.first().copy(alpha = pulseAlpha)),
            )
        }

        // 流光描边（旋转的渐变描边）
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    val a = Math.toRadians(angle.toDouble())
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = maxOf(size.width, size.height)
                    val sx = cx + (cos(a) * r).toFloat()
                    val sy = cy + (sin(a) * r).toFloat()
                    val ex = cx - (cos(a) * r).toFloat()
                    val ey = cy - (sin(a) * r).toFloat()
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                GodGold.LightStart.copy(alpha = 0.9f),
                                GodGold.LightMid,
                                GodGold.LightStart.copy(alpha = 0.9f),
                                Color.Transparent,
                            ),
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            GodMotion.CARD_CORNER.dp.toPx(), GodMotion.CARD_CORNER.dp.toPx(),
                        ),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                },
        )

        // 文本
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, end = 92.dp),
        ) {
            Text(
                text = moment.effectiveTitle(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = chapterTitle.ifBlank { "第${moment.chapterNumber}话" },
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GodStarsReadonly(rating = moment.rating, starSize = 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = String.format(java.util.Locale.ROOT, "%.1f", moment.rating),
                    color = GodGold.LightStart,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 右上徽章
        GodBadge(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )
    }
}

/** 「神回 ✦」小徽章。 */
@Composable
fun GodBadge(modifier: Modifier = Modifier) {
    val dark = godIsDark()
    val gold = GodGold.goldGradient(dark)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(gold))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("神回", color = Color(0xFF3B2A08), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("✦", color = Color(0xFF3B2A08), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
