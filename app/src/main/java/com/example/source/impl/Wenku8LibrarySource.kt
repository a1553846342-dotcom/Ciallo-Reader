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

/** Public Chinese EPUB catalogue. Resolve temporary storage links only when the worker downloads. */
class Wenku8LibrarySource(
    context: Context,
    private val baseUrl: HttpUrl = "https://wenku8.ywy.moe/".toHttpUrl(),
    client: OkHttpClient? = null
) : UpdatableNovelSource {
    override val id = "wenku8_library"
    override val name = "轻小说中文文库"
    override val capabilities = SourceCapabilities(supportEbook = true)
    private val http = client ?: JsSourceProxy.failoverClient(context.applicationContext,
        SharedHttpTransport.builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build())
    private data class Cached(val at: Long, val json: JSONObject)
    private val cache = LruCache<String, Cached>(16)
    private val mutex = Mutex()

    private fun bookUrl(bookId: String): HttpUrl {
        require(bookId.matches(Regex("[1-9][0-9]{0,8}"))) { "无效的文库书籍标识" }
        return baseUrl.newBuilder().addPathSegments("api/books").addPathSegment(bookId).build()
    }
    private suspend fun json(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Referer", baseUrl.toString()).build()
        return http.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) throw SourceException.NetworkError("中文文库返回 HTTP ${response.code}")
            val bytes = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024) }
                ?: throw SourceException.ParseError("中文文库返回空响应")
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun book(data: JSONObject): SearchBook {
        val bookId = data.optLong("id").toString()
        bookUrl(bookId)
        val title = (data.opt("title") as? String).orEmpty()
        require(title.isNotBlank()) { "文库书名为空" }
        val volumes = data.optInt("volumeCount")
        val updated = (data.opt("updatedAt") as? String).orEmpty()
        val intro = (data.opt("synopsis") as? String).orEmpty()
        return SearchBook(bookId, id, title, (data.opt("author") as? String).orEmpty(),
            cover = (data.opt("coverUrl") as? String)?.takeIf(String::isNotBlank)?.let { baseUrl.resolve(it)?.toString() },
            format = "epub", language = "中文", size = data.optLong("epubBytes").takeIf { it > 0 },
            description = "中文轻小说文库版 EPUB，含插图，下载入书架后阅读。译本信息以书内注明为准。\n" +
                "文库收录 $volumes 卷 · 更新 $updated；可能晚于原版发行。\n\n" + org.jsoup.Jsoup.parse(intro).text(),
            novelInfo = NovelInfo(synopsis = org.jsoup.Jsoup.parse(intro).text(),
                status = when (data.optString("status")) { "completed" -> "已完结"; "ongoing" -> "连载中"; else -> null },
                updatedAt = updated.takeIf(String::isNotBlank), volumeCount = volumes.takeIf { it > 0 },
                chapterCount = data.optInt("chapterCount").takeIf { it > 0 },
                wordCount = data.optLong("wordCount").takeIf { it > 0 }?.let { "%,d 字".format(it) },
                publisher = data.optString("publisher").takeIf(String::isNotBlank),
                tags = data.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
                translation = "中文译本 · 文库版", notice = "含插图；译者以书内标注为准，收录可能晚于原版发行。"))
    }
    private suspend fun detail(bookId: String, fresh: Boolean = false): JSONObject = mutex.withLock {
        cache[bookId]?.takeIf { !fresh && SystemClock.elapsedRealtime() - it.at < 60_000 }?.json
            ?: json(bookUrl(bookId)).also { cache.put(bookId, Cached(SystemClock.elapsedRealtime(), it)) }
    }
    private suspend fun <T> result(action: suspend () -> T): SourceResult<T> = withContext(Dispatchers.IO) {
        try { SourceResult.Success(action()) }
        catch (e: CancellationException) { throw e }
        catch (e: SourceException) { SourceResult.Error(e) }
        catch (e: java.io.IOException) { SourceResult.Error(SourceException.NetworkError("中文文库连接失败，请稍后重试", e)) }
        catch (e: Exception) { SourceResult.Error(SourceException.ParseError(e.message ?: "中文文库数据格式异常")) }
    }
    override suspend fun search(keyword: String) = result {
        if (keyword.isBlank()) return@result emptyList<SearchBook>()
        val url = baseUrl.newBuilder().addPathSegments("api/books").addQueryParameter("q", keyword.trim())
            .addQueryParameter("page", "1").addQueryParameter("limit", "24").build()
        val items = json(url).optJSONArray("items") ?: throw SourceException.ParseError("未返回文库书籍列表")
        (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { data ->
            book(data).also { cache.put(it.id, Cached(SystemClock.elapsedRealtime(), data)) }
        } }.distinctBy { it.id }
    }
    override suspend fun getDetail(bookId: String) = result { book(detail(bookId)) }
    override suspend fun refreshNovelDetail(bookId: String) = result { book(detail(bookId, fresh = true)) }
    override suspend fun getDownloadInfo(bookId: String) = result {
        val title = book(detail(bookId)).title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        DownloadInfo(bookUrl(bookId).newBuilder().addPathSegment("download").build().toString(),
            "$title.epub", "epub", referer = baseUrl.toString())
    }
    override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
    override suspend fun logout() = Unit
    override suspend fun isLoggedIn() = true
}
