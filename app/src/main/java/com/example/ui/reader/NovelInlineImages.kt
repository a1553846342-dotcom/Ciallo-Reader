package com.example.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 小说正文内嵌图片：EPUB 解析期把图片落盘并在正文写入 [IMG:路径|宽|高] 占位符，
 * 这里负责把占位符渲染为正文内图片（分页测量与实际渲染共用同一套尺寸推导）。
 */
object NovelInlineImages {

    val TOKEN_REGEX = Regex("""\[IMG:([^|\]]+)\|(\d+)\|(\d+)]""")

    fun hasImages(text: String): Boolean = text.contains("[IMG:")

    /**
     * 相邻图片占位符之间强制断行（"][IMG:" → "]换行[IMG:"）。
     * EPUB 彩页常两图连排无文本分隔，渲染成同一行两个整页高占位会视觉叠绘
     * （用户实测的"图片重叠"）。测量端（ReaderPagination.buildParagraph）与
     * 渲染端（buildAnnotatedWithImages）必须同用本函数，保证分页高度一致。
     */
    fun normalizeTokenBreaks(text: String): String =
        if (!hasImages(text)) text else text.replace("][IMG:", "]\n[IMG:")

    /** 去掉所有图片占位符（TTS / 字数统计等纯文本消费方使用）。 */
    fun stripTokens(text: String): String = TOKEN_REGEX.replace(text, "")

    /**
     * 插图位图缓存 + 相邻页预加载。
     * 翻页动画期间下一页层才首次组合，图才开始异步解码（大图几百 ms）——
     * 动画 180~300ms 早结束，于是"翻页背后灰白、翻完才突然出图"。
     * 缓存让重复进入秒显；prewarm 在翻页前把相邻页的图解码好，动画期间直接命中。
     */
    object NovelImageCache {
        private val cache = object : androidx.collection.LruCache<String, android.graphics.Bitmap>(
            48 * 1024 * 1024
        ) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
        }
        private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val prewarmScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        fun get(path: String): android.graphics.Bitmap? = cache.get(path)

        fun put(path: String, bmp: android.graphics.Bitmap) {
            cache.put(path, bmp)
        }

        /**
         * epzip: 引用（EPUB 文件 + 包内条目）解码 —— 零副本方案：图片不落盘，
         * 阅读时从 EPUB 的 zip 流直接解码（采样阈值与文件路径版一致）。
         * spec 形如 "epzip:/data/.../book.epub|OEBPS/images/pic.png"
         */
        fun decodeSampledFromEpub(spec: String): android.graphics.Bitmap? {
            return decodeEpubImage(spec, 1536)
        }

        /** EPUB token 中保存的是 file:// URI，ZipFile 必须接收解码后的磁盘路径。 */
        fun epubImageRef(spec: String): Pair<File, String>? {
            if (!spec.startsWith("epzip:")) return null
            val body = spec.removePrefix("epzip:")
            val sep = body.indexOf('!')
            if (sep <= 0 || sep == body.lastIndex) return null
            val location = body.substring(0, sep)
            val file = if (location.startsWith("file:")) {
                val uri = android.net.Uri.parse(location)
                File(uri.path ?: return null)
            } else File(location)
            return file to body.substring(sep + 1)
        }

