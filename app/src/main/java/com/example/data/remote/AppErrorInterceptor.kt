package com.example.data.remote

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网络层统一异常：把「底层异常 / HTTP 状态码 / 服务器自己写的拒绝理由」收敛成一句可展示文案。
 *
 * 这里刻意用中文字面量而不是 string 资源：这些文案转述的是**服务器返回内容与服务端状态码**，
 * 需要带 code 上下文，翻译成另一套固定句子反而会与事实对不上。
 * （UI 侧的固定提示语仍然走 strings.xml，见 ui/components/AppErrorSnackbar.kt。）
 */
class NetworkErrorMessage(
    override val message: String,
    val url: String? = null,
    val responseCode: Int = 0
) : IOException(message) {

    constructor(cause: Throwable, url: String? = null) : this(
        message = when (cause) {
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is UnknownHostException -> "无法解析服务器地址，请检查网络或书源"
            is ConnectException -> "无法连接到服务器"
            is SSLException -> "安全连接失败，服务器证书可能不受信任"
            else -> cause.message?.takeIf { it.isNotBlank() } ?: "网络请求失败"
        },
        url = url
    )

    /**
     * 交给 UI 直接展示的文案。
     * context 参数保留是为了以后按语言切换措辞，现在不读它。
     */
    fun toMessage(context: Context? = null): String = message

    companion object {
        fun networkTimeout(): NetworkErrorMessage =
            NetworkErrorMessage("连接超时，请检查网络后重试")

        fun networkGeneric(): NetworkErrorMessage =
            NetworkErrorMessage("网络异常，请稍后重试")

        /** 服务器用非 2xx 拒绝请求；[message] 是服务器自己写的理由（可能为空）。 */
        fun serverHttp(code: Int, message: String?, url: String? = null): NetworkErrorMessage {
            val serverMessage = message?.trim()?.takeIf { it.isNotEmpty() }
            val base = when (code) {
                401, 403 -> "服务器拒绝访问（$code），可能需要重新登录或书源已失效"
                404 -> "服务器上没有这个内容（404）"
                429 -> "请求过于频繁，服务器暂时拒绝了请求（429）"
                in 500..599 -> "服务器出错了（$code），通常是源站繁忙，稍后再试"
                else -> serverMessage ?: "服务器返回错误（$code）"
            }
            // 服务器写了理由就补在后面，同时保留状态码，方便用户截图求助时对得上
            val text = if (serverMessage != null && !base.contains(serverMessage)) {
                "$base（$serverMessage）"
            } else base
            return NetworkErrorMessage(text, url, code)
        }

        fun serverUnknown(code: Int, url: String? = null): NetworkErrorMessage =
            NetworkErrorMessage("服务器返回未知响应（$code）", url, code)
    }
}

/**
 * 从错误响应体里提取服务器返回的具体错误信息。
 *
 * 书源/站点的错误体格式不一（HTML 错误页、网关裸文本、各种键名的 JSON 都有），
 * 这里统一尝试常见字段，提取不到就回退到「服务器返回错误（code）」，
 * 保证用户永远看到的是一句话，而不是 3KB HTML 或裸 JSON。
 */
object ServerMessageExtractor {

    /** 依次尝试的常见错误字段；不同站点写法差异很大，覆盖不到的会落到兜底文案。 */
    private val MESSAGE_KEYS = arrayOf("message", "msg", "error", "detail", "errorMsg", "messageStr")

    fun extract(body: String?, code: Int): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return "服务器返回错误（$code）"
        val serverMessage = parseServerMessage(text)
        // 限流不是「做错了什么」，要说清是暂时的，否则用户只会反复点重试。
        if (code == 429) {
            val prefix = "请求过于频繁，服务器暂时拒绝了请求（429）"
            return if (serverMessage != null) "$prefix（$serverMessage）" else prefix
        }
        val fallback = when (code) {
            in 500..599 -> "服务器出错了（$code），通常是源站繁忙，稍后再试"
            401, 403 -> "服务器拒绝访问（$code），可能需要重新登录或书源已失效"
            404 -> "服务器上没有这个内容（404）"
            else -> "服务器返回错误（$code）"
        }
        return if (serverMessage != null) "$fallback（$serverMessage）" else fallback
    }

    private fun parseServerMessage(text: String): String? = try {
        val json = org.json.JSONObject(text)
        MESSAGE_KEYS.firstOrNull { json.has(it) }
            ?.let { json.optString(it).trim() }
            ?.takeIf { it.isNotEmpty() && it.length <= 160 }
    } catch (_: Exception) {
        // 非 JSON（HTML 错误页 / 网关裸文本）：截短后当补充说明，够不到就用兜底文案
        text.takeIf { !it.startsWith("<") }?.take(80)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * OkHttp 错误拦截器：把非 2xx 响应统一转成 [NetworkErrorMessage]，让上层只需要 catch 一种异常。
 *
 * ⚠️ 未挂载到任何 OkHttpClient（保留给统一网络层接入用）。
 * ZLibrary 的客户端在 ZLibraryHttpClient 里自己做了一层同款改写，逻辑与本类一致。
 */
class AppErrorInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            throw NetworkErrorMessage(e, request.url.toString())
        }

        if (!response.isSuccessful) {
            // ⚠️ 409 是 ZLibrary 书架的业务语言（「这本书已经在书架里了」），不是故障。
            // 上层 ZLibraryBookshelfScreen 的 add/remove 会读 body 里的 message 显示给用户：
            //     if (code == 409) message = bodyJson.optString("message")
            // 这里一旦把响应 close 掉，那次读取就拿到空串，提示退化成生硬的「添加失败: HTTP 409」。
            // 所以 409 必须**原样放行**：不读体、不关闭、不改写。
            if (response.code == 409) return response
            val bodyString = response.body?.string() ?: ""
            val message = ServerMessageExtractor.extract(bodyString, response.code)
            response.close()
            throw NetworkErrorMessage(message, request.url.toString(), response.code)
        }

        return response
    }
}

/** 401/403 这类「重试也没用」的错误：调用方据此决定不再自动重试。 */
fun isNonRetryableError(throwable: Throwable): Boolean {
    val message = (throwable as? NetworkErrorMessage)?.message ?: return false
    return message.contains("401") || message.contains("403")
}

