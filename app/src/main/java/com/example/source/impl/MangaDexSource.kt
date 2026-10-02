package com.example.source.impl

import android.content.Context
import android.util.Log
import com.example.source.AuthenticationState
import com.example.source.ComicChapter
import com.example.source.ComicInfo
import com.example.source.ComicSource
import com.example.source.DownloadInfo
import com.example.source.LoginCredential
import com.example.source.SearchBook
import com.example.source.SourceCapabilities
import com.example.source.SourceException
import com.example.source.SourceResult
import com.example.source.js.JsSourceProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MangaDex comic source backed by the mangadex.live mirror.
 *
 * The official MangaDex API is blocked on many mainland networks and many
 * chapters are "external" without hosted images. mangadex.live mirrors the
 * full catalog with direct CDN image URLs (t.imoutcl.sbs), which is reachable
 * from the phone, and every chapter has readable pages - including chapters
 * that are external on the official API.
 */
class MangaDexSource(
    private val client: OkHttpClient = defaultClient,
    context: Context? = null
) : ComicSource {

    private val http = context?.let {
        JsSourceProxy.failoverClient(it.applicationContext, client)
    } ?: client

    override val id: String = "mangadex"
    override val name: String = "MangaDex 漫画"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = false,
        supportComic = true,
        environmentOnly = false
    )

    companion object {
        private const val TAG = "MangaDex"
        private const val BASE = "https://mangadex.live"
        private const val REFERER = "https://mangadex.live/"
        /** 第九轮：官方 API（title 搜索覆盖 altTitles 中文别名；镜像站搜索只匹配主标题） */
        private const val OFFICIAL_API = "https://api.mangadex.org"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val defaultClient = com.example.source.SharedHttpTransport.builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> =
        withContext(Dispatchers.IO) {
            try {
                // 第九轮补强：镜像站（mangadex.live）的站内搜索只匹配罗马音/英文主标题
                // ——实测 航海王/海贼王/无职転生 均为 0 结果，而官方 API 的 title 搜索
                // 覆盖 altTitles（含中文别名，航海王→One Piece 命中）。两条路径合并：
                // 镜像结果直接可用（章节/图链路成熟），官方 API 结果带 mdapi: 前缀 id，
                // 详情/章节/图走官方 API 专用实现。
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val mirrorRequest = async { runCatching { searchMirror(encoded) } }
                val officialRequest = async { searchOfficialApi(keyword) }
                val mirrorResult = mirrorRequest.await()
                val mirror = mirrorResult.getOrDefault(emptyList())
                val official = officialRequest.await()
                if (mirrorResult.isFailure && official.isEmpty()) {
                    throw SourceException.NetworkError(
                        "MangaDex 搜索服务暂时不可用: ${mirrorResult.exceptionOrNull()?.message}"
                    )
                }
                // 同名作品优先使用官方 ID，避免镜像搜索能命中但详情页已失效。
                val merged = LinkedHashMap<String, SearchBook>()
                mirror.forEach { merged[it.id] = it }
                official.forEach { api ->
                    val apiMainTitle = officialUuid(api.id)?.let { uuid ->
                        synchronized(officialTitleCache) { officialTitleCache[uuid] }
                    }
                    val mirrorMatch = mirror.firstOrNull {
                        it.title.equals(api.title, ignoreCase = true) ||
                            normalizeTitle(it.title) == normalizeTitle(api.title) ||
                            (apiMainTitle != null &&
                                normalizeTitle(it.title) == normalizeTitle(apiMainTitle))
                    }
                    if (mirrorMatch != null) {
                        synchronized(mirrorToOfficialCache) {
                            mirrorToOfficialCache[mirrorMatch.id] = api.id
                            if (mirrorToOfficialCache.size > 64) {
                                mirrorToOfficialCache.remove(mirrorToOfficialCache.keys.first())
                            }
                        }
                        merged[mirrorMatch.id] = api
                    } else {
                        merged[api.id] = api
                    }
                }
                val books = merged.values.toList()
                Log.i(TAG, "search '$keyword' -> mirror ${mirror.size} + official ${official.size} = ${books.size} books")
                SourceResult.Success(books)
            } catch (e: SourceException) {
                Log.e(TAG, "search failed: ${e.message}", e)
                SourceResult.Error(e)
            } catch (e: IOException) {
                Log.e(TAG, "search io error: ${e.message}", e)
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "search unexpected: ${e.message}", e)
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }

    private fun normalizeTitle(t: String): String =
        t.lowercase().replace(Regex("[\\s＆&！!？?，,。·・:：\\-—～~]"), "")

    /** 镜像站 HTML 搜索（原实现抽出，行为不变） */
    private fun searchMirror(encoded: String): List<SearchBook> {
        val doc = Jsoup.parse(getHtml("$BASE/search?q=$encoded"))
        val books = mutableListOf<SearchBook>()
        for (unit in doc.select("div.unit")) {
            val link = unit.selectFirst("a[href^=/manga/]") ?: continue
            val slug = link.attr("href").trim('/').removePrefix("manga/")
            if (slug.isBlank()) continue
            val title = link.attr("title").ifBlank { link.text() }.trim()
            val img = unit.selectFirst("img[src]") ?: unit.selectFirst("img[data-src]")
            val cover = img?.let {
                (it.attr("src").ifBlank { it.attr("data-src") }).trim()
            }.orEmpty()
            val langCode = unit.selectFirst(".content li a[href^=/read/]")?.attr("href")
                ?.trim('/')?.split('/')?.getOrNull(2)
            books.add(
                SearchBook(
                    id = slug,
                    sourceId = id,
                    title = title.ifBlank { "未知书名" },
                    author = "",
                    cover = cover.ifBlank { null }?.replace(".256.jpg", ".512.jpg"),
                    format = "漫画",
                    language = langCode?.let { languageLabel(it) }
                )
            )
        }
        return books.distinctBy { it.id }
    }

    /**
     * 官方 API 搜索（api.mangadex.org）：title 参数覆盖 altTitles，
     * 中文别名（航海王/海贼王/无职转生…）可直接命中。任何失败静默返回空——
     * 官方 API 在部分网络不可达（注释见类头），此时镜像路径独立支撑搜索。
     * 结果 id 带 "mdapi:" 前缀，getDetail/getChapters/getChapterImages 据此分派。
     */
    private fun searchOfficialApi(keyword: String): List<SearchBook> = runCatching {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$OFFICIAL_API/manga?title=$encoded&limit=12" +
            "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive" +
            "&includes%5B%5D=cover_art&includes%5B%5D=author&includes%5B%5D=artist&order%5Brelevance%5D=desc"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "official api search HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SearchBook>(data.length())
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val uuid = item.optString("id")
                if (uuid.isBlank()) continue
                val attrs = item.optJSONObject("attributes") ?: continue
                val titleObj = attrs.optJSONObject("title")
                val mainTitle = titleObj?.let { t ->
                    listOf("en", "ja-ro", "ja", "zh-hans", "zh").firstNotNullOfOrNull { k ->
                        t.optString(k, "").takeIf { it.isNotBlank() }
                    }
                } ?: ""
                // altTitles 里取最优展示标题（优先中文，其次英文，再取第一个）
                val altList = attrs.optJSONArray("altTitles")
                var zhAlt: String? = null
                var firstAlt: String? = null
                if (altList != null) {
                    for (k in 0 until altList.length()) {
                        val alt = altList.optJSONObject(k) ?: continue
                        val zh = alt.optString("zh-hans", "").ifBlank { alt.optString("zh", "") }
                        if (zh.isNotBlank() && zhAlt == null) zhAlt = zh
                        if (firstAlt == null) {
                            for (keyIdx in 0 until alt.names().length()) {
                                val keyName = alt.names().getString(keyIdx)
                                val v = alt.optString(keyName, "").takeIf { it.isNotBlank() }
                                if (v != null) {
                                    firstAlt = v
                                    break
                                }
                            }
                        }
                    }
                }
                val displayTitle = listOf(zhAlt, mainTitle, firstAlt)
                    .firstOrNull { !it.isNullOrBlank() } ?: "未知书名"
                // cover：includes[] 里的 cover_art 关系 → 文件名拼官方 CDN URL
                var cover: String? = null
                val rels = item.optJSONArray("relationships")
                if (rels != null) {
                    for (k in 0 until rels.length()) {
                        val rel = rels.optJSONObject(k) ?: continue
                        if (rel.optString("type") == "cover_art") {
                            val fileName = rel.optJSONObject("attributes")?.optString("fileName")
                            if (!fileName.isNullOrBlank()) {
                                cover = "https://uploads.mangadex.org/covers/$uuid/$fileName.512.jpg"
                            }
                            break
                        }
                    }
                }
                // 记录 uuid→主标题（罗马音/英文），供官方章节无图时回退镜像搜索
                if (mainTitle.isNotBlank()) {
                    synchronized(officialTitleCache) {
                        officialTitleCache[uuid] = mainTitle
                        if (officialTitleCache.size > 64) {
                            officialTitleCache.remove(officialTitleCache.keys.first())
                        }
                    }
                }
                out.add(
                    SearchBook(
                        id = "mdapi:$uuid",
                        sourceId = id,
                        title = displayTitle,
                        author = officialCreators(item, "author").joinToString("、"),
                        cover = cover,
                        format = "漫画",
                        comicInfo = officialInfo(attrs).copy(artists = officialCreators(item, "artist")),
                        description = officialText(attrs.optJSONObject("description")),
                    )
                )
            }
            out
        }
    }.getOrDefault(emptyList())

    /** id 是否为官方 API 条目（"mdapi:<uuid>"） */
    private fun officialText(values: JSONObject?): String? = values?.let { obj ->
        listOf("zh-hans", "zh", "zh-hk", "en", "ja").firstNotNullOfOrNull { key ->
            obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        } ?: obj.keys().asSequence().map { obj.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
    }

    private fun officialCreators(item: JSONObject, type: String): List<String> {
        val relationships = item.optJSONArray("relationships") ?: return emptyList()
        return (0 until relationships.length()).mapNotNull { i ->
            relationships.optJSONObject(i)?.takeIf { it.optString("type") == type }
                ?.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun officialInfo(attrs: JSONObject?): ComicInfo {
        if (attrs == null) return ComicInfo()
        val titles = mutableListOf<String>()
        fun collect(obj: JSONObject?) { obj?.keys()?.forEach { key ->
            obj.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(titles::add)
        } }
        collect(attrs.optJSONObject("title"))
        attrs.optJSONArray("altTitles")?.let { array ->
            for (i in 0 until array.length()) collect(array.optJSONObject(i))
        }
        val tags = attrs.optJSONArray("tags")
        return ComicInfo(
            alternateTitles = titles.distinct(),
            tags = if (tags == null) emptyList() else (0 until tags.length()).mapNotNull { i ->
                officialText(tags.optJSONObject(i)?.optJSONObject("attributes")?.optJSONObject("name"))
            },
            status = attrs.optString("status").ifBlank { null },
            originalLanguage = attrs.optString("originalLanguage").ifBlank { null },
            updatedAt = attrs.optString("updatedAt").ifBlank { null },
        )
    }

    private fun officialUuid(bookId: String): String? =
        if (bookId.startsWith("mdapi:")) bookId.removePrefix("mdapi:") else null

    /** 官方条目 uuid → 罗马音/英文主标题（章节无图时按标题回退镜像搜索的桥接表）。
     *  进程内会话缓存，容量极小（近次搜索结果量级）。 */
    private val officialTitleCache = LinkedHashMap<String, String>()
    private val mirrorToOfficialCache = LinkedHashMap<String, String>()

    /** uuid → 镜像站 slug：外链章节回退镜像时避免每章重复"取标题→搜索→匹配"。 */
    private val mirrorSlugCache = LinkedHashMap<String, String>()

    /**
     * 定位作品在镜像站的 slug：先查缓存；未命中时按主标题搜索，
     * 只接受归一化后相同的标题，防止同名角色的同人作品替代原作。
     */
    private fun mirrorSlugFor(uuid: String): String? {
        synchronized(mirrorSlugCache) { mirrorSlugCache[uuid] }?.let { return it }
        val mainTitle = synchronized(officialTitleCache) { officialTitleCache[uuid] }
            ?: apiGet("$OFFICIAL_API/manga/$uuid")?.optJSONObject("data")
                ?.optJSONObject("attributes")?.optJSONObject("title")?.let { titles ->
                    listOf("en", "ja-ro", "ja", "zh-hans", "zh")
                        .firstNotNullOfOrNull { key ->
                            titles.optString(key, "").takeIf { it.isNotBlank() }
                        }
                }
            ?: return null
        val candidates = runCatching { searchMirror(URLEncoder.encode(mainTitle, "UTF-8")) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && !it.id.startsWith("mdapi:") }
        val target = normalizeTitle(mainTitle)
        val slug = candidates.firstOrNull { normalizeTitle(it.title) == target }?.id ?: return null
        synchronized(mirrorSlugCache) {
            mirrorSlugCache[uuid] = slug
            if (mirrorSlugCache.size > 32) mirrorSlugCache.remove(mirrorSlugCache.keys.first())
        }
        return slug
    }

    private fun apiGet(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let { JSONObject(it) }
        }
    }.getOrNull()

    /** 官方章节 id 直接交给 at-home API；章节列表已提供这个 id。 */
    private fun officialChapterImages(chapterId: String): List<String> {
        val atHome = apiGet("$OFFICIAL_API/at-home/server/$chapterId") ?: return emptyList()
        val baseUrl = atHome.optString("baseUrl")
        val chapter = atHome.optJSONObject("chapter") ?: return emptyList()
        val hash = chapter.optString("hash")
        val pages = chapter.optJSONArray("data") ?: return emptyList()
        if (baseUrl.isBlank() || hash.isBlank()) return emptyList()
        val out = ArrayList<String>(pages.length())
        for (i in 0 until pages.length()) {
            val p = pages.optString(i, "")
            if (p.isNotBlank()) out.add("$baseUrl/data/$hash/$p")
        }
        return out
    }

    private fun officialImagesAvailable(images: List<String>): Boolean {
        val first = images.firstOrNull() ?: return false
        // The at-home API can list files that the image server already returns 404
        // for. Read only a small range before accepting those URLs as a chapter.
        return runCatching {
            val request = Request.Builder().url(first).header("User-Agent", UA)
                .header("Range", "bytes=0-63").build()
            http.newCall(request).execute().use { response ->
                response.isSuccessful && response.peekBody(64).bytes().isNotEmpty()
            }
        }.getOrDefault(false)
    }

    /**
     * 第九轮回退：官方章节无托管图（externalLink 除外链）时，
     * 用 uuid→主标题桥接表在镜像站搜同作品，再在镜像章节列表里找同号章节，
     * 最后走镜像 read 页拿直链图。任何一步失败都静默返回空。
     */
    private fun mirrorFallbackImages(uuid: String, chapterNum: Float): List<String> = runCatching {
        val slug = mirrorSlugFor(uuid) ?: return emptyList()
        // 拉镜像章节列表，找同号
        val doc = Jsoup.parse(getHtml("$BASE/manga/$slug"))
        val blocks = doc.select("ul.chapter-list-item")
        var readPath: String? = null
        val preferred = listOf("ZH", "ZH-HK", "ZH-CN", "EN")
        for (block in blocks.sortedBy { b ->
            val idx = preferred.indexOf(b.attr("data-code").uppercase())
            if (idx >= 0) idx else 10
        }) {
            val code = block.attr("data-code").uppercase()
            val lang = code.lowercase()
            val hit = block.select("li.item[data-number]").firstOrNull {
                it.attr("data-number").trim().toFloatOrNull() == chapterNum
            }?.selectFirst("a[href^=/read/]")?.attr("href")?.trim('/')
            if (!hit.isNullOrBlank()) {
                readPath = hit
                break
            }
            var offset = block.attr("data-offset").toIntOrNull() ?: 0
            var hasMore = block.attr("data-has-more") == "true"
            var guard = 0
            while (hasMore && guard < 40) {
                val batchDoc = Jsoup.parse(
                    getHtml("$BASE/manga/$slug/chapters?lang=$code&offset=$offset", xhr = true)
                )
                val meta = batchDoc.selectFirst("span.chapter-batch-meta")
                val hit2 = batchDoc.select("li.item[data-number]").firstOrNull {
                    it.attr("data-number").trim().toFloatOrNull() == chapterNum
                }?.selectFirst("a[href^=/read/]")?.attr("href")?.trim('/')
                if (!hit2.isNullOrBlank()) {
                    readPath = hit2
                    break
                }
                hasMore = meta?.attr("data-has-more") == "true"
                offset = meta?.attr("data-next-offset")?.toIntOrNull() ?: (offset + 1)
                guard++
            }
            if (readPath != null) break
        }
        if (readPath.isNullOrBlank()) return emptyList()
        val pageDoc = Jsoup.parse(getHtml("$BASE/$readPath", referer = true))
        pageDoc.select("img[src]").mapNotNull { img ->
            val src = img.attr("src").trim()
            if (src.startsWith("http") && src.contains("/chapter/")) src else null
        }
    }.getOrDefault(emptyList())

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> =
        withContext(Dispatchers.IO) {
            try {
                // 第九轮：官方 API 条目（mdapi:<uuid>）走官方详情
                val officialId = officialUuid(bookId)
                if (officialId != null) {
                    val uuid = officialId
                    val json = apiGet(
                        "$OFFICIAL_API/manga/$uuid?includes%5B%5D=cover_art&includes%5B%5D=author&includes%5B%5D=artist"
                    ) ?: return@withContext SourceResult.Error(
                        SourceException.NetworkError("MangaDex 官方 API 不可达")
                    )
                    val item = json.optJSONObject("data")
                        ?: return@withContext SourceResult.Error(SourceException.ParseError("作品不存在"))
                    val attrs = item.optJSONObject("attributes")
                    val titleObj = attrs?.optJSONObject("title")
                    val title = titleObj?.let { t ->
                        listOf("en", "ja-ro", "ja", "zh-hans", "zh")
                            .firstNotNullOfOrNull { k -> t.optString(k, "").takeIf { it.isNotBlank() } }
                    } ?: uuid
                    var cover: String? = null
                    val rels = item.optJSONArray("relationships")
                    if (rels != null) {
                        for (k in 0 until rels.length()) {
                            val rel = rels.optJSONObject(k) ?: continue
                            if (rel.optString("type") == "cover_art") {
                                val fileName = rel.optJSONObject("attributes")?.optString("fileName")
                                if (!fileName.isNullOrBlank()) {
                                    cover = "https://uploads.mangadex.org/covers/$uuid/$fileName.512.jpg"
                                }
                                break
                            }
                        }
                    }
                    return@withContext SourceResult.Success(
                        SearchBook(
                            id = bookId,
                            sourceId = id,
                            title = title,
                            author = officialCreators(item, "author").joinToString("、"),
                            cover = cover,
                            format = "漫画",
                            description = officialText(attrs?.optJSONObject("description")),
                            comicInfo = officialInfo(attrs).copy(artists = officialCreators(item, "artist")),
                        )
                    )
                }
                val mirrorHtml = runCatching { getHtml("$BASE/manga/$bookId") }
                val officialFallback = synchronized(mirrorToOfficialCache) {
                    mirrorToOfficialCache[bookId]
                }
                if (mirrorHtml.isFailure && officialFallback != null) {
                    return@withContext getDetail(officialFallback)
                }
                val doc = Jsoup.parse(mirrorHtml.getOrThrow())
                val title = doc.selectFirst("h1")?.text()?.trim()
                    ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                    ?: bookId
                val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                    ?: doc.selectFirst("img[src*=cover]")?.attr("src")?.trim()
                val author = parseJsonLdAuthor(doc)
                SourceResult.Success(
                    SearchBook(
                        id = bookId,
                        sourceId = id,
                        title = title,
                        author = author,
                        description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() },
                        cover = cover?.ifBlank { null }?.replace(".256.jpg", ".512.jpg"),
                        format = "漫画"
                    )
                )
            } catch (e: SourceException) {
                Log.e(TAG, "detail failed: ${e.message}", e)
                SourceResult.Error(e)
            } catch (e: IOException) {
                Log.e(TAG, "detail io error: ${e.message}", e)
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "detail unexpected: ${e.message}", e)
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> =
        withContext(Dispatchers.IO) {
            try {
                // 第九轮：官方 API 条目——章节列表按官方 feed 拉取（中→英优先）
                val officialId = officialUuid(bookId)
                if (officialId != null) {
                    val chapters = ArrayList<ComicChapter>()
                    val bestByNumber = LinkedHashMap<Float, Pair<Int, ComicChapter>>()
                    var offset = 0
                    val pageSize = 500
                    var numberedCount = 0
                    var externalCount = 0
                    do {
                        val feed = apiGet(
                            "$OFFICIAL_API/manga/$officialId/feed?translatedLanguage%5B%5D=zh" +
                                "&translatedLanguage%5B%5D=zh-hk" +
                                "&translatedLanguage%5B%5D=en" +
                                "&order%5Bchapter%5D=asc&limit=$pageSize&offset=$offset" +
                                "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive" +
                                "&contentRating%5B%5D=erotica"
                        ) ?: return@withContext SourceResult.Error(
                            SourceException.NetworkError("MangaDex 官方 API 不可达")
                        )
                        val data = feed.optJSONArray("data") ?: break
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val attrs = item.optJSONObject("attributes") ?: continue
                            val chapterId = item.optString("id")
                            val num = attrs.optDouble("chapter", Double.NaN)
                            if (chapterId.isBlank()) continue
                            val numbered = !num.isNaN()
                            val key = if (numbered) num.toFloat() else (offset + i).toFloat()
                            val label = if (!numbered) {
                                "单话"
                            } else if (key == key.toLong().toFloat()) {
                                "第${key.toLong()}话"
                            } else {
                                "第$key 话"
                            }
                            val chapter = ComicChapter(
                                id = "mdapich:$officialId:$chapterId:$key",
                                title = attrs.optString("title", "").ifBlank { label },
                                order = key
                            )
                            if (numbered) {
                                numberedCount++
                                val languageRank = when (attrs.optString("translatedLanguage").lowercase()) {
                                    "zh", "zh-hans" -> 0
                                    "zh-hk", "zh-tw" -> 1
                                    "en" -> 2
                                    else -> 3
                                }
                                val external = !attrs.isNull("externalUrl") &&
                                    attrs.optString("externalUrl").isNotBlank()
                                if (external) externalCount++
                                val rank = languageRank + (if (external) 10 else 0)
                                val current = bestByNumber[key]
                                if (current == null || rank < current.first) {
                                    bestByNumber[key] = rank to chapter
                                }
                            } else {
                                chapters.add(chapter)
                            }
                        }
                        offset += data.length()
                        if (data.length() < pageSize || offset >= feed.optInt("total", offset)) break
                    } while (offset < 10000)
                    chapters.addAll(bestByNumber.values.map { it.second })
                    chapters.sortBy { it.order }
                    // 章节全为外链（如 One Piece 全卷外链 MangaPlus）时，阅读/下载
                    // 每章都要走镜像回退；在列表加载时就定位一次镜像 slug 并缓存，
                    // 后续各章直接复用，同时把"镜像没有该作品"提前暴露。
                    if (numberedCount > 0 && externalCount >= numberedCount) {
                        runCatching { mirrorSlugFor(officialId) }
                    }
                    Log.i(TAG, "official chapters for $officialId -> ${chapters.size}")
                    return@withContext SourceResult.Success(chapters)
                }
                val mirrorHtml = runCatching { getHtml("$BASE/manga/$bookId") }
                val blocks = mirrorHtml.getOrNull()?.let { Jsoup.parse(it).select("ul.chapter-list-item") }
                    ?: emptyList()
                if (blocks.isEmpty()) {
                    val officialFallback = synchronized(mirrorToOfficialCache) {
                        mirrorToOfficialCache[bookId]
                    }
                    if (officialFallback != null) {
                        return@withContext getChapters(officialFallback)
                    }
                    mirrorHtml.exceptionOrNull()?.let { throw it }
                    return@withContext SourceResult.Success(emptyList())
                }

                // 语言优先级：中 > 英 > 其余按章节数从多到少
                val preferred = listOf("ZH", "ZH-HK", "ZH-CN", "EN")
                val orderedBlocks = blocks.sortedBy { block ->
                    val idx = preferred.indexOf(block.attr("data-code").uppercase())
                    if (idx >= 0) idx else 10
                }

                val byNumber = LinkedHashMap<Float, ComicChapter>()
                var batchRequests = 0

                for (block in orderedBlocks) {
                    val code = block.attr("data-code").uppercase()
                    val lang = code.lowercase()
                    collectItems(block, bookId, code, byNumber)

                    var offset = block.attr("data-offset").toIntOrNull() ?: 0
                    var hasMore = block.attr("data-has-more") == "true"
                    var guard = 0
                    while (hasMore && guard < 60 && batchRequests < 80) {
                        val batchHtml = getHtml(
                            "$BASE/manga/$bookId/chapters?lang=$code&offset=$offset",
                            xhr = true
                        )
                        val batchDoc = Jsoup.parse(batchHtml)
                        val meta = batchDoc.selectFirst("span.chapter-batch-meta")
                        hasMore = meta?.attr("data-has-more") == "true"
                        offset = meta?.attr("data-next-offset")?.toIntOrNull() ?: (offset + 1)
                        collectItems(batchDoc, bookId, code, byNumber)
                        guard++
                        batchRequests++
                        if (lang.isBlank()) break
                    }
                }

                val chapters = byNumber.values.sortedBy { it.order }
                Log.i(TAG, "chapters for $bookId -> ${chapters.size}")
                SourceResult.Success(chapters)
            } catch (e: SourceException) {
                Log.e(TAG, "chapters failed: ${e.message}", e)
                SourceResult.Error(e)
            } catch (e: IOException) {
                Log.e(TAG, "chapters io error: ${e.message}", e)
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "chapters unexpected: ${e.message}", e)
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }

    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                // 第九轮：官方 API 章节（mdapich:<uuid>）——at-home 接口取图；
                // 无图（章节为 externalLink/无中文英文托管源）时回退镜像：
                // 按标题搜镜像拿 slug → 镜像章节列表找同号章节 → 镜像 read 页直链
                if (chapterId.startsWith("mdapich:")) {
                    val parts = chapterId.removePrefix("mdapich:").split(':')
                    // 旧版 ID 只有章节 UUID；新版同时携带作品 UUID，供外链章节回退镜像。
                    val mangaUuid = parts.getOrNull(0).takeIf { parts.size >= 3 }
                    val chapterUuid = parts.getOrNull(if (mangaUuid == null) 0 else 1).orEmpty()
                    val chapterNum = parts.getOrNull(if (mangaUuid == null) 1 else 2)?.toFloatOrNull()
                    val images = officialChapterImages(chapterUuid)
                    Log.i(TAG, "official chapter images $chapterUuid -> ${images.size}")
                    if (officialImagesAvailable(images)) {
                        return@withContext SourceResult.Success(images)
                    }
                    // Saved chapters from older versions may carry only the chapter UUID.
                    val metadata = if (mangaUuid == null || chapterNum == null) {
                        apiGet("$OFFICIAL_API/chapter/$chapterUuid")?.optJSONObject("data")
                    } else null
                    val mangaId = mangaUuid ?: metadata?.optJSONArray("relationships")?.let { rels ->
                        (0 until rels.length()).firstNotNullOfOrNull { i ->
                            rels.optJSONObject(i)?.takeIf { it.optString("type") == "manga" }?.optString("id")
                        }
                    }
                    val number = chapterNum ?: metadata?.optJSONObject("attributes")
                        ?.optString("chapter")?.toFloatOrNull()
                    // 回退镜像：uuid→标题桥接表 → 镜像搜索 → 同号章节
                    val fallback = if (!mangaId.isNullOrBlank() && number != null) {
                        mirrorFallbackImages(mangaId, number)
                    } else emptyList()
                    Log.i(TAG, "mirror fallback for $chapterUuid ch$chapterNum -> ${fallback.size}")
                    return@withContext if (fallback.isEmpty()) {
                        SourceResult.Error(
                            SourceException.ParseError("该章节官方图片不可用，镜像站也未找到同作品、同章节的图片")
                        )
                    } else {
                        SourceResult.Success(fallback)
                    }
                }
                val path = chapterId.trim('/')
                if (!path.startsWith("read/")) {
                    return@withContext SourceResult.Error(SourceException.ParseError("章节标识无效"))
                }
                val doc = Jsoup.parse(getHtml("$BASE/$path", referer = true))
                val images = doc.select("img[src]").mapNotNull { img ->
                    val src = img.attr("src").trim()
                    if (src.startsWith("http") && src.contains("/chapter/")) src else null
                }
                Log.i(TAG, "chapter images $chapterId -> ${images.size}")
                if (images.isEmpty()) {
                    SourceResult.Error(SourceException.ParseError("该章节没有可读取的图片"))
                } else {
                    SourceResult.Success(images)
                }
            } catch (e: SourceException) {
                Log.e(TAG, "chapter images failed: ${e.message}", e)
                SourceResult.Error(e)
            } catch (e: IOException) {
                Log.e(TAG, "chapter images io error: ${e.message}", e)
                SourceResult.Error(SourceException.NetworkError("网络连接错误: ${e.message}", e))
            } catch (e: Exception) {
                Log.e(TAG, "chapter images unexpected: ${e.message}", e)
                SourceResult.Error(SourceException.Unknown("未知错误: ${e.message}", e))
            }
        }

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> =
        SourceResult.Error(SourceException.ParseError("漫画源按章节在线阅读/下载，不支持单文件下载"))

    override suspend fun getChapterImageHeaders(
        chapterId: String,
        urls: List<String>
    ): Map<String, Map<String, String>> = urls.associateWith { url ->
        // Official at-home images must not inherit a third-party mirror Referer.
        // Mirror pages and official API chapters can both appear in the same search.
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val official = host == "mangadex.org" || host.endsWith(".mangadex.org") ||
            host == "mangadex.network" || host.endsWith(".mangadex.network") ||
            (chapterId.startsWith("mdapich:") && !uri?.path.orEmpty().startsWith("/chapter/"))
        if (official) mapOf("User-Agent" to UA)
        else mapOf("User-Agent" to UA, "Referer" to REFERER)
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> =
        SourceResult.Success(false)

    override suspend fun logout() {}

    override suspend fun isLoggedIn(): Boolean = false

    override suspend fun getAuthenticationState(): AuthenticationState =
        AuthenticationState.NotRequired

    private fun collectItems(
        doc: Element,
        slug: String,
        code: String,
        out: LinkedHashMap<Float, ComicChapter>
    ) {
        val lang = code.lowercase()
        for (li in doc.select("li.item[data-number]")) {
            val numStr = li.attr("data-number").trim()
            val num = numStr.toFloatOrNull() ?: continue
            if (out.containsKey(num)) continue
            val a = li.selectFirst("a[href^=/read/]") ?: continue
            val text = a.text().trim()
            val href = a.attr("href").trim('/')
            if (href.isBlank()) continue
            out[num] = ComicChapter(
                id = href,
                title = text.ifBlank { "第${numStr}话" },
                order = num
            )
        }
    }

    private fun parseJsonLdAuthor(doc: Document): String {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.html().trim()
            if (!text.contains("\"author\"")) continue
            try {
                val trimmed = text.trim()
                if (trimmed.startsWith("[")) {
                    val arr = org.json.JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val name = arr.optJSONObject(i)?.optJSONObject("author")?.optString("name")
                        if (!name.isNullOrBlank()) return name
                    }
                } else {
                    val json = JSONObject(trimmed)
                    val name = json.optJSONObject("author")?.optString("name")
                    if (!name.isNullOrBlank()) return name
                }
            } catch (_: Exception) {}
        }
        return ""
    }

    private fun languageLabel(code: String): String = when (code.uppercase()) {
        "ZH", "ZH-HK", "ZH-CN", "ZH-TW" -> "中文"
        "EN" -> "英文"
        "JA" -> "日文"
        "KO" -> "韩文"
        "ES" -> "西班牙语"
        "PT-BR", "PT" -> "葡萄牙语"
        "FR" -> "法语"
        "DE" -> "德语"
        "RU" -> "俄语"
        "IT" -> "意大利语"
        "AR" -> "阿拉伯语"
        "CA" -> "加泰罗尼亚语"
        "TR" -> "土耳其语"
        "TH" -> "泰语"
        "VI" -> "越南语"
        "ID" -> "印尼语"
        else -> code.uppercase()
    }

    private fun getHtml(
        url: String,
        xhr: Boolean = false,
        referer: Boolean = false
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer) builder.header("Referer", REFERER)
        if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
        val request = builder.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} for ${url.take(140)}")
                throw SourceException.NetworkError("MangaDex HTTP ${response.code} @ ${url.take(160)}")
            }
            return response.body?.string() ?: ""
        }
    }
}
