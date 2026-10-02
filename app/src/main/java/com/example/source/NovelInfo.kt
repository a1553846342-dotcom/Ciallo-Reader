package com.example.source

import androidx.compose.runtime.Immutable

@Immutable
data class NovelInfo(
    val synopsis: String? = null,
    val originalTitle: String? = null,
    val status: String? = null,
    val category: String? = null,
    val latestChapter: String? = null,
    val updatedAt: String? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
    val wordCount: String? = null,
    val publisher: String? = null,
    val tags: List<String> = emptyList(),
    val translation: String? = null,
    val notice: String? = null,
) {
    val revision: String get() = listOf(updatedAt.orEmpty(), latestChapter.orEmpty(), chapterCount?.toString().orEmpty(), volumeCount?.toString().orEmpty()).joinToString("|")
    val hasRevision: Boolean get() = updatedAt?.isNotBlank() == true || latestChapter?.isNotBlank() == true || chapterCount != null || volumeCount != null
}

/** Only these public whole-book sources opt into novel UI and update handling. */
object WholeBookNovelSources {
    val ids = setOf("auto_novel", "wenku8_library", "ixdzs8")
    fun contains(id: String?) = id in ids
}

interface UpdatableNovelSource : BookSource {
    suspend fun refreshNovelDetail(bookId: String): SourceResult<SearchBook>
}
