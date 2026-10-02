package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReadingSession
import com.example.library.ImageBytes
import com.example.library.MhttuImageDecryptor
import com.example.source.js.JsCookieJar
import com.example.source.js.JsImageProcessor
import com.example.ui.components.AppLiquidButton
import com.example.ui.components.ChasingDots
import com.example.ui.comic.ComicPageRef
import com.example.ui.comic.ComicReaderCore
import com.example.ui.comic.ComicTocEntry
import com.example.ui.comic.comicRemoteCacheKey
import com.example.ui.comic.ComicTransfers
import com.example.ui.comic.ComicStreamPreview
import coil.ImageLoader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 在线漫画阅读页（升级版）：
 * 由统一阅读引擎 [ComicReaderCore] 驱动，保留原有解密/代理/重试图片加载管线；
 * 新增：章节目录、上一章/下一章连续阅读、每漫画独立配置（bookKey）。
 */
@Composable
fun OnlineComicReaderScreen(
    title: String,
    imageUrls: List<String>,
    loading: Boolean,
    error: String?,
    referer: String? = null,
    imageHeaders: Map<String, Map<String, String>> = emptyMap(),
    resolveImage: (suspend (String) -> String?)? = null,
    resolveImageHeaders: (suspend (String) -> Map<String, String>)? = null,
    onRecordTime: (Long) -> Unit = {},
    onSessionEnd: (ReadingSession) -> Unit = {},
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /* ── 升级新增（可选，保持旧调用兼容） ── */
    bookKey: String? = null,
    bookTitle: String? = null,
    chapters: List<ComicTocEntry> = emptyList(),
    currentChapterIndex: Int = -1,
    onJumpToChapter: ((Int) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    /**
     * 翻页回调（章节内页序号 0 起）：在线阅读同样记录阅读进度 ——
     * 未收藏、未下载的漫画也照常置灰已读章节。调用方做防抖。
     */
    onPageChanged: (pageIndex: Int, totalPages: Int) -> Unit = { _, _ -> },
    /** 起始页：「继续阅读 · 第12话 · 第8页」精确回到上次位置 */
    initialPage: Int = 0,
    /** 神回上下文（null = 该场景不启用神回；由 MainActivity 用 sourceId::comicId 组装） */
    godContext: com.example.god.GodMomentContext? = null,
    onGodMomentSaved: ((com.example.god.GodMomentEntity) -> Unit)? = null,
) {
    // 在线阅读计时：只在 App 前台 + 屏幕亮着时累计
    ReadingTimerEffect(
        bookId = null,
        bookTitle = bookTitle ?: title,
        onFlush = { seconds -> onRecordTime(seconds) },
        onSessionEnd = { session -> onSessionEnd(session) }
    )

    val context = LocalContext.current
    val currentResolveImage = rememberUpdatedState(resolveImage)
    val currentResolveImageHeaders = rememberUpdatedState(resolveImageHeaders)

    // 漫画阅读专用加载器：进程级单例（避免反复进出阅读器叠加 Coil 缓存与线程池）
    val imageLoader = remember {
        synchronized(ComicLoaderLock) {
            sharedComicLoader ?: buildComicImageLoader(context).also { sharedComicLoader = it }
        }
    }

    // 离开阅读器时释放回调引用（拦截器侧已 ?. 判空，飞行中的请求不受影响）
    DisposableEffect(Unit) {
        sharedResolveImage = currentResolveImage
        sharedResolveImageHeaders = currentResolveImageHeaders
        onDispose {
            if (sharedResolveImage === currentResolveImage) sharedResolveImage = null
            if (sharedResolveImageHeaders === currentResolveImageHeaders) sharedResolveImageHeaders = null
        }
    }

    // The visible page's GET warms the shared connection itself. A separate HEAD
    // would duplicate source resolution and compete with it on slow origins.

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading && imageUrls.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ChasingDots(
                            size = 52.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在加载图片…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            error != null && imageUrls.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error,
                            color = Color(0xFFFF8A8A),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        AppLiquidButton(text = "重试", onClick = onRetry)
                    }
                }
            }

            imageUrls.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有可显示的图片", color = Color.White)
                }
            }

            else -> {
                // 书源的图片头可能晚于 URL 到达；头或 Referer 更新时同步刷新页面请求。
                val pages = remember(imageUrls, imageHeaders, referer) {
                    imageUrls.mapIndexed { i, url ->
                        ComicPageRef.Remote(
                            id = "u_${comicRemoteCacheKey(url, imageHeaders[url].orEmpty(), referer)}_$i",
                            url = url,
                            headers = imageHeaders[url].orEmpty(),
                            referer = referer
                        )
                    }
                }
                // 神回：当前读到的页（封面默认选中它）+ 页面引用（页面选择器用）
                var currentPageIndex by remember(imageUrls) { mutableIntStateOf(initialPage.coerceAtLeast(0)) }
                val godPages = remember(pages) {
                    pages.map { ref ->
                        com.example.god.GodPageRef(
                            id = ref.id,
                            source = ref.url,
                            remote = true,
                            // 阅读器请求带 Referer；神回封面必须复用同一组头，否则防盗链
                            // 图床会把缩略图请求拒绝，表现为十几张封面一直排队/加载。
                            headers = com.example.god.godPageHeaders(ref.headers, ref.referer),
                        )
                    }
                }
                val godBinding = com.example.god.rememberGodMomentBinding(
                    ctx = godContext?.copy(remoteLoader = imageLoader),
                    pages = godPages,
                    currentPage = { currentPageIndex },
                )
                Box(Modifier.fillMaxSize()) {
                    ComicReaderCore(
                        pages = pages,
                        title = bookTitle ?: title,
                        chapterTitle = title,
                        bookKey = bookKey,
                        initialPage = initialPage,
                        toc = chapters,
                        currentChapterIndex = currentChapterIndex,
                        onJumpToChapter = onJumpToChapter,
                        onPrevChapter = onPrevChapter,
                        onNextChapter = onNextChapter,
                        chapterNavLabel = "章",
                        remoteImageLoader = imageLoader,
                        onExit = onBack,
                        onPageChanged = { raw, _ ->
                            currentPageIndex = raw.coerceAtLeast(0)
                            onPageChanged(currentPageIndex, pages.size)
                        },
                        onGodMoment = godBinding.onOpenCurrent,
                    )
                }
                com.example.god.GodMomentHost(
                    binding = godBinding,
                    ctx = godContext?.copy(remoteLoader = imageLoader),
                    onSaved = { entity -> onGodMomentSaved?.invoke(entity) },
                )
            }
        }
    }
}

