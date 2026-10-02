package com.example.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generation = 0L
    private var chunkIndex = 0
    private var chunks = emptyList<Pair<Int, String>>()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _currentParagraphIndex = MutableStateFlow(0)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) handler.post { pause() }
    }
    init {
        try { tts = TextToSpeech(app, this) }
        catch (_: Exception) { _error.value = "设备没有可用的朗读引擎" }
    }
    override fun onInit(status: Int) { handler.post {
        val engine = tts
        ready = status == TextToSpeech.SUCCESS && engine != null
        if (!ready) { _error.value = "朗读引擎初始化失败"; return@post }
        val language = engine!!.setLanguage(Locale.CHINESE)
        if (language < 0) { ready = false; _error.value = "请先安装中文朗读语音"; return@post }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post {
                if (_isPlaying.value && id == "$generation:$chunkIndex") {
                    if (++chunkIndex < chunks.size) speak() else pause()
                }
            } }
            @Deprecated("Platform callback")
            override fun onError(id: String?) { handler.post {
                if (id == "$generation:$chunkIndex") { _error.value = "朗读失败，请检查系统语音引擎"; pause() }
            } }
        })
    } }
    fun startReading(content: String, speed: Float = 1f, pitch: Float = 1f) {
        if (!ready) { _error.value = "朗读引擎尚未就绪"; return }
        pausePlayback(stopService = false)
        val max = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(2)
        chunks = content.replace(Regex("\\[IMG:[^\\]]*]"), "").split('\n').filter { it.isNotBlank() }.flatMapIndexed { paragraph, text ->
            splitChapterText(text, max).map { paragraph to it }
        }
        chunkIndex = 0
        tts?.setSpeechRate(speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f)
        tts?.setPitch(pitch.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f)
        if (chunks.isNotEmpty()) begin() else pause()
    }
    @Suppress("DEPRECATION")
    private fun begin() {
        if (audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        _error.value = null
        TtsPlaybackService.manager = java.lang.ref.WeakReference(this)
        try { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, TtsPlaybackService::class.java).putExtra("generation", generation)) }
        catch (_: Exception) { _error.value = "无法启动后台朗读服务"; audio.abandonAudioFocus(focus); return }
        _isPlaying.value = true
        speak()
    }
    private fun speak() {
        val chunk = chunks.getOrNull(chunkIndex) ?: return pause()
        _currentParagraphIndex.value = chunk.first
        if (tts?.speak(chunk.second, TextToSpeech.QUEUE_FLUSH, null, "$generation:$chunkIndex") != TextToSpeech.SUCCESS) {
            _error.value = "朗读请求失败"; pause()
        }
    }
    @Suppress("DEPRECATION")
    fun pause() = pausePlayback(stopService = true)
    internal fun pauseIfGeneration(expected: Long) {
        if (generation == expected && _isPlaying.value) pause()
    }
    @Suppress("DEPRECATION")
    private fun pausePlayback(stopService: Boolean) {
        generation++
        _isPlaying.value = false
        tts?.stop()
        audio.abandonAudioFocus(focus)
        if (stopService) app.stopService(Intent(app, TtsPlaybackService::class.java))
    }
    fun nextParagraph() {
        val target = chunks.indexOfFirst { it.first > _currentParagraphIndex.value }
        if (target >= 0) { generation++; chunkIndex = target; begin() }
    }
    fun previousParagraph() {
        val paragraph = (_currentParagraphIndex.value - 1).coerceAtLeast(0)
        val target = chunks.indexOfFirst { it.first == paragraph }
        if (target >= 0) { generation++; chunkIndex = target; begin() }
    }
    fun stop() = pause()
    fun release() { pause(); ready = false; tts?.shutdown(); tts = null }
}
