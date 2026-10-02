package com.example.source.js

import android.content.Context
import android.util.Log
import com.example.source.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个 Venera 兼容的 JS 漫画源。
 * 通过 JsSourceEngine 调用源脚本的 search.load / comic.loadInfo / comic.loadEp。
 */

/**
 * 把 JS 源桥层的原始错误翻译成用户能看懂、能行动的中文提示。
 * 此前聚合搜索把所有非超时错误一律显示成「无结果」，真实原因（被墙需代理/需登录/登录过期）完全不可见。
 */
internal fun friendlyJsSourceError(raw: String): String {
    val text = raw.trim()
    return when {
        text.isEmpty() -> "JS 执行失败"
        text.contains("Not logged in", ignoreCase = true) ||
            text.contains("请先登录") -> "该源需要登录：请在书源管理中登录"
        text.contains("Invalid status code: 401") -> "登录已过期：请在书源管理中重新登录"
        text.contains("Cloudflare", ignoreCase = true) ->
            "源站暂时拦截请求，请稍后重试"
            text.contains("Invalid status code: 403", ignoreCase = true) ||
            text.contains("当前区域禁止访问") ->
            "源站拒绝当前网络出口（HTTP 403），请稍后重试或更换 VPN 节点"
        text.contains("ERR_", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true) ||
            text.contains("请求超时") ||
            text.contains("Connection reset", ignoreCase = true) ||
            text.contains("SocketException", ignoreCase = true) ||
            text.contains("Unable to resolve host", ignoreCase = true) ->
            "网络连接失败：请检查该源站点、系统代理或 VPN 后重试"
        else -> text.take(120)
    }
}

