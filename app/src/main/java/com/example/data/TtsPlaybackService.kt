package com.example.data

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TtsPlaybackService : Service() {
    private var owner: java.lang.ref.WeakReference<TtsManager>? = null
    private var generation = -1L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") {
            if (intent.getLongExtra("generation", -1L) == generation) {
                owner?.get()?.pauseIfGeneration(generation)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (manager?.get() == null) { stopSelf(); return START_NOT_STICKY }
        owner = manager
        generation = intent?.getLongExtra("generation", -1L) ?: -1L
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("reading_tts", "朗读", NotificationManager.IMPORTANCE_LOW))
        }
        val pause = PendingIntent.getService(this, 0, Intent(this, TtsPlaybackService::class.java).setAction("pause").putExtra("generation", generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        startForeground(302, NotificationCompat.Builder(this, "reading_tts")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("正在朗读")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "暂停", pause).build())
        return START_NOT_STICKY
    }
    override fun onDestroy() { owner?.get()?.pauseIfGeneration(generation); super.onDestroy() }
    companion object { var manager: java.lang.ref.WeakReference<TtsManager>? = null }
}
