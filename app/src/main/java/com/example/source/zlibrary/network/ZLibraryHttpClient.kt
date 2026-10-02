package com.example.source.zlibrary.network

import android.content.Context
import com.example.source.zlibrary.DiamWallInterceptor
import com.example.source.zlibrary.EncryptedCookieJar
import com.example.source.zlibrary.ZLibraryCredentialStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.util.concurrent.TimeUnit

class ZLibraryHttpClient(
    val credentialStorage: ZLibraryCredentialStorage,
    val cookieJar: EncryptedCookieJar = EncryptedCookieJar(credentialStorage),
    private val context: Context? = null
) {

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        const val DEFAULT_ACCEPT_LANG = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    val okHttpClient: OkHttpClient by lazy {
        val builder = com.example.source.SharedHttpTransport.builder()
            .dns(ZLibraryDns.INSTANCE)
            .cookieJar(cookieJar)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            // Redirects are handled manually by DiamWallInterceptor (with loop protection);
            // OkHttp's built-in auto-follow loops forever on DiamWall's 307 challenge dance.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(DiamWallInterceptor(cookieJar))
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                if (originalRequest.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
                }
                if (originalRequest.header("Accept") == null) {
                    requestBuilder.header("Accept", DEFAULT_ACCEPT)
                }
                if (originalRequest.header("Accept-Language") == null) {
                    requestBuilder.header("Accept-Language", DEFAULT_ACCEPT_LANG)
                }
                if (originalRequest.header("Sec-Ch-Ua") == null) {
                    requestBuilder.header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                }
                if (originalRequest.header("Sec-Ch-Ua-Mobile") == null) {
                    requestBuilder.header("Sec-Ch-Ua-Mobile", "?1")
                }
                if (originalRequest.header("Sec-Ch-Ua-Platform") == null) {
                    requestBuilder.header("Sec-Ch-Ua-Platform", "\"Android\"")
                }
                if (originalRequest.header("Sec-Fetch-Dest") == null) {
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                }
                if (originalRequest.header("Sec-Fetch-Mode") == null) {
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                }
                if (originalRequest.header("Sec-Fetch-Site") == null) {
                    requestBuilder.header("Sec-Fetch-Site", "same-origin")
                }
                if (originalRequest.header("Upgrade-Insecure-Requests") == null) {
                    requestBuilder.header("Upgrade-Insecure-Requests", "1")
                }

                chain.proceed(requestBuilder.build())
            }
        // Use the same system proxy as WebView/browsers when one is configured
        SystemProxyResolver.resolve(context)?.let { builder.proxy(it) }
        builder.build()
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null
    ): Response = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url).get()

        referer?.let { requestBuilder.header("Referer", it) }
        extraHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val request = requestBuilder.build()

        // Log request
        val headerMap = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            headerMap[request.headers.name(i)] = request.headers.value(i)
        }
        val currentCookies = cookieJar.loadForRequest(request.url).joinToString("; ") { "${it.name}=${it.value}" }
        ZLibraryNetworkLogger.logRequest(url, "GET", headerMap, currentCookies)

        // callTimeoutMs（会话预热等调用方限时）：必须用 OkHttp callTimeout——
        // 外层 withTimeoutOrNull 取消不了阻塞中的 socket read，超时形同虚设
        val call = if (callTimeoutMs != null) {
            okHttpClient.newBuilder()
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
        } else {
            okHttpClient.newCall(request)
        }
        val response = call.execute()

        // Log response
        val respHeaderMap = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            respHeaderMap[response.headers.name(i)] = response.headers.value(i)
        }
        val setCookieList = response.headers("Set-Cookie")
        val bodySnippet = ""

        ZLibraryNetworkLogger.logResponse(
            code = response.code,
            url = response.request.url.toString(),
            headers = respHeaderMap,
            setCookie = setCookieList,
            contentType = response.header("Content-Type"),
            bodySnippet = bodySnippet
        )

        response
    }

    suspend fun postForm(
        url: String,
        formBody: FormBody,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url).post(formBody)

        referer?.let { requestBuilder.header("Referer", it) }
        extraHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val request = requestBuilder.build()

        val headerMap = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            headerMap[request.headers.name(i)] = request.headers.value(i)
        }
        val currentCookies = cookieJar.loadForRequest(request.url).joinToString("; ") { "${it.name}=${it.value}" }
        ZLibraryNetworkLogger.logRequest(url, "POST", headerMap, currentCookies)

        val response = okHttpClient.newCall(request).execute()

        val respHeaderMap = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            respHeaderMap[response.headers.name(i)] = response.headers.value(i)
        }
        val setCookieList = response.headers("Set-Cookie")
        val bodySnippet = ""

        ZLibraryNetworkLogger.logResponse(
            code = response.code,
            url = response.request.url.toString(),
            headers = respHeaderMap,
            setCookie = setCookieList,
            contentType = response.header("Content-Type"),
            bodySnippet = bodySnippet
        )

        response
    }
}
