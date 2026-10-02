package com.example.source.importer

import com.example.data.readImportBytes
import android.content.Context
import android.net.Uri
import com.example.source.*
import com.example.source.impl.JsonBookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 书源导入器。
 *
 * 支持三种输入：
 * 1. 本项目原生 JSON 格式（search / htmlSearch / htmlChapters / htmlContent）
 * 2. Legado（开源阅读）书源格式：bookSourceName / bookSourceUrl / searchUrl /
 *    ruleSearch / ruleToc / ruleContent（社区「阅读」书源通用格式）
 * 3. 书源合集 JSON 数组（批量导入，跳过不兼容源）
 *
 * Legado 规则转换说明：
 * - {{key}} -> {keyword}，{{page}} -> {page}，支持 {{java.base64Encode(key)}} 与简单
 *   {{(page-1)*20}} 页面运算；POST 搜索源（,{ "method":"POST","body":... }）会透传 method/body
 * - ruleSearch.bookList 以 $ 或 @json: 开头时按 JSONPath 解析，否则按 HTML 规则解析
 * - 需要 @js: / webView / 嗅探 sourceRegex 的书源暂不支持，会明确跳过
 */
object SourceImporter {

    /** 社区书源网络导入预设（用户可自行替换为任意 shuyuan 文件地址）。 */
    val PRESET_SOURCE_URLS = listOf(
        "https://raw.ixnic.net/XIU2/Yuedu/master/shuyuan" to "XIU2 精品书源（镜像）",
        "https://cdn.jsdelivr.net/gh/XIU2/Yuedu@master/shuyuan" to "XIU2 精品书源（CDN）",
        "https://raw.githubusercontent.com/XIU2/Yuedu/master/shuyuan" to "XIU2 精品书源（GitHub 直连）"
    )

    data class BatchImportResult(
        val imported: List<Pair<JsonBookSource, String>>,
        val skipped: List<Pair<String, String>>
    ) {
        val importedCount: Int get() = imported.size
        val skippedCount: Int get() = skipped.size
    }

    private val urlClient = com.example.source.SharedHttpTransport.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun importFromJsonString(jsonStr: String): SourceResult<JsonBookSource> {
        val batch = parseBatch(jsonStr)
        val first = batch.imported.firstOrNull()
        return if (first != null) {
            SourceResult.Success(first.first)
        } else {
            val reason = batch.skipped.firstOrNull()?.second ?: "未找到可导入的书源"
            SourceResult.Error(SourceException.ParseError("导入失败：$reason"))
        }
    }

    /** 批量导入（支持单源或 JSON 数组），返回导入成功与跳过的明细。 */
    fun importBatchFromJsonString(jsonStr: String): BatchImportResult = parseBatch(jsonStr)

    suspend fun importFromUri(context: Context, uri: Uri): SourceResult<Pair<JsonBookSource, String>> =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext SourceResult.Error(SourceException.ParseError("无法打开选择的文件"))

                val jsonStr = inputStream.use { stream ->
                    stream.readImportBytes(2*1024*1024).toString(Charsets.UTF_8)
                }

                val batch = parseBatch(jsonStr)
                val first = batch.imported.firstOrNull()
                when {
                    first != null -> SourceResult.Success(first)
                    else -> SourceResult.Error(
                        SourceException.ParseError(batch.skipped.firstOrNull()?.second ?: "未找到可导入的书源")
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SourceResult.Error(SourceException.ParseError("读取文件失败: ${e.message}"))
            }
        }