        fun readEpubImageBytes(spec: String): ByteArray? {
            val (file, entryName) = epubImageRef(spec) ?: return null
            return runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return null
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }.getOrNull()
        }

        fun decodeEpubImage(spec: String, targetWidth: Int): android.graphics.Bitmap? {
            val (file, entryName) = epubImageRef(spec) ?: return null
            return runCatching {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry(entryName) ?: return null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                    val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
                }
            }.getOrNull()
        }

        /** 与 NovelInlineImage 同一套采样逻辑，保证缓存位图与正常加载一致。 */
        fun decodeSampled(path: String): android.graphics.Bitmap? {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 1536) sample *= 2
            val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return android.graphics.BitmapFactory.decodeFile(path, o2)
        }

        /** 后台预解码；已在缓存或解码中的跳过。 */
        fun prewarm(paths: List<String>) {
            paths.filter { it.isNotBlank() && get(it) == null && inflight.add(it) }
                .forEach { path ->
                    prewarmScope.launch {
                        try {
                            (if (path.startsWith("epzip:")) decodeSampledFromEpub(path) else decodeSampled(path))
                                ?.let { put(path, it) }
                        } finally {
                            inflight.remove(path)
                        }
                    }
                }
        }
    }

    /**
     * 阅读页插图的屏幕矩形注册表（window 坐标）。
     * 翻页手势宿主在分派翻页前查此表：点击位置落在某张插图的真实视觉矩形内
     * → 打开该图全屏并跳过翻页。用几何命中而非事件消费判断，绕开
     * PageCurl 的 Initial-pass（父先于子）导致"子层消费检测"失效的问题。
     *
     * key 是注册实例 id（非图片 path）：同一张图在正文多处引用时各占一条，
     * 互不覆盖；hit 命中重叠矩形时取面积最小者（更靠前的图）。
     */
    object ImageHitRegistry {
        private data class Entry(val path: String, val rect: androidx.compose.ui.geometry.Rect, val pageKey: Any?)

        private val entriesMap = androidx.compose.runtime.mutableStateMapOf<String, Entry>()

        @Volatile
        private var activePageKey: Any? = null

        /** 由当前页的 RenderSinglePage（registerImageHit=true）在组合期调用。 */
        fun setActivePage(pageKey: Any?) {
            android.util.Log.d("ImgHit", "setActivePage hash=${pageKey.hashCode()} len=${(pageKey as? String)?.length}")
            activePageKey = pageKey
        }

        fun register(id: String, path: String, rect: androidx.compose.ui.geometry.Rect, pageKey: Any?) {
            entriesMap[id] = Entry(path, rect, pageKey)
            android.util.Log.d("ImgHit", "register path=$path rect=$rect pageKey=${pageKey.hashCode()} size=${entriesMap.size}")
        }

        fun unregister(id: String) {
            val removed = entriesMap.remove(id)
            if (removed != null) {
                android.util.Log.d("ImgHit", "unregister path=${removed.path}")
            }
        }

        /** 清除非当前页的残留条目（当前页的注册保留）。 */
        fun purgeStale() {
            val before = entriesMap.size
            entriesMap.entries.removeIf { it.value.pageKey != activePageKey }
            if (entriesMap.size != before) {
                android.util.Log.d("ImgHit", "purgeStale: $before -> ${entriesMap.size}")
            }
        }

        /**
         * 命中返回图片 path（重叠时取面积更小者=更靠前的图），未命中返回 null。
         * 只认当前页的条目：翻页后即使引擎保留上一页的组合实例，其残留矩形也不会命中
         * （用户实测：翻到图片下一页，相同位置点击还会打开前面那张图）。
         */
        fun hit(positionInWindow: androidx.compose.ui.geometry.Offset): String? {
            val result = entriesMap.values
                .filter { it.rect.contains(positionInWindow) && it.pageKey == activePageKey }
                .minByOrNull { it.rect.width * it.rect.height }
                ?.path
            android.util.Log.d(
                "ImgHit",
                "hit($positionInWindow) -> $result (active=${activePageKey?.hashCode()} entries=${entriesMap.size} " +
                    "candidates=${entriesMap.values.filter { it.rect.contains(positionInWindow) }.map { e -> e.path to e.pageKey.hashCode() }})"
            )
            return result
        }

        fun clear() {
            entriesMap.clear()
            activePageKey = null
        }
    }

    /**
     * 正文块：文本段或图片段。图片按长宽比换算成 (dw, dh) 独立占空间 ——
     * 不再走文本流内联占位（ParagraphIntrinsics 的 placeholder 在大图场景
     * 表现不可控：分页测量与渲染不一致导致叠绘/占位失效）。
     */
    sealed class InlineBlock {
        abstract val raw: String

        data class Text(val text: String) : InlineBlock() {
            override val raw: String get() = text
        }

        data class Image(
            val path: String,
            val token: String,
            /** 按长宽比换算后的显示尺寸（px），高度已按 maxHeightPx 收敛 */
            val widthPx: Float,
            val heightPx: Float
        ) : InlineBlock() {
            override val raw: String get() = token
        }
    }

    /**
     * 把含 [IMG:...] 占位符的正文切成 文本/图片 块序列，块 raw 顺序拼接 == 输入。
     * 相邻 token 自然形成相邻图片块（每图独立占位，天然断行不叠绘）。
     * maxHeightPx 传页高（翻页）或 Float.MAX_VALUE（滚动长图）。
     */
    fun splitIntoBlocks(
        text: String,
        contentWidthPx: Float,
        maxHeightPx: Float
    ): List<InlineBlock> {
        if (!hasImages(text)) return listOf(InlineBlock.Text(text))
        val blocks = mutableListOf<InlineBlock>()
        var cursor = 0
        TOKEN_REGEX.findAll(text).forEachIndexed { _, m ->
            if (m.range.first > cursor) {
                blocks.add(InlineBlock.Text(text.substring(cursor, m.range.first)))
            }
            val w = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val h = m.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val (dw, dh) = displaySize(w, h, contentWidthPx, maxHeightPx)
            blocks.add(InlineBlock.Image(m.groupValues[1], m.value, dw, dh))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) blocks.add(InlineBlock.Text(text.substring(cursor)))
        return blocks
    }

    fun displaySize(w: Int, h: Int, contentWidthPx: Float, maxHeightPx: Float): Pair<Float, Float> {
        val iw = w.coerceAtLeast(1)
        val ih = h.coerceAtLeast(1)
        var dw = contentWidthPx
        var dh = dw * ih / iw
        // 竖版长图等比缩小，保证一页放得下
        if (dh > maxHeightPx) {
            dh = maxHeightPx
            dw = dh * iw / ih
        }
        return dw to dh
    }
}

