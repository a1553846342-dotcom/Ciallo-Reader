package com.example.ui.comic

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 漫画阅读器设置仓库：
 * - 全局配置 + 内置预设 + 用户预设（创建/编辑/复制/删除/默认）
 * - 每本漫画独立配置覆盖与阅读状态（进度/单页旋转/临时合页）
 *
 * 全部走独立 SharedPreferences（comic_reader_store），不触碰应用其它偏好，
 * 保证阅读页升级零回归风险。
 */
class ComicSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("comic_reader_store", Context.MODE_PRIVATE)

    companion object {
        /** 内置预设 id 固定，不可删除 */
        const val PRESET_MANGA = "preset_builtin_manga"       // 日漫
        const val PRESET_WEBTOON = "preset_builtin_webtoon"   // 条漫
        const val PRESET_CLASSIC = "preset_builtin_classic"   // 老漫画

        private val BUILTIN_IDS = setOf(PRESET_MANGA, PRESET_WEBTOON, PRESET_CLASSIC)

        private fun builtinPreset(id: String, name: String, emoji: String, config: ComicReaderConfig) =
            ComicPreset(id = id, name = name, emoji = emoji, config = config, builtIn = true)

        private fun builtins(): List<ComicPreset> = listOf(
            builtinPreset(
                PRESET_MANGA, "日漫", "JP",
                ComicReaderConfig(
                    mode = ComicMode.SINGLE,
                    direction = ComicDirection.RTL,
                    fit = ComicFit.FIT_WIDTH,
                    pageAnim = ComicPageAnim.CURL,
                    bgType = ComicBgType.PAPER,
                )
            ),
            builtinPreset(
                PRESET_WEBTOON, "条漫", "WL",
                ComicReaderConfig(
                    mode = ComicMode.WEBTOON,
                    direction = ComicDirection.TTB,
                    pageAnim = ComicPageAnim.NONE,
                    pageSpacingDp = 0f,
                    bgType = ComicBgType.BLACK,
                )
            ),
            builtinPreset(
                PRESET_CLASSIC, "老漫画", "RZ",
                ComicReaderConfig(
                    mode = ComicMode.SINGLE,
                    direction = ComicDirection.LTR,
                    fit = ComicFit.FIT_WIDTH,
                    cropMode = ComicCropMode.AUTO,
                    enhanceMode = ComicEnhanceMode.CAS,
                    filterGamma = 0.9f,
                    filterSharpen = 30,
                    bgType = ComicBgType.GRAY,
                )
            ),
        )
    }

    /* ── 全局配置 ── */

    fun loadGlobalConfig(): ComicReaderConfig {
        ensureBuiltinPresets()
        // 默认配置 = 默认预设（用户可换默认预设）
        val defaultId = prefs.getString(KEY_DEFAULT_PRESET, PRESET_MANGA)
        val preset = loadPresets().firstOrNull { it.id == defaultId }
        val saved = prefs.getString(KEY_GLOBAL_CONFIG, null)
            ?.let { runCatching { ComicReaderConfig.fromJson(JSONObject(it)) }.getOrNull() }
        return saved ?: preset?.config ?: ComicReaderConfig()
    }

    fun saveGlobalConfig(config: ComicReaderConfig) {
        prefs.edit().putString(KEY_GLOBAL_CONFIG, config.toJson().toString()).apply()
    }

    /* ── 预设系统 ── */

    fun loadPresets(): List<ComicPreset> {
        ensureBuiltinPresets()
        val raw = prefs.getString(KEY_PRESETS, null) ?: return builtins()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return builtins()
        val result = mutableListOf<ComicPreset>()
        for (i in 0 until arr.length()) {
            runCatching { ComicPreset.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let { result.add(it) }
        }
        return result.ifEmpty { builtins().also { savePresets(it) } }
    }

    private fun savePresets(list: List<ComicPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun createPreset(name: String, emoji: String, config: ComicReaderConfig): ComicPreset {
        val preset = ComicPreset(
            id = "preset_" + UUID.randomUUID().toString().take(12),
            name = name, emoji = emoji, config = config, builtIn = false
        )
        savePresets(loadPresets() + preset)
        return preset
    }

    /** 收藏/取消收藏预设（第 27 条）：收藏的预设在列表中置顶展示 */
    fun togglePresetFavorite(id: String): ComicPreset? {
        val list = loadPresets().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = list[idx].copy(favorite = !list[idx].favorite)
        list[idx] = updated
        // 收藏置顶排序：favorite 优先，组内保持原相对顺序（稳定排序）
        savePresets(list.sortedByDescending { it.favorite })
        return updated
    }

    /** 收藏的预设（第 27 条：长按设置入口快捷弹出的候选） */
    fun favoritePresets(): List<ComicPreset> = loadPresets().filter { it.favorite }

    /* ── 自定义缩放预设（第 26 条） ── */

    fun loadCustomFitPresets(): List<ComicCustomFitPreset> {
        val raw = prefs.getString(KEY_CUSTOM_FIT_PRESETS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ComicCustomFitPreset>()
        for (i in 0 until arr.length()) {
            runCatching { ComicCustomFitPreset.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun saveCustomFitPresets(list: List<ComicCustomFitPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CUSTOM_FIT_PRESETS, arr.toString()).apply()
    }

    fun saveCustomFitPreset(name: String, base: ComicFit, scalePct: Int): ComicCustomFitPreset {
        val preset = ComicCustomFitPreset(
            id = "cfit_" + UUID.randomUUID().toString().take(10),
            name = name.ifBlank { "缩放 %d%%".format(scalePct) },
            base = base, scalePct = scalePct.coerceIn(50, 250),
        )
        saveCustomFitPresets(loadCustomFitPresets() + preset)
        return preset
    }

    fun deleteCustomFitPreset(id: String): Boolean {
        val list = loadCustomFitPresets()
        if (list.none { it.id == id }) return false
        saveCustomFitPresets(list.filterNot { it.id == id })
        return true
    }

    /** 内置预设不可改写：返回 null（UI 仅对用户预设提供编辑入口）。 */
    fun updatePreset(id: String, name: String? = null, config: ComicReaderConfig? = null): ComicPreset? {
        val list = loadPresets().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val old = list[idx]
        if (old.builtIn) return null
        val updated = old.copy(
            name = name ?: old.name,
            config = config ?: old.config
        )
        list[idx] = updated
        savePresets(list)
        return updated
    }

    fun duplicatePreset(id: String): ComicPreset? {
        val list = loadPresets()
        val src = list.firstOrNull { it.id == id } ?: return null
        val copy = ComicPreset(
            id = "preset_" + UUID.randomUUID().toString().take(12),
            name = src.name + " 副本",
            emoji = src.emoji,
            config = src.config,
            builtIn = false
        )
        savePresets(list + copy)
        return copy
    }

    fun deletePreset(id: String): Boolean {
        if (id in BUILTIN_IDS) return false
        val list = loadPresets()
        if (list.none { it.id == id }) return false
        savePresets(list.filterNot { it.id == id })
        if (defaultPresetId() == id) {
            setDefaultPreset(PRESET_MANGA)
        }
        return true
    }

    fun defaultPresetId(): String = prefs.getString(KEY_DEFAULT_PRESET, PRESET_MANGA) ?: PRESET_MANGA

    /** 设为默认预设：新开阅读即以该预设起步（清除手动改过的全局配置） */
    fun setDefaultPreset(id: String) {
        val preset = loadPresets().firstOrNull { it.id == id } ?: return
        prefs.edit()
            .putString(KEY_DEFAULT_PRESET, id)
            .remove(KEY_GLOBAL_CONFIG)
            .apply()
        // 触发一次保存使 preset 配置成为新全局
        saveGlobalConfig(preset.config)
    }

    private fun ensureBuiltinPresets() {
        val raw = prefs.getString(KEY_PRESETS, null)
        if (raw == null) {
            savePresets(builtins())
            return
        }
        // Decode directly: loadPresets() calls this method and would recurse when
        // an upgrade needs to restore a missing built-in entry.
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: run {
            savePresets(builtins())
            return
        }
        val existing = mutableListOf<ComicPreset>()
        for (i in 0 until arr.length()) {
            runCatching { ComicPreset.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let(existing::add)
        }
        // Built-ins are immutable; migrate old directions/glyphs, preserving
        // favorites, custom presets, the default selection and per-book settings.
        val defaults = builtins().associateBy { it.id }
        val migrated = existing.map { old -> defaults[old.id]?.copy(favorite = old.favorite) ?: old }
        val missing = defaults.values.filter { candidate -> migrated.none { it.id == candidate.id } }
        val merged = missing + migrated
        if (merged != existing || existing.size != arr.length()) savePresets(merged)
    }

    /* ── 每本漫画独立配置 / 状态 ── */

    fun loadBookConfig(bookKey: String): ComicReaderConfig? =
        prefs.getString("book_cfg_$bookKey", null)
            ?.let { runCatching { ComicReaderConfig.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveBookConfig(bookKey: String, config: ComicReaderConfig) {
        prefs.edit().putString("book_cfg_$bookKey", config.toJson().toString()).apply()
    }

    fun clearBookConfig(bookKey: String) {
        prefs.edit().remove("book_cfg_$bookKey").apply()
    }

    fun loadBookState(bookKey: String): ComicBookState =
        prefs.getString("book_state_$bookKey", null)
            ?.let { runCatching { ComicBookState.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ComicBookState()

    fun saveBookState(bookKey: String, state: ComicBookState) {
        prefs.edit().putString("book_state_$bookKey", state.toJson().toString()).apply()
    }

    /** 阅读器启动时的有效配置：本漫画覆盖 > 全局。hasOverride 用于 UI 提示。 */
    data class EffectiveConfig(val config: ComicReaderConfig, val hasOverride: Boolean)

    fun effectiveConfig(bookKey: String?): EffectiveConfig {
        val global = loadGlobalConfig()
        if (bookKey == null) return EffectiveConfig(global, false)
        val override = loadBookConfig(bookKey)
        return if (override != null) EffectiveConfig(override, true) else EffectiveConfig(global, false)
    }
}

/** 一套阅读预设 */
data class ComicPreset(
    val id: String,
    val name: String,
    val emoji: String,
    val config: ComicReaderConfig,
    val builtIn: Boolean = false,
    /** 收藏星标（第 27 条）：收藏的预设列表置顶 + 长按设置入口快捷应用 */
    val favorite: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("emoji", emoji)
        put("builtIn", builtIn)
        put("favorite", favorite)
        put("config", config.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): ComicPreset = ComicPreset(
            id = json.optString("id"),
            name = json.optString("name", "预设"),
            emoji = json.optString("emoji", "SC"),
            config = ComicReaderConfig.fromJson(json.optJSONObject("config") ?: JSONObject()),
            builtIn = json.optBoolean("builtIn", false),
            favorite = json.optBoolean("favorite", false),
        )
    }
}

/** 自定义缩放预设（第 26 条）：基础适配档 + 缩放系数 */
data class ComicCustomFitPreset(
    val id: String,
    val name: String,
    val base: ComicFit,
    val scalePct: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("base", base.name)
        put("scalePct", scalePct)
    }

    companion object {
        fun fromJson(json: JSONObject): ComicCustomFitPreset = ComicCustomFitPreset(
            id = json.optString("id"),
            name = json.optString("name", "自定义"),
            base = ComicFit.entries.firstOrNull { it.name == json.optString("base") } ?: ComicFit.FIT_PAGE,
            scalePct = json.optInt("scalePct", 100).coerceIn(50, 250),
        )
    }
}

private const val KEY_GLOBAL_CONFIG = "global_config"
private const val KEY_PRESETS = "presets"
private const val KEY_DEFAULT_PRESET = "default_preset"
private const val KEY_CUSTOM_FIT_PRESETS = "custom_fit_presets"