    suspend fun importBatchFromUri(context: Context, uri: Uri): BatchImportResult =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext BatchImportResult(emptyList(), listOf("文件" to "无法打开选择的文件"))
                val jsonStr = inputStream.use { stream ->
                    stream.readImportBytes(2*1024*1024).toString(Charsets.UTF_8)
                }
                parseBatch(jsonStr)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                BatchImportResult(emptyList(), listOf("文件" to "读取失败: ${e.message}"))
            }
        }

    /** 从网络地址导入书源（支持单源 JSON 与合集数组）。 */
    suspend fun importBatchFromUrl(url: String): BatchImportResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                )
                .build()
            urlClient.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) {
                return@withContext BatchImportResult(
                    emptyList(),
                    listOf(url to "网络请求失败 HTTP ${response.code}")
                )
            }
            val body = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024).toString(Charsets.UTF_8) } ?: ""
            if (body.isBlank()) {
                return@withContext BatchImportResult(emptyList(), listOf(url to "返回内容为空"))
            }
            parseBatch(body)
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            BatchImportResult(emptyList(), listOf(url to "网络导入失败: ${e.message}"))
        }
    }

    // ---------------------------------------------------------------------
    // 核心解析
    // ---------------------------------------------------------------------

    private fun parseBatch(jsonStr: String): BatchImportResult {
        if (jsonStr.isBlank()) {
            return BatchImportResult(emptyList(), listOf("输入" to "JSON内容为空"))
        }
        val trimmed = jsonStr.trim()
        val imported = mutableListOf<Pair<JsonBookSource, String>>()
        val skipped = mutableListOf<Pair<String, String>>()

        try {
            com.example.source.parser.RuleBudget.json(jsonStr)
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                require(array.length()<=1000) { "一次最多导入 1000 个书源" }
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    convertOne(obj)?.let { converted ->
                        if (converted.source != null) {
                            imported.add(converted.source!! to converted.rawJson)
                        } else {
                            skipped.add(converted.name to (converted.reason ?: "未知原因"))
                        }
                    }
                }
            } else {
                val obj = JSONObject(trimmed)
                convertOne(obj)?.let { converted ->
                    if (converted.source != null) {
                        imported.add(converted.source!! to converted.rawJson)
                    } else {
                        skipped.add(converted.name to (converted.reason ?: "未知原因"))
                    }
                }
            }
        } catch (e: JSONException) {
            return BatchImportResult(emptyList(), listOf("输入" to "JSON 语法错误: ${e.message}"))
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            return BatchImportResult(emptyList(), listOf("输入" to "解析失败: ${e.message}"))
        }
        return BatchImportResult(imported, skipped)
    }

    private fun convertOne(obj: JSONObject): ConvertedSource? {
        val rawJson = obj.toString()
        // 优先按本项目原生格式解析
        if (obj.has("htmlSearch") || obj.has("search")) {
            return when (val result = parseNativeSource(obj, rawJson)) {
                is SourceResult.Success -> ConvertedSource(result.data, rawJson)
                is SourceResult.Error -> ConvertedSource(null, rawJson, obj.optString("name", "书源"), result.exception.message)
            }
        }
        // Legado 格式
        if (obj.has("bookSourceName") || obj.has("bookSourceUrl") || obj.has("ruleSearch")) {
            return convertLegadoSource(obj, rawJson)
        }
        return ConvertedSource(null, rawJson, obj.optString("name", obj.optString("bookSourceName", "未知书源")), "既不是本项目 JSON 格式，也不是 Legado 书源格式")
    }

    private fun parseNativeSource(obj: JSONObject, rawJson: String): SourceResult<JsonBookSource> {
        try {
            val name = obj.optString("name", "").ifBlank { "未命名书源" }
            val id = obj.optString("id", "").ifBlank { "custom_" + UUID.randomUUID().toString().substring(0, 8) }
            val baseUrl = obj.optString("baseUrl", "")

            val searchObj = obj.optJSONObject("search")
            val htmlSearchObj = obj.optJSONObject("htmlSearch")
            if (searchObj == null && htmlSearchObj == null) {
                return SourceResult.Error(SourceException.ParseError("缺少 search 或 htmlSearch 节点规则"))
            }

            val searchUrl = searchObj?.optString("url", "")
                ?.takeIf { it.isNotBlank() }
                ?: htmlSearchObj?.optString("url", "")
                    ?: ""
            if (searchUrl.isBlank()) {
                return SourceResult.Error(SourceException.ParseError("search/htmlSearch 规则中缺少 url 字段"))
            }

            val searchMethod = searchObj?.optString("method", "GET") ?: "GET"
            val searchListPath = searchObj?.optString("listPath", "books") ?: "books"

            val searchFieldsObj = searchObj?.optJSONObject("fields") ?: searchObj
            val searchFields = BookFieldRule(
                id = searchFieldsObj?.optString("id", "id") ?: "id",
                title = searchFieldsObj?.optString("title", "title") ?: "title",
                author = searchFieldsObj?.optString("author", "author"),
                cover = searchFieldsObj?.optString("cover", "cover"),
                description = searchFieldsObj?.optString("description", "description"),
                format = searchFieldsObj?.optString("format", "format"),
                downloadUrl = searchFieldsObj?.optString("downloadUrl", "downloadUrl")
            )

            val searchHeaders = parseHeaders(searchObj?.optJSONObject("headers"))
            val searchBody = searchObj?.optString("body", "")?.ifBlank { null }

            val searchRule = SearchRule(
                url = searchUrl,
                method = searchMethod,
                listPath = searchListPath,
                fields = searchFields,
                headers = searchHeaders,
                body = searchBody
            )

            var detailRule: DetailRule? = null
            val detailObj = obj.optJSONObject("detail")
            if (detailObj != null) {
                val detailUrl = detailObj.optString("url", "")
                if (detailUrl.isNotBlank()) {
                    val detailMethod = detailObj.optString("method", "GET")
                    val detailFieldsObj = detailObj.optJSONObject("fields") ?: detailObj
                    val detailFields = BookFieldRule(
                        id = detailFieldsObj?.optString("id", "id") ?: "id",
                        title = detailFieldsObj?.optString("title", "title") ?: "title",
                        author = detailFieldsObj?.optString("author", "author"),
                        cover = detailFieldsObj?.optString("cover", "cover"),
                        description = detailFieldsObj?.optString("description", "description"),
                        format = detailFieldsObj?.optString("format", "format"),
                        downloadUrl = detailFieldsObj?.optString("downloadUrl", "downloadUrl")
                    )
                    val detailHeaders = parseHeaders(detailObj.optJSONObject("headers"))
                    val detailBody = detailObj.optString("body", "").ifBlank { null }
                    detailRule = DetailRule(
                        url = detailUrl,
                        method = detailMethod,
                        fields = detailFields,
                        headers = detailHeaders,
                        body = detailBody
                    )
                }
            }

            var downloadRule = DownloadRule()
            val downloadObj = obj.optJSONObject("download")
            if (downloadObj != null) {
                val dlUrl = downloadObj.optString("url", null)
                val dlUrlField = downloadObj.optString("urlField", "downloadUrl")
                val dlFormat = downloadObj.optString("format", "epub")
                val dlHeaders = parseHeaders(downloadObj.optJSONObject("headers"))
                downloadRule = DownloadRule(
                    url = if (dlUrl.isNull_blank()) null else dlUrl,
                    urlField = if (dlUrlField.isNull_blank()) null else dlUrlField,
                    defaultFormat = dlFormat.ifBlank { "epub" },
                    headers = dlHeaders
                )
            }

            val htmlSearch = htmlSearchObj?.let { h ->
                HtmlSearchRule(
                    url = h.optString("url", ""),
                    listSelector = h.optString("listSelector", ""),
                    titleSelector = h.optString("title", ""),
                    authorSelector = h.optString("author", ""),
                    coverSelector = h.optString("cover", ""),
                    detailUrlSelector = h.optString("detailUrl", ""),
                    introSelector = h.optString("intro", ""),
                    charset = h.optString("charset", "").ifBlank { null },
                    method = h.optString("method", "GET").ifBlank { "GET" }.uppercase(),
                    body = h.optString("body", "").ifBlank { null }
                )
            }?.takeIf { it.url.isNotBlank() && it.listSelector.isNotBlank() }

            val htmlChapters = obj.optJSONObject("htmlChapters")?.let { h ->
                HtmlChapterRule(
                    url = h.optString("url", ""),
                    listSelector = h.optString("listSelector", ""),
                    nameSelector = h.optString("name", "text"),
                    hrefSelector = h.optString("href", "href")
                )
            }?.takeIf { it.url.isNotBlank() && it.listSelector.isNotBlank() }

            val htmlContent = obj.optJSONObject("htmlContent")?.let { h ->
                HtmlContentRule(
                    url = h.optString("url", ""),
                    imageSelector = h.optString("imageSelector", "")
                )
            }?.takeIf { it.url.isNotBlank() && it.imageSelector.isNotBlank() }

            val config = SourceConfig(
                id = id,
                name = name,
                baseUrl = baseUrl,
                search = searchRule,
                detail = detailRule,
                download = downloadRule,
                htmlSearch = htmlSearch,
                htmlChapters = htmlChapters,
                htmlContent = htmlContent,
                enabled = true,
                isCustom = true,
                insecureTls = obj.optBoolean("insecureTls", false),
                // 第三轮记录的遗留 NPE 修复：type 键缺失时 optString 返回 null，
                // 直接 .ifBlank 会抛空安全异常——任何不带 type 字段的书源导入必失败
                type = obj.optString("type", null)?.ifBlank { null }
            )

            return SourceResult.Success(JsonBookSource(config))
        } catch (e: JSONException) {
            return SourceResult.Error(SourceException.ParseError("JSON解析语法错误: ${e.message}"))
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            return SourceResult.Error(SourceException.ParseError("解析书源失败: ${e.message}"))
        }
    }

    private fun convertLegadoSource(obj: JSONObject, rawJson: String): ConvertedSource {
        val name = obj.optString("bookSourceName", "").ifBlank { "未命名书源" }
        val baseUrl = obj.optString("bookSourceUrl", "").trim()
        if (baseUrl.isBlank()) {
            return ConvertedSource(null, rawJson, name, "缺少 bookSourceUrl")
        }
        val searchUrlRaw = obj.optString("searchUrl", "").trim()
        if (searchUrlRaw.isBlank()) {
            return ConvertedSource(null, rawJson, name, "缺少 searchUrl")
        }

        val ruleSearch = parseRuleObject(obj, "ruleSearch") ?: return ConvertedSource(null, rawJson, name, "缺少 ruleSearch")
        val ruleToc = parseRuleObject(obj, "ruleToc") ?: JSONObject()
        val ruleContent = parseRuleObject(obj, "ruleContent") ?: JSONObject()

        val allRules = listOf(searchUrlRaw, ruleSearch.toString(), ruleToc.toString(), ruleContent.toString()).joinToString("\n")
        if (allRules.contains("@js:") || allRules.contains("{{java.") || allRules.contains("webView") ||
            allRules.contains("@js") || allRules.contains("webJs") ||
            ruleContent.optString("sourceRegex").isNotBlank()
        ) {
            return ConvertedSource(null, rawJson, name, "包含 JS 脚本/WebView 嗅探，暂不支持")
        }
        if (obj.optInt("bookSourceType", 0) == 1) {
            return ConvertedSource(null, rawJson, name, "有声书源（bookSourceType=1）暂不支持")
        }

        val bookList = ruleSearch.optString("bookList", "").trim()
        if (bookList.isBlank()) {
            return ConvertedSource(null, rawJson, name, "缺少 ruleSearch.bookList")
        }
        val chapterList = ruleToc.optString("chapterList", "").trim()
        if (chapterList.isBlank()) {
            return ConvertedSource(null, rawJson, name, "缺少 ruleToc.chapterList（无目录规则）")
        }
        val content = ruleContent.optString("content", "").trim()
        if (content.isBlank()) {
            return ConvertedSource(null, rawJson, name, "缺少 ruleContent.content（无正文/图片规则）")
        }
        if (content == "result") {
            return ConvertedSource(null, rawJson, name, "正文依赖媒体嗅探（sourceRegex），暂不支持")
        }

        val converted = convertLegadoUrl(searchUrlRaw)
        if (converted == null) {
            return ConvertedSource(null, rawJson, name, "搜索 URL 含无法转换的 JS 表达式")
        }

        val headers = parseHeaderString(obj.optString("header", ""))
        val charset = converted.charset?.ifBlank { null }
        val id = "legado_" + baseUrl
            .replace("https://", "").replace("http://", "")
            .trimEnd('/')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { UUID.randomUUID().toString().substring(0, 8) }

        val htmlSearch = HtmlSearchRule(
            url = converted.url,
            listSelector = bookList,
            titleSelector = ruleSearch.optString("name", ""),
            authorSelector = ruleSearch.optString("author", ""),
            coverSelector = ruleSearch.optString("coverUrl", ""),
            detailUrlSelector = ruleSearch.optString("bookUrl", ""),
            introSelector = ruleSearch.optString("intro", ""),
            charset = charset,
            method = converted.method,
            body = converted.body
        )

        val ruleBookInfo = parseRuleObject(obj, "ruleBookInfo") ?: JSONObject()
        val chapterName = ruleToc.optString("chapterName", "text").ifBlank { "text" }
        val chapterUrl = ruleToc.optString("chapterUrl", "href").ifBlank { "href" }
        val htmlChapters = HtmlChapterRule(
            url = "{id}",
            listSelector = chapterList,
            nameSelector = chapterName,
            hrefSelector = chapterUrl,
            tocUrlSelector = ruleBookInfo.optString("tocUrl", "").trim().ifBlank { null }
        )
        val htmlContent = HtmlContentRule(
            url = "{chapterUrl}",
            imageSelector = content
        )

        val searchRule = SearchRule(
            url = converted.url,
            method = "GET",
            listPath = "books",
            fields = BookFieldRule(),
            headers = headers
        )

        val config = SourceConfig(
            id = id,
            name = name,
            baseUrl = baseUrl,
            search = searchRule,
            detail = null,
            download = DownloadRule(),
            htmlSearch = htmlSearch,
            htmlChapters = htmlChapters,
            htmlContent = htmlContent,
            enabled = true,
            isCustom = true
        )
        return ConvertedSource(JsonBookSource(config), rawJson, name)
    }

    private fun parseRuleObject(obj: JSONObject, key: String): JSONObject? {
        val direct = obj.optJSONObject(key)
        if (direct != null) return direct
        val str = obj.optString(key, "")
        if (str.isBlank()) return null
        return try {
            JSONObject(str)
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /** 转换 Legado URL：去掉 ,{...} 选项后缀，{{key}} -> {keyword}，{{page}} -> {page}，支持简单运算。POST 源透传 method/body。 */
    private fun convertLegadoUrl(raw: String): LegadoUrl? {
        var url = raw.trim()
        var charset: String? = null
        // 去掉请求选项后缀 ,{ "charset": ... , "method": ... , "body": ... }
        val commaIdx = url.indexOf(",{")
        if (commaIdx > 0) {
            val candidate = url.substring(commaIdx + 1)
            if (candidate.trimStart().startsWith("{")) {
                val opt = try {
                    JSONObject(candidate)
                } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                if (opt != null) {
                    url = url.substring(0, commaIdx).trim()
                    charset = opt.optString("charset", "").ifBlank { null }
                    val optMethod = opt.optString("method", "GET").ifBlank { "GET" }
                    if (optMethod.equals("POST", ignoreCase = true)) {
                        // POST 搜索源：透传 method/body，不再跳过
                        return postUrl(url, opt)
                    }
                }
            }
        }
        if (url.contains("@js:") || url.contains("webView")) return null

        url = url
            .replace("{{key}}", "{keyword}")
            .replace("{{page}}", "{page}")
            .replace(Regex("""\{\{java\.base64Encode\(key\)\}\}""")) {
                "{keyword_b64}"
            }

        // 简单页面运算：{{(page-1)*20}}、{{page*10}}、{{(page-1)*50}} 等
        url = Regex("""\{\{([^{}]+)\}\}""").replace(url) { m ->
            val expr = m.groupValues[1].trim()
            evalPageExpression(expr) ?: m.value
        }
        return url.takeIf { !it.contains("{{") }
            ?.let { LegadoUrl(it, charset) }
    }

    private data class LegadoUrl(
        val url: String,
        val charset: String?,
        val method: String = "GET",
        val body: String? = null
    )

    /** POST 搜索源：处理 URL 与 body 模板中的 {{key}}/{{page}} 占位符，不再直接跳过。 */
    private fun postUrl(url: String, opt: JSONObject): LegadoUrl? {
        var u = url
        if (u.contains("@js:") || u.contains("webView")) return null
        u = u
            .replace("{{key}}", "{keyword}")
            .replace("{{page}}", "{page}")
            .replace(Regex("""\{\{java\.base64Encode\(key\)\}\}""")) {
                "{keyword_b64}"
            }
        var body = opt.optString("body", "").ifBlank { null }
        body = body
            ?.replace("{{key}}", "{keyword}")
            ?.replace("{{page}}", "{page}")
        val charset = opt.optString("charset", "").ifBlank { null }
        return u.takeIf { !it.contains("{{") }
            ?.let { LegadoUrl(it, charset, "POST", body) }
    }

    private fun evalPageExpression(expr: String): String? {
        val normalized = expr.replace(" ", "")
        if (normalized.contains("?")) return null
        // 仅支持 page 与数字的四则运算（页面首次加载固定为 page=1）
        val tokens = normalized.replace("page", "1")
        if (!tokens.matches(Regex("^[0-9+\\-*/()]+$"))) return null
        val result = try {
            SimpleArithmetic(tokens).evaluate()
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } ?: return null
        return result.toString()
    }

    /** 极简四则运算求值器（仅数字、+ - * / 与括号）。 */
    private class SimpleArithmetic(private val expr: String) {
        private var pos = 0

        fun evaluate(): Long? {
            if(expr.length>256 || expr.count { it=='(' }>32) return null
            val value = expression() ?: return null
            return if (pos == expr.length) value else null
        }

        private fun peek(): Char? = if (pos < expr.length) expr[pos] else null

        private fun number(): Long? {
            val sb = StringBuilder()
            while (peek()?.isDigit() == true) {
                sb.append(peek())
                pos++
            }
            return sb.toString().toLongOrNull()
        }

        private fun factor(): Long? {
            if (peek() == '(') {
                pos++
                val v = expression() ?: return null
                if (peek() != ')') return null
                pos++
                return v
            }
            return number()
        }

        private fun term(): Long? {
            var value = factor() ?: return null
            while (true) {
                when (peek()) {
                    '*' -> {
                        pos++
                        value = Math.multiplyExact(value, factor() ?: return null)
                    }
                    '/' -> {
                        pos++
                        val divisor = factor() ?: return null
                        if (divisor == 0L) return null
                        value /= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun expression(): Long? {
            var value = term() ?: return null
            while (true) {
                when (peek()) {
                    '+' -> {
                        pos++
                        value = Math.addExact(value, term() ?: return null)
                    }
                    '-' -> {
                        pos++
                        value = Math.subtractExact(value, term() ?: return null)
                    }
                    else -> return value
                }
            }
        }
    }

    private fun parseHeaderString(headerJson: String): Map<String, String> {
        if (headerJson.isBlank()) return emptyMap()
        return try {
            parseHeaders(JSONObject(headerJson))
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            emptyMap()
        }
    }

    private fun String?.isNull_blank(): Boolean = this == null || this.trim().isEmpty()

    private fun parseHeaders(headersObj: JSONObject?): Map<String, String> {
        if (headersObj == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        headersObj.keys().forEach { key ->
            val value = headersObj.optString(key).trim()
            if (value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private data class ConvertedSource(
        val source: JsonBookSource?,
        val rawJson: String,
        val name: String = "未知书源",
        val reason: String? = null
    )
}
