package com.example.source.js

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare 盾自动求解（Mihon/Tachiyomi 同款方案移植）：
 *
 * 1. 识别官方挑战响应：`cf-mitigated: challenge` + `server: cloudflare*`
 *    （https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/）。
 * 2. 用无界面 WebView（真实 Chromium 引擎）加载原 URL，让 JS 挑战自行执行；
 *    WebView 的 User-Agent 必须与后续网络请求一致，否则 cf_clearance 会被 Cloudflare 拒收
 *    （Mihon issue #511 的已知坑）。
 * 3. 挑战通过后 Cloudflare 自己重载页面并写入 cf_clearance；监听 onPageFinished
 *    检查 CookieManager 里出现新的 cf_clearance 即成功，最多等 30s。
 *    交互式 Turnstile 挑战（interactiveBegin 事件）无法无头求解，立即放弃。
 * 4. 成功后把 WebView CookieManager 里的 Cookie 同步进 JS 源共享存储
 *    （js_source_cookies，与 [JsCookieJar]/[JsMessageHandler] 同一份），
 *    之后的 Cronet/OkHttp/Coil/下载请求自动携带。
 *
 * 同一 host 并发命中挑战时共享同一次求解；失败后 5 分钟内不再重试，防止死循环烧流量。
 */
object CfWebViewSolver {

