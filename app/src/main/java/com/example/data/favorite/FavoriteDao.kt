package com.example.data.favorite

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    /* ─────────────── 收藏 ─────────────── */

    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    fun allFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    suspend fun allFavoritesSync(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId AND comicId = :comicId LIMIT 1")
    suspend fun favorite(sourceId: String, comicId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId AND comicId = :comicId LIMIT 1")
    fun favoriteFlow(sourceId: String, comicId: String): Flow<FavoriteEntity?>

    /** 收藏的 id 集合（书架卡片右下角小心形用；一次查询避免 N 次单查） */
    @Query("SELECT sourceId || '::' || comicId AS k FROM favorites")
    fun favoriteKeys(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(entity: FavoriteEntity)

    @Update
    suspend fun updateFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND comicId = :comicId")
    suspend fun deleteFavorite(sourceId: String, comicId: String)

    /** 批量加入收藏（多选操作栏「喜欢」） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(entities: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE sourceId || '::' || comicId IN (:keys)")
    suspend fun deleteFavoritesByKeys(keys: List<String>)

    /** 批量移动到分类（多选拖拽「放入 XXX」） */
    @Query("UPDATE favorites SET categoryName = :category WHERE sourceId || '::' || comicId IN (:keys)")
    suspend fun moveFavoritesToCategory(keys: List<String>, category: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE categoryName = :category")
    suspend fun countInCategory(category: String): Int

    /* ───────────── 收藏分类（独立于书架 categories） ───────────── */

    @Query("SELECT * FROM favorite_categories ORDER BY sortOrder ASC, createdAt ASC")
    fun favoriteCategories(): Flow<List<FavoriteCategoryEntity>>

    @Query("SELECT * FROM favorite_categories ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun favoriteCategoriesSync(): List<FavoriteCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavoriteCategory(entity: FavoriteCategoryEntity)

    @Query("DELETE FROM favorite_categories WHERE name = :name")
    suspend fun deleteFavoriteCategory(name: String)

    @Query("UPDATE favorite_categories SET name = :newName WHERE name = :oldName")
    suspend fun renameFavoriteCategory(oldName: String, newName: String)

    @Query("UPDATE favorites SET categoryName = :newName WHERE categoryName = :oldName")
    suspend fun retagFavorites(oldName: String, newName: String)

    /** 重命名分类：分类表与收藏记录一起改（否则收藏会指向一个不存在的分类）。 */
    @Transaction
    suspend fun renameFavoriteCategoryCascade(oldName: String, newName: String) {
        renameFavoriteCategory(oldName, newName)
        retagFavorites(oldName, newName)
    }

    /** 删除分类：其中的收藏退回默认分类，绝不连带删除收藏本身。 */
    @Transaction
    suspend fun deleteFavoriteCategoryAndRetag(name: String, fallback: String) {
        retagFavorites(name, fallback)
        deleteFavoriteCategory(name)
    }

    /* ─────────────── 漫画级进度 ─────────────── */

    @Query("SELECT * FROM comic_progress WHERE sourceId = :sourceId AND comicId = :comicId LIMIT 1")
    fun progressFlow(sourceId: String, comicId: String): Flow<ComicProgressEntity?>

    @Query("SELECT * FROM comic_progress WHERE sourceId = :sourceId AND comicId = :comicId LIMIT 1")
    suspend fun progress(sourceId: String, comicId: String): ComicProgressEntity?

    @Query("SELECT * FROM comic_progress ORDER BY lastReadAt DESC")
    fun allProgress(): Flow<List<ComicProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entity: ComicProgressEntity)

    @Query("DELETE FROM comic_progress WHERE sourceId = :sourceId AND comicId = :comicId")
    suspend fun deleteProgress(sourceId: String, comicId: String)

    /* ─────────────── 章节级状态 ─────────────── */

    @Query("SELECT * FROM comic_chapter_read WHERE sourceId = :sourceId AND comicId = :comicId")
    fun chapterStates(sourceId: String, comicId: String): Flow<List<ChapterReadEntity>>

    @Query("SELECT * FROM comic_chapter_read WHERE sourceId = :sourceId AND comicId = :comicId")
    suspend fun chapterStatesSync(sourceId: String, comicId: String): List<ChapterReadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapterStates(entities: List<ChapterReadEntity>)

    @Query(
        "UPDATE comic_chapter_read SET status = :status, updatedAt = :updatedAt " +
            "WHERE sourceId = :sourceId AND comicId = :comicId AND chapterId = :chapterId"
    )
    suspend fun setChapterStatus(
        sourceId: String,
        comicId: String,
        chapterId: String,
        status: Int,
        updatedAt: Long = System.currentTimeMillis(),
    )

    /** 「将以上全部标记为已读」：按阅读序号批量置位（缺章不影响） */
    @Query(
        "UPDATE comic_chapter_read SET status = 2, updatedAt = :updatedAt " +
            "WHERE sourceId = :sourceId AND comicId = :comicId AND chapterIndex <= :maxIndex"
    )
    suspend fun markChaptersReadUpTo(
        sourceId: String,
        comicId: String,
        maxIndex: Int,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM comic_chapter_read WHERE sourceId = :sourceId AND comicId = :comicId")
    suspend fun clearChapterStates(sourceId: String, comicId: String)

    /** 换源迁移：把某个漫画的收藏 + 进度整体搬到新的 (sourceId, comicId)。 */
    @Transaction
    suspend fun migrateKey(
        fromSourceId: String,
        fromComicId: String,
        toSourceId: String,
        toComicId: String,
    ) {
        val fav = favorite(fromSourceId, fromComicId)
        if (fav != null) {
            deleteFavorite(fromSourceId, fromComicId)
            insertFavorite(fav.copy(sourceId = toSourceId, comicId = toComicId))
        }
        val prog = progress(fromSourceId, fromComicId)
        if (prog != null) {
            deleteProgress(fromSourceId, fromComicId)
            upsertProgress(prog.copy(sourceId = toSourceId, comicId = toComicId))
        }
        val states = chapterStatesSync(fromSourceId, fromComicId)
        if (states.isNotEmpty()) {
            clearChapterStates(fromSourceId, fromComicId)
            upsertChapterStates(states.map { it.copy(sourceId = toSourceId, comicId = toComicId) })
        }
    }
    @Query("DELETE FROM comic_chapter_read WHERE sourceId=:source AND comicId=:comic AND chapterId IN (:ids)")
    suspend fun deleteChapterIds(source:String,comic:String,ids:List<String>)

    @Transaction
    suspend fun reconcileChapterIds(source:String,comic:String,states:List<ChapterReadEntity>,progress:ComicProgressEntity?,oldIds:List<String>) {
        if(oldIds.isNotEmpty()) deleteChapterIds(source,comic,oldIds)
        if(states.isNotEmpty()) upsertChapterStates(states)
        if(progress!=null) upsertProgress(progress)
    }

    @Transaction
    suspend fun migrateResolved(from:ComicKey,to:ComicKey,states:List<ChapterReadEntity>,progress:ComicProgressEntity?,favorite:FavoriteEntity?,clearOld:Boolean) {
        if(states.isNotEmpty()) upsertChapterStates(states)
        if(progress!=null) upsertProgress(progress)
        if(favorite!=null) insertFavorite(favorite)
        if(clearOld) {
            clearChapterStates(from.sourceId,from.comicId)
            deleteProgress(from.sourceId,from.comicId)
            deleteFavorite(from.sourceId,from.comicId)
        }
    }

    /** Replace the favorite atomically. Keep old reading records as a fallback for missing chapters. */
    @Transaction
    suspend fun replaceFavoriteMapped(
        from: ComicKey,
        replacement: FavoriteEntity,
        mapping: Map<String, String>,
        chapterOrders: Map<String, Int>,
        latestId: String?,
    ): FavoriteMigrationReport {
        val oldFavorite = favorite(from.sourceId, from.comicId)
            ?: throw IllegalStateException("旧收藏已移除，请重新选择")
        val targetStates = chapterStatesSync(replacement.sourceId, replacement.comicId).associateBy { it.chapterId }
        val oldStates = chapterStatesSync(from.sourceId, from.comicId)
            .filter { it.status != ChapterReadState.UNREAD.code || it.bookmarked }
        val mapped = oldStates.mapNotNull { state -> mapping[state.chapterId]?.let { chapterId ->
            val existing = targetStates[chapterId]
            val transferred = state.copy(
                sourceId = replacement.sourceId, comicId = replacement.comicId,
                chapterId = chapterId, chapterIndex = chapterOrders[chapterId] ?: -1,
                // Different editions have different page counts. Resume at this episode's first page.
                pageIndex = 0, pageCount = 0,
                bookmarked = state.bookmarked || existing?.bookmarked == true,
            )
            if (existing != null && (existing.status > state.status ||
                    (existing.status == state.status && existing.updatedAt >= state.updatedAt))) {
                existing.copy(bookmarked = transferred.bookmarked)
            } else transferred
        } }
        val oldProgress = progress(from.sourceId, from.comicId)
        val resumeId = oldProgress?.lastChapterId?.let(mapping::get)
        val targetProgress = progress(replacement.sourceId, replacement.comicId)
        if (resumeId != null && oldProgress != null &&
            (targetProgress?.lastChapterId == null || targetProgress.lastReadAt < oldProgress.lastReadAt)) {
            upsertProgress(oldProgress.copy(
                sourceId = replacement.sourceId, comicId = replacement.comicId,
                lastChapterId = resumeId, lastChapterIndex = chapterOrders[resumeId] ?: -1,
                lastPageIndex = 0, lastPageCount = 0,
                seenTopChapterId = latestId, seenChapterCount = chapterOrders.size,
            ))
        }
        if (mapped.isNotEmpty()) upsertChapterStates(mapped)
        val targetFavorite = favorite(replacement.sourceId, replacement.comicId)
        insertFavorite(replacement.copy(
            categoryName = targetFavorite?.categoryName ?: oldFavorite.categoryName,
            favoritedAt = targetFavorite?.favoritedAt ?: oldFavorite.favoritedAt,
            sortOrder = targetFavorite?.sortOrder ?: oldFavorite.sortOrder,
        ))
        deleteFavorite(from.sourceId, from.comicId)
        return FavoriteMigrationReport(mapped.size, oldStates.size - mapped.size,
            oldProgress?.lastChapterId == null || resumeId != null)
    }

}
