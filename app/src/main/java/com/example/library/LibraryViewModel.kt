package com.example.library

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BookRepository
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadState
import com.example.download.DownloadTaskEntity
import com.example.source.*
import com.example.source.impl.MangaDexSource
import com.example.source.js.JsSourceRepo
import com.example.source.zlibrary.ZLibrarySource
import com.example.source.zlibrary.guessFileFormatFromUrl
import com.example.source.storage.SharedPreferencesSourceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

import com.example.ui.components.AppToast

// 阅读器无法打开的格式（与 BookRepository.unsupportedBinaryExtensions 对齐）：
// PDF 不拦——下载后由 ComicParser 逐页位图渲染，以翻页模式打开；
// KFX/DJVU/DOC/RTF/CHM 无任何阅读管线，下载前拦截。
internal val READER_UNSUPPORTED_FORMATS = setOf("kfx", "djvu", "doc", "rtf", "chm")

/** 过滤掉阅读器不支持的下载格式（PDF / KFX / DJVU / DOC / RTF / CHM）。 */
internal fun filterReadableFormats(formats: List<BookFormat>): List<BookFormat> =
    formats.filter { it.format.trim().lowercase() !in READER_UNSUPPORTED_FORMATS }

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    val sourceManager = SourceManager(SharedPreferencesSourceStorage(application))
    val downloadManager = DownloadManager(application)
    val downloadedNovelKeys = AppDatabase.getDatabase(application).bookDao().getAllBooks().map { books ->
        books.filter { !it.isComic && it.sourceId != null && it.comicId != null }
            .map { DownloadManager.taskId(it.sourceId.orEmpty(), it.comicId.orEmpty()) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val database = AppDatabase.getDatabase(application)
    private val repository = BookRepository(application, database.bookDao())

    val currentSource: StateFlow<BookSource?> = sourceManager.activeSource
    val availableSources: StateFlow<List<BookSource>> = sourceManager.availableSources

    val prefs = com.example.data.PreferencesManager(application)
    val hasSeenWelcome = MutableStateFlow(prefs.hasSeenWelcome)
    val hasConfiguredSource = MutableStateFlow(prefs.hasConfiguredSource)
    val hasImportedLocalBook = MutableStateFlow(prefs.hasImportedLocalBook)

    // 阅读统计先复用原阅读入口/本地元数据，旧记录才限量并发查找。
    private val _recordCovers = MutableStateFlow<Map<Int, String>>(emptyMap())
    val recordCovers: StateFlow<Map<Int, String>> = _recordCovers.asStateFlow()
    private val _recordBooks = MutableStateFlow<Map<Int, SearchBook>>(emptyMap())
    val recordBooks: StateFlow<Map<Int, SearchBook>> = _recordBooks.asStateFlow()

    // 下载格式选择：多格式书源（Z-Library）下载前先弹选择
    private val _formatPickerBook = MutableStateFlow<SearchBook?>(null)
    val formatPickerBook: StateFlow<SearchBook?> = _formatPickerBook.asStateFlow()
    private val _pendingFormats = MutableStateFlow<List<BookFormat>>(emptyList())
    val pendingFormats: StateFlow<List<BookFormat>> = _pendingFormats.asStateFlow()
    private val _formatLoading = MutableStateFlow(false)
    val formatLoading: StateFlow<Boolean> = _formatLoading.asStateFlow()

    private val sourceSearches = SourceSearchCoordinator()
    private val recordResolver = ReadingRecordResolver(sourceSearches)
    private var recordResolveJob: Job? = null
    private val _recordCoverHeaders = MutableStateFlow<Map<Int, Map<String, String>>>(emptyMap())
    val recordCoverHeaders: StateFlow<Map<Int, Map<String, String>>> = _recordCoverHeaders.asStateFlow()

    fun resolveMissingRecordCovers(records: List<com.example.data.ReadingRecord>) {
        recordResolveJob?.cancel()
        recordResolveJob = viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val legacy = context.getSharedPreferences("record_cover_cache", android.content.Context.MODE_PRIVATE)
            val activeIds = records.map { it.id }.toSet()
            _recordBooks.update { it.filterKeys(activeIds::contains) }
            _recordCovers.update { it.filterKeys(activeIds::contains) }
            _recordCoverHeaders.update { it.filterKeys(activeIds::contains) }
            val missing = records.filter { it.id !in _recordBooks.value }
            if (missing.isEmpty()) return@launch
            val sources = sourceManager.availableSources.value
            recordResolver.resolve(missing, database.bookDao().getAllBooksSync(),
                database.favoriteDao().allFavoritesSync(), sources,
                cached = { record ->
                    ReadingRecordMetadata.find(context, record)
                        ?: ReadingRecordMetadata.decode(legacy.getString(record.id.toString(), null), record.bookTitle)
                },
                publish = { record, book ->
                    ReadingRecordMetadata.remember(context, book.copy(title = record.bookTitle), record.bookId, record.id)
                    _recordBooks.update { it + (record.id to book) }
                    book.cover?.takeIf { it.isNotBlank() }?.let { cover ->
                        _recordCovers.update { it + (record.id to cover) }
                    }
                })
        }
    }

    fun resolveRecordCoverHeaders(records: List<com.example.data.ReadingRecord>) {
        records.forEach { record ->
            val book = _recordBooks.value[record.id] ?: return@forEach
            val cover = book.cover ?: return@forEach
            if (!cover.startsWith("http") || record.id in _recordCoverHeaders.value) return@forEach
            // Mark pending first, so recomposition cannot enqueue the same JS call again.
            _recordCoverHeaders.update { it + (record.id to emptyMap()) }
            viewModelScope.launch(Dispatchers.IO) {
                val source = sourceManager.getSource(book.sourceId) as? ComicSource
                val headers = withTimeoutOrNull(3_000) { source?.getCoverHeaders(cover) }.orEmpty()
                if (_recordBooks.value[record.id] == book) {
                    _recordCoverHeaders.update { it + (record.id to headers) }
                }
            }
        }
    }

    private val _isCurrentSourceLoggedIn = MutableStateFlow(false)
    val isCurrentSourceLoggedIn: StateFlow<Boolean> = _isCurrentSourceLoggedIn

    private val _comicBook = MutableStateFlow<SearchBook?>(null)
    val comicBook: StateFlow<SearchBook?> = _comicBook.asStateFlow()
    private var chaptersRequestSeq = 0L
    private var imagesRequestSeq = 0L
    private var novelRequestSeq = 0L
    private var novelLoadJob: Job? = null
    private var novelPrefetchJob: Job? = null

    /** 章节页是否为文本小说模式（Legado 网文源：点击章节进入文字阅读而非图片阅读） */
    private val _comicIsTextMode = MutableStateFlow(false)
    val comicIsTextMode: StateFlow<Boolean> = _comicIsTextMode.asStateFlow()

    private val _novelChapterText = MutableStateFlow("")
    val novelChapterText: StateFlow<String> = _novelChapterText.asStateFlow()

    private val _novelChapterLoading = MutableStateFlow(false)
    val novelChapterLoading: StateFlow<Boolean> = _novelChapterLoading.asStateFlow()

    private val _novelChapterError = MutableStateFlow<String?>(null)
    val novelChapterError: StateFlow<String?> = _novelChapterError.asStateFlow()

    private val _activeNovelChapter = MutableStateFlow<ComicChapter?>(null)
    val activeNovelChapter: StateFlow<ComicChapter?> = _activeNovelChapter.asStateFlow()

    private val _comicChapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val comicChapters: StateFlow<List<ComicChapter>> = _comicChapters.asStateFlow()

    private val _comicChaptersLoading = MutableStateFlow(false)
    val comicChaptersLoading: StateFlow<Boolean> = _comicChaptersLoading.asStateFlow()

    private val _comicChaptersError = MutableStateFlow<String?>(null)
    val comicChaptersError: StateFlow<String?> = _comicChaptersError.asStateFlow()

    private val _comicChapterImages = MutableStateFlow<List<String>>(emptyList())
    val comicChapterImages: StateFlow<List<String>> = _comicChapterImages.asStateFlow()

    private val _comicChapterHeaders = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val comicChapterHeaders: StateFlow<Map<String, Map<String, String>>> = _comicChapterHeaders.asStateFlow()

    private val _comicChapterLoading = MutableStateFlow(false)
    val comicChapterLoading: StateFlow<Boolean> = _comicChapterLoading.asStateFlow()

    private val _comicChapterError = MutableStateFlow<String?>(null)
    val comicChapterError: StateFlow<String?> = _comicChapterError.asStateFlow()

    private val _activeComicChapter = MutableStateFlow<ComicChapter?>(null)
    val activeComicChapter: StateFlow<ComicChapter?> = _activeComicChapter.asStateFlow()

    /** 漫画下载统一由应用级 ComicDownloadManager 管理：切页面不中断、失败可重试。 */
    val comicDownloadTasks: StateFlow<Map<String, ComicDownloadTask>> = ComicDownloadManager.tasks

    val comicDownloading: StateFlow<Set<String>> = ComicDownloadManager.tasks
        .combine(comicBook) { tasks, book -> tasks.values.filter { it.book.sourceId == book?.sourceId && it.book.id == book?.id && it.status == ComicDownloadStatus.DOWNLOADING }.map { it.chapterId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val comicDownloadProgress: StateFlow<Map<String, Float>> = ComicDownloadManager.tasks
        .combine(comicBook) { tasks, book -> tasks.values.filter { it.book.sourceId == book?.sourceId && it.book.id == book?.id }.associate { it.chapterId to it.progress } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val comicPaused: StateFlow<Set<String>> = ComicDownloadManager.tasks
        .combine(comicBook) { tasks, book -> tasks.values.filter { it.book.sourceId == book?.sourceId && it.book.id == book?.id && it.status == ComicDownloadStatus.PAUSED }.map { it.chapterId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val comicMessage: StateFlow<String?> = ComicDownloadManager.message

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    init {
        ComicDownloadManager.initialize(application)
        _searchHistory.value = prefs.searchHistory
        // 漫画下载完成后刷新“已导入本地书”状态
        val notifiedSuccess = mutableSetOf<String>()
        viewModelScope.launch {
            ComicDownloadManager.tasks.collect { tasks ->
                tasks.forEach { (id, t) ->
                    if (t.status == ComicDownloadStatus.SUCCESS && notifiedSuccess.add(id)) {
                        markLocalBookImported()
                    }
                }
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 第十轮：内置 AniList 多语言标题库导入（幂等，首启一次性；
            // 数据随 APK 分发，用户零拉取、离线可用）
            com.example.data.AppDatabase.ensureBundledTitlesImported(application)
            sourceManager.initialize()
            sourceManager.registerSource(ZLibrarySource(application))
            sourceManager.registerSource(com.example.source.impl.AutoNovelSource(application))
            sourceManager.registerSource(com.example.source.impl.Wenku8LibrarySource(application))
            sourceManager.registerSource(com.example.source.impl.IxdzsSource(application))
            sourceManager.registerSource(MangaDexSource(context = application))

            // 一次性清理上一版内置的社区漫画源（comic_* 自定义书源）
            if (prefs.hasImportedCommunityComics) {
                sourceManager.availableSources.value
                    .filter { it.id.startsWith("comic_") }
                    .forEach { sourceManager.unregisterSource(it.id) }
                prefs.hasImportedCommunityComics = false
            }

            // Venera 兼容 JS 源：优先本地缓存，无缓存时从远程仓库安装
            val cachedJsSources = JsSourceRepo.loadCached(application, prefs.showAdultSources)
            val refreshedJsSources = if (cachedJsSources.isEmpty() ||
                JsSourceRepo.needsRepair(application, prefs.showAdultSources)
            ) {
                JsSourceRepo.install(application, prefs.jsSourceRepoUrl, prefs.showAdultSources)
            } else emptyList()
            val jsSources = (cachedJsSources + refreshedJsSources)
                .associateBy { it.sourceKey }.values.toList()
            jsSources.forEach { source ->
                sourceManager.registerSource(source, defaultEnabled = true)
            }
            Log.i(
                "JsRepo",
                "JS sources ready: ${jsSources.size} (${jsSources.joinToString { it.name }})"
            )

            sourceManager.activeSource.collect { source ->
                checkSourceLoginStatus()
            }
        }
    }

    fun openComic(book: SearchBook) {
        novelRequestSeq++
        novelLoadJob?.cancel()
        novelPrefetchJob?.cancel()
        _novelChapterLoading.value = false
        imagesRequestSeq++
        comicPrefetchJobs.values.forEach { it.cancel() }
        comicPrefetchJobs.clear()
        comicPrefetchCache.clear()
        _comicBook.value = book
        _comicIsTextMode.value = sourceManager.availableSources.value
            .firstOrNull { it.id == book.sourceId }?.capabilities?.supportOnlineText == true
        _comicChapters.value = emptyList()
        _comicChaptersError.value = null
        _comicChapterImages.value = emptyList()
        _comicChapterHeaders.value = emptyMap()
        _comicChapterError.value = null
        _novelChapterText.value = ""
        _novelChapterError.value = null
        _activeNovelChapter.value = null
        loadChapters(book)
    }

    /** 文本小说源：加载章节正文 */
    fun loadChapterText(chapter: ComicChapter) {
        val request = ++novelRequestSeq
        novelLoadJob?.cancel()
        novelPrefetchJob?.cancel()
        val book = _comicBook.value
        val source = sourceManager.availableSources.value
            .firstOrNull { it.id == book?.sourceId } as? ComicSource
        if (source == null) {
            _novelChapterError.value = "当前书源不可用"
            _novelChapterLoading.value = false
            return
        }
        _activeNovelChapter.value = chapter
        _novelChapterLoading.value = true
        _novelChapterError.value = null
        _novelChapterText.value = ""
        novelLoadJob = viewModelScope.launch {
            try {
                val result = source.getChapterText(chapter.id)
                if (request != novelRequestSeq || _comicBook.value != book) return@launch
                when (result) {
                    is SourceResult.Success -> {
                        _novelChapterText.value = result.data
                        // The source owns a bounded text cache. Cancel this one next-chapter
                        // request on navigation so prefetch never blocks the selected chapter.
                        if (source is com.example.source.impl.AutoNovelSource) {
                            val index = _comicChapters.value.indexOfFirst { it.id == chapter.id }
                            val next = if (index >= 0) _comicChapters.value.getOrNull(index + 1) else null
                            if (next != null) novelPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
                                source.getChapterText(next.id)
                            }
                        }
                    }
                    is SourceResult.Error -> _novelChapterError.value = result.exception.message ?: "章节加载失败"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == novelRequestSeq) _novelChapterError.value = e.message ?: "章节加载失败"
            } finally {
                if (request == novelRequestSeq) _novelChapterLoading.value = false
            }
        }
    }

    private val _aggregateMode = MutableStateFlow(true)
    val aggregateMode: StateFlow<Boolean> = _aggregateMode.asStateFlow()

    /** 聚合搜索类别："comic" 漫画源 / "novel" 小说源 */
    private val _aggregateKind = MutableStateFlow("comic")
    val aggregateKind: StateFlow<String> = _aggregateKind.asStateFlow()

    data class DetailSearchRequest(val keyword: String, val token: Long)
    private val _detailSearchRequest = MutableStateFlow<DetailSearchRequest?>(null)
    val detailSearchRequest = _detailSearchRequest.asStateFlow()
    private var detailSearchToken = 0L

    fun requestAggregateSearch(keyword: String, textMode: Boolean = false) {
        if (keyword.isBlank()) return
        setAggregateMode(true)
        setAggregateKind(if (textMode) "novel" else "comic")
        _detailSearchRequest.value = DetailSearchRequest(keyword.trim(), ++detailSearchToken)
    }

    fun consumeDetailSearch(token: Long) {
        if (_detailSearchRequest.value?.token == token) _detailSearchRequest.value = null
    }

    fun setAggregateMode(enabled: Boolean) {
        searchRequestSeq++
        if (_aggregateMode.value != enabled) resetSearchResults()
        _aggregateMode.value = enabled
    }

    fun setAggregateKind(kind: String) {
        require(kind == "comic" || kind == "novel") { "未知的聚合搜索类型" }
        if (_aggregateKind.value == kind) return
        searchRequestSeq++
        resetSearchResults()
        _aggregateKind.value = kind
    }

    private fun cancelAggregateSearch() {
        aggregateSearchSeq++
        aggregateSearchJob?.cancel()
        aggregateSearchJob = null
        singleSearchJob?.cancel()
        singleSearchJob = null
    }

    private fun resetSearchResults() {
        cancelAggregateSearch()
        _uiState.value = LibraryUiState.Empty
        errorMessage.value = null
    }

    /* ── 多语言搜索开关（第十一轮第 6 条）：UI 可见可控，驱动 expandVariants ── */
    private val _multiLanguageSearch = MutableStateFlow(prefs.multiLanguageSearch)
    val multiLanguageSearch: StateFlow<Boolean> = _multiLanguageSearch.asStateFlow()

    fun setMultiLanguageSearch(enabled: Boolean) {
        prefs.multiLanguageSearch = enabled
        _multiLanguageSearch.value = enabled
    }

    private var aggregateSearchSeq = 0L
    private var aggregateSearchJob: Job? = null
    private var singleSearchJob: Job? = null
    private val aggregateSearchSlots = Semaphore(4)
    private val comicAggregateSearch = ComicAggregateSearch(searches = sourceSearches)
    private var searchRequestSeq = 0L

    /* ── 多语言标题变体扩展（第七轮第 7 条；第十一轮第 6 条修复）──
     * 用户输入 → 归一化（含繁→简折叠）→ 查本地 AniList 标题库 → 命中作品的其它
     * 语言标题 → 作为额外关键词一起发给各书源。AniList 只是"标题扩展器"：
     * 离线 / 无数据 / 查询失败 / 用户关闭开关时安全退化为 [原始关键词] 单变体，
     * 搜索链路永不报错。
     *
     * 第十一轮第 6 条根因修复：旧版只做归一化列的"精确等值"匹配，而用户输入常为
     * 短名（"无职转生"）、库内存的是完整标题（"無職転生 ～異世界行ったら本気だす～"），
     * 等值永远落空 → 多语言扩展从未真正生效。现改为：精确 → 前缀/子串包含匹配；
     * 并叠加繁简折叠（"無職転生" ↔ "无职转生" 同一作品两种书写互匹配）。 */
    private val titleVariantCache=java.util.concurrent.ConcurrentHashMap<String, Pair<Long,List<String>>>()
    private suspend fun expandVariants(keyword: String): List<String> {
        val fallback = listOf(keyword)
        if (keyword.isBlank()) return fallback
        // 第十一轮第 6 条：多语言搜索开关（UI 可控；关闭后只用原始关键词）
        if (!prefs.multiLanguageSearch) return fallback
        val now=android.os.SystemClock.elapsedRealtime()
        titleVariantCache[keyword]?.takeIf { now-it.first<60_000 }?.let { return it.second }
        val result = runCatching {
            val normalized = com.example.source.anilist.TitleNormalizer.normalize(keyword)
            val compact = com.example.source.anilist.TitleNormalizer.compact(keyword)
            if (normalized.isEmpty()) return fallback
            val dao = com.example.data.AppDatabase.getDatabase(getApplication()).anilistDao()
            // 1) 精确等值（归一化/紧凑态任一命中）
            var mediaIds = dao.findMediaIds(normalized, compact)
            // 2) 子串包含兜底：短名（"无职转生"）命中完整标题（"無職転生 ～…～"）。
            //    归一化后 ≥2 字符才走包含匹配，避免单字噪音命中上百部作品
            if (mediaIds.isEmpty() && normalized.replace(" ", "").length >= 2) {
                val escNorm = escapeLike(normalized)
                val escComp = escapeLike(compact)
                if (escNorm.isNotEmpty() && escComp.isNotEmpty()) {
                    mediaIds = dao.findMediaIdsContaining(escNorm, escComp, 8)
                }
            }
            if (mediaIds.isEmpty()) return@runCatching fallback
            val rawTitles = dao.getRawTitlesFor(mediaIds)
            com.example.source.anilist.SearchVariantBuilder.build(keyword, rawTitles)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            fallback
        }
        if (titleVariantCache.size>=128) titleVariantCache.clear()
        titleVariantCache[keyword]=now to result
        return result
    }

    /** SQLite LIKE 通配符转义（配套 DAO 里的 ESCAPE '\'） */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun aggregateSearch(keyword: String) {
        searchRequestSeq++
        cancelAggregateSearch()
        val seq = aggregateSearchSeq
        // 聚合类别互斥过滤：novel=小说源（Z-Library/Legado 文字源）；comic=漫画源（且排除文字源）
        val novel = _aggregateKind.value == "novel"
        val sources = sourceManager.availableSources.value.filter {
            it.capabilities.supportSearch && !it.capabilities.environmentOnly &&
                if (novel) it.isNovelSource else it.isComicSource
        }
        Log.i("Aggregate", "aggregateSearch '$keyword' sources=${sources.map { it.name }}")
        if (sources.isEmpty()) {
            _uiState.value = LibraryUiState.Error(LibraryError.SourceUnavailable)
            return
        }
        aggregateSearchJob = viewModelScope.launch {
            errorMessage.value = null
            // 先展示所有源的“加载中”分组，哪个源先完成就先把哪个源的结果推给 UI
            val initialGroups = sources.map { source ->
                LibraryUiState.AggregateGroup(
                    sourceId = source.id,
                    sourceName = source.name,
                    books = emptyList(),
                    error = null,
                    loading = true
                )
            }
            _uiState.value = LibraryUiState.AggregateResults(initialGroups, running = true)
            if (!novel) {
                comicAggregateSearch.search(sources, keyword, ::expandVariants) { group ->
                    if (seq == aggregateSearchSeq) {
                        _uiState.update { current ->
                            if (current is LibraryUiState.AggregateResults) current.withComicSearchGroup(group)
                            else current
                        }
                    }
                }
                return@launch
            }
            // 第七轮第 7 条：多语言变体（本地 AniList 标题库扩展；离线安全退化）
            val variants = expandVariants(keyword)
            Log.i("Aggregate", "search variants: $variants")
            sources.forEach { source ->
                launch {
                    // 每个源依次用全部变体搜索：不同语言、不同书源的结果全部保留
                    // （跨源永不去重）；同一书源内同一资源 id 去重（多别名重复命中）
                    val collected = ArrayList<SearchBook>(32)
                    var lastError: String? = null
                    var anySuccess = false
                    variants.forEach { variant ->
                        // Z-Library（1lib.sk）首次搜索要过 DiamWall 挑战：
                        // OkHttp 503 PoW 解不了时 WebView 兜底全程约 30-40s，
                        // 20s 超时会中途砍掉 WebView 导致"搜索超时"假错误；
                        // 过挑战后验证 Cookie 同步进 OkHttp，后续搜索恢复秒级。
                        val perSourceTimeoutMs = if (source.id == "zlibrary") 55000L else 20000L
                        val result = aggregateSearchSlots.withPermit {
                            try {
                                withTimeoutOrNull(perSourceTimeoutMs) { sourceSearches.search(source, variant) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                SourceResult.Error(SourceException.NetworkError(e.message ?: "搜索失败", e))
                            }
                        }
                        if (seq != aggregateSearchSeq) return@launch
                        when (result) {
                            is SourceResult.Success -> {
                                anySuccess = true
                                collected.addAll(result.data.map { it.copy(sourceId = source.id) })
                                // 变体粒度流式更新：先到的语言结果先展示
                                _uiState.update { current ->
                                    if (current !is LibraryUiState.AggregateResults) current
                                    else {
                                        val merged = collected.distinctBy { it.id }
                                        LibraryUiState.AggregateResults(
                                            groups = current.groups.map {
                                                if (it.sourceId == source.id)
                                                    it.copy(books = merged, loading = true)
                                                else it
                                            },
                                            running = true
                                        )
                                    }
                                }
                            }
                            is SourceResult.Error -> {
                                lastError = result.exception.message
                            }
                            null -> lastError = "搜索超时"
                        }
                    }
                    if (seq != aggregateSearchSeq) return@launch
                    val group = if (anySuccess || collected.isNotEmpty()) {
                        LibraryUiState.AggregateGroup(
                            sourceId = source.id,
                            sourceName = source.name,
                            // 源内去重：仅去掉同一资源（同 id）的重复项；
                            // 不同书源/不同语言标题的结果即使同作品也全部保留
                            books = collected.distinctBy { it.id },
                            error = null,
                            loading = false
                        )
                    } else {
                        Log.i("Aggregate", "${source.name}: ERROR $lastError")
                        LibraryUiState.AggregateGroup(
                            sourceId = source.id,
                            sourceName = source.name,
                            books = emptyList(),
                            error = lastError,
                            loading = false
                        )
                    }
                    _uiState.update { current ->
                        if (current !is LibraryUiState.AggregateResults) return@update current
                        val newGroups = current.groups.map {
                            if (it.sourceId == source.id) group else it
                        }
                        LibraryUiState.AggregateResults(
                            groups = newGroups,
                            running = newGroups.any { it.loading }
                        )
                    }
                }
            }
        }
    }

    fun loadChapters(book: SearchBook) {
        val requestSeq = ++chaptersRequestSeq
        val source = sourceManager.availableSources.value.firstOrNull { it.id == book.sourceId } as? ComicSource
        if (source == null) {
            _comicChaptersError.value = "该漫画的书源已停用或不支持漫画"
            _comicChaptersLoading.value = false
            return
        }
        viewModelScope.launch {
            if (requestSeq != chaptersRequestSeq) return@launch
            _comicChaptersLoading.value = true
            _comicChaptersError.value = null
            try {
                val result = source.getChapters(book.id)
                if (requestSeq != chaptersRequestSeq) return@launch
                when (result) {
                    is SourceResult.Success -> {
                        _comicChapters.value = result.data
                        if (result.data.isEmpty()) _comicChaptersError.value = "暂无可用章节"
                    }
                    is SourceResult.Error -> {
                        _comicChaptersError.value = result.exception.message ?: "章节加载失败"
                    }
                }
                // Enrich every comic source's search snapshot. JS reuses the loadInfo request above.
                _comicChaptersLoading.value = false
                if (!_comicIsTextMode.value) {
                    val detail = try {
                        withTimeoutOrNull(15_000) { source.getDetail(book.id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("ComicDetail", "Metadata unavailable for ${book.sourceId}", e)
                        null
                    }
                    if (requestSeq != chaptersRequestSeq) return@launch
                    if (detail is SourceResult.Success) {
                        _comicBook.value = book.withComicDetail(detail.data)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestSeq == chaptersRequestSeq) _comicChaptersError.value = e.message ?: "章节加载失败"
            } finally {
                if (requestSeq == chaptersRequestSeq) _comicChaptersLoading.value = false
            }
        }
    }

    fun loadChapterImages(chapter: ComicChapter) {
        val requestSeq = ++imagesRequestSeq
        val source = sourceManager.availableSources.value
            .firstOrNull { it.id == _comicBook.value?.sourceId } as? ComicSource
        if (source == null) {
            _comicChapterError.value = "该漫画的书源已停用"
            _comicChapterLoading.value = false
            return
        }
        viewModelScope.launch {
            if (requestSeq != imagesRequestSeq) return@launch
            _activeComicChapter.value = chapter
            _comicChapterLoading.value = true
            _comicChapterError.value = null
            _comicChapterImages.value = emptyList()
            try {
                // 下一章预取命中：图片列表零网络直接上屏（对齐 Mihon/Kotatsu 预载策略）
                val prefetched = comicPrefetchCache[chapter.id]
                if (prefetched != null) {
                    if (requestSeq != imagesRequestSeq) return@launch
                    _comicChapterImages.value = prefetched
                    _comicChapterHeaders.value = runCatching {
                        source.getChapterImageHeaders(chapter.id, prefetched)
                    }.getOrDefault(emptyMap())
                    return@launch
                }
                val result = source.getChapterImages(chapter.id)
                if (requestSeq != imagesRequestSeq) return@launch
                when (result) {
                    is SourceResult.Success -> {
                        val headers = source.getChapterImageHeaders(chapter.id, result.data)
                        if (requestSeq != imagesRequestSeq) return@launch
                        _comicChapterImages.value = result.data
                        _comicChapterHeaders.value = headers
                    }
                    is SourceResult.Error -> {
                        _comicChapterError.value = result.exception.message ?: "图片加载失败"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestSeq == imagesRequestSeq) _comicChapterError.value = e.message ?: "图片加载失败"
            } finally {
                if (requestSeq == imagesRequestSeq) _comicChapterLoading.value = false
            }
        }
    }

    /** 供阅读器按页懒加载：把 e-hentai 图片页 URL 解析成真实图片 URL（带缓存）。 */
    suspend fun resolveComicImage(url: String): String? = withContext(Dispatchers.IO) {
        val source = sourceManager.availableSources.value
            .firstOrNull { it.id == _comicBook.value?.sourceId } as? ComicSource
        source?.resolveChapterImage(url)
    }

    /** 供阅读器按页懒加载：真实图片 URL 需要的请求头。 */
    suspend fun resolveComicImageHeaders(url: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val source = sourceManager.availableSources.value
                .firstOrNull { it.id == _comicBook.value?.sourceId } as? ComicSource
            source?.getResolvedHeaders(url) ?: emptyMap()
        }

    /* ── 下一章预取（对齐 Mihon/Kotatsu：读到章尾自动预载下一章） ── */

    // 插入序缓存（近 6 章）；预取命中后切章零网络
    private val comicPrefetchCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val comicPrefetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val comicPrefetchInFlight =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 读到章尾时预取下一章：图片列表进内存缓存，前两页图片进阅读器磁盘缓存。 */
    fun prefetchNextComicChapter(chapter: ComicChapter?) {
        val ch = chapter ?: return
        if (comicPrefetchCache.containsKey(ch.id)) return
        if (!comicPrefetchInFlight.add(ch.id)) return
        val selected = _comicBook.value ?: return
        val source = sourceManager.availableSources.value.firstOrNull { it.id == selected.sourceId } as? ComicSource ?: return
        comicPrefetchJobs[ch.id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    source.getChapterImages(ch.id)
                }
                if (result is SourceResult.Success && result.data.isNotEmpty() && _comicBook.value == selected) {
                    // 近 6 章上限：超出时挤掉最早的
                    if (comicPrefetchCache.size >= 6) {
                        comicPrefetchCache.remove(comicPrefetchCache.keys.first())
                    }
                    comicPrefetchCache[ch.id] = result.data
                    val headers = runCatching {
                        source.getChapterImageHeaders(ch.id, result.data)
                    }.getOrDefault(emptyMap())
                    // 前两页直接进阅读器加载器（磁盘缓存 512MB）：切章首屏零网络
                    result.data.take(2).forEach { url ->
                        com.example.ui.warmComicPage(url, headers[url].orEmpty())
                    }
                }
            } catch (e: Exception) {
                if(e is CancellationException) throw e
                // 预取失败静默：切章时按正常流程加载
            } finally {
                comicPrefetchInFlight.remove(ch.id)
                comicPrefetchJobs.remove(ch.id)
            }
        }
    }

    fun downloadComicChapter(book: SearchBook, chapter: ComicChapter) {
        if (chapter.external) {
            AppToast.makeText(
                getApplication(),
                "站外链接章节暂不支持下载",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val source = sourceManager.availableSources.value
            .firstOrNull { it.id == book.sourceId } as? ComicSource
        if (source == null) {
            AppToast.makeText(
                getApplication(),
                "当前书源不支持漫画",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        ComicDownloadManager.start(getApplication(), database, book, chapter, source)
    }

    fun retryComicChapter(book: SearchBook, chapter: ComicChapter) {
        downloadComicChapter(book, chapter)
    }

    fun pauseComicChapter(chapterId: String) {
        ComicDownloadManager.pause(chapterId, _comicBook.value)
    }

    fun cancelComicChapter(chapterId: String) {
        ComicDownloadManager.cancel(chapterId, _comicBook.value)
    }

    fun clearComicMessage() {
        ComicDownloadManager.clearMessage()
    }

    fun recordSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val updated = (listOf(q) + _searchHistory.value.filter { it != q }).take(20)
        _searchHistory.value = updated
        prefs.searchHistory = updated
    }

    /** 单条删除搜索历史（搜索历史卡长按编辑态的 × 按钮使用）。 */
    fun removeSearchHistory(query: String) {
        val updated = _searchHistory.value.filter { it != query }
        _searchHistory.value = updated
        prefs.searchHistory = updated
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        prefs.searchHistory = emptyList()
    }

    /**
     * 远程触发的逐源冒烟测试（adb am start -n ... --ez smoke_test true）：
     * 对每个 JS 漫画源 + MangaDex 依次做 搜索 → 章节 → 图片列表 → 真实拉取第一张图并解码，
     * 结果输出到 Logcat 的 SmokeTest 标签，用于排查“黑屏”问题。
     */
    /**
     * picacg 登录子项的凭据由开发者经 adb intent extras 现场传入
     * （--es smoke_user <u> --es smoke_pass <p>），不再写入源码；未提供则跳过该子项。
     */
    fun runSourceSmokeTest(
        filter: String = "",
        keyword: String = "",
        smokeUser: String? = null,
        smokePassword: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i("SmokeTest", "=== smoke test start ===")
            val client = com.example.source.SharedHttpTransport.builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .dns(com.example.source.js.SourceDns.dns())
                .build()
            val prefs = com.example.data.PreferencesManager(getApplication())
            var jsSources = JsSourceRepo.loadCached(getApplication(), includeAdult = true)
            if (jsSources.isEmpty()) {
                jsSources = JsSourceRepo.install(getApplication(), prefs.jsSourceRepoUrl, includeAdult = true)
            }
            val all = jsSources + listOf(MangaDexSource(context = getApplication()))
            all.forEach { source ->
                val name = (source as? com.example.source.js.JsComicSource)?.name ?: source.id
                if (filter.isNotBlank() && !source.id.contains(filter, ignoreCase = true) &&
                    !name.contains(filter, ignoreCase = true)
                ) {
                    return@forEach
                }
                val started = System.currentTimeMillis()
                try {
                    if (source.id == "js_picacg") {
                        val user = smokeUser
                        val pass = smokePassword
                        if (user.isNullOrBlank() || pass.isNullOrBlank()) {
                            Log.i("SmokeTest", "$name|login=SKIP|需 adb 传入 --es smoke_user / smoke_pass")
                        } else {
                            val loginResult = source.login(LoginCredential(username = user, password = pass))
                            Log.i("SmokeTest", "$name|login=${loginResult is SourceResult.Success}")
                        }
                    }
                    var books = emptyList<SearchBook>()
                    var second: SourceResult<List<SearchBook>>? = null
                    val searchTimeout = if (name.contains("ehentai", ignoreCase = true)) 50000L else 15000L
                    val first = if (keyword.isNotBlank()) {
                        withTimeoutOrNull(searchTimeout) { sourceSearches.search(source, keyword) }
                    } else {
                        withTimeoutOrNull(searchTimeout) { sourceSearches.search(source, "海贼王") }
                    }
                    if (first is SourceResult.Success && first.data.isNotEmpty()) {
                        books = first.data
                    } else if (keyword.isBlank()) {
                        second = withTimeoutOrNull(searchTimeout) { sourceSearches.search(source, "one piece") }
                        if (second is SourceResult.Success) books = second.data
                    }
                    if (books.isEmpty()) {
                        val err1 = (first as? SourceResult.Error)?.exception?.message
                        val err2 = if (first is SourceResult.Success) "" else (second as? SourceResult.Error)?.exception?.message ?: ""
                        Log.i("SmokeTest", "$name|FAIL|search empty ($err1 / $err2)|${System.currentTimeMillis() - started}ms")
                        return@forEach
                    }
                    val chapters = when (val r = withTimeoutOrNull(20000) { source.getChapters(books.first().id) }) {
                        is SourceResult.Success -> r.data
                        is SourceResult.Error -> {
                            Log.i("SmokeTest", "$name|FAIL|chapters err: ${r.exception.message}|${System.currentTimeMillis() - started}ms")
                            return@forEach
                        }
                        else -> emptyList()
                    }
                    if (chapters.isEmpty()) {
                        Log.i("SmokeTest", "$name|FAIL|chapters empty|${System.currentTimeMillis() - started}ms")
                        return@forEach
                    }
                    val chapter = chapters.first()
                    Log.i("SmokeTest", "  first chapter id: ${chapter.id.take(120)}")
                    val imgTimeout = if (name.contains("hitomi", ignoreCase = true) || name.contains("ehentai", ignoreCase = true)) 60000L else 25000L
                    val urls = when (val r = withTimeoutOrNull(imgTimeout) { source.getChapterImages(chapter.id) }) {
                        is SourceResult.Success -> r.data
                        is SourceResult.Error -> {
                            Log.i("SmokeTest", "$name|FAIL|images err: ${r.exception.message}|${System.currentTimeMillis() - started}ms")
                            return@forEach
                        }
                        else -> emptyList()
                    }
                    if (urls.isEmpty()) {
                        Log.i("SmokeTest", "$name|FAIL|images empty|${System.currentTimeMillis() - started}ms")
                        return@forEach
                    }
                    var fetchTarget = urls.first()
                    val resolved = source.resolveChapterImage(fetchTarget)
                    if (resolved != null && resolved != fetchTarget) fetchTarget = resolved
                    val baseHeaders = source.getChapterImageHeaders(chapter.id, urls)[urls.first()] ?: emptyMap()
                    val resolvedHeaders = source.getResolvedHeaders(fetchTarget)
                    val headers = baseHeaders + resolvedHeaders
                    val ok = fetchAndDecode(client, fetchTarget, headers)
                    if (ok) {
                        Log.i("SmokeTest", "$name|OK|pages=${urls.size}|${System.currentTimeMillis() - started}ms")
                    } else {
                        Log.i("SmokeTest", "$name|FAIL|image fetch/decode|pages=${urls.size}|${System.currentTimeMillis() - started}ms")
                    }
                } catch (e: Exception) {
                    Log.i("SmokeTest", "$name|FAIL|${e.javaClass.simpleName}: ${e.message}|${System.currentTimeMillis() - started}ms")
                }
            }
            Log.i("SmokeTest", "=== smoke test done ===")
        }
    }

    private suspend fun fetchAndDecode(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): Boolean = withTimeoutOrNull(30000) {
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                return@withTimeoutOrNull fetchOnce(client, url, headers)
            } catch (e: Exception) {
                lastErr = e
                Log.i("SmokeTest", "  fetch attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message} url=${url.take(120)}")
                kotlinx.coroutines.delay(1000L * attempt)
            }
        }
        Log.i("SmokeTest", "  fetch all attempts failed: ${lastErr?.message}")
        false
    } ?: false

    private suspend fun fetchOnce(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        if (url.startsWith("file:")) {
            val f = java.io.File(java.net.URI.create(url))
            if (!f.exists() || f.length() == 0L) return false
            val raw = f.readBytes()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            val ok = bmp != null && bmp.width > 0
            Log.i("SmokeTest", "  file fetch ${f.name}: ${raw.size} bytes, decode ok=$ok")
            return ok
        }
        val builder = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
            )
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", "image/webp,image/jpeg,image/png,*/*;q=0.8")
        }
        val cookie = com.example.source.js.JsCookieJar.cookieHeader(getApplication(), url)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i("SmokeTest", "  fetch ${url.take(90)} -> HTTP ${response.code}")
                return false
            }
            val raw = response.body?.bytes() ?: return false
            Log.i(
                "SmokeTest",
                "  fetch ${url.take(90)} -> HTTP 200, ${raw.size} bytes, type=${response.header("Content-Type")}"
            )
            var bytes = ImageBytes.normalizeImage(raw, response.header("Content-Encoding"))
            val magicAfter = bytes.take(12).joinToString(" ") { "%02X".format(it) }
            Log.i("SmokeTest", "  after normalize: ${bytes.size} bytes, magic=$magicAfter")
            if (ImageBytes.isAvif(bytes) && !ImageBytes.decodeOk(bytes)) {
                for (candidate in ImageBytes.webpVariants(url)) {
                    try {
                        val rb = Request.Builder().url(candidate)
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
                            )
                        client.newCall(rb.build()).execute().use { r2 ->
                            if (r2.isSuccessful) {
                                val b2 = r2.body?.bytes()
                                if (b2 != null) {
                                    val p2 = ImageBytes.normalizeImage(b2, r2.header("Content-Encoding"))
                                    if (!ImageBytes.isAvif(p2) && ImageBytes.decodeOk(p2)) {
                                        Log.i("SmokeTest", "  webp fallback OK: $candidate")
                                        bytes = p2
                                    }
                                }
                            }
                        }
                        if (!ImageBytes.isAvif(bytes) && ImageBytes.decodeOk(bytes)) break
                    } catch (e: Exception) {
                        // 尝试下一个候选
                    }
                }
            }
            bytes = if (MhttuImageDecryptor.isEncryptedHost(
                    try { java.net.URL(url).host } catch (e: Exception) { "" }
                )
            ) {
                MhttuImageDecryptor.decryptIfNeeded(bytes)
            } else {
                bytes
            }
            val transformed = com.example.source.js.JsImageProcessor.transform(url, bytes)
            if (transformed != null) {
                Log.i("SmokeTest", "  modifyImage applied: ${raw.size} -> ${transformed.size} bytes")
                bytes = transformed
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val ok = bmp != null && bmp.width > 0 && bmp.height > 0
            Log.i("SmokeTest", "  decode ok=$ok size=${bmp?.width}x${bmp?.height}")
            return ok
        }
    }

    fun clearComicState() {
        chaptersRequestSeq++
        imagesRequestSeq++
        comicPrefetchJobs.values.forEach { it.cancel() }
        comicPrefetchJobs.clear()
        comicPrefetchCache.clear()
        _comicBook.value = null
        _comicChapters.value = emptyList()
        _comicChaptersError.value = null
        _comicChapterImages.value = emptyList()
        _comicChapterHeaders.value = emptyMap()
        _comicChapterError.value = null
        _activeComicChapter.value = null
    }

    fun markWelcomeSeen() {
        prefs.hasSeenWelcome = true
        hasSeenWelcome.value = true
    }

    fun markSourceConfigured() {
        prefs.hasConfiguredSource = true
        hasConfiguredSource.value = true
    }

    fun markLocalBookImported() {
        prefs.hasImportedLocalBook = true
        hasImportedLocalBook.value = true
    }

    fun checkSourceLoginStatus() {
        val source = sourceManager.activeSource.value
        if (source != null) {
            viewModelScope.launch {
                _isCurrentSourceLoggedIn.value = source.isLoggedIn()
            }
        } else {
            _isCurrentSourceLoggedIn.value = false
        }
    }

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Empty)
    val uiState: StateFlow<LibraryUiState> = _uiState

    val downloadStates: StateFlow<Map<String, DownloadState>> = kotlinx.coroutines.flow.combine(
        downloadManager.downloadStates, downloadManager.allTasksFlow
    ) { states, tasks ->
        tasks.associate { task ->
            DownloadManager.taskId(task.sourceId, DownloadManager.originalBookId(task.id, task.sourceId)) to
                (states[task.id] ?: when (task.status) {
                    com.example.download.DownloadStatus.COMPLETED -> DownloadState.Success(task.filePath)
                    com.example.download.DownloadStatus.DOWNLOADING -> DownloadState.Downloading(task.downloadedBytes, task.totalBytes,
                        if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) else 0f)
                    com.example.download.DownloadStatus.PAUSED -> DownloadState.Paused(task.downloadedBytes, task.totalBytes)
                    com.example.download.DownloadStatus.FAILED -> DownloadState.Error(task.errorMessage ?: "下载失败")
                    else -> DownloadState.Pending
                })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val allDownloadTasks: Flow<List<DownloadTaskEntity>> = downloadManager.allTasksFlow
    val errorMessage = MutableStateFlow<String?>(null)

    fun search(keyword: String) {
        cancelAggregateSearch()
        val source = sourceManager.activeSource.value ?: return
        val requestSeq = ++searchRequestSeq
        singleSearchJob = viewModelScope.launch {
            if (requestSeq != searchRequestSeq) return@launch
            _uiState.value = LibraryUiState.Searching
            errorMessage.value = null
            // 第七轮第 7 条：单源搜索同样走多语言变体（本地 AniList 标题库扩展）；
            // 源内按资源 id 去重（多别名命中同一资源只显示一次）
            val variants = expandVariants(keyword)
            val collected = ArrayList<SearchBook>(32)
            var firstError: SourceException? = null
            variants.forEach { variant ->
                if (requestSeq != searchRequestSeq) return@launch
                val result = try {
                    sourceSearches.search(source, variant)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SourceResult.Error(SourceException.NetworkError(e.message ?: "搜索失败", e))
                }
                when (result) {
                    is SourceResult.Success -> collected.addAll(result.data.map { it.copy(sourceId = source.id) })
                    is SourceResult.Error -> if (firstError == null) firstError = result.exception
                }
            }
            if (requestSeq != searchRequestSeq) return@launch
            if (collected.isNotEmpty()) {
                val merged = collected.distinctBy { it.id }
                _uiState.value = LibraryUiState.SearchResults(merged)
                if (source.id == "mangadex" && merged.isNotEmpty()) {
                    enrichMangaDexAuthors(merged, source, requestSeq)
                }
            } else {
                val e = firstError
                val error = when (e) {
                    null -> LibraryError.Unknown("没有找到相关结果")
                    is SourceException.NetworkError -> {
                        val msg = e.message ?: ""
                        com.example.source.SourceLog.log(source.name, "搜索「$keyword」失败: $msg")
                        when {
                            msg.contains("Cloudflare") || msg.contains("DiamWall") -> LibraryError.CloudflareBlocked
                            msg.contains("Z-Library 当前节点不可用") -> LibraryError.SourceUnavailable
                            msg.isNotBlank() -> LibraryError.NetworkDetail(msg)
                            else -> LibraryError.NetworkUnavailable
                        }
                    }
                    is SourceException.LoginRequired -> LibraryError.AuthenticationRequired
                    is SourceException.ParseError -> LibraryError.ParseFailed(e.message ?: "解析失败")
                    else -> LibraryError.Unknown(e.message ?: "未知错误", e)
                }
                _uiState.value = LibraryUiState.Error(error)
                errorMessage.value = error.message ?: "发生错误"
            }
        }
    }

    private suspend fun enrichMangaDexAuthors(books: List<SearchBook>, source: BookSource, requestSeq: Long) {
        val semaphore = Semaphore(5)
        val enriched = books.map { book ->
            semaphore.withPermit {
                val detail = source.getDetail(book.id).getOrNull()
                if (detail != null) {
                    book.copy(
                        author = detail.author.takeIf { it.isNotBlank() && it != "MangaDex" } ?: book.author,
                        description = detail.description ?: book.description,
                        cover = book.cover ?: detail.cover
                    )
                } else {
                    book
                }
            }
        }
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (requestSeq == searchRequestSeq && enriched.isNotEmpty()) {
                _uiState.value = LibraryUiState.SearchResults(enriched)
            }
        }
    }

    fun selectSource(sourceId: String) {
        searchRequestSeq++
        resetSearchResults()
        _aggregateMode.value = false
        viewModelScope.launch {
            sourceManager.setActiveSource(sourceId)
        }
    }

    fun startDownload(book: SearchBook) {
        val source = sourceManager.availableSources.value.firstOrNull { it.id == book.sourceId }
            ?: sourceManager.activeSource.value
            ?: return
        // Z-Library 支持多格式：先取格式列表，多个格式时弹选择框
        if (source is ZLibrarySource) {
            viewModelScope.launch {
                _formatPickerBook.value = book
                _formatLoading.value = true
                val fetched = when (val result = source.getAvailableFormats(book)) {
                    is SourceResult.Success -> result.data.filter { it.format.isNotBlank() }
                    is SourceResult.Error -> emptyList()
                }
                _formatLoading.value = false
                // 过滤阅读器不支持的格式（PDF 等），避免下载后书架出现打不开的书
                val formats = filterReadableFormats(fetched)
                when {
                    formats.size > 1 -> _pendingFormats.value = formats
                    formats.size == 1 -> {
                        _formatPickerBook.value = null
                        _pendingFormats.value = emptyList()
                        downloadBook(book, source, formats.first().format)
                    }
                    fetched.isNotEmpty() -> {
                        // 全部格式都不支持阅读：不再下载，提示换其他版本
                        _formatPickerBook.value = null
                        _pendingFormats.value = emptyList()
                        errorMessage.value = "该书仅有 " +
                            fetched.joinToString(" / ") { it.format.uppercase() } +
                            " 格式，App 内暂不支持阅读，请换其他版本"
                    }
                    else -> {
                        // 格式获取失败：按默认格式下载
                        _formatPickerBook.value = null
                        _pendingFormats.value = emptyList()
                        downloadBook(book, source, null)
                    }
                }
            }
        } else {
            downloadBook(book, source, null)
        }
    }

    /** 用户选定格式后下载（格式为空表示用默认格式）。 */
    fun startDownload(book: SearchBook, format: String?) {
        _formatPickerBook.value = null
        _pendingFormats.value = emptyList()
        val source = sourceManager.availableSources.value.firstOrNull { it.id == book.sourceId }
            ?: sourceManager.activeSource.value
            ?: return
        // 默认格式走软件自研 /dl/ + Cookie 方案，非默认格式走 eapi 多格式；
        // 具体路由由 getDownloadInfo 内部按 preferredFormat 决定，这里统一交给下载器。
        downloadBook(book, source, format)
    }

    fun dismissFormatPicker() {
        _formatPickerBook.value = null
        _pendingFormats.value = emptyList()
    }

    private fun downloadBook(book: SearchBook, source: BookSource, format: String?, replaceNovel: Boolean = false) {
        viewModelScope.launch {
            val snapshot = if (WholeBookNovelSources.contains(book.sourceId) && !replaceNovel) {
                (source.getDetail(book.id) as? SourceResult.Success)?.data ?: book
            } else book
            when (val result = source.getDownloadInfo(book.id, preferredFormat = format)) {
                is SourceResult.Success -> {
                    val finalFormat = result.data.format.ifBlank { book.format }
                    // 统一兜底：阅读器不支持的格式（PDF 等）不下载，避免书架出现打不开的书
                    if (finalFormat.lowercase() in READER_UNSUPPORTED_FORMATS) {
                        errorMessage.value = "该书文件为 ${finalFormat.uppercase()} 格式，App 内暂不支持阅读，请换其他版本"
                        return@launch
                    }
                    val request = DownloadRequest(
                        bookId = book.id,
                        title = snapshot.title,
                        author = snapshot.author,
                        sourceId = book.sourceId,
                        downloadUrl = result.data.url,
                        format = finalFormat,
                        coverUrl = snapshot.cover,
                        novelSnapshot = snapshot.takeIf { WholeBookNovelSources.contains(it.sourceId) },
                        replaceExistingNovel = replaceNovel
                    )
                    downloadManager.enqueueDownload(request, result.data.referer, result.data.headers)
                }
                is SourceResult.Error -> {
                    errorMessage.value = result.exception.message ?: "获取下载信息失败"
                    if (replaceNovel) _novelUpdate.value?.takeIf {
                        it.local.sourceId == book.sourceId && it.local.comicId == book.id
                    }?.let { _novelUpdate.value = it.copy(error = errorMessage.value) }
                }
            }
        }
    }

    suspend fun novelDetail(book: SearchBook): SourceResult<SearchBook> {
        val source = sourceManager.availableSources.value.firstOrNull { it.id == book.sourceId && it.isNovelSource }
            ?: return SourceResult.Error(SourceException.ParseError("此小说源已停用"))
        return when (val result = source.getDetail(book.id)) {
            is SourceResult.Error -> result
            is SourceResult.Success -> SourceResult.Success(result.data.copy(
                id = book.id, sourceId = book.sourceId,
                title = result.data.title.ifBlank { book.title }, author = result.data.author.ifBlank { book.author },
                cover = result.data.cover ?: book.cover, language = result.data.language ?: book.language,
                size = result.data.size ?: book.size, description = result.data.description ?: book.description,
                eapiId = result.data.eapiId ?: book.eapiId, eapiHash = result.data.eapiHash ?: book.eapiHash,
                novelInfo = result.data.novelInfo ?: book.novelInfo))
        }
    }

    suspend fun localNovel(book: SearchBook): com.example.data.Book? = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(getApplication()).bookDao().getBookBySourceResource(book.sourceId, book.id)
    }

    data class NovelUpdate(val local: com.example.data.Book, val sourceName: String, val loading: Boolean = true,
        val remote: SearchBook? = null, val changed: Boolean? = null, val error: String? = null)
    private val _novelUpdate = MutableStateFlow<NovelUpdate?>(null)
    val novelUpdate = _novelUpdate.asStateFlow()
    private var novelUpdateJob: Job? = null

    fun dismissNovelUpdate() { novelUpdateJob?.cancel(); _novelUpdate.value = null }

    fun checkNovelUpdate(local: com.example.data.Book) {
        if (!WholeBookNovelSources.contains(local.sourceId) || local.isComic || local.comicId.isNullOrBlank()) return
        novelUpdateJob?.cancel()
        val source = sourceManager.availableSources.value.firstOrNull { it.id == local.sourceId } as? UpdatableNovelSource
        _novelUpdate.value = NovelUpdate(local, source?.name ?: "小说源")
        novelUpdateJob = viewModelScope.launch {
            if (source == null) { _novelUpdate.value = NovelUpdate(local, "小说源", loading = false, error = "此小说源已停用"); return@launch }
            try {
                when (val result = source.refreshNovelDetail(local.comicId)) {
                    is SourceResult.Success -> {
                        val baseline = com.example.download.NovelDownloadStore(getApplication()).baseline(source.id, local.comicId)?.novelInfo
                        val remote = result.data.novelInfo
                        val changed = if (baseline?.hasRevision == true && remote?.hasRevision == true) baseline.revision != remote.revision else null
                        _novelUpdate.value = NovelUpdate(local, source.name, loading = false, remote = result.data, changed = changed)
                    }
                    is SourceResult.Error -> _novelUpdate.value = NovelUpdate(local, source.name, loading = false, error = result.exception.message ?: "检查失败，请重试")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _novelUpdate.value = NovelUpdate(local, source.name, loading = false, error = e.message ?: "检查失败，请重试") }
        }
    }

    fun downloadNovelUpdate(book: SearchBook) {
        val source = sourceManager.availableSources.value.firstOrNull { it.id == book.sourceId && WholeBookNovelSources.contains(it.id) } ?: return
        downloadBook(book, source, book.format, replaceNovel = true)
    }

    /**
     * WebView 会话下载入口：真实文件 URL 来自隐藏 WebView 的 DownloadListener，
     * Cookie 头来自该会话（含 __diamwall / 登录凭证）。
     */
    fun startWebViewDownload(book: SearchBook?, realUrl: String, cookieHeader: String) {
        if (book == null || realUrl.isBlank()) return
        val format = guessFileFormatFromUrl(realUrl) ?: book.format.ifBlank { "epub" }
        // 阅读器不支持的格式（如 PDF）不再入下载队列，避免书架出现打不开的书
        if (format.lowercase() in READER_UNSUPPORTED_FORMATS) {
            errorMessage.value = "该书文件为 ${format.uppercase()} 格式，App 内暂不支持阅读，请换其他版本"
            return
        }
        val request = DownloadRequest(
            bookId = book.id,
            title = book.title,
            author = book.author,
            sourceId = book.sourceId,
            downloadUrl = realUrl,
            format = format,
            coverUrl = book.cover
        )
        downloadManager.enqueueDownload(
            request,
            referer = "https://${ZLibraryNodeConfig.domain}/",
            headers = mapOf("Cookie" to cookieHeader)
        )
    }

    fun pauseDownload(bookId: String) {
        downloadManager.pauseDownload(bookId)
    }

    fun resumeDownload(bookId: String) {
        downloadManager.resumeDownload(bookId)
    }

    fun cancelDownload(bookId: String) {
        downloadManager.cancelDownload(bookId)
    }
}
