package com.example.source.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SharedPreferencesSourceStorage(context: Context) : SourceStorage {
    private val directory = java.io.File(context.filesDir, "source_configs").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("book_sources_config", Context.MODE_PRIVATE)

    override suspend fun saveSourceState(sourceId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("source_enabled_$sourceId", enabled).commit()
        Unit
    }

    override suspend fun getSourceStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Boolean>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("source_enabled_") && value is Boolean) {
                val sourceId = key.removePrefix("source_enabled_")
                result[sourceId] = value
            }
        }
        result
    }

    override suspend fun saveActiveSourceId(sourceId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString("active_source_id", sourceId).commit()
        Unit
    }

    override suspend fun getActiveSourceId(): String? = withContext(Dispatchers.IO) {
        prefs.getString("active_source_id", null)
    }

    private fun configFile(id: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return java.io.File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    override suspend fun saveCustomSourceJson(sourceId: String, jsonContent: String) = withContext(Dispatchers.IO) {
        synchronized(storageLock) {
            require(jsonContent.toByteArray().size <= 2 * 1024 * 1024) { "书源配置超过 2MiB" }
            val atomic = android.util.AtomicFile(configFile(sourceId))
            val out = atomic.startWrite()
            try { out.write(jsonContent.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
            val ids = prefs.getStringSet("custom_source_ids", emptySet()).orEmpty() + sourceId
            check(prefs.edit().putStringSet("custom_source_ids", ids).remove("custom_source_json_$sourceId").commit())
        }
    }

    override suspend fun getCustomSourceJsons(): Map<String, String> = withContext(Dispatchers.IO) {
        synchronized(storageLock) {
            val ids = prefs.getStringSet("custom_source_ids", emptySet()).orEmpty().toMutableSet()
            val editor = prefs.edit()
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("custom_source_json_") && value is String) {
                    val id = key.removePrefix("custom_source_json_")
                    require(value.toByteArray().size <= 2 * 1024 * 1024) { "书源配置超过 2MiB" }
                    val atomic = android.util.AtomicFile(configFile(id))
                    val out = atomic.startWrite()
                    try { out.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
                    catch (e: Exception) { atomic.failWrite(out); throw e }
                    ids.add(id); editor.remove(key)
                }
            }
            check(editor.putStringSet("custom_source_ids", ids).commit())
            ids.mapNotNull { id ->
                val f = configFile(id)
                if (f.isFile && f.length() <= 2 * 1024 * 1024) id to android.util.AtomicFile(f).openRead().bufferedReader().use { it.readText() } else null
            }.toMap()
        }
    }

    override suspend fun removeCustomSourceJson(sourceId: String) = withContext(Dispatchers.IO) {
        synchronized(storageLock) {
            check(prefs.edit().putStringSet("custom_source_ids", prefs.getStringSet("custom_source_ids", emptySet()).orEmpty() - sourceId)
                .remove("custom_source_json_$sourceId").remove("source_enabled_$sourceId").commit())
            android.util.AtomicFile(configFile(sourceId)).delete()
        }
    }

    companion object { private val storageLock = Any() }
}
