package com.example.ui.favorite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.shelf.pressScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.CategoryEntity
import com.example.data.favorite.ComicReadingLogic
import com.example.data.favorite.FavoriteItem
import com.example.data.favorite.FAV_DEFAULT_CATEGORY
import com.example.ui.components.MascotEmptyState
import com.example.ui.mascot.MascotSpriteSheet
import com.example.ui.theme.MintPrimary

/** 「我喜欢的」排序方式。 */
enum class FavoriteSort(val label: String) {
    RECENT_FAVORITE("最近收藏"),
    RECENT_READ("最近阅读"),
    RECENT_UPDATE("最近更新"),
    TITLE("书名"),
    CUSTOM("自定义"),
}

/**
 * 书架「我喜欢的」栏：分类 Chip 栏 + 网格/列表 + 排序，样式与「我的书架」保持一致。
 *
 * 打开逻辑：点击卡片直接进漫画主页面（在线阅读，不需要任何下载流程）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesPanel(
    items: List<FavoriteItem>,
    /** 「我喜欢的」自己的分类名（与书架 categories 完全独立，各过各的） */
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onAddCategory: () -> Unit,
    /** 长按分类 → 重命名 / 删除（只影响收藏分类，碰不到书架） */
    onCategoryLongPress: (String) -> Unit = {},
    sortMode: FavoriteSort,
    onSortChange: (FavoriteSort) -> Unit,
    onItemClick: (FavoriteItem) -> Unit,
    onRefresh: () -> Unit,
    onGoLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    refreshing: Boolean = false,
    /** 网格左右留白（作为独立整页时用 12.dp，嵌在别处用 0） */
    horizontalPadding: Dp = 0.dp,
    /** 网格底部留白：默认给底部 Tab 栏留出空间 */
    bottomPadding: Dp = 120.dp,
    /** 多选/拖拽用：由宿主注入的几何注册 Modifier（key = "sourceId::comicId"） */
    itemBounds: (String) -> Modifier = { Modifier },
    /** 多选态：显示左上角圆形勾选框 */
    isSelecting: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
) {
    val filtered = remember(items, selectedCategory) {
        if (selectedCategory == ALL_FAV_CATEGORY) items
        else items.filter { it.favorite.categoryName == selectedCategory }
    }
    val sorted = remember(filtered, sortMode) {
        when (sortMode) {
            FavoriteSort.RECENT_FAVORITE -> filtered.sortedByDescending { it.favorite.favoritedAt }
            FavoriteSort.RECENT_READ -> filtered.sortedByDescending { it.progress?.lastReadAt ?: 0L }
            FavoriteSort.RECENT_UPDATE -> filtered.sortedByDescending { it.favorite.latestChapterUpdateAt }
            FavoriteSort.TITLE -> filtered.sortedBy { it.favorite.title.lowercase() }
            FavoriteSort.CUSTOM -> filtered.sortedBy { it.favorite.sortOrder }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 分类 Chip 栏（与我的书架相互独立：各自的选中项各自记住）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.example.ui.shelf.CategoryPill(
                name = ALL_FAV_CATEGORY,
                selected = selectedCategory == ALL_FAV_CATEGORY,
                count = items.size,
                onClick = { onCategorySelected(ALL_FAV_CATEGORY) },
            )
            categories.forEach { name ->
                com.example.ui.shelf.CategoryPill(
                    name = name,
                    selected = selectedCategory == name,
                    count = items.count { it.favorite.categoryName == name },
                    onClick = { onCategorySelected(name) },
                    // 「默认」不允许删除/改名：它是收藏的兜底分类
                    onLongClick = { if (name != FAV_DEFAULT_CATEGORY) onCategoryLongPress(name) },
                )
            }
            com.example.ui.shelf.AddCategoryPill(onClick = onAddCategory)
        }

        // 排序胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FavoriteSort.entries.forEach { mode ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (sortMode == mode) MintPrimary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                        )
                        .border(
                            0.5.dp,
                            if (sortMode == mode) MintPrimary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            RoundedCornerShape(14.dp),
                        )
                        .pressScale(scale = 0.94f) { onSortChange(mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = mode.label,
                        fontSize = 11.sp,
                        fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Medium,
                        color = if (sortMode == mode) MintPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                    .pressScale(scale = 0.94f) { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (refreshing) "检查中…" else "检查更新",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MintPrimary,
                )
            }
        }

        if (sorted.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MascotEmptyState(
                    mascotResId = MascotSpriteSheet.idleDrawable,
                    title = "「还没有喜欢的漫画」",
                    description = "在漫画页点一下 ♡，喜欢的漫画都在这里 —— 不下载、不占空间，随时在线接着看。",
                    actionLabel = "去书库逛逛",
                    onActionClick = onGoLibrary,
                    testTagPrefix = "favorites_empty_state",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = sorted, key = { it.key }) { item ->
                    FavoriteCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        // 排序/筛选变化时条目是"挪过去"的，不是瞬间重排
                        modifier = itemBounds(item.key).animateItemPlacement(),
                        selected = isSelecting && item.key in selectedKeys,
                        // 未选中也要画白圆圈 → 与书架卡片同构
                        multiSelecting = isSelecting,
                        dimmed = isSelecting && item.key !in selectedKeys,
                    )
                }
            }
        }
    }
}

