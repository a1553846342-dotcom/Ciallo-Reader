package com.example.data.favorite

import com.example.source.ComicInfo
import com.example.source.SearchBook
import com.example.source.withComicDetail
import org.junit.Assert.*
import org.junit.Test

class ComicFavoriteMatchingTest {
    private fun favorite(title: String, author: String = "", source: String = "old") =
        FavoriteEntity(sourceId = source, comicId = title, title = title, author = author)
    private fun book(title: String, author: String = "") = SearchBook("new-id", "new", title, author)

    @Test fun `known alternate titles match another language`() {
        val new = book("Bloom Into You").copy(comicInfo = ComicInfo(alternateTitles = listOf("终将成为你")))
        assertEquals(1, ComicFavoriteMatching.candidates(new, listOf(favorite("终将成为你")), emptyList()).size)
    }
    @Test fun `local aliases and simplified traditional spelling are candidates`() {
        assertEquals(1, ComicFavoriteMatching.candidates(book("Mushoku Tensei"), listOf(favorite("無職転生")), listOf("无职转生")).size)
    }
    @Test fun `same author does not imply same work and same source is excluded`() {
        assertTrue(ComicFavoriteMatching.candidates(book("作品甲", "作者 A"), listOf(favorite("作品乙", "作者 A")), emptyList()).isEmpty())
        assertTrue(ComicFavoriteMatching.candidates(book("作品甲"), listOf(favorite("作品甲", source = "new")), emptyList()).isEmpty())
    }
    @Test fun `partial details preserve identity metadata and chosen translation`() {
        val selected = book("中文标题", "真实作者").copy(description = "已有简介")
        val detail = SearchBook("different-id", "different-source", "different-id", "未知作者")
        val merged = selected.withComicDetail(detail)
        assertEquals(selected, merged)
        assertEquals("中文标题", selected.withComicDetail(detail.copy(title = "English title",
            comicInfo = ComicInfo(alternateTitles = listOf("中文标题")))).title)
    }
}
