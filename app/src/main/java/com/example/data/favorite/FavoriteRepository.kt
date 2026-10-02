package com.example.data.favorite

import com.example.source.ComicChapter
import com.example.source.ComicSource
import com.example.source.SearchBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** 距上次检查超过这个间隔才在「进入栏 / 下拉刷新」时联网检查更新。 */
const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L

/** 「全部」是筛选伪分类，不是真实分类行（不写进 favorite_categories）。 */
const val ALL_FAV_CATEGORY_NAME = "全部"

/**
 * 「我喜欢的」+ 阅读进度的仓库：收藏、进度、下载三态互不耦合的唯一出口。
 *
 * 设计要点：
 * - 收藏/进度用 (sourceId, comicId) 关联，与 Book（本地下载）完全独立：
 *   取消喜欢不动下载与进度，删除下载也不动喜欢与进度；
 * - 更新检查带两级限流（全局并发 ≤3 + 同一来源每秒最多 1 个请求），
 *   单本失败只标记该本、不影响其他本，避免被来源封禁。
 */
class FavoriteRepository(
    private val dao: FavoriteDao,
    private val comicSourceOf: suspend (String) -> ComicSource?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val repoScope = scope
    var catalogContext: android.content.Context? = null
    private val catalogMutex = kotlinx.coroutines.sync.Mutex()

    /* ───────────── 收藏 ───────────── */

    val favorites: StateFlow<List<FavoriteEntity>> = dao.allFavorites()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    /** 收藏主键集合（书架卡片右下角小心形用；Room Flow 全量订阅，不做 N 次单查） */
    val favoriteKeys: StateFlow<Set<String>> = dao.favoriteKeys()
        .map { it.toSet() }
        .stateIn(repoScope, SharingStarted.Eagerly, emptySet())

    /* ───────────── 收藏分类（独立于书架） ───────────── */

    /** 「我喜欢的」自己的分类体系；书架的 categories 与本表互不干涉。 */
    val favoriteCategories: StateFlow<List<FavoriteCategoryEntity>> =
        dao.favoriteCategories().stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    suspend fun addFavoriteCategory(name: String) {
        if (name.isBlank()) return
        dao.insertFavoriteCategory(
            FavoriteCategoryEntity(
                name = name.trim(),
                sortOrder = dao.favoriteCategoriesSync().size,
            )
        )
    }

    suspend fun renameFavoriteCategory(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        dao.renameFavoriteCategoryCascade(oldName, newName.trim())
    }

    /** 删除分类：里面的收藏退回「默认」，绝不连带删除收藏本身。 */
    suspend fun deleteFavoriteCategory(name: String) {
        if (name == FAV_DEFAULT_CATEGORY) return
        dao.deleteFavoriteCategoryAndRetag(name, FAV_DEFAULT_CATEGORY)
    }

    /** 保证分类存在（收藏/拖拽落点前调用，避免出现"有收藏但没有分类行"）。 */
    suspend fun ensureFavoriteCategory(name: String) {
        if (name.isBlank() || name == ALL_FAV_CATEGORY_NAME) return
        if (dao.favoriteCategoriesSync().none { it.name == name }) {
            dao.insertFavoriteCategory(
                FavoriteCategoryEntity(name = name, sortOrder = dao.favoriteCategoriesSync().size)
            )
        }
    }

    suspend fun isFavorite(sourceId: String, comicId: String): Boolean =
        dao.favorite(sourceId, comicId) != null

    suspend fun favoritesSnapshot(): List<FavoriteEntity> = dao.allFavoritesSync()

    suspend fun replaceFavorite(
        from: ComicKey,
        book: SearchBook,
        newChapters: List<ComicChapter>,
    ): FavoriteMigrationReport = withContext(Dispatchers.IO) {
        require(from.valid && book.sourceId.isNotBlank() && book.id.isNotBlank())
        require(from.raw != favoriteKey(book.sourceId, book.id))
        require(newChapters.isNotEmpty()) { "新来源暂无可用章节，暂时无法迁移" }
        catalogMutex.lock()
        try {
            val catalog = catalogContext?.let(::ChapterCatalog)
            var oldChapters = catalog?.read(from.sourceId, from.comicId).orEmpty()
            if (oldChapters.isEmpty()) {
                val source = comicSourceOf(from.sourceId)
                val loaded = try {
                    kotlinx.coroutines.withTimeoutOrNull(15_000) { source?.getChapters(from.comicId) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { null }
                if (loaded is com.example.source.SourceResult.Success) oldChapters = loaded.data
            }
            val mapping = ComicChapterMatching.mapping(oldChapters, newChapters).mapValues { it.value.id }
            val ordered = ComicReadingLogic.ordered(newChapters)
            val latest = ordered.lastOrNull()?.chapter
            val replacement = FavoriteEntity(
                sourceId = book.sourceId, comicId = book.id,
                title = book.title, author = book.author, coverUrl = book.cover,
                serialStatus = serialStatusOf(book.comicInfo?.status),
                latestChapterId = latest?.id, latestChapterTitle = latest?.title,
                lastCheckedAt = System.currentTimeMillis(),
            )
            // Write catalog first: a failed disk write must not remove the old favorite.
            catalog?.write(book.sourceId, book.id, newChapters)
            dao.replaceFavoriteMapped(from, replacement, mapping,
                ordered.associate { it.chapter.id to it.order }, latest?.id)
        } finally { catalogMutex.unlock() }
    }

    fun favoriteFlow(sourceId: String, comicId: String): Flow<FavoriteEntity?> =
        dao.favoriteFlow(sourceId, comicId)

    /** 加入收藏（幂等：已存在则只更新分类与快照）。 */
    suspend fun add(
        book: SearchBook,
        category: String = FAV_DEFAULT_CATEGORY,
        chapters: List<ComicChapter> = emptyList(),
    ) {
        if (book.sourceId.isBlank() || book.id.isBlank()) return
        val top = ComicReadingLogic.ordered(chapters).lastOrNull()
        val existing = dao.favorite(book.sourceId, book.id)
        val useCategory = if (existing != null) existing.categoryName else category
        // 分类行必须存在（收藏可以指向任意分类名，但 chip 栏只认 favorite_categories 里的行）
        ensureFavoriteCategory(useCategory)
        dao.insertFavorite(
            (existing ?: FavoriteEntity(
                sourceId = book.sourceId,
                comicId = book.id,
                title = book.title,
                author = book.author,
                coverUrl = book.cover,
            )).copy(
                title = book.title,
                author = book.author,
                coverUrl = book.cover ?: existing?.coverUrl,
                categoryName = useCategory,
                sourceAlive = true,
                serialStatus = book.comicInfo?.status?.let(::serialStatusOf) ?: existing?.serialStatus ?: SerialStatus.UNKNOWN.code,
                latestChapterId = top?.chapter?.id ?: existing?.latestChapterId,
                latestChapterTitle = top?.chapter?.title ?: existing?.latestChapterTitle,
            )
        )
    }

    /** 批量加入收藏：返回成功条数（无来源的书由调用方提前过滤）。 */
    suspend fun addAll(books: List<SearchBook>, category: String = FAV_DEFAULT_CATEGORY): Int {
        val valid = books.filter { it.sourceId.isNotBlank() && it.id.isNotBlank() }
        valid.forEach { add(it, category) }
        return valid.size
    }

    suspend fun remove(sourceId: String, comicId: String) =
        dao.deleteFavorite(sourceId, comicId)

    suspend fun removeByKeys(keys: List<String>) = dao.deleteFavoritesByKeys(keys)

    suspend fun moveToCategory(keys: List<String>, category: String) {
        ensureFavoriteCategory(category)
        dao.moveFavoritesToCategory(keys, category)
    }

    /* ───────────── 阅读进度（与收藏、下载无关） ───────────── */

    /** 全部漫画级进度，按 "sourceId::comicId" 索引（聚合列表用，避免 N 次单查）。 */
    val progressByKey: StateFlow<Map<String, ComicProgressEntity>> = dao.allProgress()
        .map { list -> list.associateBy { favoriteKey(it.sourceId, it.comicId) } }
        .stateIn(repoScope, SharingStarted.Eagerly, emptyMap())

    fun progressFlow(sourceId: String, comicId: String): Flow<ComicProgressEntity?> =
        dao.progressFlow(sourceId, comicId)

    fun chapterStatesFlow(sourceId: String, comicId: String): Flow<List<ChapterReadEntity>> =
        dao.chapterStates(sourceId, comicId)

    suspend fun chapterStates(sourceId: String, comicId: String): Map<String, ChapterReadEntity> =
        dao.chapterStatesSync(sourceId, comicId).associateBy { it.chapterId }

    /**
     * 翻页时保存进度（调用方做防抖；退出阅读器/进后台强制调用一次）。
     * 同时维护章节级状态：未读完 → 阅读中，读完（最后一页 或 ≥90%）→ 已读。
     */
    suspend fun saveProgress(
        sourceId: String,
        comicId: String,
        chapterId: String,
        chapterIndex: Int,
        pageIndex: Int,
        pageCount: Int,
    ) {
        val finished = ComicReadingLogic.isFinished(pageIndex, pageCount)
        dao.upsertProgress(
            ComicProgressEntity(
                sourceId = sourceId,
                comicId = comicId,
                lastChapterId = chapterId,
                lastChapterIndex = chapterIndex,
                lastPageIndex = pageIndex.coerceAtLeast(0),
                lastPageCount = pageCount.coerceAtLeast(0),
                lastReadAt = System.currentTimeMillis(),
            )
        )
        dao.upsertChapterStates(
            listOf(
                // 读旧合并：REPLACE 会整行覆盖，直接构造会抹掉读者标注的书签
                (dao.chapterStatesSync(sourceId, comicId)
                    .firstOrNull { it.chapterId == chapterId } ?: ChapterReadEntity(
                    sourceId = sourceId,
                    comicId = comicId,
                    chapterId = chapterId,
                    chapterIndex = chapterIndex,
                )).copy(
                    status = if (finished) ChapterReadState.READ.code else ChapterReadState.READING.code,
                    pageIndex = pageIndex.coerceAtLeast(0),
                    pageCount = pageCount.coerceAtLeast(0),
                    chapterIndex = chapterIndex,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
    }

    /** 手动把某一章标记为已读/未读（长按章节）。 */
    suspend fun markChapter(
        sourceId: String,
        comicId: String,
        chapterId: String,
        chapterIndex: Int,
        read: Boolean,
    ) {
        // 读旧合并：保留书签列（REPLACE 会整行覆盖）
        val previous = dao.chapterStatesSync(sourceId, comicId)
            .firstOrNull { it.chapterId == chapterId }
        dao.upsertChapterStates(
            listOf(
                (previous ?: ChapterReadEntity(
                    sourceId = sourceId,
                    comicId = comicId,
                    chapterId = chapterId,
                    chapterIndex = chapterIndex,
                )).copy(
                    status = if (read) ChapterReadState.READ.code else ChapterReadState.UNREAD.code,
                    chapterIndex = chapterIndex,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
    }

    /** 详情页左滑/右滑该话卡片：切换书签标注。 */
    suspend fun setChapterBookmark(
        sourceId: String,
        comicId: String,
        chapterId: String,
        chapterIndex: Int,
        bookmarked: Boolean,
    ) {
        val previous = dao.chapterStatesSync(sourceId, comicId)
            .firstOrNull { it.chapterId == chapterId }
        dao.upsertChapterStates(
            listOf(
                (previous ?: ChapterReadEntity(
                    sourceId = sourceId,
                    comicId = comicId,
                    chapterId = chapterId,
                    chapterIndex = chapterIndex,
                )).copy(
                    bookmarked = bookmarked,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )
    }

    /** 「将以上全部标记为已读」（按阅读序号批量置位）。 */
    suspend fun markChaptersReadUpTo(sourceId: String, comicId: String, maxIndex: Int) =
        dao.markChaptersReadUpTo(sourceId, comicId, maxIndex)

    /** 进入章节列表时记录「已见」快照，用于之后显示「新」小红点。 */
    suspend fun markSeen(sourceId: String, comicId: String, chapters: List<ComicChapter>) {
        reconcileCatalog(sourceId,comicId,chapters)
        val seq = ComicReadingLogic.ordered(chapters)
        val top = seq.lastOrNull()?.chapter?.id
        val cur = dao.progress(sourceId, comicId)
        dao.upsertProgress(
            (cur ?: ComicProgressEntity(sourceId = sourceId, comicId = comicId)).copy(
                seenTopChapterId = top ?: cur?.seenTopChapterId,
                seenChapterCount = seq.size,
            )
        )
    }

    /** 换源迁移（粗糙版）：收藏 + 进度 + 章节状态整体搬到新的 (sourceId, comicId)。 */
    suspend fun migrateKey(from: ComicKey, to: ComicKey) =
        dao.migrateKey(from.sourceId, from.comicId, to.sourceId, to.comicId)

    /**
     * 换源迁移（推荐）：按「阅读序号」把章节状态映射到新源的章节上。
     *
     * 不同书源的 chapterId 毫无关系，直接整体搬（[migrateKey]）会让已读状态全部错位；
     * 这里用归一化后的阅读序号做映射：旧源第 N 话的已读状态 → 新源第 N 话。
     * 差集（新源多出/缺失的章节）保持未读，不做猜测。
     *
     * @param newChapters 新源的章节列表（用于建立序号 → 新 chapterId 的映射）
     * @return 成功映射的章节条数
     */
    suspend fun migrateByOrder(
        from: ComicKey,
        to: ComicKey,
        newChapters: List<ComicChapter>,
    ): Int = withContext(Dispatchers.IO) {
        if (!from.valid || !to.valid || from.raw == to.raw) return@withContext 0
        val seq = ComicReadingLogic.ordered(newChapters)
        val oldStates = dao.chapterStatesSync(from.sourceId, from.comicId)
        val oldProg = dao.progress(from.sourceId, from.comicId)
        val oldFav = dao.favorite(from.sourceId, from.comicId)

        val catalog=catalogContext?.let { ChapterCatalog(it) }
        val identities=catalog?.mapping(catalog.read(from.sourceId,from.comicId),newChapters).orEmpty()
        val orders=seq.associate { it.chapter.id to it.order }
        val mapped=oldStates.mapNotNull { state -> identities[state.chapterId]?.let { chapter ->
            state.copy(sourceId=to.sourceId,comicId=to.comicId,chapterId=chapter.id,chapterIndex=orders[chapter.id] ?: -1)
        } }
        val progressChapter=oldProg?.lastChapterId?.let { identities[it] }
        val newProgress=if(oldProg!=null && progressChapter!=null) oldProg.copy(sourceId=to.sourceId,comicId=to.comicId,
            lastChapterId=progressChapter.id,lastChapterIndex=orders[progressChapter.id] ?: -1,
            seenTopChapterId=seq.lastOrNull()?.chapter?.id,seenChapterCount=seq.size) else null
        val complete=mapped.size==oldStates.size && (oldProg?.lastChapterId==null || progressChapter!=null)
        dao.migrateResolved(from,to,mapped,newProgress,oldFav?.copy(sourceId=to.sourceId,comicId=to.comicId),complete)
        catalog?.write(to.sourceId,to.comicId,newChapters)
        mapped.size
    }

    private suspend fun reconcileCatalog(source:String,comic:String,chapters:List<ComicChapter>) = withContext(Dispatchers.IO) {
        val context=catalogContext ?: return@withContext
        catalogMutex.lock()
        try {
            val catalog=ChapterCatalog(context)
            val mapping=catalog.mapping(catalog.read(source,comic),chapters)
            val orders=ComicReadingLogic.ordered(chapters).associate { it.chapter.id to it.order }
            val states=dao.chapterStatesSync(source,comic)
            val changed=states.mapNotNull { state -> mapping[state.chapterId]?.takeIf { it.id!=state.chapterId }?.let { chapter ->
                state.copy(chapterId=chapter.id,chapterIndex=orders[chapter.id] ?: state.chapterIndex)
            } }
            val progress=dao.progress(source,comic)
            val mapped=progress?.lastChapterId?.let { mapping[it] }
            val updated=if(mapped!=null) progress?.copy(lastChapterId=mapped.id,lastChapterIndex=orders[mapped.id] ?: progress.lastChapterIndex) else null
            val changedIds=changed.mapTo(HashSet()) { it.chapterId }
            val oldIds=states.filter { mapping[it.chapterId]?.id in changedIds }.map { it.chapterId }
            dao.reconcileChapterIds(source,comic,changed,updated,oldIds)
            catalog.write(source,comic,chapters)
        } finally { catalogMutex.unlock() }
    }

    /* ───────────── 更新检测 ───────────── */

    private val updateGate = Semaphore(3)
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    private var checking = false

    /** 单本失败不影响其他本；并发 ≤3，同一来源每秒最多 1 个请求。 */
    private suspend fun throttle(sourceId: String) {
        val last = lastRequestAt[sourceId] ?: 0L
        val wait = (last + 1000L) - System.currentTimeMillis()
        if (wait > 0) delay(min(wait, 1000L))
        lastRequestAt[sourceId] = System.currentTimeMillis()
    }

    /**
     * 检查更新（stale-while-revalidate：先返回缓存快照，后台刷新不阻塞 UI）。
     * @param force true = 下拉刷新，忽略 30 分钟间隔
     * @return 本次真正联网检查过的条数
     */
    suspend fun checkUpdates(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (checking) return@withContext 0
        checking = true
        try {
            val now = System.currentTimeMillis()
            val targets = dao.allFavoritesSync().filter {
                force || now - it.lastCheckedAt > UPDATE_CHECK_INTERVAL_MS
            }
            targets.forEach { fav ->
                repoScope.launch {
                    updateGate.withPermit {
                        runCatching {
                            throttle(fav.sourceId)
                            val source = comicSourceOf(fav.sourceId) ?: return@runCatching
                            val chapters = when (val r = source.getChapters(fav.comicId)) {
                                is com.example.source.SourceResult.Success -> r.data
                                is com.example.source.SourceResult.Error -> throw IllegalStateException(
                                    r.exception.message ?: "章节加载失败"
                                )
                            }
                            val top = ComicReadingLogic.ordered(chapters).lastOrNull()
                            // ⚠️ 回写前重读当前行：targets 是检查开始时的快照，检查期间
                            // 用户可能取消收藏 / 移动分类 —— 拿快照整行 REPLACE 会把
                            // 已删除的收藏"复活"、把分类跳回旧值。
                            val fresh = dao.favorite(fav.sourceId, fav.comicId)
                                ?: return@runCatching
                            // ⚠️ 「是否真的有新话」要走话数归一化判定（isNewChapterObserved）：
                            // 裸 id 比较在 id 不稳定的源上会把同一话反复判成新话。
                            val changed = ComicReadingLogic.isNewChapterObserved(
                                fav.latestChapterId, fav.latestChapterTitle, top,
                            )
                            dao.insertFavorite(
                                fresh.copy(
                                    latestChapterId = top?.chapter?.id ?: fresh.latestChapterId,
                                    latestChapterTitle = top?.chapter?.title ?: fresh.latestChapterTitle,
                                    // ⚠️ 只有真的观察到新话才推进「更新时间」：以前每次检查
                                    // 都盖 now —— 「最近更新」排序实际是「最近检查过」，
                                    // 每 30 分钟自动检查后所有收藏都排到最前（用户实测排序乱跳）
                                    latestChapterUpdateAt = if (changed) {
                                        System.currentTimeMillis()
                                    } else {
                                        fresh.latestChapterUpdateAt
                                    },
                                    lastCheckedAt = System.currentTimeMillis(),
                                    sourceAlive = true,
                                )
                            )
                        }.onFailure {
                            // 单本失败只标记这一本：保留缓存信息与已读状态，UI 显示灰色警示。
                            // 同样重读当前行，避免复活竞态
                            val fresh = dao.favorite(fav.sourceId, fav.comicId)
                            if (fresh != null) {
                                dao.insertFavorite(
                                    fresh.copy(
                                        lastCheckedAt = System.currentTimeMillis(),
                                        sourceAlive = false,
                                    )
                                )
                            }
                        }
                    }
                }
            }
            targets.size
        } finally {
            checking = false
        }
    }

    /** 来源失效后重试单本。 */
    suspend fun retrySource(sourceId: String, comicId: String): Boolean = withContext(Dispatchers.IO) {
        updateGate.withPermit {
            runCatching {
                throttle(sourceId)
                val source = comicSourceOf(sourceId) ?: return@runCatching false
                val chapters = when (val r = source.getChapters(comicId)) {
                    is com.example.source.SourceResult.Success -> r.data
                    is com.example.source.SourceResult.Error -> return@runCatching false
                }
                val top = ComicReadingLogic.ordered(chapters).lastOrNull()
                // 与 checkUpdates 同款：重读当前行（防复活竞态）+ 话数归一化判定
                // + 只有真观察到新话才推进「更新时间」
                val fresh = dao.favorite(sourceId, comicId) ?: return@runCatching false
                val changed = ComicReadingLogic.isNewChapterObserved(
                    fresh.latestChapterId, fresh.latestChapterTitle, top,
                )
                dao.insertFavorite(
                    fresh.copy(
                        latestChapterId = top?.chapter?.id ?: fresh.latestChapterId,
                        latestChapterTitle = top?.chapter?.title ?: fresh.latestChapterTitle,
                        latestChapterUpdateAt = if (changed) {
                            System.currentTimeMillis()
                        } else {
                            fresh.latestChapterUpdateAt
                        },
                        lastCheckedAt = System.currentTimeMillis(),
                        sourceAlive = true,
                    )
                )
                true
            }.getOrDefault(false)
        }
    }
}

private fun serialStatusOf(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "completed", "finished", "已完结", "完结", "已完結", "完結" -> SerialStatus.COMPLETED.code
    "ongoing", "连载中", "連載中", "连载", "連載" -> SerialStatus.ONGOING.code
    "hiatus", "paused", "暂停", "暫停", "休刊" -> SerialStatus.HIATUS.code
    else -> SerialStatus.UNKNOWN.code
}