class JsComicSource(
    private val context: Context,
    val sourceKey: String,
    override val name: String,
    val version: String,
    private val script: String,
    private val insecureTls: Boolean = false,
    private val loginRequired: Boolean = false
) : ComicSource {

    override val id: String get() = "js_$sourceKey"

    override suspend fun getRegistrationUrl(): String? {
        // Literal links (e.g. Picacg) need no JS bootstrap or network request.
        val literal = Regex("""registerWebsite\s*:\s*(["'])([^"'\r\n]+)\1""")
            .find(script)?.groupValues?.get(2)
        validRegistrationUrl(literal)?.let { return it }
        return try {
            val json = getEngine().call("src.account && src.account.registerWebsite || null")
                ?.let(::JSONObject)
            validRegistrationUrl(json?.optString("data"))
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
    }
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = true,
        supportComic = true,
        searchRequiresLogin = loginRequired && sourceKey !in setOf("ikmmh", "vomic"),
        downloadRequiresLogin = loginRequired && sourceKey != "ikmmh"
    )

    private val createMutex = Mutex()
    private var engine: JsSourceEngine? = null
    private val verificationPrefs by lazy {
        context.getSharedPreferences("js_source_website_verification", Context.MODE_PRIVATE)
    }

    private suspend fun applyVerifiedUserAgent(runtime: JsSourceEngine, userAgent: String) {
        if (sourceKey != "mycomic" || userAgent.isBlank()) return
        runtime.call("""
            (()=>{
                if (typeof headers !== 'undefined') {
                    headers['User-Agent'] = ${q(userAgent)};
                    for (const key of Object.keys(headers)) {
                        if (key.toLowerCase().startsWith('sec-ch-ua')) delete headers[key];
                    }
                }
            })()
        """.trimIndent())
    }
    private data class CachedChapters(val savedAt: Long, val chapters: List<ComicChapter>)
    private val chapterLoadMutex = Mutex()
    private val chapterCacheMonitor = Any()
    private val chapterCache = LinkedHashMap<String, CachedChapters>()
    private val detailCache = LinkedHashMap<String, Pair<Long, SearchBook>>()
    private var chapterCacheEpoch = 0L
    private fun invalidateChapterCache() {
        synchronized(chapterCacheMonitor) {
            chapterCacheEpoch++
            chapterCache.clear()
            detailCache.clear()
        }
        // Image configuration may contain the old account's authorization headers.
        synchronized(imageConfigCache) { imageConfigCache.clear() }
    }

    private data class ImageConfig(
        val originalUrl: String,
        val finalUrl: String,
        val headers: Map<String, String>,
        val modifyCode: String?,
        val nl: String? = null
    )

    private val imageConfigCache = object : LinkedHashMap<String, ImageConfig>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageConfig>?): Boolean = size > 512
    }

    private var engineInitialized = false

    private suspend fun getEngine(): JsSourceEngine {
        return createMutex.withLock {
            val runtime = engine ?: JsSourceEngine(
                runtimeJs = VeneraRuntime.get(context),
                sourceJs = script,
                sourceKey = sourceKey,
                context = context,
                insecureTls = insecureTls
            ).also { engine = it }
            if (!engineInitialized) {
                runtime.call("null")
                // Preserve the source's original headers for ordinary reading.
                // Only a completed WebView verification establishes a new UA-bound session.
                verificationPrefs.getString("${sourceKey}_user_agent", null)?.let { userAgent ->
                    applyVerifiedUserAgent(runtime, userAgent)
                }
                engineInitialized = true
            }
            runtime
        }
    }

    private suspend fun callJs(jsCall: String): JSONObject? {
        val raw = getEngine().call(jsCall) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 网络类错误（超时/断连/重置）自动重试一次，源站抽风时大幅提高成功率。 */
    private suspend fun callJsWithRetry(jsCall: String): JSONObject? {
        var result = callJs(jsCall)
        // These adapters already retry a complete GET through the other transport.
        if (sourceKey in setOf("pufei", "bilimanga")) return result
        val err = result?.optString("error", "") ?: ""
        val retryable = (sourceKey == "copy_manga" && result?.optBoolean("ok") == false) ||
            err.contains("timeout", ignoreCase = true) ||
            err.contains("timed out", ignoreCase = true) ||
            err.contains("ERR_CONNECTION", ignoreCase = true) ||
            err.contains("ERR_EMPTY_RESPONSE", ignoreCase = true) ||
            err.contains("ERR_RESPONSE_HEADERS_TRUNCATED", ignoreCase = true) ||
            err.contains("Connection reset", ignoreCase = true) ||
            err.contains("SocketException", ignoreCase = true) ||
            err.contains("connect", ignoreCase = true)
        if (retryable) {
            kotlinx.coroutines.delay(900)
            if (sourceKey == "copy_manga") {
                runCatching { callJs("src.refreshAppApi ? src.refreshAppApi() : null") }
            }
            result = callJs(jsCall)
        }
        return result
    }

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> =
        withContext(Dispatchers.IO) {
            try {
                var obj = callJsWithRetry("src.search.load.call(src, ${q(keyword)}, [], 1)")
                    ?: return@withContext SourceResult.Error(SourceException.ParseError("JS 源无响应"))
                // Copied circle/artist labels contain parenthesized aliases. Hitomi's
                // whitespace AND search treats those parentheses as literal index terms.
                if (sourceKey == "hitomi" && obj.optBoolean("ok") &&
                    obj.optJSONObject("data")?.optJSONArray("comics")?.length() == 0) {
                    val circle = keyword.trim().replace(Regex("\\s*[（(][^()（）]+[）)]\\s*$"), "").trim()
                    if (circle.isNotBlank() && circle != keyword.trim()) {
                        obj = callJsWithRetry("src.search.load.call(src, ${q(circle)}, [], 1)") ?: obj
                    }
                }
                if (!obj.optBoolean("ok")) {
                    val err = obj.optString("error", "JS 执行失败")
                    Log.w("JsComic[$sourceKey]", "call error: $err")
                    // 号码牌直达：源搜索接口报错（部分源对纯数字 ID 无搜索结果甚至报错）时，按 ID 直接加载详情
                    numericIdFallback(keyword)?.let {
                        return@withContext SourceResult.Success(listOf(it))
                    }
                    return@withContext SourceResult.Error(SourceException.ParseError(friendlyJsSourceError(err)))
                }
                val comics = obj.optJSONObject("data")?.optJSONArray("comics") ?: JSONArray()
                val books = (0 until comics.length()).mapNotNull { i ->
                    val c = comics.optJSONObject(i) ?: return@mapNotNull null
                    val id = c.optString("id")
                    val title = c.optString("title")
                    if (id.isBlank() || title.isBlank()) return@mapNotNull null
                    val metadata = JsComicMetadata.book(c, this@JsComicSource.id, id)
                    SearchBook(
                        id = id,
                        sourceId = this@JsComicSource.id,
                        title = title,
                        author = metadata.author,
                        cover = c.optString("cover", "").ifBlank { null },
                        description = c.optString("description", "").ifBlank { null },
                        language = c.optString("language", "").ifBlank { null },
                        comicId = extractComicId(c),
                        format = "漫画",
                        comicInfo = metadata.comicInfo,
                    )
                }
                // 号码牌直达：搜索结果为空且关键词是纯数字号牌时，按 ID 直接加载详情
                if (books.isEmpty()) {
                    numericIdFallback(keyword)?.let {
                        return@withContext SourceResult.Success(listOf(it))
                    }
                }
                SourceResult.Success(books)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("JsComic[$sourceKey]", "search failed", e)
                SourceResult.Error(SourceException.Unknown("JS 搜索失败: ${e.message}", e))
            }
        }

    /**
     * 号码牌直达：部分漫画源的搜索接口对纯数字 ID 无结果（原 App 里这类输入会直接跳转到
     * 漫画页面），这里用 comic.loadInfo 按号牌直接加载详情并作为单条搜索结果返回，
     * 点开即进入漫画页。仅当关键词是纯数字号牌（可带 # 前缀，1~15 位）时尝试一次；
     * 失败或源不支持则返回 null，维持原有搜索结果——正常关键词搜索永远不会走到这里。
     */
    private suspend fun numericIdFallback(keyword: String): SearchBook? {
        val numericId = keyword.trim().removePrefix("#")
        if (!numericId.matches(Regex("\\d{1,15}"))) return null
        return try {
            val obj = callJs("src.comic.loadInfo.call(src, ${q(numericId)})") ?: return null
            if (!obj.optBoolean("ok")) return null
            val data = obj.optJSONObject("data") ?: return null
            val title = data.optString("title").trim()
            if (title.isBlank()) return null
            Log.i("JsComic[$sourceKey]", "号码牌直达命中: $numericId → $title")
            SearchBook(
                id = numericId,
                sourceId = id,
                title = title,
                author = JsComicMetadata.book(data, id, numericId).author,
                cover = data.optString("cover").ifBlank { null },
                description = data.optString("description").ifBlank { null },
                comicId = numericId,
                format = "漫画",
                comicInfo = JsComicMetadata.book(data, id, numericId).comicInfo,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("JsComic[$sourceKey]", "号码牌直达尝试失败: ${e.message}")
            null
        }
    }

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> =
        withContext(Dispatchers.IO) {
            chapterLoadMutex.withLock {
                val now = android.os.SystemClock.elapsedRealtime()
                val (cached, epoch) = synchronized(chapterCacheMonitor) {
                    chapterCache[bookId]?.takeIf { now - it.savedAt < 60_000 } to chapterCacheEpoch
                }
                if (cached != null) return@withLock SourceResult.Success(cached.chapters)
                val result = loadChapters(bookId)
                if (result is SourceResult.Success && result.data.isNotEmpty() && result.data.size <= 10_000) {
                    synchronized(chapterCacheMonitor) {
                        if (epoch == chapterCacheEpoch) {
                            chapterCache[bookId] = CachedChapters(android.os.SystemClock.elapsedRealtime(), result.data)
                            while (chapterCache.size > 8 || chapterCache.values.sumOf { it.chapters.size } > 10_000) {
                                chapterCache.remove(chapterCache.keys.first())
                            }
                        }
                    }
                }
                result
            }
        }

    private suspend fun loadChapters(bookId: String): SourceResult<List<ComicChapter>> =
        withContext(Dispatchers.IO) {
            try {
                val detailEpoch = synchronized(chapterCacheMonitor) { chapterCacheEpoch }
                val obj = callJsWithRetry("src.comic.loadInfo.call(src, ${q(bookId)})")
                    ?: return@withContext SourceResult.Error(SourceException.ParseError("JS 源无响应"))
                if (!obj.optBoolean("ok")) {
                    val err = obj.optString("error", "JS 执行失败")
                    Log.w("JsComic[$sourceKey]", "call error: $err")
                    return@withContext SourceResult.Error(
                        if (err.contains("请先登录") || err.contains("Not logged in", ignoreCase = true) ||
                            err.contains("Invalid status code: 401")) SourceException.LoginRequired
                        else SourceException.ParseError(friendlyJsSourceError(err))
                    )
                }
                val data = obj.optJSONObject("data") ?: return@withContext SourceResult.Error(
                    SourceException.ParseError("JS 源无数据")
                )
                synchronized(chapterCacheMonitor) {
                    if (detailEpoch == chapterCacheEpoch) {
                        detailCache[bookId] = android.os.SystemClock.elapsedRealtime() to JsComicMetadata.book(data, id, bookId)
                        while (detailCache.size > 8) detailCache.remove(detailCache.keys.first())
                    }
                }
                val chapters = data.optJSONArray("chapters") ?: JSONArray()
                val list = (0 until chapters.length()).mapNotNull { i ->
                    val c = chapters.optJSONObject(i) ?: return@mapNotNull null
                    val epId = c.optString("id")
                    val title = c.optString("title")
                    if (epId.isBlank() || title.isBlank()) return@mapNotNull null
                    ComicChapter(
                        id = "$bookId\u0001$epId",
                        title = title,
                        volume = c.optString("group", "").ifBlank { null },
                        order = c.optDouble("order", 0.0).toFloat()
                    )
                }
                // 与 Venera 一致：单图集/画廊类源（nhentai/hitomi/ehentai 等）
                // loadInfo 不返回 chapters，App 会合成一个“整本阅读”章节
                if (list.isEmpty() && sourceKey in setOf("manhuaren", "goda", "comick", "bilimanga", "vomic")) {
                    SourceResult.Error(SourceException.ParseError("源站当前未提供这部作品的在线章节"))
                } else if (list.isEmpty()) {
                    SourceResult.Success(
                        listOf(
                            ComicChapter(
                                id = "$bookId\u0001",
                                title = data.optString("title").ifBlank { "开始阅读" },
                                order = 1f
                            )
                        )
                    )
                } else {
                    SourceResult.Success(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("JsComic[$sourceKey]", "chapters failed", e)
                SourceResult.Error(SourceException.Unknown("JS 章节加载失败: ${e.message}", e))
            }
        }

    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> =
        withContext(Dispatchers.IO) {
            val pair = splitChapterId(chapterId)
                ?: return@withContext SourceResult.Error(SourceException.ParseError("无效章节 ID"))
            try {
                val obj = callJsWithRetry("src.comic.loadEp.call(src, ${q(pair.first)}, ${q(pair.second)})")
                    ?: return@withContext SourceResult.Error(SourceException.ParseError("JS 源无响应"))
                if (!obj.optBoolean("ok")) {
                    val err = obj.optString("error", "JS 执行失败")
                    Log.w("JsComic[$sourceKey]", "call error: $err")
                    return@withContext SourceResult.Error(
                        if (err.contains("请先登录") || err.contains("Not logged in", ignoreCase = true) ||
                            err.contains("Invalid status code: 401")) SourceException.LoginRequired
                        else SourceException.ParseError(friendlyJsSourceError(err))
                    )
                }
                val images = obj.optJSONObject("data")?.optJSONArray("images") ?: JSONArray()
                val rawUrls = (0 until images.length()).mapNotNull { i ->
                    images.optString(i).ifBlank { null }
                }
                if (rawUrls.isEmpty()) {
                    SourceResult.Error(SourceException.ParseError("该章节没有可读取的图片"))
                } else if (sourceKey == "ehentai") {
                    // e-hentai 返回的是“图片页 URL”，真正的图片在阅读/下载时懒加载，
                    // 避免一次性逐页请求（大画廊会等几十秒）
                    SourceResult.Success(rawUrls)
                } else {
                    // 应用 onImageLoad 返回的重写 URL（部分源会替换图片域名/签名）
                    // 顺序解析：部分源（ehentai）需要把上一页的 nl 传给下一页才能取到真实图片 URL
                    val resolved = mutableListOf<String>()
                    var prevNl: String? = null
                    rawUrls.forEach { rawUrl ->
                        val config = resolveImageConfig(chapterId, rawUrl, prevNl)
                        resolved.add(config.finalUrl)
                        prevNl = config.nl
                    }
                    val pages = if (sourceKey == "mxs") stripLeadingMxsSpacers(resolved) { url ->
                        val headers = synchronized(imageConfigCache) {
                            imageConfigCache.values.firstOrNull { it.finalUrl == url }?.headers
                        }.orEmpty()
                        val cacheKey = com.example.ui.comic.comicRemoteCacheKey(url, headers)
                        val request = coil.request.ImageRequest.Builder(context)
                            .data(url).memoryCacheKey(cacheKey).diskCacheKey(cacheKey)
                            .size(com.example.ui.comic.ComicPageLoader.DECODE_MAX_EDGE)
                            .scale(coil.size.Scale.FIT).precision(coil.size.Precision.INEXACT)
                            .allowHardware(false)
                            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                            .build()
                        val result = com.example.ui.comicImageLoader(context).execute(request)
                        (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    } else resolved
                    if (pages.isEmpty()) SourceResult.Error(SourceException.ParseError("该章仅包含空白图片"))
                    else SourceResult.Success(pages)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("JsComic[$sourceKey]", "images failed", e)
                SourceResult.Error(SourceException.Unknown("JS 图片加载失败: ${e.message}", e))
            }
        }

    override suspend fun getChapterImageHeaders(
        chapterId: String,
        urls: List<String>
    ): Map<String, Map<String, String>> {
        val result = HashMap<String, Map<String, String>>()
        if (sourceKey == "ehentai") {
            urls.forEach { url ->
                result[url] = mapOf("Referer" to "https://e-hentai.org/")
            }
            return result
        }
        if (sourceKey == "goda") {
            urls.forEach { url ->
                result[url] = mapOf("Referer" to "https://godamh.com/", "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/108.0.5359.128 Mobile Safari/537.36")
            }
            return result
        }
        if (sourceKey == "wnacg") {
            val pair = splitChapterId(chapterId)
            val galleryId = pair?.second?.ifBlank { pair.first }.orEmpty()
            val baseUrl = callJs("src.baseUrl")?.optString("data").orEmpty()
                .ifBlank { "https://www.wn001.cfd" }
            val referer = "$baseUrl/photos-gallery-aid-$galleryId.html"
            urls.forEach { url ->
                result[url] = mapOf("Referer" to referer, "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/108.0.5359.128 Mobile Safari/537.36")
            }
            return result
        }
        urls.forEach { url ->
            val config = synchronized(imageConfigCache) {
                imageConfigCache.values.firstOrNull { it.finalUrl == url }
            }
            val headers = config?.headers ?: resolveImageConfig(chapterId, url).headers
            if (headers.isNotEmpty()) result[url] = headers
        }
        return result
    }

    override suspend fun resolveChapterImage(url: String): String? = withContext(Dispatchers.IO) {
        if (sourceKey != "ehentai") return@withContext null
        resolveEhentaiImage(url, 0)
    }

    private suspend fun resolveEhentaiImage(url: String, attempt: Int): String? {
        val config = resolveImageConfig("$url\u0001", url)
        val realUrl = config.finalUrl
        if (realUrl == url) {
            // 图片页解析失败（代理抽风/超时），清掉缓存重试拿新链接
            if (attempt < 3) {
                synchronized(imageConfigCache) { imageConfigCache.remove(url) }
                delay(700L * (attempt + 1))
                return resolveEhentaiImage(url, attempt + 1)
            }
            return null
        }
        if (!realUrl.contains("hath.network")) return realUrl
        // H@H 图床对 OkHttp 握手不友好，改用 Cronet 下载并缓存到本地，阅读器直接加载本地文件
        val cacheDir = File(context.cacheDir, "ehimg").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${realUrl.hashCode().toUInt().toString(16)}.img")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val headers = config.headers + mapOf("Referer" to "https://e-hentai.org/")
            val bytes = getEngine().fetchImageBytes(realUrl, headers)
            if (bytes != null && bytes.isNotEmpty()) {
                runCatching { cacheFile.writeBytes(bytes) }
            }
            if ((cacheFile.length() == 0L || !cacheFile.exists()) && attempt < 3) {
                // keystamp 可能过期/代理抽风，重新解析一次拿到新图片链接再下载
                synchronized(imageConfigCache) { imageConfigCache.remove(url) }
                delay(800L * (attempt + 1))
                return resolveEhentaiImage(url, attempt + 1)
            }
        }
        return if (cacheFile.exists() && cacheFile.length() > 0L) {
            android.net.Uri.fromFile(cacheFile).toString()
        } else {
            realUrl
        }
    }

    override suspend fun getResolvedHeaders(url: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (sourceKey != "ehentai") return@withContext emptyMap()
            synchronized(imageConfigCache) {
                imageConfigCache[url]?.headers
                    ?: imageConfigCache.values.firstOrNull { it.finalUrl == url }?.headers
            } ?: emptyMap()
        }

    private suspend fun resolveImageConfig(
        chapterId: String,
        url: String,
        prevNl: String? = null
    ): ImageConfig {
        synchronized(imageConfigCache) {
            imageConfigCache[url]?.let { return it }
        }
        val pair = splitChapterId(chapterId)
        val fallback = ImageConfig(url, url, emptyMap(), null, null)
        val config = if (pair == null) fallback else try {
            val nlArg = prevNl?.let { ", ${q(it)}" } ?: ""
            val obj = callJsWithRetry(
                "(src.comic.onImageLoad ? src.comic.onImageLoad.call(src, ${q(url)}, ${q(pair.first)}, ${q(pair.second)}$nlArg) : null)"
            )
            if (obj?.optBoolean("ok") != true) fallback else {
                val data = obj.optJSONObject("data")
                if (data == null) fallback else {
                    val merged = LinkedHashMap<String, String>()
                    data.optJSONObject("headers")?.keys()?.forEach { k ->
                        merged[k] = data.optJSONObject("headers")!!.optString(k)
                    }
                    data.optString("referer").ifBlank { null }?.let {
                        merged.putIfAbsent("Referer", it)
                    }
                    ImageConfig(
                        originalUrl = url,
                        finalUrl = data.optString("url")
                            .takeIf { it.startsWith("http") || it.startsWith("//") }
                            ?.let { if (it.startsWith("//")) "https:$it" else it }
                            ?: url,
                        headers = merged,
                        modifyCode = data.optString("modifyImage").ifBlank { null },
                        nl = data.optString("nl").ifBlank { null }
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
        synchronized(imageConfigCache) { imageConfigCache[url] = config }
        if (config.modifyCode != null) {
            Log.i("JsImageProcessor", "register ${config.finalUrl.take(80)} code=${config.modifyCode.take(40).replace('\n', ' ')}")
            JsImageProcessor.register(config.finalUrl, getEngine(), config.modifyCode)
        } else {
            JsImageProcessor.unregister(config.finalUrl)
        }
        return config
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> {
        fun cached(): SearchBook? = synchronized(chapterCacheMonitor) {
            detailCache[bookId]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < 60_000 }?.second
        }
        cached()?.let { return SourceResult.Success(it) }
        // loadInfo supplies both metadata and chapters; getChapters serializes/caches the shared request.
        val result = getChapters(bookId)
        cached()?.let { return SourceResult.Success(it) }
        return if (result is SourceResult.Error) result
        else SourceResult.Error(SourceException.ParseError("源站未返回作品详情"))
    }

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> {
        return SourceResult.Error(SourceException.ParseError("JS 漫画源请通过章节下载"))
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                if (sourceKey == "mycomic") {
                    var supplied = credential.cookie.orEmpty().trim()
                    if (supplied.isBlank()) {
                        // 未粘贴 Cookie 时自动过盾：无头 WebView 加载 mycomic.com 让
                        // Cloudflare JS 挑战自行执行，cf_clearance 同步进共享 Cookie 存储
                        val solved = runCatching {
                            CfWebViewSolver.solveAndSync(
                                context,
                                "https://mycomic.com/",
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
                            )
                        }.getOrDefault(false)
                        if (solved) {
                            supplied = JsCookieJar.cookieHeader(context, "https://mycomic.com/")
                                .split(';')
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("cf_clearance=") }
                                .orEmpty()
                                .removePrefix("cf_clearance=")
                        }
                    }
                    val clearance = supplied
                        .ifBlank { credential.cookie.orEmpty() }
                        .substringAfter("cf_clearance=", supplied)
                        .substringBefore(';').trim()
                    if (clearance.isBlank()) {
                        return@withContext SourceResult.Error(
                            SourceException.Unknown(
                                "自动过 Cloudflare 盾失败：请先用浏览器打开 mycomic.com 过盾，" +
                                    "在 DevTools→Cookies 复制 cf_clearance 值粘贴到 Cookie 输入框"
                            )
                        )
                    }
                    val cookieResult = callJs(
                        "src.account.loginWithCookies.validate.call(src, [${q(clearance)}])"
                    )
                    if (cookieResult?.optBoolean("ok") == true && cookieResult?.optBoolean("data") == true) {
                        getEngine().setLoggedIn(true)
                        invalidateChapterCache()
                        return@withContext SourceResult.Success(true)
                    }
                    return@withContext SourceResult.Error(
                        SourceException.Unknown("Cloudflare 验证未通过，请更新 cf_clearance 后重试")
                    )
                }
                val obj = callJs(
                    """src.account && typeof src.account.login === 'function' ? src.account.login.call(src, ${q(credential.username)}, ${q(credential.password)}) : Promise.resolve(false)"""
                )
                if (obj?.optBoolean("ok") == true && obj?.optString("data") == "ok") {
                    getEngine().setLoggedIn(true)
                    invalidateChapterCache()
                    SourceResult.Success(true)
                } else {
                    val raw = obj?.optString("error", "") ?: ""
                    Log.w("JsComic[$sourceKey]", "login error: $raw")
                    val msg = when {
                        raw.isBlank() -> "登录失败：账号或密码错误"
                        raw.contains("Invalid email", ignoreCase = true) ||
                            raw.contains("invalid password", ignoreCase = true) -> "登录失败：账号或密码错误"
                        raw.contains("ERR_", ignoreCase = true) ||
                            raw.contains("timed out", ignoreCase = true) ||
                            raw.contains("timeout", ignoreCase = true) ||
                            raw.contains("请求超时") ->
                            "登录失败：网络无法连接该源（该源被墙，请开系统代理或 VPN）"
                        else -> "登录失败：${friendlyJsSourceError(raw)}"
                    }
                    SourceResult.Error(SourceException.Unknown(msg))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceResult.Error(SourceException.Unknown("登录失败: ${e.message}", e))
            }
        }

    suspend fun verifyWebsite(): SourceResult<Boolean> {
        val url = when (sourceKey) {
            "mycomic" -> "https://mycomic.com/cn"
            "nhentai" -> "https://nhentai.net/"
            else -> return SourceResult.Error(SourceException.Unknown("该源不支持此验证入口"))
        }
        return try {
            val runtime = getEngine()
            val userAgent = android.webkit.WebSettings.getDefaultUserAgent(context)
            val verified = awaitWebsiteVerification(url, userAgent)
            if (!verified) SourceResult.Error(SourceException.Unknown("站点验证尚未完成"))
            else {
                if (sourceKey == "mycomic") {
                    applyVerifiedUserAgent(runtime, userAgent)
                    verificationPrefs.edit().putString("${sourceKey}_user_agent", userAgent).apply()
                }
                invalidateChapterCache()
                SourceResult.Success(true)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { SourceResult.Error(SourceException.Unknown(e.message ?: "站点验证失败", e)) }
    }

    /**
     * 站内注册（vomic 等支持 account.register 的源）：
     * JS 侧驱动弹窗链（邮箱→验证码→昵称→密码→注册→自动登录），
     * 注册成功自动登录后返回 Success。
     */
    suspend fun register(): SourceResult<Boolean> {
        return try {
            val obj = callJs(
                "src.account && typeof src.account.register === 'function' ? src.account.register.call(src) : Promise.reject(new Error('该源不支持站内注册'))"
            )
            if (obj?.optBoolean("ok") == true && obj?.optString("data") == "ok") {
                getEngine().setLoggedIn(true)
                invalidateChapterCache()
                SourceResult.Success(true)
            } else {
                val raw = obj?.optString("error", "") ?: ""
                SourceResult.Error(SourceException.Unknown(raw.ifBlank { "注册失败" }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceResult.Error(SourceException.Unknown("注册失败: ${e.message}", e))
        }
    }

    override suspend fun logout() {
        runCatching {
            callJs("src.account && typeof src.account.logout === 'function' ? src.account.logout.call(src) : null")
        }
        runCatching { getEngine().setLoggedIn(false) }
        invalidateChapterCache()
    }

    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        runCatching { getEngine().isLoggedIn() }.getOrDefault(false)
    }

    override suspend fun getCoverHeaders(url: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val obj = callJs(
                    """src.comic && typeof src.comic.onThumbnailLoad === 'function' ? src.comic.onThumbnailLoad.call(src, ${q(url)}) : null"""
                )
                if (obj?.optBoolean("ok") == true) {
                    val data = obj.optJSONObject("data") ?: return@withContext emptyMap()
                    val merged = LinkedHashMap<String, String>()
                    data.optJSONObject("headers")?.keys()?.forEach { k ->
                        merged[k] = data.optJSONObject("headers")!!.optString(k)
                    }
                    data.optString("referer").ifBlank { null }?.let {
                        merged.putIfAbsent("Referer", it)
                    }
                    merged
                } else {
                    emptyMap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }

    private fun splitChapterId(chapterId: String): Pair<String, String>? {
        val sep = chapterId.indexOf('\u0001')
        if (sep <= 0) return null
        return chapterId.substring(0, sep) to chapterId.substring(sep + 1)
    }

    private fun q(value: String): String = org.json.JSONObject.quote(value)

    /** 兼容数组/对象形式的作者字段（jm 的 author 是数组，picacg 等为字符串）。 */
    private fun JSONObject.optStringOrJoin(key: String): String = when (val v = opt(key)) {
        is String -> v.trim()
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            v.optString(i).trim().ifBlank { null }
        }.distinct().joinToString("、")
        is JSONObject -> v.optString("name", "").trim()
        else -> ""
    }

    /** 从 tags 中提取 artist/author/cosplayer/group 作为作者兜底。 */
    private fun extractAuthorFromTags(tags: JSONArray?): String {
        if (tags == null) return ""
        val hits = mutableListOf<String>()
        for (i in 0 until tags.length()) {
            val t = tags.optString(i)
            val lower = t.lowercase()
            val hit = when {
                lower.startsWith("artist:") -> t.substringAfter(':').trim()
                lower.startsWith("author:") -> t.substringAfter(':').trim()
                lower.startsWith("cosplayer:") -> t.substringAfter(':').trim()
                lower.startsWith("group:") -> t.substringAfter(':').trim()
                else -> null
            }
            if (!hit.isNullOrBlank()) hits.add(hit)
        }
        return hits.distinct().joinToString("、")
    }

    /** 提取作品编号（jm 号 / ehentai gid / nhentai id 等），方便用户按号码搜索。 */
    private fun extractComicId(c: JSONObject): String? {
        val id = c.optString("id").trim()
        if (id.isBlank()) return null
        val patterns = listOf(
            Regex("""/g/(\d+)/"""),
            Regex("""album[=/](\d+)"""),
            Regex("""/(\d+)/?$""")
        )
        for (re in patterns) {
            re.find(id)?.groupValues?.getOrNull(1)?.let { return it }
        }
        if (id.all(Char::isDigit)) return id
        return null
    }
}

/** 缓存 Venera 运行时脚本，避免每个源重复读 assets。 */
internal object VeneraRuntime {
    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.assets.open("venera/_venera_.js")
                .bufferedReader()
                .use { it.readText() }
            cached = text
            return text
        }
    }
}
