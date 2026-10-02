package com.example.mangatranslate

import kotlinx.coroutines.Dispatchers
import com.example.source.executeCancellable
import com.example.data.readImportBytes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 文本翻译引擎接口：目标语言恒为简体中文。 */
interface TextTranslator {
    /** 翻译单段文本；失败返回 null（调用方保留原文显示）。 */
    suspend fun translate(text: String, sourceLang: String): String?

    /** 预热（语言包下载等），返回 null=成功 或 失败原因。 */
    suspend fun prepare(sourceLang: String): String?

    fun close()
}

/** 语言检测（纯函数，供单测）：按文字系统启发式。 */
object ScriptDetector {
    /** 返回语言码：ja / en / ko / zh；无法判断返回 null。 */
    fun detect(text: String): String? {
        var kana = 0
        var hangul = 0
        var latin = 0
        var cjk = 0
        for (ch in text) {
            when {
                ch.code in 0x3040..0x30FF || ch.code == 0x31F0 || ch.code == 0x31F1 -> kana++
                ch.code in 0xAC00..0xD7A3 || ch.code in 0x1100..0x11FF -> hangul++
                ch.code in 0x61..0x7A || ch.code in 0x41..0x5A -> latin++
                ch.code in 0x4E00..0x9FFF -> cjk++
            }
        }
        val total = (kana + hangul + latin + cjk).coerceAtLeast(1)
        return when {
            kana > 0 && kana * 3 >= total / 4 -> "ja"
            hangul > 0 && hangul * 2 >= total -> "ko"
            latin * 2 >= total -> "en"
            // 纯汉字按中文跳过翻译（第十九轮实测：中文电子书被误判 ja 会导致乱序覆盖）。
            // 无假名的纯日文极罕见——漫画对白几乎必带假名，误跳过的代价远小于错翻。
            cjk * 2 >= total -> "zh"
            else -> null
        }
    }
}

/**
 * 在线兜底翻译（第十七轮）：腾讯交互翻译 transmart（国内直连免费，批量多句）。
 * 旧 Google gtx 仅作备源（国内网络通常不可达）。
 */
class OnlineFallbackTranslator(private val context:android.content.Context?=null) : TextTranslator {

    private val client by lazy {
        com.example.source.SharedHttpTransport.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun prepare(source: String): String? = null

    override suspend fun translate(text: String, sourceLang: String): String? {
        val out = translateBatch(listOf(text), sourceLang)
        return out?.firstOrNull()
    }

    /**
     * 批量翻译（腾讯 transmart /api/imt 一次请求多句，保持顺序）。
     * 返回与输入等长的译文列表（失败元素为 null→整体 null）。
     */
    suspend fun translateBatch(texts: List<String>, sourceLang: String): List<String>? =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            if(context!=null && !TranslationPrivacy.allowed(context)) return@withContext null
            require(texts.size<=500 && texts.sumOf { it.length }<=128_000) { "翻译请求过大" }
            val srcLang = when (sourceLang) {
                "ja" -> "ja"; "en" -> "en"; "zh" -> "zh"
                else -> "auto"
            }
            val body = JSONObject()
                .put(
                    "header",
                    JSONObject()
                        .put("fn", "auto_translation")
                        .put("session", "")
                        .put("client_key", "browser-chromium-131.0.0.0")
                )
                .put("source", JSONObject().put("text_list", JSONArray(texts)).put("lang", srcLang))
                .put("target", JSONObject().put("lang", "zh"))
            val request = Request.Builder()
                .url("https://transmart.qq.com/api/imt")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Content-Type", "application/json")
                .header("Referer", "https://transmart.qq.com/")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).executeCancellable().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val raw = resp.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: return@runCatching null
                    parseTransmart(raw, texts.size)
                }
            }.getOrElse { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null } ?: gtxFallback(texts, sourceLang)
        }

    /** 腾讯响应解析：{"auto_translation":["译1","译2",...]}。长度必须与请求一致。 */
    internal fun parseTransmart(raw: String, expectCount: Int): List<String>? = runCatching {
        val obj = JSONObject(raw)
        if (obj.optJSONObject("header")?.optString("ret_code") != "succ") return@runCatching null
        val arr = obj.optJSONArray("auto_translation") ?: return@runCatching null
        if (arr.length() != expectCount) return@runCatching null
        List(expectCount) { i -> arr.optString(i) }
    }.getOrNull()

    /** 备源：Google gtx（逐句；国内网络一般不可达，仅海外/代理兜底）。 */
    private suspend fun gtxFallback(texts: List<String>, sourceLang: String): List<String>? =
        withContext(Dispatchers.IO) {
            val results = texts.map { text ->
                runCatching {
                    val sl = if (sourceLang == "zh") "auto" else sourceLang
                    val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                        "&sl=$sl&tl=zh-CN&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8")
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        .build()
                    client.newCall(request).executeCancellable().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        val body = resp.body?.byteStream()?.use { it.readImportBytes(2*1024*1024).toString(Charsets.UTF_8) } ?: return@runCatching null
                        parseGtx(body)
                    }
                }.getOrElse { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
            }
            @Suppress("UNCHECKED_CAST")
            if (results.any { it == null }) null else results as List<String>
        }

    internal fun parseGtx(body: String): String? = runCatching {
        val root = JSONArray(body)
        val segments = root.optJSONArray(0) ?: return@runCatching null
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            val translated = seg.optString(0)
            if (translated.isNotEmpty()) sb.append(translated)
        }
        sb.toString().ifBlank { null }
    }.getOrNull()

    override fun close() {}

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }
}
