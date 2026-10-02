package com.example.source.zlibrary

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ZLibraryCredentialStorage private constructor(preferencesFactory: () -> SharedPreferences?) {
    constructor(context: Context) : this({
        try {
            context.getSharedPreferences("zlib_credentials_fallback", Context.MODE_PRIVATE).edit().clear().apply()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "zlib_secure_credentials", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) { null }
    })

    // Test injection exercises cookie semantics independently of AndroidKeyStore support.
    internal constructor(preferences: SharedPreferences?) : this({ preferences })
    private val prefs: SharedPreferences? by lazy(preferencesFactory)

    fun saveCredentials(
        userId: String? = null,
        userKey: String? = null,
        domain: String = DEFAULT_DOMAIN,
        cookies: String? = null
    ) {
        val securePrefs = prefs ?: error("系统安全存储不可用，无法保存登录凭据，请修复系统密钥后重试")
        securePrefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_KEY, userKey)
            .putString(KEY_DOMAIN, domain.ifBlank { DEFAULT_DOMAIN })
            .putString(KEY_COOKIES, cookies)
            .apply()
    }

    fun getUserId(): String? = runCatching { prefs?.getString(KEY_USER_ID, null) }.getOrNull()
    fun getUserKey(): String? = runCatching { prefs?.getString(KEY_USER_KEY, null) }.getOrNull()
    fun getDomain(): String = runCatching { prefs?.getString(KEY_DOMAIN, DEFAULT_DOMAIN) }.getOrNull() ?: DEFAULT_DOMAIN
    fun getCookies(): String? = runCatching { prefs?.getString(KEY_COOKIES, null) }.getOrNull()

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    fun isLoggedIn(): Boolean {
        val cookies = getCookies()
        val userKey = getUserKey()
        return (!cookies.isNullOrBlank() && (cookies.contains("remix_userkey") || cookies.contains("remix_userid"))) || !userKey.isNullOrBlank()
    }

    companion object {
        const val DEFAULT_DOMAIN = "1lib.sk" // 2026-09-04 实测：唯一正确官网（rpc.php/eapi/搜索/下载全链路已验证）；z-lib.li 等为仿冒站
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_KEY = "userKey"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COOKIES = "cookies"
    }
}
