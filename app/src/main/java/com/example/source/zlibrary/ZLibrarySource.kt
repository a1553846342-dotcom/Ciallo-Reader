package com.example.source.zlibrary

import android.content.Context
import android.util.Log
import com.example.source.*
import com.example.library.ZLibraryNodeConfig
import com.example.library.ZLibraryNodeManager
import com.example.source.zlibrary.network.ZLibraryHttpClient
import com.example.source.zlibrary.network.ZLibraryNetworkLogger
import com.example.source.zlibrary.network.ZLibrarySessionManager
import com.example.source.zlibrary.parser.ZLibraryParserManager
import okhttp3.FormBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class ZLibrarySource(
    private val context: Context,
    val credentialStorage: ZLibraryCredentialStorage = ZLibraryCredentialStorage(context),
    val httpClient: ZLibraryHttpClient = ZLibraryHttpClient(credentialStorage),
    val sessionManager: ZLibrarySessionManager = ZLibrarySessionManager(httpClient, credentialStorage)
) : BookSource {

    /** eapi（bipinkrish 方案）兜底客户端：登录/搜索/详情/多格式下载。 */
    val eapiClient = ZLibraryEapiClient(httpClient, credentialStorage)

    override val id: String = "zlibrary"
    override val name: String = "Z-Library"
    override suspend fun getRegistrationUrl(): String = "https://${ZLibraryNodeConfig.domain}/registration"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = true,
        searchRequiresLogin = false,
        downloadRequiresLogin = true,
        supportDebug = true
    )

    val domainResolver = ZLibraryDomainResolver(context, credentialStorage)

    override suspend fun isLoggedIn(): Boolean {
        return credentialStorage.isLoggedIn()
    }

    override suspend fun getAuthenticationState(): AuthenticationState {
        return if (isLoggedIn()) {
            AuthenticationState.Authenticated
        } else {
            AuthenticationState.Required
        }
    }

    private fun checkCloudflare(responseCode: Int, htmlBody: String) {
        // 513 = DiamWall 对非浏览器 TLS 指纹的硬拦截（2026-09-04 实测：OkHttp 冷启动
        // 常态命中）。不抛异常会掉进下方 !isSuccessful 分支只试 eapi 后直接报错，
        // 错失 WebView（Chromium 指纹）兜底——所以必须并入挑战码集合。
        if (responseCode == 403 || responseCode == 503 || responseCode == 513 || responseCode == 517 ||
            (htmlBody.contains("Just a moment") && !htmlBody.contains("dwid")) ||
            htmlBody.contains("Checking your browser")
        ) {
            throw SourceException.NetworkError("Cloudflare / DiamWall verification required")
        }
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> {
        return try {
            val domain = credential.extraData["domain"]?.ifBlank { null } ?: domainResolver.resolveDomain()

            if (!credential.cookie.isNullOrBlank()) {
                // Cookie login mode
                sessionManager.ensureSessionInitialized(domain)
                httpClient.cookieJar.syncFromRawCookieString(credential.cookie, domain)
                return if (credentialStorage.isLoggedIn()) {
                    SourceResult.Success(true)
                } else {
                    SourceResult.Error(SourceException.LoginRequired)
                }
            }

            if (credential.username.isBlank() || credential.password.isBlank()) {
                return SourceResult.Error(SourceException.ParseError("请输入用户名和密码，或提供有效的 Cookie"))
            }

            // 候选节点：健康检查通过的域优先（2026-09-04 修复"逐域盲提交"——
            // 旧行为对 18 个候选挨个 POST 登录表单，死域/重定向域浪费几分钟还
            // 有额外认证请求风险；现在登录前先快速可达性过滤，最多提交 4 个域）。
            // 只对"首页确认是 Z-Library"的主机提交登录（跳过假镜像，如 z-lib.id），
            // 每个候选最多 8 秒，避免 rpc.php 挂起导致读超时。
            // 2026-09-04 补充：首选域（用户选择/默认 = 1lib.sk）免健康检查——
            // DiamWall 挑战页会被健康检查误判为不可达，导致登录请求被引到
            // 仿冒站/死站上；首选域始终排第一优先尝试。
            val reachable = buildList {
                runCatching { ZLibraryNodeConfig.domain }.getOrNull()?.let { add(it) }
                add(domain)
                add(ZLibraryNodeManager.DEFAULT_NODE)
                addAll(ZLibraryNodeManager.getScrapedNodes(context))
            }.map {
                it.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
            }.filter { it.isNotBlank() }.distinct()

            val preferred = reachable.firstOrNull()
            val candidates: List<String> = coroutineScope {
                val deferred = reachable.drop(1).take(9).map { h: String ->
                    async {
                        val ok = runCatching { domainResolver.checkDomainHealth(h).isAvailable }.getOrDefault(false)
                        if (ok) h else null
                    }
                }
                (listOfNotNull(preferred) + deferred.awaitAll().filterNotNull()).distinct().take(4)
            }.ifEmpty { reachable.take(4) }

            var lastError: String? = null
            for (candidate in candidates) {
                val outcome = withTimeoutOrNull(8000) {
                    runCatching {
                        // 1) 访问首页，让 DiamWall 拦截器完成重定向 + PoW，拿到真实主机
                        val homeResponse = httpClient.get("https://$candidate/", referer = "https://$candidate/")
                        val effectiveHost = homeResponse.request.url.host
                        val homeHtml = homeResponse.body?.string() ?: ""
                        homeResponse.close()

                        val looksLikeZlib = homeHtml.contains("zlibrary.js") ||
                            homeHtml.contains("z-cover.js") ||
                            homeHtml.contains("z-bookcard") ||
                            homeHtml.contains("book-item")
                        if (!looksLikeZlib) return@runCatching null

                        // 2) 在真实主机上提交登录表单
                        val loginUrl = "https://$effectiveHost/rpc.php"
                        val formBody = FormBody.Builder()
                            .add("action", "login")
                            .add("email", credential.username)
                            .add("password", credential.password)
                            .add("is_remember", "1")
                            .build()

                        val response = httpClient.postForm(loginUrl, formBody, referer = "https://$effectiveHost/")
                        val htmlOrJson = response.body?.string() ?: ""
                        val responseUrl = response.request.url

                        if (!response.isSuccessful) {
                            return@runCatching "登录接口返回异常 HTTP ${response.code}"
                        }
                        val hasSession = httpClient.cookieJar.loadForRequest(responseUrl).any { it.name == "remix_userkey" } ||
                            httpClient.cookieJar.loadForRequest(responseUrl).any { it.name == "remix_userid" }
                        if (htmlOrJson.contains("\"is_error\":false") || htmlOrJson.contains("\"success\":1") || hasSession) {
                            credentialStorage.saveCredentials(
                                userId = credentialStorage.getUserId(),
                                userKey = credentialStorage.getUserKey(),
                                domain = effectiveHost,
                                cookies = credentialStorage.getCookies()
                            )
                            syncCookiesToWebView(effectiveHost)
                            return@runCatching "OK"
                        }
                        val doc = Jsoup.parse(htmlOrJson)
                        doc.select(".alert-danger, .error, .message").text().ifBlank {
                            Regex("alert\\(\"([^\"]+)\"\\)").find(htmlOrJson)?.groupValues?.get(1)
                                ?: "登录失败，请检查账号密码或 Cookie"
                        }
                    }.getOrElse { e -> e.message ?: "登录失败" }
                }
                when (outcome) {
                    "OK" -> return SourceResult.Success(true)
                    null -> Unit // 超时或非 Z-Library 首页：跳过该候选
                    else -> lastError = outcome
                }
            }
            // 兜底：rpc.php 全部失败时走 eapi 登录（bipinkrish 方案）
            for (candidate in candidates) {
                val ok = withTimeoutOrNull(8000) {
                    runCatching { eapiClient.login(credential.username, credential.password, candidate) }
                        .getOrDefault(false)
                } ?: false
                if (ok) {
                    syncCookiesToWebView(candidate)
                    return SourceResult.Success(true)
                }
            }
            SourceResult.Error(SourceException.ParseError(lastError ?: "登录失败，请检查账号密码或 Cookie"))
        } catch (e: Exception) {
            SourceResult.Error(SourceException.NetworkError("登录过程异常: ${e.message}"))
        }
    }

    override suspend fun logout() {
        credentialStorage.clear()
        sessionManager.invalidateSession()
    }

    private fun translateException(e: Exception): SourceException {
        return when (e) {
            is SourceException -> e
            is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.io.IOException -> {
                if (e.message?.contains("Cloudflare") == true || e.message?.contains("403") == true || e.message?.contains("503") == true) {
                    SourceException.NetworkError("该书源需要浏览器验证，请稍后重试")
                } else {
                    SourceException.NetworkError("Z-Library 当前节点不可用，正在寻找可用入口")
                }
            }
            else -> SourceException.NetworkError("网络连接失败，请检查网络: ${e.message}")
        }
    }

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
        return try {
            val domain = domainResolver.resolveDomain()
            sessionManager.ensureSessionInitialized(domain)

            // 路径段必须用 %20 而非 +（URLEncoder 会把空格编成 +，多词搜索会失效）
            val encodedKw = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
            val searchUrl = "https://$domain/s/$encodedKw"

            var response = try {
                httpClient.get(searchUrl, referer = "https://$domain/")
            } catch (e: Exception) {
                val fallbackDomain = domainResolver.resolveDomain(forceScan = true)
                sessionManager.ensureSessionInitialized(fallbackDomain, forceRefresh = true)
                val fallbackSearchUrl = "https://$fallbackDomain/s/$encodedKw"
                httpClient.get(fallbackSearchUrl, referer = "https://$fallbackDomain/")
            }

            var html = response.body?.string() ?: ""
            checkCloudflare(response.code, html)

            // Z-Library 官网搜索服务故障时（/s/ 与 /eapi/book/search 均返回
            // "Search service temporary unavailable!"）直接报错，绝不走 /fulltext/ 兜底：
            // /fulltext/ 会对任何关键词返回同一批推荐书（假结果）。
            if (html.contains("Search service temporary unavailable", ignoreCase = true)) {
                response.close()
                // 官网明确声明搜索故障时，eapi 搜索大概率同样故障，直接给出明确错误
                ZLibraryNetworkLogger.logParserResult("FAILED", 0, "Z-Library search service unavailable")
                return SourceResult.Error(
                    SourceException.NetworkError("Z-Library 搜索服务暂时不可用（官网故障），请稍后重试")
                )
            }

            // 搜索假结果守卫 #1（修复"搜索出40本"）：入口跳转域（zh.101z.by 等）会把
            // /s/{kw} 302 丢路径跳到主页——最终 URL 不在 /s/ 即铁证，强制扫描换真实
            // 镜像重试一次。零结果页仍在 /s/ 路径，不受影响。
            var finalUrl = response.request.url
            if (response.isSuccessful && !finalUrl.encodedPath.startsWith("/s/")) {
                response.close()
                ZLibraryNetworkLogger.logParserResult(
                    "RETRY_FALLBACK", 0,
                    "search redirected off /s/: path=${finalUrl.encodedPath}"
                )
                val fallbackDomain = domainResolver.resolveDomain(forceScan = true)
                sessionManager.ensureSessionInitialized(fallbackDomain, forceRefresh = true)
                val retryResponse = httpClient.get(
                    "https://$fallbackDomain/s/$encodedKw",
                    referer = "https://$fallbackDomain/"
                )
                val retryHtml = retryResponse.body?.string() ?: ""
                val retryRedirected = retryResponse.isSuccessful &&
                    !retryResponse.request.url.encodedPath.startsWith("/s/")
                if (retryRedirected) {
                    retryResponse.close()
                    return SourceResult.Error(
                        SourceException.NetworkError("搜索被重定向（节点为入口跳转域，非真实镜像），请更换节点后重试")
                    )
                }
                response = retryResponse
                html = retryHtml
            }

            if (!response.isSuccessful) {
                response.close()
                ZLibraryNetworkLogger.logParserResult("RETRY_EAPI", 0, "HTTP ${response.code}")
                val eapiBooks = eapiClient.search(keyword, response.request.url.host)
                if (eapiBooks.isNotEmpty()) {
                    ZLibraryNetworkLogger.logParserResult("SUCCESS_EAPI", eapiBooks.size, "eapi fallback")
                    return SourceResult.Success(eapiBooks)
                }
                // 513/529/500 等 HTTP 层全被挡（eapi 也被挡时返回空）：WebView
                // （Chromium TLS 指纹 + 会话 Cookie）是唯一能进的入口，再兜一次。
                val webBooks = ZLibraryWebViewHelper.searchViaWebView(
                    context,
                    response.request.url.host,
                    keyword,
                    cookies = credentialStorage.getCookies()
                )
                syncWebViewCookiesToHttp(webBooks.cookies, response.request.url.host)
                if (webBooks.books.isNotEmpty()) {
                    ZLibraryNetworkLogger.logParserResult("SUCCESS_WEBVIEW", webBooks.books.size, "HTTP error fallback")
                    return SourceResult.Success(webBooks.books)
                }
                return SourceResult.Error(SourceException.NetworkError("搜索请求失败 HTTP ${response.code}"))
            }

            val books = ZLibraryParserManager.parseSearchPage(html, "https://${response.request.url.host}", id)
            // 搜索假结果守卫 #2：页面留在 /s/ 但内容与关键词无关（登录墙后的推荐书
            // 列表等）。只拦"大结果集 + 标题归一化零命中 + 页面无关键词"的形态——
            // 主页充数通常是整页 40 本推荐；作者搜索/繁简变体的合法结果不会同时
            // 满足三条，避免误伤。
            if (books.size >= 15 && !html.contains(keyword, ignoreCase = true)) {
                fun normTitle(s: String): String = com.example.source.anilist.TitleNormalizer.normalize(
                    s.lowercase().replace(Regex("""[\s\p{Punct}]+"""), "")
                )
                val want = normTitle(keyword)
                val anyTitleHit = want.isNotBlank() && books.any { b ->
                    val n = normTitle(b.title)
                    n.isNotEmpty() && (n.contains(want) || want.contains(n))
                }
                if (!anyTitleHit) {
                    response.close()
                    ZLibraryNetworkLogger.logParserResult("RETRY_FALLBACK", books.size, "results irrelevant to keyword")
                    val fallbackDomain = domainResolver.resolveDomain(forceScan = true)
                    sessionManager.ensureSessionInitialized(fallbackDomain, forceRefresh = true)
                    val retryResponse = httpClient.get(
                        "https://$fallbackDomain/s/$encodedKw",
                        referer = "https://$fallbackDomain/"
                    )
                    val retryHtml = retryResponse.body?.string() ?: ""
                    val retryBooks = if (retryResponse.isSuccessful &&
                        retryResponse.request.url.encodedPath.startsWith("/s/") &&
                        retryHtml.contains(keyword, ignoreCase = true)
                    ) {
                        ZLibraryParserManager.parseSearchPage(retryHtml, "https://${retryResponse.request.url.host}", id)
                    } else {
                        emptyList()
                    }
                    retryResponse.close()
                    return if (retryBooks.isNotEmpty()) {
                        ZLibraryNetworkLogger.logParserResult("SUCCESS_FALLBACK", retryBooks.size, "fallback domain retry")
                        SourceResult.Success(retryBooks)
                    } else {
                        SourceResult.Error(
                            SourceException.NetworkError("搜索结果与关键词无关（节点返回了无关推荐），请更换节点后重试")
                        )
                    }
                }
            }
            if (books.isEmpty()) {
                response.close()
                // 页面含书卡片标记但解析为空：真实"无结果"
                if (html.contains("z-bookcard") || html.contains("resItemBox") ||
                    html.contains("book-item") || html.contains("/book/")
                ) {
                    ZLibraryNetworkLogger.logParserResult("EMPTY", 0, "no parseable cards")
                    // HTML 解析不出结果但页面结构正常：尝试 eapi 兜底，避免新版布局导致全空
                    val eapiBooks = eapiClient.search(keyword, response.request.url.host)
                    if (eapiBooks.isNotEmpty()) {
                        ZLibraryNetworkLogger.logParserResult("SUCCESS_EAPI", eapiBooks.size, "eapi empty fallback")
                        return SourceResult.Success(eapiBooks)
                    }
                    return SourceResult.Success(emptyList())
                }
                // 页面无任何书卡片标记：可能是 JS 渲染或新版挑战，交给 WebView 兜底
                val webBooks = ZLibraryWebViewHelper.searchViaWebView(
                    context,
                    response.request.url.host,
                    keyword,
                    cookies = credentialStorage.getCookies()
                )
                syncWebViewCookiesToHttp(webBooks.cookies, response.request.url.host)
                if (webBooks.books.isNotEmpty()) {
                    ZLibraryNetworkLogger.logParserResult("SUCCESS_WEBVIEW", webBooks.books.size, "HTML empty, WebView fallback")
                    return SourceResult.Success(webBooks.books)
                }
                if (webBooks.stillChallenged) {
                    ZLibraryNetworkLogger.logParserResult("FAILED", 0, "captcha challenge")
                    return SourceResult.Error(SourceException.NetworkError("需要浏览器验证，暂时无法自动搜索"))
                }
                ZLibraryNetworkLogger.logParserResult("EMPTY", 0, "no results anywhere")
                return SourceResult.Success(emptyList())
            }
            ZLibraryNetworkLogger.logParserResult(
                status = if (books.isNotEmpty()) "SUCCESS" else "EMPTY",
                bookCount = books.size
            )
            SourceResult.Success(books)
        } catch (e: SourceException) {
            val challengeDomain = domainResolver.resolveDomain()
            val webBooks = ZLibraryWebViewHelper.searchViaWebView(
                context,
                challengeDomain,
                keyword,
                cookies = credentialStorage.getCookies()
            )
            syncWebViewCookiesToHttp(webBooks.cookies, challengeDomain)
            if (webBooks.books.isNotEmpty()) {
                ZLibraryNetworkLogger.logParserResult("SUCCESS_WEBVIEW", webBooks.books.size, "Challenge fallback")
                return SourceResult.Success(webBooks.books)
            }
            ZLibraryNetworkLogger.logParserResult("FAILED", 0, e.message)
            SourceResult.Error(e)
        } catch (e: Exception) {
            val challengeDomain = domainResolver.resolveDomain()
            val webBooks = ZLibraryWebViewHelper.searchViaWebView(
                context,
                challengeDomain,
                keyword,
                cookies = credentialStorage.getCookies()
            )
            syncWebViewCookiesToHttp(webBooks.cookies, challengeDomain)
            if (webBooks.books.isNotEmpty()) {
                ZLibraryNetworkLogger.logParserResult("SUCCESS_WEBVIEW", webBooks.books.size, "Exception fallback")
                return SourceResult.Success(webBooks.books)
            }
            val translated = translateException(e)
            ZLibraryNetworkLogger.logParserResult("FAILED", 0, translated.message)
            SourceResult.Error(translated)
        }
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> {
        return try {
            val domain = domainResolver.resolveDomain()
            sessionManager.ensureSessionInitialized(domain)

            val cleanBookId = bookId.trimStart('/')
            val detailUrl = if (cleanBookId.startsWith("http")) cleanBookId else "https://$domain/$cleanBookId"

            val response = try {
                httpClient.get(detailUrl, referer = "https://$domain/")
            } catch (e: Exception) {
                val fallbackDomain = domainResolver.resolveDomain(forceScan = true)
                sessionManager.ensureSessionInitialized(fallbackDomain, forceRefresh = true)
                val fallbackDetailUrl = if (cleanBookId.startsWith("http")) cleanBookId else "https://$fallbackDomain/$cleanBookId"
                httpClient.get(fallbackDetailUrl, referer = "https://$fallbackDomain/")
            }

            val html = response.body?.string() ?: ""
            checkCloudflare(response.code, html)

            if (!response.isSuccessful) {
                // 兜底：HTML 详情页失败时尝试 eapi 书信息
                val eapiBook = resolveEapiBookKey(domain, bookId)?.let { key ->
                    eapiClient.getBookInfo(key.first, key.second, domain)
                }
                if (eapiBook != null) {
                    return SourceResult.Success(eapiBook)
                }
                return SourceResult.Error(SourceException.NetworkError("获取详情失败 HTTP ${response.code}"))
            }

            val parsed = ZLibraryParserManager.parseDetailPage(html, "https://${response.request.url.host}")

            val novelInfo = com.example.library.zLibraryNovelMetadata(html)

            val sourceBook = SearchBook(
                id = bookId,
                sourceId = id,
                title = parsed.title,
                author = parsed.author,
                cover = parsed.cover,
                format = parsed.format,
                description = novelInfo.synopsis,
                novelInfo = novelInfo,
                downloadUrl = parsed.downloadUrl
            )
            // 新版详情页可能解析不到 /dl/ 链接：用 eapi 书信息的 dl 字段补全
            // （该 /dl/ 直链已实测：解 DiamWall 后返回真实 EPUB 文件）
            if (sourceBook.downloadUrl.isNullOrBlank()) {
                val eapiBook = runCatching {
                    resolveEapiBookKey(domain, bookId)?.let { key ->
                        eapiClient.getBookInfo(key.first, key.second, domain)
                    }
                }.getOrNull()
                  if (eapiBook != null) {
                      return SourceResult.Success(eapiBook.copy(
                          description = eapiBook.description ?: sourceBook.description,
                          novelInfo = eapiBook.novelInfo ?: sourceBook.novelInfo))
                  }
            }
            SourceResult.Success(sourceBook)
        } catch (e: SourceException) {
            // 兜底：HTML 详情异常时尝试 eapi
            val eapiBook = runCatching {
                val d = domainResolver.resolveDomain()
                resolveEapiBookKey(d, bookId)?.let { key ->
                    eapiClient.getBookInfo(key.first, key.second, d)
                }
            }.getOrNull()
            if (eapiBook != null) {
                SourceResult.Success(eapiBook)
            } else {
                SourceResult.Error(e)
            }
        } catch (e: Exception) {
            val translated = translateException(e)
            val eapiBook = runCatching {
                val d = domainResolver.resolveDomain()
                resolveEapiBookKey(d, bookId)?.let { key ->
                    eapiClient.getBookInfo(key.first, key.second, d)
                }
            }.getOrNull()
            if (eapiBook != null) {
                SourceResult.Success(eapiBook)
            } else {
                SourceResult.Error(translated)
            }
        }
    }

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> {
        return getDownloadInfo(bookId, preferredFormat = null)
    }

    /**
     * 多格式下载：按用户选择的路由到对应格式的直链。
     * - 默认格式（HTML 详情页主直链）直接走 /dl/；
     * - 其他格式通过 eapi（bipinkrish 方案）的 formats -> file 链路获取。
     */
    override suspend fun getDownloadInfo(
        bookId: String,
        preferredFormat: String?
    ): SourceResult<DownloadInfo> {
        if (!isLoggedIn()) {
            return SourceResult.Error(SourceException.LoginRequired)
        }

        return try {
            val domain = domainResolver.resolveDomain()

            // 前置拦截：今日下载额度已用尽时直接提示，避免白白走下载+校验流程
            // （新站 /dl/ 在额度用尽时会返回 HTML 限额页，旧逻辑会误报“HTML 错误页”）
            val limitInfo = eapiClient.getDailyDownloadLimit(domain)
            if (limitInfo != null && limitInfo.first >= limitInfo.second) {
                return SourceResult.Error(
                    SourceException.NetworkError(
                        "今日下载次数已达上限（${limitInfo.first}/${limitInfo.second}），请等待额度重置或提升额度"
                    )
                )
            }

            val detailResult = getDetail(bookId)
            if (detailResult is SourceResult.Error) {
                return SourceResult.Error(detailResult.exception)
            }

            val book = (detailResult as SourceResult.Success).data
            val dlUrl = book.downloadUrl

            val cleanTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 页面解析格式可能缺/错（旧版页面默认 epub），优先按下载链接真实后缀推断
            val defaultFormat = guessFileFormatFromUrl(dlUrl)
                ?: book.format.ifBlank { "epub" }.lowercase()
            val wantFormat = preferredFormat?.lowercase()?.trim()
                ?.takeIf { it.isNotBlank() && it != defaultFormat }

            val cookieHeader = credentialStorage.getCookies() ?: ""

            // 用户手动选择的非默认格式：走 eapi formats -> 变体 file 链路（多格式下载）
            if (wantFormat != null) {
                val key = if (!book.eapiId.isNullOrBlank() && !book.eapiHash.isNullOrBlank()) {
                    book.eapiId to book.eapiHash
                } else {
                    resolveEapiBookKey(domain, bookId)
                }
                if (key != null) {
                    val variants = eapiClient.getFormats(key.first, key.second, domain)
                    val variant = variants.firstOrNull { it.format == wantFormat }
                    val variantKey = if (variant?.eapiId != null && variant.eapiHash != null) {
                        variant.eapiId to variant.eapiHash
                    } else {
                        null
                    }
                    if (variantKey != null) {
                        val linkResult = eapiClient.getDownloadLinkResult(
                            variantKey.first,
                            variantKey.second,
                            domain
                        )
                        if (!linkResult.url.isNullOrBlank()) {
                            return SourceResult.Success(
                                DownloadInfo(
                                    url = linkResult.url,
                                    fileName = "$cleanTitle.$wantFormat",
                                    format = wantFormat,
                                    size = variant?.size,
                                    referer = "https://$domain/",
                                    headers = mapOf("Cookie" to cookieHeader)
                                )
                            )
                        }
                        if (!linkResult.disallowMessage.isNullOrBlank()) {
                            return SourceResult.Error(
                                SourceException.NetworkError("$wantFormat 下载被限制：${linkResult.disallowMessage}")
                            )
                        }
                    }
                }
                return SourceResult.Error(
                    SourceException.NetworkError("无法获取 $wantFormat 格式（官网搜索服务暂不可用时可先下载默认格式）")
                )
            }

            // 默认格式：优先 eapi CDN 直链（真实文件，不经过 /dl/ HTML 中转页；
            // 部分节点如 zlib.bz 的 /dl/ 会返回 HTML 下载页而不是文件本身）。
            // eapi 失败/不可用时再回退详情页 /dl/ 链接 + 会话 Cookie
            // （/dl/ 会先返回 DiamWall 503 挑战页，DownloadWorker 的 DiamWallInterceptor
            // 会自动解 PoW 后重试）。
            val detailUrl = "https://$domain/${bookId.trimStart('/')}"
            val eapiLink = runCatching {
                val key = if (!book.eapiId.isNullOrBlank() && !book.eapiHash.isNullOrBlank()) {
                    book.eapiId to book.eapiHash
                } else {
                    resolveEapiBookKey(domain, bookId)
                }
                key?.let { eapiClient.getDownloadLinkResult(it.first, it.second, domain) }
            }.getOrNull()
            if (eapiLink?.url.isNullOrBlank() && !eapiLink?.disallowMessage.isNullOrBlank()) {
                // 每日额度用尽等明确限制：直接提示，避免白白走 /dl/ 后报“HTML 错误页”
                return SourceResult.Error(
                    SourceException.NetworkError(eapiLink!!.disallowMessage)
                )
            }
            val finalUrl = eapiLink?.url ?: dlUrl
            if (finalUrl.isNullOrBlank()) {
                return SourceResult.Error(
                    SourceException.ParseError("未获取到下载链接，请稍后重试或切换节点")
                )
            }
            SourceResult.Success(
                DownloadInfo(
                    url = finalUrl,
                    fileName = "$cleanTitle.$defaultFormat",
                    format = defaultFormat,
                    referer = detailUrl,
                    headers = mapOf("Cookie" to cookieHeader)
                )
            )
        } catch (e: SourceException) {
            SourceResult.Error(e)
        } catch (e: Exception) {
            SourceResult.Error(SourceException.NetworkError("获取下载链接失败: ${e.message}"))
        }
    }

    /**
     * 可用格式列表：
     * 1. eapi 元数据已知时（eapi 兜底搜索产生的结果）直接查 formats 接口（权威、带大小）；
     * 2. 否则抓 HTML 详情页拿默认格式；
     * 3. 尽力通过 eapi 搜索书名解析出 eapi id/hash 后补全多格式。
     */
    override suspend fun getAvailableFormats(book: SearchBook): SourceResult<List<BookFormat>> {
        return try {
            val domain = domainResolver.resolveDomain()
            sessionManager.ensureSessionInitialized(domain)

            val merged = LinkedHashMap<String, BookFormat>()

            // 1) eapi 元数据已知 -> formats 接口
            if (!book.eapiId.isNullOrBlank() && !book.eapiHash.isNullOrBlank()) {
                eapiClient.getFormats(book.eapiId, book.eapiHash, domain).forEach { merged[it.format] = it }
            }

            // 2) HTML 详情页默认格式
            val detail = runCatching { getDetail(book.id) }.getOrNull()
            val detailBook = (detail as? SourceResult.Success)?.data
            if (detailBook != null) {
                val defaultFormat = guessFileFormatFromUrl(detailBook.downloadUrl)
                    ?: detailBook.format.ifBlank { "epub" }.lowercase()
                if (defaultFormat.isNotBlank() && !merged.containsKey(defaultFormat)) {
                    merged[defaultFormat] = BookFormat(
                        format = defaultFormat,
                        downloadUrl = detailBook.downloadUrl
                    )
                }
            }

            // 3) 尽力解析 eapi key 补全多格式（需要官网搜索可用）
            if (merged.isEmpty() || book.eapiId.isNullOrBlank()) {
                val key = resolveEapiBookKey(domain, book.id)
                if (key != null) {
                    eapiClient.getFormats(key.first, key.second, domain).forEach { merged[it.format] = it }
                }
            }

            if (merged.isEmpty()) {
                SourceResult.Success(emptyList())
            } else {
                SourceResult.Success(merged.values.toList())
            }
        } catch (e: Exception) {
            SourceResult.Error(SourceException.NetworkError("获取格式列表失败: ${e.message}"))
        }
    }

    /**
     * 解析一本书的 eapi (id, hash)：
     * 详情页有 data-book_id（数字 id），再用 eapi 搜索书名匹配同 id 的书拿到短 hash。
     */
    private suspend fun resolveEapiBookKey(domain: String, bookId: String): Pair<String, String>? {
        return runCatching {
            val cleanBookId = bookId.trimStart('/')
            val detailUrl = if (cleanBookId.startsWith("http")) cleanBookId else "https://$domain/$cleanBookId"
            val response = httpClient.get(detailUrl, referer = "https://$domain/")
            val html = response.body?.string() ?: ""
            response.close()
            val numericId = Regex("""data-book_id="(\d+)"""").find(html)?.groupValues?.get(1)
            if (numericId == null) return@runCatching null

            val title = Jsoup.parse(html).selectFirst("h1")?.text()?.trim()
            if (title.isNullOrBlank()) return@runCatching null

            val books = eapiClient.search(title, domain)
            val hit = books.firstOrNull { it.eapiId == numericId }
            val eid = hit?.eapiId
            val ehash = hit?.eapiHash
            if (eid.isNullOrBlank() || ehash.isNullOrBlank()) return@runCatching null
            eid to ehash
        }.getOrNull()
    }

    /**
     * 把 eapi/rpc 登录得到的会话 Cookie 同步到 WebView CookieManager，
     * 供自研 WebView 下载方案（/dl/ 页面）直接使用。
     */
    fun syncCookiesToWebView(domain: String = credentialStorage.getDomain()) {
        val cookies = credentialStorage.getCookies() ?: return
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cookies.split(';').forEach { cookie ->
                if(cookie.contains('=')) cm.setCookie("https://$domain/", cookie.trim()+"; Path=/; Secure")
            }
            cm.flush()
        }
    }

    /**
     * WebView 兜底过完 DiamWall 挑战后，把 WebView 侧的完整 Cookie
     * （dwid / c_token / __diamwall / remix_*）回灌进 OkHttp 的 CookieJar，
     * 后续详情页与下载请求全程免挑战（2026-09-04 1lib.sk 实测）。
     */
    private fun syncWebViewCookiesToHttp(cookies: String?, domain: String) {
        if (cookies.isNullOrBlank() || domain.isBlank()) return
        runCatching {
            httpClient.cookieJar.syncFromRawCookieString(cookies, domain)
        }.onFailure {
            Log.w("ZLibSource", "sync WebView cookies failed: ${it.message}")
        }
    }

}
