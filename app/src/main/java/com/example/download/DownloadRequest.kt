package com.example.download

data class DownloadRequest(
    val bookId: String,
    val title: String,
    val author: String,
    val sourceId: String,
    val downloadUrl: String,
    val format: String,
    val coverUrl: String? = null,
    val novelSnapshot: com.example.source.SearchBook? = null,
    val replaceExistingNovel: Boolean = false
)
