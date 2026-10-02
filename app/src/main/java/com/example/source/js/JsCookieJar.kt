package com.example.source.js

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * JS 源共享 Cookie 存储读取器。
 * 与 JsMessageHandler 的 js_source_cookies 共用同一份数据，
 * 供阅读器 Coil 加载与章节下载使用——对齐 Venera 图片请求走共享 CookieJar 的行为。
 */
object JsCookieJar {
    private val lock = Any()
    private fun preferences(context: Context) =
        context.getSharedPreferences("js_source_cookies", Context.MODE_PRIVATE)

    private fun pairs(raw: String): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            val name = it.substringBefore('=').trim()
            if (name.isNotBlank()) put(name, it)
        }
    }

    private fun metadata(raw: String?) = try { JSONObject(raw ?: "{}") } catch (_: Exception) { JSONObject() }

    fun cookieHeader(context: Context, url: String): String = synchronized(lock) {
        val parsed = url.toHttpUrlOrNull() ?: return@synchronized ""
        val prefs = preferences(context)
        val now = System.currentTimeMillis()
        val baseDomain = parsed.topPrivateDomain() ?: parsed.host
        val cookies = LinkedHashMap<String, String>()
        var host = parsed.host
        while (host.isNotBlank()) {
            val stored = pairs(prefs.getString("ck_$host", "").orEmpty())
            val attributes = metadata(prefs.getString("ck_meta_$host", null))
            var changed = false
            stored.entries.removeAll { (name, pair) ->
                val meta = attributes.optJSONObject(name)
                // Migrate the old one-second search throttle without discarding login sessions.
                val oldSearchThrottle = meta == null && name == "ss_search_delay" &&
                    (host == "ikmmh.com" || host.endsWith(".ikmmh.com"))
                val expired = oldSearchThrottle || (meta != null && meta.optLong("expiresAt", Long.MAX_VALUE) <= now)
                if (expired) {
                    attributes.remove(name)
                    changed = true
                } else {
                    val path = meta?.optString("path", "/") ?: "/"
                    val pathMatches = parsed.encodedPath == path ||
                        (parsed.encodedPath.startsWith(path) && (path.endsWith('/') || parsed.encodedPath.getOrNull(path.length) == '/'))
                    val scopeMatches = meta == null ||
                        ((!meta.optBoolean("hostOnly") || host == parsed.host) &&
                            (!meta.optBoolean("secure") || parsed.isHttps) && pathMatches)
                    if (scopeMatches) cookies.putIfAbsent(name, pair)
                }
                expired
            }
            if (changed) prefs.edit().putString("ck_$host", stored.values.joinToString("; "))
                .putString("ck_meta_$host", attributes.toString()).apply()
            if (host == baseDomain) break
            host = host.substringAfter('.', "")
        }
        cookies.values.joinToString("; ")
    }

    fun saveSetCookies(context: Context, url: String, headers: List<String>) = synchronized(lock) {
        val parsed = url.toHttpUrlOrNull() ?: return@synchronized
        val prefs = preferences(context)
        val now = System.currentTimeMillis()
        headers.mapNotNull { Cookie.parse(parsed, it) }.groupBy { it.domain }.forEach { (host, updates) ->
            val stored = pairs(prefs.getString("ck_$host", "").orEmpty())
            val attributes = metadata(prefs.getString("ck_meta_$host", null))
            updates.forEach { cookie ->
                if (cookie.expiresAt <= now) {
                    stored.remove(cookie.name)
                    attributes.remove(cookie.name)
                } else {
                    stored[cookie.name] = "${cookie.name}=${cookie.value}"
                    attributes.put(cookie.name, JSONObject().put("expiresAt", cookie.expiresAt)
                        .put("path", cookie.path).put("secure", cookie.secure).put("hostOnly", cookie.hostOnly))
                }
            }
            prefs.edit().putString("ck_$host", stored.values.joinToString("; "))
                .putString("ck_meta_$host", attributes.toString()).apply()
        }
    }

    /** JS and WebView supply live name/value pairs without Set-Cookie attributes. */
    fun saveCookiePairs(context: Context, host: String, updates: List<String>) = synchronized(lock) {
        if (host.isBlank()) return@synchronized
        val prefs = preferences(context)
        val domain = host.trimStart('.').lowercase()
        val stored = pairs(prefs.getString("ck_$domain", "").orEmpty())
        val attributes = metadata(prefs.getString("ck_meta_$domain", null))
        pairs(updates.joinToString("; ")).forEach { (name, pair) ->
            stored[name] = pair
            attributes.remove(name)
        }
        prefs.edit().putString("ck_$domain", stored.values.joinToString("; "))
            .putString("ck_meta_$domain", attributes.toString()).apply()
    }

    fun clear(context: Context, host: String) = synchronized(lock) {
        preferences(context).edit().remove("ck_$host").remove("ck_meta_$host").apply()
    }
}