    private const val TAG = "CfSolver"
    private const val SOLVE_TIMEOUT_MS = 30_000L
    private const val FAIL_RETRY_INTERVAL_MS = 5 * 60_000L

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile
        var success: Boolean = false
    }

    /** host -> 进行中的求解；并发调用共享同一次 WebView。 */
    private val inflight = HashMap<String, Pending>()

    /** host -> 上次失败时间；失败后冷却期内直接返回 false。 */
    private val lastFailAt = HashMap<String, Long>()

    /** 官方挑战响应识别（状态码 + 响应头，键大小写不敏感）。 */
    fun isChallengeResponse(status: Int, headers: Map<String, String>): Boolean {
        if (status != 403 && status != 503 && status != 429) return false
        val mitigated = headers.entries.firstOrNull {
            it.key.equals("cf-mitigated", ignoreCase = true)
        }?.value.orEmpty()
        val server = headers.entries.firstOrNull {
            it.key.equals("server", ignoreCase = true)
        }?.value.orEmpty()
        return mitigated.contains("challenge", ignoreCase = true) &&
            server.contains("cloudflare", ignoreCase = true)
    }

    /**
     * 无头求解并把 Cookie 同步进 JS 源共享存储。阻塞调用线程，最多 [timeoutMs]。
     * @return true 表示拿到了新的 cf_clearance（或本次求解期间已由并发调用完成）。
     */
    fun solveAndSync(
        context: Context,
        url: String,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = SOLVE_TIMEOUT_MS
    ): Boolean {
        val host = hostOf(url) ?: return false
        if (!isPublicHost(host)) {
            Log.w(TAG, "refuse to solve non-public host: $host")
            return false
        }
        synchronized(lastFailAt) {
            lastFailAt[host]?.let {
                if (System.currentTimeMillis() - it < FAIL_RETRY_INTERVAL_MS) return false
            }
        }
        val pending: Pending = synchronized(inflight) {
            inflight[host]?.let { return sharedWait(it, timeoutMs) }
            Pending().also { inflight[host] = it }
        }
        try {
            val ok = runSolver(context, url, userAgent, extraHeaders, timeoutMs, pending)
            pending.success = ok
            return ok
        } finally {
            synchronized(inflight) { inflight.remove(host) }
        }
    }

    /** 其它线程已在解同一个 host：等待其结果，不重复起 WebView。 */
    private fun sharedWait(pending: Pending, timeoutMs: Long): Boolean {
        pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return pending.success
    }

    private fun runSolver(
        context: Context,
        url: String,
        userAgent: String,
        extraHeaders: Map<String, String>,
        timeoutMs: Long,
        pending: Pending
    ): Boolean {
        val host = hostOf(url) ?: return false
        var webview: WebView? = null
        val finished = AtomicBoolean(false)
        val challengeSeen = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) pending.latch.countDown()
        }
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val oldClearance = clearanceOf(cookieManager.getCookie(url))

            val main = Handler(Looper.getMainLooper())
            main.post {
                try {
                    @SuppressLint("SetJavaScriptEnabled")
                    val wv = WebView(context.applicationContext)
                    webview = wv
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
                    }
                    wv.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun interactiveDetected() = finish()
                    }, "easyReaderSolver")

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, viewUrl: String) {
                            val current = clearanceOf(cookieManager.getCookie(url))
                            if (current != null && current != oldClearance) {
                                // Cloudflare 解完会自动重载页面并写入新 cf_clearance
                                pending.success = true
                                finish()
                                return
                            }
                            view.evaluateJavascript(
                                """
                                addEventListener("message", ({data}) => {
                                    if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                        easyReaderSolver.interactiveDetected();
                                    }
                                })
                                """.trimIndent(),
                                null
                            )
                            if (viewUrl == url && current == null && oldClearance == null && !challengeSeen.get()) {
                                // Finishing the challenge document does not mean its async
                                // verification has finished. Check the page before stopping.
                                view.evaluateJavascript(
                                    """
                                    Boolean(document.querySelector('script[src*="challenge-platform"],#challenge-running,#challenge-stage') ||
                                        /Just a moment|Checking your browser/i.test(document.title))
                                    """.trimIndent()
                                ) { result ->
                                    if (result == "true") challengeSeen.set(true) else finish()
                                }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true) {
                                val mitigated = errorResponse?.responseHeaders
                                    ?.entries?.firstOrNull {
                                        it.key.equals("cf-mitigated", ignoreCase = true)
                                    }?.value.orEmpty()
                                if (mitigated.contains("challenge", ignoreCase = true)) {
                                    challengeSeen.set(true)
                                } else {
                                    // 主帧报错且不是挑战页：等不到结果
                                    finish()
                                }
                            }
                        }
                    }
                    wv.layout(0, 0, 1080, 1920)
                    // 只带安全的头（对齐 Mihon parseHeaders 的保守做法）
                    val safeHeaders = extraHeaders.filterKeys { k ->
                        val n = k.lowercase()
                        n !in setOf(
                            "content-length", "host", "cookie", "cookie2",
                            "transfer-encoding", "upgrade", "te", "trailer"
                        ) && !n.startsWith("proxy-")
                    }
                    wv.loadUrl(url, safeHeaders)
                    // Some sites set the cookie asynchronously without navigating again.
                    main.postDelayed(object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            val current = clearanceOf(cookieManager.getCookie(url))
                            if (current != null && current != oldClearance) {
                                pending.success = true
                                finish()
                            } else main.postDelayed(this, 250)
                        }
                    }, 250)
                } catch (e: Exception) {
                    Log.w(TAG, "webview solve setup failed: ${e.message}")
                    finish()
                }
            }

            pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            finish()

            main.post {
                try {
                    webview?.stopLoading()
                    webview?.destroy()
                } catch (_: Exception) {}
            }

            if (pending.success) {
                syncToJsCookieJar(context, url)
                Log.i(TAG, "cloudflare solved for $host")
            } else {
                synchronized(lastFailAt) { lastFailAt[host] = System.currentTimeMillis() }
                Log.w(TAG, "cloudflare solve failed for $host")
            }
            return pending.success
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            synchronized(inflight) { inflight.remove(host) }
        }
    }

    private fun clearanceOf(cookieHeader: String?): String? =
        cookieHeader?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }

    /** 把 android.webkit.CookieManager 的 Cookie 合并进 JS 源共享存储（ck_<host>，"k=v; ..."）。 */
    fun syncToJsCookieJar(context: Context, url: String) {
        try {
            val host = hostOf(url) ?: return
            val raw = CookieManager.getInstance().getCookie(url) ?: return
            val pairs = raw.split(';').map { it.trim() }.filter { it.contains('=') }
            if (pairs.isEmpty()) return
            JsCookieJar.saveCookiePairs(context, host, pairs)
        } catch (e: Exception) {
            Log.w(TAG, "cookie sync failed: ${e.message}")
        }
    }

    private fun hostOf(url: String): String? = try {
        val u = URL(url)
        if (u.protocol == "http" || u.protocol == "https") u.host?.lowercase() else null
    } catch (e: Exception) {
        null
    }

    /** 仅允许公网主机（拒绝环回/私有/保留地址解析结果）。 */
    private fun isPublicHost(host: String): Boolean {
        if (host.isBlank() || host == "localhost") return false
        return try {
            InetAddress.getAllByName(host).all { addr ->
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                    addr.isAnyLocalAddress || addr.isMulticastAddress ||
                    addr.isSiteLocalAddress
                ) {
                    false
                } else {
                    // IPv6 unique-local (fc00::/7) 无内建标记，手动判首字节
                    val b = addr.address
                    !(b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
