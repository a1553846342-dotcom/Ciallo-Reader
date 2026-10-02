package com.example.source

interface BookSource {
    val id: String
    val name: String
    val capabilities: SourceCapabilities
    val requiresLogin: Boolean get() = capabilities.requiresLogin

    suspend fun search(keyword: String): SourceResult<List<SearchBook>>
    suspend fun getDetail(bookId: String): SourceResult<SearchBook>
    suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo>
    suspend fun login(credential: LoginCredential): SourceResult<Boolean>
    suspend fun logout()
    suspend fun isLoggedIn(): Boolean

    /** Source-provided account registration page, when available. */
    suspend fun getRegistrationUrl(): String? = null

    /**
     * 返回书籍可用的下载格式列表。默认实现返回空列表（不支持多格式选择）。
     * 调用方可凭此展示"选择格式"弹窗；空列表表示直接走 [getDownloadInfo] 默认格式。
     */
    suspend fun getAvailableFormats(book: SearchBook): SourceResult<List<BookFormat>> =
        SourceResult.Success(emptyList())

    /**
     * 按指定格式获取下载信息。默认实现忽略 [preferredFormat]，与单参数版本一致。
     */
    suspend fun getDownloadInfo(
        bookId: String,
        preferredFormat: String? = null
    ): SourceResult<DownloadInfo> = getDownloadInfo(bookId)

    suspend fun getAuthenticationState(): AuthenticationState {
        return if (capabilities.downloadRequiresLogin) {
            if (isLoggedIn()) AuthenticationState.Authenticated else AuthenticationState.Required
        } else {
            AuthenticationState.NotRequired
        }
    }
}
