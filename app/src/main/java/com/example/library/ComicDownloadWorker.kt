package com.example.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.data.AppDatabase
import com.example.download.DownloadWorker
import com.example.source.SourceResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ComicDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        if (android.os.Build.VERSION.SDK_INT>=26) applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("comic_downloads","漫画下载",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"comic_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("正在下载漫画章节").setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"暂停",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(android.os.Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode() and Int.MAX_VALUE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode() and Int.MAX_VALUE,notification)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val key=inputData.getString("task_id") ?: return@withContext Result.failure()
        DownloadWorker.withTaskLock(key) {
            val task=ComicDownloadManager.task(applicationContext,key) ?: return@withTaskLock Result.failure()
            if(task.status!=ComicDownloadStatus.DOWNLOADING) return@withTaskLock Result.success()
            try {
                setForeground(getForegroundInfo())
                slots.withPermit {
                    val dao=AppDatabase.getDatabase(applicationContext).bookDao()
                    val dir=ComicDownloadManager.directory(applicationContext,key)
                    if(dao.getBookByFilePath(dir.absolutePath)==null) {
                        val source=ComicDownloadManager.source(applicationContext,task.book.sourceId) ?: error("书源未安装，请重新启用该书源后重试")
                        val images=when(val result=source.getChapterImages(task.chapter.id)) {
                            is SourceResult.Success -> result.data
                            is SourceResult.Error -> throw result.exception
                        }
                        ComicLocalImporter.importChapter(applicationContext,dao,task.book,task.chapter,images,
                            headers=source.getChapterImageHeaders(task.chapter.id,images),targetDir=dir,
                            resolveImage={ source.resolveChapterImage(it) },resolveHeaders={ source.getResolvedHeaders(it) },concurrency=3,
                            onProgress={ progress -> ComicDownloadManager.update(key,false) { it.copy(progress=progress) } }).getOrThrow()
                    }
                    withContext(NonCancellable) {
                        ComicDownloadManager.update(key) { it.copy(status=ComicDownloadStatus.SUCCESS,progress=1f,error=null) }
                    }
                    ComicDownloadManager.message("已下载到书架：${task.book.title} · ${task.chapter.title}")
                }
                Result.success()
            } catch(e:CancellationException) {
                ComicDownloadManager.update(key) { it.copy(status=ComicDownloadStatus.PAUSED) }
                throw e
            } catch(e:Exception) {
                ComicDownloadManager.update(key) { it.copy(status=ComicDownloadStatus.FAILED,error=e.message ?: "章节下载失败") }
                ComicDownloadManager.message("下载失败：${e.message ?: "章节下载失败"}")
                Result.failure()
            }
        }
    }
    companion object { private val slots=Semaphore(2) }
}
