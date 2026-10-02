package com.example.god

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToInt

/**
 * 「神回」数据模型。
 *
 * 设计要点（扩展口）：
 * - [GodContentType] 区分漫画 / 小说，本期只实现漫画，字段与流程都为小说留好位置；
 * - [CoverSource] 用 sealed interface 抽象封面来源：漫画页 / 相册 / 小说摘录（预留）；
 * - bookId / chapterId 用字符串：在线漫画 bookId = "sourceId::comicId"（与
 *   com.example.data.favorite.favoriteKey 同构），本地漫画 bookId = 书籍主键字符串。
 *   这样漫画与未来的小说共用同一张表，不需要两套字段。
 */

/** 内容类型（本期只做 COMIC，NOVEL 为扩展口）。 */
enum class GodContentType(val code: String, val label: String) {
    COMIC("comic", "漫画"),
    NOVEL("novel", "小说"),
    ;

    companion object {
        fun of(code: String?): GodContentType =
            entries.firstOrNull { it.code == code } ?: COMIC
    }
}

/** 神回排行榜展示风格（设置项三选一）。 */
enum class GodRankingStyle(val code: String, val label: String, val desc: String) {
    PODIUM("podium", "领奖台", "前三名登台，其余卡牌流"),
    VINYL_SHELF("vinyl", "唱片架", "黑胶 Cover Flow 翻阅"),
    POLAROID_WALL("polaroid", "照片墙", "拍立得挂在麻绳上"),
    ;

    companion object {
        fun of(code: String?): GodRankingStyle =
            entries.firstOrNull { it.code == code } ?: PODIUM
    }
}

/** 排行榜排序方式。 */
enum class GodSort(val code: String, val label: String) {
    RATING("rating", "评分"),
    RECENT("recent", "最近添加"),
    BOOK("book", "书籍"),
}

/* ══════════════ 封面来源 ══════════════ */

/**
 * 封面来源。存库前用 [CoverSource.toTag] 序列化为一行字符串（避免引入
 * 序列化库），解析用 [parseCoverSource]。
 */
@Immutable
sealed interface CoverSource {

    /** 漫画页：页序号（0 起）+ 页 id（本地=文件签名，在线=url 指纹，可为空） */
    @Immutable
    data class ComicPage(val pageIndex: Int = 0, val pageId: String = "") : CoverSource

    /** 相册：Photo Picker 返回的 content:// Uri 字符串 */
    @Immutable
    data class Album(val uri: String = "") : CoverSource

    /** 小说摘录（预留，本期不落地） */
    @Immutable
    data class NovelExcerpt(val chapterId: String = "", val start: Int = 0, val end: Int = 0) :
        CoverSource

    companion object {
        /** 默认来源：漫画第 0 页 */
        fun comicDefault(pageIndex: Int = 0) = ComicPage(pageIndex, "")
    }
}

fun CoverSource.toTag(): String = when (this) {
    is CoverSource.ComicPage -> "page|$pageIndex|$pageId"
    is CoverSource.Album -> "album|$uri"
    is CoverSource.NovelExcerpt -> "novel|$chapterId|$start|$end"
}

fun parseCoverSource(tag: String?): CoverSource {
    if (tag.isNullOrBlank()) return CoverSource.ComicPage()
    val p = tag.split("|")
    return runCatching {
        when (p.getOrNull(0)) {
            "page" -> CoverSource.ComicPage(
                pageIndex = p.getOrNull(1)?.toIntOrNull() ?: 0,
                pageId = p.getOrNull(2) ?: "",
            )
            "album" -> CoverSource.Album(p.getOrNull(1) ?: "")
            "novel" -> CoverSource.NovelExcerpt(
                p.getOrNull(1) ?: "",
                p.getOrNull(2)?.toIntOrNull() ?: 0,
                p.getOrNull(3)?.toIntOrNull() ?: 0,
            )
            else -> CoverSource.ComicPage()
        }
    }.getOrDefault(CoverSource.ComicPage())
}

/* ══════════════ 裁剪参数 ══════════════ */

/**
 * 裁剪参数。
 *
 * - [cropL]/[cropT]/[cropR]/[cropB]：**（旋转后）原图归一化坐标** 0~1，与视口无关，
 *   因此换设备/换屏幕重进编辑也不会错位；
 * - [scale]/[offsetX]/[offsetY]/[rotationDeg]：编辑视图的变换快照，仅用于"再次编辑时
 *   还原到用户上次的手势状态"，不参与合成（合成只看 cropRect + rotation）。
 */
