package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A failed keystore never permits a plaintext read or write. */
internal class EncryptedSecretStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences? by lazy {
        synchronized(lock) {
            runCatching {
                val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(app, "app_secure_secrets", master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }.getOrNull()
        }
    }
    fun read(key: String, legacy: SharedPreferences? = null): String? = synchronized(lock) {
        val secure = prefs ?: return@synchronized null
        runCatching {
            secure.getString(key, null)?.let { return@runCatching it }
            val old = legacy?.getString(key, null) ?: return@runCatching null
            check(secure.edit().putString(key, old).commit()) { "密钥迁移失败" }
            check(legacy.edit().remove(key).commit()) { "旧密钥清理失败" }
            old
        }.getOrNull()
    }
    fun write(key: String, value: String) = synchronized(lock) {
        val secure = prefs ?: error("系统安全存储不可用，无法保存 API 密钥")
        if (secure.getString(key, null) != value) check(secure.edit().putString(key, value).commit()) { "API 密钥保存失败" }
    }
    fun remove(key: String) { runCatching { prefs?.edit()?.remove(key)?.apply() } }
    private companion object { val lock = Any() }
}