const val ALL_FAV_CATEGORY = "全部"

/** 「我喜欢的」卡片：封面 + 来源小标签 + 更新/未读角标 + 底部细进度条 + 副文案。 */
@Composable
fun FavoriteCard(
    item: FavoriteItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /**
     * 「我喜欢的」板块当前是否处于多选态（由宿主按板块注入）。
     *
     * 决定左上角圆形勾选框**画不画**：未选中也要画白圆圈，才与书架卡片同构。
     * 以前用 `selected` 兼任这个判断，导致未选中条目不出现勾选框。
     */
    multiSelecting: Boolean = false,
    /** 多选态下「未被选中」的书：压暗到 0.85，把选中的那几本衬出来 */
    dimmed: Boolean = false,
    /**
     * 被拿起来拖拽 / 正在飞回原位时为 true：整张卡淡到 **0**，原位不留任何色块。
     *
     * ⚠️ 书架书卡一直有这条（isDragged || isFlyingHome → 0f），收藏卡片却只有
     * [dimmed]（0.85）—— 用户反馈「我喜欢的拖动的时候原处书本没有跟我的书架一样
     * 原处消失」。两个板块必须是同一套规则，否则拖起来完全不像一个东西。
     */
    hidden: Boolean = false,
    /**
     * 手势已交给书架拖拽宿主时为 true。
     *
     * ⚠️ 此时卡片自己**绝不能**再挂 pressScale：`pressScale` 内部是 clickable，
     * down 的一瞬间就把事件消费掉，宿主的长按等待循环永远等不到超时 ——
     * 表现就是「书架长按有反应，我喜欢的长按完全没反应」。
     * 按压反馈改由 [pressed] 驱动（值来自宿主的 ShelfSelectionState.pressedKey）。
     */
    hostOwnsGesture: Boolean = false,
    pressed: Boolean = false,
    /** 封面几何登记：归位动画的落点依据（与书架书卡同一套机制） */
    coverModifier: Modifier = Modifier,
) {
    val fav = item.favorite
    val cardAlpha by animateFloatAsState(
        targetValue = if (hidden) 0f else if (dimmed) 0.85f else 1f,
        animationSpec = com.example.ui.feedback.AppMotion.springDefault,
        label = "fav_card_dim",
    )
    val pressAnim by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = com.example.ui.feedback.AppMotion.springDefault,
        label = "fav_card_press",
    )
    val latestNum = ComicReadingLogic.chapterNumber(fav.latestChapterTitle.orEmpty())
    val readNum = (item.progress?.lastChapterIndex ?: -1).let { if (it >= 0) it + 1 else 0 }
    // 「有更新」= 快照 id 与已见快照不同。⚠️ 要再加一道话数兜底：
    // 有的源每次拉取 chapterId 都不同（URL 带时间戳/hash），裸 id 比较会让
    // 已经读到最新话数的漫画永远亮着「更新」角标。标题能解析出话数、且用户
    // 已读到该话数（latestNum ≤ readNum）→ 不算更新；解析不到话数退回 id 比较
    //（缺章源里 order 序号 ≤ 话数，readNum 偏小只会少兜底、不会误压制真更新）。
    val hasUpdate = fav.latestChapterId != null &&
        item.progress?.seenTopChapterId != null &&
        fav.latestChapterId != item.progress.seenTopChapterId &&
        !(latestNum != null && readNum > 0 && latestNum <= readNum)
    val unread = if (latestNum != null && readNum > 0) (latestNum - readNum).coerceAtLeast(0) else 0
    val dead = !fav.sourceAlive

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = cardAlpha
                scaleX = pressAnim
                scaleY = pressAnim
            }
            .clip(RoundedCornerShape(14.dp))
            // 按下轻微下沉（0.97），松手弹回：卡片像"实体"而不是一张图。
            // 宿主接管手势时不挂，避免抢走 down 事件。
            .then(
                if (hostOwnsGesture) Modifier
                else Modifier.pressScale(scale = 0.97f, onTap = onClick)
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .then(coverModifier)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        )
                    )
                ),
        ) {
            val cover = fav.localThumbPath ?: fav.coverUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = fav.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 更新角标
            if (hasUpdate) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xFFFF4D4F), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("更新", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            // 未读话数
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("未读 $unread 话", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            // 来源小标签
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(fav.sourceId, fontSize = 9.sp, color = Color.White, maxLines = 1)
            }
            // 已下载 N 话
            if (item.downloadedChapters > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(MintPrimary.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("已下载 ${item.downloadedChapters} 话", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            // 多选态：左上角圆形勾选框 + 主题色遮罩
            //
            // ⚠️ 原来只有 `if (selected)` 才画，所以「我喜欢的」**未选中**的条目根本没有白圆圈，
            // 与书架的观感不一致（用户反馈）。改为按 [multiSelecting]（宿主注入的板块多选态）
            // 决定是否显示勾选框：未选中 = 白圆圈，选中 = 主色底 + 白勾 + 主题色遮罩。
            if (multiSelecting) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MintPrimary.copy(alpha = 0.22f)),
                    )
                }
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    com.example.ui.shelf.ShelfSelectBadge(selected = selected)
                }
            }
            if (dead) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = "来源失效", tint = Color(0xFFFFD166), modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = fav.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildString {
                if (latestNum != null) append("更新至第 ${latestNum} 话")
                if (readNum > 0) append(if (latestNum != null) " · 看到第 ${readNum} 话" else "看到第 ${readNum} 话")
                if (isEmpty()) append(if (dead) "来源暂不可用" else "尚未开始阅读")
            },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (readNum > 0 && latestNum != null && latestNum > 0) {
            val ratio = (readNum.toFloat() / latestNum.toFloat()).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = MintPrimary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            )
        }
    }
}