/* ── 进程级共享加载器（拦截器通过 State 引用读取最新解密回调） ── */

private val ComicLoaderLock = Any()
private var sharedComicLoader: ImageLoader? = null
private var sharedComicAppContext: android.content.Context? = null
@Volatile private var sharedResolveImage: State<(suspend (String) -> String?)?>? = null
@Volatile private var sharedResolveImageHeaders: State<(suspend (String) -> Map<String, String>)?>? = null

/**
 * 漫画专用图片加载器（进程级单例）。
 *
 * 非阅读器场景（神回封面选择等）也必须用它：这里带 UA/Cookie 注入、3 次重试、
 * 图片字节归一化、AVIF 回退、DNS failover 代理和 512MB 磁盘缓存；落到全局默认
 * Coil loader 时这些全没有 —— 神回缩略图走默认 loader 的表现就是"很多图片
 * 加载不出来 / 特别慢"（用户实测）。
 */
fun comicImageLoader(context: android.content.Context): ImageLoader =
    synchronized(ComicLoaderLock) {
        sharedComicLoader
            ?: buildComicImageLoader(context.applicationContext).also { sharedComicLoader = it }
    }

/** 下一章预取：页图直接入阅读器加载器（磁盘缓存 512MB），切章首屏零网络。 */
fun warmComicPage(url: String, headers: Map<String, String>) {
    val loader = synchronized(ComicLoaderLock) { sharedComicLoader } ?: return
    val context = synchronized(ComicLoaderLock) { sharedComicAppContext } ?: return
    val request = coil.request.ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(comicRemoteCacheKey(url, headers))
        .diskCacheKey(comicRemoteCacheKey(url, headers))
        .size(com.example.ui.comic.ComicPageLoader.DECODE_MAX_EDGE)
        .scale(coil.size.Scale.FIT)
        .precision(coil.size.Precision.INEXACT)
        .allowHardware(false)
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .build()
    loader.enqueue(request)
}

