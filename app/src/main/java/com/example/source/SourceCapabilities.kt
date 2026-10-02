package com.example.source

data class SourceCapabilities(
    val supportSearch: Boolean = true,
    val supportDownload: Boolean = true,
    val searchRequiresLogin: Boolean = false,
    val downloadRequiresLogin: Boolean = false,
    val supportDebug: Boolean = false,
    val supportImport: Boolean = false,
    val supportComic: Boolean = false,
    /** 支持章节式在线文字阅读（Legado 网文源：htmlChapters + 文本正文规则） */
    val supportOnlineText: Boolean = false,
    val environmentOnly: Boolean = false,
    /** 提供小说电子书文件，可整本下载后进入本地文字阅读器。 */
    val supportEbook: Boolean = false
) {
    val requiresLogin: Boolean get() = downloadRequiresLogin
}

/**
 * 小说源（电子书/网文）：电子书下载源，或支持章节文字阅读的源。
 * 与漫画源（capabilities.supportComic）互斥，用于书源分类展示与聚合搜索分组。
 */
val BookSource.isNovelSource: Boolean
    get() = id == "zlibrary" || (!capabilities.supportComic &&
        (capabilities.supportEbook || capabilities.supportOnlineText))

val BookSource.isComicSource: Boolean
    get() = capabilities.supportComic && !isNovelSource
