package com.example.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 隐私模式管理器（第七轮第 6.4 条）。
 *
 * - 全局 6 位数字 PIN：首次开启隐私模式时设置（输入 + 二次确认）；
 * - 存储做基本安全处理：随机盐 + PBKDF2（兼容旧 SHA-256 并在验证后升级），不落明文（补充说明第 3 条）；
 * - 开关状态持久化——重启 App 后受保护分类仍需 PIN 验证；
 * - 无痕浏览（6.5）语义由 MainViewModel 按"隐私模式开启 且 书籍所在分类受保护"
 *   判定，本类只提供开关与校验。
 */
class PrivacyManager(context: Context) {

    private val prefs = context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "privacy_mode_enabled"
        private const val KEY_PIN_HASH = "privacy_pin_hash"
        private const val KEY_PIN_SALT = "privacy_pin_salt"
        private const val PIN_LENGTH = 6

        fun pinLength(): Int = PIN_LENGTH

        /** SHA-256(盐 + PIN) 十六进制 */
        internal fun hashPin(pin: String, saltHex: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            digest.update(salt)
            digest.update(pin.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun newSaltHex(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    /** 隐私模式是否开启 */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** 是否已设置过 PIN（用于区分"首次开启"与"后续验证"） */
    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    /**
     * 设置 PIN 并启用隐私模式（首次开启流程）。
     * @return false = PIN 格式非法（非 6 位数字）
     */
    @Synchronized
    fun enableWithPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val salt = newSaltHex()
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, derivePin(pin, salt))
            .putInt("pin_kdf_version", 1)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        return true
    }

    /** 验证 PIN（常数时间比较防时序侧信道的基本形态） */
    @Synchronized
    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        if (!isValidPin(pin) || salt.length != 32 || salt.any { it.digitToIntOrNull(16) == null }) return false
        if (System.currentTimeMillis() < prefs.getLong("pin_retry_after", 0)) return false
        val modern = prefs.getInt("pin_kdf_version", 0) == 1
        val candidate = if (modern) derivePin(pin, salt) else hashPin(pin, salt)
        if (candidate.length != stored.length) return false
        var diff = 0
        for (i in candidate.indices) diff = diff or (candidate[i].code xor stored[i].code)
        if (diff != 0) {
            val failures = prefs.getInt("pin_failures", 0) + 1
            prefs.edit().putInt("pin_failures", failures)
                .putLong("pin_retry_after", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0).apply()
            return false
        }
        val editor = prefs.edit().putInt("pin_failures", 0).putLong("pin_retry_after", 0)
        if (!modern) editor.putString(KEY_PIN_HASH, derivePin(pin, salt)).putInt("pin_kdf_version", 1)
        editor.apply()
        return true
    }

    /** 修改 PIN（需先验证旧 PIN） */
    @Synchronized
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin) || !isValidPin(newPin)) return false
        val salt = newSaltHex()
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, derivePin(newPin, salt))
            .putInt("pin_kdf_version", 1)
            .apply()
        return true
    }

    /** 关闭隐私模式（需先验证 PIN；分类保护标记保留在 DB，开关关闭期间不生效） */
    @Synchronized
    fun disable(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        return true
    }

    private fun derivePin(pin: String, salt: String): String {
        val bytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), bytes, 120_000, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
                .joinToString("") { "%02x".format(it) }
        } finally { spec.clearPassword() }
    }

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
}
