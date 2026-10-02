package com.example.mangatranslate

import android.content.Context
import com.example.source.executeCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 翻译模型下载管理器：det/rec 两个 ONNX（共约 31MB）按需下载到
 * filesDir/manga_translate_models/，多源容灾（hf-mirror 优先照顾国内直连，
 * huggingface.co 兜底），断点安全（tmp 文件 + 原子重命名 + 尺寸校验）。
 *
 * 模型不打包进 APK（保持 9.9MB 瘦身成果），首次开启翻译时在设置面板里下载。
 */
object TranslateModelManager {

    data class ModelSpec(
        val fileName: String,
        /** 依次尝试的下载源（host 全部为 https 公网 CDN）。 */
        val urls: List<String>,
        val minBytes: Long,
        val label: String,
        val sha256: String? = null,
    )

    val detModel = ModelSpec(
        fileName = "ppocr_det.onnx",
        urls = listOf(
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx",
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx",
        ),
        minBytes = 9_000_000L,
        label = "文字检测模型",
        sha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    )
    val recModel = ModelSpec(
        fileName = "ppocr_rec.onnx",
        urls = listOf(
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx",
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx",
        ),
        minBytes = 19_000_000L,
        label = "文字识别模型",
        sha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    )

    /**
     * YOLO26n-seg 气泡分割模型（2026-09-21：由 APK 内置改为按需下载）。
     *
     * 原随 APK assets 打包（4.0MB，压缩后约 2.3MB）。该模型没有官方公开下载源
     * （作者仅随 APK 分发），但 MIT License 允许再分发，因此托管到本仓库并用
     * jsDelivr CDN 分发，与 det/rec 一样走「首次使用时下载」。
     * 三源容灾：jsDelivr（国内可达性最好）→ GitHub raw → GitHub Release 直链。
     */
    val bubbleModel = ModelSpec(
        fileName = "mt_yolo_bubble.onnx",
        // 源顺序按「国内直连可达性」排：GitHub 国内不可直连，故全部走代理/CDN，
        // 末尾保留两个海外直连作为最终兜底（境外用户或已挂代理时最快）。
        urls = listOf(
            // 1) ghproxy.net 代理 GitHub Release 直链（国内最常用的 GitHub 文件代理）
            "https://ghproxy.net/https://github.com/a1553846342-dotcom/EASYREADER/releases/download/v1.0.7/manga-bubble-seg-yolo26n.onnx",
            // 2) jsDelivr 的 Fastly 节点（相对 cdn.jsdelivr.net 国内可达性更好）
            "https://fastly.jsdelivr.net/gh/a1553846342-dotcom/EASYREADER@main/models/manga-bubble-seg-yolo26n.onnx",
            // 3) gh-proxy.com 代理 GitHub raw
            "https://gh-proxy.com/https://raw.githubusercontent.com/a1553846342-dotcom/EASYREADER/main/models/manga-bubble-seg-yolo26n.onnx",
            // 4) jsDelivr 主域（2021 年 ICP 被吊销后国内时好时坏，仍保留）
            "https://cdn.jsdelivr.net/gh/a1553846342-dotcom/EASYREADER@main/models/manga-bubble-seg-yolo26n.onnx",
            // 5) 海外直连兜底：GitHub raw
            "https://raw.githubusercontent.com/a1553846342-dotcom/EASYREADER/main/models/manga-bubble-seg-yolo26n.onnx",
            // 6) 海外直连兜底：GitHub Release（已作为 v1.0.7 asset 上传）
            "https://github.com/a1553846342-dotcom/EASYREADER/releases/download/v1.0.7/manga-bubble-seg-yolo26n.onnx",
        ),
        minBytes = 4_000_000L,
        label = "气泡分割模型",
        sha256 = "a01ad9477fac2b8815c2308c827c0a60a898f457a6692717201123911ba819c0",
    )

    sealed interface DownloadState {
        data object NotDownloaded : DownloadState
        data class Downloading(val which: String, val progress: Float) : DownloadState
        data object Ready : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val state: StateFlow<DownloadState> = _state
    private val mutex = Mutex()
    private val verified = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val verificationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    private val client by lazy {
        com.example.source.SharedHttpTransport.builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun modelDir(context: Context): File =
        File(context.filesDir, "manga_translate_models").apply { mkdirs() }

    fun detFile(context: Context): File = File(modelDir(context), detModel.fileName)
    fun recFile(context: Context): File = File(modelDir(context), recModel.fileName)
    fun bubbleFile(context: Context): File = File(modelDir(context), bubbleModel.fileName)

    fun isReady(context: Context): Boolean {
        val specs = listOf(detModel, recModel, bubbleModel)
        val files = specs.map { File(modelDir(context), it.fileName) }
        if (files.zip(specs).any { (file, spec) -> !file.isFileAndBig(spec.minBytes) }) return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val ready = files.zip(specs).all { (file, spec) -> verified[file.signature(spec)] == true }
            if (!ready && checking.compareAndSet(false, true)) {
                val app = context.applicationContext
                verificationScope.launchVerification(app)
            }
            return ready
        }
        return files.zip(specs).all { (file, spec) -> file.isVerified(spec) }
    }
    private fun kotlinx.coroutines.CoroutineScope.launchVerification(context: Context) = launch {
        try {
            mutex.withLock {
                _state.value = if (isReady(context)) DownloadState.Ready else DownloadState.NotDownloaded
            }
        } finally { checking.set(false) }
    }
    private fun File.isFileAndBig(min: Long): Boolean = isFile && length() >= min
    private fun File.signature(spec: ModelSpec) = "$absolutePath:${length()}:${lastModified()}:${spec.sha256}"
    private fun File.isVerified(spec: ModelSpec): Boolean {
        if (!isFileAndBig(spec.minBytes)) return false
        val key = signature(spec)
        verified[key]?.let { return it }
        val valid = spec.sha256 == null || runCatching { sha256(this) == spec.sha256 }.getOrDefault(false)
        if (verified.size > 32) verified.clear()
        verified[key] = valid
        return valid
    }
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun totalBytes(context: Context): Long =
        (detFile(context).takeIf { it.isFile }?.length() ?: 0L) +
            (recFile(context).takeIf { it.isFile }?.length() ?: 0L) +
            (bubbleFile(context).takeIf { it.isFile }?.length() ?: 0L)

