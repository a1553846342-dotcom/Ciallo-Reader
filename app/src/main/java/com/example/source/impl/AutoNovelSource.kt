package com.example.source.impl

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import com.example.data.readImportBytes
import com.example.source.*
import com.example.source.js.JsSourceProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Guest-facing AutoNovel web-novel API, independently adapted from its public API contract. */
class AutoNovelSource(
    context: Context,
    private val baseUrl: HttpUrl = "https://n.novelia.cc/".toHttpUrl(),
    client: OkHttpClient? = null
) : ComicSource, UpdatableNovelSource {
    override val id = "auto_novel"
    override val name = "轻小说机翻机器人"
    override val capabilities = SourceCapabilities(supportOnlineText = true)
    private val http = client ?: JsSourceProxy.failoverClient(context.applicationContext,
        SharedHttpTransport.builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build())
    private val providers = setOf("syosetu", "kakuyomu", "novelup", "hameln", "alphapolis")
    private data class Metadata(val at: Long, val data: JSONObject)
    private val metadata = LruCache<String, Metadata>(16)
    private val metadataMutex = Mutex()
    private val texts = object : LruCache<String, String>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String) = value.length * 2
    }
    private val textMutex = Mutex()

    private fun path(bookId: String): HttpUrl {
        val parts = bookId.split('/')
        require(parts.size == 2 && parts[0] in providers && parts[1].matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
            "无效的小说标识"
        }
        return baseUrl.newBuilder().addPathSegments("api/novel")
            .addPathSegment(parts[0]).addPathSegment(parts[1]).build()
    }

    private suspend fun json(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/119.0.0.0 Mobile Safari/537.36")
            .header("Referer", baseUrl.toString()).build()
        return http.newCall(request).executeCancellable().use { response ->
            if (response.code == 401) throw SourceException.LoginRequired
            if (!response.isSuccessful) throw SourceException.NetworkError("轻小说源返回 HTTP ${response.code}")
            val body = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024) }
                ?: throw SourceException.ParseError("轻小说源返回空响应")
            JSONObject(body.toString(Charsets.UTF_8))
        }
    }

    private suspend fun detail(bookId: String, fresh: Boolean = false): JSONObject = metadataMutex.withLock {
        metadata[bookId]?.takeIf { !fresh && SystemClock.elapsedRealtime() - it.at < 60_000 }?.data
            ?: json(path(bookId)).also { metadata.put(bookId, Metadata(SystemClock.elapsedRealtime(), it)) }
    }

    private fun title(data: JSONObject) = (data.opt("titleZh") as? String).orEmpty()
        .ifBlank { (data.opt("titleJp") as? String).orEmpty() }
    private fun book(bookId: String, data: JSONObject): SearchBook {
        val author = data.optJSONArray("authors")?.let { authors ->
            (0 until authors.length()).mapNotNull { i ->
                authors.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }.joinToString("、")
        }.orEmpty()
        val toc = data.optJSONArray("toc")
        val chapters = toc?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.takeIf { c -> c.optString("chapterId").isNotBlank() } } }
        val updated = data.optLong("updateAt", data.optLong("syncAt", 0))
        val tags = data.optJSONArray("keywords")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        return SearchBook(bookId, id, title(data), author, format = "epub", language = "中文（机翻）",
            description = "日本轻小说 Web 版，正文为中文机器翻译；缺译章节会提示等待译文。\n\n" +
                (data.opt("introductionZh") as? String).orEmpty(),
            novelInfo = NovelInfo(synopsis = (data.opt("introductionZh") as? String),
                originalTitle = (data.opt("titleJp") as? String), status = (data.opt("type") as? String),
                latestChapter = chapters?.lastOrNull()?.let(::title),
                chapterCount = chapters?.size ?: data.optInt("jp").takeIf { it > 0 },
                updatedAt = updated.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it * 1000)) },
                tags = tags, translation = "中文机翻 · 日本 Web 版",
                notice = "机器翻译；整本文件的缺译章节取决于原站译文进度。"))
    }

    private suspend fun <T> result(block: suspend () -> T): SourceResult<T> = withContext(Dispatchers.IO) {
        try { SourceResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: SourceException) { SourceResult.Error(e) }
        catch (e: java.io.IOException) { SourceResult.Error(SourceException.NetworkError("轻小说源连接失败，请稍后重试", e)) }
        catch (e: Exception) { SourceResult.Error(SourceException.ParseError(e.message ?: "轻小说数据格式异常")) }
    }

    override suspend fun search(keyword: String) = result {
        val query = keyword.trim()
        if (query.isEmpty()) return@result emptyList<SearchBook>()
        if (query.lowercase().replace(Regex("[ :：-]"), "") in setOf("re0", "rezero")) {
            return@result listOf(book("syosetu/n2267be", detail("syosetu/n2267be")))
        }
        val url = baseUrl.newBuilder().addPathSegments("api/novel")
            .addQueryParameter("page", "0").addQueryParameter("pageSize", "20")
            .addQueryParameter("query", query).addQueryParameter("provider", providers.joinToString(","))
            .addQueryParameter("type", "0").addQueryParameter("level", "1")
            .addQueryParameter("translate", "2").addQueryParameter("sort", "0").build()
        val items = json(url).optJSONArray("items") ?: throw SourceException.ParseError("未返回小说列表")
        (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val provider = item.optString("providerId")
            val novel = item.optString("novelId")
            if (provider !in providers || novel.isBlank() || title(item).isBlank()) null else book("$provider/$novel", item)
        }.distinctBy { it.id }
    }

    override suspend fun getDetail(bookId: String) = result { book(bookId, detail(bookId)) }
    override suspend fun refreshNovelDetail(bookId: String) = result { book(bookId, detail(bookId, fresh = true)) }
    override suspend fun getChapters(bookId: String) = result {
        val toc = detail(bookId).optJSONArray("toc") ?: throw SourceException.ParseError("未返回完整目录")
        var group = ""
        (0 until toc.length()).mapNotNull { i ->
            val item = toc.optJSONObject(i) ?: return@mapNotNull null
            val chapterId = item.optString("chapterId")
            if (chapterId.isBlank()) { group = title(item); return@mapNotNull null }
            require(chapterId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "章节标识无效" }
            ComicChapter("$bookId/$chapterId", title(item), volume = group, order = i.toFloat())
        }.distinctBy { it.id }.also { require(it.isNotEmpty()) { "小说目录为空" } }
    }

    override suspend fun getChapterText(chapterId: String) = result {
        textMutex.withLock {
            texts[chapterId] ?: run {
                val bookId = chapterId.substringBeforeLast('/')
                val chapter = chapterId.substringAfterLast('/')
                require(chapter.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "章节标识无效" }
                val data = json(path(bookId).newBuilder().addPathSegment("chapter").addPathSegment(chapter).build())
                paragraphText(data).also { texts.put(chapterId, it) }
            }
        }
    }

    internal fun paragraphText(data: JSONObject): String {
        val original = data.optJSONArray("paragraphs") ?: throw SourceException.ParseError("正文缺失")
        val translations = listOf("sakuraParagraphs", "gptParagraphs", "youdaoParagraphs", "baiduParagraphs")
            .mapNotNull { data.optJSONArray(it)?.takeIf { a -> a.length() == original.length() } }
        var missing = 0
        val paragraphs = (0 until original.length()).map { i ->
            val source = (original.opt(i) as? String).orEmpty().trim()
            val text = translations.firstNotNullOfOrNull { (it.opt(i) as? String)?.trim()?.takeIf(String::isNotEmpty) }
                ?: when {
                    source.startsWith("<图片>") || source.none { it.isLetter() } -> source
                    else -> { missing++; "" }
                }
            if (text.startsWith("<图片>")) "[插图] " + text.removePrefix("<图片>") else text
        }
        if (missing > 0) throw SourceException.ParseError("本章中文译文尚未齐全（缺 $missing 段），请稍后重试")
        return paragraphs.filter(String::isNotBlank).joinToString("\n\n").also { require(it.isNotBlank()) { "本章中文译文为空" } }
    }

    override suspend fun getDownloadInfo(bookId: String) = getDownloadInfo(bookId, "epub")
    override suspend fun getDownloadInfo(bookId: String, preferredFormat: String?) = result {
        val format = preferredFormat?.lowercase()?.takeIf { it in setOf("epub", "txt") } ?: "epub"
        val fileName = title(detail(bookId)).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".$format"
        val url = path(bookId).newBuilder().addPathSegment("file")
            .addQueryParameter("mode", "zh").addQueryParameter("translationsMode", "priority")
            .addQueryParameter("type", format).addQueryParameter("filename", fileName)
        listOf("sakura", "gpt", "youdao", "baidu").forEach { url.addQueryParameter("translations", it) }
        DownloadInfo(url.build().toString(), fileName, format, referer = baseUrl.toString())
    }
    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> =
        SourceResult.Error(SourceException.ParseError("请使用小说文字阅读器"))
    override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
    override suspend fun logout() = Unit
    override suspend fun isLoggedIn() = true
}
