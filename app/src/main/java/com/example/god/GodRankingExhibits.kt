package com.example.god

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 三种陈列共用数据和交互，但每种都用独立、清晰的空间结构：
 * 编排式榜单 / 中央唱片陈列 / 双列相纸收藏墙。
 */

@Composable
internal fun GodPodiumStyle(
    items: List<GodMomentItem>,
    compact: Boolean,
    tilt: GodTilt,
    reduceMotion: Boolean,
    interactions: GodRankingInteractions,
    onItemClick: (GodMomentEntity) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GodPodiumTopThree(items, compact, tilt, reduceMotion, interactions, onItemClick)

        items.drop(3).forEach { item ->
            GodItemCard(
                moment = item.entity,
                reduceMotion = reduceMotion,
                interactions = interactions,
                onItemClick = onItemClick,
                modifier = Modifier.fillMaxWidth(),
            ) { _ ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${item.rank.toString().padStart(2, '0')}",
                        color = GodGold.LightEnd,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.entity.effectiveTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            item.entity.bookTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        if (item.entity.note.isNotBlank()) {
                            Text(
                                item.entity.note,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { interactions.noteFor = item.entity },
                            )
                        }
                    }
                    Text("${item.entity.rating} ★", color = GodGold.LightEnd, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 真正的高低台座：第二名 / 第一名 / 第三名，封面与名次跟随台座高度。 */
@Composable
private fun GodPodiumTopThree(
    items: List<GodMomentItem>,
    compact: Boolean,
    tilt: GodTilt,
    reduceMotion: Boolean,
    interactions: GodRankingInteractions,
    onItemClick: (GodMomentEntity) -> Unit,
) {
    val dark = godIsDark()
    val order = if (items.size >= 3) listOf(1, 0, 2) else items.indices.toList()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            order.forEach { index ->
                val item = items[index]
                val winner = item.rank == 1
                val accent = when (item.rank) {
                    1 -> Color(0xFFD6AC62)
                    2 -> Color(0xFFADB6C0)
                    else -> Color(0xFFC49A7A)
                }
                val stone = when (item.rank) {
                    1 -> if (dark) Color(0xFF4E412E) else Color(0xFFEBDFC5)
                    2 -> if (dark) Color(0xFF3C4148) else Color(0xFFE0E4E8)
                    else -> if (dark) Color(0xFF4B3D36) else Color(0xFFE8D9CE)
                }
                GodItemCard(
                    moment = item.entity,
                    reduceMotion = reduceMotion,
                    interactions = interactions,
                    onItemClick = onItemClick,
                    modifier = Modifier.weight(if (winner) 1.12f else 1f),
                ) { _ ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(if (winner) 105.dp else 88.dp)
                                .aspectRatio(GodCoverEngine.COVER_RATIO)
                                .graphicsLayer {
                                    translationX = if (reduceMotion) 0f else tilt.x * if (winner) 0.4f else 0.2f
                                }
                                .shadow(8.dp, RoundedCornerShape(9.dp))
                                .clip(RoundedCornerShape(9.dp)),
                        ) {
                            GodCoverImage(item.entity, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            item.entity.effectiveTitle(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = if (winner) 11.sp else 10.sp,
                            fontWeight = if (winner) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().height(30.dp),
                        )
                        Text(
                            "${item.entity.rating} ★",
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(
                                    when (item.rank) {
                                        1 -> if (compact) 76.dp else 98.dp
                                        2 -> if (compact) 50.dp else 66.dp
                                        else -> if (compact) 38.dp else 52.dp
                                    },
                                )
                                .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(stone, stone.copy(alpha = 0.76f)),
                                    ),
                                )
                                .border(
                                    1.dp,
                                    accent.copy(alpha = if (dark) 0.48f else 0.64f),
                                    RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${item.rank}",
                                color = if (dark) accent else Color(0xFF594936),
                                fontSize = if (winner) 30.sp else 24.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
            }
        }
        items.firstOrNull()?.entity?.takeIf { it.note.isNotBlank() }?.let { leader ->
            Text(
                leader.note,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
                    .clickable { interactions.noteFor = leader }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
internal fun GodVinylStyle(
    items: List<GodMomentItem>,
    compact: Boolean,
    tilt: GodTilt,
    reduceMotion: Boolean,
    interactions: GodRankingInteractions,
    onItemClick: (GodMomentEntity) -> Unit,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val selected = items[pager.currentPage.coerceIn(items.indices)]
    val sleeveWidth = if (compact) 152.dp else 190.dp
    val pageWidth = if (compact) 220.dp else 260.dp
    val exhibitHeight = if (compact) 228.dp else 282.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = ((maxWidth - pageWidth) / 2).coerceAtLeast(0.dp)
            HorizontalPager(
                state = pager,
                pageSize = PageSize.Fixed(pageWidth),
                contentPadding = PaddingValues(horizontal = side),
                modifier = Modifier.fillMaxWidth().height(exhibitHeight),
            ) { page ->
                val item = items[page]
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 唱片只露出套封的一侧；纹路是材质细节，静态且低对比度。
                    GodRecordDisc(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 9.dp)
                            .graphicsLayer { translationX = if (reduceMotion) 0f else tilt.x * 0.4f }
                            .size(if (compact) 116.dp else 148.dp),
                    )
                    GodItemCard(
                        moment = item.entity,
                        reduceMotion = reduceMotion,
                        interactions = interactions,
                        onItemClick = onItemClick,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .width(sleeveWidth)
                            .aspectRatio(GodCoverEngine.COVER_RATIO)
                            .shadow(10.dp, RoundedCornerShape(11.dp)),
                        onTap = {
                            if (page == pager.currentPage) onItemClick(item.entity)
                            else scope.launch { pager.animateScrollToPage(page) }
                        },
                    ) { _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(11.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            GodCoverImage(item.entity, Modifier.fillMaxSize())
                            Text(
                                "${item.rank.toString().padStart(2, '0')}",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(9.dp)
                                    .background(Color(0xC11C1B1A), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        // 细搁板代替大面积玻璃和倒影。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(2.dp)
                .background(GodGold.LightMid.copy(alpha = 0.32f)),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            selected.entity.effectiveTitle(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        GodStarsReadonly(selected.entity.rating, starSize = 14.dp)
        Spacer(Modifier.height(5.dp))
        Text(
            "${selected.entity.bookTitle}  ·  ${pager.currentPage + 1} / ${items.size}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
        )
        if (selected.entity.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                selected.entity.note,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { interactions.noteFor = selected.entity },
            )
        }
    }
}

@Composable
private fun GodRecordDisc(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        drawCircle(Color(0xFF202124), radius)
        for (i in 1..7) {
            drawCircle(
                Color.White.copy(alpha = 0.045f),
                radius * (0.40f + i * 0.075f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawCircle(Color(0xFFB89A67), radius * 0.27f)
        drawCircle(Color(0xFF25231F), radius * 0.035f)
    }
}

@Composable
internal fun GodPolaroidStyle(
    items: List<GodMomentItem>,
    compact: Boolean,
    tilt: GodTilt,
    reduceMotion: Boolean,
    interactions: GodRankingInteractions,
    onItemClick: (GodMomentEntity) -> Unit,
) {
    if (items.isEmpty()) return
    val dark = godIsDark()
    val wall = if (dark) Color(0xFF232321) else Color(0xFFF1EDE6)
    val paper = if (dark) Color(0xFFF2EFEA) else Color.White
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(wall)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MOMENT WALL",
                color = if (dark) Color(0xFFE5D1AD) else Color(0xFF705F49),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.7.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${items.size} FRAMES",
                color = if (dark) Color(0xFFB9AD9C) else Color(0xFF8E8172),
                fontSize = 10.sp,
            )
        }
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { item ->
                    GodItemCard(
                        moment = item.entity,
                        reduceMotion = reduceMotion,
                        interactions = interactions,
                        onItemClick = onItemClick,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                translationX = if (reduceMotion) 0f else tilt.x * 0.25f
                            }
                            .shadow(5.dp, RoundedCornerShape(4.dp)),
                    ) { _ ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(paper)
                                .padding(7.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(GodCoverEngine.COVER_RATIO)
                                    .clip(RoundedCornerShape(2.dp)),
                            ) {
                                GodCoverImage(item.entity, Modifier.fillMaxSize())
                                Text(
                                    "${item.rank.toString().padStart(2, '0')}",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .background(Color(0xB922201E), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(
                                item.entity.effectiveTitle(),
                                color = Color(0xFF302C27),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.entity.rating} ★  ·  ${item.entity.bookTitle}",
                                color = Color(0xFF8C7250),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.entity.note.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    item.entity.note,
                                    color = Color(0xFF786E62),
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { interactions.noteFor = item.entity },
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
