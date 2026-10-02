package com.example.source.impl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MangaDexImageHeadersTest {
    private val source = MangaDexSource()

    @Test fun officialAtHomeImagesDoNotInheritMirrorReferer() = runBlocking {
        val urls = listOf("https://uploads.mangadex.org/data/hash/page.jpg", "https://node.example.org/data/hash/page.jpg")
        val headers = source.getChapterImageHeaders("mdapich:manga:chapter:1", urls)
        urls.forEach { assertFalse(headers.getValue(it).containsKey("Referer")) }
    }

    @Test fun mirrorFallbackFromOfficialChapterKeepsMirrorReferer() = runBlocking {
        val url = "https://t.imoutcl.sbs/chapter/hash/page.jpg"
        assertEquals("https://mangadex.live/", source.getChapterImageHeaders("mdapich:manga:chapter:1", listOf(url))
            .getValue(url)["Referer"])
    }

    @Test fun mirrorChapterProvidesHeadersForReaderAndPrefetch() = runBlocking {
        val url = "https://t.imoutcl.sbs/chapter/hash/page.jpg"
        val headers = source.getChapterImageHeaders("read/title/en/chapter", listOf(url)).getValue(url)
        assertEquals("https://mangadex.live/", headers["Referer"])
        assertTrue(headers["User-Agent"].orEmpty().contains("Mobile"))
    }
}
