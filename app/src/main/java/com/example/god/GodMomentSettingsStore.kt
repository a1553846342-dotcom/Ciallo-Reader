package com.example.god

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 神回专属 DataStore（与既有 SharedPreferences 体系并存，互不干扰）。 */
private val Context.godDataStore: DataStore<Preferences> by preferencesDataStore(name = "god_moment_prefs")

/**
 * 神回设置项（设置页「神回」分组）。
 *
 * - 排行榜风格 [GodRankingStyle]
 * - 末页「标记神回」提示胶囊开关（默认开）
 * - 排行榜陀螺仪视差开关（默认开；设备无旋转向量传感器时 UI 侧自动隐藏）
 */
class GodMomentSettingsStore(private val context: Context) {

    private object Keys {
        val RANKING_STYLE = stringPreferencesKey("ranking_style")
        val HINT_CAPSULE = booleanPreferencesKey("last_page_hint")
        val GYRO_PARALLAX = booleanPreferencesKey("gyro_parallax")
    }

    /**
     * 统一兜底：DataStore 读文件失败（磁盘满 / 文件损坏）时返回默认值，
     * 不允许把 App 带崩 —— 神回只是附加功能。
     */
    private fun <T> Flow<T>.guarded(default: T, tag: String): Flow<T> =
        this.catch { e ->
            android.util.Log.w("GodMoment", "settings read failed: $tag", e)
            emit(default)
        }

    val rankingStyle: Flow<GodRankingStyle> =
        context.godDataStore.data
            .map { GodRankingStyle.of(it[Keys.RANKING_STYLE]) }
            .guarded(GodRankingStyle.PODIUM, "rankingStyle")

    val hintCapsuleEnabled: Flow<Boolean> =
        context.godDataStore.data
            .map { it[Keys.HINT_CAPSULE] ?: true }
            .guarded(true, "hintCapsule")

    val gyroParallaxEnabled: Flow<Boolean> =
        context.godDataStore.data
            .map { it[Keys.GYRO_PARALLAX] ?: true }
            .guarded(true, "gyro")

    suspend fun rankingStyleOnce(): GodRankingStyle = rankingStyle.first()

    suspend fun setRankingStyle(style: GodRankingStyle) {
        context.godDataStore.edit { it[Keys.RANKING_STYLE] = style.code }
    }

    suspend fun setHintCapsuleEnabled(enabled: Boolean) {
        context.godDataStore.edit { it[Keys.HINT_CAPSULE] = enabled }
    }

    suspend fun setGyroParallaxEnabled(enabled: Boolean) {
        context.godDataStore.edit { it[Keys.GYRO_PARALLAX] = enabled }
    }
}
