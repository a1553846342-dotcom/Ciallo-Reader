package com.example.source

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Refresh one cached CDN server error without changing successful image/cache URLs. */
internal object PufeiImageCacheRetry : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val host = request.url.host
        if (request.method != "GET" || !host.endsWith(".6wm.top") ||
            request.header("Referer") != "https://manhuafree.com/" ||
            response.code !in 500..599) return response
        response.close()
        if (chain.call().isCanceled()) throw IOException("Canceled")
        // A fixed URL can keep serving an edge's cached 502, even after ordinary
        // retries. Preserve path and headers; a minute bucket avoids random URLs
        // and lets concurrent requests share the refreshed edge cache.
        val refreshed = request.url.newBuilder()
            .setQueryParameter("pufei_retry", (System.currentTimeMillis() / 60_000).toString())
            .build()
        return chain.proceed(request.newBuilder().url(refreshed)
            .header("Cache-Control", "no-cache").build())
    }
}
