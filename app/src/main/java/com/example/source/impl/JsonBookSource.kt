package com.example.source.impl

import com.example.data.readImportBytes
import com.example.source.*
import com.example.source.parser.JsonPathResolver
import com.example.source.parser.LegadoRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.Charset
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 自定义 JSON 书源实现。
 *
 * 支持两套规则：
 * 1. 本项目原生 JSON 格式：search / detail / download + htmlSearch / htmlChapters / htmlContent
 * 2. Legado（开源阅读）兼容格式：class.x@tag.a@text、@css:、|| 回退、##正则##替换、
 *    JSONPath（$.data.books / $..books[*]）等
 */
class JsonBookSource(
    val config: SourceConfig,
    private val client: OkHttpClient = defaultClient
) : ComicSource {

    /** 搜索结果缓存：让 getDownloadInfo 直接复用列表里的下载链接/标题，避免必须配置 detail。 */
    private val searchItemCache = java.util.concurrent.ConcurrentHashMap<String, SearchBook>()
    private val searchRawCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val configuredHost=runCatching { java.net.URI(config.baseUrl.ifBlank { config.search.url }).host?.lowercase() }.getOrNull()
    private val requestClient: OkHttpClient = (if (config.insecureTls) createInsecureClient() else client).newBuilder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = client.dns.lookup(hostname).also { addresses ->
                addresses.forEach { com.example.source.SourceNetworkPolicy.checkAddress(hostname,it,configuredHost) }
            }
        })
        .addNetworkInterceptor { chain ->
            val request=chain.request()
            com.example.source.SourceNetworkPolicy.check(request.url,configuredHost)
            if(config.insecureTls && (request.header("Authorization")!=null || request.header("Cookie")!=null))
                throw java.io.IOException("不安全 TLS 书源不能发送登录凭据，请启用证书验证")
            chain.proceed(request)
        }.build()

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
        private val defaultClient = com.example.source.SharedHttpTransport.builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private fun createInsecureClient(): OkHttpClient {
            val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            return com.example.source.SharedHttpTransport.builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAll[0])
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }

    override val id: String = config.id
    override val name: String = config.name

    /**
     * 是否漫画源：
     * 1. 配置里显式声明 type=comic/novel 时以声明为准；
     * 2. 否则看正文规则是否在提取“图片”（img/@src/data-src 等选择器）。
     * 文本源即使配置了 htmlChapters/htmlContent（章节/正文规则），也不会被误判成漫画。
     */
    private val isComicLike: Boolean = when (config.type?.lowercase()) {
        "comic", "漫画" -> true
        "novel", "text", "小说", "文本" -> false
        else -> {
            val sel = config.htmlContent?.imageSelector?.lowercase() ?: ""
            sel.contains("@src") ||
                sel.contains("data-src") ||
                sel.contains("data-original") ||
                sel.contains("lazy") ||
                sel.contains("img") ||
                sel.contains("amp-img") ||
                sel.contains("mip-img") ||
                sel.contains("image") ||
                sel.contains("pic")
        }
    }

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportImport = true,
        supportComic = isComicLike,
        // 文本源：有章节规则且正文不是图片规则时，支持在线章节阅读
        supportOnlineText = config.htmlChapters != null && !isComicLike
    )

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
        return withContext(Dispatchers.IO) {
            try {
                if(searchItemCache.size>1000) { searchItemCache.clear(); searchRawCache.clear() }
                if (config.htmlSearch != null) {
                    return@withContext searchHtml(keyword)
                }
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val keywordB64 = android.util.Base64.encodeToString(
                    keyword.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val requestUrl = config.search.url
                    .replace("{keyword}", encodedKeyword)
                    .replace("{keyword_b64}", keywordB64)
                val fullUrl = resolveUrl(requestUrl)

                val requestBuilder = Request.Builder().url(fullUrl)
                config.search.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                val request = buildRequest(requestBuilder, config.search.method, config.search.body, encodedKeyword)

                val response = requestClient.newCall(request).executeCancellable()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext SourceResult.Error(
                        SourceException.NetworkError("HTTP error status code: ${response.code}")
                    )
                }

                val bodyStr = response.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: ""
                val jsonItems = JsonPathResolver.resolveArray(bodyStr, config.search.listPath)

                jsonItems.forEach { jsonObj ->
                    parseSearchBook(jsonObj, config.search.fields)?.let { book ->
                        searchItemCache[book.id] = book
                        searchRawCache[book.id] = jsonObj
                    }
                }

                val books = jsonItems.mapNotNull { jsonObj ->
                    parseSearchBook(jsonObj, config.search.fields)
                }

                SourceResult.Success(books)
            } catch (e: IOException) {
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: JSONException) {
                SourceResult.Error(SourceException.ParseError("JSON解析异常: ${e.message}"))
            } catch (e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }
    }

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> {
        val rule = config.htmlChapters
        if (rule == null) {
            return SourceResult.Error(SourceException.ParseError("该书源未配置漫画目录规则"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val detailUrl = resolveUrl(rule.url.replace("{id}", bookId))
                var url = detailUrl
                // 目录不在详情页：先用 tocUrlSelector 从详情页解析目录页地址
                val tocRule = rule.tocUrlSelector
                if (!tocRule.isNullOrBlank()) {
                    try {
                        val detailRaw = fetchHtml(detailUrl)
                        val detailDoc = Jsoup.parse(detailRaw)
                        val tocUrl = LegadoRule.evalFirst(detailDoc, tocRule)
                        if (!tocUrl.isNullOrBlank()) {
                            url = resolveUrl(tocUrl)
                            SourceLog.log(name, "目录页跳转 ← $tocRule → ${url.take(120)}")
                        }
                    } catch (e: Exception) {
                        SourceLog.log(name, "tocUrl 解析失败，回退详情页: ${e.message}")
                    }
                }
                val raw = fetchHtml(url)
                val chapters = if (LegadoRule.isJsonRule(rule.listSelector)) {
                    val items = JsonPathResolver.resolveArray(raw, LegadoRule.cleanJsonPath(rule.listSelector))
                    items.mapNotNull { item ->
                        val name = resolveJsonTemplate(item, rule.nameSelector) ?: return@mapNotNull null
                        val href = resolveJsonTemplate(item, rule.hrefSelector) ?: return@mapNotNull null
                        ComicChapter(id = resolveUrl(href), title = name)
                    }
                } else {
                    val doc = Jsoup.parse(raw)
                    LegadoRule.selectElements(doc, rule.listSelector).mapNotNull { el ->
                        val name = selectValue(el, rule.nameSelector) ?: return@mapNotNull null
                        val href = selectValue(el, rule.hrefSelector) ?: return@mapNotNull null
                        ComicChapter(id = resolveUrl(href), title = name)
                    }
                }
                SourceResult.Success(chapters)
            } catch (e: IOException) {
                SourceLog.log(name, "目录失败 ← $bookId: ${e.message}")
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                SourceLog.log(name, "目录异常 ← $bookId: ${e.message}")
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }
    }

    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> {
        val rule = config.htmlContent
        if (rule == null) {
            return SourceResult.Error(SourceException.ParseError("该书源未配置漫画图片规则"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = resolveUrl(
                    rule.url.replace("{chapterUrl}", chapterId)
                )
                val raw = fetchHtml(url)
                val images = if (LegadoRule.isJsonRule(rule.imageSelector)) {
                    JsonPathResolver.resolveStringArray(raw, LegadoRule.cleanJsonPath(rule.imageSelector))
                        .mapNotNull { resolveUrl(it).takeIf { u -> validateUrl(u) } }
                } else {
                    val doc = Jsoup.parse(raw)
                    val images = LegadoRule.evalValues(doc, rule.imageSelector)
                        .flatMap { extractImageUrls(it) }
                        .mapNotNull { resolveUrl(it).takeIf { u -> validateUrl(u) } }
                        .distinct()
                    images
                }
                if (images.isEmpty()) {
                    SourceLog.log(name, "正文无图片 ← $chapterId（可能是文本源或规则失效）")
                    SourceResult.Error(SourceException.ParseError("该章节没有可读取的图片（若为文本书源，请前往详情页下载原文件）"))
                } else {
                    SourceLog.log(name, "正文图片 ${images.size} 张 ← $chapterId")
                    SourceResult.Success(images)
                }
            } catch (e: IOException) {
                SourceLog.log(name, "正文失败 ← $chapterId: ${e.message}")
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                SourceLog.log(name, "正文异常 ← $chapterId: ${e.message}")
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }
    }

    /** 章节式文本源：抓取章节页并按 Legado 正文规则提取纯文本（段落 \n\n 分隔）。 */
    override suspend fun getChapterText(chapterId: String): SourceResult<String> {
        val rule = config.htmlContent
            ?: return SourceResult.Error(SourceException.ParseError("该书源未配置正文规则"))
        return withContext(Dispatchers.IO) {
            try {
                val url = resolveUrl(rule.url.replace("{chapterUrl}", chapterId))
                val raw = fetchHtml(url)
                val paragraphs = if (LegadoRule.isJsonRule(rule.imageSelector)) {
                    JsonPathResolver.resolveStringArray(raw, LegadoRule.cleanJsonPath(rule.imageSelector))
                } else {
                    val doc = Jsoup.parse(raw)
                    LegadoRule.evalValues(doc, rule.imageSelector)
                }
                val text = paragraphs
                    .map { it.replace("\u00A0", " ").trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                if (text.isBlank()) {
                    SourceLog.log(name, "正文无文字 ← $chapterId（规则可能失效）")
                    SourceResult.Error(SourceException.ParseError("本章内容为空（正文规则可能失效）"))
                } else {
                    SourceLog.log(name, "正文 ${text.length} 字 ← $chapterId")
                    SourceResult.Success(text)
                }
            } catch (e: IOException) {
                SourceLog.log(name, "正文失败 ← $chapterId: ${e.message}")
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}"))
            } catch (e: Exception) {
                SourceLog.log(name, "正文异常 ← $chapterId: ${e.message}")
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}"))
            }
        }
    }

    private suspend fun searchHtml(keyword: String): SourceResult<List<SearchBook>> {
        val rule = config.htmlSearch ?: return SourceResult.Error(SourceException.ParseError("缺少 htmlSearch 规则"))
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val keywordB64 = android.util.Base64.encodeToString(
            keyword.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val url = resolveUrl(
            rule.url
                .replace("{keyword}", encoded)
                .replace("{keyword_b64}", keywordB64)
                .replace("{page}", "1")
        )
        val body = rule.body
            ?.replace("{keyword}", encoded)
            ?.replace("{keyword_b64}", keywordB64)
            ?.replace("{page}", "1")
        val raw = try {
            fetchHtml(url, rule.method, body)
        } catch (e: Exception) {
            SourceLog.log(name, "搜索失败「$keyword」: ${e.message}")
            throw e
        }
        if (LegadoRule.isJsonRule(rule.listSelector)) {
            val r = searchHtmlJson(raw, rule)
            SourceLog.log(name, "搜索「$keyword」(${rule.method}) → ${if (r is SourceResult.Success) "${r.data.size} 条结果" else "失败: ${(r as SourceResult.Error).exception.message}"}")
            return r
        }
        val doc = Jsoup.parse(raw)
        val books = mutableListOf<SearchBook>()
        for (el in LegadoRule.selectElements(doc, rule.listSelector)) {
            val title = selectValue(el, rule.titleSelector.ifBlank { "a@text" })
                ?: selectValue(el, "text")
                ?: continue
            val detailRaw = selectValue(el, rule.detailUrlSelector.ifBlank { "a@href" }) ?: continue
            val coverRaw = selectValue(el, rule.coverSelector)
            books.add(
                SearchBook(
                    id = detailRaw,
                    sourceId = id,
                    title = title,
                    author = selectValue(el, rule.authorSelector) ?: "未知作者",
                    cover = coverRaw?.let { resolveUrl(it) },
                    description = selectValue(el, rule.introSelector),
                    format = if (isComicLike) "漫画" else config.download.defaultFormat.ifBlank { "" }
                )
            )
        }
        SourceLog.log(name, "搜索「$keyword」(${rule.method}) → ${books.size} 条结果")
        return SourceResult.Success(books)
    }

    /** JSON 搜索结果：rule.listSelector 为 JSONPath，字段规则为 JSONPath / 模板。 */
    private fun searchHtmlJson(raw: String, rule: HtmlSearchRule): SourceResult<List<SearchBook>> {
        val items = JsonPathResolver.resolveArray(raw, LegadoRule.cleanJsonPath(rule.listSelector))
        val books = mutableListOf<SearchBook>()
        for (item in items) {
            val title = resolveJsonTemplate(item, rule.titleSelector.ifBlank { "name" }) ?: continue
            val detailRaw = resolveJsonTemplate(item, rule.detailUrlSelector.ifBlank { "id" }) ?: continue
            val coverRaw = resolveJsonTemplate(item, rule.coverSelector)
            books.add(
                SearchBook(
                    id = detailRaw,
                    sourceId = id,
                    title = title,
                    author = resolveJsonTemplate(item, rule.authorSelector) ?: "未知作者",
                    cover = coverRaw?.let { resolveUrl(it) },
                    description = resolveJsonTemplate(item, rule.introSelector),
                    format = if (isComicLike) "漫画" else config.download.defaultFormat.ifBlank { "" }
                )
            )
        }
        return SourceResult.Success(books)
    }

    /**
     * JSON 模式字段取值：支持纯字段名（name / $.name）、URL 模板（/book/{{$.id}}）、
     * 以及旧式 {$._id} 模板。
     */
    private fun resolveJsonTemplate(item: JSONObject, rule: String?): String? {
        val r = rule?.trim() ?: return null
        if (r.isBlank()) return null
        if (r == "text") {
            return JsonPathResolver.getString(item, "name")
                ?: JsonPathResolver.getString(item, "title")
        }
        if (r == "href") {
            return JsonPathResolver.getString(item, "href")
                ?: JsonPathResolver.getString(item, "url")
                ?: JsonPathResolver.getString(item, "link")
        }
        if (r.contains("{{")) {
            val result = Regex("""\{\{([^}]+)\}\}""").replace(r) { m ->
                JsonPathResolver.getString(item, LegadoRule.cleanJsonPath(m.groupValues[1])) ?: ""
            }
            return result.trim().ifBlank { null }
        }
        if (r.contains("{$")) {
            val result = Regex("""\{([^}]+)\}""").replace(r) { m ->
                JsonPathResolver.getString(item, LegadoRule.cleanJsonPath(m.groupValues[1])) ?: ""
            }
            return result.trim().ifBlank { null }
        }
        return JsonPathResolver.getString(item, LegadoRule.cleanJsonPath(r))
    }

    private fun selectValue(root: Element, rule: String): String? {
        val r = rule.trim()
        if (r.isBlank()) return null
        if (r == "text") return root.text().trim().ifBlank { null }
        if (r == "href" || r == "src" || r == "data-src") {
            return root.attr(r).trim().ifBlank { null }
        }
        return LegadoRule.evalFirst(root, r)
    }

    private suspend fun fetchHtml(url: String, method: String = "GET", body: String? = null): String {
        val builder = Request.Builder().url(url)
        val headers = config.search.headers
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DEFAULT_UA)
        }
        if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
            builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (method.equals("POST", ignoreCase = true)) {
            val mediaType = if (body?.trimStart()?.startsWith("{") == true) {
                "application/json; charset=utf-8"
            } else {
                "application/x-www-form-urlencoded"
            }
            builder.post((body ?: "").toRequestBody(mediaType.toMediaType()))
        } else {
            builder.get()
        }
        SourceLog.log(name, "请求 $method ${url.take(120)}")
        return requestClient.newCall(builder.build()).executeCancellable().use { response ->
        if (!response.isSuccessful) {
            SourceLog.log(name, "HTTP ${response.code} ← $method ${url.take(120)}")
            throw SourceException.NetworkError("HTTP ${response.code} @ ${url.take(160)}")
        }
        SourceLog.log(name, "HTTP ${response.code} OK ← $method ${url.take(120)}")
        val charset = config.htmlSearch?.charset?.ifBlank { null }
        if (charset != null) {
            val bytes = response.body?.byteStream()?.use { it.readImportBytes(2*1024*1024) } ?: return ""
            try {
                String(bytes, Charset.forName(charset))
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        } else {
            response.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: ""
        }
        }
    }

    /**
     * 从规则值中提取图片 URL。
     * 支持三种形态：纯 URL 列表（空白分隔）、HTML 片段（<img>/<amp-img>/<mip-img> 标签）。
     */
    private fun extractImageUrls(value: String): List<String> {
        val v = value.trim()
        if (v.isEmpty()) return emptyList()
        if (Regex("""<(img|amp-img|mip-img)\b""", RegexOption.IGNORE_CASE).containsMatchIn(v)) {
            val fragment = Jsoup.parseBodyFragment(v)
            return fragment.select("img, amp-img, mip-img").mapNotNull { el ->
                el.attr("data-src")
                    .ifBlank { el.attr("data-original") }
                    .ifBlank { el.attr("data-lazy-src") }
                    .ifBlank { el.attr("src") }
                    .ifBlank { el.attr("data-url") }
                    .trim()
                    .ifBlank { null }
            }
        }
        return Regex("""https?://[^\s"'<>]+""").findAll(v)
            .map { it.value.trimEnd(',', ';', ']', '}') }
            .toList()
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> {
        return withContext(Dispatchers.IO) {
            val detailRule = config.detail
            if (detailRule == null) {
                // Return a basic placeholder book if detail rule is omitted
                return@withContext SourceResult.Success(
                    SearchBook(
                        id = bookId,
                        sourceId = id,
                        title = bookId,
                        author = "Unknown"
                    )
                )
            }

            try {
                val encodedId = URLEncoder.encode(bookId, "UTF-8")
                val requestUrl = detailRule.url.replace("{id}", encodedId)
                val fullUrl = resolveUrl(requestUrl)

                val requestBuilder = Request.Builder().url(fullUrl)
                detailRule.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                val request = buildRequest(requestBuilder, detailRule.method, detailRule.body, encodedId)

                val response = requestClient.newCall(request).executeCancellable()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext SourceResult.Error(
                        SourceException.NetworkError("HTTP error status code: ${response.code}")
                    )
                }

                val bodyStr = response.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: ""
                val jsonObj = try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    return@withContext SourceResult.Error(SourceException.ParseError("无效的详情 JSON"))
                }

                val book = parseSearchBook(jsonObj, detailRule.fields, defaultId = bookId)
                if (book != null) {
                    SourceResult.Success(book)
                } else {
                    SourceResult.Error(SourceException.BookNotFound)
                }
            } catch (e: IOException) {
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: JSONException) {
                SourceResult.Error(SourceException.ParseError("JSON解析异常: ${e.message}"))
            } catch (e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }
    }

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> {
        return withContext(Dispatchers.IO) {
            val downloadRule = config.download
            val downloadUrlTemplate = downloadRule.url
            val cachedBook = searchItemCache[bookId]
            val cachedRaw = searchRawCache[bookId]
            val encodedId = URLEncoder.encode(bookId, "UTF-8")
            val fallbackDetail = getDetail(bookId).getOrNull()

            val targetUrl = if (!downloadUrlTemplate.isNullOrEmpty()) {
                resolveUrl(downloadUrlTemplate.replace("{id}", encodedId))
            } else if (!cachedBook?.downloadUrl.isNullOrBlank()) {
                resolveUrl(cachedBook!!.downloadUrl!!)
            } else if (!downloadRule.urlField.isNullOrBlank() && cachedRaw != null) {
                val raw = JsonPathResolver.getString(cachedRaw, downloadRule.urlField)
                if (!raw.isNullOrBlank()) resolveUrl(raw) else ""
            } else {
                // Fetch detail to locate downloadUrl field
                fallbackDetail?.downloadUrl ?: ""
            }

            val displayTitle = cachedBook?.title
                ?: fallbackDetail?.title
                ?: bookId
            val cleanTitle = displayTitle
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { bookId }

            if (!validateUrl(targetUrl)) {
                return@withContext SourceResult.Error(SourceException.ParseError("非法或不支持的下载链接: $targetUrl"))
            }

            SourceResult.Success(
                DownloadInfo(
                    url = targetUrl,
                    fileName = "$cleanTitle.${downloadRule.defaultFormat}",
                    format = downloadRule.defaultFormat,
                    headers = downloadRule.headers,
                    referer = config.baseUrl.trimEnd('/').ifBlank { null }
                )
            )
        }
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> {
        return SourceResult.Success(false)
    }

    override suspend fun logout() {}

    override suspend fun isLoggedIn(): Boolean = false

    private fun parseSearchBook(jsonObj: JSONObject, fields: BookFieldRule, defaultId: String = ""): SearchBook? {
        val extractedId = JsonPathResolver.getString(jsonObj, fields.id) ?: defaultId
        val title = JsonPathResolver.getString(jsonObj, fields.title) ?: return null
        val author = JsonPathResolver.getString(jsonObj, fields.author) ?: "未知"
        val rawCover = JsonPathResolver.getString(jsonObj, fields.cover)
        val cover = if (!rawCover.isNullOrEmpty()) resolveUrl(rawCover) else null
        val description = JsonPathResolver.getString(jsonObj, fields.description)
        val format = JsonPathResolver.getString(jsonObj, fields.format) ?: config.download.defaultFormat
        val rawDownloadUrl = JsonPathResolver.getString(jsonObj, fields.downloadUrl)
        val downloadUrl = if (!rawDownloadUrl.isNullOrEmpty()) resolveUrl(rawDownloadUrl) else null

        return SearchBook(
            id = extractedId,
            sourceId = id,
            title = title,
            author = author,
            cover = cover,
            description = description,
            format = format,
            downloadUrl = downloadUrl
        )
    }

    private fun validateUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.trim().lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:")) return false
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun resolveUrl(url: String): String {
        val lower = url.trim().lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("javascript:") || lower.startsWith("file:")) return url
        val base = config.baseUrl.trimEnd('/')
        val path = url.trimStart('/')
        return if (base.isEmpty()) url else "$base/$path"
    }

    private fun buildRequest(
        builder: okhttp3.Request.Builder,
        method: String,
        bodyTemplate: String?,
        encodedParam: String
    ): Request {
        val upperMethod = method.uppercase()
        return if (upperMethod == "POST" || upperMethod == "PUT" || upperMethod == "PATCH") {
            val jsonBody = bodyTemplate
                ?.replace("{keyword}", encodedParam)
                ?.replace("{id}", encodedParam)
                ?.ifBlank { null }
                ?: "{}"
            builder.method(
                upperMethod,
                jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            ).build()
        } else {
            builder.get().build()
        }
    }
}