/**
 * 单张内嵌图片（本地文件，按显示尺寸降采样解码），点击回调 onTap。
 */
@Composable
fun NovelInlineImage(path: String, onTap: () -> Unit, enabled: Boolean = true) {
    var decodeDone by remember(path) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = NovelInlineImages.NovelImageCache.get(path), path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (path.startsWith("epzip:")) {
                        NovelInlineImages.NovelImageCache.decodeSampledFromEpub(path)
                    } else {
                        NovelInlineImages.NovelImageCache.decodeSampled(path)
                    }
                }.getOrNull()
            }?.also { NovelInlineImages.NovelImageCache.put(path, it) }
        }
        decodeDone = true
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.04f))
            // prev/next 层同屏叠放且在命中链上；非当前页彻底不安装 clickable，
            // 让点击穿透到阅读器的菜单/翻页手势宿主。
            .then(if (enabled) Modifier.clickable { onTap() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "插图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (decodeDone) {
            Text("图片读取失败", color = Color.Gray)
        }
    }
}

/**
 * 把含 [IMG:...] 占位符的正文构建为带内嵌图片的 AnnotatedString。
 *
 * @param imageContent 图片渲染组合项（null 时生成空占位，仅用于分页测量）
 * @return AnnotatedString 与 Text/BasicText 所需的 inlineContent 映射
 */
fun buildAnnotatedWithImages(
    text: String,
    contentWidthPx: Float,
    maxHeightPx: Float,
    density: Density,
    imageContent: (@Composable (String) -> Unit)?,
    highlightQuery: String? = null,
    highlightStyle: SpanStyle? = null
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    // 相邻 token 强制断行（与测量端 ReaderPagination.buildParagraph 同源）
    val text = NovelInlineImages.normalizeTokenBreaks(text)
    val contents = mutableMapOf<String, InlineTextContent>()
    if (!NovelInlineImages.hasImages(text)) {
        val annotated = buildAnnotatedString {
            append(text)
            if (!highlightQuery.isNullOrBlank() && highlightStyle != null) {
                var at = text.indexOf(highlightQuery, ignoreCase = true)
                while (at >= 0) {
                    addStyle(highlightStyle, at, at + highlightQuery.length)
                    at = text.indexOf(highlightQuery, at + highlightQuery.length, ignoreCase = true)
                }
            }
        }
        return annotated to contents
    }
    // Placeholder 尺寸单位是 sp：px→sp 需除以 (density * fontScale)
    val spPerPx = 1f / (density.density * density.fontScale).coerceAtLeast(0.01f)
    val annotated = buildAnnotatedString {
        // annotated 内当前写入位置：token 段被替换为 key 字符串，坐标需手工跟踪
        var ac = 0
        fun appendSegWithHighlight(seg: String) {
            append(seg)
            if (!highlightQuery.isNullOrBlank() && highlightStyle != null) {
                var at = seg.indexOf(highlightQuery, ignoreCase = true)
                while (at >= 0) {
                    addStyle(highlightStyle, ac + at, ac + at + highlightQuery.length)
                    at = seg.indexOf(highlightQuery, at + highlightQuery.length, ignoreCase = true)
                }
            }
            ac += seg.length
        }
        var cursor = 0
        NovelInlineImages.TOKEN_REGEX.findAll(text).forEachIndexed { idx, m ->
            if (m.range.first > cursor) {
                appendSegWithHighlight(text.substring(cursor, m.range.first))
            }
            val path = m.groupValues[1]
            val w = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val h = m.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val key = "novel_img_$idx"
            val (dw, dh) = NovelInlineImages.displaySize(w, h, contentWidthPx, maxHeightPx)
            contents[key] = InlineTextContent(
                Placeholder(
                    width = (dw * spPerPx).sp,
                    height = (dh * spPerPx).sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) { imageContent?.invoke(path) }
            // 必须用 appendInlineContent（带 inlineContent 注解）—— 纯文本 append(key)
            // 不会被 TextDelegate 识别为占位引用，key 会以字面渲染（novel_img_0 症状）
            appendInlineContent(key, " ")
            ac += 1 // inlineContent 在 annotated 里只占 1 个字符宽
            cursor = m.range.last + 1
        }
        if (cursor < text.length) appendSegWithHighlight(text.substring(cursor))
    }
    return annotated to contents
}
