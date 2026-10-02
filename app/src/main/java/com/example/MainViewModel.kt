package com.example

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.source.isComicSource
import com.example.source.withComicDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val prefs = PreferencesManager(application)
    // 懒初始化：等下面的 godRepository 就绪后再建（备份导出要带上神回元数据）
    val backupManager by lazy { BackupManager(application, prefs, godRepository) }
    val ttsManager = TtsManager(application)
    private val readingTimeMutex = kotlinx.coroutines.sync.Mutex()
    val downloadManager = com.example.download.DownloadManager(application)
    val repository = BookRepository(application, database.bookDao())

    /* ══════════════ 神回（GodMoment） ══════════════
     * 与 GodMomentViewModel 共享同一个 Room 库：统计页 / 全屏排行榜用这里的
     * StateFlow，阅读器内的窗口用 GodMomentViewModel —— 两处读写同一张表，
     * 增删改后自动同步。 */
    val godRepository = com.example.god.GodMomentRepository(
        application,
        database.godMomentDao(),
    )
    val godSettings = com.example.god.GodMomentSettingsStore(application)
    /**
     * 神回流：用 WhileSubscribed 而不是 Eagerly —— Eagerly 会在 ViewModel
     * 构造的瞬间就去查库，一旦数据库侧有任何异常，未捕获异常会直接把进程带崩
     * （表现为"一打开就闪退"）。改为有人订阅才查，且上游已带 catch 兜底。
     */
    val godMoments: StateFlow<List<com.example.god.GodMomentEntity>> =
        godRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 删除单个神回（含封面缓存文件清理） */
    fun deleteGodMoment(moment: com.example.god.GodMomentEntity) {
        viewModelScope.launch { godRepository.delete(moment.id) }
    }

    /* ══════════════ 「我喜欢的」在线收藏（三态解耦） ══════════════
     * 收藏 / 阅读进度 / 下载 三张数据互不耦合：
     * - 取消喜欢不影响下载与阅读进度；
     * - 删除下载不影响喜欢与阅读进度；
     * - 没有来源信息的本地书不能被喜欢（入口置灰并说明原因）。
     * 书源实例由 MainActivity 注入（SourceManager 归 LibraryViewModel 持有）。
     */
    var comicSourceProvider: (suspend (String) -> com.example.source.ComicSource?)? = null

    val favoriteRepository = com.example.data.favorite.FavoriteRepository(
        dao = database.favoriteDao(),
        comicSourceOf = { sourceId -> comicSourceProvider?.invoke(sourceId) },
        scope = viewModelScope,
    ).also { it.catalogContext = application.applicationContext }

    /** 收藏列表（Room Flow） */
    val favorites: StateFlow<List<com.example.data.favorite.FavoriteEntity>> = favoriteRepository.favorites

    /** 收藏主键集合："sourceId::comicId" —— 书架卡片右下角小心形用 */
    val favoriteKeys: StateFlow<Set<String>> = favoriteRepository.favoriteKeys

    /**
     * 收藏 + 进度 + 已下载章节数的聚合流：书架「我喜欢的」栏直接消费。
     * 已下载话数用 sourceId/comicId 反查本地 books（一个已下载章节 = 一本本地漫画）。
     */
    val favoriteItems: StateFlow<List<com.example.data.favorite.FavoriteItem>> =
        combine(favorites, favoriteRepository.progressByKey, repository.allBooks) { favs, progressMap, books ->
            val key = { s: String, c: String -> com.example.data.favorite.favoriteKey(s, c) }
            val downloadedByKey = books
                .filter { it.isComic && !it.sourceId.isNullOrBlank() && !it.comicId.isNullOrBlank() }
                .groupBy { key(it.sourceId!!, it.comicId!!) }
            favs.map { fav ->
                com.example.data.favorite.FavoriteItem(
                    favorite = fav,
                    progress = progressMap[key(fav.sourceId, fav.comicId)],
                    downloadedChapters = downloadedByKey[key(fav.sourceId, fav.comicId)]?.size ?: 0,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleFavorite(
        book: com.example.source.SearchBook,
        next: Boolean,
        category: String = com.example.data.favorite.FAV_DEFAULT_CATEGORY,
        chapters: List<com.example.source.ComicChapter> = emptyList(),
    ) {
        if (!next) {
            viewModelScope.launch(Dispatchers.IO) { favoriteRepository.remove(book.sourceId, book.id) }
            return
        }
        if (favoriteAddJob?.isActive == true || _favoriteAddRequest.value != null) {
            val key = "${book.sourceId}::${book.id}"
            if (_favoriteAddRequest.value?.book?.let { "${it.sourceId}::${it.id}" } != key &&
                favoriteAddQueue.none { "${it.book.sourceId}::${it.book.id}" == key }) {
                favoriteAddQueue.add(QueuedFavorite(book, category, chapters))
            }
            return
        }
        favoriteAddJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val source = comicSourceProvider?.invoke(book.sourceId)
                if (source?.isComicSource != true || favoriteRepository.isFavorite(book.sourceId, book.id)) {
                    favoriteRepository.add(book, category, chapters)
                    return@launch
                }
                val existing = favoriteRepository.favoritesSnapshot().filter { it.sourceId != book.sourceId }
                if (existing.isEmpty()) {
                    favoriteRepository.add(book, category, chapters)
                    return@launch
                }
                val titleDao = database.anilistDao()
                val titles = listOf(book.title) + book.comicInfo?.alternateTitles.orEmpty()
                val mediaIds = titles.flatMap { title -> titleDao.findMediaIds(
                    com.example.source.anilist.TitleNormalizer.normalize(title),
                    com.example.source.anilist.TitleNormalizer.compact(title),
                ) }.distinct()
                val aliases = if (mediaIds.size == 1) titleDao.getRawTitlesFor(mediaIds) else emptyList()
                val candidates = com.example.data.favorite.ComicFavoriteMatching.candidates(book, existing, aliases)
                if (candidates.isEmpty()) {
                    favoriteRepository.add(book, category, chapters)
                    return@launch
                }
                _favoriteAddRequest.value = com.example.data.favorite.FavoriteAddRequest(
                    book, category, chapters, existing, candidates,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _favoriteActionMessage.value = e.message ?: "收藏失败，请重试"
            }
        }.also { job -> job.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main) { processFavoriteAddQueue() } } }
    }

    private data class QueuedFavorite(val book: com.example.source.SearchBook, val category: String,
        val chapters: List<com.example.source.ComicChapter>)
    private val favoriteAddQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedFavorite>()
    private fun processFavoriteAddQueue() {
        if (_favoriteAddRequest.value != null || favoriteAddJob?.isActive == true) return
        favoriteAddQueue.poll()?.let { toggleFavorite(it.book, true, it.category, it.chapters) }
    }
    private var favoriteAddJob: Job? = null
    private val _favoriteAddRequest = MutableStateFlow<com.example.data.favorite.FavoriteAddRequest?>(null)
    val favoriteAddRequest = _favoriteAddRequest.asStateFlow()
    private val _favoriteActionMessage = MutableStateFlow<String?>(null)
    val favoriteActionMessage = _favoriteActionMessage.asStateFlow()
    fun clearFavoriteActionMessage() { _favoriteActionMessage.value = null }
    fun dismissFavoriteAdd() {
        if (_favoriteAddRequest.value?.busy == true) return
        favoriteAddJob?.cancel()
        _favoriteAddRequest.value = null
        processFavoriteAddQueue()
    }

    fun confirmFavoriteAdd(replace: com.example.data.favorite.FavoriteEntity?) {
        val request = _favoriteAddRequest.value?.takeUnless { it.busy } ?: return
        _favoriteAddRequest.value = request.copy(busy = true, error = null)
        favoriteAddJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (replace == null) {
                    favoriteRepository.add(request.book, request.category, request.chapters)
                    _favoriteActionMessage.value = "已收进「我喜欢的」♡"
                } else {
                    val source = comicSourceProvider?.invoke(request.book.sourceId)
                    var book = request.book
                    val chapters = request.chapters.ifEmpty {
                        when (val result = kotlinx.coroutines.withTimeout(30_000) { source?.getChapters(book.id) }) {
                            is com.example.source.SourceResult.Success -> result.data
                            is com.example.source.SourceResult.Error -> throw IllegalStateException(result.exception.message)
                            else -> throw IllegalStateException("新来源暂不可用，请稍后重试")
                        }
                    }
                    val detail = kotlinx.coroutines.withTimeoutOrNull(15_000) { source?.getDetail(book.id) }
                    if (detail is com.example.source.SourceResult.Success) book = book.withComicDetail(detail.data)
                    val report = favoriteRepository.replaceFavorite(
                        com.example.data.favorite.ComicKey(replace.sourceId, replace.comicId), book, chapters,
                    )
                    _favoriteActionMessage.value = buildString {
                        append("已替换收藏，迁移 ${report.migrated} 话阅读标记与书签")
                        if (report.unmatched > 0 || !report.resumeMatched) append("；未匹配记录仍保留在旧来源")
                        append("。新版本从对应话的第一页续读")
                    }
                }
                _favoriteAddRequest.value = null
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _favoriteAddRequest.value = request.copy(error = "来源响应超时，旧收藏已保留，可重试或选择并存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _favoriteAddRequest.value = request.copy(error = e.message ?: "操作失败，旧收藏已保留")
            }
        }.also { job -> job.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main) { processFavoriteAddQueue() } } }
    }

    /* ───────── 「我喜欢的」的分类（与书架分类完全独立） ───────── */

    val favoriteCategories: StateFlow<List<com.example.data.favorite.FavoriteCategoryEntity>> =
        favoriteRepository.favoriteCategories

    fun addFavoriteCategory(name: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.addFavoriteCategory(name)
        }
    }

    fun renameFavoriteCategory(oldName: String, newName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.renameFavoriteCategory(oldName, newName)
        }
    }

    fun deleteFavoriteCategory(name: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.deleteFavoriteCategory(name)
        }
    }

    fun moveFavoritesToCategory(keys: List<String>, category: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.moveToCategory(keys, category)
        }
    }

    fun removeFavorites(keys: List<String>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.removeByKeys(keys)
        }
    }

    /* ───────── 阅读进度：翻页防抖保存，退出/进后台强制保存 ───────── */

    private var progressSaveJob: kotlinx.coroutines.Job? = null

    /**
     * 保存漫画阅读进度。翻页时调用（自动防抖 400ms），退出阅读器 /
     * 进入后台时用 force = true 立刻落库。
     * 与是否收藏、是否下载完全无关 —— 没收藏的漫画同样记录已读状态。
     */
    fun saveComicProgress(
        sourceId: String,
        comicId: String,
        chapterId: String,
        chapterIndex: Int,
        pageIndex: Int,
        pageCount: Int,
        force: Boolean = false,
    ) {
        if (sourceId.isBlank() || comicId.isBlank() || chapterId.isBlank()) return
        progressSaveJob?.cancel()
        val write: suspend () -> Unit = {
            favoriteRepository.saveProgress(
                sourceId = sourceId,
                comicId = comicId,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                pageCount = pageCount,
            )
        }
        if (force) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { write() }
        } else {
            progressSaveJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(400)
                write()
            }
        }
    }

    /** 进入章节列表时记录"已见"快照（之后新增的章节显示「新」）。 */
    /** 详情页左滑/右滑该话卡片：切读书签（持久化到章节读状态表）。 */
    fun setChapterBookmark(sourceId: String, comicId: String, chapterId: String, chapterIndex: Int, bookmarked: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                favoriteRepository.setChapterBookmark(sourceId, comicId, chapterId, chapterIndex, bookmarked)
            }.onFailure {
                android.util.Log.e("MainViewModel", "章节书签写入失败", it)
            }
        }
    }

    fun markComicSeen(sourceId: String, comicId: String, chapters: List<com.example.source.ComicChapter>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.markSeen(sourceId, comicId, chapters)
        }
    }

    /** 手动标记某章已读/未读（长按章节）。 */
    fun markComicChapterRead(
        sourceId: String,
        comicId: String,
        chapterId: String,
        chapterIndex: Int,
        read: Boolean,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.markChapter(sourceId, comicId, chapterId, chapterIndex, read)
        }
    }

    /** 「将以上全部标记为已读」。 */
    fun markComicChaptersReadUpTo(sourceId: String, comicId: String, maxIndex: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.markChaptersReadUpTo(sourceId, comicId, maxIndex)
        }
    }

    /**
     * 换源迁移：把收藏 / 进度 / 已读状态按「阅读序号」搬到新源的这本上。
     * 只有用户显式确认了候选来源才调用（见 [com.example.ui.favorite.SourceMigrateSheet]）。
     */
    fun migrateComic(
        fromSourceId: String,
        fromComicId: String,
        toSourceId: String,
        toComicId: String,
        newChapters: List<com.example.source.ComicChapter> = emptyList(),
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.migrateByOrder(
                from = com.example.data.favorite.ComicKey(fromSourceId, fromComicId),
                to = com.example.data.favorite.ComicKey(toSourceId, toComicId),
                newChapters = newChapters,
            )
        }
    }

    fun checkFavoriteUpdates(force: Boolean = false) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            favoriteRepository.checkUpdates(force)
        }
    }

    /* ── 隐私模式（第七轮第 6.4/6.5 条） ── */
    val privacy = PrivacyManager(application)

    /** 隐私模式开关状态（重启后保持——受保护分类仍需 PIN 验证） */
    private val _privacyModeEnabled = MutableStateFlow(privacy.isEnabled())
    val privacyModeEnabled: StateFlow<Boolean> = _privacyModeEnabled.asStateFlow()

    /** 受保护分类名集合（数据源 = categories.isProtected，随 DB Flow 自动更新） */
    val protectedCategoryNames: StateFlow<Set<String>> = allCategoriesProtected()

    /** 本次进程内已通过 PIN 验证解锁的分类 id（重启 App 即失效，需重新验证） */
    private val _unlockedCategoryIds = MutableStateFlow<Set<Int>>(emptySet())
    val unlockedCategoryIds: StateFlow<Set<Int>> = _unlockedCategoryIds.asStateFlow()

    private fun allCategoriesProtected(): StateFlow<Set<String>> = repository.allCategories
        .map { cats -> cats.filter { it.isProtected }.map { it.name }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _autoNightMode = MutableStateFlow(prefs.autoNightMode)
    val autoNightMode: StateFlow<Boolean> = _autoNightMode.asStateFlow()

    private val _blueLightFilter = MutableStateFlow(prefs.blueLightFilter)
    val blueLightFilter: StateFlow<Boolean> = _blueLightFilter.asStateFlow()

    private val _blueLightAlpha = MutableStateFlow(prefs.blueLightAlpha)
    val blueLightAlpha: StateFlow<Float> = _blueLightAlpha.asStateFlow()

    private val _screenOrientationLock = MutableStateFlow(prefs.screenOrientationLock)
    val screenOrientationLock: StateFlow<Int> = _screenOrientationLock.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(prefs.hapticsEnabled)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _colorPrimaryIndex = MutableStateFlow(prefs.colorPrimaryIndex)
    val colorPrimaryIndex: StateFlow<Int> = _colorPrimaryIndex.asStateFlow()

    private val _colorSecondaryIndex = MutableStateFlow(prefs.colorSecondaryIndex)

    /** 封面有效性缓存：避免每次书架数据变更都重新 file.exists() 全部书籍。 */
    private val coverValidCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val colorSecondaryIndex: StateFlow<Int> = _colorSecondaryIndex.asStateFlow()

    private val _renderQuality = MutableStateFlow(prefs.renderQuality)
    val renderQuality: StateFlow<Int> = _renderQuality.asStateFlow()

    fun updateRenderQuality(quality: Int) {
        prefs.renderQuality = quality
        _renderQuality.value = quality
    }

    fun updateAutoNightMode(enabled: Boolean) {
        prefs.autoNightMode = enabled
        _autoNightMode.value = enabled
    }

    fun updateBlueLightFilter(enabled: Boolean) {
        prefs.blueLightFilter = enabled
        _blueLightFilter.value = enabled
    }

    fun updateBlueLightAlpha(alpha: Float) {
        prefs.blueLightAlpha = alpha
        _blueLightAlpha.value = alpha
    }

    fun updateScreenOrientationLock(mode: Int) {
        prefs.screenOrientationLock = mode
        _screenOrientationLock.value = mode
    }

    fun updateHapticsEnabled(enabled: Boolean) {
        prefs.hapticsEnabled = enabled
        _hapticsEnabled.value = enabled
    }

    fun updateColorTheme(primary: Int, secondary: Int) {
        prefs.colorPrimaryIndex = primary
        prefs.colorSecondaryIndex = secondary
        _colorPrimaryIndex.value = primary
        _colorSecondaryIndex.value = secondary
    }

    // 注意：init 块必须位于其访问的全部属性声明之后——Kotlin 按声明顺序执行
    // 初始化，launch(Dispatchers.IO) 的协程可能在构造函数完成前并发执行，
    // 若 _streakDays 等字段尚未初始化则在该协程里读到 null（高负载下实测
    // 触发 FATAL NPE，MainViewModel$3），故 init 移至文件后部声明。
    private val _totalReadTimeSeconds = MutableStateFlow(prefs.totalReadTimeSeconds)
    val totalReadTimeSeconds: StateFlow<Long> = _totalReadTimeSeconds.asStateFlow()

    /**
     * 今日已阅读秒数（第七轮第 4 条修复）：
     * 旧版书架统计卡片把全生命周期累计值 [totalReadTimeSeconds] 标成"今日已阅读"，
     * 数字只会单调增长、永不清零——分钟数"不对"的直接根因。
     * 此流读取 prefs 的 daily_read_time_<今天> 键，随每次 [recordTime] 实时累加。
     */
    private val _todayReadSeconds = MutableStateFlow(
        prefs.getDailyReadTime(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        )
    )
    val todayReadSeconds: StateFlow<Long> = _todayReadSeconds.asStateFlow()

    private val _streakDays = MutableStateFlow(0)
    val streakDays: StateFlow<Int> = _streakDays.asStateFlow()

    init {
        com.example.source.zlibrary.network.ZLibraryDns.INSTANCE.watchNetwork(application)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.checkAndSeedDefaultBooks()
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 第七轮第 6.1 条：默认分类幂等种子（迁移兜底；书架始终至少有一个分类）
            repository.ensureDefaultCategory()
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 存量超大章节自动拆分（与本地导入书一致，修复旧下载书的打开卡顿/闪退）
            repository.splitOversizedChaptersInLibrary()
            repository.cleanupOrphanFiles()
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val streak = prefs.calculateStreak()
            _streakDays.value = streak
        }
    }

    val allBooks: StateFlow<List<Book>> = repository.allBooks
        .map { books ->
            books.forEach { book ->
                if (!book.coverUri.isNullOrEmpty()) {
                    val key = book.coverUri
                    book.isCoverValid = coverValidCache.getOrPut(key) {
                        val path = if (key.startsWith("file://")) key.substring(7) else key
                        java.io.File(path).exists()
                    }
                } else {
                    book.isCoverValid = false
                }
            }
            books
        }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allCategories: StateFlow<List<CategoryEntity>> = repository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allReadingRecords: StateFlow<List<ReadingRecord>> = repository.allReadingRecords.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val allReadingSessions: StateFlow<List<ReadingSession>> = repository.allReadingSessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _selectedBook = MutableStateFlow<Book?>(null)
    val selectedBook: StateFlow<Book?> = _selectedBook

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters
    private val _readerLoading = MutableStateFlow(false)
    val readerLoading: StateFlow<Boolean> = _readerLoading
    private val _readerLoadError = MutableStateFlow<String?>(null)
    val readerLoadError: StateFlow<String?> = _readerLoadError

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _importStatusMessage = MutableStateFlow<String?>(null)
    val importStatusMessage: StateFlow<String?> = _importStatusMessage

    private var cachedMetadataList = emptyList<Chapter>()
    private var chapterMapping: LogicalChapterBook? = null
    private var lastLoadedBookId: Int? = null
    private var lastLoadedChapterIndex: Int? = null
    private val _loadedChapterIndices = MutableStateFlow<Set<Int>>(emptySet())
    val loadedChapterIndices: StateFlow<Set<Int>> = _loadedChapterIndices
    private var readerSelectionToken = 0
    private var readerSelectionJob: Job? = null
    private var readerContentJob: Job? = null
    private var bookmarkCollectJob: Job? = null
    private var highlightCollectJob: Job? = null
    private var selectedSourceBook: Book? = null

    private fun loadActiveChaptersContent(bookId: Int, currentLogicalIdx: Int) {
        readerContentJob?.cancel()
        val token = readerSelectionToken
        val mapping = chapterMapping
        val metadata = cachedMetadataList
        val needsCurrentChapter = currentLogicalIdx !in _loadedChapterIndices.value
        _readerLoading.value = needsCurrentChapter
        readerContentJob = viewModelScope.launch {
            try {
                check(metadata.isNotEmpty() && mapping != null) { "没有可读取的章节" }

                val targetLogical = listOf(currentLogicalIdx - 1, currentLogicalIdx, currentLogicalIdx + 1)
                    .filter { it >= 0 && it < metadata.size }

                val targetOrders = targetLogical
                    .flatMap { mapping!!.logicalToPhysicalOrders[it].asIterable() }
                    .distinct()

                val activeParts = repository.getChaptersByOrders(bookId, targetOrders).associateBy { it.chapterOrder }

                val merged = metadata.mapIndexed { logicalIdx, chapter ->
                    if (logicalIdx !in targetLogical) {
                        chapter
                    } else {
                        val parts = mapping!!.logicalToPhysicalOrders[logicalIdx]
                            .map { activeParts[it] }
                            .filterNotNull()
                        if (parts.isEmpty()) {
                            chapter
                        } else {
                            chapter.copy(content = parts.joinToString(separator = "") { it.content })
                        }
                    }
                }

                check(currentLogicalIdx in merged.indices &&
                    mapping.logicalToPhysicalOrders[currentLogicalIdx].all { it in activeParts }) {
                    "当前章节数据缺失，请重试加载"
                }
                if (token != readerSelectionToken || _selectedBook.value?.id != bookId ||
                    lastLoadedChapterIndex != currentLogicalIdx) return@launch
                _chapters.value = merged
                _loadedChapterIndices.value = targetLogical.filter { logicalIdx ->
                    mapping.logicalToPhysicalOrders[logicalIdx].all { it in activeParts }
                }.toSet()
                _readerLoadError.value = null
                _readerLoading.value = false
                android.util.Log.d("BookImport", "[MainViewModel] Lazy loaded content for logical chapters: $targetLogical, physical: $targetOrders")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if(t is CancellationException) throw t
                android.util.Log.e("BookImport", "[MainViewModel] Error lazy loading active chapters content", t)
                if (token == readerSelectionToken && _selectedBook.value?.id == bookId &&
                    lastLoadedChapterIndex == currentLogicalIdx && needsCurrentChapter) {
                    _readerLoadError.value = t.localizedMessage ?: "章节加载失败"
                    _readerLoading.value = false
                }
            }
        }
    }

    fun selectBook(book: Book) {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _isSearching.value = false
        readerSelectionToken++
        readerSelectionJob?.cancel()
        readerContentJob?.cancel()
        bookmarkCollectJob?.cancel()
        highlightCollectJob?.cancel()
        selectedSourceBook = book
        cachedMetadataList = emptyList()
        chapterMapping = null
        lastLoadedBookId = null
        lastLoadedChapterIndex = null
        _loadedChapterIndices.value = emptySet()
        _chapters.value = emptyList()
        _bookmarks.value = emptyList()
        _highlights.value = emptyList()
        _readerLoadError.value = null
        _readerLoading.value = true
        _selectedBook.value = book
        val token = readerSelectionToken
        readerSelectionJob = viewModelScope.launch {
            try {
                android.util.Log.d("BookImport", "[MainViewModel] Selecting book: ${book.title}, isComic: ${book.isComic}")
                if (book.isComic) {
                    // For comics, load all chapters directly since their content is just image file paths (very small)
                    if (token != readerSelectionToken) return@launch
                    chapterMapping = null
                    collectAnnotations(book.id)
                    repository.getChaptersForBook(book.id).collect {
                        if (token != readerSelectionToken) return@collect
                        _chapters.value = it
                        _readerLoading.value = false
                    }
                } else {
                    // For novels, use lazy loading
                    // 老书首次打开时懒迁移内嵌图片（EPUB/FB2/DOCX/MOBI）：
                    // 成功返回带图新章节的书，失败/无需迁移返回 null 继续用原书
                    val migratedBook = try {
                        repository.migrateInlineImagesIfNeeded(book)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                    val effectiveBook = migratedBook ?: book
                    val metadata = repository.getChaptersMetadataList(effectiveBook.id)
                    val logical = ChapterMerger.buildLogicalChapters(metadata)
                    check(logical.chapters.isNotEmpty()) { "这本书没有可读取的章节" }
                    if (token != readerSelectionToken || _selectedBook.value?.id != book.id) return@launch
                    cachedMetadataList = logical.chapters
                    chapterMapping = logical
                    lastLoadedBookId = effectiveBook.id

                    val physicalStart = effectiveBook.currentChapterIndex.coerceAtLeast(0)
                    val logicalStart = logical.logicalIndexOf(physicalStart)
                        .coerceIn(0, logical.chapters.lastIndex)
                    val logicalOffset = logical.logicalOffsetOf(physicalStart, effectiveBook.scrollOffset)
                    lastLoadedChapterIndex = logicalStart
                    _selectedBook.value = effectiveBook.copy(
                        currentChapterIndex = logicalStart,
                        scrollOffset = logicalOffset
                    )
                    collectAnnotations(effectiveBook.id)
                    loadActiveChaptersContent(effectiveBook.id, logicalStart)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("BookImport", "[MainViewModel] Error selecting book ${book.title}", t)
                if (token == readerSelectionToken) {
                    _readerLoadError.value = t.localizedMessage ?: "书籍加载失败"
                    _readerLoading.value = false
                }
            }
        }
    }

    fun retrySelectedBook() {
        val source = selectedSourceBook ?: return
        val token = readerSelectionToken
        viewModelScope.launch {
            val fresh = database.bookDao().getBookById(source.id) ?: source
            if (token == readerSelectionToken && _selectedBook.value?.id == source.id) {
                selectBook(fresh)
            }
        }
    }

    /** 切章立刻启动正文读取，不能等待进度写库完成。 */
    fun ensureActiveChapter(bookId: Int, logicalIndex: Int) {
        if (_selectedBook.value?.id != bookId || lastLoadedBookId != bookId ||
            logicalIndex !in cachedMetadataList.indices || lastLoadedChapterIndex == logicalIndex) return
        lastLoadedChapterIndex = logicalIndex
        _readerLoadError.value = null
        _selectedBook.value = _selectedBook.value?.copy(currentChapterIndex = logicalIndex, scrollOffset = 0)
        loadActiveChaptersContent(bookId, logicalIndex)
    }

    private fun collectAnnotations(bookId: Int) {
        bookmarkCollectJob = viewModelScope.launch {
            repository.getBookmarksForBook(bookId).collect { list ->
                val mapping = chapterMapping
                _bookmarks.value = if (mapping == null) {
                    list
                } else {
                    list.map { bm ->
                        val li = mapping.logicalIndexOf(bm.chapterIndex)
                        val off = mapping.logicalOffsetOf(bm.chapterIndex, bm.scrollOffset)
                        if (li == bm.chapterIndex && off == bm.scrollOffset) {
                            val cleanTitle = ChapterMerger.cleanSplitTitle(bm.title)
                            if (cleanTitle == bm.title) bm else bm.copy(title = cleanTitle)
                        } else {
                            bm.copy(chapterIndex = li, scrollOffset = off, title = ChapterMerger.cleanSplitTitle(bm.title))
                        }
                    }
                }
            }
        }
        highlightCollectJob = viewModelScope.launch {
            repository.getHighlightsForBook(bookId).collect { list ->
                val mapping = chapterMapping
                _highlights.value = if (mapping == null) {
                    list
                } else {
                    list.map { h ->
                        val li = mapping.logicalIndexOf(h.chapterIndex)
                        if (li == h.chapterIndex) h else h.copy(chapterIndex = li)
                    }
                }
            }
        }
    }

    fun moveBookToCategory(book: Book, newCategory: String) {
        viewModelScope.launch {
            val updated = book.copy(category = newCategory)
            database.bookDao().updateBook(updated)
        }
    }

    fun importBook(uri: Uri, fileName: String, category: String = DEFAULT_CATEGORY) {
        viewModelScope.launch {
            try {
                android.util.Log.d("BookImport", "[MainViewModel] Starting import: $fileName, category: $category")
                val result = repository.importBookFromUri(uri, fileName)
                result.onSuccess { book ->
                    // 第七轮第 6.1/6.2 条：书籍单一归属真实分类；旧聚合词归一为"默认"
                    val normalizedCategory = if (category == "全部" || category == "未分类") DEFAULT_CATEGORY else category
                    val finalBook = if (normalizedCategory != book.category) {
                        val updated = book.copy(category = normalizedCategory)
                        database.bookDao().updateBook(updated)
                        updated
                    } else {
                        book
                    }
                    android.util.Log.d("BookImport", "[MainViewModel] Import success: ${finalBook.title}")
                    _importStatusMessage.value = "《${finalBook.title}》 导入成功"
                }.onFailure {
                    android.util.Log.e("BookImport", "[MainViewModel] Import failure", it)
                    _importStatusMessage.value = "导入失败: ${it.localizedMessage ?: "未知错误"}"
                }
            } catch (t: Throwable) {
                android.util.Log.e("BookImport", "[MainViewModel] Uncaught exception in import coroutine", t)
                _importStatusMessage.value = "导入出错: ${t.localizedMessage ?: "发生未知异常"}"
            }
        }
    }

    fun clearImportMessage() {
        _importStatusMessage.value = null
    }

    private val progressSaveJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    fun updateProgress(bookId: Int, chapterIndex: Int, scrollOffset: Int, isFinished: Boolean) {
        if (_selectedBook.value?.id != bookId) return
        val physicalIndex=chapterMapping?.physicalIndexFor(chapterIndex) ?: chapterIndex
        val epoch = ContentMutationGate.epoch
        progressSaveJobs.remove(bookId)?.cancel()
        val job=viewModelScope.launch(Dispatchers.IO) {
            if(!isFinished) delay(400)
            repository.updateBookProgress(bookId,physicalIndex,scrollOffset,isFinished,epoch)
        }
        progressSaveJobs[bookId]=job
        job.invokeOnCompletion { progressSaveJobs.remove(bookId,job) }
    }

    /**
     * 删书级联神回：Room 侧漫画主键是源作用域字符串（无法建 Int 外键），
     * 所以在这里显式清理 —— 先删神回记录，再删书（含其封面缓存文件）。
     */
    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
        }
    }

    /* ── 多选删除的撤销窗口 ──
     * 提交必须挂在 Activity 级作用域上：挂在 rememberCoroutineScope 上时，
     * 8 秒内离开该界面协程就被取消，删除被静默丢弃（界面显示已删、实际什么都没删）。
     * 每批一个独立 token，连续删多批互不覆盖。 */
    private val deletionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingDeleteTokens: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 8 秒后仍未撤销则真正落盘删除；onCommitted 在主线程回调（用于把该批从"软删除"列表移除）。 */
    fun scheduleBooksDeletion(books: List<Book>, token: String, onCommitted: () -> Unit = {}) {
        pendingDeleteTokens.add(token)
        deletionScope.launch {
            delay(8_000)
            if (pendingDeleteTokens.remove(token)) {
                books.forEach {
                    runCatching { repository.deleteBook(it) }
                }
                withContext(Dispatchers.Main) { onCommitted() }
            }
        }
    }

    fun cancelBooksDeletion(token: String) {
        pendingDeleteTokens.remove(token)
    }

    /** 删除前预估这批书占用的磁盘字节（书体/封面/匹配的下载任务文件，含漫画目录递归）。 */
    suspend fun booksDiskBytes(books: List<Book>): Long = repository.booksDiskBytes(books)

    fun deleteReadingRecord(id: Int) {
        viewModelScope.launch {
            repository.deleteReadingRecord(id)
            getApplication<Application>()
                .getSharedPreferences("record_cover_cache", android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(id.toString())
                .apply()
        }
    }

    /** 记录一次完整阅读会话（阅读器可见且前台期间），供日历时段/高峰时段使用。
     *  第七轮第 6.5 条：无痕浏览（受保护分类内阅读）不写入任何统计。 */
    fun addReadingSession(session: ReadingSession) {
        if (session.durationSeconds <= 0) return
        if (isIncognitoReading()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                repository.addReadingSession(session)
            }.onFailure {
                android.util.Log.e("MainViewModel", "Error saving reading session", it)
            }
        }
    }

    fun addBookmark(bookId: Int, chapterIndex: Int, scrollOffset: Int, title: String, snippet: String) {
        viewModelScope.launch {
            val existing = _bookmarks.value.find { (it.bookId == bookId || it.bookId == 0) && it.chapterIndex == chapterIndex }
            if (existing == null) {
                val physicalIndex = chapterMapping?.physicalIndexFor(chapterIndex) ?: chapterIndex
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId,
                        chapterIndex = physicalIndex,
                        scrollOffset = scrollOffset,
                        title = title,
                        snippet = snippet
                    )
                )
            }
        }
    }

    fun toggleBookmark(bookId: Int, chapterIndex: Int, scrollOffset: Int, title: String, snippet: String) {
        viewModelScope.launch {
            val existing = _bookmarks.value.find { (it.bookId == bookId || it.bookId == 0) && it.chapterIndex == chapterIndex }
            if (existing != null) {
                repository.deleteBookmark(existing.id)
            } else {
                val physicalIndex = chapterMapping?.physicalIndexFor(chapterIndex) ?: chapterIndex
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId,
                        chapterIndex = physicalIndex,
                        scrollOffset = scrollOffset,
                        title = title,
                        snippet = snippet
                    )
                )
            }
        }
    }

    fun deleteBookmark(id: Int) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
        }
    }

    fun addHighlight(bookId: Int, chapterIndex: Int, selectedText: String, note: String, colorHex: String) {
        viewModelScope.launch {
            val physicalIndex = chapterMapping?.physicalIndexFor(chapterIndex) ?: chapterIndex
            repository.addHighlight(
                Highlight(
                    bookId = bookId,
                    chapterIndex = physicalIndex,
                    selectedText = selectedText,
                    note = note,
                    colorHex = colorHex
                )
            )
        }
    }

    fun deleteHighlight(id: Int) {
        viewModelScope.launch {
            repository.deleteHighlight(id)
        }
    }

    fun addCategory(name: String) {
        viewModelScope.launch {
            repository.addCategory(name)
        }
    }

    /**
     * 第七轮第 6.1 条：删除分类。"默认"分类不可删除。
     * @return true = 已删除；false = 被拒绝（默认分类不可删除）
     */
    fun deleteCategory(category: com.example.data.CategoryEntity, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.deleteCategory(category)
            onResult(ok)
        }
    }

    /* ── 隐私模式操作（第七轮第 6.3/6.4/6.5 条） ── */

    /** 首次开启：设置 6 位 PIN 并启用。返回 false = PIN 非法。 */
    suspend fun enablePrivacyMode(pin: String): Boolean {
        val ok = withContext(Dispatchers.Default) { privacy.enableWithPin(pin) }
        if (ok) _privacyModeEnabled.value = true
        return ok
    }

    /** 关闭隐私模式（先验证 PIN）。返回 false = PIN 错误。 */
    suspend fun disablePrivacyMode(pin: String): Boolean {
        val ok = withContext(Dispatchers.Default) { privacy.disable(pin) }
        if (ok) {
            _privacyModeEnabled.value = false
            _unlockedCategoryIds.value = emptySet()
        }
        return ok
    }

    suspend fun verifyPrivacyPin(pin: String): Boolean = withContext(Dispatchers.Default) { privacy.verifyPin(pin) }

    /** 修改 PIN（先验证旧 PIN） */
    suspend fun changePrivacyPin(oldPin: String, newPin: String): Boolean = withContext(Dispatchers.Default) { privacy.changePin(oldPin, newPin) }

    /** 切换某分类的密码保护标记（仅在隐私模式开启时允许——6.4 总开关约束） */
    fun setCategoryProtected(categoryId: Int, isProtected: Boolean) {
        if (!_privacyModeEnabled.value) return
        viewModelScope.launch {
            repository.setCategoryProtected(categoryId, isProtected)
            if (!isProtected) {
                // 取消保护时一并收起解锁态
                _unlockedCategoryIds.value = _unlockedCategoryIds.value - categoryId
            }
        }
    }

    /** 进入受保护分类：验证 PIN，成功则本次进程内解锁该分类 */
    suspend fun unlockCategory(categoryId: Int, pin: String): Boolean {
        if (!_privacyModeEnabled.value) return false
        if (!withContext(Dispatchers.Default) { privacy.verifyPin(pin) }) return false
        _unlockedCategoryIds.value = _unlockedCategoryIds.value + categoryId
        return true
    }

    /**
     * 无痕浏览判定（6.5 + 第九轮扩展）：
     * - 全局无痕开关开启 → 一切阅读不计统计；
     * - 否则：隐私模式开启 且 当前书所在分类受保护 → 不计统计。
     * 两种口径都只作用于统计/会话写入，阅读进度保存链路不受影响。
     */
    private fun isIncognitoReading(): Boolean {
        if (_incognitoBrowsingEnabled.value) return true
        if (!_privacyModeEnabled.value) return false
        val book = _selectedBook.value ?: return false
        return protectedCategoryNames.value.contains(book.category)
    }

    /* ── 第九轮：全局无痕浏览开关 ── */

    private val _incognitoBrowsingEnabled = MutableStateFlow(prefs.incognitoBrowsingEnabled)
    val incognitoBrowsingEnabled: StateFlow<Boolean> = _incognitoBrowsingEnabled.asStateFlow()

    fun setIncognitoBrowsing(enabled: Boolean) {
        _incognitoBrowsingEnabled.value = enabled
        prefs.incognitoBrowsingEnabled = enabled
    }

    /* ── 「我喜欢的」密码保护（与书架受保护分类同一套 PIN） ── */

    private val _favoritesProtected = MutableStateFlow(prefs.favoritesProtected)
    val favoritesProtected: StateFlow<Boolean> = _favoritesProtected.asStateFlow()

    fun setFavoritesProtected(enabled: Boolean) {
        _favoritesProtected.value = enabled
        prefs.favoritesProtected = enabled
    }

    fun recordTime(seconds: Long, title: String? = null, onlineBook: com.example.source.SearchBook? = null) {
        if (seconds <= 0 || isIncognitoReading()) return
        val epoch = ContentMutationGate.epoch
        val currentBook = if (onlineBook == null) _selectedBook.value else null
        val recordTitle = currentBook?.title ?: title ?: "在线阅读"
        val end = System.currentTimeMillis()
        val bounded = seconds.coerceAtMost(24L * 60 * 60)
        val slices = ReadingTimeSlices.split(end - bounded * 1000, end, bounded)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            readingTimeMutex.lock()
            try {
                if(!repository.addReadingTime(currentBook?.id, recordTitle, slices, epoch, prefs)) return@launch
                for (slice in slices) {
                    val record = if (currentBook != null) database.bookDao().getReadingRecordForBookAndDate(currentBook.id, slice.date)
                        else database.bookDao().getReadingRecordForTitleAndDate(recordTitle, slice.date)
                    if (currentBook != null) com.example.library.ReadingRecordMetadata.remember(getApplication(), currentBook, record?.id)
                    else onlineBook?.let { com.example.library.ReadingRecordMetadata.remember(getApplication(), it, recordId = record?.id) }
                }
                val total = database.bookDao().totalRecordedSeconds() + prefs.legacyUnattributedSeconds
                prefs.totalReadTimeSeconds = total
                for (slice in slices) prefs.setDailyReadTime(slice.date, database.bookDao().recordedSecondsForDate(slice.date))
                _totalReadTimeSeconds.value = total
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                _todayReadSeconds.value = database.bookDao().recordedSecondsForDate(today)
                _streakDays.value = prefs.calculateStreak()
            } finally { readingTimeMutex.unlock() }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchFullText(query: String) {
        // 输入框逐字符触发：取消上一轮搜索 + 300ms 防抖，否则"插"的慢结果
        // 会晚于"插图"返回并覆盖结果列表 —— 列表里混进不含完整关键词的条目，
        // 点进去自然没有关键词（用户实测的"检索结果没有关键词"即此因）
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        val bookId = _selectedBook.value?.id ?: return
        val token = readerSelectionToken
        val mapping = chapterMapping
        _isSearching.value = true
        searchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            try {
                val matches = ArrayList<Chapter>()
                var afterOrder = -1
                var resultCount = 0
                while (resultCount < com.example.data.SearchLocator.MAX_RESULTS) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val batch = database.bookDao().searchChaptersBatch(bookId, query, afterOrder)
                    if (batch.isEmpty()) break
                    // Keep only chapters needed for the result cap.
                    for (chapter in batch) {
                        matches.add(chapter)
                        resultCount += com.example.data.SearchLocator.countOccurrences(
                            chapter.content, query)
                        if (resultCount >= com.example.data.SearchLocator.MAX_RESULTS) break
                    }
                    afterOrder = batch.last().chapterOrder
                }
                val results = com.example.data.SearchLocator.buildResults(matches, query,
                    logicalIndexOf = { order -> mapping?.logicalIndexOf(order) ?: order },
                    logicalTitleOf = { idx -> mapping?.chapters?.getOrNull(idx)?.title })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (token != readerSelectionToken || _selectedBook.value?.id != bookId) return@launch
                _searchResults.value = results
                _isSearching.value = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == readerSelectionToken && _selectedBook.value?.id == bookId) {
                    android.util.Log.e("BookImport", "Error searching full text", e)
                    _isSearching.value = false
                }
            }
        }
    }

    override fun onCleared() {
        ttsManager.release()
        super.onCleared()
    }

}
