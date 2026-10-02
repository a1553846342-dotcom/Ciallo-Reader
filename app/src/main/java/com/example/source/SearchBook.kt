package com.example.source

import androidx.compose.runtime.Immutable

@Immutable
data class SearchBook(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val cover: String? = null,
    val description: String? = null,
    val format: String = "epub",
    val language: String? = null,
    val comicId: String? = null,
    val size: Long? = null,
    val downloadUrl: String? = null,
    /** eapi（bipinkrish 方案）书对象里的数字 id，用于多格式查询，仅 eapi 兜底搜索时填充。 */
    val eapiId: String? = null,
    /** eapi 书对象里的短 hash，用于多格式查询，仅 eapi 兜底搜索时填充。 */
    val eapiHash: String? = null,
    val novelInfo: NovelInfo? = null,
    val comicInfo: ComicInfo? = null
)
