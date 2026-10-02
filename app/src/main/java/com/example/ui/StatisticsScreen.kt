package com.example.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.Book
import com.example.data.ReadingRecord
import com.example.data.ReadingSession
import com.example.ui.components.AcrylicBottomOverlay
import com.example.ui.components.GlassCard
import com.example.ui.components.SegmentedPillSelector
import com.example.ui.components.TabScreenHeader
import com.example.ui.components.rememberHeaderCollapsedSource
import com.example.ui.components.ReadingCalendarCard
import com.example.ui.components.ReadingTrendCard
import com.example.ui.components.WeeklyReadingChart
import com.example.ui.components.dailySeries
import com.example.ui.components.dateStrOf
import com.example.ui.components.rememberTodayCalendar
import com.example.ui.components.daysReadBetween
import com.example.ui.components.formatReadDuration
import com.example.ui.components.formatSessionTime
import com.example.ui.components.formatShortDuration
import com.example.ui.components.monthlySeries
import com.example.ui.components.readingRecordsToDailyTotals
import com.example.ui.components.streakEndingAt
import com.example.ui.components.sumSecondsBetween
import com.example.ui.components.todayCalendar
import com.example.ui.components.weekDatesOf
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import com.example.ui.theme.glassTitleColor
import com.example.ui.theme.LocalAppBottomInset
import com.example.ui.mascot.MascotSpriteSheet
import java.util.Calendar
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.design.DesignTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    books: List<Book>,
    totalReadTimeSecondsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    readingRecords: List<ReadingRecord> = emptyList(),
    readingSessions: List<ReadingSession> = emptyList(),
    onGoToShelf: () -> Unit = {},
    onDeleteRecord: (ReadingRecord) -> Unit = {},
    recordCovers: Map<Int, String> = emptyMap(),
    recordBooks: Map<Int, com.example.source.SearchBook> = emptyMap(),
    recordCoverHeaders: Map<Int, Map<String, String>> = emptyMap(),
    onResolveRecordCoverHeaders: (List<ReadingRecord>) -> Unit = {},
    onResolveRecordCovers: (List<ReadingRecord>) -> Unit = {},
    onOpenRecordDetail: (com.example.source.SearchBook) -> Unit = {},
    onOpenBook: (Book) -> Unit = {},
    dailyGoalMinutes: Int = 60,
    onGoalChange: (Int) -> Unit = {},
    /* ── 神回排行榜（紧随核心数据卡片之后） ── */
    godMoments: List<com.example.god.GodMomentEntity> = emptyList(),
    godStyle: com.example.god.GodRankingStyle = com.example.god.GodRankingStyle.PODIUM,
    godGyroEnabled: Boolean = true,
    onOpenGodRanking: () -> Unit = {},
    onGodMomentClick: (com.example.god.GodMomentEntity) -> Unit = {},
    onEditGodMoment: (com.example.god.GodMomentEntity) -> Unit = {},
    onDeleteGodMoment: (com.example.god.GodMomentEntity) -> Unit = {},
) {
    val totalReadTimeSeconds by totalReadTimeSecondsFlow.collectAsStateWithLifecycle()
    val finishedCount = books.count { it.isFinished }

    // 已删除书籍的封面补抓
    LaunchedEffect(readingRecords) {
        onResolveRecordCovers(readingRecords)
    }

    LaunchedEffect(readingRecords, recordBooks) {
        onResolveRecordCoverHeaders(readingRecords)
    }

    val dailyTotals = remember(readingRecords) {
        readingRecordsToDailyTotals(readingRecords)
    }

    var selectedDate by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (LocalAppBackgroundActive.current) Color.Transparent
                else MaterialTheme.colorScheme.background
            )
    ) {
        // 滚动联动折叠头部：此前 LazyColumn 无 state 可挂，先补 state（代理B方案挂点）
        val statisticsListState = rememberLazyListState()
        val statsHeaderCollapsedSource = rememberHeaderCollapsedSource(statisticsListState)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabScreenHeader(
                    collapsedSource = statsHeaderCollapsedSource,
                    modifier = Modifier.statusBarsPadding(),
                    title = "阅读统计",
                    subtitle = "STATISTICS & INSIGHTS",
                    titleColor = glassTitleColor()
                )
            }
        ) { padding ->
            // 性能优化（视觉零变化）：原为普通 Column+verticalScroll，
            // 5 张玻璃卡 + 3 个图表在滚动时全部参与绘制命令录制（低端机卡顿主因）。
            // 改 LazyColumn 后屏幕外的卡片/图表不再组合与绘制，布局顺序间距完全一致。
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
LazyColumn(
                state = statisticsListState,
                modifier = Modifier
                .widthIn(max = AdaptiveSpec.pageContentMaxWidth)
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = DesignTokens.SpacePage),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                // 底部避让放进 contentPadding：contentPadding 区不做裁剪，
                // 卡片滚动到 tab 栏背后时玻璃背景/内容照常渲染（与其他页一致）。
                // A2：原 120.dp magic number → 统一避让值
                contentPadding = PaddingValues(top = 12.dp, bottom = LocalAppBottomInset.current)
            ) {
                if (totalReadTimeSeconds == 0L) {
                    item(key = "stats_empty") {
                        com.example.ui.components.MascotEmptyState(
                            mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                            title = "「暂无阅读统计记录」",
                            description = "您最近还没有在本软件中阅读过小说哦。Roxy 已经乖乖为您备好了专属书签，快去读一章，开启您的阅读旅程并点亮统计图表吧！",
                            actionLabel = "立即前往书架阅读",
                            onActionClick = onGoToShelf,
                            testTagPrefix = "stats_empty_state"
                        )
                    }
                } else {
                    // ---------- 周期总览 ----------
                    item(key = "stats_period") {
                        PeriodOverviewCard(
                            dailyTotals = dailyTotals,
                            books = books,
                            finishedCount = finishedCount,
                            dailyGoalMinutes = dailyGoalMinutes,
                            onGoalChange = onGoalChange
                        )
                    }

                    // ---------- 神回排行榜（核心数据卡片之后） ----------
                    item(key = "stats_god_ranking") {
                        com.example.god.GodRankingCard(
                            moments = godMoments,
                            style = godStyle,
                            gyroEnabled = godGyroEnabled,
                            reduceMotion = com.example.god.rememberReduceMotion(),
                            onOpenAll = onOpenGodRanking,
                            onItemClick = onGodMomentClick,
                            onEdit = onEditGodMoment,
                            onDelete = onDeleteGodMoment,
                        )
                    }

                    // ---------- 日历视图 ----------
                    item(key = "stats_calendar") {
                        ReadingCalendarCard(
                            dailyTotals = dailyTotals,
                            onDayClick = { selectedDate = it }
                        )
                    }

                    // ---------- 趋势图表 ----------
                    item(key = "stats_trend") {
                        ReadingTrendCard(
                            dailyTotals = dailyTotals,
                            sessions = readingSessions
                        )
                    }

                    // ---------- 本周明细（保留原周视图 + 封面轮播/删除） ----------
                    item(key = "stats_weekly_header") {
                        Text(
                            text = "本周阅读明细",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = adaptiveTitleColor(),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    item(key = "stats_weekly") {
                        // 2026-09-21 修复：原来两处都是无 key 的 remember，只在首次组合算一次 ——
                        // 跨过午夜/跨周后不刷新，“这周过了不重置”。改用跨天自动更新的今天。
                        val todayCal = rememberTodayCalendar()
                        val todayIdx = remember(todayCal) {
                            (todayCal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
                        }
                        val weekDates = remember(todayCal) {
                            weekDatesOf(todayCal).map { dateStrOf(it) }
                        }
                        // 2026-09-21 修复：原实现在本周无任何阅读记录时，把「历史累计总时长」
                        // 整个塞进“今天”那一格（表现为“今天读了 197 分钟，但我根本没看”），
                        // 并凭空把书架第一本书当成今天读的书。这是凭空伪造数据，一律删除：
                        // 没有记录就是 0，空状态交给 WeeklyReadingChart 自己呈现。
                        val minutesList = remember(readingRecords, weekDates) {
                            weekDates.map { date ->
                                (readingRecords.filter { it.dateStr == date }
                                    .sumOf { it.durationSeconds } / 60).toInt()
                            }
                        }
                        val booksList = remember(readingRecords, weekDates) {
                            weekDates.map { date ->
                                val daily = readingRecords.filter { it.dateStr == date }
                                if (daily.isEmpty()) "" else daily.map { it.bookTitle }.distinct().joinToString(", ")
                            }
                        }
                        val dayRecords = remember(readingRecords, weekDates) {
                            weekDates.map { date ->
                                readingRecords
                                    .filter { it.dateStr == date }
                                    .sortedByDescending { it.id }
                                    .let { mergeDuplicateReadingRecords(it) }
                            }
                        }

                        WeeklyReadingChart(
                            minutesPerDay = minutesList,
                            bookTitlesPerDay = booksList,
                            todayIndex = todayIdx,
                            dayRecords = dayRecords,
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
            }
        }
    }

    selectedDate?.let { date ->
        DayDetailSheet(
            date = date,
            totalSeconds = dailyTotals[date] ?: 0L,
            records = readingRecords.filter { it.dateStr == date }.let { mergeDuplicateReadingRecords(it) },
            sessions = readingSessions.filter { it.dateStr == date }.sortedBy { it.startTimeMs },
            recordCovers = recordCovers,
            recordBooks = recordBooks,
            recordCoverHeaders = recordCoverHeaders,
            books = books,
            onOpenRecordDetail = onOpenRecordDetail,
            onOpenBook = onOpenBook,
            onDeleteRecord = onDeleteRecord,
            onDismiss = { selectedDate = null }
        )
    }
}

/** 周期总览卡：周/月/年切换、周期时长、日均、同比、目标环、阅读天数/连续天数。 */
@Composable
private fun PeriodOverviewCard(
    dailyTotals: Map<String, Long>,
    books: List<Book>,
    finishedCount: Int,
    dailyGoalMinutes: Int = 60,
    onGoalChange: (Int) -> Unit = {}
) {
    var period by remember { mutableIntStateOf(0) } // 0=周 1=月 2=年
    val today = remember { todayCalendar() }

    val range = remember(period, today) {
        val start: Calendar
        val end = today.clone() as Calendar
        val prevStart: Calendar
        val prevEnd: Calendar
        when (period) {
            0 -> {
                val week = weekDatesOf(today)
                start = week.first().clone() as Calendar
                val prevMonday = (week.first().clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
                prevStart = prevMonday.clone() as Calendar
                prevEnd = (week.first().clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
            1 -> {
                start = (today.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val prevMonthStart = (start.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                prevStart = prevMonthStart.clone() as Calendar
                prevEnd = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
            else -> {
                start = (today.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                prevStart = (start.clone() as Calendar).apply { add(Calendar.YEAR, -1) }
                prevEnd = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
        }
        PeriodRange(start, end, prevStart, prevEnd)
    }

    val periodTotal = remember(dailyTotals, range) {
        sumSecondsBetween(dailyTotals, range.start, range.end)
    }
    val prevTotal = remember(dailyTotals, range) {
        sumSecondsBetween(dailyTotals, range.prevStart, range.prevEnd)
    }
    val daysInPeriod = remember(range) {
        ((range.end.timeInMillis - range.start.timeInMillis) / 86_400_000L + 1L).toInt().coerceAtLeast(1)
    }
    val avgDaily = periodTotal / daysInPeriod
    val daysRead = remember(dailyTotals, range) {
        daysReadBetween(dailyTotals, range.start, range.end)
    }
    val streak = remember(dailyTotals) { streakEndingAt(dailyTotals, todayCalendar()) }
    val deltaPct = remember(periodTotal, prevTotal) {
        when {
            prevTotal <= 0 && periodTotal > 0 -> 100
            prevTotal <= 0 -> 0
            else -> ((periodTotal - prevTotal) * 100 / prevTotal).toInt()
        }
    }
    val goalProgress = (avgDaily / (dailyGoalMinutes * 60f)).coerceIn(0f, 1f)
    // 复用全软件统一弹簧（AppBottomTabBar/DownloadGlassCard 同款：
    // MediumBouncy + StiffnessMediumLow），切换周/月/年时数字与圆环平滑过渡。
    val animatedGoal by animateFloatAsState(
        targetValue = goalProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "goalRingProgress"
    )
    val animatedPeriodTotal by animateFloatAsState(
        targetValue = periodTotal.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "periodTotal"
    )
    val periodName = listOf("本周", "本月", "今年")[period]
    val prevName = listOf("上周", "上月", "去年")[period]

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MintPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${periodName}阅读时长", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatReadDuration(animatedPeriodTotal.toLong()),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val avgDailyText = if (avgDaily in 1..59) "不足1分钟" else formatShortDuration(avgDaily)
                    val prevText = if (prevTotal <= 0 && periodTotal > 0) "首次记录" else "$deltaPct%"
                    val prevColor = when {
                        prevTotal <= 0 && periodTotal > 0 -> MintGold
                        deltaPct > 0 -> MintPrimary
                        deltaPct < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MintGold
                    }
                    Text(
                        text = "日均 $avgDailyText · 较$prevName $prevText",
                        fontSize = 11.sp,
                        color = prevColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // ── Block 2: 目标环 + 步进器（单行紧凑布局）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val primary = MintPrimary
                        val secondary = MintSecondary
                        Canvas(modifier = Modifier.size(54.dp)) {
                            val stroke = 6.dp.toPx()
                            drawArc(
                                color = primary.copy(alpha = 0.15f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke)
                            )
                            if (animatedGoal > 0f) {
                                if (animatedGoal >= 0.99f) {
                                    drawCircle(
                                        color = MintGold.copy(alpha = 0.35f),
                                        radius = stroke * 1.8f,
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                                drawArc(
                                    brush = Brush.sweepGradient(listOf(primary, secondary)),
                                    startAngle = -90f,
                                    sweepAngle = 360f * animatedGoal,
                                    useCenter = false,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                                )
                            }
                        }
                        Text(
                            text = "${(animatedGoal * 100).toInt()}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MintPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("每日目标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        onClick = { if (dailyGoalMinutes > 15) onGoalChange(dailyGoalMinutes - 15) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp).semantics { contentDescription = "减少目标15分钟" }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("−", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${dailyGoalMinutes}分钟", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MintPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { if (dailyGoalMinutes < 480) onGoalChange(dailyGoalMinutes + 15) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp).semantics { contentDescription = "增加目标15分钟" }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("+", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Block 3: 分享本周/月/年阅读报告（替换原周条形图）──
                val shareCtx = androidx.compose.ui.platform.LocalContext.current
                val shareLabel = if (period == 0) "周" else if (period == 1) "月" else "年"
                Surface(
                    onClick = {
                        val shareText = buildString {
                            appendLine("📚 我的阅读${shareLabel}报 · Ciallo 阅读")
                            appendLine("────────────────────")
                            if (periodTotal > 0) {
                                appendLine("⏱ ${periodName}阅读 ${formatReadDuration(periodTotal)}（日均 ${if (avgDaily in 1..59) "不足1分钟" else formatShortDuration(avgDaily)}）")
                            } else {
                                appendLine("⏱ ${periodName}暂无阅读记录，从一页开始 ✨")
                            }
                            appendLine("🎯 每日目标 ${dailyGoalMinutes} 分钟 · 完成 ${(animatedGoal * 100).toInt()}%")
                            if (streak > 0) appendLine("🔥 连续阅读 $streak 天")
                            if (daysRead > 0) appendLine("📖 ${periodName}阅读 $daysRead 天 · 读完 $finishedCount 本")
                            appendLine("━━━━━━━━━━━━━━━━")
                            appendLine("Ciallo 阅读 · Roxy 陪伴 ✨")
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                        }
                        shareCtx.startActivity(android.content.Intent.createChooser(intent, "分享阅读报告"))
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MintPrimary.copy(alpha = 0.10f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MintPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = MintPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "分享${periodName}阅读报告",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MintPrimary
                        )
                    }
                }
            Spacer(modifier = Modifier.height(12.dp))

            SegmentedPillSelector(
                options = listOf(0 to "周", 1 to "月", 2 to "年"),
                selected = period,
                onSelect = { period = it },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MiniStatCard(
                    icon = { Icon(Icons.Filled.Book, contentDescription = null, tint = MintSecondary) },
                    label = if (period == 0) "本周阅读天数" else "周期阅读天数",
                    value = "$daysRead 天",
                    modifier = Modifier.weight(1f)
                )
                MiniStatCard(
                    icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MintGold) },
                    label = "当前连续",
                    value = "$streak 天",
                    modifier = Modifier.weight(1f)
                )
            }

            if (period == 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本周点亮", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(6.dp))
                    val weekDates = remember { weekDatesOf(todayCalendar()).map { dateStrOf(it) } }
                    weekDates.forEach { date ->
                        val lit = (dailyTotals[date] ?: 0L) > 0
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(16.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .then(
                                    if (lit) Modifier.background(
                                        Brush.linearGradient(listOf(MintPrimary, MintSecondary))
                                    ) else Modifier
                                )
                                .background(
                                    if (!lit) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else Color.Transparent
                                )
                                .then(
                                    if (!lit) Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        RoundedCornerShape(5.dp)
                                    ) else Modifier
                                )
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("${books.size} 本藏书 · ${finishedCount} 本读完", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 数据很少时给一点情感引导，避免页面空洞
            if (periodTotal in 1 until 300) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MintPrimary.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = MascotSpriteSheet.readingDrawable),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "${periodName}只读了 ${formatShortDuration(periodTotal)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MintPrimary
                            )
                            Text(
                                "Roxy 捧着魔法书等你翻开第一页～",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PeriodRange(
    val start: Calendar,
    val end: Calendar,
    val prevStart: Calendar,
    val prevEnd: Calendar
)

@Composable
private fun MiniStatCard(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 某天详情：亚克力底部弹层，展示当天书籍时长与阅读时段。 */
@Composable
private fun DayDetailSheet(
    date: String,
    totalSeconds: Long,
    records: List<ReadingRecord>,
    sessions: List<ReadingSession>,
    recordCovers: Map<Int, String>,
    recordBooks: Map<Int, com.example.source.SearchBook>,
    recordCoverHeaders: Map<Int, Map<String, String>>,
    books: List<Book>,
    onOpenRecordDetail: (com.example.source.SearchBook) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDeleteRecord: (ReadingRecord) -> Unit,
    onDismiss: () -> Unit
) {
    AcrylicBottomOverlay(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 32.dp)
        ) {
            Text("$date 阅读明细", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "共 ${formatReadDuration(totalSeconds)}",
                fontSize = 13.sp,
                color = MintPrimary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (records.isEmpty() && sessions.isEmpty()) {
                Text("这一天没有阅读记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            records.forEach { record ->
                val localBook = books.firstOrNull { it.id == record.bookId }
                val recordBook = recordBooks[record.id]?.takeIf { it.sourceId.isNotBlank() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable(enabled = localBook != null || recordBook != null) {
                            if (localBook != null) onOpenBook(localBook)
                            else if (recordBook != null) onOpenRecordDetail(recordBook)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cover = localBook?.coverUri ?: recordCovers[record.id]
                    if (cover != null) {
                        com.example.ui.components.ReadingRecordCover(
                            cover = cover,
                            sourceId = recordBooks[record.id]?.sourceId,
                            headers = recordCoverHeaders[record.id].orEmpty(),
                            contentDescription = record.bookTitle,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MintPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Book, contentDescription = null, tint = MintPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            record.bookTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatReadDuration(record.durationSeconds),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onDeleteRecord(record) }) {
                        Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("阅读时段", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                sessions.forEach { session ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${formatSessionTime(session)} 起",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            formatShortDuration(session.durationSeconds),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 合并同一天内同一本书的多条阅读记录（本地书按 bookId，在线书按书名），
 * 时长累加、保留第一条的 id，避免周几阅读记录里同一本书重复显示。
 */
private fun mergeDuplicateReadingRecords(records: List<ReadingRecord>): List<ReadingRecord> {
    if (records.size <= 1) return records
    val merged = LinkedHashMap<String, ReadingRecord>()
    for (record in records) {
        val key = record.bookId?.let { "book:$it" } ?: "title:${record.bookTitle}"
        val existing = merged[key]
        if (existing == null) {
            merged[key] = record
        } else {
            merged[key] = existing.copy(durationSeconds = existing.durationSeconds + record.durationSeconds)
        }
    }
    return merged.values.toList()
}