@Immutable
data class CropParams(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDeg: Float = 0f,
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 1f,
    val cropB: Float = 1f,
) {
    /** 裁剪框宽高比（自由比例；0 表示无效/整图） */
    val cropAspect: Float
        get() {
            val w = (cropR - cropL).coerceAtLeast(0.0001f)
            val h = (cropB - cropT).coerceAtLeast(0.0001f)
            return w / h
        }

    fun toTag(): String = listOf(
        scale, offsetX, offsetY, rotationDeg, cropL, cropT, cropR, cropB,
    ).joinToString(",") { round3(it) }

    companion object {
        val DEFAULT = CropParams()

        fun parse(tag: String?): CropParams {
            if (tag.isNullOrBlank()) return DEFAULT
            val p = tag.split(",")
            fun f(i: Int, d: Float) = p.getOrNull(i)?.toFloatOrNull() ?: d
            return CropParams(
                scale = f(0, 1f),
                offsetX = f(1, 0f),
                offsetY = f(2, 0f),
                rotationDeg = f(3, 0f),
                cropL = f(4, 0f).coerceIn(0f, 1f),
                cropT = f(5, 0f).coerceIn(0f, 1f),
                cropR = f(6, 1f).coerceIn(0f, 1f),
                cropB = f(7, 1f).coerceIn(0f, 1f),
            ).normalized()
        }

        /** 修正非法矩形（左右/上下颠倒或退化）。 */
        fun CropParams.normalized(): CropParams {
            val l = cropL.coerceIn(0f, 1f)
            val t = cropT.coerceIn(0f, 1f)
            val r = cropR.coerceIn(0f, 1f)
            val b = cropB.coerceIn(0f, 1f)
            return copy(
                cropL = minOf(l, r),
                cropR = maxOf(l, r).coerceAtLeast(minOf(l, r) + 0.02f).coerceAtMost(1f),
                cropT = minOf(t, b),
                cropB = maxOf(t, b).coerceAtLeast(minOf(t, b) + 0.02f).coerceAtMost(1f),
            )
        }
    }
}

private fun round3(v: Float): String =
    ((v * 1000f).roundToInt() / 1000f).toString()

/* ══════════════ Room 实体 ══════════════ */

/**
 * 神回实体。
 *
 * 唯一索引 (bookId, chapterId)：同一话只有一个神回（重复标记=更新）。
 * 外键级联：漫画主键是源作用域字符串，无法建 Room 外键，改为仓库级级联
 * （[GodMomentRepository.deleteForBook] / [deleteForChapter]，含封面文件清理）。
 */
@Entity(
    tableName = "god_moments",
    indices = [
        Index(value = ["bookId", "chapterId"], unique = true),
        Index(value = ["rating"]),
        Index(value = ["createdAt"]),
    ],
)
data class GodMomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentType: String = GodContentType.COMIC.code,
    /** 在线漫画 = "sourceId::comicId"；本地漫画 = 书籍主键字符串 */
    val bookId: String = "",
    /** 章节 id（在线=源章节 id；本地=章节主键字符串） */
    val chapterId: String = "",
    /** 书名快照（排行榜/备份用，删书后仍可展示来源） */
    val bookTitle: String = "",
    /** 章节名快照 */
    val chapterTitle: String = "",
    /** 话数（用于默认标题「书名 第X话」） */
    val chapterNumber: Int = 0,
    /** 神回名称；未自定义时跟随「书名 第X话」 */
    val title: String = "",
    val titleIsCustom: Boolean = false,
    /** 0.5 ~ 5.0，步长 0.5（不允许 0 分） */
    val rating: Float = 5f,
    /** 随笔 ≤ 500 字 */
    val note: String = "",
    /** 合成后的封面文件路径（filesDir/god_covers 下） */
    val coverPath: String? = null,
    /** [CoverSource.toTag] */
    val coverSource: String = CoverSource.ComicPage().toTag(),
    /** [CropParams.toTag] */
    val cropParams: String = CropParams.DEFAULT.toTag(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val source: CoverSource get() = parseCoverSource(coverSource)
    val crop: CropParams get() = CropParams.parse(cropParams)
    val contentTypeEnum: GodContentType get() = GodContentType.of(contentType)

    /** 未自定义名称时的兜底标题 */
    fun effectiveTitle(): String =
        if (titleIsCustom && title.isNotBlank()) title else defaultTitle()

    fun defaultTitle(): String =
        if (bookTitle.isBlank()) (chapterTitle.ifBlank { "第${chapterNumber}话" })
        else "$bookTitle 第${chapterNumber}话"
}