/** 构建漫画专用加载器：tu.mhttu.cc 自动 AES 解密、代理路由、AVIF 变体回退、3 次重试 */
internal fun buildComicImageLoader(context: android.content.Context): ImageLoader {
    val client = com.example.source.SharedHttpTransport.builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .proxySelector(com.example.source.js.JsSourceProxy.selector(context))
        .addInterceptor { chain ->
            ComicTransfers.update(chain.request().url.toString(), 0, -1)
            var original = chain.request()
            val resolveImg = sharedResolveImage?.value
            val resolveHeaders = sharedResolveImageHeaders?.value
            if (resolveImg != null) {
                val pageUrl = original.url.toString()
                val resolved = kotlinx.coroutines.runBlocking { resolveImg(pageUrl) }
                if (!resolved.isNullOrBlank() && resolved != pageUrl) {
                    if (resolved.startsWith("file:")) {
                        // H@H 图片已由 Cronet 下载缓存到本地，直接作为响应返回
                        val f = java.io.File(java.net.URI.create(resolved))
                        if (f.exists() && f.length() > 0) {
                            val bytes = f.readBytes()
                            return@addInterceptor okhttp3.Response.Builder()
                                .request(original)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("Content-Type", "image/*")
                                .body(bytes.toResponseBody("image/*".toMediaType()))
                                .build()
                        }
                    } else {
                        val rb = original.newBuilder().url(resolved)
                        val rh = kotlinx.coroutines.runBlocking {
                            resolveHeaders?.invoke(resolved)
                        }.orEmpty()
                        rh.forEach { (k, v) -> rb.header(k, v) }
                        original = rb.build()
                    }
                }
            }
            val builder = original.newBuilder()
            if (original.headers.names().none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
                )
            }
            if (original.headers.names().none { it.equals("Cookie", ignoreCase = true) }) {
                val cookie = JsCookieJar.cookieHeader(context, original.url.toString())
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            }
            // 不声明 avif，避免部分图源 CDN 返回 BitmapFactory/Coil 解不了的 AVIF
            if (original.headers.names().none { it.equals("Accept", ignoreCase = true) }) {
                builder.header("Accept", "image/webp,image/jpeg,image/png,*/*;q=0.8")
            }
            var response: okhttp3.Response? = null
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    if (chain.call().isCanceled()) throw java.io.IOException("请求已取消")
                    response = chain.proceed(builder.build())
                    if (response!!.code !in listOf(408, 429, 500, 502, 503, 504) || attempt == 3) break
                    response!!.close()
                    response = null
                } catch (e: Exception) {
                    if (chain.call().isCanceled()) throw e
                    lastError = e
                }
                if (attempt < 3) {
                    // Check cancellation during backoff; no sleep after the final failure.
                    repeat(attempt * 3) {
                        if (chain.call().isCanceled()) throw java.io.IOException("请求已取消")
                        Thread.sleep(100)
                    }
                }
            }
            if (response == null) throw (lastError ?: Exception("图片请求失败"))
            val finalResponse = response!!
            if (!finalResponse.isSuccessful) return@addInterceptor finalResponse
            val body = finalResponse.body ?: return@addInterceptor finalResponse
            val progressUrl = chain.request().url.toString()
            val total = body.contentLength()
            ComicTransfers.update(progressUrl, 0, total)
            val buffer = okio.Buffer()
            val previewStarted = System.nanoTime()
            val streamPreview = chain.request().tag(ComicStreamPreview::class.java)?.takeIf {
                !MhttuImageDecryptor.isEncryptedHost(finalResponse.request.url.host) &&
                    !JsImageProcessor.hasTransform(progressUrl) &&
                    !JsImageProcessor.hasTransform(finalResponse.request.url.toString())
            }
            body.use {
                val source = it.source()
                var lastUpdate = 0L
                while (source.read(buffer, 64L * 1024) != -1L) {
                    if (buffer.size > 32L * 1024 * 1024) throw java.io.IOException("图片文件过大")
                    if (chain.call().isCanceled()) throw java.io.IOException("请求已取消")
                    // Fast transfers go straight to the full decoder. Slow transfers can
                    // reveal completed progressive scans without waiting for the last byte.
                    if (System.nanoTime() - previewStarted >= 250_000_000L) streamPreview?.offer(buffer)
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastUpdate >= 100) {
                        ComicTransfers.update(progressUrl, buffer.size, total)
                        lastUpdate = now
                    }
                }
            }
            ComicTransfers.update(progressUrl, buffer.size, total)
            val raw = buffer.readByteArray()
            val host = response.request.url.host
            var processed = ImageBytes.normalizeImage(raw, finalResponse.header("Content-Encoding"))
            // 平台解不了 AVIF 时，自动尝试 hitomi 类 CDN 的 webp 变体
            if (ImageBytes.isAvif(processed) && !ImageBytes.decodeOk(processed)) {
                for (candidate in ImageBytes.webpVariants(response.request.url.toString())) {
                    try {
                        val p2 = chain.proceed(response.request.newBuilder().url(candidate).build()).use { r2 ->
                            if (!r2.isSuccessful) null else r2.body?.bytes()?.let { b2 ->
                                ImageBytes.normalizeImage(b2, r2.header("Content-Encoding"))
                            }
                        } ?: continue
                        if (!ImageBytes.isAvif(p2) && ImageBytes.decodeOk(p2)) {
                            processed = p2
                            break
                        }
                    } catch (e: Exception) {
                        // 尝试下一个候选
                    }
                }
            }
            processed = if (MhttuImageDecryptor.isEncryptedHost(host)) {
                MhttuImageDecryptor.decryptIfNeeded(processed)
            } else {
                processed
            }
            processed = JsImageProcessor.transform(response.request.url.toString(), processed) ?: processed
            finalResponse.newBuilder()
                .removeHeader("Content-Encoding")
                .header("Content-Length", processed.size.toString())
                .body(processed.toResponseBody(finalResponse.body?.contentType()))
                .build()
        }
        .addInterceptor(com.example.source.PufeiImageCacheRetry)
        .build()
    synchronized(ComicLoaderLock) {
        sharedComicAppContext = context
    }
    return ImageLoader.Builder(context)
        .okHttpClient(com.example.source.js.JsSourceProxy.failoverClient(context, client))
        // 阅读器逐页直显：crossfade 只会推迟首帧可见
        .crossfade(false)
        // EXIF 方向归一化（第六轮第 4 条现象三）：远程 JPEG 带 90°/270° 标签时
        // 不处理会横显——与本地解码（ComicPageLoader.decodeLocal）行为对齐
        .bitmapFactoryExifOrientationPolicy(coil.decode.ExifOrientationPolicy.RESPECT_ALL)
        .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.10).build() }
        // 磁盘缓存的是拦截器处理后的图片字节（已解密/解 scramble）：重进同一章
        // 直接命中缓存不再走网络。respectCacheHeaders(false)：图床普遍发 no-cache
        // 头，不禁用则磁盘缓存永远命不中。
        .respectCacheHeaders(false)
        .diskCache {
            coil.disk.DiskCache.Builder()
                .directory(java.io.File(context.cacheDir, "comic_image_cache"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .build()
}
