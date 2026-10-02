package com.example.library

import android.net.Uri
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.source.SearchBook
import com.example.source.zlibrary.parser.ZLibraryParserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

/**
 * 原生书库背后的隐藏 WebView 会话：
 * - 搜索：在隐藏 WebView 中加载 1lib.sk 搜索页，轮询 DOM 并把结果解析成
 *   List<SearchBook> 交给原生 UI 展示（用户全程不离开 App）。
 * - 登录：注入账号密码完成站点登录，Cookie 留在会话里自动生效。
 * - 下载：捕获 DownloadListener 的真实文件 URL 交给下载管理器。
 */
class ZLibraryNativeSession(
    private val onSearchResults: (List<SearchBook>, String) -> Unit,
    private val onRealDownloadUrl: (String) -> Unit,
    private val onLoginResult: (Boolean, String) -> Unit
) {
    companion object {
        private const val MAX_POLL_TRIES = 5
        // 挑战态放宽轮询上限：1lib.sk 的 DiamWall 透明 PoW 由页面 JS 自解约 3-8s，
        // 交互式复选框挑战被代点后还要等校验回程，5 次（12.5s）不够，给 10 次（25s）。
        private const val MAX_CHALLENGE_POLL_TRIES = 10
        private const val POLL_DELAY_MS = 2500L
        // 挑战轮询上限放宽到 25s，硬超时同步放到 30s（覆盖代点 + 校验回程时间）
        private const val SEARCH_TIMEOUT_MS = 30000L

        /** DiamWall 复选框代点（与 ZLibraryWebViewHelper 同款）：
         *  挑战 iframe 与主文档同源，可跨 contentDocument 操作；点击后挑战页
         *  JS 自行完成 PoW 校验并放行。 */
        private const val DIAMWALL_GATE_CLICK_JS = """
            (function(){
                try {
                    var ifr = document.querySelector('iframe');
                    if (!ifr) return 'no-iframe';
                    var doc = ifr.contentDocument;
                    if (!doc || !doc.body) return 'no-doc';
                    var gate = doc.querySelector('#pane-gate, .dw-gate, [class*="dw-gate"]');
                    if (!gate) return 'no-gate';
                    gate.click();
                    return 'clicked';
                } catch (e) { return 'err'; }
            })()
        """
    }

    private val domain: String
        // 空串 = 尚未恢复用户选择（仅启动空窗/单测），退回默认节点
        get() = ZLibraryNodeConfig.domain.ifBlank { ZLibraryNodeManager.DEFAULT_NODE }

    // 游离 WebView 未挂载时 View.postDelayed 可能不执行，必须用主线程 Handler 调度
    private val mainHandler = Handler(Looper.getMainLooper())
    // 搜索结果 HTML 解析放到后台线程，避免 Jsoup 大页面解析卡住主线程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var webView: WebView? = null
        private set

    private var pollTries = 0
    private var pendingQuery: String? = null
    private var searchClient: WebViewClient = WebViewClient()
    private var loginInProgress = false
    private var searchGeneration = 0
    private var searchFinished = false
    private var activeSearchUrl: String? = null
    private var downloadHandoffPending = false
    private var pendingDestroy = false
    private var lastGateClickAt = 0L

    /** 离开书库时释放隐藏 WebView，回收渲染进程内存，避免后台持续占用。 */
    fun destroy() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { mainHandler.post { destroy() }; return }
        searchFinished = true
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        val wv = webView
        if (wv == null) return
        // 若正在等待下载真实链接（用户刚点下载就切走），等回调拿到 URL 后再释放
        if (downloadHandoffPending) {
            pendingDestroy = true
            mainHandler.postDelayed({ performDestroy(wv) }, 12000)
        } else {
            performDestroy(wv)
        }
    }

    private fun performDestroy(wv: WebView) {
        if (webView != wv) return
        try { wv.stopLoading() } catch (_: Exception) {}
        try { wv.destroy() } catch (_: Exception) {}
        webView = null
        pendingDestroy = false
        downloadHandoffPending = false
    }

    /**
     * 按需创建会话 WebView。WebView 不挂载到任何视图层级（游离模式）：
     * 依然可以加载页面、执行 JS、获取 DOM 和触发下载监听，但不会参与
     * 界面渲染，彻底避免挂载 WebView 导致的窗口冻结/CPU 空转问题。
     */
    fun ensureCreated(context: Context) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { mainHandler.post { ensureCreated(context.applicationContext) }; return }
        if (webView == null) {
            try { attach(WebView(context.applicationContext)) }
            catch(e:Exception) {
                searchFinished=true; mainHandler.removeCallbacksAndMessages(null)
                onLoginResult(false,"系统 WebView 不可用，请更新 Android System WebView")
                onSearchResults(emptyList(),"系统 WebView 不可用")
            }
        }
    }

    fun attach(wv: WebView) {
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        // 解析只需要 DOM/封面地址，禁止隐藏 WebView 下载页面图片，
        // 避免一次搜索触发几十个封面请求导致站点把 IP 临时封禁。
        wv.settings.blockNetworkImage = true
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.setDownloadListener { url, _, _, _, _ ->
            downloadHandoffPending = false
            url?.let { onRealDownloadUrl(it) }
            if (pendingDestroy) performDestroy(wv)
        }
        searchClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val activeUrl = activeSearchUrl
                if (activeUrl != null && !searchFinished && url != null && url.startsWith("https://$domain")) {
                    parseCurrentPage(view ?: return, activeUrl)
                }
            }
        }
        wv.webViewClient = searchClient
        // 创建后默认暂停，避免隐藏 WebView 空闲时持续消耗 CPU/帧率
        try { wv.onPause() } catch (_: Exception) {}
        pendingQuery?.let { q ->
            pendingQuery = null
            search(q)
        }
    }

    fun search(query: String) {
        pollTries = 0
        searchFinished = false
        val generation = ++searchGeneration
        pendingQuery = query
        val wv = webView ?: return
        val url = "https://$domain/s/" + Uri.encode(query)
        activeSearchUrl = url
        resumeForAction(wv)
        wv.loadUrl(url)
        schedulePoll(wv, url)
        // 硬超时兜底：无论页面/回调卡在哪个环节，15 秒后必须结束搜索状态
        mainHandler.postDelayed({
            if (generation == searchGeneration && !searchFinished) {
                finishSearch(wv, emptyList(), "搜索超时，请重试或切换节点")
            }
        }, SEARCH_TIMEOUT_MS)
    }

    fun loadUrl(url: String) {
        webView?.let { wv ->
            downloadHandoffPending = true
            resumeForAction(wv)
            wv.loadUrl(url)
            mainHandler.postDelayed({
                downloadHandoffPending = false
                if (pendingDestroy) performDestroy(wv)
            }, 12000)
        }
    }

    /** 空闲时暂停隐藏 WebView，避免其 JS/渲染持续占用 CPU 导致界面掉帧。 */
    private fun resumeForAction(wv: WebView) {
        try { wv.onResume() } catch (_: Exception) {}
    }

    /** 代点 DiamWall 复选框（6s 节流，重复点击无益）。 */
    private fun clickDiamWallGate(wv: WebView) {
        val now = System.currentTimeMillis()
        if (now - lastGateClickAt < 6000) return
        runCatching {
            wv.evaluateJavascript(DIAMWALL_GATE_CLICK_JS) { result ->
                if (result != null && result.contains("clicked")) {
                    lastGateClickAt = now
                    Log.d("ZLibSession", "DiamWall gate auto-clicked on $domain")
                }
            }
        }
    }

    private fun finishSearch(wv: WebView?, books: List<SearchBook>, status: String) {
        if (searchFinished) return
        searchFinished = true
        onSearchResults(books, status)
        try { wv?.onPause() } catch (_: Exception) {}
    }

    fun isLoggedIn(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://$domain/") ?: return false
        return cookies.contains("remix_userid") || cookies.contains("remix_userkey")
    }

    fun login(email: String, password: String) {
        val wv = webView ?: run {
            onLoginResult(false, "会话未就绪，请重试")
            return
        }
        if (loginInProgress) return
        loginInProgress = true
        val jEmail = org.json.JSONObject.quote(email)
        val jPass = org.json.JSONObject.quote(password)
        val js = "var e=document.querySelector('input[name=email],input[name=login],input[type=email]');" +
            "var p=document.querySelector('input[name=password],input[type=password]');" +
            "if(e&&p){e.value=$jEmail;p.value=$jPass;" +
            "var f=e.form||p.form;" +
            "if(f){var sb=f.querySelector('button[type=submit],input[type=submit]');" +
            "if(sb){sb.click();'clicked'}else{f.submit();'submitted'}}" +
            "else{'noform'}}" +
            "else{'nofields'}"

        val loginClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loginInProgress) return
                if (url == null) return
                if (url.contains("/login")) {
                    view?.evaluateJavascript(js) { result ->
                        Log.d("ZLibLogin", "inject result: $result")
                    }
                } else if (url.startsWith("https://$domain") && !url.contains("/login")) {
                    loginInProgress = false
                    wv.webViewClient = searchClient
                    try { wv.onPause() } catch (_: Exception) {}
                    if (isLoggedIn()) {
                        Log.d("ZLibLogin", "login success, cookies present")
                        onLoginResult(true, "登录成功")
                    } else {
                        Log.d("ZLibLogin", "login failed, no session cookies")
                        onLoginResult(false, "登录失败，请检查账号密码")
                    }
                }
            }
        }
        wv.webViewClient = loginClient
        resumeForAction(wv)
        wv.loadUrl("https://$domain/login")
        // 超时兜底：20 秒未完成则视为失败
        mainHandler.postDelayed({
            if (loginInProgress) {
                loginInProgress = false
                if (webView == wv) {
                    wv.webViewClient = searchClient
                }
                try { wv.onPause() } catch (_: Exception) {}
                onLoginResult(false, "登录超时，请检查网络后重试")
            }
        }, 20000)
    }

    private fun parseCurrentPage(wv: WebView, url: String) {
        // 游离 WebView 永不挂载，不能用 isAttachedToWindow；只校验是否仍是当前会话
        if (webView != wv) return
        if (searchFinished) return
        if (!url.startsWith("https://$domain")) return
        wv.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();") { htmlJson ->
            if (searchFinished) return@evaluateJavascript
            if (htmlJson == null) {
                // 页面还没就绪/回调无结果：继续重试而不是永久转圈
                pollTries++
                if (pollTries >= MAX_POLL_TRIES) {
                    finishSearch(wv, emptyList(), "页面无响应，请重试或切换节点")
                } else {
                    schedulePoll(wv, url)
                }
                return@evaluateJavascript
            }
            val html = try {
                JSONTokener(htmlJson).nextValue() as String
            } catch (e: Exception) {
                htmlJson
            }
            val isChallenge = html.contains("Verifying your browser", ignoreCase = true) ||
                html.contains("Checking your browser", ignoreCase = true) ||
                html.contains("cpt.lib") ||
                html.contains("solve this captcha", ignoreCase = true)
            if (isChallenge) {
                // 被动等不会过：主动代点复选框（挑战 JS 自行完成后续校验）
                clickDiamWallGate(wv)
                pollTries++
                if (pollTries >= MAX_CHALLENGE_POLL_TRIES) {
                    finishSearch(wv, emptyList(), "验证未通过，请重试或切换节点")
                    return@evaluateJavascript
                }
                onSearchResults(emptyList(), "正在通过验证…")
                schedulePoll(wv, url)
                return@evaluateJavascript
            }

            // 站点明确提示无结果时直接结束，不再空转 12 次
            val noResultMarkers = listOf(
                "没有找到", "未找到", "没有搜索结果",
                "no results", "nothing found", "0 results", "not found"
            )
            val noResult = !html.contains("z-bookcard") &&
                noResultMarkers.any { html.contains(it, ignoreCase = true) }
            if (noResult) {
                finishSearch(wv, emptyList(), "未找到相关书籍")
                return@evaluateJavascript
            }

            scope.launch(Dispatchers.Default) {
                val books = try {
                    ZLibraryParserManager.parseSearchPage(html, "https://$domain", "zlibrary")
                } catch (e: Exception) {
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    if (searchFinished || webView != wv) return@withContext
                    if (books.isNotEmpty()) {
                        pollTries = 0
                        finishSearch(wv, books, "找到 ${books.size} 本")
                    } else {
                        pollTries++
                        if (pollTries >= MAX_POLL_TRIES) {
                            finishSearch(wv, emptyList(), "未解析到书籍（可能结构变化或需要登录）")
                        } else {
                            schedulePoll(wv, url)
                        }
                    }
                }
            }
        }
    }

    private fun schedulePoll(wv: WebView, url: String) {
        val generation = searchGeneration
        mainHandler.postDelayed({
            if (generation == searchGeneration &&
                pollTries < MAX_POLL_TRIES &&
                webView == wv &&
                !searchFinished
            ) {
                parseCurrentPage(wv, url)
            }
        }, POLL_DELAY_MS)
    }
}
