package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 漫画页位图加载器：本地文件 / 在线 URL 统一处理。
 * - 解码 → [ComicImagePipeline] 处理 → 内存 LRU 缓存（按字节计量）
 * - 并发去重（同页同参数只处理一次，异常路径同样清理）
 * - 相邻页预加载 + 尺寸探测（拆页判定需要原始内在尺寸）
 * - 独立的小图预览管线（滤镜实时预览用，不污染主缓存）
 */
class ComicPageLoader(
    private val context: Context,
    /** 在线漫画专用加载器（解密/headers 代理），null 时用全局默认 */
    private val remoteImageLoader: ImageLoader? = null,
) {
    companion object {
        /** 解码/处理长边上限 */
        const val DECODE_MAX_EDGE = 2800
        private const val CACHE_FRACTION = 6   // 处理结果缓存：堆的 1/6（与 Coil 缓存错开）
        private const val PREVIEW_CACHE_BYTES = 6 shl 20
        /** 窗口驻留扩容硬上限：min(堆 1/3, 128MB)——超出部分仍按 LRU 正常淘汰（内存安全护栏） */
        private const val WINDOW_RETAIN_CEIL_BYTES = 128 shl 20
        private const val WINDOW_RETAIN_FRACTION = 3
    }

    /** 页 id → 原始内在尺寸（未处理），Compose 可观察 */
    private val _sizes = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SizeI>>(emptyMap())
    val sizes: kotlinx.coroutines.flow.StateFlow<Map<String, SizeI>> = _sizes
    private val sizeMap = ConcurrentHashMap<String, SizeI>()

    private val cacheBaseBytes = (Runtime.getRuntime().maxMemory() / CACHE_FRACTION)
        .toInt().coerceIn(24 shl 20, 96 shl 20)

    /**
     * 窗口驻留容量硬上限（5.5 修复）：主缓存可随预载窗口字节量动态扩容到
     * min(堆 1/3, 128MB)，下限恒为基础值——扩容只增不减到基础值以下。
     */
    private val cacheRetainCeilBytes = maxOf(
        cacheBaseBytes,
        (Runtime.getRuntime().maxMemory() / WINDOW_RETAIN_FRACTION).toInt()
            .coerceAtMost(WINDOW_RETAIN_CEIL_BYTES),
    )

    private val cache = object : LruCache<String, Bitmap>(cacheBaseBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: Bitmap?,
            newValue: Bitmap?,
        ) {
            // 5.5 排查信标（debug only）：逐出事件——稳态翻页时窗口页不应出现在此日志
            if (evicted && oldValue != null && com.example.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "ComicCache",
                    "evict key=$key bytes=${oldValue.byteCount} max=${maxSize()}",
                )
            }
        }
    }

    /** 5.5 排查信标（debug only，inline lambda 使 release 零字符串开销） */
    private inline fun cacheDbg(msg: () -> String) {
        if (com.example.BuildConfig.DEBUG) android.util.Log.d("ComicCache", msg())
    }

    /** 预览结果独立小缓存（按字节） */
    private val previewCache = object : LruCache<String, Bitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 缩略图缓存（进度条/目录） */
    private val thumbCache = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /** 高倍缩放区域重解码缓存（字节计量，32MB 上限） */
    private val regionCache = object : LruCache<String, Bitmap>(32 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /**
     * BitmapRegionDecoder 复用池（按路径，最多 4 个）：decodeRegion 高频触发时
     * 免去每次重开文件+头解析；逐出/关闭时 recycle。decodeRegion 调用经
     * [regionMutex] 串行（decoder 实例不保证线程安全，且本调用本身低频空闲触发）。
     */
    private val decoderPool = object : LruCache<String, BitmapRegionDecoder>(4) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: BitmapRegionDecoder,
            newValue: BitmapRegionDecoder?,
        ) {
            runCatching { oldValue.recycle() }
        }
    }
    private val regionMutex = kotlinx.coroutines.sync.Mutex()

    /** 预取并发闸门：无界并行时 N 页全尺寸解码+管线峰值内存 = N×数十 MB */
    private val preloadGate = Semaphore(2)
    private val decodeGate = Semaphore(2)

    /** 逐像素增强管线并发闸门：内存护栏（预取+当前页并行处理会数倍放大峰值内存） */
    private val pixelOpGate = Semaphore(1)

    private val inFlight = ComicLoadLocks()
    private val terminalLoads = LruCache<String, Boolean>(256)
    private val availabilityEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    @Volatile private var foregroundKeys: Set<String> = emptySet()
    private val readingPreviews = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val _previewEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val previewEpoch: kotlinx.coroutines.flow.StateFlow<Long> = _previewEpoch
    fun peekReadingPreview(key: String): Bitmap? = readingPreviews.get(key)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 增强结果磁盘持久化缓存（第七轮第 1 条子问题 B）：
     * 重管线档（ANIME4K/WAIFU2X/SUPER_RES）的处理结果跨会话复用，
     * 重启 App 后翻回已增强页不再重跑秒级推理。仅本地/在线页 id 稳定时有效
     * （Local id = "b{bookId}_c{chapterId}"，跨会话稳定）。
     */
    private val diskCache = ComicProcessedDiskCache(context)

    /** 页处理结果历史最大字节（ref.id 键控）：预载窗口容量估算优先用实测值，
     *  尺寸未知/首载前用 [estimateProcessedBytes] 保守估计兜底 */
    private val pageBytes = ConcurrentHashMap<String, Long>()

    /* ── 阅读窗口驻留（5.5 修复，方向 a：显式优先级强引用）──
     * 纯 LRU 方案（仅扩容+探针 bump）实测存在结构性竞态：翻页动画期间 Compose
     * 对 lookahead 页（pN+1，不在预载窗口内）的 seed/put 会重排 LRU 近期性，
     * 窗口页在探针 bump 后 100ms 内被窗口外 put 逐出（ComicCache 逐出日志实证，
     * victim=刚 bump 的最新窗口页）。LRU 近期性无法表达"窗口页优先"这一显式
     * 优先级——驻留表以窗口键集为单一事实源：入窗即驻留（缓存已有直接引用、
     * 加载完成回填），离窗即解除（不 recycle，位图可能仍被组合层绘制）。
     * 预算超限按优先级（当前页 > 下一页 > 上一页，调用方按此序传入）保留。 */
    private val pinnedLock = Any()
    private val pinned = LinkedHashMap<String, Bitmap>()   // 插入序 = 驻留优先级（高→低）
    private var pinnedBytes = 0L
    private val windowKeys = LinkedHashSet<String>()       // 迭代序 = 窗口优先级（高→低）

    class PageLoadResult(val bitmap: Bitmap, val fromCache: Boolean)

    /** All renderer modes share visible-page priority, Wi-Fi policy and preload limits. */
    suspend fun loadForDisplay(
        ref: ComicPageRef, cacheKey: String, geo: ComicImagePipeline.Geometry,
        tone: ComicImagePipeline.Toning, visible: Boolean,
    ): PageLoadResult {
        if (visible) return load(ref, cacheKey, geo, tone)
        cachedOrPinned(cacheKey)?.let { return PageLoadResult(it, true) }
        if (ref is ComicPageRef.Remote && appPrefs.preloadWifiOnly && !wifiConnectedOrUnmetered()) {
            // Promotion to a visible page restarts its producer and bypasses this wait.
            kotlinx.coroutines.awaitCancellation()
        }
        return preloadGate.withPermit {
            if (ref is ComicPageRef.Remote) awaitForeground()
            load(ref, cacheKey, geo, tone)
        }
    }

    private suspend fun awaitForeground() {
        availabilityEpoch.first {
            foregroundKeys.all { key -> peekProcessed(key) != null || terminalLoads.get(key) != null }
        }
    }

    /**
     * 同步窥探处理结果缓存（第六轮族 A 根治）：组合期初值播种用。
     * 页面状态（rememberPageBitmap）从 Loading 改为"缓存命中即 Ready"——
     * 缓存是单一数据源，UI 状态第一帧就与它一致，不存在"先 Loading 再纠正"
     * 的中间态（磁吸翻页黑屏/本地书加载圈/Pager 邻页闪黑的共同根因）。
     * 窗口驻留页可能已被 LRU 逐出但仍在驻留表——同样命中。
     */
    fun peekProcessed(cacheKey: String): Bitmap? =
        cache.get(cacheKey) ?: synchronized(pinnedLock) { pinned[cacheKey] }

    /** 测试专用：直接预填处理结果缓存（验证组合期播种语义）。 */
    internal fun putProcessedForTest(cacheKey: String, bmp: Bitmap) {
        cache.put(cacheKey, bmp)
    }

    /**
     * 漫画翻译（第十五轮）：以烘焙了译文的位图替换缓存中的原位图（同 key 覆盖）。
     * 不回收旧位图——主缓存 entryRemoved 不 recycle，替换后旧引用交由 GC；
     * 避免正在 GL/Compose 绘制中的位图被回收导致 native 崩溃。
     */
    fun replaceProcessed(cacheKey: String, baked: Bitmap) {
        cache.put(cacheKey, baked)
        synchronized(pinnedLock) {
            if (windowKeys.contains(cacheKey)) {
                pinned[cacheKey]?.let { old -> pinnedBytes -= old.byteCount.toLong() }
                pinned[cacheKey] = baked
                pinnedBytes += baked.byteCount.toLong()
            }
        }
    }

    /** 漫画翻译关闭/缩放变更时：解除单键驻留并移出缓存（不回收，防在用崩溃）。 */
    fun evictProcessed(cacheKey: String) {
        cache.remove(cacheKey)
        synchronized(pinnedLock) {
            pinned.remove(cacheKey)?.let { pinnedBytes -= it.byteCount.toLong() }
            windowKeys.remove(cacheKey)
        }
    }

    /* ── EXIF 方向归一化（第六轮第 4 条现象三：偶发横向页） ──
     * BitmapFactory/BitmapRegionDecoder 不应用 EXIF orientation（Coil 默认也不开），
     * 带 90°/270° 标签的扫描页会横着显示。三处入口统一归一：
     * decodeLocal / probeLocalSize / decodeRegion。
     * JPEG APP1-Exif IFD 手写解析（免新增依赖）；PNG/WebP 无此标签不受影响。 */
    private val exifCache = ConcurrentHashMap<String, Int>()

    /** JPEG EXIF orientation（1..8；解析失败/无标签=1） */
    internal fun exifOrientationOf(path: String): Int =
        exifCache.getOrPut(path) { runCatching { parseJpegExifOrientation(path) }.getOrDefault(1) }

    private fun parseJpegExifOrientation(path: String): Int {
        java.io.File(path).inputStream().use { ins ->
            val head = ByteArray(1 shl 20)   // IFD0 通常在文件头几 KB 内
            val n = ins.read(head)
            if (n < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return 1
            var i = 2
            while (i + 4 <= n) {
                if (head[i] != 0xFF.toByte()) return 1
                val marker = head[i + 1].toInt() and 0xFF
                if (marker == 0xD8 || (marker in 0xD0..0xD7) || marker == 0x01) { i += 2; continue }
                if (marker == 0xDA || marker == 0xD9) return 1   // SOS/EOI：没遇到 Exif
                val segLen = ((head[i + 2].toInt() and 0xFF) shl 8) or (head[i + 3].toInt() and 0xFF)
                if (marker == 0xE1 && segLen >= 8 && i + 4 + 6 <= n) {
                    // APP1 "Exif\0\0"
                    if (head[i + 4] == 'E'.code.toByte() && head[i + 5] == 'x'.code.toByte() &&
                        head[i + 6] == 'i'.code.toByte() && head[i + 7] == 'f'.code.toByte() &&
                        head[i + 8] == 0.toByte() && head[i + 9] == 0.toByte()
                    ) {
                        return readOrientationFromTiff(head, i + 10, n)
                    }
                }
                i += 2 + segLen
            }
        }
        return 1
    }

    /** TIFF 头（II/MM 字节序）→ IFD0 → tag 0x0112 orientation */
    private fun readOrientationFromTiff(b: ByteArray, off: Int, limit: Int): Int {
        if (off + 8 > limit) return 1
        val little = when {
            b[off] == 'I'.code.toByte() && b[off + 1] == 'I'.code.toByte() -> true
            b[off] == 'M'.code.toByte() && b[off + 1] == 'M'.code.toByte() -> false
            else -> return 1
        }
        fun u16(p: Int) = if (little) ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)
        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u32(p: Int): Long {
            var v = 0L
            if (little) for (k in 3 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
            else for (k in 0..3) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
            return v
        }
        if (u16(off + 2) != 42) return 1
        val ifd0 = u32(off + 4).toInt()
        if (off + ifd0 + 2 > limit) return 1
        val count = u16(off + ifd0)
        var e = off + ifd0 + 2
        repeat(count) {
            if (e + 12 > limit) return 1
            if (u16(e) == 0x0112) {
                val v = u16(e + 8)
                return if (v in 1..8) v else 1
            }
            e += 12
        }
        return 1
    }

    /** orientation → 显示变换矩阵（null = 恒等）。Android 画廊同款表。 */
    internal fun exifDisplayMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        return when (orientation) {
            2 -> m.apply { setScale(-1f, 1f) }
            3 -> m.apply { setRotate(180f) }
            4 -> m.apply { setRotate(180f); postScale(-1f, 1f) }
            5 -> m.apply { setRotate(90f); postScale(-1f, 1f) }
            6 -> m.apply { setRotate(90f) }
            7 -> m.apply { setRotate(-90f); postScale(-1f, 1f) }
            8 -> m.apply { setRotate(-90f) }
            else -> null
        }
    }

    /** 旋转 90/270（5/6/7/8）时宽高互换后的尺寸 */
    internal fun exifSwappedSize(w: Int, h: Int, orientation: Int): SizeI =
        if (orientation in 5..8) SizeI(h, w) else SizeI(w, h)

    /** 取消后台预取作业（阅读器退出时调用） */
    fun shutdown() {
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
        scope.cancel()
        readingPreviews.evictAll()
        synchronized(pinnedLock) {
            pinned.clear()
            pinnedBytes = 0L
            windowKeys.clear()
        }
        // 释放 decoder 池持有的 native 文件句柄（最多 4 个 BitmapRegionDecoder）；
        // 正被 decodeRegion 持锁时跳过（clearProcessedCache 路径会兜底回收）
        if (regionMutex.tryLock()) {
            try {
                decoderPool.evictAll()
            } finally {
                regionMutex.unlock()
            }
        }
    }

    /**
     * 加载并处理一页。cacheKey 相同的请求共享一次处理。
     * 逐像素增强（Anime4K/CAS 等重管线）经并发闸门串行化，防并行峰值内存翻倍。
     */
    suspend fun load(
        ref: ComicPageRef,
        cacheKey: String,
        geo: ComicImagePipeline.Geometry,
        tone: ComicImagePipeline.Toning,
    ): PageLoadResult = withContext(Dispatchers.Default) {
        cachedOrPinned(cacheKey)?.let { return@withContext PageLoadResult(it, true) }
        terminalLoads.remove(cacheKey)
        val mutex = inFlight.acquire(cacheKey)
        var finished = false
        try {
            // 第七轮第 1 条子问题 A：处理失败的原图不允许进入任何缓存层——
            // 旧版虽不写 LRU，但仍被 pinIfWindowed 以"增强后的 key"钉入驻留表，
            // cachedOrPinned 命中驻留还会回填 LRU——一旦某档处理失败（大页 OOM 等），
            // 原图冒充该档结果被永久缓存，之后"关了再开/切任何档位"都命中同一张原图，
            // 表现为"无论怎么切换档位画面都没有区别"。失败结果只保显示、不驻留。
            var failedFallback = false
            val bitmap = mutex.withLock {
                cachedOrPinned(cacheKey) ?: run {
                    // 磁盘持久化命中（仅重管线档）：免解码+免推理直接用，
                    // 并回填内存 LRU（下次内存路径直达）
                    if (ComicProcessedDiskCache.eligible(tone.enhanceMode)) {
                        withContext(Dispatchers.IO) { diskCache.read(cacheKey) }?.let { fromDisk ->
                            if (!sizeMap.containsKey(ref.id)) {
                                putSize(ref.id, SizeI(fromDisk.width, fromDisk.height))
                            }
                            cache.put(cacheKey, fromDisk)
                            return@run fromDisk
                        }
                    }
                    val raw = decodeRaw(ref, onPreview = { partial ->
                        val preview = ComicImagePipeline.process(partial, geo, ComicImagePipeline.Toning())
                        readingPreviews.put(cacheKey, preview)
                        _previewEpoch.update { it + 1 }
                    }) ?: throw IllegalStateException("图片解码失败: ${ref.id}")
                    // 记录原始内在尺寸（区域重解码坐标换算与拆页判定基准）：
                    // 本地页用边界解码取原始文件尺寸（decodeRaw 已 capEdge，非原始值）
                    if (!sizeMap.containsKey(ref.id)) {
                        if (ref is ComicPageRef.Local) {
                            runCatching {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(ref.path, opts) }
                                if (opts.outWidth > 0 && opts.outHeight > 0) {
                                    putSize(ref.id, exifSwappedSize(opts.outWidth, opts.outHeight, exifOrientationOf(ref.path)))
                                }
                            }
                        } else {
                            putSize(ref.id, SizeI(raw.width, raw.height))
                        }
                    }
                    // 本地宽页：随加载顺带做中央装订缝探测（复用已解码位图，零额外解码）。
                    // 第 19 条：保留缝的精确位置（拆分落点用），不止是布尔结论
                    if (ref is ComicPageRef.Local && sizeMap[ref.id]?.gutter == null) {
                        val a = raw.width.toFloat() / raw.height
                        if (a >= ComicPageLayout.WIDE_ASPECT && a < ComicPageLayout.HARD_WIDE_ASPECT) {
                            val diag = runCatching {
                                ComicImagePipeline.detectCenterGutterDetail(raw)
                            }.getOrNull()
                            if (diag != null) {
                                val base = sizeMap[ref.id] ?: SizeI(raw.width, raw.height)
                                updateSize(
                                    ref.id,
                                    base.copy(gutter = diag.isGutter, gutterPos = if (diag.isGutter) diag.position else null),
                                )
                            }
                        }
                    }
                    // 管线异常语义：处理失败时返回原图保显示，但不写入缓存——
                    // 旧版 getOrDefault(raw) 后照样 put，未滤镜图会以带指纹的 key
                    // 永久命中缓存，滤镜静默失效且无重试路径。
                    // 第六轮族 A 根治：管线无任务（默认配置）时解码位图就是该
                    // cacheKey 的确定性最终结果——必须缓存。旧版把"无任务"与
                    // "处理失败"混入同一分支，默认配置下页面永远不进缓存，
                    // 每次翻页/重组都重新解码（磁吸黑屏与本地书加载圈的总病根）。
                    val didWork = ComicImagePipeline.hasWork(geo, tone)
                    // One download. Publish a correctly cropped/rotated readable preview before
                    // waiting for expensive enhancement. It never enters the final-result cache.
                    if (ref is ComicPageRef.Remote && ComicImagePipeline.toningHasWork(tone)) {
                        try {
                            val preview = ComicImagePipeline.process(capEdge(raw, 960), geo, ComicImagePipeline.Toning())
                            readingPreviews.put(cacheKey, preview)
                            _previewEpoch.update { it + 1 }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) { /* Preview failure does not block the full page. */ }
                    }
                    currentCoroutineContext().ensureActive()
                    var processed: Bitmap? = null
                    if (didWork) {
                        // 闸门覆盖所有重活：增强内核 + 锐化 + LUT/色矩阵类调色
                        // （LUT/色矩阵在 2800px 满尺寸也是 ~31MB×2 的瞬时分配，
                        // 不该只有增强内核受 Semaphore 约束）。
                        // 直接复用 Toning.hasWork()——手抄字段集会在 Toning
                        // 新增调色字段时漂移成"有调色不进闸门"回归
                        val heavy = ComicImagePipeline.toningHasWork(tone)
                        processed = if (heavy) pixelOpGate.withPermit {
                            runCatching { ComicImagePipeline.process(raw, geo, tone) }.getOrNull()
                        } else {
                            runCatching { ComicImagePipeline.process(raw, geo, tone) }.getOrNull()
                        }
                    }
                    if (processed != null) {
                        cache.put(cacheKey, processed)
                        // 重管线档结果异步落盘（无损编码，IO 线程 + 写闸串行；
                        // 失败静默——磁盘缓存只是加速器）
                        if (ComicProcessedDiskCache.eligible(tone.enhanceMode)) {
                            val toStore = processed
                            scope.launch(Dispatchers.IO) {
                                runCatching { diskCache.write(cacheKey, toStore) }
                            }
                        }
                        processed
                    } else if (!didWork) {
                        // 无管线任务：解码结果即最终结果，确定性缓存
                        cache.put(cacheKey, raw)
                        raw
                    } else {
                        // 处理失败（真异常）：显示原图、不缓存、不驻留（下次进入自动重试管线）
                        failedFallback = true
                        raw
                    }
                }
            }
            recordPageBytes(ref, bitmap)
            cacheDbg { "put key=$cacheKey bytes=${bitmap.byteCount} max=${cache.maxSize()}" }
            if (!failedFallback) pinIfWindowed(cacheKey, bitmap)
            finished = true
            PageLoadResult(bitmap, false)
        } finally {
            if (finished || currentCoroutineContext().isActive) terminalLoads.put(cacheKey, finished)
            availabilityEpoch.update { it + 1 }
            // Keep the lock registered while other callers are still waiting on it.
            inFlight.release(cacheKey)
        }
    }

    /** LRU + 驻留表联合读取（驻留命中时回填 LRU，恢复常规近期性） */
    private fun cachedOrPinned(cacheKey: String): Bitmap? {
        cache.get(cacheKey)?.let { return it }
        val pinnedHit = synchronized(pinnedLock) { pinned[cacheKey] } ?: return null
        cache.put(cacheKey, pinnedHit)
        return pinnedHit
    }

    /** 记录页处理结果实际字节（取历史最大，覆盖半页/整页变体） */
    private fun recordPageBytes(ref: ComicPageRef, bitmap: Bitmap) {
        pageBytes.merge(ref.id, bitmap.byteCount.toLong()) { a, b -> maxOf(a, b) }
    }

    /** 加载完成的页若属于当前预载窗口，按窗口优先级序补入驻留表
     *  （Agent C 补审 F3 修正：完成顺序 ≠ 优先级序——低优先级页先完成不能
     *  挤占预算导致当前页被拒；统一按 windowKeys 迭代序重建，超限丢尾部） */
    private fun pinIfWindowed(cacheKey: String, bitmap: Bitmap) {
        synchronized(pinnedLock) {
            if (!windowKeys.contains(cacheKey)) return
            pinned[cacheKey] = bitmap
            rebuildPinnedInPriorityOrder()
        }
    }

    /** 按 windowKeys 优先级序重建驻留表，预算超限从尾部（最低优先级）丢弃 */
    private fun rebuildPinnedInPriorityOrder() {
        var budget = cacheRetainCeilBytes.toLong()
        val rebuilt = LinkedHashMap<String, Bitmap>()
        for (k in windowKeys) {
            val b = pinned[k] ?: continue
            if (b.byteCount.toLong() <= budget) {
                rebuilt[k] = b
                budget -= b.byteCount
            }
        }
        pinnedBytes = cacheRetainCeilBytes.toLong() - budget
        pinned.clear()
        pinned.putAll(rebuilt)
    }

    /**
     * 预览管线：小图（≤540px）+ 独立缓存，用于设置面板实时预览，
     * 不重处理全尺寸页、不挤占主缓存。
     */
    suspend fun loadPreview(
        ref: ComicPageRef,
        geo: ComicImagePipeline.Geometry,
        tone: ComicImagePipeline.Toning,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val key = "pv|${ref.id}|${geo.half}|${geo.splitPosition}|${geo.rotationDeg}|${geo.cropMode}|${geo.manualCrop}" +
            "|${tone.brightness},${tone.contrast},${tone.saturation},${tone.hue},${tone.gamma}," +
            "${tone.sharpen},${tone.shadow},${tone.bw},${tone.enhanceMode},${tone.enhanceStrength}"
        previewCache.get(key)?.let { return@withContext it }
        val small = decodeRaw(ref, maxEdge = 540) ?: return@withContext null
        val out = runCatching { ComicImagePipeline.process(small, geo, tone) }.getOrDefault(small)
        previewCache.put(key, out)
        out
    }

    /** 进度缩略图 */
    suspend fun loadThumb(ref: ComicPageRef, edge: Int = 220): Bitmap? = withContext(Dispatchers.IO) {
        val key = "thumb|${ref.id}|$edge"
        thumbCache.get(key)?.let { return@withContext it }
        val bmp = decodeRaw(ref, maxEdge = edge) ?: return@withContext null
        thumbCache.put(key, bmp)
        bmp
    }

    /**
     * 高倍缩放区域重解码（简化 subsampling，思想来自 zoomimage/SSIV tile 引擎）：
     * 当显示比例超过已解码分辨率时，对当前可视区域按原始像素重新解码，
     * 深放大下细节不再受 2800px 全页解码上限约束。仅本地文件（在线图无区域解码能力）。
     *
     * @param rect 可视区域（**EXIF 归一化后**的显示坐标系像素）
     * @param targetEdge 期望输出长边（~解码上限）
     * @return 重解码位图（内容严格对应 rect 区域；null = 不支持/失败）
     */
    suspend fun decodeRegion(
        ref: ComicPageRef,
        rect: RectF,
        targetEdge: Int = DECODE_MAX_EDGE,
        cacheTag: String = "",
    ): Bitmap? {
        if (ref !is ComicPageRef.Local) return null
        if (rect.width() < 8f || rect.height() < 8f) return null
        val key = "rgn|${ref.id}|$cacheTag|${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}"
        regionCache.get(key)?.let { return it }
        return regionMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val decoder = decoderPool.get(ref.path) ?: run {
                        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BitmapRegionDecoder.newInstance(ref.path)
                        } else {
                            @Suppress("DEPRECATION")
                            BitmapRegionDecoder.newInstance(ref.path, false)
                        } ?: return@withContext null
                        decoderPool.put(ref.path, d)
                        d
                    }
                    val iw = decoder.width
                    val ih = decoder.height
                    if (iw <= 0 || ih <= 0) return@withContext null
                    // rect 在 EXIF 归一化坐标系：先映射回文件原始坐标再解码
                    val o = exifOrientationOf(ref.path)
                    val rawRect = exifRectToRaw(rect, iw, ih, o)
                    // 区域夹取到图像范围内
                    val r = Rect(
                        rawRect.left.toInt().coerceIn(0, iw - 1),
                        rawRect.top.toInt().coerceIn(0, ih - 1),
                        rawRect.right.toInt().coerceIn(1, iw),
                        rawRect.bottom.toInt().coerceIn(1, ih),
                    )
                    // 采样率：区域长边解码后不超过 targetEdge
                    val longEdge = maxOf(r.width(), r.height())
                    var sample = 1
                    while (longEdge / sample > targetEdge) sample *= 2
                    val dopts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val raw = decoder.decodeRegion(r, dopts) ?: return@withContext null
                    // 输出应用同一 EXIF 显示变换，与底层全页位图同向
                    val bmp = exifDisplayMatrix(o)?.let { m ->
                        runCatching { Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true) }.getOrDefault(raw)
                    } ?: raw
                    regionCache.put(key, bmp)
                    bmp
                }.getOrNull()
            }
        }
    }

    /** 显示坐标 rect → 文件原始坐标（逆 EXIF 变换，corner 映射精确）。
     *  各方向由前向映射 (x_d,y_d)=f(x_r,y_r) 求逆推得：
     *  2=水平镜像；3=180°；4=垂直镜像；5=转置；6=顺时针90°；7=反转置；8=逆时针90° */
    internal fun exifRectToRaw(rect: RectF, rawW: Int, rawH: Int, orientation: Int): RectF = when (orientation) {
        2 -> RectF(rawW - rect.right, rect.top, rawW - rect.left, rect.bottom)
        3 -> RectF(rawW - rect.right, rawH - rect.bottom, rawW - rect.left, rawH - rect.top)
        4 -> RectF(rect.left, rawH - rect.bottom, rect.right, rawH - rect.top)
        5 -> RectF(rect.top, rect.left, rect.bottom, rect.right)
        6 -> RectF(rect.top, rawH - rect.right, rect.bottom, rawH - rect.left)
        7 -> RectF(rawW - rect.bottom, rawH - rect.right, rawW - rect.top, rawH - rect.left)
        8 -> RectF(rawW - rect.bottom, rect.left, rawW - rect.top, rect.right)
        else -> rect
    }

    /** 预取原始尺寸（本地文件仅解码边界，非常廉价）；远程跳过 */
    fun probeSize(ref: ComicPageRef) {
        if (sizeMap.containsKey(ref.id)) return
        when (ref) {
            is ComicPageRef.Local -> scope.launch { probeLocalSize(ref) }
            else -> Unit
        }
    }

    /** 批量顺序预取（单协程逐个进行，避免瞬时抢占 CPU） */
    fun probeSizes(refs: List<ComicPageRef>) {
        scope.launch {
            refs.forEach { ref ->
                if (ref is ComicPageRef.Local && !sizeMap.containsKey(ref.id)) {
                    probeLocalSize(ref)
                }
            }
        }
    }

    private suspend fun probeLocalSize(ref: ComicPageRef.Local) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(ref.path, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                // EXIF 旋转后的显示尺寸（与 decodeLocal 的归一化输出一致）
                putSize(ref.id, exifSwappedSize(opts.outWidth, opts.outHeight, exifOrientationOf(ref.path)))
                // 中央装订缝探测已移至 load() 路径（复用已解码位图、覆盖全部页，
                // 包括 probeSizes 的 take(600) 之后的页）
            }
        }
    }

    private fun updateSize(id: String, size: SizeI?) {
        if (size == null) return
        sizeMap[id] = size
        _sizes.update { it + (id to size) }
    }

    /** 进行中的预取任务（按 cacheKey）：新批次到达时取消不在批次内的旧任务，
     *  连续快速翻页不堆积解码（闸门等待中的任务被取消即放弃；已在解码中的
     *  任务会在完成时写缓存——结果按键隔离，不会串页） */
    private val preloadJobs = ConcurrentHashMap<String, Job>()

    /** 预载窗口条目：一个显示槽位的完整加载参数（整窗一次提交） */
    class WindowEntry(
        val ref: ComicPageRef,
        val cacheKey: String,
        val geo: ComicImagePipeline.Geometry,
        val visible: Boolean = false,
    )

    /**
     * 「仅 Wi-Fi 预加载」开关（在线阅读省流量）：开启后非 Wi-Fi 网络下不预取
     * 在线页（[ComicPageRef.Remote]）；本地页不受影响，始终预取。
     * 当前页本身永远会加载 —— 这里只限制"提前下载还没看到的页"。
     */
    private val appPrefs by lazy { com.example.data.PreferencesManager(context) }

    private fun wifiConnectedOrUnmetered(): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return true
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * 批量预加载（整窗一次调用，5.5 修复）。旧 API 按单页逐次调用时，每次调用的
     * "批次外取消"会立即取消同窗口其它槽位刚入队的任务——双页模式只有下一跨的
     * 最后一个槽位真正预载、后退翻页几乎从不预载（"本地书偶现加载圈"根因之一）。
     * 现在整窗一次提交，按序原子完成：
     * 1) [ensureWindowCapacity] 主 LRU 扩容到能容纳整个窗口；
     * 2) 驻留表同步（窗口键集更新：入窗驻留、离窗解除，预算内按传入优先级保留——
     *    调用方必须按 当前页 → 下一页 → 上一页 的顺序传入 entries）；
     * 3) 未命中页全部入队（并发闸门内解码+处理）；
     * 4) 批次外的旧预取任务取消（快速翻页节流）。
     * 窗口页由驻留表强引用保活（LRU 逐出免疫），翻到时播种必命中——不依赖
     * 任何"事后校正"。
     */
    fun preloadWindow(entries: List<WindowEntry>, tone: ComicImagePipeline.Toning) {
        // 仅 Wi-Fi 预加载：非 Wi-Fi 且是计费网络时，跳过在线页的提前下载
        val effective = if (appPrefs.preloadWifiOnly && !wifiConnectedOrUnmetered()) {
            entries.filter { it.visible || it.ref !is ComicPageRef.Remote }
        } else entries
        val newForeground = effective.filter { it.visible }.mapTo(HashSet()) { it.cacheKey }
        if (newForeground != foregroundKeys) {
            newForeground.forEach { terminalLoads.remove(it) }
            if (newForeground.any { peekProcessed(it) == null }) preloadJobs.forEach { (key, job) ->
                // Preserve an in-flight request that has become the foreground page.
                // Other speculative transfers yield bandwidth when the reader changes page.
                if (key !in newForeground) { job.cancel(); preloadJobs.remove(key, job) }
            }
        }
        foregroundKeys = newForeground
        availabilityEpoch.update { it + 1 }
        ensureWindowCapacity(effective, tone)
        cacheDbg { "window keys=${effective.joinToString { it.cacheKey }}" }
        synchronized(pinnedLock) {
            windowKeys.clear()
            effective.forEach { windowKeys.add(it.cacheKey) }
            // 离窗解除：仅保留仍属窗口的旧驻留项，随后按优先级序重建（超限丢尾部）
            val oldPinned = HashMap(pinned)
            pinned.clear()
            effective.forEach { e ->
                (cache.get(e.cacheKey) ?: oldPinned[e.cacheKey])?.let { pinned[e.cacheKey] = it }
            }
            rebuildPinnedInPriorityOrder()
        }
        val batchKeys = HashSet<String>(effective.size)
        effective.forEach { e ->
            batchKeys.add(e.cacheKey)
            val probeHit = cachedOrPinned(e.cacheKey)
            cacheDbg { "probe key=${e.ref.id} hit=${probeHit != null}" }
            // Visible pages have their own foreground producer and never wait for this gate.
            if (probeHit == null && !e.visible && !preloadJobs.containsKey(e.cacheKey)) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        // 并发闸门：最多 2 页同时解码+处理（无界并行峰值内存 = N×数十 MB）
                        preloadGate.withPermit {
                            if (e.ref is ComicPageRef.Remote) awaitForeground()
                            runCatching { load(e.ref, e.cacheKey, e.geo, tone) }
                        }
                    } finally {
                        // 条件移除：仅当映射仍是自己（被新任务顶替时不误删）
                        preloadJobs.remove(e.cacheKey, coroutineContext[Job])
                    }
                }
                if (preloadJobs.putIfAbsent(e.cacheKey, job) == null) job.start() else job.cancel()
            }
        }
        // 取消已不在当前窗口的等待中任务（快速翻页旧页预取）
        preloadJobs.forEach { (k, job) ->
            if (k !in batchKeys) {
                job.cancel()
                preloadJobs.remove(k, job)
            }
        }
    }

    /**
     * 窗口容量保障（5.5 修复方向 c）：主 LRU 上限 = 堆 1/6（24-96MB），而单页处理
     * 结果最高 ~29MB（≤2800 解码 + 增强低分辨率源 2x 至 3200 上限），预载窗口
     * （当前±1 spread，双页最多 6 槽）字节量轻松超过上限——窗口页在翻到之前被
     * 逐出，翻页时重新解码+重跑增强管线 = "偶现加载圈"（5.5 用户回归反馈主因）。
     * 估算优先用实测字节（[pageBytes]），未知页用内禀尺寸+增强档保守估计；
     * 目标值钳在 [基础上限, 窗口驻留硬上限]——超限部分照常 LRU 淘汰，内存安全。
     */
    internal fun ensureWindowCapacity(entries: List<WindowEntry>, tone: ComicImagePipeline.Toning) {
        var needed = 0L
        entries.forEach { e ->
            needed += pageBytes[e.ref.id] ?: run {
                val s = sizeMap[e.ref.id]
                estimateProcessedBytes(s?.width ?: 0, s?.height ?: 0, tone.enhanceMode)
            }
        }
        val target = needed.coerceAtLeast(cacheBaseBytes.toLong())
            .coerceAtMost(cacheRetainCeilBytes.toLong()).toInt()
        if (target != cache.maxSize()) {
            cacheDbg { "resize needed=$needed target=$target from=${cache.maxSize()}" }
            cache.resize(target)
        }
    }

    /**
     * 页处理结果字节上界估计（纯函数）：内禀尺寸 → 解码等比钳 2800 →
     * 低/中分辨率源增强档 2x（ANIME4K/WAIFU2X <2400、SUPER_RES ≤1800，
     * 与管线各档实现一致）→ 长边上限 [ComicImagePipeline.MAX_EDGE]。
     * 尺寸未知按档位保守缺省（非 2x 档 2800×2000、2x 档 3200×2286）。
     */
    internal fun estimateProcessedBytes(w: Int, h: Int, enhanceMode: ComicEnhanceMode): Long {
        if (w <= 0 || h <= 0) {
            return if (enhanceMode == ComicEnhanceMode.OFF || enhanceMode == ComicEnhanceMode.CAS) {
                2800L * 2000L * 4L
            } else {
                3200L * 2286L * 4L
            }
        }
        var long = maxOf(w, h).toLong()
        var short = minOf(w, h).toLong()
        if (long > DECODE_MAX_EDGE) {
            short = short * DECODE_MAX_EDGE / long
            long = DECODE_MAX_EDGE.toLong()
        }
        val x2 = when (enhanceMode) {
            ComicEnhanceMode.ANIME4K, ComicEnhanceMode.WAIFU2X -> long < 2400
            ComicEnhanceMode.SUPER_RES -> long <= 1800
            else -> false
        }
        if (x2 && long < ComicImagePipeline.MAX_EDGE) {
            val nl = minOf(long * 2, ComicImagePipeline.MAX_EDGE.toLong())
            short = short * nl / long
            long = nl
        }
        return long * short * 4L
    }

    /** 测试专用：当前主缓存上限（窗口驻留扩容断言用） */
    internal fun cacheLimitForTest(): Int = cache.maxSize()

    /** 测试专用：存活预载任务键集（整窗入队/批次外取消语义断言用） */
    internal fun activePreloadKeysForTest(): Set<String> = preloadJobs.keys.toSet()

    /** 测试专用：驻留表键序（优先级序契约断言用：当前→下一→上一/前瞻近→远） */
    internal fun pinnedOrderForTest(): List<String> = synchronized(pinnedLock) { pinned.keys.toList() }

    private fun putSize(id: String, size: SizeI) {
        if (sizeMap.putIfAbsent(id, size) == null) {
            _sizes.update { it + (id to size) }
        }
    }

    private suspend fun decodeRaw(ref: ComicPageRef, maxEdge: Int = DECODE_MAX_EDGE, onPreview: ((Bitmap) -> Unit)? = null): Bitmap? =
        withContext(Dispatchers.IO) { decodeGate.withPermit {
            suspend fun decode(edge: Int) = when (ref) {
                is ComicPageRef.Local -> decodeLocal(ref.path, edge)
                is ComicPageRef.Remote -> decodeRemote(ref, edge, onPreview)
            }
            val decoded = try { decode(maxEdge) } catch (oom: OutOfMemoryError) {
                // Large scans must produce a readable page or a retry state, not kill
                // the reader. Preserve referenced bitmaps; only release cache ownership.
                clearProcessedCache()
                (remoteImageLoader ?: coil.Coil.imageLoader(context)).memoryCache?.clear()
                try { decode(minOf(maxEdge, 1280)) }
                catch (retry: OutOfMemoryError) { throw IllegalStateException("页面过大，内存不足，请重试", retry) }
            }
            decoded?.let { capEdge(it, maxEdge) }
        } }

    private fun capEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxEdge) return src
        val scale = maxEdge.toFloat() / long
        // 不 recycle：src 可能是 Coil 内存缓存中的共享实例
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun decodeLocal(path: String, maxEdge: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val sample = sampleSizeFor(opts.outWidth, opts.outHeight, maxEdge)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, decodeOpts) ?: return null
        // EXIF 方向归一化：解码器不应用 orientation，此处统一转正
        // （第 4 条现象三：带 90°/270° 标签的页偶发横向显示的根因）
        val o = exifOrientationOf(path)
        val m = exifDisplayMatrix(o) ?: return decoded
        return runCatching { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) }
            .getOrDefault(decoded)
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun decodeRemote(ref: ComicPageRef.Remote, maxEdge: Int, onPreview: ((Bitmap) -> Unit)?): Bitmap? {
        val loader = remoteImageLoader ?: coil.Coil.imageLoader(context)
        val streamPreview = onPreview?.let { ComicStreamPreview(CoroutineScope(currentCoroutineContext()), it) }
        val builder = ImageRequest.Builder(context)
            .data(ref.url)
            .tag(ComicStreamPreview::class.java, streamPreview)
            .memoryCacheKey(comicRemoteCacheKey(ref.url, ref.headers, ref.referer))
            .diskCacheKey(comicRemoteCacheKey(ref.url, ref.headers, ref.referer))
            .size(maxEdge)
            // Bound the LONG edge at decode time. FILL can decode huge portrait/webtoon
            // images before capEdge shrinks them; EXACT also needlessly enlarges small pages.
            .scale(coil.size.Scale.FIT)
            .precision(coil.size.Precision.INEXACT)
            // 漫画管线全链路在软件画布上作画（CURL 纹理合成、增强后处理、
            // 区域裁剪）。Coil 默认在 API 26+ 产出 HARDWARE 位图，喂给
            // Canvas.drawBitmap 会抛 "Software rendering doesn't support
            // hardware bitmaps"（远程页加载完成→重铺 CURL 纹理时崩溃）。
            // 本管线解码后立即派生副本，禁用硬件位图无性能损失。
            .allowHardware(false)
            // EXIF 方向归一化由构建器侧的 bitmapFactoryExifOrientationPolicy
            // 提供（Coil 2.7 API，见 buildComicImageLoader），与本地解码对齐
        ref.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (!ref.referer.isNullOrBlank()) builder.addHeader("Referer", ref.referer)
        val result = try { loader.execute(builder.build()) } finally { streamPreview?.close() }
        if (result is coil.request.ErrorResult) {
            // A truncated/invalid disk entry must not make the retry button fail forever.
            loader.diskCache?.remove(comicRemoteCacheKey(ref.url, ref.headers, ref.referer))
            throw result.throwable
        }
        val drawable = result.drawable ?: return null
        return runCatching {
            (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: drawable.toBitmap()
        }.getOrNull()
    }

    /** Bound allocation before decoding; shrinking afterwards still allocates the large scan. */
    private fun sampleSizeFor(w: Int, h: Int, maxEdge: Int): Int {
        var sample = 1
        var long = maxOf(w, h)
        while (long / sample > maxEdge) sample *= 2
        return sample
    }

    fun clearProcessedCache() {
        cache.evictAll()
        // 驻留键含管线指纹：配置变更后旧键全失效，一并清空（防陈旧位图继续显示）
        synchronized(pinnedLock) {
            pinned.clear()
            pinnedBytes = 0L
            windowKeys.clear()
        }
        previewCache.evictAll()
        readingPreviews.evictAll()
        // regionCache 条目虽含指纹 key，但陈旧 tile 仍占 32MB LRU 空间——一并清空
        regionCache.evictAll()
        // decoder 池同样回收（evictAll 触发 entryRemoved → recycle）：必须先拿
        // regionMutex，否则与进行中的 decodeRegion 并发 recycle 正在使用的 decoder
        // （native use-after-free 崩溃窗口）；拿不到锁就跳过（shutdown 路径兜底）
        if (regionMutex.tryLock()) {
            try {
                decoderPool.evictAll()
            } finally {
                regionMutex.unlock()
            }
        }
    }
}
