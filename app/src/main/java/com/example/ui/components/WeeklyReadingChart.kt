package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.Book
import com.example.data.ReadingRecord
import com.example.ui.components.AcrylicDialog
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import com.example.ui.theme.MintSecondary
import me.trishiraj.shadowglow.consistentShadow
import kotlinx.coroutines.delay

/**
 * Reusable Weekly Reading Duration Bar Chart Component (周一至周日阅读时长统计图)
 *
 * @param minutesPerDay Reading minutes for Monday to Sunday (7 integers, default 0s)
 * @param bookTitlesPerDay Titles of books read on corresponding days (7 strings, default empty)
 * @param todayIndex Index of current day of week (0 = Monday ... 6 = Sunday)
 */
@Composable
fun WeeklyReadingChart(
    minutesPerDay: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0),
    bookTitlesPerDay: List<String> = listOf("", "", "", "", "", "", ""),
    dayRecords: List<List<ReadingRecord>> = emptyList(),
    books: List<Book> = emptyList(),
    recordCovers: Map<Int, String> = emptyMap(),
    recordBooks: Map<Int, com.example.source.SearchBook> = emptyMap(),
    recordCoverHeaders: Map<Int, Map<String, String>> = emptyMap(),
    onDeleteRecord: (ReadingRecord) -> Unit = {},
    onOpenRecordDetail: (com.example.source.SearchBook) -> Unit = {},
    onOpenBook: (Book) -> Unit = {},
    todayIndex: Int = remember {
        val cal = java.util.Calendar.getInstance()
        (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    },
    modifier: Modifier = Modifier
) {
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    var selectedDayIndex by remember { mutableIntStateOf(todayIndex.coerceIn(0, 6)) }
    
    val totalMinutes = remember(minutesPerDay) { minutesPerDay.sum() }
    val maxMinutes = remember(minutesPerDay) { (minutesPerDay.maxOrNull() ?: 1).coerceAtLeast(1) }
    val chartPrimary = MaterialTheme.colorScheme.primary
    val chartSecondary = MaterialTheme.colorScheme.secondary
    // 本周峰值日（阅读时长最长的一天）：金色强调，让"重点"第一眼可见
    val lowQuality = LocalRenderQuality.current == RenderQuality.LOW
    // 系统「动画缩放」被关掉 / 开启减少动态效果时也要跳过生长动画：
    // 否则用户眼里动画根本不播，柱子却要等 320+400ms 才定格，看着像"卡了一下"。
    val reduceMotion = LocalReduceMotion.current
    val peakDayIdx = remember(minutesPerDay) {
        minutesPerDay.indices
            .maxByOrNull { minutesPerDay[it] }
            ?.takeIf { minutesPerDay.getOrElse(it) { 0 } > 0 }
    }

    // Staggered entrance animation factors for 7 bars
    val barAnimatables = remember { List(7) { Animatable(0f) } }

    LaunchedEffect(minutesPerDay, lowQuality, reduceMotion) {
        if (lowQuality || reduceMotion) {
            // 流畅档 / 系统关掉动画：跳过生长动画，直接定格终态
            barAnimatables.forEachIndexed { _, animatable -> animatable.snapTo(1f) }
            return@LaunchedEffect
        }
        // Defer chart bar growth animation until page switch transition completes (250-300ms)
        delay(320L)
        barAnimatables.forEachIndexed { index, animatable ->
            delay((index * 40).toLong())
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header Row: Title & Total Minutes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MintPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = MintPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "本周阅读趋势",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "周一 至 周日",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "本周共 ${totalMinutes} 分钟",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MintPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chart Bars Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                dayNames.forEachIndexed { index, dayName ->
                    val mins = minutesPerDay.getOrElse(index) { 0 }
                    val isToday = index == todayIndex
                    val isSelected = index == selectedDayIndex
                    
                    val rawFraction = if (maxMinutes <= 0 || mins <= 0) 0f else (mins.toFloat() / maxMinutes.toFloat()).coerceIn(0.04f, 1f)
                    // B4：原先只有一个入场遮罩（barAnimatables 跑一次到 1f 就再也不动），
                    // 之后当天阅读时长实时增长时柱子直接重绘、毫无过渡。
                    // 拆成两层：本层负责「数值变化」的过渡，入场遮罩仍由 barAnimatables 负责。
                    val animatedRaw by animateFloatAsState(
                        targetValue = rawFraction,
                        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                        label = "barValue_$index"
                    )
                    val animatedFraction = animatedRaw * barAnimatables[index].value

                    val scaleState by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = tween(durationMillis = 150),
                        label = "barScale"
                    )

                    val barGradient = when {
                        isToday || isSelected -> listOf(chartSecondary, chartPrimary)
                        index == peakDayIdx && mins > 0 -> listOf(MintGold.copy(alpha = 0.80f), MintGold.copy(alpha = 0.50f))
                        mins > 0 -> listOf(
                            chartSecondary.copy(alpha = 0.65f),
                            chartPrimary.copy(alpha = 0.45f)
                        )
                        else -> listOf(Color.LightGray.copy(alpha = 0.4f), Color.LightGray.copy(alpha = 0.2f))
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                selectedDayIndex = index
                            }
                    ) {
                        // 1. Top duration label box aligned at 20.dp height
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Text(
                                text = if (mins > 0) "${mins}分" else "-",
                                fontSize = 10.sp,
                                fontWeight = when {
                                    index == peakDayIdx && mins > 0 -> FontWeight.Bold
                                    isSelected || isToday -> FontWeight.Bold
                                    else -> FontWeight.Normal
                                },
                                color = when {
                                    isToday -> MintPrimary
                                    index == peakDayIdx && mins > 0 -> MintGold
                                    isSelected -> MintSecondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 2. Bar Container strictly 100.dp height
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(100.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            // Baseline for 0-minute days
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(
                                        color = Color.LightGray.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )

                            // Animated bar inside 100dp container
                            if (animatedFraction > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(animatedFraction.coerceAtLeast(0.04f))
                                        .then(
                                            if (isSelected) {
                                                Modifier.consistentShadow(
                                                    elevation = 6.dp,
                                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                                    ambientColor = chartPrimary.copy(alpha = 0.30f),
                                                    spotColor = chartPrimary.copy(alpha = 0.30f)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .graphicsLayer {
                                            scaleX = scaleState
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                        }
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(brush = Brush.verticalGradient(colors = barGradient))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. Day label tag at bottom
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isToday -> MintPrimary
                                isSelected -> MintSecondary.copy(alpha = 0.2f)
                                else -> Color.Transparent
                            }
                        ) {
                            Text(
                                text = dayName,
                                fontSize = 11.sp,
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    isToday -> Color.White
                                    isSelected -> MintSecondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detail Popover Card for Selected Day
            val selMins = minutesPerDay.getOrElse(selectedDayIndex) { 0 }
            val selRecords = dayRecords.getOrElse(selectedDayIndex) { emptyList() }
            val selDayName = dayNames.getOrElse(selectedDayIndex) { "" }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = null,
                            tint = MintPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${selDayName}阅读记录：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (selMins > 0 && selRecords.isNotEmpty()) {
                                "共 ${selRecords.size} 本 · 详情见下方封面"
                            } else if (selMins > 0) {
                                "自选阅读"
                            } else {
                                "无阅读记录"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selMins > 0) MintPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = if (selMins > 0) "共 ${selMins} 分钟" else "0 分钟",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 选中日封面轮播：周几阅读记录直接接入漫画封面
            DayCoverCarousel(
                records = dayRecords.getOrElse(selectedDayIndex) { emptyList() },
                books = books,
                recordCovers = recordCovers,
                recordBooks = recordBooks,
                recordCoverHeaders = recordCoverHeaders,
                onDeleteRecord = onDeleteRecord,
                onOpenRecordDetail = onOpenRecordDetail,
                onOpenBook = onOpenBook
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCoverCarousel(
    records: List<ReadingRecord>,
    books: List<Book>,
    recordCovers: Map<Int, String>,
    recordBooks: Map<Int, com.example.source.SearchBook>,
    recordCoverHeaders: Map<Int, Map<String, String>>,
    onDeleteRecord: (ReadingRecord) -> Unit,
    onOpenRecordDetail: (com.example.source.SearchBook) -> Unit,
    onOpenBook: (Book) -> Unit
) {
    if (records.isEmpty()) return
    val listState = rememberLazyListState()
    // 5 秒无操作后自动平滑滚动；用户拖动时暂停。
    // 流畅档（渲染画质=低）：关闭自动轮播，只保留手动滑动。
    val carouselAutoScroll = LocalRenderQuality.current != RenderQuality.LOW
    LaunchedEffect(records.size, carouselAutoScroll) {
        if (records.size <= 1 || !carouselAutoScroll) return@LaunchedEffect
        while (true) {
            delay(5000)
            if (listState.isScrollInProgress) continue
            val next = (listState.firstVisibleItemIndex + 1) % records.size
            listState.animateScrollToItem(next)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(records, key = { it.id }) { record ->
            DayCoverCard(
                record = record,
                books = books,
                recordCovers = recordCovers,
                recordBooks = recordBooks,
                recordCoverHeaders = recordCoverHeaders,
                onDeleteRecord = onDeleteRecord,
                onOpenRecordDetail = onOpenRecordDetail,
                onOpenBook = onOpenBook
            )
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCoverCard(
    record: ReadingRecord,
    books: List<Book>,
    recordCovers: Map<Int, String>,
    recordBooks: Map<Int, com.example.source.SearchBook>,
    recordCoverHeaders: Map<Int, Map<String, String>>,
    onDeleteRecord: (ReadingRecord) -> Unit,
    onOpenRecordDetail: (com.example.source.SearchBook) -> Unit,
    onOpenBook: (Book) -> Unit
) {
    val book = books.firstOrNull { it.id == record.bookId }
    val recordBook = recordBooks[record.id]?.takeIf { it.sourceId.isNotBlank() }
    var showMenu by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    val remoteCover = recordBook?.cover ?: recordCovers[record.id]
    Column(
        modifier = Modifier
            .width(84.dp)
            .combinedClickable(
                onClick = {
                    when {
                        book != null -> onOpenBook(book)
                        recordBook != null -> onOpenRecordDetail(recordBook)
                        else -> showDetail = true
                    }
                },
                onLongClick = { showMenu = true }
            )
    ) {
        Box(
            modifier = Modifier
                .width(84.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(MintSecondary.copy(alpha = 0.55f), MintPrimary.copy(alpha = 0.65f))
                    )
                )
        ) {
            val coverData = remember(book?.coverUri, book?.isCoverValid, remoteCover) {
                if (book == null || book.coverUri.isNullOrEmpty()) {
                    remoteCover
                } else if (book.coverUri!!.startsWith("content://")) {
                    android.net.Uri.parse(book.coverUri!!)
                } else if (book.isCoverValid) {
                    val p = if (book.coverUri!!.startsWith("file://")) {
                        book.coverUri!!.substring(7)
                    } else {
                        book.coverUri!!
                    }
                    java.io.File(p)
                } else {
                    null
                }
            }
            if (coverData != null) {
                ReadingRecordCover(
                    cover = coverData,
                    sourceId = recordBook?.sourceId,
                    headers = recordCoverHeaders[record.id].orEmpty(),
                    contentDescription = record.bookTitle,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp),
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.38f)
            ) {
                Text(
                    // 2026-09-21：原来 (seconds / 60).coerceAtLeast(1) 会把 20 秒
                    // 显示成“1分”——与“没读却 197 分钟”同源的虚报。改用统一格式化，
                    // 不足 1 分钟就显示秒。
                    text = formatShortDuration(record.durationSeconds),
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = record.bookTitle,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    if (showMenu) {
        AcrylicDialog(
            onDismissRequest = { showMenu = false },
            title = {
                Text(
                    text = record.bookTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column {
                    MenuActionRow(
                        label = "前往阅读",
                        enabled = book != null || recordBook != null,
                        onClick = {
                            showMenu = false
                            if (book != null) onOpenBook(book)
                            else if (recordBook != null) onOpenRecordDetail(recordBook)
                        }
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    MenuActionRow(
                        label = "阅读详情",
                        onClick = {
                            showMenu = false
                            showDetail = true
                        }
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    MenuActionRow(
                        label = "删除阅读历史",
                        destructive = true,
                        onClick = {
                            showMenu = false
                            onDeleteRecord(record)
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMenu = false }) { Text("取消") }
            }
        )
    }

    if (showDetail) {
        AcrylicDialog(
            onDismissRequest = { showDetail = false },
            title = {
                Text(
                    text = record.bookTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column {
                    Text("阅读日期：${record.dateStr}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("时长：${formatShortDuration(record.durationSeconds)}")
                    if (book == null && recordBook == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "该书已不在书库中",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun MenuActionRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
