package com.example.source.js

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

/**
 * 漫画源代理路由。JS 请求优先用 Cronet；需要 OkHttp 时按系统代理转发。
 *
 * 背景：picacg 的 API 与图片域名（*.picacomic.com、*.picacmic.com）直连可能超时（PC 端已复现：
 * 直连连接超时，走代理登录 HTTP 200）；JS 桥默认走 Cronet，而 Cronet 不支持显式代理。
 * picacg 请求直接用 OkHttp；其它 JS 请求先走 Cronet，失败且设备设置了
 * 系统代理时用 OkHttp 重试。记住成功的代理路径，后续请求避免重复等待直连失败。
 *
 * 通过 [selector] 挂到 JS 的 OkHttp 客户端，阅读与下载使用 [failoverClient]。
 */
object JsSourceProxy {

    /** 同时匹配当前 API 域名与旧图床域名。 */
    private val PICACG_DOMAINS = setOf("picacomic.com", "picacmic.com")

    @Volatile
    private var cachedSystemProxy: Pair<Long, Proxy?>? = null
    private data class Route(val proxy: Proxy, val expiresAt: Long)
    private val successfulProxyRoutes = ConcurrentHashMap<String, Route>()
    private const val ROUTE_TTL_MS = 5 * 60_000L
    private fun authority(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching null
        "${uri.scheme?.lowercase()}://$host:${uri.port}"
    }.getOrNull()
    internal fun forgetRoute(url: String) { authority(url)?.let { successfulProxyRoutes.remove(it) } }
    internal fun rememberProxySuccess(context: Context, url: String) {
        val proxy = cachedSystemProxy(context) ?: return
        val key = authority(url) ?: return
        if (successfulProxyRoutes.size >= 128 && !successfulProxyRoutes.containsKey(key)) {
            successfulProxyRoutes.keys.firstOrNull()?.let { successfulProxyRoutes.remove(it) }
        }
        successfulProxyRoutes[key] = Route(proxy, android.os.SystemClock.elapsedRealtime() + ROUTE_TTL_MS)
    }
    private val cancellationWatcher by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "comic-proxy-cancellation").apply { isDaemon = true }
        }
    }

    /** Picacg and origins that recently succeeded through the configured proxy use OkHttp. */
    fun matches(context: Context?, url: String): Boolean = forUrl(context, url) != null

    fun forUrl(context: Context?, url: String): Proxy? {
        if (context == null || url.isEmpty()) return null
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isEmpty()) return null
        val isPicacg = PICACG_DOMAINS.any { host == it || host.endsWith(".$it") }
        val proxy = cachedSystemProxy(context) ?: return null
        if (isPicacg) return proxy
        val key = authority(url) ?: return null
        val learned = successfulProxyRoutes[key] ?: return null
        if (learned.proxy != proxy || learned.expiresAt <= android.os.SystemClock.elapsedRealtime()) {
            successfulProxyRoutes.remove(key, learned)
            return null
        }
        return proxy
    }

    private fun cachedSystemProxy(context: Context): Proxy? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedSystemProxy
        if (cached != null && now - cached.first < 2_000) return cached.second
        val resolved = com.example.source.zlibrary.network.SystemProxyResolver.resolve(context)
        cachedSystemProxy = now to resolved
        return resolved
    }

    /** Learn successful proxy routes so neighbouring pages do not repeat a failed direct attempt. */
    fun failoverClient(context: Context, base: OkHttpClient): OkHttpClient {
        val builder = base.newBuilder().proxy(Proxy.NO_PROXY)
        val proxyClient = AtomicReference<Pair<Proxy, OkHttpClient>?>(null)
        // 图床域名被本地 DNS 解析到坏节点时，用已验证边缘 IP 兜底重连
        builder.dns(SourceDns.dns())
        builder.interceptors().add(0, Interceptor { chain ->
                val request = chain.request()
                if (request.method != "GET") return@Interceptor chain.proceed(request)
                fun throughProxy(): okhttp3.Response? {
                    val proxy = cachedSystemProxy(context) ?: return null
                    val client = proxyClient.get()?.takeIf { it.first == proxy }?.second
                        ?: base.newBuilder().proxy(proxy).build().also { proxyClient.set(proxy to it) }
                    val call = client.newCall(request)
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                    // The fallback is a separate Call. Keep its socket tied to cancellation of
                    // the original reader/download Call, including while its body is being read.
                    val watch = cancellationWatcher.scheduleWithFixedDelay({
                        if (chain.call().isCanceled()) call.cancel()
                    }, 0, 100, TimeUnit.MILLISECONDS)
                    try {
                        val response = call.execute()
                        if (response.isSuccessful) rememberProxySuccess(context, request.url.toString())
                        val body = response.body
                        if (body == null) { watch.cancel(false); return response }
                        val source = object : ForwardingSource(body.source()) {
                            override fun close() {
                                try { super.close() } finally { watch.cancel(false) }
                            }
                        }.buffer()
                        return response.newBuilder().body(object : ResponseBody() {
                            override fun contentType() = body.contentType()
                            override fun contentLength() = body.contentLength()
                            override fun source() = source
                        }).build()
                    } catch (e: Exception) { watch.cancel(false); call.cancel(); throw e }
                }
                // Honour an explicitly configured system proxy for image/download requests.
                // Direct-first could return headers but stall on the body, outside this
                // interceptor's fallback path, wasting every retry on the same direct route.
                val localHost = request.url.host == "localhost" || request.url.host == "::1" ||
                    request.url.host.startsWith("127.")
                if (!localHost && cachedSystemProxy(context) != null) {
                    val response = try { throughProxy() } catch (e: IOException) {
                        if (chain.call().isCanceled()) throw e
                        forgetRoute(request.url.toString())
                        return@Interceptor chain.proceed(request)
                    }
                    if (response == null) return@Interceptor chain.proceed(request)
                    if (response.code == 403 || response.code == 429 || response.code >= 500) {
                        response.close()
                        forgetRoute(request.url.toString())
                        return@Interceptor chain.proceed(request)
                    }
                    return@Interceptor response
                }
                val direct = try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    return@Interceptor throughProxy() ?: throw e
                }
                // Cloudflare 挑战：无头 WebView 过盾一次，带上新 Cookie 重试（Mihon 同款方案）
                if ((direct.code == 403 || direct.code == 503 || direct.code == 429) &&
                    CfWebViewSolver.isChallengeResponse(
                        direct.code,
                        mapOf(
                            "cf-mitigated" to (direct.header("cf-mitigated") ?: ""),
                            "server" to (direct.header("server") ?: "")
                        )
                    )
                ) {
                    val solved = runCatching {
                        CfWebViewSolver.solveAndSync(
                            context, request.url.toString(),
                            request.header("User-Agent") ?: "",
                            request.headers.toMap()
                        )
                    }.getOrDefault(false)
                    direct.close()
                    if (solved) {
                        val cookie = JsCookieJar.cookieHeader(context, request.url.toString())
                        val retry = if (cookie.isNotBlank()) {
                            request.newBuilder().header("Cookie", cookie).build()
                        } else {
                            request
                        }
                        Log.i("JsSourceProxy", "cloudflare solved, retry ${request.url.host}")
                        return@Interceptor chain.proceed(retry)
                    }
                    Log.w("JsSourceProxy", "cloudflare solve failed for ${request.url.host}; fallback to proxy route")
                    // 未解出：继续走原有的代理回退
                    return@Interceptor throughProxy()
                        ?: throw IOException("Cloudflare 盾自动验证失败（${request.url.host}）")
                }
                if (direct.code != 403 && direct.code != 429 && direct.code < 500) {
                    direct
                } else if (cachedSystemProxy(context) == null) {
                    direct
                } else {
                    direct.close()
                    throughProxy() ?: throw IOException("system proxy disappeared")
                }
            })
        return builder.build()
    }

    /**
     * 给 JS 桥的 OkHttp 回退客户端使用：设备配置了系统代理时经代理转发。
     */
    fun selector(context: Context?): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val viaProxy = if (uri?.scheme.equals("http", true) ||
                uri?.scheme.equals("https", true)
            ) context?.let { cachedSystemProxy(it) } else null
            if (viaProxy != null) return listOf(viaProxy)
            // 与未挂本 selector 时的默认行为保持一致
            return runCatching { ProxySelector.getDefault()?.select(uri) }.getOrNull()
                ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }
}
