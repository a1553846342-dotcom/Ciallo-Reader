package com.example.source.js

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 追踪当前前台 Activity，供 JS 源的 UI 对话框使用。
 * 通过 applicationContext 注册生命周期回调，无需修改 Manifest。
 */
object JsActivityTracker {
    @Volatile
    private var current = java.lang.ref.WeakReference<Activity>(null)
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register(context: Context) {
        val app = context.applicationContext as? android.app.Application ?: return
        if (!registered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { current = java.lang.ref.WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (current.get() === activity) current.clear() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (current.get() === activity) current.clear() }
        })
    }

    fun currentActivity(): Activity? = current.get()
}

/**
 * 挂起式输入对话框：实现 Venera 契约的 UI.showInputDialog。
 * 支持展示图片（验证码），返回用户输入文本；取消返回空串。
 */
suspend fun awaitJsInputDialog(
    context: Context,
    title: String,
    imageBase64: String? = null
): String = withContext(Dispatchers.Main) {
    val activity = JsActivityTracker.currentActivity()
        ?: throw IllegalStateException("无可用界面（请回到 App 后重试）")
    suspendCancellableCoroutine { cont ->
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (!imageBase64.isNullOrBlank()) {
            runCatching {
                val bytes = android.util.Base64.decode(
                    imageBase64.substringAfter("base64,"), android.util.Base64.DEFAULT
                )
                val iv = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (180 * density).toInt()
                    )
                    adjustViewBounds = true
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { setImageBitmap(it) }
                }
                container.addView(iv)
            }
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> cont.resume(input.text.toString()) }
            .setNegativeButton("取消") { _, _ -> cont.resume("") }
            .setOnCancelListener { cont.resume("") }
            .create()
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
        input.requestFocus()
    }
}

/** A user-operated site verification, with cookies kept in the app's browser jar. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
suspend fun awaitWebsiteVerification(url: String, userAgent: String): Boolean = withContext(Dispatchers.Main) {
    require(android.net.Uri.parse(url).host in setOf("mycomic.com", "nhentai.net"))
    val activity = JsActivityTracker.currentActivity()
        ?: throw IllegalStateException("请回到应用后打开站点验证")
    suspendCancellableCoroutine { continuation ->
        val main = Handler(Looper.getMainLooper())
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val browser = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
        }
        var mainPageOk = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("站点验证")
            .setView(browser)
            .setNegativeButton("取消") { _, _ -> }
            .create()
        fun complete(success: Boolean) {
            if (!continuation.isActive) return
            if (success) {
                cookies.flush()
                CfWebViewSolver.syncToJsCookieJar(activity, url)
            }
            continuation.resume(success)
            dialog.dismiss()
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val destination = request.url
                if (!request.isForMainFrame) {
                    return destination.scheme != "https" && destination.toString() != "about:blank"
                }
                return destination.scheme != "https" || destination.host != android.net.Uri.parse(url).host
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                mainPageOk = true
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) mainPageOk = false
            }
            override fun onPageFinished(view: WebView, currentUrl: String?) {
                if (!mainPageOk || android.net.Uri.parse(currentUrl.orEmpty()).host != android.net.Uri.parse(url).host) return
                view.evaluateJavascript("Boolean(document.querySelector('a[href*=\"/cn/comics/\"],a[href^=\"/g/\"],div.gallery'))") { value ->
                    if (value == "true") complete(true)
                }
            }
        }
        dialog.setOnDismissListener {
            browser.stopLoading()
            browser.destroy()
            if (continuation.isActive) continuation.resume(false)
        }
        continuation.invokeOnCancellation { main.post { dialog.dismiss() } }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.85).toInt())
        browser.layoutParams?.let { params ->
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            browser.layoutParams = params
        }
        browser.loadUrl(url)
    }
}
