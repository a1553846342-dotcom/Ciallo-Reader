package com.example.library

import org.junit.Assert.*
import org.junit.Test

class ZLibraryNovelMetadataTest {
    @Test fun actualPropertyLayoutProvidesPlainSynopsisAndBibliographicFacts() {
        val info = zLibraryNovelMetadata("""
            <div id="bookDescriptionBox"><p>小说简介。</p><script>tracking()</script><button>展开</button></div>
            <div class="property_publisher"><div class="property_value">人民文学出版社</div></div>
            <div class="property_year"><div class="property_value">2024</div></div>
            <div class="property_pages"><div class="property_value">320</div></div>
            <div class="property_isbn13"><div class="property_value">9780000000001</div></div>
            <div class="property_categories"><div class="property_value"><a>小说</a></div></div>
        """.trimIndent())
        assertEquals("小说简介。", info.synopsis)
        assertEquals("人民文学出版社", info.publisher)
        assertEquals(listOf("小说"), info.tags)
        assertTrue(info.notice!!.contains("出版年份：2024"))
        assertTrue(info.notice!!.contains("页数：320"))
        assertTrue(info.notice!!.contains("ISBN-13：9780000000001"))
        assertNull(zLibraryNovelMetadata("<html></html>").synopsis)
    }
}