/** UI 消费模型：实体 + 排名（1 起）。 */
@Immutable
data class GodMomentItem(
    val entity: GodMomentEntity,
    val rank: Int = 0,
)

/**
 * 阅读器 → 神回窗口的上下文。
 *
 * 由调用方（MainActivity 的阅读路由）组装：阅读器本身不认识书源，
 * 只把「我是谁 + 我有哪些页」交给神回窗口。null = 该场景不启用神回。
 */
@Immutable
data class GodMomentContext(
    /** 在线漫画 = "sourceId::comicId"；本地漫画 = "local_<bookId>" */
    val bookId: String,
    /** 章节 id（本地漫画 = 书籍主键字符串，整本视为一话） */
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String = "",
    val chapterNumber: Int = 1,
    /** 在线页需要带 referer/自定义头，这里带上专用加载器 */
    val remoteLoader: coil.ImageLoader? = null,
) {
    val enabled: Boolean get() = bookId.isNotBlank() && chapterId.isNotBlank()
}

/** 建立 / 更新神回的请求（阅读器触发 & 详情页编辑共用）。 */
@Immutable
data class GodMomentRequest(
    val contentType: GodContentType = GodContentType.COMIC,
    val bookId: String,
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String = "",
    val chapterNumber: Int = 0,
    /** 本话全部页引用（页面选择器用） */
    val pages: List<GodPageRef> = emptyList(),
    /** 默认选中页（用户当前读到的页） */
    val initialPageIndex: Int = 0,
)

/** 页面引用（与阅读器的 ComicPageRef 解耦，避免 UI 层反向依赖）。 */
@Immutable
data class GodPageRef(
    val id: String,
    /** 本地文件路径 或 在线 URL */
    val source: String,
    val remote: Boolean,
    /** 在线页的请求头（在线源需要 referer/自定义头） */
    val headers: Map<String, String> = emptyMap(),
)

/** 神回封面请求与阅读器保持相同防盗链头；已有源专用 Referer 时优先保留。 */
fun godPageHeaders(headers: Map<String, String>, referer: String?): Map<String, String> =
    if (referer.isNullOrBlank() || headers.keys.any { it.equals("Referer", ignoreCase = true) }) {
        headers
    } else {
        headers + ("Referer" to referer)
    }

/**
 * 跨路由打开神回编辑窗口的信标（进程内一次性）。
 * 排行榜 / 设置页把要编辑的实体放进来 → navigate("god_editor") → 路由读走并清空。
 */
object GodMomentEditTarget {
    @Volatile
    var entity: GodMomentEntity? = null
}

/**
 * 排行榜 → 书籍详情页的跳转信标（进程内一次性）。
 * 从排行榜点条目时写入 chapterId，详情页进入后读取并滚动定位 + 金色高亮脉冲，
 * 读完即清空（避免二次进入误触发）。
 */
object GodMomentJumpState {
    @Volatile
    var chapterId: String? = null
}

/**
 * 排行榜 → 设置页「神回设置」分区的跳转信标（进程内一次性）。
 * 陈列方式按钮点击时置位；设置页进入后读取并动画滚动到神回分区，读完即清空。
 */
object GodStyleSettingsJump {
    @Volatile
    var pending: Boolean = false
}

/** 排序比较器：评分高→低，同分按添加时间倒序。 */
fun List<GodMomentEntity>.sortedForRanking(sort: GodSort): List<GodMomentEntity> =
    when (sort) {
        GodSort.RATING -> sortedWith(compareByDescending<GodMomentEntity> { it.rating }
            .thenByDescending { it.createdAt })
        GodSort.RECENT -> sortedByDescending { it.createdAt }
        GodSort.BOOK -> sortedWith(compareBy<GodMomentEntity> { it.bookTitle }
            .thenBy { it.chapterNumber }
            .thenByDescending { it.rating })
    }
