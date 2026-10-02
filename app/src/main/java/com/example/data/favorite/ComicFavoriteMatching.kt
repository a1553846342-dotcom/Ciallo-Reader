package com.example.data.favorite

import com.example.source.SearchBook
import com.example.source.anilist.TitleNormalizer
import com.example.source.isKnownComicAuthor

data class DuplicateComicCandidate(val favorite: FavoriteEntity, val reason: String, val score: Double)

data class FavoriteAddRequest(
    val book: SearchBook,
    val category: String,
    val chapters: List<com.example.source.ComicChapter>,
    val favorites: List<FavoriteEntity>,
    val candidates: List<DuplicateComicCandidate>,
    val busy: Boolean = false,
    val error: String? = null,
)

object ComicFavoriteMatching {
    fun candidates(book: SearchBook, favorites: List<FavoriteEntity>, aliases: List<String>): List<DuplicateComicCandidate> {
        val titles = (listOf(book.title) + book.comicInfo?.alternateTitles.orEmpty() + aliases)
            .map(TitleNormalizer::compact).filter { usableTitle(it) }.toSet()
        val author = book.author.takeIf { it.isKnownComicAuthor() }?.let(TitleNormalizer::compact)
        return favorites.filter { it.sourceId != book.sourceId }.mapNotNull { favorite ->
            val title = TitleNormalizer.compact(favorite.title)
            if (!usableTitle(title)) return@mapNotNull null
            val sameAuthor = author != null && favorite.author.isKnownComicAuthor() &&
                TitleNormalizer.compact(favorite.author) == author
            val similarity = titles.maxOfOrNull { dice(it, title) } ?: 0.0
            when {
                title in titles -> DuplicateComicCandidate(favorite, "书名或已知别名相同", 1.0)
                title.length >= 4 && similarity >= 0.82 -> DuplicateComicCandidate(favorite,
                    if (sameAuthor) "书名相似，作者相同" else "书名相似，请核对作品", similarity)
                else -> null
            }
        }.sortedByDescending { it.score }
    }

    private fun usableTitle(title: String) = title.isNotBlank() && title !in setOf("未知书名", "未知", "unknown", "untitled")

    private fun dice(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 4 || b.length < 4) return 0.0
        val left = a.windowed(2).groupingBy { it }.eachCount()
        val right = b.windowed(2).groupingBy { it }.eachCount()
        val common = left.entries.sumOf { (pair, count) -> minOf(count, right[pair] ?: 0) }
        return 2.0 * common / (a.length + b.length - 2)
    }
}
