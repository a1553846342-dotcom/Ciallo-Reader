package com.example.source.zlibrary

import android.util.Log
import com.example.source.BookFormat
import com.example.source.SearchBook
import com.example.source.zlibrary.network.ZLibraryHttpClient
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * bipinkrish/Zlibrary-API（eapi JSON 接口）客户端。
 *
 * 已实测验证（2026-08）：
 * - POST /eapi/user/login：可用（拿到 remix_userid / remix_userkey）
 * - GET  /eapi/book/{id}/{hash}/formats：返回全部格式变体（epub/mobi/pdf/azw3/fb2/lit…）
 * - GET  /eapi/book/{id}/{hash}/file：返回 CDN downloadLink，可直接下载对应格式
 * - POST /eapi/book/search：端点正确（当前官网搜索服务全站故障，恢复后可用）
 *
 * 该客户端是 ZLibrarySource 的兜底方案：HTML 流程（rpc.php）失败时走这里。
 */
class ZLibraryEapiClient(
    private val httpClient: ZLibraryHttpClient,
    private val credentialStorage: ZLibraryCredentialStorage
) {

    private suspend fun getJson(
        url: String,
        referer: String? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.get(url, referer = referer)
            val body = response.body?.string() ?: return@withContext null
            response.close()
            if (!response.isSuccessful) {
                Log.w(TAG, "eapi GET ${response.code} $url")
                return@withContext null
            }
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "eapi GET failed: $url ${e.message}")
            null
        }
    }

    private suspend fun postJson(
        url: String,
        fields: Map<String, String>,
        referer: String? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            val response = httpClient.postForm(url, formBody, referer = referer)
            val body = response.body?.string() ?: return@withContext null
            response.close()
            if (!response.isSuccessful) {
                Log.w(TAG, "eapi POST ${response.code} $url")
                return@withContext null
            }
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "eapi POST failed: $url ${e.message}")
            null
        }
    }

    /** eapi 登录：成功会把 remix cookie 写入 cookieJar 并持久化。
     *  2026-09-04 修复：eapi 协议要求密码做三段 MD5 变换 md5(email:pass:md5(pass))——
     *  旧实现传明文密码，服务器返回 "Authorization failed"（实测 z-lib.is）。
     *  注意：MD5 是 zlib 服务端协议既定算法（模拟其 Web 前端混淆），非本项目自选。 */
    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun login(email: String, password: String, domain: String): Boolean {
        val md5Pass = md5Hex(password)
        val transformed = md5Hex("$email:$password:$md5Pass")
        val json = postJson(
            "https://$domain/eapi/user/login",
            mapOf(
                "email" to email,
                "password" to transformed,
                "site_mode" to "books",
                "action" to "login"
            ),
            referer = "https://$domain/"
        ) ?: return false
        val ok = json.optInt("success", 0) == 1
        if (ok) {
            val user = json.optJSONObject("user")
            val uid = user?.optString("id", null)
            val ukey = user?.optString("remix_userkey", null)
            // cookieJar.saveFromResponse 已把 remix cookie 持久化；这里再补写 domain
            credentialStorage.saveCredentials(
                userId = uid ?: credentialStorage.getUserId(),
                userKey = ukey ?: credentialStorage.getUserKey(),
                domain = domain,
                cookies = credentialStorage.getCookies()
            )
        }
        return ok
    }

    /** 每日下载额度：(今日已用, 上限)。查询失败返回 null，调用方不应因此阻断下载。 */
    suspend fun getDailyDownloadLimit(domain: String): Pair<Int, Int>? {
        val json = getJson(
            "https://$domain/eapi/user/profile",
            referer = "https://$domain/"
        ) ?: return null
        if (json.optInt("success", 0) != 1) return null
        val user = json.optJSONObject("user") ?: return null
        val today = user.optInt("downloads_today", -1)
        val limit = user.optInt("downloads_limit", -1)
        return if (today >= 0 && limit > 0) today to limit else null
    }

    /**
     * eapi 搜索。要求已登录（bipinkrish 库同样要求登录态）。
     * 返回的 SearchBook 会携带 eapiId / eapiHash，供多格式查询使用。
     */
    suspend fun search(keyword: String, domain: String): List<SearchBook> {
        if (!credentialStorage.isLoggedIn()) return emptyList()
        val json = postJson(
            "https://$domain/eapi/book/search",
            mapOf("message" to keyword, "limit" to "30"),
            referer = "https://$domain/"
        ) ?: return emptyList()
        if (json.optInt("success", 0) != 1) return emptyList()
        val booksArr = json.optJSONArray("books") ?: return emptyList()
        val result = mutableListOf<SearchBook>()
        for (i in 0 until booksArr.length()) {
            val b = booksArr.optJSONObject(i) ?: continue
            val bookUrl = b.optString("url", "")
            if (bookUrl.isBlank()) continue
            val eapiId = b.optString("id", "")
            val eapiHash = b.optString("hash", "")
            if (eapiId.isBlank() || eapiHash.isBlank()) continue
            val cover = b.optString("cover", "").ifBlank { null }
            val dl = b.optString("dl", "").ifBlank { null }
            result.add(
                SearchBook(
                    id = bookUrl.trimStart('/'),
                    sourceId = "zlibrary",
                    title = b.optString("title", "未知书名"),
                    author = b.optString("author", "未知作者"),
                    cover = cover,
                    format = b.optString("extension", "epub").lowercase(),
                    language = b.optString("language", "").ifBlank { null },
                    size = b.optLong("filesize", 0L).takeIf { it > 0 },
                    downloadUrl = dl?.let { if (it.startsWith("http")) it else "https://$domain$it" },
                    eapiId = eapiId,
                    eapiHash = eapiHash
                )
            )
        }
        return result
    }

    /** eapi 书信息（HTML 详情页不可用时兜底）。 */
    suspend fun getBookInfo(eapiId: String, eapiHash: String, domain: String): SearchBook? {
        val json = getJson(
            "https://$domain/eapi/book/$eapiId/$eapiHash",
            referer = "https://$domain/"
        ) ?: return null
        if (json.optInt("success", 0) != 1) return null
        val b = json.optJSONObject("book") ?: return null
        val dl = b.optString("dl", "").ifBlank { null }
        return SearchBook(
            id = b.optString("url", "/book/$eapiId/$eapiHash").trimStart('/'),
            sourceId = "zlibrary",
            title = b.optString("title", "未知书名"),
            author = b.optString("author", "未知作者"),
            cover = b.optString("cover", "").ifBlank { null },
            format = b.optString("extension", "epub").lowercase(),
            language = b.optString("language", "").ifBlank { null },
            description = b.optString("description", "").takeIf(String::isNotBlank)?.let { org.jsoup.Jsoup.parse(it).text() },
            downloadUrl = dl?.let { if (it.startsWith("http")) it else "https://$domain$it" },
            eapiId = eapiId,
            eapiHash = eapiHash
        )
    }

    /** 某本书全部可用格式（epub/mobi/pdf/azw3/fb2/lit/txt…），每个格式一个变体。 */
    suspend fun getFormats(eapiId: String, eapiHash: String, domain: String): List<BookFormat> {
        val json = getJson(
            "https://$domain/eapi/book/$eapiId/$eapiHash/formats",
            referer = "https://$domain/"
        ) ?: return emptyList()
        if (json.optInt("success", 0) != 1) return emptyList()
        val arr = json.optJSONArray("books") ?: return emptyList()
        val seen = LinkedHashMap<String, BookFormat>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val ext = v.optString("extension", "").lowercase()
            if (ext.isBlank()) continue
            val size = v.optLong("filesize", 0L).takeIf { it > 0 }
            if (!seen.containsKey(ext)) {
                seen[ext] = BookFormat(
                    format = ext,
                    size = size,
                    sizeText = v.optString("filesizeString", "").ifBlank { null },
                    eapiId = v.optString("id", "").ifBlank { null },
                    eapiHash = v.optString("hash", "").ifBlank { null }
                )
            }
        }
        return seen.values.toList()
    }

    /** 取某格式变体的 CDN 下载直链（可带 Cookie 直接下载）。 */
    suspend fun getDownloadLink(eapiId: String, eapiHash: String, domain: String): String? {
        return getDownloadLinkResult(eapiId, eapiHash, domain).url
    }

    /** eapi file 接口结果：CDN 直链；每日下载额度用尽时返回明确的限制文案。 */
    suspend fun getDownloadLinkResult(
        eapiId: String,
        eapiHash: String,
        domain: String
    ): DownloadLinkResult {
        val json = getJson(
            "https://$domain/eapi/book/$eapiId/$eapiHash/file",
            referer = "https://$domain/"
        ) ?: return DownloadLinkResult(null, null)
        if (json.optInt("success", 0) != 1) return DownloadLinkResult(null, null)
        val file = json.optJSONObject("file") ?: return DownloadLinkResult(null, null)

        // 每日下载额度用尽：服务器返回 allowDownload=false + disallowDownloadMessage（HTML 富文本）
        if (!file.optBoolean("allowDownload", true)) {
            val raw = file.optString("disallowDownloadMessage", "")
            val clean = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
            return DownloadLinkResult(
                null,
                clean.ifBlank { "今日下载次数已达上限，请等待额度重置或提升下载额度" }
            )
        }

        val raw = file.optString("downloadLink", null).ifBlank { null }
        // 部分 CDN 链接是协议相对（//dln1.…）或裸路径，OkHttp 会报
        // "Expected URL scheme 'http' or 'https'"，这里统一补全 scheme
        val normalized = when {
            raw == null -> null
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://$domain$raw"
            else -> raw
        }
        return DownloadLinkResult(normalized, null)
    }

    private companion object {
        const val TAG = "ZLibraryEapiClient"
    }
}

/** eapi file 接口返回：url 为 CDN 直链；disallowMessage 为每日额度限制提示。 */
data class DownloadLinkResult(
    val url: String?,
    val disallowMessage: String?
)
