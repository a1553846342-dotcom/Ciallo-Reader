package com.example.god

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.GlassCard

/**
 * 阅读统计页 · 神回排行榜卡片。
 *
 * 头部：标题 + 总数 + 「查看全部」；卡片内展示前 10 项预览，固定按评分排序。
 * 切换展示风格时用交叉淡入 + 轻微缩放过渡。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GodRankingCard(
    moments: List<GodMomentEntity>,
    style: GodRankingStyle,
    gyroEnabled: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    onOpenAll: () -> Unit,
    onItemClick: (GodMomentEntity) -> Unit,
    onEdit: (GodMomentEntity) -> Unit,
    onDelete: (GodMomentEntity) -> Unit,
) {
    // 固定按评分排序（排行榜本义）；曾经有「评分/最近添加/书籍」三个分段 tab，
    // 用户定稿删掉——一个排行榜不需要三套排序。
    val ranked = remember(moments) {
        moments.sortedForRanking(GodSort.RATING).mapIndexed { i, e -> GodMomentItem(e, i + 1) }
    }
    val preview = remember(ranked) { ranked.take(10) }

    GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            /* ── 头部 ── */
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题走 titleLarge 槽位（17sp/负字距），不再手写 16sp+Bold
                Text(
                    text = "神回排行榜",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                GodCountPill(count = moments.size)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenAll) {
                    Text("查看全部", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(14.dp))

            if (preview.isEmpty()) {
                GodEmptyState(modifier = Modifier.fillMaxWidth(), reduceMotion = reduceMotion)
            } else {
                // 风格切换：交叉淡入 + 轻微缩放
                AnimatedContent(
                    targetState = style,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                        } else {
                            (fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.97f)) togetherWith
                                (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 1.03f))
                        }
                    },
                    label = "rankingStyle",
                ) { target ->
                    GodRankingBody(
                        items = preview,
                        style = target,
                        compact = true,
                        gyroEnabled = gyroEnabled,
                        reduceMotion = reduceMotion,
                        onItemClick = onItemClick,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 神回总数小徽章。
 *
 * 实心金块在玻璃卡上会显得"贴上去的"，这里按亚克力小控件的做法处理：
 * 金调渐变打底 + 顶部高光带 + 一圈半透明白描边（模拟金属边的折光），
 * 数字用等宽感更强的 labelSmall 并把字距放开到 +0.3，避免小字号挤在一起。
 */
@Composable
private fun GodCountPill(count: Int) {
    val dark = godIsDark()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Brush.horizontalGradient(GodGold.goldGradient(dark)))
            .drawBehind {
                // 顶部受光带（与 acrylicPanel 顶棱同源）
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    )
                )
            }
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (dark) 0.18f else 0.32f),
                shape = shape,
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF3B2A08),
        )
    }
}

/**
 * 神回排行榜全屏页：与统计页卡片共享同一份数据、同一套交互，
 * 只是展示层换成完整版（不做 10 项截断）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun GodRankingScreen(
    moments: List<GodMomentEntity>,
    style: GodRankingStyle,
    gyroEnabled: Boolean,
    onBack: () -> Unit,
    onItemClick: (GodMomentEntity) -> Unit,
    onEdit: (GodMomentEntity) -> Unit,
    onDelete: (GodMomentEntity) -> Unit,
    onOpenStyleSettings: (() -> Unit)? = null,
) {
    // 固定按评分排序，与统计页卡一致（分段排序 tab 已按用户要求移除）
    val ranked = remember(moments) {
        moments.sortedForRanking(GodSort.RATING).mapIndexed { i, e -> GodMomentItem(e, i + 1) }
    }
    val reduce = rememberReduceMotion()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "神回排行榜",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.width(8.dp))
                        GodCountPill(count = moments.size)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (ranked.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                GodEmptyState(reduceMotion = reduce)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                GodRankingHero(
                    leader = ranked.first().entity,
                    count = ranked.size,
                )
            }
            item(key = "style") {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    onClick = onOpenStyleSettings,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("陈列方式", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(style.label, color = GodGold.LightEnd, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (onOpenStyleSettings != null) {
                            Spacer(Modifier.width(5.dp))
                            Text("↗", color = GodGold.LightEnd, fontSize = 13.sp)
                        }
                    }
                }
            }
            item(key = "body") {
                AnimatedContent(
                    targetState = style,
                    transitionSpec = {
                        if (reduce) fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                        else (fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.97f)) togetherWith
                            (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 1.03f))
                    },
                    label = "rankingStyleFull",
                ) { target ->
                    GodRankingBody(
                        items = ranked,
                        style = target,
                        compact = false,
                        gyroEnabled = gyroEnabled,
                        reduceMotion = reduce,
                        onItemClick = onItemClick,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 排行榜卷首：把第一名与收藏规模放进同一张展签，列表仍可完整浏览。 */
@Composable
private fun GodRankingHero(
    leader: GodMomentEntity,
    count: Int,
) {
    val dark = godIsDark()
    val ink = if (dark) Color(0xFF171712) else Color(0xFF292315)
    val gold = GodGold.goldGradient(dark)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 192.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(ink, ink.copy(red = 0.23f, green = 0.20f, blue = 0.13f))))
            .border(1.dp, gold[0].copy(alpha = 0.38f), RoundedCornerShape(28.dp)),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.90f
            val cy = size.height * 0.30f
            for (i in 0..3) {
                drawCircle(
                    color = gold[0].copy(alpha = 0.14f - i * 0.026f),
                    radius = (58 + i * 33).dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(gold[1].copy(alpha = 0.18f), 82.dp.toPx(), Offset(cx, cy))
        }
        Column(Modifier.padding(22.dp)) {
            Text("THE MOMENTS / 精选记录", color = gold[0], fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(11.dp))
            Text(
                "值得重读的，\n都在这里。",
                color = Color(0xFFF9F2E2),
                fontSize = 27.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "${count} 个高光时刻  ·  最高 ${leader.rating} 分",
                color = Color(0xFFE6D5B0),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "榜首  ${leader.effectiveTitle()}",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}
