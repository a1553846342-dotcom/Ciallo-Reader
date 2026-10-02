package com.example.god

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import com.example.data.ContentMutationGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 神回仓库。
 *
 * 职责：
 * - 对上暴露 Flow / suspend API（DAO 直接透传 + 少量业务封装）；
 * - **级联删除**：删书 / 删章时连带删除神回并清理 [GodCoverEngine] 产出的封面缓存文件；
 * - 备份导出 / 导入（只搬元数据，封面是本地缓存可重建，避免备份体积爆炸）。
 */
class GodMomentRepository(
    private val context: Context,
    private val dao: GodMomentDao,
) {

    /**
     * 统一兜底：神回是**附加功能**，任何一步出问题（数据库未就绪、磁盘异常、
     * 迁移失败……）都只允许它自己不可用，**绝不能把整个 App 带崩**。
     * 所有对外 Flow 都经过这里。
     */
    private fun <T> Flow<T>.guarded(default: T, tag: String): Flow<T> =
        this.catch { e ->
            android.util.Log.w("GodMoment", "god flow failed, degraded: $tag", e)
            emit(default)
        }

    /** 全量神回（排行榜 + 统计页共用同一个流） */
    fun observeAll(): Flow<List<GodMomentEntity>> =
        dao.observeAll().guarded(emptyList(), "observeAll")

    fun observeForBook(bookId: String): Flow<List<GodMomentEntity>> =
        dao.observeForBook(bookId).guarded(emptyList(), "observeForBook")

    fun observeChapter(bookId: String, chapterId: String): Flow<GodMomentEntity?> =
        dao.observeChapter(bookId, chapterId).guarded(null, "observeChapter")

    /** 书籍详情页用：chapterId → 实体（章节卡片判断是否神回态） */
    fun observeChapterMap(bookId: String): Flow<Map<String, GodMomentEntity>> =
        dao.observeForBook(bookId)
            .map { list -> list.associateBy { it.chapterId } }
            .guarded(emptyMap(), "observeChapterMap")

    suspend fun chapter(bookId: String, chapterId: String): GodMomentEntity? =
        dao.chapterSync(bookId, chapterId)

    suspend fun count(): Int = dao.count()

    /**
     * 保存（新增 or 更新）。
     *
     * 复用旧 id（存在时）→ UI 列表 key 稳定；同时把 [updatedAt] 推到当前时间，
     * 封面缓存 key 含 updatedAt，编辑后能强制刷新图片加载库缓存。
     */
    suspend fun save(entity: GodMomentEntity): Long = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.withLock {
            val existing=dao.chapterSync(entity.bookId,entity.chapterId)
            val merged=entity.copy(id=existing?.id ?: entity.id,createdAt=existing?.createdAt ?: entity.createdAt,
                updatedAt=System.currentTimeMillis())
            val inserted=dao.upsert(merged)
            withContext(NonCancellable) {
                existing?.coverPath?.takeIf { it!=merged.coverPath }?.let { GodCoverEngine.deleteQuietly(it) }
            }
            if(merged.id!=0L) merged.id else inserted
        }
    }
    /** Database commits before any associated file is removed. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.withLock {
            val entity=dao.byId(id) ?: return@withLock
            dao.deleteById(id)
            withContext(NonCancellable) { GodCoverEngine.deleteQuietly(entity.coverPath) }
        }
    }
    suspend fun deleteForBook(bookId: String) = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.withLock {
            val entities=dao.forBookSync(bookId)
            dao.deleteForBook(bookId)
            withContext(NonCancellable) { entities.forEach { GodCoverEngine.deleteQuietly(it.coverPath) } }
        }
    }
    suspend fun deleteForChapter(bookId: String, chapterId: String) = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.withLock {
            val entity=dao.chapterSync(bookId,chapterId) ?: return@withLock
            dao.deleteForChapter(bookId,chapterId)
            withContext(NonCancellable) { GodCoverEngine.deleteQuietly(entity.coverPath) }
        }
    }

    /* ══════════════ 备份 ══════════════ */

    /** 导出神回元数据为 JSON 数组（不含封面位图，封面可重建）。 */
    suspend fun exportJson(): JSONArray = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        dao.allSync().forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("contentType", e.contentType)
                    .put("bookId", e.bookId)
                    .put("chapterId", e.chapterId)
                    .put("bookTitle", e.bookTitle)
                    .put("chapterTitle", e.chapterTitle)
                    .put("chapterNumber", e.chapterNumber)
                    .put("title", e.title)
                    .put("titleIsCustom", e.titleIsCustom)
                    .put("rating", e.rating.toDouble())
                    .put("note", e.note)
                    .put("coverSource", e.coverSource)
                    .put("cropParams", e.cropParams)
                    .put("createdAt", e.createdAt)
                    .put("updatedAt", e.updatedAt),
            )
        }
        arr
    }

    /** 导入（同 (bookId, chapterId) 覆盖）。 */
    suspend fun importJson(arr: JSONArray?): Int = withContext(Dispatchers.IO) {
        if (arr == null) return@withContext 0
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = GodMomentEntity(
                id = 0,
                contentType = o.optString("contentType", GodContentType.COMIC.code),
                bookId = o.optString("bookId"),
                chapterId = o.optString("chapterId"),
                bookTitle = o.optString("bookTitle"),
                chapterTitle = o.optString("chapterTitle"),
                chapterNumber = o.optInt("chapterNumber", 0),
                title = o.optString("title"),
                titleIsCustom = o.optBoolean("titleIsCustom", false),
                rating = o.optDouble("rating", 5.0).toFloat().coerceIn(0.5f, 5f),
                note = o.optString("note"),
                coverPath = null,
                coverSource = o.optString("coverSource", CoverSource.ComicPage().toTag()),
                cropParams = o.optString("cropParams", CropParams.DEFAULT.toTag()),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
            if (e.bookId.isBlank() || e.chapterId.isBlank()) continue
            save(e)
            n++
        }
        n
    }
}