    /**
     * 下载缺失模型（已就位则跳过）。进度以两模型合计汇报。
     * 返回 null = 全部就绪；否则返回失败原因。
     */
    suspend fun ensureDownloaded(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            if (isReady(appContext)) {
                _state.value = DownloadState.Ready
                return@withContext null
            }
            val totalMin = (detModel.minBytes + recModel.minBytes + bubbleModel.minBytes).toFloat()
            var doneBytes = 0L
            for (spec in listOf(detModel, recModel, bubbleModel)) {
                val target = File(modelDir(appContext), spec.fileName)
                if (target.isVerified(spec)) {
                    doneBytes += target.length()
                    continue
                }
                val err = downloadOne(spec, target) { fileFraction ->
                    onProgress(((doneBytes + fileFraction * spec.minBytes) / totalMin).coerceIn(0f, 1f))
                }
                if (err != null) {
                    _state.value = DownloadState.Failed(err)
                    return@withContext err
                }
                doneBytes += target.length()
                onProgress((doneBytes / totalMin).coerceIn(0f, 1f))
            }
            _state.value = DownloadState.Ready
            null
        }
    }

    private suspend fun downloadOne(
        spec: ModelSpec,
        target: File,
        onProgress: (Float) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var lastError: String? = null
        for (url in spec.urls) {
            val attempt = attemptDownload(url, spec, tmp, onProgress)
            if (attempt != null) {
                lastError = attempt
                continue
            }
            if (!tmp.isVerified(spec)) {
                lastError = "${spec.label}下载不完整（${"%.1f".format(tmp.length() / 1e6)}MB < ${spec.minBytes / 1_000_000}MB）"
                runCatching { tmp.delete() }
                continue
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return@withContext "模型文件保存失败，请重试"
            onProgress(1f)
            return@withContext null
        }
        lastError ?: "未知错误"
    }

    /** 单源尝试：返回 null = 下载成功；否则失败原因（tmp 已清理）。 */
    private suspend fun attemptDownload(
        url: String,
        spec: ModelSpec,
        tmp: File,
        onProgress: (Float) -> Unit,
    ): String? {
        try {
            _state.value = DownloadState.Downloading(spec.label, 0f)
            val request = Request.Builder()
                .url(validateUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) CialloReader/1.0")
                .build()
            client.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return "HTTP ${response.code}（${url.substringAfter("//").take(28)}…）"
                val body = response.body ?: return "空响应体"
                tmp.outputStream().use { out ->
                    body.byteStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var written = 0L
                        val declared = body.contentLength()
                        require(declared <= 128L * 1024 * 1024) { "模型文件过大" }
                        while (ins.read(buf).also { read = it } != -1) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            require(written + read <= 128L * 1024 * 1024) { "模型文件过大" }
                            out.write(buf, 0, read)
                            written += read
                            val frac = if (declared > 0) written.toFloat() / declared
                            else (written.toFloat() / spec.minBytes).coerceIn(0f, 0.99f)
                            onProgress(frac)
                            _state.value = DownloadState.Downloading(spec.label, frac)
                        }
                        require(declared <= 0 || written == declared) { "模型下载不完整" }
                        out.flush()
                    }
                }
                return null
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            if (e is kotlinx.coroutines.CancellationException) throw e
            return e.message ?: e.javaClass.simpleName
        }
    }

    /** 仅允许 https 公网 CDN（Mimosa 约束：拒绝 localhost/环回/私网/保留地址）。 */
    private fun validateUrl(raw: String): String {
        val u = java.net.URI(raw)
        require(u.scheme == "https") { "仅允许 https 下载源" }
        val host = u.host?.lowercase() ?: error("URL 缺少 host")
        val notAllowed = host == "localhost" ||
            host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
            host.endsWith(".localhost") ||
            host == "0.0.0.0" ||
            host.endsWith(".internal") || host.endsWith(".local")
        require(!notAllowed) { "拒绝非公网下载源: $host" }
        val ip = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)
        if (ip != null) {
            val o = ip.destructured.toList().map { it.toInt() }
            val priv = o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) ||
                o[0] == 169 && o[1] == 254 || o[0] >= 224
            require(!priv) { "拒绝私网/保留地址下载源: $host" }
        }
        return raw
    }

    fun deleteModels(context: Context): Boolean {
        if (!mutex.tryLock()) return false
        try {
            if (OrtSessions.globalInFlight.get() > 0) return false
            val deleted = modelDir(context).listFiles().orEmpty().map { !it.exists() || it.delete() }.all { it }
            verified.clear()
            if (deleted || !isReady(context)) _state.value = DownloadState.NotDownloaded
            return deleted
        } finally { mutex.unlock() }
    }
}
