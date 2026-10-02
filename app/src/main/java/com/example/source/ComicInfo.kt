package com.example.source

/** Source-provided metadata. Missing fields stay missing; chapter totals come from the loaded catalog. */
data class ComicInfo(
    val alternateTitles: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val originalLanguage: String? = null,
    val updatedAt: String? = null,
) {
    fun mergedWith(fresh: ComicInfo) = ComicInfo(
        alternateTitles = (alternateTitles + fresh.alternateTitles).distinct(),
        artists = fresh.artists.ifEmpty { artists },
        tags = fresh.tags.ifEmpty { tags },
        status = fresh.status ?: status,
        originalLanguage = fresh.originalLanguage ?: originalLanguage,
        updatedAt = fresh.updatedAt ?: updatedAt,
    )
}

fun String.isKnownComicAuthor(): Boolean = trim().lowercase() !in setOf(
    "", "null", "未知", "未知作者", "佚名", "unknown", "unknown author", "mangadex",
)

/** Keep the selected source/id and useful search metadata when a source only returns a partial detail. */
fun SearchBook.withComicDetail(detail: SearchBook): SearchBook = copy(
    title = if (title in detail.comicInfo?.alternateTitles.orEmpty()) title else
        detail.title.takeIf { it.isNotBlank() && it != detail.id && it != "未知书名" } ?: title,
    author = detail.author.takeIf { it.isKnownComicAuthor() } ?: author,
    cover = detail.cover?.takeIf { it.isNotBlank() } ?: cover,
    description = detail.description?.takeIf { it.isNotBlank() } ?: description,
    language = detail.language?.takeIf { it.isNotBlank() } ?: language,
    comicId = detail.comicId ?: comicId,
    comicInfo = when {
        detail.comicInfo == null -> comicInfo
        comicInfo == null -> detail.comicInfo
        else -> comicInfo.mergedWith(detail.comicInfo)
    },
)
