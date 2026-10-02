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
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Search and download links from the site's public guest-facing HTML. */
class IxdzsSource(
    context: Context,
    private val baseUrl: HttpUrl = "https://ixdzs8.com/".toHttpUrl(),
    client: OkHttpClient? = null
) : UpdatableNovelSource {
    override val id = "ixdzs8"
    override val name = "爱下电子书"
    override val capabilities = SourceCapabilities(supportEbook = true)
    private val http = client ?: JsSourceProxy.failoverClient(context.applicationContext,
        SharedHttpTransport.builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build())
    private data class Detail(val at: Long, val book: SearchBook, val download: String)
    private val cache = LruCache<String, Detail>(16)
    private val mutex = Mutex()

    private fun bookUrl(bookId: String): HttpUrl {
        require(bookId.matches(Regex("[1-9][0-9]{0,8}"))) { "无效的网文书籍标识" }
        return baseUrl.newBuilder().addPathSegment("read").addPathSegment(bookId).addPathSegment("").build()
    }
    private suspend fun page(url: HttpUrl): org.jsoup.nodes.Document {
        val request = Request.Builder().url(url).header("Referer", baseUrl.toString())
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36").build()
        return http.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) throw SourceException.NetworkError("爱下电子书返回 HTTP ${response.code}")
            val bytes = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024) }
                ?: throw SourceException.ParseError("网文源返回空页面")
            Jsoup.parse(bytes.toString(Charsets.UTF_8), url.toString())
        }
    }
    private suspend fun detail(bookId: String, fresh: Boolean = false): Detail = mutex.withLock {
        cache[bookId]?.takeIf { !fresh && SystemClock.elapsedRealtime() - it.at < 60_000 } ?: run {
            val doc = page(bookUrl(bookId))
            val title = doc.selectFirst(".novel h1")?.text().orEmpty()
            require(title.isNotBlank()) { "未找到小说详情，站点可能暂时不可用" }
            val download = doc.select(".n-btn a[href]").firstOrNull { it.text().contains("TXT下载") }
                ?.absUrl("href")?.toHttpUrl() ?: throw SourceException.ParseError("本书未提供 TXT 下载")
            require(download.scheme == "https" && (download.host.matches(Regex("down[0-9]+\\.ixdzs8\\.com")) ||
                download.host == baseUrl.host)) { "网文下载地址异常" }
            val info = doc.selectFirst(".n-text")!!
            val latest = info.select("p").firstOrNull { it.text().startsWith("最新:") }?.text().orEmpty()
            val updated = info.select("p").firstOrNull { it.text().startsWith("更新:") }?.text().orEmpty()
            val book = SearchBook(bookId, id, title, info.selectFirst(".bauthor")?.text().orEmpty(),
                cover = doc.selectFirst(".n-img img[src]")?.absUrl("src"), format = "txt", language = "中文",
                description = "中文网文，TXT 下载到书架阅读。原站下载包可能缺章或更新滞后。\n" +
                    "$latest\n$updated\n\n" + doc.selectFirst("#intro")?.text().orEmpty(),
                novelInfo = NovelInfo(synopsis = doc.selectFirst("#intro")?.text(),
                    status = info.selectFirst(".lz")?.text(), category = info.selectFirst(".nsort")?.text(),
                    wordCount = info.selectFirst(".nsize")?.text(), latestChapter = latest.removePrefix("最新:").takeIf(String::isNotBlank),
                    updatedAt = updated.removePrefix("更新:").takeIf(String::isNotBlank),
                    tags = doc.select(".tags a").map { it.text() }, translation = "中文网文",
                    notice = "原站下载包可能缺章或更新滞后；标称最新章节不代表下载包已有完整正文。"))
            Detail(SystemClock.elapsedRealtime(), book, download.toString()).also { cache.put(bookId, it) }
        }
    }
    private suspend fun <T> result(block: suspend () -> T): SourceResult<T> = withContext(Dispatchers.IO) {
        try { SourceResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: SourceException) { SourceResult.Error(e) }
        catch (e: java.io.IOException) { SourceResult.Error(SourceException.NetworkError("网文源连接失败，请稍后重试", e)) }
        catch (e: Exception) { SourceResult.Error(SourceException.ParseError(e.message ?: "网文页面解析失败")) }
    }
    override suspend fun search(keyword: String) = result {
        if (keyword.isBlank()) return@result emptyList<SearchBook>()
        val doc = page(baseUrl.newBuilder().addPathSegment("bsearch").addQueryParameter("q", keyword.trim()).build())
        doc.select(".l-info").mapNotNull { info ->
            val link = info.selectFirst("h3.bname a[href]") ?: return@mapNotNull null
            val url = baseUrl.resolve(link.attr("href")) ?: return@mapNotNull null
            val bookId = Regex("^/read/([1-9][0-9]{0,8})/$").matchEntire(url.encodedPath)?.groupValues?.get(1)
                ?: return@mapNotNull null
            if (url.host != baseUrl.host || link.text().isBlank()) return@mapNotNull null
            SearchBook(bookId, id, link.text(), info.selectFirst(".bauthor")?.text().orEmpty(),
                cover = info.parent()?.parent()?.selectFirst("img[src]")?.absUrl("src"), format = "txt", language = "中文",
                description = "中文网文，TXT 下载到书架阅读；原站下载包可能缺章或更新滞后。\n" +
                    info.selectFirst(".l-last")?.text().orEmpty() + "\n\n" + info.selectFirst(".l-p2")?.text().orEmpty(),
                novelInfo = NovelInfo(synopsis = info.selectFirst(".l-p2")?.text(),
                    status = info.selectFirst(".lz")?.text(), category = info.selectFirst(".nsort")?.text(),
                    latestChapter = info.selectFirst(".l-last a")?.text(),
                    translation = "中文网文", notice = "原站下载包可能缺章或更新滞后。"))
        }.distinctBy { it.id }
    }
    override suspend fun getDetail(bookId: String) = result { detail(bookId).book }
    override suspend fun refreshNovelDetail(bookId: String) = result { detail(bookId, fresh = true).book }
    override suspend fun getDownloadInfo(bookId: String) = result {
        val data = detail(bookId)
        val filename = data.book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".txt"
        DownloadInfo(data.download, filename, "txt", referer = baseUrl.toString())
    }
    override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
    override suspend fun logout() = Unit
    override suspend fun isLoggedIn() = true
}
