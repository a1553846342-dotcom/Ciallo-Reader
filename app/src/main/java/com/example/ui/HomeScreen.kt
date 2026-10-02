package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.Book
import com.example.data.CategoryEntity
import com.example.ui.components.AppButton
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.AcrylicBottomOverlay
import com.example.ui.components.AppIconButton
import com.example.ui.components.GlassCard
import com.example.ui.components.TabScreenHeader
import com.example.ui.components.rememberHeaderCollapsedSource
import com.example.ui.components.scrollTiltSource
import com.example.ui.components.AcrylicDialog
import com.example.ui.components.StarryNightBackground
import com.example.ui.components.GlassDialogWindowEffect
import com.example.ui.components.filmGrain
import com.example.ui.components.iridescentBorder
import com.example.ui.components.liquidGlass
import com.example.ui.components.radialGlassScrim
import com.example.ui.components.rememberGlassPanelBackdrop
import com.example.ui.components.rememberIridescentColors
import com.example.ui.theme.MintGold
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import com.example.ui.theme.clickableWithFeedback
import com.example.ui.theme.glassTitleColor
import com.example.ui.theme.LocalAppBottomInset
import kotlin.math.roundToInt
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.AppFonts
import me.trishiraj.shadowglow.consistentShadow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AppToast

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@androidx.compose.animation.ExperimentalSharedTransitionApi
fun HomeScreen(
    books: List<Book>,
    categories: List<CategoryEntity>,
    onBookClick: (Book) -> Unit,
    onImportClick: (String) -> Unit,
    onAddCategory: (String) -> Unit,
    /**
     * 书架当前选中的分类（**由 MainActivity 持有**）。
     *
     * ⚠️ 以前这是 HomeScreen 自己的 `remember`，而打开书籍是 `navController.navigate`
     * 跳到另一个目的地 —— HomeScreen 会**整体离开组合**，回来时状态重新初始化成「默认」。
     * 用户眼里就是「点进一本书再退出，分类被重置回默认了」。所以必须提到页面外。
     */
    shelfCategory: String = com.example.data.DEFAULT_CATEGORY,
    onShelfCategoryChange: (String) -> Unit = {},
    onSettingsClick: () -> Unit,
    onNavigateToShelf: () -> Unit,
    onNavigateToStats: () -> Unit,
    /** 今日已阅读秒数（daily_read_time_<今天>；第七轮第 4 条：卡片显示"今日"口径） */
    todayReadSecondsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    streakDaysFlow: kotlinx.coroutines.flow.StateFlow<Int>,
    onDeleteBook: (Book) -> Unit,
    onMoveBook: (Book, String) -> Unit,
    onDeleteCategory: ((com.example.data.CategoryEntity, (Boolean) -> Unit) -> Unit)? = null,
    /* ── 隐私模式（第七轮第 6 条）：状态由 MainActivity 从 MainViewModel 注入 ── */
    privacyModeEnabled: Boolean = false,
    protectedCategoryNames: Set<String> = emptySet(),
    unlockedCategoryIds: Set<Int> = emptySet(),
    onUnlockCategory: (suspend (com.example.data.CategoryEntity, String) -> Boolean)? = null,
    onToggleCategoryProtected: ((com.example.data.CategoryEntity, Boolean) -> Unit)? = null,
    /* ══════════════ 「我喜欢的」板块：与「我的书架」平行 ══════════════ */
    /** 收藏聚合列表（收藏 + 进度 + 已下载话数） */
    favoriteItems: List<com.example.data.favorite.FavoriteItem> = emptyList(),
    /** "sourceId::comicId" 集合：书架卡片右下角小心形 */
    favoriteKeys: Set<String> = emptySet(),
    /** 点击「我喜欢的」卡片 → 直接进漫画主页面（在线阅读，不走下载） */
    onOpenFavorite: (com.example.data.favorite.FavoriteItem) -> Unit = {},
    onCheckFavoriteUpdates: () -> Unit = {},
    /** 拖拽/操作栏：把收藏移动到分类 */
    onMoveFavoritesToCategory: (List<String>, String) -> Unit = { _, _ -> },
    /** 取消喜欢（无损：不影响下载与阅读进度） */
    onRemoveFavorites: (List<String>) -> Unit = {},
    /** 书架批量「喜欢」：返回（成功数，无来源被跳过的数量） */
    onFavoriteSelectedBooks: ((List<Book>) -> Pair<Int, Int>)? = null,
    /**
     * 把书拖到「我喜欢的」某个分类上：加入收藏并**直接归入该分类**。
     * 返回 (成功本数, 因无来源被跳过本数)。
     */
    onFavoriteSelectedBooksToCategory: ((List<Book>, String) -> Pair<Int, Int>)? = null,
    /** 删除下载（软删除 + 撤销窗口） */
    onDeleteDownloads: ((List<Book>) -> Unit)? = null,
    /** 撤销 Snackbar：message + 撤销动作 */
    onShowUndo: ((String, () -> Unit) -> Unit)? = null,
    /** 新建分类后把收藏放进去 */
    onCreateFavoriteCategoryThenMove: ((List<String>) -> Unit)? = null,
    /** 「我喜欢的」自己的分类（独立于书架 categories） */
    favoriteCategories: List<String> = emptyList(),
    /** 隐私模式里的开关：为 true 时「我喜欢的」需先验证 PIN 才显示内容 */
    favoritesProtected: Boolean = false,
    onVerifyPrivacyPin: (suspend (String) -> Boolean)? = null,
    onAddFavoriteCategory: (String) -> Unit = {},
    onRenameFavoriteCategory: (String, String) -> Unit = { _, _ -> },
    onDeleteFavoriteCategory: (String) -> Unit = {},
    /** 标记为已读完（本地书） */
    onMarkFinished: (Book) -> Unit = {},
) {
    val sharedTransitionScope = com.example.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.example.LocalNavAnimatedVisibilityScope.current
    val streakDays by streakDaysFlow.collectAsStateWithLifecycle()

    val currentlyReading = remember(books) {
        val result = if (books.isEmpty()) {
            Book(id = -1, title = "Empty", filePath = "")
        } else {
            books.maxByOrNull { it.lastReadTime } ?: books.first()
        }
        result
    }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }

    // 搜索防抖 300ms：避免大书库逐键触发全量过滤
    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300)
        debouncedQuery = searchQuery
    }
    // ⚠️ 分类**不再**是本页的 remember：打开书籍会走 navController 跳到另一个目的地，
    // 本页整体离开组合，回来时 remember 重新初始化 → 分类被重置成「默认」。
    // 现在由 MainActivity 持有并通过 [shelfCategory] 传入。
    val selectedCategory = shelfCategory
    // 第七轮第 6.1 条：不再有聚合视图"全部"——所选分类必须是真实存在的分类行；
    // 分类列表异步到达/删除后失效时回退到"默认"
    LaunchedEffect(categories) {
        val names = categories.map { it.name }
        if (names.isNotEmpty() && selectedCategory !in names) {
            onShelfCategoryChange(
                if (names.contains(com.example.data.DEFAULT_CATEGORY)) {
                    com.example.data.DEFAULT_CATEGORY
                } else names.first()
            )
        }
    }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var bookToMove by remember { mutableStateOf<Book?>(null) }
    var longPressBook by remember { mutableStateOf<Book?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    /* ── 「我喜欢的」板块的分类：新建 / 重命名 / 删除（只动收藏分类，碰不到书架） ── */
    var showAddFavoriteCategoryDialog by remember { mutableStateOf(false) }
    var newFavoriteCategoryText by remember { mutableStateOf("") }
    var favCategorySheetFor by remember { mutableStateOf<String?>(null) }
    var favCategoryRenameFor by remember { mutableStateOf<String?>(null) }
    var favCategoryDeleteFor by remember { mutableStateOf<String?>(null) }
    // 隐私交互状态（第七轮第 6.3 条）：长按分类 → 分类操作面板；受保护分类进入 → PIN 验证
    var categorySheetFor by remember { mutableStateOf<com.example.data.CategoryEntity?>(null) }
    var pinVerifyFor by remember { mutableStateOf<com.example.data.CategoryEntity?>(null) }

    /* ── 「我喜欢的」板块：与「我的书架」平行，同一页往下滚即是 ── */
    var favoriteCategory by remember { mutableStateOf(com.example.ui.favorite.ALL_FAV_CATEGORY) }
    /** 隐私：本进程内是否已验证过 PIN（与受保护分类同一套密码） */
    var favoritesUnlocked by remember { mutableStateOf(false) }
    var favoritesPinFor by remember { mutableStateOf(false) }
    // 只在隐私模式整体开启时才上锁：否则关掉隐私模式后这里会永远要求验证、又验证不过
    val favoritesLocked = favoritesProtected && privacyModeEnabled && !favoritesUnlocked
    var favoriteSort by remember { mutableStateOf(com.example.ui.favorite.FavoriteSort.RECENT_FAVORITE) }
    var favoriteRefreshing by remember { mutableStateOf(false) }
    /** 多选 / 拖拽状态机（书架书籍：长按进入 → 滑动连选 → 再长按拖到分类或 ♡） */
    val shelfSelection = com.example.ui.shelf.rememberShelfSelectionState()
    /** 首次使用的一次性提示：最多显示 3 次 */
    var showDragHint by remember { mutableStateOf(false) }
    var pendingNewCategoryKeys by remember { mutableStateOf<List<String>>(emptyList()) }

    val refreshScope = rememberCoroutineScope()

    // 进入书架页触发一次收藏更新检测（限流在仓库里）
    LaunchedEffect(Unit) {
        favoriteRefreshing = true
        onCheckFavoriteUpdates()
        kotlinx.coroutines.delay(1500)
        favoriteRefreshing = false
    }
    // 首次进入多选的一次性提示：最多展示 3 次（次数持久化，之后不再打扰）
    val hintContext = androidx.compose.ui.platform.LocalContext.current
    val hintPrefs = remember(hintContext) { com.example.data.PreferencesManager(hintContext) }
    var dragHintShown by remember { mutableIntStateOf(hintPrefs.shelfDragHintShown) }
    LaunchedEffect(shelfSelection.phase) {
        if (shelfSelection.phase == com.example.ui.shelf.ShelfPhase.SELECTING && dragHintShown < 3) {
            showDragHint = true
            dragHintShown += 1
            hintPrefs.shelfDragHintShown = dragHintShown
        }
    }

    /** 批量操作目标：0 = 我的书架，1 = 我喜欢的，-1 = 无 */
    var bulkCategoryTarget by remember { mutableIntStateOf(-1) }
    var showMoreSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 新建分类后（拖拽落到「＋新建分类」或操作栏新建），把此前待放入的那批放进去。
    // ⚠️ 这批 key **可能是收藏**（fav:: 前缀）：以前只按 book.id 匹配，
    // 收藏一条都匹配不到 ⇒ 名单非空却一本书都没动（用户：「选了两本只移动一本」的另一种形态）。
    LaunchedEffect(categories) {
        if (pendingNewCategoryKeys.isNotEmpty()) {
            val target = categories.lastOrNull()?.name
            if (target != null) {
                val keys = pendingNewCategoryKeys
                val bookKeys = keys.filterNot { com.example.ui.shelf.isFavShelfKey(it) }
                val favKeys = keys.filter { com.example.ui.shelf.isFavShelfKey(it) }
                    .map { it.removePrefix(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX) }
                books.filter { it.id.toString() in bookKeys }.forEach { onMoveBook(it, target) }
                if (favKeys.isNotEmpty()) onMoveFavoritesToCategory(favKeys, target)
                pendingNewCategoryKeys = emptyList()
            }
        }
    }

    // 第七轮第 6.3 条验收缺口修复：受保护分类在"锁定"状态（隐私模式开启、标记保护、
    // 本进程未通过 PIN 解锁）时内容不得可见——包括重启后初始选中恰好是受保护分类的
    // 场景（旧逻辑只挡"切换进入"的点击，不挡默认选中直达）。
    val selectedCategoryLocked = remember(
        selectedCategory, privacyModeEnabled, protectedCategoryNames, unlockedCategoryIds, categories
    ) {
        if (!privacyModeEnabled) false
        else if (selectedCategory !in protectedCategoryNames) false
        else categories.find { it.name == selectedCategory }?.id !in unlockedCategoryIds
    }
    // 书架呼吸动画：30fps 自定义驱动（4 秒周期），视觉与 60fps 一致但绘制开销减半。
    // 真正的启动/暂停逻辑放在 homeGridState 之后（需要读滚动状态）——
    // 滚动期间冻结呼吸，见下方 LaunchedEffect。
    val breathingProgress = remember { mutableFloatStateOf(0f) }

    // Filter books by category and search query (debounced)
    // 第七轮第 6.1/6.2 条：分类互斥单归属——选中分类只显示归属它的书；
    // 6.3：锁定状态下的受保护分类不显示内容（PIN 解锁后才可见）
    val filteredBooks = remember(books, selectedCategory, selectedCategoryLocked, debouncedQuery) {
        if (selectedCategoryLocked) emptyList()
        else books.filter { book ->
            val matchesCategory = book.category == selectedCategory
            val matchesSearch = book.title.contains(debouncedQuery, ignoreCase = true) ||
                                book.author.contains(debouncedQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    // 书架排序：0=默认导入顺序 1=按标题 A-Z 2=最近阅读优先
    var sortBy by remember { mutableIntStateOf(0) }
    var categoryToDelete by remember { mutableStateOf<com.example.data.CategoryEntity?>(null) }
    val sortedBooks = remember(filteredBooks, sortBy) {
        when (sortBy) {
            1 -> filteredBooks.sortedBy { it.title.lowercase() }
            2 -> filteredBooks.sortedByDescending { it.lastReadTime }
            else -> filteredBooks
        }
    }

    // 「我喜欢的」板块：分类筛选 + 排序（与书架各自的选中项互不干涉）
    /** 自建的收藏分类（不含兜底的「默认」）：只有一个「默认」时不显示分类栏 */
    val favCustomCategories = remember(favoriteCategories) {
        favoriteCategories.filter { it != com.example.data.favorite.FAV_DEFAULT_CATEGORY }
    }
    val favFiltered = remember(favoriteItems, favoriteCategory) {
        if (favoriteCategory == com.example.ui.favorite.ALL_FAV_CATEGORY) favoriteItems
        else favoriteItems.filter { it.favorite.categoryName == favoriteCategory }
    }
    val favSorted = remember(favFiltered, favoriteSort) {
        when (favoriteSort) {
            com.example.ui.favorite.FavoriteSort.RECENT_FAVORITE ->
                favFiltered.sortedByDescending { it.favorite.favoritedAt }
            com.example.ui.favorite.FavoriteSort.RECENT_READ ->
                favFiltered.sortedByDescending { it.progress?.lastReadAt ?: 0L }
            com.example.ui.favorite.FavoriteSort.RECENT_UPDATE ->
                favFiltered.sortedByDescending { it.favorite.latestChapterUpdateAt }
            com.example.ui.favorite.FavoriteSort.TITLE ->
                favFiltered.sortedBy { it.favorite.title.lowercase() }
            com.example.ui.favorite.FavoriteSort.CUSTOM ->
                favFiltered.sortedBy { it.favorite.sortOrder }
        }
    }

    // 分类删除确认对话框（第七轮第 6.1 条："默认"分类不可删除；删除时书籍迁回默认）
    if (categoryToDelete != null) {
        val cat = categoryToDelete!!
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val isDefaultCategory = cat.name == com.example.data.DEFAULT_CATEGORY
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text(if (isDefaultCategory) "无法删除" else "删除分类", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isDefaultCategory) "默认分类不可删除。"
                    else "确定要删除分类「${cat.name}」吗？其中的书籍会移动到「默认」分类。"
                )
            },
            confirmButton = {
                if (!isDefaultCategory) {
                    TextButton(onClick = {
                        onDeleteCategory?.invoke(cat) { deleted ->
                            if (!deleted) {
                                AppToast.makeText(
                                    ctx,
                                    "默认分类不可删除",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        if (selectedCategory == cat.name) onShelfCategoryChange(com.example.data.DEFAULT_CATEGORY)
                        categoryToDelete = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { categoryToDelete = null }) { Text("知道了") }
                }
            },
            dismissButton = {
                if (!isDefaultCategory) {
                    TextButton(onClick = { categoryToDelete = null }) { Text("取消") }
                }
            }
        )
    }

    // 批量分类 Sheet（我的书架 / 我喜欢的 共用组件，语义各自不同）
    // bulkCategoryTarget：0 = 我的书架的书；1 = 我喜欢的条目
    if (bulkCategoryTarget >= 0) {
        val forFavorites = bulkCategoryTarget == 1
        val pickedKeys = shelfSelection.selected.toList()
        com.example.ui.shelf.CategoryPickerSheet(
            categories = if (forFavorites) favCustomCategories else categories.map { it.name },
            title = "移动到分类",
            onPick = { name ->
                if (forFavorites) {
                    val keys = pickedKeys.filter { com.example.ui.shelf.isFavShelfKey(it) }
                        .map { it.removePrefix(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX) }
                    val old = keys.mapNotNull { k ->
                        favSorted.find { it.key == k }?.let { k to it.favorite.categoryName }
                    }
                    onMoveFavoritesToCategory(keys, name)
                    onShowUndo?.invoke("已移动 ${keys.size} 本到『$name』") {
                        old.forEach { (k, c) -> onMoveFavoritesToCategory(listOf(k), c) }
                    }
                } else {
                    // ⚠️ 用**全部书**而不是当前分类过滤后的 sortedBooks：
                    // 选中两本时如果其中一本刚被移走、列表还没刷新，
                    // 按 sortedBooks 找就只能找到一本 —— 表现就是「选了两本却只移动一本」。
                    val list = books.filter { it.id.toString() in pickedKeys }
                    list.forEach { onMoveBook(it, name) }
                    // 混选（书架的书 + 我喜欢的条目）时不能只动书：
                    // 以前收藏那部分被静默丢下 —— 就是「选了两本却只移动一本」。
                    val alsoFavs = pickedKeys.filter { com.example.ui.shelf.isFavShelfKey(it) }
                        .map { it.removePrefix(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX) }
                    if (alsoFavs.isNotEmpty()) onMoveFavoritesToCategory(alsoFavs, name)
                    onShowUndo?.invoke("已移动 ${list.size + alsoFavs.size} 本到『$name』") {
                        list.forEach { onMoveBook(it, selectedCategory) }
                    }
                }
                shelfSelection.exitSelection()
                bulkCategoryTarget = -1
            },
            onDismiss = { bulkCategoryTarget = -1 },
            onCreate = { name ->
                if (forFavorites) onAddFavoriteCategory(name)
                else onAddCategory(name)
                pendingNewCategoryKeys = pickedKeys
                bulkCategoryTarget = -1
            },
        )
    }

    // 「更多」菜单：我的书架 → 标记为已读完；我喜欢的 → 检查更新
    if (showMoreSheet) {
        com.example.ui.shelf.MoreActionSheet(
            titles = listOf("标记为已读完", "取消全选"),
            onDismiss = { showMoreSheet = false },
            onPick = { label ->
                when (label) {
                    "标记为已读完" -> {
                        sortedBooks.filter { it.id.toString() in shelfSelection.selected }
                            .forEach { onMarkFinished(it) }
                        shelfSelection.exitSelection()
                    }
                    "取消全选" -> shelfSelection.exitSelection()
                }
                showMoreSheet = false
            },
        )
    }

    // Move dialog
    if (bookToMove != null) {
        AcrylicDialog(
            onDismissRequest = { bookToMove = null },
            title = { Text("选择目标分类", fontWeight = FontWeight.Bold) },
            text = {
                androidx.compose.foundation.layout.Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val availableCategories = categories.filter { it.name != bookToMove?.category }
                    if (availableCategories.isEmpty()) {
                        Text(
                            "没有其他可用的分类。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        availableCategories.forEach { cat ->
                            TextButton(
                                onClick = {
                                    onMoveBook(bookToMove!!, cat.name)
                                    bookToMove = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(cat.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { bookToMove = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Delete dialog
    if (bookToDelete != null) {
        AcrylicDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("移出此书", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确认要将《${bookToDelete?.title}》从书架中移出吗？",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookToDelete?.let { onDeleteBook(it) }
                        bookToDelete = null
                    }
                ) {
                    Text("确认移出", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 两套分类共用新建窗口的 UI，保存回调各自独立。
    if (showAddCategoryDialog) {
        NewCategoryDialog(
            title = "新建书架分类",
            description = "用于「我的书架」里的本地书籍。",
            name = newCategoryText,
            onNameChange = { newCategoryText = it },
            onDismiss = { showAddCategoryDialog = false },
            onCreate = { name ->
                onAddCategory(name)
                AppToast.makeText(context, "已创建分类「$name」", android.widget.Toast.LENGTH_SHORT).show()
                newCategoryText = ""
                showAddCategoryDialog = false
            },
        )
    }

    /* ── 「我喜欢的」分类：新建 / 重命名 / 删除（独立体系，与书架分类互不影响） ── */

    if (showAddFavoriteCategoryDialog) {
        NewCategoryDialog(
            title = "新建收藏分类",
            description = "只用于「我喜欢的」，不会出现在书架的分类里。",
            name = newFavoriteCategoryText,
            onNameChange = { newFavoriteCategoryText = it },
            onDismiss = { showAddFavoriteCategoryDialog = false },
            onCreate = { name ->
                onAddFavoriteCategory(name)
                newFavoriteCategoryText = ""
                showAddFavoriteCategoryDialog = false
            },
        )
    }

    // 长按收藏分类 → 重命名 / 删除
    if (favCategorySheetFor != null) {
        com.example.ui.shelf.MoreActionSheet(
            titles = listOf("重命名分类", "删除分类"),
            onPick = { pick ->
                val name = favCategorySheetFor
                favCategorySheetFor = null
                if (name != null) {
                    when (pick) {
                        "重命名分类" -> favCategoryRenameFor = name
                        "删除分类" -> favCategoryDeleteFor = name
                    }
                }
            },
            onDismiss = { favCategorySheetFor = null },
        )
    }

    if (favCategoryRenameFor != null) {
        var text by remember(favCategoryRenameFor) { mutableStateOf(favCategoryRenameFor ?: "") }
        AcrylicDialog(
            onDismissRequest = { favCategoryRenameFor = null },
            title = { Text("重命名分类", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("名称", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedLabelColor = MintPrimary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedIndicatorColor = MintPrimary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MintPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val old = favCategoryRenameFor
                        if (old != null && text.isNotBlank()) {
                            onRenameFavoriteCategory(old, text.trim())
                            if (favoriteCategory == old) favoriteCategory = text.trim()
                        }
                        favCategoryRenameFor = null
                    }
                ) {
                    Text("保存", color = MintPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { favCategoryRenameFor = null }) { Text("取消") }
            }
        )
    }

    if (favCategoryDeleteFor != null) {
        val name = favCategoryDeleteFor ?: ""
        AcrylicDialog(
            onDismissRequest = { favCategoryDeleteFor = null },
            title = { Text("删除收藏分类", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "删除后，该分类里的收藏会移到「默认」，收藏本身不会被删除。书架分类不受影响。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFavoriteCategory(name)
                        if (favoriteCategory == name) {
                            favoriteCategory = com.example.ui.favorite.ALL_FAV_CATEGORY
                        }
                        favCategoryDeleteFor = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { favCategoryDeleteFor = null }) { Text("取消") }
            }
        )
    }

    /* ── 「我喜欢的」隐私锁：验证 PIN 后本进程内一直可见 ── */
    if (favoritesPinFor) {
        com.example.ui.privacy.PrivacyPinOverlay(
            mode = com.example.ui.privacy.PinEntryMode.VERIFY,
            onPinSet = { },
            onPinVerified = { pin ->
                val ok = onVerifyPrivacyPin?.invoke(pin) ?: false
                if (ok) {
                    favoritesUnlocked = true
                    favoritesPinFor = false
                }
                ok
            },
            onDismiss = { favoritesPinFor = false },
        )
    }

    /**
     * 数据自愈：把掉进「不存在的分类」的书捞回默认分类。
     *
     * ⚠️ 为什么会存在这种书：落点几何表只写不删，分类被删/改名后矩形仍然生效，
     * 手指划过那块空白会"命中"一个不存在的分类名，书就被写上了这个分类 ——
     * 于是它在**任何**分类下都不显示，用户眼里就是"书莫名其妙找不到了"。
     * 这些书在数据库里好好的，只是分类名是孤儿，所以能救回来。
     *
     * 只在分类列表已经加载出来之后才动手（否则一上来会把所有书都当孤儿）。
     */
    LaunchedEffect(books, categories) {
        if (categories.isEmpty()) return@LaunchedEffect
        val names = categories.map { it.name }.toHashSet()
        val orphans = books.filter { it.category !in names }
        if (orphans.isNotEmpty()) {
            android.util.Log.w("ShelfHeal", "捞回 ${orphans.size} 本掉进不存在分类的书")
            orphans.forEach { onMoveBook(it, com.example.data.DEFAULT_CATEGORY) }
        }
    }

    /**
     * 拖拽宿主用来裁剪几何表的"存活名单"。
     *
     * ⚠️ 几何表只写不删（`onGloballyPositioned` 没有 dispose 回调）：书被移动到别的
     * 分类后它的矩形会永久留下，变成点不动/选不中的幽灵热区。所以每次书目变化都要
     * 把"现在真实存在的 key"交给宿主裁一遍。
     * 用 remember 包住，避免每次重组都新建一个 Set 把宿主的 LaunchedEffect 反复重启。
     */
    val shelfAliveKeys = remember(sortedBooks, favSorted) {
        val s = HashSet<String>()
        sortedBooks.forEach { s.add(it.id.toString()) }
        favSorted.forEach { s.add(com.example.ui.shelf.favShelfKey(it.key)) }
        s
    }

    /** 书架网格自身在窗口中的位置（命中兜底要用） */
    var booksGridRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 主书架滚动 → 卡片惯性倾斜信号源（任务书「整卡倾斜」§2）；同时驱动头部折叠（原声明在内容分支内，头部够不到，上提）
    val homeGridState = rememberLazyGridState()
    homeGridState.scrollTiltSource()
    val homeHeaderCollapsedSource = rememberHeaderCollapsedSource(homeGridState)

    /**
     * 命中**兜底**：卡片的坐标回调不可靠（槽位复用时整帧都不触发，见
     * [com.example.ui.shelf.ShelfSelectionState.registerItem]），所以再准备一条
     * 直接问网格「现在哪些格子摆在屏幕的哪」的路子。网格自己是常驻节点，
     * 坐标一定是最新的，于是切换分类后的**第一次**点击也能命中。
     */
    val booksFallbackHit: (androidx.compose.ui.geometry.Offset) -> String? = { root ->
        val g = booksGridRoot
        var hit: String? = null
        for (info in homeGridState.layoutInfo.visibleItemsInfo) {
            val ox = g.x + info.offset.x
            val oy = g.y + info.offset.y
            if (root.x >= ox && root.x <= ox + info.size.width &&
                root.y >= oy && root.y <= oy + info.size.height
            ) {
                hit = info.key.toString()
                break
            }
        }
        hit
    }

    // 呼吸动画的时钟：只有「有书 且 列表不在滚动 且 不在多选/拖拽」时才走。
    // 冻结的三个理由，缺一不可：
    //  1. 滚动中冻结是苹果式取舍——手指在动时，视野里不该有别的东西也在动；
    //  2. 顺带把滚动期间的每帧重绘需求去掉（这是 jank 的主要来源之一）；
    //  3. 多选/拖拽中必须冻结：卡片的 itemRect 是拖拽命中与落点的唯一依据，
    //     卡片若还在 ±3dp 上下浮动，落点矩形会跟着抖，出现"明明对准了却放不进去"。
    // collectLatest 在状态翻转时会取消内层循环，相位保留在 breathingProgress 里，
    // 因此恢复后是「接着原来的相位继续」，不会跳变。
    LaunchedEffect(books.isNotEmpty()) {
        if (books.isEmpty()) return@LaunchedEffect
        val twoPi = (2.0 * Math.PI).toFloat()
        val step = twoPi / 120f // 4000ms / 33ms ≈ 121 步
        snapshotFlow {
            homeGridState.isScrollInProgress ||
                shelfSelection.phase != com.example.ui.shelf.ShelfPhase.BROWSE
        }.collectLatest { busy ->
            if (busy) return@collectLatest
            while (true) {
                delay(33)
                breathingProgress.floatValue = (breathingProgress.floatValue + step) % twoPi
            }
        }
    }

    StarryNightBackground(showLamp = true) {
        com.example.ui.shelf.ShelfSelectionHost(
            state = shelfSelection,
            categories = categories.map { it.name },
            currentCategory = selectedCategory,
            showFavoriteTarget = true,
            onDropToCategory = drop@{ keys, name ->
                // ⚠️ 最后一道保险：落点矩形可能残留（分类被删了、改名了，矩形还在），
                // 一旦把书写进一个**不存在的分类名**，它在任何分类下都不显示 ——
                // 对用户来说就是「书凭空消失了」。宁可这次不动，也不能把书弄丢。
                if (categories.none { it.name == name }) {
                    onShowUndo?.invoke("『$name』不是可用的分类，没有移动") { }
                    return@drop
                }
                // ⚠️ 被拖的条目**不一定是书**：「我喜欢的」的卡片也会被拖上来，
                // 它们的 key 带 fav:: 前缀。曾经这里只写
                // `sortedBooks.filter { it.id.toString() in keys }`，收藏一条都匹配不到
                // ⇒ list 为空 ⇒ 什么都不做，但提示照弹 —— 表现就是
                // 「我喜欢的拖进分类，提示操作出错，而且确实没移动到别的分类」。
                val bookKeys = keys.filterNot { com.example.ui.shelf.isFavShelfKey(it) }
                val favKeys = keys
                    .filter { com.example.ui.shelf.isFavShelfKey(it) }
                    .map { it.removePrefix(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX) }

                val list = books.filter { it.id.toString() in bookKeys }
                list.forEach { onMoveBook(it, name) }
                // 收藏落到书架分类胶囊 → 归到「我喜欢的」的**同名**分类。
                // 两套分类表是分开的（categories / favorite_categories），但用户眼里
                // "分类"就是那个名字：拖到哪个名字就归到哪个名字。
                // 名字在收藏侧还不存在也没关系，仓库的 ensureFavoriteCategory 会先建。
                val favUndo = if (favKeys.isNotEmpty()) {
                    val prev = favKeys.mapNotNull { k ->
                        favoriteItems.find { it.key == k }?.favorite?.categoryName?.let { k to it }
                    }
                    onMoveFavoritesToCategory(favKeys, name)
                    prev
                } else emptyList()

                val total = list.size + favKeys.size
                if (total > 0) {
                    onShowUndo?.invoke("已移动 $total 本到『$name』") {
                        list.forEach { onMoveBook(it, selectedCategory) }
                        favUndo.forEach { (k, old) -> onMoveFavoritesToCategory(listOf(k), old) }
                    }
                }
            },
            onDropToFavorite = { keys ->
                val list = sortedBooks.filter { it.id.toString() in keys }
                val (ok, skipped) = onFavoriteSelectedBooks?.invoke(list) ?: (0 to 0)
                val msg = if (skipped > 0) "已喜欢 $ok 本，$skipped 本无来源已跳过" else "已加入我喜欢的 $ok 本"
                onShowUndo?.invoke(msg) { }
            },
            onDropToFavoriteCategory = { keys, name ->
                // 同理：**先按 key 前缀分流**。
                // - 书架的书 → 加入「我喜欢的」并归入该分类（原有行为）；
                // - 「我喜欢的」自己的卡片 → 直接改它所属的分类。
                val bookKeys = keys.filterNot { com.example.ui.shelf.isFavShelfKey(it) }
                val favKeys = keys
                    .filter { com.example.ui.shelf.isFavShelfKey(it) }
                    .map { it.removePrefix(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX) }

                val list = books.filter { it.id.toString() in bookKeys }
                val (ok, skipped) = if (list.isNotEmpty()) {
                    onFavoriteSelectedBooksToCategory?.invoke(list, name) ?: (0 to 0)
                } else (0 to 0)

                val favUndo = if (favKeys.isNotEmpty()) {
                    val prev = favKeys.mapNotNull { k ->
                        favoriteItems.find { it.key == k }?.favorite?.categoryName?.let { k to it }
                    }
                    if (prev.any { (_, old) -> old != name }) onMoveFavoritesToCategory(favKeys, name)
                    prev
                } else emptyList()

                val msg = when {
                    favKeys.isNotEmpty() && list.isEmpty() ->
                        "已移动 ${favKeys.size} 本到『$name』"
                    skipped > 0 -> "已加入『$name』$ok 本，$skipped 本无来源已跳过"
                    else -> "已加入『$name』$ok 本"
                }
                onShowUndo?.invoke(msg) {
                    favUndo.forEach { (k, old) -> onMoveFavoritesToCategory(listOf(k), old) }
                }
            },
            onCreateCategoryThenDrop = { keys ->
                pendingNewCategoryKeys = keys
                showAddCategoryDialog = true
            },
            aliveKeys = shelfAliveKeys,
            favoriteCategories = favCustomCategories,
            fallbackHit = booksFallbackHit,
            onTapItem = onTapItem@ { key ->
                // 多选名单**按板块独立**：一旦进入多选，另一板块的卡片一律直接 return
                // —— 不改名单、不给触觉反馈、不进入拖拽。于是「书架里点我喜欢的卡片」
                // 或反过来都不会把两个栏目混进同一个名单（浏览态不受影响，照旧打开）。
                if (shelfSelection.phase != com.example.ui.shelf.ShelfPhase.BROWSE &&
                    !shelfSelection.accepts(key)
                ) {
                    return@onTapItem
                }
                if (com.example.ui.shelf.isFavShelfKey(key)) {
                    val fav = favSorted.find { com.example.ui.shelf.favShelfKey(it.key) == key }
                    when {
                        fav == null -> Unit
                        shelfSelection.phase == com.example.ui.shelf.ShelfPhase.BROWSE -> onOpenFavorite(fav)
                        shelfSelection.isRecentAutoSelect() -> Unit
                        else -> shelfSelection.toggle(key)
                    }
                } else {
                    val tapped = sortedBooks.find { it.id.toString() == key }
                    when {
                        tapped == null -> Unit
                        shelfSelection.phase == com.example.ui.shelf.ShelfPhase.BROWSE -> onBookClick(tapped)
                        // 长按刚自动选中这本，紧随其后的抬手不算一次点击（否则会立刻取消选中）
                        shelfSelection.isRecentAutoSelect() -> Unit
                        else -> shelfSelection.toggle(key)
                    }
                }
            },
            scrollBy = { homeGridState.scrollBy(it) },
            coverOf = { key -> ShelfDragCover(books = books, key = key, favorites = favSorted) },
            modifier = Modifier.fillMaxSize(),
        ) { itemBounds ->
                    // 整页内容 + 多选浮层同处一个 Box：浮层用 align 叠上去，
                    // 绝不参与 Column 的布局，因此不会把内容顶下去。
                    Box(modifier = Modifier.fillMaxSize()) {
                    // 本次多选名单归属于哪个板块（进入多选时由第一个被长按的条目决定）。
                    // 顶栏的「全选/取消全选」和操作栏的 hint 都要按它分流 ——
                    // 不能再靠"哪一边的选中列表恰好非空"去猜，那正是"混选"的来源。
                    val favScope = shelfSelection.scope ==
                        com.example.ui.shelf.ShelfSelectionScope.FAV
                    // 顶栏「全选」的作用域名单 + 是否已全选。
                    // 在这里（AnimatedVisibility 之外）算好，避免把顶栏那段代码撑长。
                    val favKeys = if (favScope) {
                        favSorted.map { com.example.ui.shelf.favShelfKey(it.key) }
                    } else {
                        sortedBooks.map { it.id.toString() }
                    }
                    val allSelected = favKeys.isNotEmpty() &&
                        favKeys.all { it in shelfSelection.selected }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 1. TOP BAR GREETINGS & SEARCH
                        val isDark = MaterialTheme.colorScheme.background == com.example.ui.theme.DarkCharcoal
                        val barBgColor = if (isDark) Color(0xFF222428) else Color.White
                        val searchPlaceholderColor = if (isDark) Color.LightGray.copy(alpha = 0.6f) else Color.Gray
                        val searchTextColor = if (isDark) Color.White else Color.DarkGray
                        val iconBtnBgColor = if (isDark) Color(0xFF2B2D31) else Color(0xFFF4F4F4)
                        val iconCloseTint = if (isDark) Color.LightGray else Color.DarkGray

                        TabScreenHeader(
                            collapsedSource = homeHeaderCollapsedSource,
                            title = "我的书架",
                            // 与其它三页页头副标题统一英文风格（LIBRARY & SEARCH / STATISTICS & INSIGHTS / SETTINGS & PREFERENCES）
                            subtitle = "BOOKSHELF & READING",
                            titleColor = glassTitleColor(),
                            titleVisible = !isSearchExpanded,
                            trailing = {
                                AnimatedVisibility(
                                    visible = isSearchExpanded,
                                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("输入书名或作者", color = searchPlaceholderColor, fontSize = 13.sp) },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 13.sp,
                                            color = searchTextColor
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = searchTextColor,
                                            unfocusedTextColor = searchTextColor,
                                            focusedBorderColor = MintPrimary,
                                            unfocusedBorderColor = if (isDark) Color(0xFF383A40) else Color.LightGray,
                                            focusedContainerColor = if (isDark) Color(0xFF2B2D31) else Color.White,
                                            unfocusedContainerColor = if (isDark) Color(0xFF2B2D31) else Color.White
                                        ),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .padding(end = 8.dp),
                                        trailingIcon = {
                                            AppIconButton(onClick = {
                                                searchQuery = ""
                                                isSearchExpanded = false
                                            }) {
                                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = iconCloseTint)
                                            }
                                        }
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // MAIN INTERFACE SCROLLABLE LAYOUT
                        // 「我喜欢的」是并列板块：即使书架一本本地书都没有，只要还有收藏，
                        // 也得把整页（含收藏板块）渲染出来 —— 只有两边都空才走整页空态。
                        if (books.isEmpty() && favoriteItems.isEmpty()) {
                            // EXQUISITE EMPTY STATE
                            var isVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                isVisible = true
                            }
                            val alpha by animateFloatAsState(
                                targetValue = if (isVisible) 1f else 0f,
                                animationSpec = tween(durationMillis = 400, easing = EaseOut),
                                label = "empty_alpha"
                            )
                            val offsetY by animateDpAsState(
                                targetValue = if (isVisible) 0.dp else (-8).dp,
                                animationSpec = tween(durationMillis = 400, easing = EaseOut),
                                label = "empty_offset"
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .alpha(alpha)
                                    .offset(y = offsetY),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                com.example.ui.components.MascotEmptyState(
                                    mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                                    title = "「书架空空如也」",
                                    description = "您的书架还没有任何书哦！Roxy 觉得有点寂寞。您可以点击下方按钮导入本地电子书，或者直接去在线书库挑选精彩的小说！",
                                    actionLabel = "立即前往在线书库",
                                    onActionClick = onNavigateToShelf,
                                    testTagPrefix = "books_empty_state"
                                )
                    
                                Spacer(modifier = Modifier.height(8.dp))
                    
                                TextButton(
                                    onClick = { onImportClick(com.example.data.DEFAULT_CATEGORY) },
                                    modifier = Modifier.testTag("empty_bookshelf_import_button")
                                ) {
                                    Text("或者 导入本地 TXT / EPUB / Comic 文件", color = MintPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // RICH PREMIUM CONTENT STATE
                            val screenWidthDp = LocalConfiguration.current.screenWidthDp
                            // 多设备一致：按宽度自适应列数（手机3列 / 折叠展开与小平板4列 / 宽平板最多6列），
                            // 不再只有 3/4 两档——宽屏拉伸大卡片的问题源头
                            val cols = ((screenWidthDp + 24) / 150).coerceIn(3, 6)

                            // 第七轮第 6.3 条：Hero"正在阅读"卡片同样是内容泄漏面——
                            // 其书籍所在分类处于锁定态时整卡隐藏（PIN 解锁后恢复）
                            val heroCategoryLocked = remember(
                                currentlyReading.category, privacyModeEnabled,
                                protectedCategoryNames, unlockedCategoryIds, categories
                            ) {
                                if (!privacyModeEnabled) false
                                else if (currentlyReading.category !in protectedCategoryNames) false
                                else categories.find { it.name == currentlyReading.category }?.id !in unlockedCategoryIds
                            }

                            // 第九轮修复⑤续：曾在此处叠加"内容纱罩"全尺寸渐变矩形（浅色主题下
                            // 呈半透明白色大矩形盖住整个内容区，用户反馈遮挡/点击异常）——已从
                            // 视图层彻底移除，书籍网格直接承载在页面背景之上。
                            // ⚠️ 多选态顶部栏**不在这里**。
                            // 它曾经是 Column 的一个子节点（用 expandVertically 展开），
                            // 一旦出现就把下方整屏内容顶下去 ~56dp：
                            //   · 长按瞬间整页跳一下（用户反馈的"闪烁"）；
                            //   · 更要命的是 itemRects 记录的书卡位置瞬间全部失效，
                            //     手指还按着却命中到了另一张卡 → 选中又被取消。
                            // 现在改成页面顶部的**浮层**（见下方 Box 里的 align(TopCenter)），
                            // 不占布局高度，内容一动不动。

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(cols),
                                state = homeGridState,
                                // ⚠️ 拖拽中必须关掉网格自身的滚动：
                                // 宿主虽然会 consume 掉指针事件，但网格在更早的 pass 里已经把它
                                // 当成一次普通滑动吃掉了 —— 实测手指拖着书往上走时，
                                // 背景自己滚了 800px，把分类栏整条滚出屏幕，根本没法放。
                                // 拖拽期间的滚动改由宿主的边缘自动滚动（scrollBy）独占。
                                userScrollEnabled = shelfSelection.phase !=
                                    com.example.ui.shelf.ShelfPhase.DRAGGING,
                                // ⚠️ 网格自身的 root 位置：命中兜底要用（见 [booksFallbackHit]）。
                                // 网格是**常驻**节点，切换分类不会重建它，所以它的坐标一定是最新的；
                                // 而卡片会复用槽位，坐标回调可能整帧不触发。
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onGloballyPositioned { booksGridRoot = it.positionInRoot() },
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!heroCategoryLocked && books.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "hero_section") {
                                    // 2. HERO: RECENTLY READ BIG BOOK CARD (pre-calculated and remembered at top)

                                    val heroCoverData = remember(currentlyReading.coverUri, currentlyReading.isCoverValid) {
                                        if (currentlyReading.coverUri.isNullOrEmpty()) null
                                        else if (currentlyReading.coverUri!!.startsWith("content://")) {
                                            android.net.Uri.parse(currentlyReading.coverUri!!)
                                        } else if (currentlyReading.isCoverValid) {
                                            val path = if (currentlyReading.coverUri!!.startsWith("file://")) currentlyReading.coverUri!!.substring(7) else currentlyReading.coverUri!!
                                            java.io.File(path)
                                        } else {
                                            null
                                        }
                                    }
                                    val heroHasValidCover = heroCoverData != null

                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    val heroImageRequest = remember(currentlyReading.coverUri, heroCoverData) {
                                        if (heroCoverData == null) null else {
                                            coil.request.ImageRequest.Builder(context)
                                                .data(heroCoverData)
                                                .memoryCacheKey(currentlyReading.coverUri)
                                                .diskCacheKey(currentlyReading.coverUri)
                                                .crossfade(true)
                                                .build()
                                        }
                                    }

                                    Column {
                                        Text(
                                            text = "正在阅读",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = adaptiveTitleColor(),
                                            fontFamily = AppFonts.Serif,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        GlassCard(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(16.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Large exquisite procedural book cover with light novel style
                                                Box(
                                                    modifier = Modifier
                                                        .size(width = 110.dp, height = 155.dp)
                                                        .background(
                                                            Brush.linearGradient(
                                                                colors = listOf(
                                                                    MintSecondary,
                                                                    Color(0xFF1B143F),
                                                                    MintPrimary
                                                                )
                                                            ),
                                                            RoundedCornerShape(12.dp)
                                                        )
                                                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                                        .consistentShadow(6.dp, RoundedCornerShape(12.dp))
                                                        .clip(RoundedCornerShape(12.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (heroHasValidCover && heroImageRequest != null) {
                                                        AsyncImage(
                                                            model = heroImageRequest,
                                                            contentDescription = currentlyReading.title,
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Crop
                                                        )
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .background(
                                                                    Brush.horizontalGradient(
                                                                        colors = listOf(
                                                                            Color.Black.copy(alpha = 0.15f),
                                                                            Color.Transparent,
                                                                            Color.Black.copy(alpha = 0.05f)
                                                                        ),
                                                                        startX = 0f,
                                                                        endX = 40f
                                                                    )
                                                                )
                                                        )
                                                    } else {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                            modifier = Modifier.padding(12.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.AutoAwesome,
                                                                contentDescription = null,
                                                                tint = MintGold,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                            Spacer(modifier = Modifier.height(10.dp))
                                                            Text(
                                                                text = currentlyReading.title,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White,
                                                                maxLines = 3,
                                                                overflow = TextOverflow.Ellipsis,
                                                                textAlign = TextAlign.Center,
                                                                lineHeight = 16.sp
                                                            )
                                                        }
                                                    }

                                                    // Decorative spine overlay on top of everything
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .width(6.dp)
                                                            .background(Color.Black.copy(alpha = 0.15f))
                                                            .align(Alignment.CenterStart)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(16.dp))

                                                Column(
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(
                                                        text = currentlyReading.title,
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontFamily = AppFonts.Serif
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "著  ${currentlyReading.author}",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(14.dp))

                                                    // Calculate progress percentage safely
                                                    val progressPercent = if (currentlyReading.totalChapters > 0) {
                                                        (currentlyReading.currentChapterIndex * 100) / currentlyReading.totalChapters
                                                    } else {
                                                        32 // Beautiful fallback progress
                                                    }

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { progressPercent / 100f },
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .height(6.dp)
                                                                .clip(CircleShape),
                                                            color = MintPrimary,
                                                            trackColor = Color.Black.copy(alpha = 0.05f),
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Text(
                                                            text = "已读 $progressPercent%",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MintGold
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(16.dp))

                                                    AppActionButton(
                                                        text = "继续阅读",
                                                        onClick = { onBookClick(currentlyReading) },
                                                        variant = AppButtonVariant.Primary,
                                                        buttonSize = AppButtonSize.Small,
                                                        modifier = Modifier.align(Alignment.End)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                item(span = { GridItemSpan(maxLineSpan) }, key = "bookshelf_header") {
                                    // 3. MAIN SECTION: MY BOOKSHELF
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "我的书架",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = adaptiveTitleColor(),
                                                fontFamily = AppFonts.Serif
                                            )

                                            // 第七轮第 5.1 条：三个次级操作统一为有质感的玻璃小胶囊
                                            // （图标+文字+轻背景描边），与标题形成明确主次——不过重、不抢内容
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                HomeActionChip(
                                                    icon = Icons.Filled.Sort,
                                                    label = when (sortBy) { 0 -> "默认"; 1 -> "A-Z"; else -> "最近" },
                                                    emphasized = sortBy > 0,
                                                ) { sortBy = (sortBy + 1) % 3 }

                                                Spacer(modifier = Modifier.width(6.dp))

                                                HomeActionChip(
                                                    icon = Icons.Filled.Add,
                                                    label = "导入新书",
                                                    emphasized = true,
                                                ) { onImportClick(selectedCategory) }

                                                Spacer(modifier = Modifier.width(6.dp))

                                                HomeActionChip(
                                                    icon = Icons.Filled.Category,
                                                    label = "新建分类",
                                                    emphasized = false,
                                                ) { showAddCategoryDialog = true }
                                            }
                                        }

                                        // 第七轮第 5.2/6.1 条：分类 Tab——可横向滚动的真实分类列表
                                        // （"全部"聚合视图已移除，"默认"为不可删除的真实分类）。
                                        // 受保护分类带锁标记，进入需 PIN 验证；长按弹出分类操作面板。
                                        // ⚠️ 拖拽期间要关掉分类栏自己的横向滚动：手指在胶囊上左右
                                        // 挪动时会把这排胶囊滑走，落点矩形跟着漂，
                                        // 表现就是"手指明明没对准分类，却触发了放进分类"。
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(
                                                    rememberScrollState(),
                                                    enabled = shelfSelection.phase !=
                                                        com.example.ui.shelf.ShelfPhase.DRAGGING,
                                                )
                                                .padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            categories.forEach { cat ->
                                                val isSelected = selectedCategory == cat.name
                                                val isProtected = privacyModeEnabled &&
                                                    protectedCategoryNames.contains(cat.name)
                                                val isUnlocked = unlockedCategoryIds.contains(cat.id)
                                                val locked = isProtected && !isUnlocked
                                                // 拖拽放置目标就是这一排分类胶囊本身（不再另起底部 Dock）。
                                                //
                                                // ⚠️ 当前所处的分类**不注册**为落点。
                                                // 之前注册了，结果 endDrag() 认为"命中了某个 target"
                                                // → 直接 phase=BROWSE 并清空选中；而业务层又判定
                                                // "放进当前分类 = 没动" 走回弹 —— 于是书弹回来了，
                                                // 多选却被退掉了，用户的选中白费。
                                                // 不注册就是落在非放置区：保持选中 + 优雅回弹，正合预期。
                                                val isCurrentCategory = cat.name == selectedCategory
                                                val dropId = com.example.ui.shelf.dropTargetOfCategory(cat.name)
                                                com.example.ui.shelf.CategoryPill(
                                                    name = cat.name,
                                                    selected = isSelected,
                                                    locked = locked,
                                                    count = books.count { it.category == cat.name },
                                                    onClick = {
                                                        when {
                                                            // 受保护且未解锁 → 先验证 PIN，通过后才进入
                                                            locked -> pinVerifyFor = cat
                                                            // 已解锁或普通分类 → 直接切换
                                                            else -> onShelfCategoryChange(cat.name)
                                                        }
                                                    },
                                                    onLongClick = { categorySheetFor = cat },
                                                    modifier = if (isCurrentCategory) Modifier
                                                    else shelfSelection.dropTargetModifier(dropId),
                                                    dropHighlight = !isCurrentCategory &&
                                                        shelfSelection.hoverTarget == dropId,
                                                )
                                            }
                                        }
                                    }
                                }


            if (sortedBooks.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty_state", contentType = "fullspan") {
                                        if (selectedCategoryLocked) {
                                            // 6.3：受保护分类锁定态——不显示内容，引导验证密码
                                            // 第九轮修复⑤：不再整块白色大卡（用户反馈"书架下面盖了
                                            // 一层透明白色矩形"）——改为克制的行内玻璃胶囊，锁定
                                            // 提示+验证按钮一行呈现，与书架玻璃语言一致。
                                            val lockedCat = categories.find { it.name == selectedCategory }
                                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
                                                        .border(
                                                            0.5.dp,
                                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                                            RoundedCornerShape(18.dp)
                                                        )
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Lock,
                                                        contentDescription = null,
                                                        tint = MintPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            text = "分类『$selectedCategory』已锁定",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = adaptiveTitleColor()
                                                        )
                                                        Text(
                                                            text = "验证隐私密码后即可查看其中的书籍",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                                        )
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .background(MintPrimary.copy(alpha = 0.14f))
                                                            .border(
                                                                0.5.dp,
                                                                MintPrimary.copy(alpha = 0.45f),
                                                                RoundedCornerShape(14.dp)
                                                            )
                                                            .clickableWithFeedback { lockedCat?.let { pinVerifyFor = it } }
                                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(
                                                            "验证密码查看",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MintPrimary
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            com.example.ui.components.MascotEmptyState(
                                                mascotResId = com.example.ui.mascot.MascotSpriteSheet.sadDrawable,
                                                title = "「分类下没有图书」",
                                                description = "在分类『$selectedCategory』下还没有任何图书哦。您可以点击下方按钮导入新书到该分类！",
                                                actionLabel = "在此分类下导入新书",
                                                onActionClick = { onImportClick(selectedCategory) },
                                                testTagPrefix = "category_empty_state"
                                            )
                                        }
                                    }
                                } else {
                                    items(items = sortedBooks, key = { it.id }, contentType = { "book" }) { book ->
                                        val coverData = remember(book.coverUri, book.isCoverValid) {
                                            if (book.coverUri.isNullOrEmpty()) null
                                            else if (book.coverUri!!.startsWith("content://")) {
                                                android.net.Uri.parse(book.coverUri!!)
                                            } else if (book.isCoverValid) {
                                                val path = if (book.coverUri!!.startsWith("file://")) book.coverUri!!.substring(7) else book.coverUri!!
                                                java.io.File(path)
                                            } else {
                                                null
                                            }
                                        }
                                        val hasValidCover = coverData != null

                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        val imageRequest = remember(book.coverUri, coverData) {
                                            if (coverData == null) null else {
                                                coil.request.ImageRequest.Builder(context)
                                                    .data(coverData)
                                                    .memoryCacheKey(book.coverUri)
                                                    .diskCacheKey(book.coverUri)
                                                    .crossfade(true)
                                                    .build()
                                            }
                                        }

                                        // 手势全部由 ShelfSelectionHost 处理（长按 / 连选 / 拖拽 / 点击）。
                                        // 卡片这里只读取宿主的按压态做视觉反馈——自己再挂
                                        // pointerInput 会在 down 时抢走事件，导致长按失效。
                                        val isPressed = shelfSelection.pressedKey == book.id.toString()
                                        // 长按由拖拽宿主接管：卡片只反映"被选中"的抬起状态
                                        // 被"拿起"跟手拖拽的那本（原位留给虚线凹槽）。
                                        // 注意：这里的缩放语义必须是"内缩"，不能是"放大"——
                                        // 曾经同时叠加 1.05（抬起）与 0.94（选中）两个 scale，
                                        // 相乘 ≈ 0.987 等于互相抵消，还给所有选中项加了 8dp 上浮，
                                        // 长按瞬间整屏卡片会集体跳一下。抬起的手感已经完全交给
                                        // 跟随手指的幽灵卡（放大 + 投影）承担，原位只负责"让位"。
                                        val isDragged = book.id.toString() in shelfSelection.dragging
                                        // ⚠️ 归位飞行期间（~560ms）卡片必须继续藏着：
                                        // 松手瞬间 dragging 就空了，只按 dragging 判定的话书会立刻浮现，
                                        // 而幽灵卡还在半空 —— 屏幕上同时出现两个不对齐的书。
                                        val isFlyingHome = book.id.toString() in shelfSelection.flyingHome
                                        val scaleAnim by animateFloatAsState(
                                            targetValue = if (isDragged) 0.92f else 1f,
                                            animationSpec = com.example.ui.feedback.AppMotion.springSettle,
                                            label = "book_drag_scale"
                                        )

                                        // 多选态：未选中的书变暗到 0.85（选中的不变）—— 用弹簧过渡，避免硬跳。
                                        // 被拿起来拖拽、以及正在飞回原位的那本都淡到 0：
                                        // 原位不能留下任何色块，只由宿主层的极淡虚线轮廓标出"位置给你留着"。
                                        val dimTarget = if (isDragged || isFlyingHome) 0f
                                        else if (shelfSelection.phase == com.example.ui.shelf.ShelfPhase.BROWSE) 1f
                                        else if (book.id.toString() in shelfSelection.selected) 1f else 0.85f
                                        val scaleTarget = if (book.id.toString() in shelfSelection.selected) {
                                            com.example.ui.feedback.AppMotion.SELECTED_SCALE
                                        } else 1f
                                        val dimAlpha by animateFloatAsState(
                                            targetValue = dimTarget,
                                            animationSpec = com.example.ui.feedback.AppMotion.springDefault,
                                            label = "book_dim",
                                        )
                                        val selectedScale by animateFloatAsState(
                                            targetValue = scaleTarget,
                                            animationSpec = com.example.ui.feedback.AppMotion.springDefault,
                                            label = "book_selected_scale",
                                        )
                                        // 按压反馈由宿主的 pressedKey 驱动（卡片自己不抢手势）
                                        val pressScale by animateFloatAsState(
                                            targetValue = if (isPressed) 0.96f else 1f,
                                            animationSpec = com.example.ui.feedback.AppMotion.springDefault,
                                            label = "book_press",
                                        )

                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier
                                                    .graphicsLayer {
                                                        val phase = (book.id.hashCode() % 1000) / 1000f * 2f * Math.PI.toFloat()
                                                        translationY = (kotlin.math.sin(breathingProgress.floatValue + phase) * 3.dp.toPx()).toFloat()
                                                        scaleX = scaleAnim * selectedScale * pressScale
                                                        scaleY = scaleAnim * selectedScale * pressScale
                                                        alpha = dimAlpha
                                                    }
                                                    .then(itemBounds(book.id.toString())),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                            val isThemeDark = MaterialTheme.colorScheme.background == com.example.ui.theme.DarkCharcoal
                                            val coverGradColors = if (isThemeDark) {
                                                listOf(
                                                    Color(0xFF2B2D31),
                                                    Color(0xFF1E2022),
                                                    MintSecondary.copy(alpha = 0.15f)
                                                )
                                            } else {
                                                listOf(
                                                    Color(0xFFF4F4F4),
                                                    Color(0xFFE3E5E7),
                                                    MintSecondary.copy(alpha = 0.3f)
                                                )
                                            }
                                
                                            var boxModifier = Modifier
                                                .aspectRatio(0.72f)
                                                .fillMaxWidth()
                                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                                with(sharedTransitionScope) {
                                                    boxModifier = boxModifier.sharedElement(
                                                        state = rememberSharedContentState(key = "book_cover_${book.id}"),
                                                        animatedVisibilityScope = animatedVisibilityScope,
                                                        boundsTransform = { _, _ ->
                                                            tween(420, easing = FastOutSlowInEasing)
                                                        }
                                                    )
                                                }
                                            }
                                            boxModifier = boxModifier
                                                // 第七轮第 5.4 条：封面卡片质感——更大圆角 + 更柔和的
                                                // 双层阴影（环境 + 投射），"书架上的书"更立体安静
                                                .consistentShadow(
                                                    elevation = 8.dp,
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                                    ambientColor = Color.Black.copy(alpha = 0.10f),
                                                    spotColor = Color.Black.copy(alpha = 0.16f)
                                                )
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                                .background(androidx.compose.ui.graphics.Brush.verticalGradient(colors = coverGradColors))
                                                .border(1.dp, Color.Black.copy(alpha = 0.07f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                                // 单独登记**封面**几何（不是整张卡）。
                                                // 归位动画的落点必须是封面中心：整卡含书名那一行，
                                                // 中心比封面低，用它当落点会让书飞到比原位低半张封面的地方，
                                                // 动画一结束就"从下面瞬移到上面"。
                                                .then(shelfSelection.coverBoundsModifier(book.id.toString()))

                                            Box(
                                                modifier = boxModifier,
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (hasValidCover && imageRequest != null) {
                                                    AsyncImage(
                                                        model = imageRequest,
                                                        contentDescription = book.title,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    // Subtle vertical shadow for paper depth
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(
                                                                Brush.horizontalGradient(
                                                                    colors = listOf(
                                                                        Color.Black.copy(alpha = 0.15f),
                                                                        Color.Transparent,
                                                                        Color.Black.copy(alpha = 0.05f)
                                                                    ),
                                                                    startX = 0f,
                                                                    endX = 40f
                                                                )
                                                            )
                                                    )
                                                } else {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.SpaceBetween,
                                                        modifier = Modifier.fillMaxHeight().padding(8.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.MenuBook,
                                                            contentDescription = null,
                                                            tint = MintPrimary.copy(alpha = 0.6f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                            
                                                        Text(
                                                            text = book.title,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 3,
                                                            overflow = TextOverflow.Ellipsis,
                                                            textAlign = TextAlign.Center,
                                                            lineHeight = 14.sp,
                                                            modifier = Modifier.padding(horizontal = 4.dp)
                                                        )

                                                        Text(
                                                            text = if (book.isComic) "漫画" else "TXT",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (book.isComic) Color(0xFFFF6B6B) else MintGold.copy(alpha = 0.8f)
                                                        )
                                                    }
                                                }

                                                // Cover Spine shadow overlay on top of everything
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .width(4.dp)
                                                        .background(Color.Black.copy(alpha = 0.2f))
                                                        .align(Alignment.CenterStart)
                                                )

                                                // 已喜欢 → 右下角小心形（有来源的书才可能已喜欢）
                                                val favKey = remember(book.sourceId, book.comicId) {
                                                    if (!book.sourceId.isNullOrBlank() && !book.comicId.isNullOrBlank()) {
                                                        "${book.sourceId}::${book.comicId}"
                                                    } else null
                                                }
                                                if (favKey != null && favKey in favoriteKeys) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(6.dp)
                                                            .size(18.dp)
                                                            .clip(CircleShape)
                                                            .background(Color.White.copy(alpha = 0.82f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        // 与飞行体、「喜欢」按钮共用同一颗渐变心
                                                        com.example.ui.favorite.HeartArt(
                                                            modifier = Modifier
                                                                .size(12.dp)
                                                                .semantics {
                                                                    contentDescription = "已加入我喜欢的"
                                                                },
                                                            glow = 0f,
                                                        )
                                                    }
                                                }

                                                // 「书被拿走了」的原位处理：**整张卡淡到全透明**。
                                                // 之前是在原位盖一块 surface@0.94 的浅色圆角块 + 虚线，
                                                // 在浅色主题下就是一块刺眼的纯白区域（用户明确反馈不好），
                                                // 而且那块白底会盖住页面背景，看起来像"破了个洞"。
                                                // 现在卡片彻底隐去（alpha 见上方 dimAlpha），原位只剩
                                                // 宿主层画的一个极淡虚线轮廓 —— 视觉上就是页面背景本身。
                                                // 注意：虚线必须在宿主层画，因为卡片的 graphicsLayer alpha
                                                // 会把画在它内部的任何东西一起淡掉。

                                                // 多选态：左上角圆形勾选框（弹簧弹出）
                                                // ⚠️ 只看 phase 是错的：在「我喜欢的」里多选时，
                                                // 书架卡片也会跟着冒出未选中的白圆圈（用户反馈）。
                                                // 必须按**板块**判定 —— 只有 scope == BOOK 时才画。
                                                if (shelfSelection.scope ==
                                                    com.example.ui.shelf.ShelfSelectionScope.BOOK
                                                ) {
                                                    Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                                                        com.example.ui.shelf.ShelfSelectBadge(
                                                            selected = book.id.toString() in shelfSelection.selected,
                                                        )
                                                    }
                                                }
                                            }
                                
                                            Spacer(modifier = Modifier.height(6.dp))
                                
                                            Text(
                                                text = book.title,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            // 读完标记：金色 ✓ + "已读完"
                                            if (book.isFinished) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Filled.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MintGold,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(
                                                        "已读完",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MintGold
                                                    )
                                                }
                                            }

                                            // 阅读进度条（已开始阅读且未完成时显示）
                                            if (book.totalChapters > 0 && book.currentChapterIndex > 0 && !book.isFinished) {
                                                val progress = book.currentChapterIndex.toFloat() / book.totalChapters
                                                LinearProgressIndicator(
                                                    progress = { progress.coerceIn(0f, 1f) },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 4.dp)
                                                        .height(3.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = MintPrimary.copy(alpha = 0.85f),
                                                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                                )
                                                Text(
                                                    text = "${book.currentChapterIndex}/${book.totalChapters} 章",
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                                )
                                            }
                                        }
                                        } // Close Box
                                    }
                                }

                            /* ══════════════════════════════════════════════════════════════════
                               「我喜欢的」板块：紧贴「我的书架」的书网格，排在阅读统计之前。
                               与书架板块完全同构：标题 + 操作胶囊 + 分类栏 + 卡片网格，
                               只是数据是收藏自己的（分类也与书架分类互不干涉）。
                               ══════════════════════════════════════════════════════════════════ */
                            item(span = { GridItemSpan(maxLineSpan) }, key = "fav_section_header") {
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "我喜欢的",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = adaptiveTitleColor(),
                                            fontFamily = AppFonts.Serif,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (favoritesLocked) {
                                                // 锁定态：整个板块只有一把锁，点它验证密码
                                                HomeActionChip(
                                                    icon = Icons.Filled.Lock,
                                                    label = "已锁定",
                                                    emphasized = true,
                                                ) { favoritesPinFor = true }
                                            } else {
                                                HomeActionChip(
                                                    icon = Icons.Filled.Refresh,
                                                    label = if (favoriteRefreshing) "检查中…" else "检查更新",
                                                    emphasized = favoriteRefreshing,
                                                ) {
                                                    if (favoriteRefreshing) return@HomeActionChip
                                                    favoriteRefreshing = true
                                                    onCheckFavoriteUpdates()
                                                    refreshScope.launch {
                                                        delay(1500)
                                                        favoriteRefreshing = false
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                HomeActionChip(
                                                    icon = Icons.Filled.Category,
                                                    label = "新建分类",
                                                    emphasized = false,
                                                ) { showAddFavoriteCategoryDialog = true }
                                            }
                                        }
                                    }

                                    // 分类栏：只在真的有自建分类时才出现 —— 只有"默认"一个
                                    // 分类时，"全部 / 默认"两个胶囊是同一批东西，纯属噪音。
                                    // 「默认」不单独列出：未分类的收藏在「全部」里就能看到。
                                    if (!favoritesLocked && favCustomCategories.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(
                                                    rememberScrollState(),
                                                    enabled = shelfSelection.phase !=
                                                        com.example.ui.shelf.ShelfPhase.DRAGGING,
                                                )
                                                .padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            com.example.ui.shelf.CategoryPill(
                                                name = com.example.ui.favorite.ALL_FAV_CATEGORY,
                                                selected = favoriteCategory == com.example.ui.favorite.ALL_FAV_CATEGORY,
                                                count = favoriteItems.size,
                                                onClick = { favoriteCategory = com.example.ui.favorite.ALL_FAV_CATEGORY },
                                            )
                                            favCustomCategories.forEach { name ->
                                                // 「我喜欢的」同理：拖到这一排收藏分类胶囊上就放进去。
                                                // 注意「全部」不是真分类，不能当落点，所以只给自建分类注册。
                                                val favDropId = com.example.ui.shelf.dropTargetOfFavoriteCategory(name)
                                                com.example.ui.shelf.CategoryPill(
                                                    name = name,
                                                    selected = favoriteCategory == name,
                                                    count = favoriteItems.count { it.favorite.categoryName == name },
                                                    onClick = { favoriteCategory = name },
                                                    onLongClick = { favCategorySheetFor = name },
                                                    modifier = shelfSelection.dropTargetModifier(favDropId),
                                                    dropHighlight = shelfSelection.hoverTarget == favDropId,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (favoritesLocked) {
                                // 锁定：只留一句引导，不泄露任何收藏标题/封面
                                item(span = { GridItemSpan(maxLineSpan) }, key = "fav_locked", contentType = "fullspan") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 8.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
                                            .border(
                                                0.5.dp,
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                                RoundedCornerShape(18.dp),
                                            )
                                            .clickableWithFeedback { favoritesPinFor = true }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.Lock,
                                                contentDescription = null,
                                                tint = MintPrimary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "「我喜欢的」已锁定",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = "点击验证密码后查看",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (favSorted.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "fav_empty_state", contentType = "fullspan") {
                                    com.example.ui.components.MascotEmptyState(
                                        mascotResId = com.example.ui.mascot.MascotSpriteSheet.idleDrawable,
                                        title = "「还没有喜欢的漫画」",
                                        description = "在漫画页点一下 ♡，喜欢的漫画都在这里 —— 不下载、不占空间，随时在线接着看。",
                                        actionLabel = "去书库逛逛",
                                        onActionClick = onNavigateToShelf,
                                        testTagPrefix = "favorites_empty_state",
                                    )
                                }
                            } else {
                                items(items = favSorted, key = { com.example.ui.shelf.favShelfKey(it.key) }, contentType = { "favcard" }) { item ->
                                    // ⚠️ 收藏卡片也要登记几何，否则宿主的手势命中测试找不到它 ——
                                    // 「我喜欢的」长按完全没反应，就是差这一步。
                                    val favKey = com.example.ui.shelf.favShelfKey(item.key)
                                    val favSelected = favKey in shelfSelection.selected
                                    com.example.ui.favorite.FavoriteCard(
                                        item = item,
                                        onClick = { onOpenFavorite(item) },
                                        modifier = Modifier.then(itemBounds(favKey)),
                                        coverModifier = shelfSelection.coverBoundsModifier(favKey),
                                        selected = favSelected,
                                        // 勾选框按**板块**判定（与书架卡片同一套规则）：
                                        // 收藏板块多选时，未选中的条目也要有白圆圈；
                                        // 书架板块多选时，收藏卡片**完全不参与**（不画圈、不压暗）。
                                        multiSelecting = shelfSelection.scope ==
                                            com.example.ui.shelf.ShelfSelectionScope.FAV,
                                        dimmed = shelfSelection.scope ==
                                            com.example.ui.shelf.ShelfSelectionScope.FAV && !favSelected,
                                        // 与书架书卡同一套规则：被拿起来 / 正在飞回原位时
                                        // 原位必须干净地空出来（只留宿主的虚线占位框），
                                        // 否则屏幕上同时有"原处的卡"和"手上的幽灵卡"。
                                        hidden = favKey in shelfSelection.dragging ||
                                            favKey in shelfSelection.flyingHome,
                                        hostOwnsGesture = true,
                                        pressed = shelfSelection.pressedKey == favKey,
                                    )
                                }
                            }

                                item(span = { GridItemSpan(maxLineSpan) }, key = "bottom_stats", contentType = "fullspan") {
                                    // 4. BOTTOM INFO CARD: READING STATISTICS PREVIEW
                                    // A2：原 24.dp 完全不足以躲开悬浮 Tab 栏 + 系统导航栏，
                                    // 改用全 App 统一的下发值
                                    Column(modifier = Modifier.padding(bottom = LocalAppBottomInset.current)) {
                                        Text(
                                            text = "阅读统计",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = adaptiveTitleColor(),
                                            fontFamily = AppFonts.Serif,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        GlassCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableWithFeedback { onNavigateToStats() },
                                            shape = RoundedCornerShape(16.dp),
                                            contentPadding = PaddingValues(16.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    TodayReadTimeText(todayReadSecondsFlow)
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "保持阅读，遇见更好的自己。",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .background(MintGold.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                                        .border(1.dp, MintGold.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            Icons.Filled.Star,
                                                            contentDescription = null,
                                                            tint = MintGold,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "连续 ${streakDays} 天",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MintGold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                            }

                            // 多选态底部悬浮操作栏（我的书架：移动 / 喜欢 / 分享 / 删除下载 / 更多）
                            if (shelfSelection.phase != com.example.ui.shelf.ShelfPhase.BROWSE) {
                                // 名单现在只可能属于一个板块（ShelfSelectionScope 在进入多选时定死），
                                // 所以这两个列表必然一个为空、一个可能非空 —— 不再有"混选"。
                                val selectedBooks = sortedBooks.filter { it.id.toString() in shelfSelection.selected }
                                // 选中的「我喜欢的」条目（key 带 fav:: 前缀）——
                                // 它们不是本地书籍，"移动/删除下载"这些操作对它们没有意义。
                                val selectedFavs = favSorted.filter {
                                    com.example.ui.shelf.favShelfKey(it.key) in shelfSelection.selected
                                }
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                // 收藏板块的 4 项**恒定存在**，不再用「selectedFavs 是否为空」决定整条栏。
                                // 原写法 `if (selectedFavs.isEmpty()) null else listOf(...)` + 后面的 `?:`
                                // 会在匹配为空时**静默回退成书架动作** ⇒ 用户看到「我喜欢的」里冒出
                                // 「删除下载」，且选项数 5↔4 跳变导致整条栏位置大变样。
                                val favActions = listOf(
                                    com.example.ui.shelf.ShelfAction(
                                        label = "打开",
                                        icon = androidx.compose.material.icons.Icons.Filled.MenuBook,
                                        enabled = selectedFavs.size == 1,
                                        onClick = {
                                            selectedFavs.firstOrNull()?.let { onOpenFavorite(it) }
                                            shelfSelection.exitSelection()
                                        },
                                    ),
                                    // 「我喜欢的」以前只有 打开/取消喜欢 两个，比书架少一大截
                                    // （用户：没有分享移动等等）。移动 = 归到收藏分类，
                                    // 分享 = 把选中的标题拼成文本发出去。
                                    com.example.ui.shelf.ShelfAction(
                                        label = "移动",
                                        icon = androidx.compose.material.icons.Icons.Filled.Folder,
                                        enabled = true,
                                        onClick = { bulkCategoryTarget = 1 },
                                    ),
                                    com.example.ui.shelf.ShelfAction(
                                        label = "分享",
                                        icon = androidx.compose.material.icons.Icons.Filled.Share,
                                        enabled = true,
                                        onClick = {
                                            shareTitles(ctx, selectedFavs.map { it.favorite.title })
                                        },
                                    ),
                                    com.example.ui.shelf.ShelfAction(
                                        label = "取消喜欢",
                                        icon = androidx.compose.material.icons.Icons.Filled.Favorite,
                                        enabled = true,
                                        destructive = true,
                                        onClick = {
                                            val keys = selectedFavs.map { it.key }
                                            onRemoveFavorites(keys)
                                            onShowUndo?.invoke("已取消喜欢 ${keys.size} 部") { }
                                            shelfSelection.exitSelection()
                                        },
                                    ),
                                )
                                com.example.ui.shelf.MultiSelectActionBar(
                                    visible = shelfSelection.phase == com.example.ui.shelf.ShelfPhase.SELECTING,
                                    // 操作栏上方那行提示小字已按用户要求整段删除（不传 hint）。
                                    // 严格按板块给动作，**不允许跨板块回退**：
                                    //   收藏 → 打开 / 移动 / 分享 / 取消喜欢（永远没有「删除下载」）
                                    //   书架 → 移动 / 分享 / 删除下载 / 更多（永远没有「喜欢 / 取消喜欢」）
                                    // 两边都是 4 项 ⇒ 数量恒定，切换时不会因数量变化而整体偏移。
                                    actions = if (favScope) favActions else listOf(
                                        com.example.ui.shelf.ShelfAction(
                                            label = "移动",
                                            icon = androidx.compose.material.icons.Icons.Filled.Folder,
                                            enabled = selectedBooks.isNotEmpty(),
                                            onClick = { bulkCategoryTarget = 0 },
                                        ),
                                        // 「喜欢」项已按用户要求移除：
                                        // 书架多选栏不再提供加入/取消「我喜欢的」（用户判定为无用）。
                                        // 需要收藏请走单本书内的 ♡。
                                        com.example.ui.shelf.ShelfAction(
                                            label = "分享",
                                            icon = androidx.compose.material.icons.Icons.Filled.Share,
                                            enabled = selectedBooks.isNotEmpty(),
                                            onClick = { shareTitles(ctx, selectedBooks.map { it.title }) },
                                        ),
                                        com.example.ui.shelf.ShelfAction(
                                            label = "删除下载",
                                            icon = androidx.compose.material.icons.Icons.Filled.Delete,
                                            enabled = selectedBooks.isNotEmpty(),
                                            destructive = true,
                                            onClick = {
                                                onDeleteDownloads?.invoke(selectedBooks)
                                                shelfSelection.exitSelection()
                                            },
                                        ),
                                        com.example.ui.shelf.ShelfAction(
                                            label = "更多",
                                            icon = androidx.compose.material.icons.Icons.Filled.MoreVert,
                                            enabled = selectedBooks.isNotEmpty(),
                                            onClick = { showMoreSheet = true },
                                        ),
                                    ),
                                )
                            }

                                // 这个 24dp 是浏览态下给内容留的收尾呼吸位。
                                // 多选态必须去掉：它排在悬浮操作栏**之后**，会把操作栏整体顶高
                                // 24dp（实测栏底离导航栏 40dp，像悬在半空）。
                                if (shelfSelection.phase == com.example.ui.shelf.ShelfPhase.BROWSE) {
                                    Spacer(modifier = Modifier.height(24.dp))
                                }
                            }
                        }

                        /* ── 多选态顶部栏（浮层，不占布局高度） ──
                           盖在页头上方从顶部滑入/淡出。因为它只是 Box 的 overlay，
                           下方网格一像素都不会动 —— 长按进入多选时不会再"跳一下"，
                           书卡的 itemRect 也不会瞬间失效导致误命中。 */
                        val reduceMotion = com.example.ui.feedback.LocalReduceMotion.current
                        androidx.compose.animation.AnimatedVisibility(
                            visible = shelfSelection.phase != com.example.ui.shelf.ShelfPhase.BROWSE,
                            enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None
                            else androidx.compose.animation.fadeIn(animationSpec = tween(150)) +
                                androidx.compose.animation.slideInVertically(
                                    initialOffsetY = { -it },
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                ),
                            exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None
                            else androidx.compose.animation.fadeOut(animationSpec = tween(110)) +
                                androidx.compose.animation.slideOutVertically(
                                    targetOffsetY = { -it },
                                    animationSpec = tween(170, easing = FastOutSlowInEasing),
                                ),
                            modifier = Modifier.align(Alignment.TopCenter),
                            label = "multiselect_topbar",
                        ) {
                            com.example.ui.shelf.MultiSelectTopBar(
                                // 计数 = 名单大小。名单已按板块锁死，所以它天然就是
                                // "单一板块的数量"，不需要（也不允许）把两边相加。
                                count = shelfSelection.selected.size,
                                // 「全选/取消全选」按 scope 分流：在「我喜欢的」里多选时，
                                // 全选选的是收藏条目，不会把书架的书都选上。
                                allSelected = allSelected,
                                onClose = { shelfSelection.exitSelection() },
                                onToggleAll = {
                                    if (allSelected) {
                                        shelfSelection.exitSelection()
                                    } else {
                                        shelfSelection.selectAll(favKeys)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 已移除原 `.background(colorScheme.background)` 实底矩形：
                                    // 顶栏现在是悬浮玻璃胶囊，那层满宽实底会在胶囊外围形成
                                    // 一个"长方形纯色矩形"，读起来像"矩形里套一块毛玻璃"（用户点名不要）。
                                    .statusBarsPadding(),
                            )
                        }
                    }
        }
        }

        longPressBook?.let { book ->
            BookActionSheet(
                book = book,
                onDismiss = { longPressBook = null },
                onOpenDetail = {
                    longPressBook = null
                    onBookClick(book)
                },
                onMove = {
                    longPressBook = null
                    bookToMove = book
                },
                onDelete = {
                    longPressBook = null
                    bookToDelete = book
                }
            )
        }

        /* ── 分类操作面板（长按分类；第七轮第 6.3 条） ── */
        categorySheetFor?.let { sheetCat ->
            // 第九轮修复②：面板展示/开关初值必须取实时分类（categories 由 Room Flow
            // 驱动）——此前直接用长按瞬间的 cat 快照，开关拨动后 DB 已更新而面板仍
            // 显示旧值（"开关不动、书架却已锁"的根因）。
            val latestSheetCat = categories.find { it.id == sheetCat.id } ?: sheetCat
            androidx.activity.compose.BackHandler { categorySheetFor = null }
            CategoryActionSheet(
                category = latestSheetCat,
                privacyModeEnabled = privacyModeEnabled,
                onToggleProtected = { protected ->
                    onToggleCategoryProtected?.invoke(latestSheetCat, protected)
                },
                onDeleteRequest = {
                    categorySheetFor = null
                    categoryToDelete = latestSheetCat
                },
                onGoToSettings = onSettingsClick,
                onDismiss = { categorySheetFor = null },
            )
        }

        /* ── 受保护分类的 PIN 验证（进入前；第七轮第 6.3/6.4 条） ── */
        pinVerifyFor?.let { cat ->
            val latestVerifyCat by rememberUpdatedState(cat)
            com.example.ui.privacy.PrivacyPinOverlay(
                mode = com.example.ui.privacy.PinEntryMode.VERIFY,
                onPinSet = { },
                onPinVerified = { pin ->
                    // 解锁成功即刻选中该分类（StateFlow → Compose 的传播晚于本同步回调）
                    val ok = onUnlockCategory?.invoke(latestVerifyCat, pin) ?: false
                    if (ok) onShelfCategoryChange(latestVerifyCat.name)
                    ok
                },
                onDismiss = { pinVerifyFor = null },
            )
        }
    }

/**
 * 拖拽时跟随手指的封面缩略图：宿主按条目 key（书籍 id 字符串）索取。
 *
 * 只画一张封面，不做任何状态订阅——拖拽期间每帧都要重组，越轻越好。
 */
@Composable
private fun ShelfDragCover(
    books: List<Book>,
    key: String,
    modifier: Modifier = Modifier,
    favorites: List<com.example.data.favorite.FavoriteItem> = emptyList(),
) {
    // 「我喜欢的」条目：key 带 fav:: 前缀，封面是网络图 / 本地缩略图
    if (key.startsWith(com.example.ui.shelf.SHELF_KEY_FAV_PREFIX)) {
        val fav = favorites.find { com.example.ui.shelf.favShelfKey(it.key) == key }
            ?.favorite
        val cover = fav?.localThumbPath ?: fav?.coverUrl
        if (fav != null && !cover.isNullOrBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MintPrimary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MintPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        return
    }
    val book = books.find { it.id.toString() == key }
    val cover = book?.coverUri
    if (book != null && !cover.isNullOrBlank()) {
        val data: Any = when {
            cover.startsWith("content://") -> android.net.Uri.parse(cover)
            cover.startsWith("file://") -> java.io.File(cover.substring(7))
            else -> java.io.File(cover)
        }
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MintPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MenuBook,
                contentDescription = null,
                tint = MintPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** 书架里的书 → 「我喜欢的」关联键；无来源（手动导入）的书返回 null。 */
private fun favKeyOf(book: Book): String? =
    if (!book.sourceId.isNullOrBlank() && !book.comicId.isNullOrBlank()) {
        "${book.sourceId}::${book.comicId}"
    } else null

/** 分享：多本合并成一段文本（书名 + 来源链接）。 */
private fun shareTitles(context: android.content.Context, titles: List<String>) {
    if (titles.isEmpty()) return
    val text = buildString {
        append("我在 Ciallo 阅读里看：\n")
        titles.forEach { append("《$it》\n") }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, "分享")) }
}

@Composable
private fun TodayReadTimeText(todaySecondsFlow: kotlinx.coroutines.flow.StateFlow<Long>) {
    // 第七轮第 4 条修复：数据源改为"今日"阅读秒数（daily_read_time_<今天>），
    // 旧版误用全生命周期累计值标"今日"；0 分钟如实显示（不再 coerceAtLeast(1)）
    val todaySeconds by todaySecondsFlow.collectAsStateWithLifecycle()
    val minutes = todayReadMinutes(todaySeconds)

    Text(
        text = "今日已阅读 $minutes 分钟",
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun NewCategoryDialog(
    title: String,
    description: String,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    AcrylicDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("名称", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedLabelColor = MintPrimary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedIndicatorColor = MintPrimary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MintPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text("新建", color = MintPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 今日阅读秒数 → 显示分钟（四舍五入到最近分钟；纯函数可单测）。
 *  验收口径：精确阅读 5 分钟（≈300s±2s）→ 显示 5，误差远小于 ±10 秒容差。 */
internal fun todayReadMinutes(todaySeconds: Long): Int =
    kotlin.math.round(todaySeconds / 60.0).toInt()

/* ══════════════ 第七轮第 5 条：书架操作胶囊 / 分类 Chip ══════════════ */

/**
 * 书架顶部的次级操作胶囊（排列方式 / 导入新书 / 新建分类）：
 * 图标 + 文字 + 轻背景描边（与设置面板的毛玻璃语言同源），
 * 克制的尺寸——次级操作不抢"我的书架"标题和书籍内容的视觉主次。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(120), label = "homeChipPress"
    )
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (emphasized) 0.14f else 0.08f))
            .border(
                0.5.dp,
                if (emphasized) MintPrimary.copy(alpha = 0.40f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                RoundedCornerShape(18.dp)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (emphasized) MintPrimary else adaptiveTitleColor().copy(alpha = 0.60f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MintPrimary else adaptiveTitleColor().copy(alpha = 0.72f),
            maxLines = 1
        )
    }
}

/**
 * 分类 Chip（第七轮第 5.2/6.3 条）：可横向滚动的书架导航。
 * 选中 = 品牌薄荷半透明填充 + 细边框 + 加粗；未选中弱化。
 * 受保护且未解锁的分类带锁图标；长按弹出分类操作面板（设密码 / 删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCategoryChip(
    name: String,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val baseColor = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 0.16f else 0.06f,
        animationSpec = tween(160), label = "catChipBg"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 0.50f else 0.13f,
        animationSpec = tween(160), label = "catChipBorder"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(baseColor.copy(alpha = bgAlpha))
            .border(0.5.dp, baseColor.copy(alpha = borderAlpha), RoundedCornerShape(17.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        if (locked) {
            Spacer(modifier = Modifier.width(5.dp))
            Icon(
                Icons.Filled.Lock,
                contentDescription = "已加密",
                tint = if (selected) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * 分类操作面板（长按分类；第七轮第 6.3 条）：
 * - 密码保护开关（受 6.4 隐私模式总开关约束——未开启时引导去设置开启）；
 * - 删除分类（"默认"分类给出"不可删除"提示）。
 */
@Composable
private fun CategoryActionSheet(
    category: com.example.data.CategoryEntity,
    privacyModeEnabled: Boolean,
    onToggleProtected: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDefault = category.name == com.example.data.DEFAULT_CATEGORY
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 第十一轮第 4 条：长按菜单出现动画——遮罩淡入 + 面板自底部上滑，
        // 与下载中心底部面板（AcrylicBottomOverlay）同款节奏
        com.example.ui.components.ScrimEntrance {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }
        com.example.ui.components.BottomSheetEntrance {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                // 任务四修复：悬浮 Tab 栏渲染在 MainActivity 层级（本面板之上），
                // 原 28dp 底边距使「删除分类」行落在 Tab 栏后面被挡——抬高面板至 Tab 栏上方。
                // A2：原 104.dp 是拍脑袋的 magic number，手势导航下多余、
                // 三键导航下不足；改用 LocalAppBottomInset（导航栏 + Tab 栏实测高度）。
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp, bottom = LocalAppBottomInset.current)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (category.isProtected) Icons.Filled.Lock else Icons.Filled.Folder,
                        contentDescription = null,
                        tint = if (category.isProtected) MintPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        category.name,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (privacyModeEnabled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "密码保护",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "进入此分类需要验证密码 · 无痕阅读",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                        com.example.ui.components.AppSwitch(
                            checked = category.isProtected,
                            onCheckedChange = onToggleProtected
                        )
                    }
                } else {
                    // 6.4 总开关约束：隐私模式未开启时不能对分类设密码——引导去开启
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .clickable {
                                onDismiss()
                                onGoToSettings()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "设置密码保护",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "需先开启隐私模式 ›",
                            fontSize = 11.sp,
                            color = MintPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isDefault) MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                        )
                        .clickable(enabled = !isDefault) { onDeleteRequest() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = if (isDefault) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (isDefault) "删除分类（默认分类不可删除）" else "删除分类",
                        fontSize = 13.sp,
                        color = if (isDefault) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        } // BottomSheetEntrance 结束
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionSheet(
    book: Book,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // 分享用 Activity 级作用域：弹窗关闭也不中断正在准备的分享
    val hostActivity = LocalContext.current as? androidx.activity.ComponentActivity
    val shareScope = hostActivity?.lifecycleScope ?: rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }

    val coverData = remember(book.coverUri, book.isCoverValid) {
        if (book.coverUri.isNullOrEmpty()) {
            null
        } else if (book.coverUri!!.startsWith("content://")) {
            android.net.Uri.parse(book.coverUri!!)
        } else if (book.isCoverValid) {
            val path = if (book.coverUri!!.startsWith("file://")) {
                book.coverUri!!.substring(7)
            } else {
                book.coverUri!!
            }
            java.io.File(path)
        } else {
            null
        }
    }
    val imageRequest = remember(book.coverUri, coverData) {
        if (coverData == null) null else coil.request.ImageRequest.Builder(context)
            .data(coverData)
            .memoryCacheKey(book.coverUri)
            .diskCacheKey(book.coverUri)
            .crossfade(true)
            .build()
    }

    /* 下滑关闭手势状态 */
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dismissThreshold = with(density) { 120.dp.toPx() }

    /* 面板高度硬约束：50% 屏高 —— 菜单列内部滚动，
       "删除图书"在任何设备上都物理不可能出界 */
    val maxPanelH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.5f

    /* 下载管理中心同款容器：亚克力底部悬浮面板 */
    AcrylicBottomOverlay(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxPanelH)
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
        ) {
            Column {
                // 拖拽把手
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffsetY > dismissThreshold) onDismiss()
                                    else dragOffsetY = 0f
                                },
                                onDragCancel = { dragOffsetY = 0f },
                                onVerticalDrag = { _, dragAmount ->
                                    dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFB9B9BE))
                    )
                }

                // 封面 + 书名/作者
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(78.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Gray.copy(alpha = 0.2f))
                    ) {
                        if (imageRequest != null) {
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = book.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = book.author,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                // 操作菜单（内部排版与原版一致；weight 弹性填充剩余空间）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    val primary = MaterialTheme.colorScheme.primary
                    val error = MaterialTheme.colorScheme.error
                    val onSurface = MaterialTheme.colorScheme.onSurface
                    listOf(
                        Triple("打开详情", Icons.Default.MenuBook, primary),
                        Triple("移动到其他书架", Icons.Default.Folder, primary),
                        Triple("分享图书", Icons.Default.Share, primary),
                        Triple("删除图书", Icons.Default.Delete, error)
                    ).forEach { (label, icon, tint) ->
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val pressScale by animateFloatAsState(
                            targetValue = if (pressed) 0.97f else 1f,
                            label = "press"
                        )
                        if (label == "分享图书" && sharing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("正在准备分享…", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = onSurface)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        enabled = !(label == "分享图书" && sharing)
                                    ) {
                                        when (label) {
                                            "打开详情" -> onOpenDetail()
                                            "移动到其他书架" -> onMove()
                                            "分享图书" -> {
                                                if (sharing) return@clickable
                                                sharing = true
                                                shareScope.launch {
                                                    val err = com.example.library.BookShareHelper.shareBook(context, book)
                                                    sharing = false
                                                    if (err != null) AppToast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            else -> onDelete()
                                        }
                                    }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = label,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (label == "删除图书") tint else onSurface
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }


    }
}

