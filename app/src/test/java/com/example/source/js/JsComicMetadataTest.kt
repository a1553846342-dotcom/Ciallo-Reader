package com.example.source.js

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JsComicMetadataTest {
    @Test fun `subtitle update counts are not authors`() {
        val data = JSONObject("""{"title":"作品","subtitle":"最新第12话","tags":["冒险"]}""")
        assertEquals("", JsComicMetadata.book(data, "source", "id").author)
    }
    @Test fun `named categories preserve true authors artists status and aliases`() {
        val data = JSONObject("""{"title":"作品","description":"第一段<br>第二段","tags":{
            "作者":["甲","乙"],"artist":["丙"],"状态":["已完结"],"别名":["Alias"],"语言":["中文"]}}""")
        val book = JsComicMetadata.book(data, "source", "id")
        assertEquals("甲、乙", book.author)
        assertEquals(listOf("丙"), book.comicInfo?.artists)
        assertEquals("已完结", book.comicInfo?.status)
        assertEquals(listOf("Alias"), book.comicInfo?.alternateTitles)
        assertEquals("中文", book.language)
        assertTrue(book.description.orEmpty().contains("第一段"))
        assertFalse(book.description.orEmpty().contains("<br>"))
    }
}
