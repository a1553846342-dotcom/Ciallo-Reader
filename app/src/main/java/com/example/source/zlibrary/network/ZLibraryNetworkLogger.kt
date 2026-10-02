package com.example.source.zlibrary.network

import android.util.Log
import com.example.BuildConfig

/** Debug diagnostics exclude credentials, query strings, and response bodies. */
object ZLibraryNetworkLogger {
    private const val TAG = "ZLibraryNetwork"
    private fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
    fun logRequest(url: String, method: String, headers: Map<String, String>, cookies: String?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$method ${host(url)} headers=${headers.size} session=${!cookies.isNullOrBlank()}")
    }
    fun logResponse(code: Int, url: String, headers: Map<String, String>, setCookie: List<String>, contentType: String?, bodySnippet: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "HTTP $code ${host(url)} type=$contentType headers=${headers.size}")
    }
    fun logParserResult(status: String, bookCount: Int, message: String? = null) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Parser $status books=$bookCount")
    }
}
