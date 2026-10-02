package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class BackupPayload(
    val exportTime: Long = System.currentTimeMillis(),
    val booksCount: Int,
    val preferences: Map<String, String>
)

/** Full portable ZIP backup plus compatibility JSON envelope. */
class BackupManager(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val godRepository: com.example.god.GodMomentRepository? = null,
) {

    suspend fun exportBackupArchive(target: File): File {
        ContentMutationGate.mutex.lock()
        return try { BackupArchive.export(context,target) } finally { ContentMutationGate.mutex.unlock() }
    }

    suspend fun restoreBackupArchive(archive: File): Boolean = withContext(Dispatchers.IO) {
        com.example.download.DownloadManager.withControlLock {
            com.example.library.ComicDownloadManager.withControlLock {
                val dao=AppDatabase.getDatabase(context).downloadTaskDao()
                val work=androidx.work.WorkManager.getInstance(context)
                for(task in dao.getAllTasksSync()) {
                    if(task.status in setOf(com.example.download.DownloadStatus.PENDING,com.example.download.DownloadStatus.DOWNLOADING)) {
                        work.cancelUniqueWork("download_${task.id}").result.get()
                        com.example.download.DownloadWorker.withTaskLock(task.id) {
                            val fresh=dao.getTaskById(task.id)
                            if(fresh!=null && fresh.status!=com.example.download.DownloadStatus.COMPLETED)
                                dao.updateProgressAndStatus(task.id,com.example.download.DownloadStatus.PAUSED,fresh.downloadedBytes,fresh.totalBytes,null)
                        }
                    }
                }
                dao.getAllTasksSync().forEach { com.example.download.DownloadProgressBroadcaster.removeState(it.id) }
                com.example.library.ComicDownloadManager.pauseAllLocked(context)
                ContentMutationGate.mutex.lock()
                try { BackupArchive.restore(context,archive).also { if(it) ContentMutationGate.invalidatePendingWrites() } } finally { ContentMutationGate.mutex.unlock() }
            }
        }
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val archive = exportBackupArchive(File(context.cacheDir, "backup_${java.util.UUID.randomUUID()}.zip"))
        val encoded = try {
            require(archive.length() <= 16L * 1024 * 1024) { "备份超过 JSON 容量，请使用 ZIP 备份接口" }
            android.util.Base64.encodeToString(archive.readBytes(), android.util.Base64.NO_WRAP)
        } finally { archive.delete() }
        val godArray = godRepository?.runCatching { exportJson() }?.getOrNull()
        val payload = BackupPayload(
            booksCount = AppDatabase.getDatabase(context).bookDao().getBooksCount(),
            preferences = mapOf(
                "fontSize" to prefs.fontSize.toString(),
                "lineHeight" to prefs.lineHeight.toString(),
                "readerTheme" to prefs.readerTheme.toString(),
                "pageTurnMode" to prefs.pageTurnMode.toString(),
                "splashPureMode" to prefs.splashPureMode.toString(),
                "screenOrientationLock" to prefs.screenOrientationLock.toString(),
                "restReminderMinutes" to prefs.restReminderMinutes.toString()
            )
        )
        val json = JSONObject()
            .put("archive", encoded)
            .put("exportTime", payload.exportTime)
            .put("booksCount", payload.booksCount)
            .put("preferences", JSONObject(payload.preferences))
            .apply { if (godArray != null) put("godMoments", godArray) }
            .toString()

        val file = File(context.filesDir, "novel_reader_backup.json")
        file.writeText(json)
        json
    }

    suspend fun restoreBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val payload = JSONObject(jsonString)
            payload.optString("archive").takeIf { it.isNotBlank() }?.let { encoded ->
                require(encoded.length <= 24 * 1024 * 1024)
                val archive = File(context.cacheDir, "restore_${java.util.UUID.randomUUID()}.zip")
                try { archive.writeBytes(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)); return@withContext restoreBackupArchive(archive) }
                finally { archive.delete() }
            }
            val preferences = payload.optJSONObject("preferences") ?: return@withContext false

            preferences.optString("fontSize").toFloatOrNull()?.takeIf { it.isFinite() && it in 8f..80f }?.let { prefs.fontSize = it }
            preferences.optString("lineHeight").toFloatOrNull()?.takeIf { it.isFinite() && it in 8f..120f }?.let { prefs.lineHeight = it }
            preferences.optString("readerTheme").toIntOrNull()?.takeIf { it in 0..5 }?.let { prefs.readerTheme = it }
            preferences.optString("pageTurnMode").toIntOrNull()?.takeIf { it in 0..4 }?.let { prefs.pageTurnMode = it }
            preferences.optString("splashPureMode").toBooleanStrictOrNull()?.let { prefs.splashPureMode = it }
            preferences.optString("screenOrientationLock").toIntOrNull()?.takeIf { it in 0..2 }?.let { prefs.screenOrientationLock = it }
            preferences.optString("restReminderMinutes").toIntOrNull()?.takeIf { it in 0..1440 }?.let { prefs.restReminderMinutes = it }

            // 神回：同 (bookId, chapterId) 覆盖；缺失字段走默认值，旧备份文件照样能读
            payload.optJSONArray("godMoments")?.let { godRepository?.importJson(it) }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}
