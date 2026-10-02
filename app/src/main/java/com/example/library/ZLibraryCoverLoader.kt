package com.example.library

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.runtime.Composable
import coil.ImageLoader
import com.example.source.zlibrary.network.SystemProxyResolver
import com.example.source.zlibrary.network.ZLibraryDns
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 封面专用图片加载器：走与书库相同的网络路径（DoH 防污染 DNS + 系统代理），
 * 否则 covers.1lib.sk 在部分网络下无法加载。
 * 全局单例复用，避免每次进入书库都重建 OkHttpClient / Coil。
 */
object ZLibraryCoverLoader {
    @Volatile
    private var cachedLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return cachedLoader ?: synchronized(this) {
            cachedLoader ?: buildLoader(context.applicationContext).also { cachedLoader = it }
        }
    }

    private fun buildLoader(context: Context): ImageLoader {
        val clientBuilder = com.example.source.SharedHttpTransport.builder()
            .dns(ZLibraryDns.INSTANCE)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // 封面 CDN 可能需要浏览器身份/防盗链 Referer/会话 Cookie
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                val nodeCookie = CookieManager.getInstance()
                    .getCookie("https://${ZLibraryNodeConfig.domain}/") ?: ""
                val combinedCookie = listOf(cookie, nodeCookie)
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                val builder = req.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Referer", "https://${ZLibraryNodeConfig.domain}/")
                if (combinedCookie.isNotBlank()) {
                    builder.header("Cookie", combinedCookie)
                }
                chain.proceed(builder.build())
            }
        SystemProxyResolver.resolve(context)?.let { clientBuilder.proxy(it) }
        return ImageLoader.Builder(context)
            .okHttpClient(clientBuilder.build())
            .crossfade(true)
            .build()
    }
}

/** 保持原有调用签名：返回全局复用的封面加载器。 */
@Composable
fun rememberZLibraryImageLoader(context: Context): ImageLoader {
    return ZLibraryCoverLoader.get(context)
}
