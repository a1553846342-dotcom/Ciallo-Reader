package com.example.source.zlibrary

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class EncryptedCookieJar(private val credentialStorage: ZLibraryCredentialStorage) : CookieJar {

    private val cookieMap = mutableMapOf<String, Cookie>()

    init {
        loadFromStorage()
    }

    @Synchronized
    private fun loadFromStorage() {
        val rawCookies = credentialStorage.getCookies() ?: return
        val domain = credentialStorage.getDomain()

        rawCookies.split(";").forEach { cookieStr ->
            val parts = cookieStr.trim().split("=", limit = 2)
            if (parts.size == 2) {
                val name = parts[0].trim()
                val value = parts[1].trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(domain)
                        .build()
                    cookieMap["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie
                }
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        var userId: String? = null
        var userKey: String? = null

        cookies.forEach { cookie ->
            cookieMap["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie
            if (cookie.name == "remix_userid") userId = cookie.value
            if (cookie.name == "remix_userkey") userKey = cookie.value
        }

        val cookieString = cookieMap.values.filter { it.matches(url) && it.expiresAt >= System.currentTimeMillis() }.joinToString("; ") { "${it.name}=${it.value}" }
        credentialStorage.saveCredentials(
            userId = userId ?: credentialStorage.getUserId(),
            userKey = userKey ?: credentialStorage.getUserKey(),
            domain = credentialStorage.getDomain(),
            cookies = cookieString
        )
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val storageDomain = credentialStorage.getDomain()
        // 空串 = 尚未恢复用户选择（仅启动空窗/单测），退回账号保存时的域名
        val currentDomain = runCatching { com.example.library.ZLibraryNodeConfig.domain }
            .getOrDefault(storageDomain)
            .ifBlank { storageDomain }
        val requestHost = url.host

        // 标准 Cookie 作用域：按 Cookie 自己的 domain 匹配请求主机，
        // 不再用“账号保存时的域名”一刀切拦截（否则切换节点后 c_token 等
        // 验证 Cookie 永远带不回去，导致 DiamWall 重试永远 503）。
        val list = cookieMap.values.filter { cookie ->
            cookie.expiresAt >= System.currentTimeMillis() && cookie.matches(url)
        }.toMutableList()

        val remixUserKey = credentialStorage.getUserKey()
        val remixUserId = credentialStorage.getUserId()

        val loginMatches = requestHost.equals(currentDomain, ignoreCase = true) ||
            requestHost.endsWith(".$currentDomain", ignoreCase = true) ||
            requestHost.equals(storageDomain, ignoreCase = true) ||
            requestHost.endsWith(".$storageDomain", ignoreCase = true)

        if (loginMatches) {
            if (!remixUserKey.isNullOrBlank() && !list.any { it.name == "remix_userkey" }) {
                list.add(Cookie.Builder().name("remix_userkey").value(remixUserKey).domain(requestHost).build())
            }
            if (!remixUserId.isNullOrBlank() && !list.any { it.name == "remix_userid" }) {
                list.add(Cookie.Builder().name("remix_userid").value(remixUserId).domain(requestHost).build())
            }
        }
        return list
    }

    @Synchronized
    fun syncFromRawCookieString(rawCookieString: String, domain: String) {
        cookieMap.entries.removeAll { it.value.expiresAt < System.currentTimeMillis() }
        var userId: String? = null
        var userKey: String? = null

        rawCookieString.split(";").forEach { cookieStr ->
            val parts = cookieStr.trim().split("=", limit = 2)
            if (parts.size == 2) {
                val name = parts[0].trim()
                val value = parts[1].trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(domain)
                        .build()
                    cookieMap["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie
                    if (name == "remix_userid") userId = value
                    if (name == "remix_userkey") userKey = value
                }
            }
        }

        credentialStorage.saveCredentials(
            userId = userId ?: credentialStorage.getUserId(),
            userKey = userKey ?: credentialStorage.getUserKey(),
            domain = domain,
            cookies = rawCookieString
        )
    }
}