/** 顶部一级分段：[ 我的书架 | 我喜欢的 ]。 */
@Composable
fun ShelfSegmentedTabs(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    favoritesCount: Int,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val padPx = with(density) { 4.dp.toPx() }
    var rowWidthPx by remember { mutableStateOf(0f) }
    val itemWidthPx = ((rowWidthPx - padPx * 2) / 2f).coerceAtLeast(0f)

    // 指示器是"滑过去"的，不是"闪现"的：弹簧带轻微过冲，刚好能被眼睛捕捉到
    val fraction by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) {
            tween(0)
        } else {
            spring(dampingRatio = 0.72f, stiffness = 420f, visibilityThreshold = 0.001f)
        },
        label = "segment_slide",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
            .padding(4.dp),
    ) {
        if (itemWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((fraction * itemWidthPx).toInt(), 0) }
                    .fillMaxHeight()
                    .width(with(density) { itemWidthPx.toDp() })
                    .clip(RoundedCornerShape(16.dp))
                    .background(MintPrimary.copy(alpha = 0.18f)),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            SegmentedItem(
                text = "我的书架",
                selected = selectedIndex == 0,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(0) },
            )
            SegmentedItem(
                text = "我喜欢的",
                selected = selectedIndex == 1,
                badge = favoritesCount,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(1) },
            )
        }
    }
}

@Composable
private fun SegmentedItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = -1,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            // 底色由外层滑动指示器提供，这里只管内容与点击（点按时轻微下沉）
            .pressScale { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text == "我喜欢的") {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        if (badge > 0) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = badge.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
            )
        }
    }
}

/** 空状态：友好插画 + 引导（设计稿 §2.5）。 */
@Composable
fun FavoritesEmptyState(onGoLibrary: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MascotEmptyState(
            mascotResId = MascotSpriteSheet.idleDrawable,
            title = "「还没有喜欢的漫画」",
            description = "在漫画页点一下 ♡，喜欢的漫画都在这里。",
            actionLabel = "去书库逛逛",
            onActionClick = onGoLibrary,
            testTagPrefix = "favorites_empty_state",
        )
    }
}

/** 默认分类兜底（分类行被删光时收藏不至于变孤儿）。 */
fun ensureFavoriteCategory(current: String, existing: List<String>): String =
    if (current in existing) current
    else if (FAV_DEFAULT_CATEGORY in existing) FAV_DEFAULT_CATEGORY
    else existing.firstOrNull() ?: FAV_DEFAULT_CATEGORY
