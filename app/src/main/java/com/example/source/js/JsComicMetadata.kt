package com.example.source.js

import com.example.source.ComicInfo
import com.example.source.SearchBook
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Venera sources use strings, arrays, or category-keyed tag objects. Never treat a subtitle as an author. */
internal object JsComicMetadata {
    private fun values(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim()).filter { it.isNotEmpty() && it != "null" }
        is JSONArray -> (0 until value.length()).flatMap { values(value.opt(it)) }
        is JSONObject -> values(value.opt("name"))
        else -> emptyList()
    }

    fun book(data: JSONObject, sourceId: String, bookId: String): SearchBook {
        val categories = data.optJSONObject("tags")
        fun category(vararg names: String): List<String> = categories?.let { tags ->
            tags.keys().asSequence().filter { key -> names.any { it.equals(key, ignoreCase = true) } }
                .flatMap { values(tags.opt(it)).asSequence() }.toList()
        }.orEmpty()
        val flatTags = values(data.opt("tags"))
        fun prefixed(vararg names: String) = flatTags.mapNotNull { tag ->
            val prefix = tag.substringBefore(':')
            tag.substringAfter(':', "").trim().takeIf { value ->
                value.isNotBlank() && names.any { it.equals(prefix, true) }
            }
        }
        val authors = (values(data.opt("author")) + values(data.opt("authors")) +
            category("author", "authors", "作者", "作家") + prefixed("author")).distinct()
        val artists = (values(data.opt("artist")) + prefixed("artist") +
            category("artist", "artists", "艺术家", "藝術家", "画师", "画家", "繪師", "绘师")).distinct()
        val tags = if (categories != null) categories.keys().asSequence().flatMap { key ->
            values(categories.opt(key)).map { "$key：$it" }.asSequence()
        }.toList() else flatTags
        val aliases = (values(data.opt("alternateTitles")) + values(data.opt("aliases")) +
            values(data.opt("otherNames")) + category("别名", "別名", "其他名称", "alternative", "alternate titles")).distinct()
        val status = values(data.opt("status")).firstOrNull()
            ?: category("状态", "狀態", "status").firstOrNull()
            ?: (data.opt("isCompleted") as? Boolean)?.let { if (it) "已完结" else "连载中" }
        return SearchBook(
            id = bookId,
            sourceId = sourceId,
            title = values(data.opt("title")).firstOrNull().orEmpty(),
            author = authors.joinToString("、"),
            cover = values(data.opt("cover")).firstOrNull(),
            description = values(data.opt("description")).firstOrNull()?.let { raw ->
                Jsoup.parse(raw.replace(Regex("(?i)<br\\s*/?>"), "\n")).wholeText().trim()
            },
            format = "漫画",
            language = values(data.opt("language")).firstOrNull() ?: category("language", "语言", "語言").firstOrNull(),
            comicInfo = ComicInfo(
                alternateTitles = aliases,
                artists = artists,
                tags = tags.distinct(),
                status = status,
                originalLanguage = values(data.opt("originalLanguage")).firstOrNull(),
                updatedAt = values(data.opt("updateTime")).firstOrNull() ?: values(data.opt("updatedAt")).firstOrNull()
                    ?: category("更新", "更新时间", "更新時間").firstOrNull(),
            ),
        )
    }
}
