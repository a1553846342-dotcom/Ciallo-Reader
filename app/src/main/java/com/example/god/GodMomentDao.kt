package com.example.god

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 神回 DAO。
 *
 * 全部查询返回 Flow —— 排行榜卡片与详情/编辑窗口监听同一个流，
 * 增删改后两侧自动刷新，不需要手动 invalidate。
 */
@Dao
interface GodMomentDao {

    /** 全量（排行榜消费） */
    @Query("SELECT * FROM god_moments ORDER BY rating DESC, createdAt DESC")
    fun observeAll(): Flow<List<GodMomentEntity>>

    @Query("SELECT * FROM god_moments ORDER BY rating DESC, createdAt DESC")
    suspend fun allSync(): List<GodMomentEntity>

    /** 一本书的全部神回（书籍详情页消费） */
    @Query("SELECT * FROM god_moments WHERE bookId = :bookId ORDER BY chapterNumber ASC")
    fun observeForBook(bookId: String): Flow<List<GodMomentEntity>>

    @Query("SELECT * FROM god_moments WHERE bookId = :bookId ORDER BY chapterNumber ASC")
    suspend fun forBookSync(bookId: String): List<GodMomentEntity>

    /** 单话（唯一索引保证至多一条） */
    @Query("SELECT * FROM god_moments WHERE bookId = :bookId AND chapterId = :chapterId LIMIT 1")
    fun observeChapter(bookId: String, chapterId: String): Flow<GodMomentEntity?>

    @Query("SELECT * FROM god_moments WHERE bookId = :bookId AND chapterId = :chapterId LIMIT 1")
    suspend fun chapterSync(bookId: String, chapterId: String): GodMomentEntity?

    @Query("SELECT * FROM god_moments WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): GodMomentEntity?

    @Query("SELECT COUNT(*) FROM god_moments")
    suspend fun count(): Int

    /**
     * 插入或更新。冲突策略 REPLACE 会让自增 id 变化，因此先按
     * (bookId, chapterId) 取旧 id 复用 —— 保证 UI 的 item key 稳定，
     * 排行榜的位移过渡动画不会因为 id 跳变而错位。
     */
    @Upsert
    suspend fun upsert(entity: GodMomentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GodMomentEntity): Long

    @Query("DELETE FROM god_moments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM god_moments WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM god_moments WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun deleteForChapter(bookId: String, chapterId: String)

    @Query("DELETE FROM god_moments")
    suspend fun clear()
}
