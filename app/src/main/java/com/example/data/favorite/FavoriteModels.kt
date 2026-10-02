package com.example.data.favorite

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「我喜欢的」= 在线收藏（追漫）：不下载、不占空间，用 (sourceId, comicId) 唯一标识。
 * 与下载（Download）和阅读进度（[ComicProgressEntity]）三张数据相互独立：
 * 同一漫画可「仅下载 / 仅喜欢 / 两者都有」，取消喜欢不影响下载与进度。
 */

/** 收藏默认分类（与书架分类体系一致：单分类，分类行复用 categories 表）。 */
const val FAV_DEFAULT_CATEGORY = "默认"

/** 章节阅读状态：未读 / 阅读中 / 已读。存库用 [ChapterReadState.code]。 */
enum class ChapterReadState(val code: Int) {
    UNREAD(0),
    READING(1),
    READ(2);

    companion object {
        fun of(code: Int): ChapterReadState = entries.firstOrNull { it.code == code } ?: UNREAD
    }
}

/** 连载状态快照（存字符串，未知来源也能安全落地）。 */
enum class SerialStatus(val code: String) {
    UNKNOWN("unknown"),
    ONGOING("ongoing"),
    COMPLETED("completed"),
    HIATUS("hiatus");

    companion object {
        fun of(code: String?): SerialStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Entity(
    tableName = "favorites",
    primaryKeys = ["sourceId", "comicId"],
    indices = [
        Index(value = ["categoryName"]),
        Index(value = ["favoritedAt"]),
        Index(value = ["lastCheckedAt"]),
    ]
)
data class FavoriteEntity(
    /** 书源 id（无来源的本地书不能被喜欢） */
    val sourceId: String,
    /** 源内的漫画 id */
    val comicId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    /** 封面缩略图的本地缓存路径（离线/弱网时仍能显示卡片） */
    val localThumbPath: String? = null,
    val serialStatus: String = SerialStatus.UNKNOWN.code,
    /* ── 最新章节快照（stale-while-revalidate 的 stale 来源） ── */
    val latestChapterId: String? = null,
    val latestChapterTitle: String? = null,
    val latestChapterUpdateAt: Long = 0L,
    /** 上次真正联网检查更新的时刻（用于 >30 分钟才检查的限流） */
    val lastCheckedAt: Long = 0L,
    /** 上次检查时源是否可用（false → 卡片显示灰色警示，但不删除、可看缓存） */
    val sourceAlive: Boolean = true,
    val categoryName: String = FAV_DEFAULT_CATEGORY,
    val favoritedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
)

/** 漫画级阅读进度：与是否收藏、是否下载无关。 */
@Entity(
    tableName = "comic_progress",
    primaryKeys = ["sourceId", "comicId"],
    indices = [Index(value = ["lastReadAt"])]
)
data class ComicProgressEntity(
    val sourceId: String,
    val comicId: String,
    /** 上次读到的章节 id */
    val lastChapterId: String? = null,
    /** 上次读到的章节在「阅读顺序」中的序号（冗余，便于下一话计算） */
    val lastChapterIndex: Int = -1,
    /** 上次读到的页（0 起） */
    val lastPageIndex: Int = 0,
    /** 该章节总页数（用于判定已读与显示百分比） */
    val lastPageCount: Int = 0,
    val lastReadAt: Long = 0L,
    /** 上次进入章节列表时的「最新章节 id」——此后新增的章节显示「新」红点 */
    val seenTopChapterId: String? = null,
    /** 上次进入时的章节总数（源结构变化时兜底判新） */
    val seenChapterCount: Int = 0,
) {
    /** 该章节是否读到「已读」判定线（最后一页 或 进度 ≥90%）。纯函数，可单测。 */
    fun isChapterFinished(): Boolean {
        if (lastPageCount <= 0) return false
        if (lastPageIndex >= lastPageCount - 1) return true
        return (lastPageIndex + 1).toFloat() / lastPageCount.toFloat() >= 0.9f
    }
}

/**
 * 「我喜欢的」的分类 —— 与书架的 categories 表**完全独立**。
 *
 * 书架（本地下载制）和我喜欢的（在线收藏制）是两套系统，分类也各过各的：
 * 在书架里改/删一个分类，不会牵动我喜欢的的任何分组。
 */
@Entity(tableName = "favorite_categories")
data class FavoriteCategoryEntity(
    @PrimaryKey val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 章节级阅读状态。 */
@Entity(
    tableName = "comic_chapter_read",
    primaryKeys = ["sourceId", "comicId", "chapterId"],
    indices = [Index(value = ["sourceId", "comicId"])]
)
data class ChapterReadEntity(
    val sourceId: String,
    val comicId: String,
    val chapterId: String,
    val status: Int = ChapterReadState.UNREAD.code,
    /** 读到第几页（0 起） */
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    /** 章节在阅读顺序中的序号（源正序/倒序归一化后的值） */
    val chapterIndex: Int = -1,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 读者手动标注的书签（详情页左滑/右滑该话卡片切换） */
    val bookmarked: Boolean = false,
) {
    val state: ChapterReadState get() = ChapterReadState.of(status)
}

/** UI 消费的聚合模型：收藏 + 进度 + 已下载章节数。 */
data class FavoriteItem(
    val favorite: FavoriteEntity,
    val progress: ComicProgressEntity? = null,
    /** 已下载到本地书架的章节数（0 表示纯在线收藏） */
    val downloadedChapters: Int = 0,
) {
    val key: String get() = favoriteKey(favorite.sourceId, favorite.comicId)
    /** 是否在线收藏 + 本地下载都有 */
    val alsoDownloaded: Boolean get() = downloadedChapters > 0
}

/** 三张表统一使用的业务主键。 */
data class ComicKey(val sourceId: String, val comicId: String) {
    val raw: String get() = favoriteKey(sourceId, comicId)
    /** 无来源信息的书（手动导入的本地文件）不能被喜欢 */
    val valid: Boolean get() = sourceId.isNotBlank() && comicId.isNotBlank()
}

fun favoriteKey(sourceId: String, comicId: String): String = "$sourceId::$comicId"
