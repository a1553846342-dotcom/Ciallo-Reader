package com.example.library

import com.example.source.NovelInfo
import org.jsoup.Jsoup

/** Display metadata only; never used to select a download URL or authenticate. */
internal fun zLibraryNovelMetadata(html: String): NovelInfo {
    val doc = Jsoup.parse(html)
    val synopsis = doc.selectFirst("#bookDescriptionBox, .book-description, [itemprop=description], #description")
        ?.let { element -> element.clone().apply { select("script, style, button").remove() }.wholeText().trim() }
        ?.takeIf(String::isNotBlank)
    fun property(key: String): String? = doc.selectFirst(".property_$key .property_value, .book-property__$key .property_value")
        ?.text()?.takeIf(String::isNotBlank)
        ?: doc.selectFirst(".property_$key, .book-property__$key")?.text()?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
    val facts = listOfNotNull(property("year")?.let { "出版年份：$it" },
        property("pages")?.let { "页数：$it" }, property("isbn")?.let { "ISBN：$it" },
        property("isbn13")?.let { "ISBN-13：$it" })
    return NovelInfo(synopsis = synopsis, publisher = property("publisher"),
        tags = doc.select(".property_categories .property_value a").map { it.text() }.filter(String::isNotBlank),
        notice = facts.takeIf { it.isNotEmpty() }?.joinToString(" · "))
}
