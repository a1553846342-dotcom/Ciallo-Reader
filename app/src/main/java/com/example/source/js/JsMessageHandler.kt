package com.example.source.js

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.library.ImageBytes
import com.example.data.readImportBytes
import com.example.source.executeWithCancellation
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.chromium.net.CronetEngine
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import org.json.JSONObject
import org.json.JSONArray

/**
 * Venera JS 运行时消息桥：处理 JS 侧 sendMessage(...) 的所有方法。
 * 覆盖 convert / http / cookie / html / storage / UI / 工具函数。
 */
class JsMessageHandler(
    private val context: Context,
    private val sourceKey: String,
    private val insecureTls: Boolean = false
) {
    private val dataPrefs = context.getSharedPreferences("js_source_data", Context.MODE_PRIVATE)
    val htmlStore = JsHtmlStore()

    @Volatile internal var requestJob: kotlinx.coroutines.Job? = null
    private val settingDefaults = HashMap<String, String>()
    private val client = buildClient(insecureTls)
    private val publicDirectRoutes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 命中 JS 源代理路由的 URL 改走 OkHttp（代理在 client 的 ProxySelector 里；Cronet 不支持显式代理）。 */
    private fun useProxyRoute(url: String): Boolean = JsSourceProxy.matches(context, url)

    // ---- 真实 Bitmap 图像桥：供 onImageLoad.modifyImage 等脚本做像素级重排 ----
    private val images = LinkedHashMap<Int, Bitmap>()
    private val imageKeyGen = AtomicInteger(1)
    private val imageBudget: Long get() = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)
    private fun checkImageAllocation(width: Int, height: Int) = synchronized(images) {
        val pixels=width.toLong()*height
        require(pixels<=imageBudget/4) { "脚本图片尺寸过大" }
        val requested=pixels*4
        require(width>0 && height>0 && requested<=imageBudget &&
            images.values.distinct().sumOf { it.allocationByteCount.toLong() }+requested<=imageBudget) { "脚本图像内存预算不足" }
    }
    private fun putImage(bmp: Bitmap): Int = synchronized(images) {
        require(images.size<48) { "脚本创建的图片过多" }
        val key=imageKeyGen.incrementAndGet()
        images[key]=bmp
        key
    }
    fun beginImageSession(): Set<Int> = synchronized(images) { images.keys.toSet() }
    fun endImageSession(previous: Set<Int>) = synchronized(images) {
        val keys=images.keys.filter { it !in previous }
        keys.forEach { disposeImage(it) }
    }

    private fun getImage(key: Int): Bitmap? = synchronized(images) { images[key] }

    /** 供 JsSourceEngine 解码一张图片并登记到桥中 */
    fun createImage(bytes: ByteArray): Int? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        checkImageAllocation(bounds.outWidth,bounds.outHeight)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return putImage(bmp)
    }

    /** 供 JsSourceEngine 导出处理后的图片（PNG） */
    fun exportImageBytes(key: Int): ByteArray? {
        val bmp = getImage(key) ?: return null
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        return out.toByteArray()
    }

    fun disposeImage(key: Int) {
        synchronized(images) {
            val bitmap=images.remove(key)
            if(bitmap!=null && images.values.none { it===bitmap }) bitmap.recycle()
        }
    }

    /** 用 Cronet（Chromium 网络栈）直接下载图片字节，用于 H@H 等 OkHttp 握手不兼容的图床。 */
    fun fetchImageBytes(url: String, headers: Map<String, String>, job: kotlinx.coroutines.Job? = null): ByteArray? {
        if (useProxyRoute(url)) {
            return runCatching {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).executeWithCancellation(job).use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.byteStream()?.use { it.readImportBytes(16 * 1024 * 1024) }
                }
            }.getOrNull()
        }
        val res = cronetRequest("GET", url, headers, null, job)
        if (res.error != null || res.status !in 200..299) return null
        return res.body
    }

    private fun handleImage(msg: Map<*, *>): Any? {
        val function = msg["function"] as? String ?: return null
        return try {
            when (function) {
                "emptyImage" -> {
                    val w = (msg["width"] as? Number)?.toInt() ?: return null
                    val h = (msg["height"] as? Number)?.toInt() ?: return null
                    if (w <= 0 || h <= 0) null
                    else { checkImageAllocation(w,h); putImage(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)) }
                }
                "copyRange" -> {
                    val key = (msg["key"] as? Number)?.toInt() ?: return null
                    val src = getImage(key) ?: return null
                    val x = (msg["x"] as? Number)?.toInt() ?: 0
                    val y = (msg["y"] as? Number)?.toInt() ?: 0
                    val w = (msg["width"] as? Number)?.toInt() ?: 0
                    val h = (msg["height"] as? Number)?.toInt() ?: 0
                    if (w <= 0 || h <= 0 || x + w > src.width || y + h > src.height) null
                    else { checkImageAllocation(w,h); putImage(Bitmap.createBitmap(src, x, y, w, h)) }
                }
                "copyAndRotate90" -> {
                    val key = (msg["key"] as? Number)?.toInt() ?: return null
                    val src = getImage(key) ?: return null
                    checkImageAllocation(src.height,src.width)
                    val matrix = Matrix().apply { setRotate(90f) }
                    putImage(
                        Bitmap.createBitmap(
                            src, 0, 0, src.width, src.height, matrix, true
                        )
                    )
                }
                "fillImageAt" -> {
                    val dst = getImage((msg["key"] as? Number)?.toInt() ?: return null) ?: return null
                    val srcKey = (msg["image"] as? Number)?.toInt() ?: return null
                    val src = getImage(srcKey) ?: return null
                    val x = (msg["x"] as? Number)?.toFloat() ?: 0f
                    val y = (msg["y"] as? Number)?.toFloat() ?: 0f
                    Canvas(dst).drawBitmap(src, x, y, null)
                    null
                }
                "fillImageRangeAt" -> {
                    val dst = getImage((msg["key"] as? Number)?.toInt() ?: return null) ?: return null
                    val srcKey = (msg["image"] as? Number)?.toInt() ?: return null
                    val src = getImage(srcKey) ?: return null
                    val x = (msg["x"] as? Number)?.toInt() ?: 0
                    val y = (msg["y"] as? Number)?.toInt() ?: 0
                    val sx = (msg["srcX"] as? Number)?.toInt() ?: 0
                    val sy = (msg["srcY"] as? Number)?.toInt() ?: 0
                    val w = (msg["width"] as? Number)?.toInt() ?: 0
                    val h = (msg["height"] as? Number)?.toInt() ?: 0
                    if (w <= 0 || h <= 0) return null
                    val srcRect = Rect(sx, sy, sx + w, sy + h)
                    val dstRect = Rect(x, y, x + w, y + h)
                    Canvas(dst).drawBitmap(src, srcRect, dstRect, null)
                    null
                }
                "getWidth" -> getImage((msg["key"] as? Number)?.toInt() ?: return null)?.width
                "getHeight" -> getImage((msg["key"] as? Number)?.toInt() ?: return null)?.height
                else -> null
            }
        } catch (e: Exception) {
            Log.w("JsSource[$sourceKey]", "image $function failed", e)
            null
        }
    }
    private val cronetExecutor = Executors.newSingleThreadExecutor()
    private val cronetEngine: CronetEngine by lazy {
        CronetEngine.Builder(context)
            // 部分站点（拷贝漫画/漫画柜）对 HTTP/2 直接断连，强制 HTTP/1.1 与 Venera 一致
            .enableHttp2(sourceKey in setOf("mycomic", "nhentai", "pufei", "bilimanga"))
            .enableBrotli(true)
            .enableQuic(false)
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
            )
            .build()
    }

    fun setSettingDefaults(defaults: Map<String, String>) {
        settingDefaults.putAll(defaults)
    }

    fun setLoggedIn(logged: Boolean) {
        dataPrefs.edit().putBoolean("logged_$sourceKey", logged).apply()
    }

    fun isLoggedIn(): Boolean = dataPrefs.getBoolean("logged_$sourceKey", false)

    private fun buildClient(insecure: Boolean): OkHttpClient {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val builder = com.example.source.SharedHttpTransport.builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // 图床域名被本地 DNS 解析到坏节点时，用已验证边缘 IP 兜底重连
            .dns(SourceDns.dns())
            // 命中 JS 源代理路由的域名走显式/系统代理，其余直连
            .proxySelector(JsSourceProxy.selector(context))
            // Venera(Dio) 默认 HTTP/1.1；部分站点/代理对 HTTP/2 直接断连，禁用 h2 提高兼容性
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.headers.names().none { it.equals("Accept", ignoreCase = true) }) {
                    b.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                }
                if (req.headers.names().none { it.equals("Accept-Language", ignoreCase = true) }) {
                    b.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                }
                chain.proceed(b.build())
            }
        builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            if (insecure && request.headers.names().any { it.equals("Cookie", true) || it.equals("Authorization", true) })
                throw java.io.IOException("不安全 TLS 书源不能发送登录凭据，请启用证书验证")
            chain.proceed(request)
        }
        if (insecure) {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll[0])
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    fun handle(message: Any?): Any? {
        val msg = message as? Map<*, *> ?: return null
        val method = msg["method"] as? String ?: return null
        return when (method) {
            "delay" -> {
                val t = (msg["time"] as? Number)?.toLong() ?: 0L
                if (t > 0) Thread.sleep(t)
                null
            }
            "convert" -> handleConvert(msg)
            "http" -> handleHttp(msg)
            "cookie" -> handleCookie(msg)
            "html" -> handleHtml(msg)
            "load_data" -> {
                val key = dataKey(msg["data_key"])
                loadStored(dataPrefs.getString(key, null))
            }
            "save_data" -> {
                dataPrefs.edit().putString(dataKey(msg["data_key"]), encodeStored(msg["data"])).apply()
                null
            }
            "delete_data" -> {
                dataPrefs.edit().remove(dataKey(msg["data_key"])).apply()
                null
            }
            "load_setting" -> {
                val key = msg["setting_key"] as? String ?: return null
                // 用户手动保存的设置优先，其次才用脚本里的默认值
                dataPrefs.getString("setting_${sourceKey}_$key", null)
                    ?: settingDefaults[key]
            }
            "isLogged" -> dataPrefs.getBoolean("logged_$sourceKey", false)
            "UI" -> handleUi(msg)
            "getLocale" -> "zh-CN"
            "getPlatform" -> "android"
            "setClipboard" -> null
            "getClipboard" -> ""
            "uuid" -> UUID.randomUUID().toString()
            "random" -> handleRandom(msg)
            "log" -> {
                Log.i("JsSource[$sourceKey]", "${msg["title"]}: ${msg["content"]}")
                null
            }
            "compute" -> null
            "image" -> handleImage(msg)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // convert
    // ------------------------------------------------------------------

    private fun handleConvert(msg: Map<*, *>): Any? {
        val type = msg["type"] as? String ?: return null
        val value = msg["value"]
        val isEncode = msg["isEncode"] == true
        val isString = msg["isString"] == true
        return try {
            when (type) {
                "utf8", "gbk" -> {
                    val charset = if (type == "gbk") Charset.forName("GBK") else Charsets.UTF_8
                    if (isEncode) (value as? String)?.toByteArray(charset)
                    else bytesOf(value)?.toString(charset)
                }
                "base64" -> {
                    if (isEncode) bytesOf(value)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                    else (value as? String)?.let { Base64.decode(it, Base64.NO_WRAP) }
                }
                "md5", "sha1", "sha256", "sha512" -> {
                    val alg = when (type) {
                        "md5" -> "MD5"
                        "sha1" -> "SHA-1"
                        "sha256" -> "SHA-256"
                        else -> "SHA-512"
                    }
                    bytesOf(value)?.let { MessageDigest.getInstance(alg).digest(it) }
                }
                "hmac" -> {
                    val key = bytesOf(msg["key"]) ?: return null
                    val data = bytesOf(value) ?: return null
                    val alg = when (msg["hash"] as? String) {
                        "md5" -> "HmacMD5"
                        "sha1" -> "HmacSHA1"
                        "sha256" -> "HmacSHA256"
                        "sha512" -> "HmacSHA512"
                        else -> "HmacSHA256"
                    }
                    val mac = Mac.getInstance(alg)
                    mac.init(SecretKeySpec(key, alg))
                    val digest = mac.doFinal(data)
                    if (isString) digest.joinToString("") { "%02x".format(it) } else digest
                }
                "aes-ecb", "aes-cbc", "aes-cfb", "aes-ofb" -> {
                    val mode = when (type) {
                        "aes-ecb" -> "AES/ECB/NoPadding"
                        "aes-cbc" -> "AES/CBC/PKCS5Padding"
                        "aes-cfb" -> "AES/CFB/NoPadding"
                        else -> "AES/OFB/NoPadding"
                    }
                    val key = bytesOf(msg["key"]) ?: return null
                    val data = bytesOf(value) ?: return null
                    val cipher = Cipher.getInstance(mode)
                    val keySpec = SecretKeySpec(key, "AES")
                    val iv = bytesOf(msg["iv"])
                    if (iv != null && iv.isNotEmpty()) {
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
                    } else {
                        cipher.init(Cipher.DECRYPT_MODE, keySpec)
                    }
                    cipher.doFinal(data)
                }
                "hex" -> bytesOf(value)?.joinToString("") { "%02x".format(it) }
                else -> null
            }
        } catch (e: Exception) {
            Log.w("JsSource[$sourceKey]", "convert $type failed", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // http
    // ------------------------------------------------------------------

    private fun handleHttp(msg: Map<*, *>): Any? {
        val url = msg["url"] as? String ?: return mapOf("error" to "缺少 url")
        val httpMethod = (msg["http_method"] as? String ?: "GET").uppercase()
        val bytes = msg["bytes"] == true
        return try {
            val effectiveHeaders = LinkedHashMap<String, String>()
            val sourceHeaders = msg["headers"] as? Map<*, *>
            var hasUa = false
            sourceHeaders?.forEach { (k, v) ->
                if (k != null && v != null) {
                    effectiveHeaders[k.toString()] = v.toString()
                    if (k.toString().equals("User-Agent", ignoreCase = true)) hasUa = true
                }
            }
            if (!hasUa) {
                effectiveHeaders["User-Agent"] =
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
            }
            if (effectiveHeaders.keys.none { it.equals("Accept", ignoreCase = true) }) {
                effectiveHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            }
            if (effectiveHeaders.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
                effectiveHeaders["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
            }
            // 合并脚本显式 Cookie 与本地 CookieJar：之前直接覆盖会把脚本里
            // 的 cookie（如 ehentai 的 nw=1）丢掉，导致站点响应异常/挂起。
            val scriptCookie = sourceHeaders?.entries
                ?.firstOrNull { it.key.toString().equals("Cookie", ignoreCase = true) }
                ?.value?.toString()?.trim()
            val jarCookie = getCookiesFor(url).trim()
            val mergedCookie = listOfNotNull(scriptCookie, jarCookie)
                .filter { it.isNotBlank() }
                .joinToString("; ")
            if (mergedCookie.isNotBlank()) effectiveHeaders["Cookie"] = mergedCookie

            val declaredContentType = effectiveHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.toMediaTypeOrNull()
            val data = msg["data"]
            val structured = data is Map<*, *> || data is List<*>
            val bodyBytes = when (data) {
                is ByteArray -> data
                is String -> data.toByteArray(Charsets.UTF_8)
                is Map<*, *> -> JSONObject(data).toString().toByteArray(Charsets.UTF_8)
                is List<*> -> JSONArray(data).toString().toByteArray(Charsets.UTF_8)
                else -> if (httpMethod in setOf("POST", "PUT", "PATCH")) ByteArray(0) else null
            }
            val bodyType = declaredContentType ?: when {
                structured -> "application/json; charset=utf-8".toMediaType()
                data is ByteArray -> "application/octet-stream".toMediaType()
                data is String -> "text/plain; charset=utf-8".toMediaType()
                else -> null
            }
            if (bodyType != null && declaredContentType == null) {
                effectiveHeaders["Content-Type"] = bodyType.toString()
            }
            val body = bodyBytes?.toRequestBody(bodyType)

            val publicReadBatch = sourceKey == "ikmmh" && httpMethod == "POST" &&
                url.toHttpUrl().encodedPath == "/api/comic/read/pics"
            if ((httpMethod == "GET" && sourceKey in setOf("pufei", "bilimanga", "ikmmh") || publicReadBatch) && !insecureTls) {
                return handleComicRead(url, effectiveHeaders, bytes, httpMethod, body)
            }

            val proxied = useProxyRoute(url)
            if (!insecureTls && !proxied) {
                // 走 Cronet（Chromium 网络栈）：浏览器级 TLS 指纹，绕过按 Java 客户端封禁的站点
                var res = cronetRequest(httpMethod, url, effectiveHeaders, bodyBytes)
                // Cloudflare 挑战：用无头 WebView 自动过盾一次（cf_clearance 会同步进共享
                // Cookie 存储），刷新请求头里的 Cookie 后重试原请求
                if (res.error == null && CfWebViewSolver.isChallengeResponse(res.status, res.headers)) {
                    val solved = runCatching {
                        CfWebViewSolver.solveAndSync(
                            context, url,
                            effectiveHeaders.entries.firstOrNull {
                                it.key.equals("User-Agent", ignoreCase = true)
                            }?.value.orEmpty(),
                            effectiveHeaders
                        )
                    }.getOrDefault(false)
                    if (solved) {
                        val scriptCookie = sourceHeaders?.entries
                            ?.firstOrNull { it.key.toString().equals("Cookie", ignoreCase = true) }
                            ?.value?.toString()?.trim()
                        val freshJar = getCookiesFor(url).trim()
                        val merged = listOfNotNull(scriptCookie, freshJar)
                            .filter { it.isNotBlank() }
                            .joinToString("; ")
                        if (merged.isNotBlank()) effectiveHeaders["Cookie"] = merged
                        res = cronetRequest(httpMethod, url, effectiveHeaders, bodyBytes)
                        Log.i("JsSource[$sourceKey]", "cloudflare challenge solved; retry -> ${res.status}")
                    }
                }
                val systemProxy = com.example.source.zlibrary.network.SystemProxyResolver.resolve(context)
                val retryViaProxy = systemProxy != null &&
                    (res.error != null || res.status == 403 || res.status == 429 || res.status >= 500)
                val retryViaHttp = httpMethod == "GET" &&
                    ((sourceKey in setOf("pufei", "bilimanga") && res.error != null) ||
                        (sourceKey == "bilimanga" && res.status == 403))
                if (res.error != null || retryViaProxy || retryViaHttp) {
                    if (!retryViaProxy && !retryViaHttp) {
                        return JSONObject().put("error", res.error).toString()
                    }
                    Log.w("JsSource[$sourceKey]", "Cronet failed/status ${res.status}; retrying via OkHttp: ${res.error}")
                } else {
                    val respHeaders = LinkedHashMap<String, String>()
                    res.headers.forEach { (k, v) ->
                        if (!k.equals("Set-Cookie", ignoreCase = true)) {
                            respHeaders.putIfAbsent(k, v)
                        }
                    }
                    val obj = JSONObject()
                    obj.put("status", res.status)
                    obj.put("url", res.finalUrl ?: url)
                    obj.put("headers", JSONObject(respHeaders))
                    if (bytes) {
                        obj.put(
                            "body",
                            res.body?.let { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
                                ?: JSONObject.NULL
                        )
                    } else {
                        val text = res.body?.let { String(it, Charsets.UTF_8) } ?: ""
                        obj.put("body", text.ifEmpty { JSONObject.NULL })
                    }
                    return obj.toString()
                }
            }

            // 证书有问题的源走 OkHttp（可忽略证书校验）；命中代理路由的请求也走 OkHttp（代理见 ProxySelector）
            val builder = Request.Builder().url(url)
            effectiveHeaders.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.method(httpMethod, body).build()
            val result = client.newCall(request).executeWithCancellation(requestJob).use {
                if (httpMethod == "GET") {
                    if (it.isSuccessful) JsSourceProxy.rememberProxySuccess(context, url)
                    else if (it.code == 403 || it.code == 429 || it.code >= 500) JsSourceProxy.forgetRoute(url)
                }
                val respHeaders = LinkedHashMap<String, String>()
                it.headers.forEach { pair -> respHeaders.putIfAbsent(pair.first, pair.second) }
                saveCookies(url, it.headers("Set-Cookie"))
                val obj = JSONObject()
                obj.put("status", it.code)
                obj.put("url", it.request.url.toString())
                obj.put("headers", JSONObject(respHeaders))
                val b = it.body?.byteStream()?.use { input -> input.readImportBytes(16 * 1024 * 1024) }
                val decoded = b?.let { bytes -> ImageBytes.gunzipIfNeeded(bytes) }
                if (bytes) {
                    obj.put(
                        "body",
                        decoded?.let { data -> Base64.encodeToString(data, Base64.NO_WRAP) }
                            ?: JSONObject.NULL
                    )
                } else {
                    val text = decoded?.toString(Charsets.UTF_8) ?: ""
                    obj.put("body", text.ifEmpty { JSONObject.NULL })
                }
                obj.toString()
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** Both routes must consume a complete response before success is remembered. */
    private fun handleComicRead(url: String, headers: Map<String, String>, bytes: Boolean,
                                method: String, body: okhttp3.RequestBody?): String {
        val httpFirst = useProxyRoute(url) ||
            com.example.source.zlibrary.network.SystemProxyResolver.resolve(context) != null
        // Chromium inherits Android's HTTP proxy too. Verified public reading
        // endpoints can work directly while the configured proxy closes TLS.
        // Retry the same HTTPS endpoint, keeping certificate checks and cancellation.
        val host = url.toHttpUrl().host
        val publicMirror = sourceKey == "pufei" && host in setOf("www.guoman.net", "m.guoman.net") ||
            sourceKey == "ikmmh" && host == "ymcdnyfqdapp.ikmmh.com"
        val directMirrorRetry = httpFirst && publicMirror
        val preferDirect = publicMirror && (publicDirectRoutes[host] ?: 0L) > android.os.SystemClock.elapsedRealtime()
        // Bili's search ticket redemption deliberately returns an empty 200 response.
        val allowEmpty = sourceKey == "bilimanga" && url.toHttpUrl().queryParameter("search_guard") == "redeem"
        var requestUrl = url
        var lastError = "源站连接失败"
        val requestHeaders = LinkedHashMap(headers)
        for (attempt in 0..1) {
            requestJob?.ensureActive()
            val directMirror = if (preferDirect) attempt == 0 else attempt == 1 && directMirrorRetry
            val http = directMirror || if (preferDirect) true else if (attempt == 0) httpFirst else !httpFirst
            if (attempt != 0) {
                requestHeaders["Cache-Control"] = "no-cache"
                requestHeaders["Pragma"] = "no-cache"
                JsSourceProxy.forgetRoute(url)
                val merged = LinkedHashMap<String, String>()
                listOf(requestHeaders["Cookie"].orEmpty(), getCookiesFor(requestUrl)).forEach { cookies ->
                    cookies.split(';').forEach { pair ->
                        val name = pair.substringBefore('=').trim()
                        if (name.isNotEmpty() && '=' in pair) merged[name] = pair.substringAfter('=').trim()
                    }
                }
                if (merged.isNotEmpty()) requestHeaders["Cookie"] = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val response = try {
                if (http) {
                    val boundedClient = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
                        .apply { if (directMirror) proxy(java.net.Proxy.NO_PROXY) }.build()
                    boundedClient.newCall(Request.Builder().url(requestUrl).method(method, body).apply {
                        requestHeaders.forEach { (key, value) -> header(key, value) }
                    }.build()).executeWithCancellation(requestJob).use { result ->
                        val data = result.body?.byteStream()?.use { it.readImportBytes(16 * 1024 * 1024) }
                        saveCookies(result.request.url.toString(), result.headers("Set-Cookie"))
                        val responseHeaders = LinkedHashMap<String, String>()
                        result.headers.forEach { (key, value) -> if (!key.equals("Set-Cookie", true)) responseHeaders.putIfAbsent(key, value) }
                        CronetResult(result.code, responseHeaders, data?.let(ImageBytes::gunzipIfNeeded), null,
                            finalUrl = result.request.url.toString())
                    }
                } else {
                    val payload = body?.let { requestBody -> okio.Buffer().also { requestBody.writeTo(it) }.readByteArray() }
                    var result = cronetRequest(method, requestUrl, requestHeaders, payload, timeoutMs = 15_000)
                    if (result.error == null && CfWebViewSolver.isChallengeResponse(result.status, result.headers)) {
                        val solved = CfWebViewSolver.solveAndSync(context, requestUrl,
                            requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty(), requestHeaders)
                        if (solved) {
                            getCookiesFor(requestUrl).takeIf(String::isNotBlank)?.let { requestHeaders["Cookie"] = it }
                            result = cronetRequest(method, requestUrl, requestHeaders, payload, timeoutMs = 15_000)
                        }
                    }
                    result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CronetResult(-1, emptyMap(), null, e.message ?: e.javaClass.simpleName)
            }
            val empty = response.status in 200..299 && response.body?.isNotEmpty() != true && !allowEmpty
            val retryable = response.error != null || empty || response.status == 403 ||
                response.status == 429 || response.status >= 500
            if (retryable) {
                if (directMirror) publicDirectRoutes.remove(host)
                lastError = when {
                    empty -> "源站返回空响应，重新连接后仍未收到页面"
                    response.error != null -> "网络连接失败：${response.error}"
                    else -> "源站返回 HTTP ${response.status}"
                }
                if (empty && method == "GET") requestUrl = url.toHttpUrl().newBuilder()
                    .setQueryParameter("_ciallo_retry", System.currentTimeMillis().toString()).build().toString()
                Log.w("JsSource[$sourceKey]", "GET ${if (http) "OkHttp" else "Chromium"} failed: $lastError; attempt=${attempt + 1}")
                continue
            }
            if (response.status in 200..299) {
                if (directMirror) publicDirectRoutes[host] = android.os.SystemClock.elapsedRealtime() + 5 * 60_000
                else if (http) {
                    publicDirectRoutes.remove(host)
                    JsSourceProxy.rememberProxySuccess(context, url)
                }
            }
            val body = response.body ?: ByteArray(0)
            return JSONObject().put("status", response.status).put("url", response.finalUrl ?: requestUrl)
                .put("headers", JSONObject(response.headers)).put("body",
                    if (bytes) Base64.encodeToString(body, Base64.NO_WRAP) else decodeComicText(body, response.headers)).toString()
        }
        JsSourceProxy.forgetRoute(url)
        return JSONObject().put("error", "$lastError，请重试").toString()
    }

    private fun decodeComicText(bytes: ByteArray, headers: Map<String, String>): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty()
        val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declaredGbk = Regex("charset\\s*=\\s*[\"']?\\s*(gbk|gb2312|gb18030)", RegexOption.IGNORE_CASE)
            .containsMatchIn(contentType + "\n" + prefix)
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (declaredGbk || utf8.contains('\uFFFD')) bytes.toString(Charset.forName("GB18030")) else utf8
    }

    private data class CronetResult(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val error: String?,
        val finalUrl: String? = null
    )

    private fun cronetRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        job: kotlinx.coroutines.Job? = requestJob,
        timeoutMs: Long = 60_000
    ): CronetResult {
        val latch = CountDownLatch(1)
        val statusRef = AtomicReference(-1)
        val headersRef = AtomicReference<Map<String, String>>(emptyMap())
        val bodyOut = ByteArrayOutputStream()
        val errorRef = AtomicReference<String?>(null)
        val finalUrlRef = AtomicReference(url)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                newLocationUrl: String?
            ) {
                try {
                    request?.followRedirect()
                } catch (t: Throwable) {
                    Log.e("CronetCB", "onRedirectReceived", t)
                    errorRef.set(t.toString())
                    request?.cancel()
                    latch.countDown()
                }
            }

            override fun onResponseStarted(request: UrlRequest?, info: UrlResponseInfo?) {
                try {
                    statusRef.set(info?.httpStatusCode ?: -1)
                    finalUrlRef.set(info?.url ?: url)
                    val map = LinkedHashMap<String, String>()
                    info?.allHeaders?.forEach { (k, v) ->
                        if (k.equals("Set-Cookie", ignoreCase = true)) {
                            saveCookies(info?.url ?: url, v)
                        }
                        map.putIfAbsent(k, v.joinToString("; "))
                    }
                    headersRef.set(map)
                    request?.read(java.nio.ByteBuffer.allocateDirect(64 * 1024))
                } catch (t: Throwable) {
                    Log.e("CronetCB", "onResponseStarted", t)
                    errorRef.set(t.toString())
                    request?.cancel()
                    latch.countDown()
                }
            }

            override fun onReadCompleted(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                buffer: java.nio.ByteBuffer?
            ) {
                try {
                    if (buffer != null) {
                        val bytes = ByteArray(buffer.position())
                        buffer.rewind()
                        buffer.get(bytes)
                        require(bodyOut.size().toLong()+bytes.size<=16L*1024*1024) { "JS 网络响应超过 16MiB" }
                        bodyOut.write(bytes)
                    }
                    request?.read(java.nio.ByteBuffer.allocateDirect(64 * 1024))
                } catch (t: Throwable) {
                    Log.e("CronetCB", "onReadCompleted", t)
                    errorRef.set(t.toString())
                    request?.cancel()
                    latch.countDown()
                }
            }

            override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {
                latch.countDown()
            }

            override fun onFailed(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                error: org.chromium.net.CronetException?
            ) {
                val cause = error?.cause
                errorRef.set(
                    "[$url] " +
                        (error?.message ?: error?.javaClass?.simpleName ?: "Cronet 请求失败") +
                        if (cause != null) " | cause=${cause}" else ""
                )
                latch.countDown()
            }

            override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                errorRef.compareAndSet(null, "请求已取消")
                latch.countDown()
            }
        }

        val builder = cronetEngine.newUrlRequestBuilder(url, callback, cronetExecutor)
            .setHttpMethod(method)
            .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_MEDIUM)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (body != null) {
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                builder.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            }
            builder.setUploadDataProvider(UploadDataProviders.create(body), cronetExecutor)
        }
        val request = builder.build()
        request.start()
        // 部分代理/节点首次连接 e-hentai 等站点可能冷启动 30s+，放宽到 60s 避免误杀
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                job?.ensureActive()
                if (android.os.SystemClock.elapsedRealtime()>=deadline) {
                    request.cancel()
                    return CronetResult(-1, emptyMap(), null, "请求超时")
                }
            }
            job?.ensureActive()
        } catch(e: Exception) { request.cancel(); throw e }
        val err = errorRef.get()
        if (err != null) {
            return CronetResult(-1, emptyMap(), null, err)
        }
        val bodyBytes = bodyOut.toByteArray()
        return CronetResult(
            statusRef.get(),
            headersRef.get(),
            bodyBytes.takeIf { it.isNotEmpty() },
            null,
            finalUrlRef.get()
        )
    }

    // ------------------------------------------------------------------
    // cookie
    // ------------------------------------------------------------------

    private fun handleCookie(msg: Map<*, *>): Any? {
        val url = msg["url"] as? String ?: return null
        return when (msg["function"] as? String) {
            "set" -> {
                val cookies = msg["cookies"]
                when (cookies) {
                    is List<*> -> cookies.forEach { item ->
                        when (item) {
                            is Map<*, *> -> {
                                val name = item["name"]?.toString() ?: return@forEach
                                val value = item["value"]?.toString() ?: return@forEach
                                val domain = (item["domain"]?.toString() ?: hostOf(url))
                                    .removePrefix(".")
                                saveCookieList(domain, listOf("$name=$value"))
                            }
                            else -> item?.toString()?.let {
                                saveCookieList(hostOf(url), listOf(it))
                            }
                        }
                    }
                    is String -> saveCookieList(
                        hostOf(url),
                        cookies.split(";").map { it.trim() }.filter { it.isNotBlank() }
                    )
                    else -> Unit
                }
                null
            }
            // Network.getCookies promises Cookie objects, rather than "name=value" strings.
            // Include parent-domain cookies just as the HTTP request path does.
            "get" -> JSONArray(getCookiesFor(url).split(';').mapNotNull { raw ->
                val cookie = raw.trim()
                val separator = cookie.indexOf('=')
                if (separator <= 0) null else mapOf(
                    "name" to cookie.substring(0, separator),
                    "value" to cookie.substring(separator + 1),
                    "domain" to hostOf(url),
                    "path" to "/"
                )
            }).toString()
            "delete" -> {
                JsCookieJar.clear(context, hostOf(url))
                null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // html
    // ------------------------------------------------------------------

    private fun handleHtml(msg: Map<*, *>): Any? {
        val function = msg["function"] as? String ?: return null
        val key = (msg["key"] as? Number)?.toInt() ?: 0
        return when (function) {
            "parse" -> {
                val html = msg["data"] as? String ?: ""
                val jsKey = (msg["key"] as? Number)?.toInt() ?: 0
                htmlStore.parse(jsKey, html)
            }
            "dispose" -> {
                htmlStore.dispose(key)
                null
            }
            "querySelector" -> htmlStore.querySelector(key, msg["query"] as? String ?: "")
            "querySelectorAll" -> htmlStore.querySelectorAll(key, msg["query"] as? String ?: "")
            "getElementById" -> {
                val id = msg["id"] as? String ?: ""
                htmlStore.getElementById(key, id)
            }
            "dom_querySelector" -> htmlStore.domQuerySelector(key, msg["query"] as? String ?: "")
            "dom_querySelectorAll" -> htmlStore.domQuerySelectorAll(key, msg["query"] as? String ?: "")
            "getText" -> htmlStore.getText(key)
            "getAttributes" -> JSONObject(htmlStore.getAttributes(key)).toString()
            "getChildren" -> htmlStore.getChildren(key)
            "getNodes" -> htmlStore.getNodes(key)
            "getInnerHTML" -> htmlStore.getInnerHTML(key)
            "getParent" -> htmlStore.getParent(key)
            "getClassNames" -> htmlStore.getClassNames(key)
            "getId" -> htmlStore.getId(key)
            "getLocalName" -> htmlStore.getLocalName(key)
            "getPreviousSibling" -> htmlStore.getPreviousSibling(key)
            "getNextSibling" -> htmlStore.getNextSibling(key)
            "node_text" -> htmlStore.nodeText(key)
            "node_type" -> htmlStore.nodeType(key)
            "node_toElement" -> htmlStore.nodeToElement(key)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // UI / misc
    // ------------------------------------------------------------------

    private fun handleUi(msg: Map<*, *>): Any? {
        when (msg["function"] as? String) {
            "showMessage" -> Log.i("JsSource[$sourceKey]", "UI: ${msg["message"]}")
            "launchUrl" -> Log.i("JsSource[$sourceKey]", "launchUrl ignored: ${msg["url"]}")
            "showLoading" -> return 0
            "cancelLoading" -> return null
            "showInputDialog" -> return ""
            "showSelectDialog" -> return 0
            "showDialog" -> Log.i("JsSource[$sourceKey]", "dialog ignored: ${msg["title"]}")
        }
        return null
    }

    /**
     * 挂起版消息处理：仅在异步桥（sendMessage）里调用。
     * 与同步 handle 的区别：showInputDialog 会弹出真实对话框等待用户输入。
     */
    suspend fun handleAsync(message: Any?): Any? {
        val msg = message as? Map<*, *> ?: return handle(message)
        if (msg["method"] == "delay") {
            var remaining = ((msg["time"] as? Number)?.toLong() ?: 0).coerceIn(0, 45_000)
            while (remaining > 0) {
                requestJob?.ensureActive()
                val part = minOf(remaining, 100)
                kotlinx.coroutines.delay(part)
                remaining -= part
            }
            requestJob?.ensureActive()
            return null
        }
        if (msg["method"] == "UI" && msg["function"] == "showInputDialog") {
            val title = msg["title"] as? String ?: "输入"
            val image = msg["image"] as? String
            return awaitJsInputDialog(context, title, image)
        }
        return handle(message)
    }

    private fun handleRandom(msg: Map<*, *>): Any? {
        val min = (msg["min"] as? Number)?.toLong() ?: 0L
        val max = (msg["max"] as? Number)?.toLong() ?: 1L
        if (max <= min) return min
        return when (msg["type"] as? String) {
            "int" -> Random.nextLong(min, max)
            "double" -> Random.nextDouble(min.toDouble(), max.toDouble())
            else -> Random.nextLong(min, max)
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun dataKey(raw: Any?): String = "data_${sourceKey}_${raw ?: ""}"

    private fun encodeStored(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean, is Number -> value.toString()
        else -> {
            val wrapped = JSONObject.wrap(value)
            "JSON:" + wrapped.toString()
        }
    }

    private fun loadStored(raw: String?): Any? {
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun bytesOf(value: Any?): ByteArray? = when (value) {
        is ByteArray -> value
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
        else -> null
    }

    private fun hostOf(url: String): String {
        return try {
            URL(url).host
        } catch (e: Exception) {
            url.substringBefore('/')
        }
    }

    private fun getCookiesFor(url: String): String = JsCookieJar.cookieHeader(context, url)

    private fun saveCookieList(host: String, cookies: List<String>) {
        JsCookieJar.saveCookiePairs(context, host, cookies)
    }

    private fun saveCookies(url: String, setCookies: List<String>) {
        JsCookieJar.saveSetCookies(context, url, setCookies)
    }
}
