package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 不支持阅读的格式（PDF/MOBI 等）入库时使用的占位章节标题。 */
const val UNSUPPORTED_CHAPTER_TITLE = "暂不支持阅读"

/** 章节最大长度：超过则入库时拆分为多个小章节，保证打开阅读器不卡顿/不闪退。 */
const val MAX_CHAPTER_LENGTH = 30_000

enum class ContentType {
    NOVEL,
    COMIC
}

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String = "未知作者",
    val filePath: String,
    val coverUri: String? = null,
    val category: String = DEFAULT_CATEGORY,
    val currentChapterIndex: Int = 0,
    val scrollOffset: Int = 0,
    val isFinished: Boolean = false,
    val totalChapters: Int = 0,
    val contentType: String = "NOVEL",
    val addedTime: Long = System.currentTimeMillis(),
    val lastReadTime: Long = System.currentTimeMillis(),
    /**
     * 来源标识（在线下载入库的书才有）：与 [comicId] 一起构成「我喜欢的」的
     * 关联键 (sourceId, comicId)。手动导入的本地文件为空 —— 这类书不能被喜欢，
     * 但阅读进度照常记录。
     */
    val sourceId: String? = null,
    val comicId: String? = null,
) {
    val isComic: Boolean
        get() = contentType == "COMIC"

    @androidx.room.Ignore
    var isCoverValid: Boolean = false
}

@Entity(tableName = "chapters")
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val chapterOrder: Int,
    val title: String,
    val content: String,
    val startCharIndex: Long = 0,
    val endCharIndex: Long = 0
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId", "chapterIndex"], unique = true)]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val scrollOffset: Int,
    val title: String,
    val snippet: String,
    val createdTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "highlights")
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val selectedText: String,
    val note: String = "",
    val colorHex: String = "#7FD8C8",
    val createdTime: Long = System.currentTimeMillis()
)

/** 第七轮第 6.1 条：默认分类名——书架不再有聚合视图"全部"，所有书籍必须归属
 *  一个真实分类；默认分类不可删除。 */
const val DEFAULT_CATEGORY = "默认"

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    /** 第七轮第 6.3 条：密码保护标记（隐私模式开启时生效；长按分类或隐私窗口切换） */
    val isProtected: Boolean = false
)

@Entity(tableName = "reading_records")
data class ReadingRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int?,
    val bookTitle: String,
    val dateStr: String,
    val durationSeconds: Long
)

data class SearchResultItem(
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    /** 关键词是合并后逻辑章正文中的第几处出现（0 起）。
     *  不用字符偏移：ChapterMerger 的物理→逻辑偏移表基于空 content 的 metadata，不可信；
     *  "第 N 处出现"在渲染文本上直接数，天然免疫缩进/清洗造成的偏移漂移。 */
    val occurrence: Int = 0
)
