package com.example.data.favorite

import com.example.source.ComicChapter
import org.junit.Assert.*
import org.junit.Test

class ComicChapterMatchingTest {
    private fun chapter(id: String, title: String, volume: String? = null) = ComicChapter(id, title, volume)

    @Test fun `missing episodes and differing order never shift read state`() {
        val old = listOf(chapter("a", "第1话"), chapter("b", "第2话"), chapter("c", "第3话"))
        val new = listOf(chapter("z", "Chapter 3"), chapter("x", "Chapter 2"))
        val map = ComicChapterMatching.mapping(old, new)
        assertEquals(setOf("b", "c"), map.keys)
        assertEquals("x", map["b"]?.id)
        assertEquals("z", map["c"]?.id)
    }
    @Test fun `decimal episodes remain separate and Chinese numerals match`() {
        val map = ComicChapterMatching.mapping(listOf(chapter("a", "第12.5话"), chapter("b", "第十二话")),
            listOf(chapter("x", "Chapter 12"), chapter("y", "Chapter 12.5")))
        assertEquals("y", map["a"]?.id)
        assertEquals("x", map["b"]?.id)
    }
    @Test fun `ambiguous numbering volumes and split episodes are not guessed`() {
        assertTrue(ComicChapterMatching.mapping(listOf(chapter("a", "第1话", "第1卷"), chapter("b", "第1话", "第2卷")),
            listOf(chapter("x", "Chapter 1"))).isEmpty())
        assertTrue(ComicChapterMatching.mapping(listOf(chapter("a", "第12话（上）")), listOf(chapter("x", "Chapter 12"))).isEmpty())
        assertTrue(ComicChapterMatching.mapping(listOf(chapter("a", "第1卷 第2话")), listOf(chapter("x", "Vol 2 Chapter 2"))).isEmpty())
    }
    @Test fun `same ids do not prove identity across sources`() {
        assertTrue(ComicChapterMatching.mapping(listOf(chapter("shared", "第1话")), listOf(chapter("shared", "Chapter 9"))).isEmpty())
    }
    @Test fun `known volumes normalize and repeated episode numbers remain in their volume`() {
        val map = ComicChapterMatching.mapping(listOf(chapter("a", "第1话", "第1卷"), chapter("b", "第1话", "第2卷")),
            listOf(chapter("x", "Chapter 1", "Volume 2"), chapter("y", "Chapter 1", "Volume 1")))
        assertEquals("y", map["a"]?.id)
        assertEquals("x", map["b"]?.id)
    }
}
