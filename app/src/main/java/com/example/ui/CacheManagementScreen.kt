@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.example.ui

import android.os.StatFs
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.app.usage.StorageStatsManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import me.trishiraj.shadowglow.consistentShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIconButton
import com.example.ui.components.ShimmerBox
import com.example.ui.theme.MintPrimary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.design.DesignTokens
import com.example.ui.components.AppToast

/** 四类书籍封面目录（漫画/EPUB/FB2/MOBI 解析产物）。 */
private val COVER_DIRS = listOf("comic_covers", "epub_covers", "fb2_covers", "mobi_covers")

/**
 * 存储行数据。zone 划分即安全模型：
 * 0=缓存（删除后自动重建，即时清理） 1=用户数据（不可再生或含状态，删除需确认） 2=应用核心数据（只读统计）
 */
private data class StorageRow(
    val key: String,
    val name: String,
    val desc: String,
    val size: Long,
    val zone: Int,
    val deletable: Boolean,
    val confirmTip: String? = null,
    val countLabel: String? = null
)

/**
 * 缓存管理页面：让用户看清应用存储占用，并能放心清理不紧要的文件。
 *
 * 安全模型（对标成熟应用的存储管理）：
 * - 一键清理只作用于可再生图片缓存和临时文件，不触碰译文、模型和离线内容；
 * - 离线书籍、离线漫画、封面图、网页浏览数据属于用户数据，删除必须经确认对话框量化后果；
 * - 数据库与设置只读统计，不提供清理入口；
 * - 所有统计带异常保护（权限/IO 异常按 0 处理，不让扫描协程卡死），清理后实测释放量并重扫刷新；
 * - 清理期间按钮禁用防重复触发；临时文件清理保留进行中的分享/导入。
 *
 * @param onBack 返回上一页回调
 */
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var storageRows by remember { mutableStateOf<List<StorageRow>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var isClearing by remember { mutableStateOf(false) }
    var appTotalSize by remember { mutableLongStateOf(0L) }
    var systemMeasured by remember { mutableStateOf(false) }
    var otherSize by remember { mutableLongStateOf(0L) }
    var freeBytes by remember { mutableLongStateOf(0L) }
    var deviceTotalBytes by remember { mutableLongStateOf(0L) }
    var hasActiveDownload by remember { mutableStateOf(false) }
    var hasActiveComicDownload by remember { mutableStateOf(false) }
    var scanTrigger by remember { mutableIntStateOf(0) }
    var confirmRow by remember { mutableStateOf<StorageRow?>(null) }

    // ── B1 清理动画状态 ──────────────────────────────────────────
    // 清理完成后弹出「打勾 + 释放量」，替代原来只有一个 Toast
    var successFreed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(successFreed) {
        if (successFreed != null) {
            delay(2400)
            successFreed = null
        }
    }
    // 总占用数字：从 0 滚动到实测值，而不是直接蹦出来
    val totalAnim = remember { Animatable(0f) }
    LaunchedEffect(appTotalSize) {
        if (appTotalSize > 0L) {
            totalAnim.snapTo(0f)
            totalAnim.animateTo(
                appTotalSize.toFloat(),
                androidx.compose.animation.core.tween(650, easing = FastOutSlowInEasing)
            )
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    // 带异常保护的目录大小统计：任何权限/IO 异常都按 0 处理，绝不让扫描协程崩溃卡住
    fun dirSize(target: File?): Long {
        if (target == null || !target.exists()) return 0L
        return runCatching {
            target.walkBottomUp().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        }.getOrDefault(0L)
    }

    // 目录内最新文件的修改时间：目录 mtime 只在直接子项增删时更新，解析中的 epub_* 目录需要递归判断
    fun newestFileTime(target: File): Long = runCatching {
        target.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: target.lastModified()
    }.getOrDefault(target.lastModified())

    // 删除执行前的现场复核（扫描快照会过期）：书籍下载以 30 分钟内的 .tmp 为准，
    // 漫画页图片写入后不再修改，2 分钟内有新文件即认为下载仍在进行
    fun hasActiveWrite(key: String): Boolean {
        val now = System.currentTimeMillis()
        return when (key) {
            "downloads" -> File(context.filesDir, "downloads")
                .listFiles { f -> f.isFile && f.name.endsWith(".tmp") && f.lastModified() > now - 30 * 60_000L }
                ?.isNotEmpty() == true
            "comics" -> context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("comics_") }?.any { d ->
                d.walkTopDown().any { f -> f.isFile && f.lastModified() > now - 2 * 60_000L }
            } == true
            else -> false
        }
    }

    // ── 各清理项对应的磁盘目标 ──
    fun targetsFor(key: String): List<File> = when (key) {
        "ehimg" -> listOf(File(context.cacheDir, "ehimg"))
        "image_cache" -> listOf(File(context.cacheDir, "image_cache"))
        "comic_image_cache" -> listOf(File(context.cacheDir, "comic_image_cache"))
        "comic_processed" -> listOf(File(context.cacheDir, "comic_processed_v1"))
        "external_cache" -> context.externalCacheDirs.filterNotNull().distinctBy { it.absolutePath }
        "temp" -> context.cacheDir.listFiles()
            ?.filter { it.name !in setOf("ehimg", "image_cache", "comic_image_cache", "comic_processed_v1", "manga_translate_v1") }
            ?: emptyList()
        "downloads" -> listOf(File(context.filesDir, "downloads"))
        "imports" -> listOf(File(context.filesDir, "imports"))
        "comics" -> context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("comics_") }?.toList() ?: emptyList()
        "book_images" -> listOf("epub_images", "mobi_images", "fb2_images", "docx_images").map { File(context.filesDir, it) }
        "external_files" -> (context.getExternalFilesDirs(null).filterNotNull() + context.externalMediaDirs.filterNotNull()).distinctBy { it.absolutePath }
        "personalization" -> context.filesDir.listFiles { f -> f.isFile &&
            (f.name == "custom_font.ttf" || f.name.startsWith("custom_poster_") || f.name.startsWith("custom_app_bg_")) }?.toList() ?: emptyList()
        "sources" -> listOf(File(context.filesDir, "js_sources"), File(context.filesDir, "novel_reader_backup.json"))
        "appdata" -> listOf("databases", "shared_prefs", "no_backup").map { File(context.dataDir, it) }
        "other" -> {
            val knownDirs = setOf("downloads", "imports", "epub_images", "mobi_images", "fb2_images",
                "docx_images", "js_sources", "manga_translate_models") + COVER_DIRS
            val fileRemainder = context.filesDir.listFiles()?.filter { f ->
                f.name !in knownDirs && !f.name.startsWith("comics_") &&
                    f.name != "custom_font.ttf" && f.name != "novel_reader_backup.json" &&
                    !f.name.startsWith("custom_poster_") && !f.name.startsWith("custom_app_bg_")
            }.orEmpty()
            val dataRemainder = context.dataDir.listFiles()?.filter { f ->
                f.name !in setOf("files", "cache", "databases", "shared_prefs", "no_backup", "app_webview", "lib")
            }.orEmpty()
            fileRemainder + dataRemainder
        }
        "covers" -> COVER_DIRS.map { File(context.filesDir, it) }
        "webview" -> listOf(File(context.dataDir, "app_webview"))
        "manga_tr_cache" -> listOf(com.example.mangatranslate.TranslationCache.dir(context))
        "manga_tr_models" -> listOf(com.example.mangatranslate.TranslateModelManager.modelDir(context))
        else -> emptyList()
    }

    // 临时文件清理的保守策略：Coil 缓存目录有自己的入口不重复动；
    // share_temp 保留 15 分钟内的文件（分享面板里停留或目标应用冷启动都可能超过几分钟）；
    // epub_* 解压目录内最近 2 分钟仍有文件更新的说明可能正在导入
    fun clearTargets(key: String) {
        val now = System.currentTimeMillis()
        when (key) {
            "temp" -> context.cacheDir.listFiles()?.forEach { f ->
                when {
                    f.name in setOf("ehimg", "image_cache", "comic_image_cache", "comic_processed_v1", "manga_translate_v1") -> Unit
                    f.name == "share_temp" && f.isDirectory ->
                        f.listFiles()?.forEach { c -> if (c.lastModified() < now - 15 * 60_000L) runCatching { c.deleteRecursively() } }
                    f.name.startsWith("epub_") && f.isDirectory && newestFileTime(f) > now - 2 * 60_000L -> Unit
                    else -> if (newestFileTime(f) < now - 2 * 60_000L) {
                        runCatching { f.deleteRecursively() }
                    }
                }
            }
            else -> targetsFor(key).forEach { runCatching { it.deleteRecursively() } }
        }
    }

    // 删除并统计实际释放量（删除前后实测差），完成后重扫刷新全部数字
    fun clearRow(key: String) {
        if (isClearing) return
        scope.launch {
            isClearing = true
            try {
                val freed = withContext(Dispatchers.IO) {
                    // 快照可能过期，删除前现场复核；-1 表示因下载进行中放弃本次删除
                    if (hasActiveWrite(key)) return@withContext -1L
                    val before = targetsFor(key).sumOf { dirSize(it) }
                    runCatching { clearTargets(key) }
                    val after = targetsFor(key).sumOf { dirSize(it) }
                    (before - after).coerceAtLeast(0L)
                }
                // B1：清理成功不再只弹 Toast，改为播放「打勾 + 释放量」动画
                val msg = when {
                    freed < 0 -> "检测到下载正在进行，已取消删除"
                    freed > 0 -> { successFreed = freed; com.example.ui.mascot.MascotAnimationController.play(com.example.ui.mascot.MascotEvent.CleanComplete); "" }
                    else -> "没有可释放的内容"
                }
                if (msg.isNotEmpty()) AppToast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                scanTrigger++
            } finally {
                isClearing = false
            }
        }
    }

    // 一键清理只作用于可再生缓存，译文、模型和离线内容走独立入口
    fun clearAllCache() {
        if (isClearing) return
        scope.launch {
            isClearing = true
            try {
                val freed = withContext(Dispatchers.IO) {
                    val keys = listOf("ehimg", "image_cache", "comic_image_cache", "comic_processed", "external_cache", "temp")
                    val before = keys.sumOf { key -> targetsFor(key).sumOf { dirSize(it) } }
                    keys.forEach { key -> runCatching { clearTargets(key) } }
                    val after = keys.sumOf { key -> targetsFor(key).sumOf { dirSize(it) } }
                    (before - after).coerceAtLeast(0L)
                }
                // B1：同上，成功走动画
                val msg = if (freed > 0) {
                    successFreed = freed
                    com.example.ui.mascot.MascotAnimationController.play(com.example.ui.mascot.MascotEvent.CleanComplete)
                    ""
                } else "没有可释放的内容"
                if (msg.isNotEmpty()) AppToast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                scanTrigger++
            } finally {
                isClearing = false
            }
        }
    }

    // ── 扫描：内部数据 + 外部缓存；安装包和系统组件不在此口径内 ──
    LaunchedEffect(scanTrigger) {
        isScanning = true
        val rows = mutableListOf<StorageRow>()
        var appTotal = 0L
        var other = 0L
        var free = 0L
        var deviceTotal = 0L
        var activeDownload = false
        var activeComic = false
        var measuredBySystem = false
        try {
            withContext(Dispatchers.IO) {
                val filesDir = context.filesDir
                val cacheDir = context.cacheDir
                val dataDir = context.dataDir

                val ehimgSize = dirSize(File(cacheDir, "ehimg"))
                val imageCacheSize = dirSize(File(cacheDir, "image_cache"))
                val comicImageSize = dirSize(File(cacheDir, "comic_image_cache"))
                val comicProcessedSize = dirSize(File(cacheDir, "comic_processed_v1"))
                val cacheAll = dirSize(cacheDir)
                val externalCacheSize = targetsFor("external_cache").sumOf { dirSize(it) }
                val externalFilesSize = (context.getExternalFilesDirs(null).filterNotNull() +
                    context.externalMediaDirs.filterNotNull()).distinctBy { it.absolutePath }.sumOf { dirSize(it) }
                val tempSize = targetsFor("temp").sumOf { dirSize(it) }

                val downloadsDir = File(filesDir, "downloads")
                val downloadsSize = dirSize(downloadsDir)
                val importsSize = dirSize(File(filesDir, "imports"))
                val bookImagesSize = targetsFor("book_images").sumOf { dirSize(it) }
                val comicDirs = filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("comics_") }?.toList() ?: emptyList()
                val comicsSize = comicDirs.sumOf { dirSize(it) }
                val coversSize = COVER_DIRS.sumOf { dirSize(File(filesDir, it)) }
                val personalFiles = filesDir.listFiles { f -> f.isFile && (f.name == "custom_font.ttf" || f.name.startsWith("custom_poster_") || f.name.startsWith("custom_app_bg_")) }?.toList() ?: emptyList()
                val personalSize = personalFiles.sumOf { runCatching { it.length() }.getOrDefault(0L) }
                val sourcesSize = dirSize(File(filesDir, "js_sources")) + (File(filesDir, "novel_reader_backup.json").takeIf { it.exists() }?.length() ?: 0L)
                val trModelsSize = dirSize(File(filesDir, "manga_translate_models"))
                val webviewSize = dirSize(File(dataDir, "app_webview"))
                val appDataSize = listOf("databases", "shared_prefs", "no_backup").sumOf { dirSize(File(dataDir, it)) }

                // filesDir 中未归类的杂项（历史遗留示例数据等），归入「其他」让总数自洽
                filesDir.listFiles()?.forEach { f ->
                    val known = (f.isDirectory && (f.name == "downloads" || f.name == "imports" || f.name in setOf("epub_images", "mobi_images", "fb2_images", "docx_images") || f.name.startsWith("comics_") || f.name in COVER_DIRS || f.name == "js_sources" || f.name == "manga_translate_models")) ||
                        (f.isFile && (f.name == "custom_font.ttf" || f.name == "novel_reader_backup.json" || f.name.startsWith("custom_poster_") || f.name.startsWith("custom_app_bg_")))
                    if (!known) other += dirSize(f)
                }
                dataDir.listFiles()?.forEach { f ->
                    if (f.name !in setOf("files", "cache", "databases", "shared_prefs", "no_backup", "app_webview", "lib")) {
                        other += dirSize(f)
                    }
                }

                appTotal = cacheAll + externalCacheSize + externalFilesSize + downloadsSize + importsSize + bookImagesSize + comicsSize + coversSize + personalSize + sourcesSize + trModelsSize + other + webviewSize + appDataSize
                val scannedData = appTotal
                val systemTotal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
                    val stats = context.getSystemService(StorageStatsManager::class.java)
                        .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
                    stats.appBytes + stats.dataBytes
                }.getOrNull() else null
                measuredBySystem = systemTotal != null && systemTotal >= scannedData
                val installAndSystemSize = if (measuredBySystem) {
                    systemTotal!! - scannedData
                } else {
                    (listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty())
                        .distinct().sumOf { path -> dirSize(File(path)) }
                }
                appTotal += installAndSystemSize
                runCatching { StatFs(filesDir.path) }.getOrNull()?.let {
                    free = it.availableBytes
                    deviceTotal = it.totalBytes
                }

                val now = System.currentTimeMillis()
                activeDownload = downloadsDir
                    .listFiles { f -> f.isFile && f.name.endsWith(".tmp") && f.lastModified() > now - 30 * 60_000L }
                    ?.isNotEmpty() == true
                activeComic = comicDirs.any { d ->
                    d.walkTopDown().any { f -> f.isFile && f.lastModified() > now - 2 * 60_000L }
                }
                val bookCount = downloadsDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.size ?: 0
                val activeDownloadTip = if (activeDownload)
                    "\n\n注意：检测到有下载正在进行，暂不能删除；请完成或取消下载后再试。" else ""
                val activeComicTip = if (activeComic)
                    "\n\n注意：检测到有漫画下载正在进行，暂不能删除；请完成或取消下载后再试。" else ""

                rows += StorageRow("ehimg", "网页图片缓存", "在线书源图片，删除后浏览时自动重新加载", ehimgSize, 0, true)
                rows += StorageRow("image_cache", "图片加载缓存", "封面与在线图片的通用缓存，自动重建", imageCacheSize, 0, true)
                rows += StorageRow("comic_image_cache", "漫画原图缓存", "在线漫画图片，删除后阅读时重新下载", comicImageSize, 0, true)
                rows += StorageRow("comic_processed", "漫画增强缓存", "画质增强后的页面，删除后重新处理", comicProcessedSize, 0, true)
                if (externalCacheSize > 0) {
                    rows += StorageRow("external_cache", "外部缓存", "应用专用外部缓存，删除后按需重建", externalCacheSize, 0, true)
                }
                rows += StorageRow("temp", "其他临时文件", "导入、分享和模型副本；正在使用的文件会保留", tempSize, 0, true)
                // 译文需要重新翻译、模型需要重新下载，因此归入按需管理
                val trCacheSize = runCatching { com.example.mangatranslate.TranslationCache.totalBytes(context) }.getOrDefault(0L)
                val trCacheCount = runCatching {
                    com.example.mangatranslate.TranslationCache.dir(context).listFiles()?.count { it.isFile } ?: 0
                }.getOrDefault(0)
                if (trCacheSize > 0) {
                    rows += StorageRow("manga_tr_cache", "漫画译文缓存", "已翻译页面；清理后需要重新翻译", trCacheSize, 1, true,
                        "清理后再次阅读这些页面需要重新翻译。", if (trCacheCount > 0) "共 $trCacheCount 页" else null)
                }
                if (trModelsSize > 0) {
                    rows += StorageRow("manga_tr_models", "漫画翻译模型", "离线模型；清理后需要重新下载", trModelsSize, 1, true,
                        "清理后漫画翻译将不可用，直到重新下载模型。")
                }

                rows += StorageRow("downloads", "离线书籍", "下载的书籍原文件，属于你的离线内容", downloadsSize, 1, true,
                    "书架记录仍保留，但删除原文件后无法重新解析；若书源失效，也无法重新下载。$activeDownloadTip",
                    if (bookCount > 0) "共 $bookCount 个文件" else null)
                rows += StorageRow("imports", "导入的原文件", "本地书籍源文件", importsSize, 1, true,
                    "删除后可能无法重新解析或恢复书内图片；书架记录仍会保留。")
                rows += StorageRow("comics", "离线漫画", "已下载的漫画页面与封面，属于你的离线内容", comicsSize, 1, true,
                    "已下载的漫画页会被删除，之后无法离线阅读。$activeComicTip",
                    if (comicDirs.isNotEmpty()) "共 ${comicDirs.size} 个目录" else null)
                rows += StorageRow("covers", "封面图片", "书籍与漫画的封面图，删除后无法自动恢复", coversSize, 1, true,
                    "书架封面将显示为空白，重新导入对应书籍后才能恢复。")
                rows += StorageRow("webview", "网页浏览数据", "网页书源的浏览器数据，删除后需重新登录书源网站", webviewSize, 1, true,
                    "网页书源的登录状态与浏览数据将被清除，之后需要重新登录。")
                rows += StorageRow("personalization", "个性化文件", "导入的字体、启动海报与软件背景，可在设置中更换", personalSize, 1, false)
                rows += StorageRow("book_images", "书内插图", "本地书籍解析出的图片", bookImagesSize, 1, false)
                if (externalFilesSize > 0) rows += StorageRow("external_files", "外部应用文件", "应用专用外部文件", externalFilesSize, 1, false)
                rows += StorageRow("sources", "书源与备份", "已安装的书源脚本与设置备份", sourcesSize, 1, false)
                rows += StorageRow("appdata", "书籍数据与设置", "书架、阅读进度与全部设置，不可在此清理", appDataSize, 2, false)
                if (installAndSystemSize > 0) rows += StorageRow("install", "安装包与系统文件", "应用安装及系统编译文件", installAndSystemSize, 2, false)
                if (other > 0) {
                    rows += StorageRow("other", "其他应用数据", "运行代码缓存等内部文件，应用运行时不提供清理", other, 2, false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            // 协程被取消（快速重扫/离开页面）时不落状态，避免旧扫描覆盖新扫描或闪空数据
            if (isActive) {
                storageRows = rows
                appTotalSize = appTotal
                systemMeasured = measuredBySystem
                otherSize = other
                freeBytes = free
                deviceTotalBytes = deviceTotal
                hasActiveDownload = activeDownload
                hasActiveComicDownload = activeComic
                isScanning = false
            }
        }
    }

    val cacheTotal = storageRows.filter { it.zone == 0 }.sumOf { it.size }
    // 第十八轮：全部类别明细（手机内存管理式：单删/批删/全选/全清 + 弹出动画）
    var detailRow by remember { mutableStateOf<StorageRow?>(null) }
    detailRow?.let { row ->
        StorageDetailDialog(
            rowKey = row.key,
            title = row.name,
            targets = targetsFor(row.key),
            confirmTip = row.confirmTip,
            allowDelete = row.deletable,
            onDismiss = { detailRow = null },
            onChanged = { scanTrigger++ },
        )
    }

    // 与「书库使用手册」一致的 ModalBottomSheet 结构与观感
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight(0.9f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxSize()) {
            // 顶部标题栏（同手册页：64dp + 图标 + 标题 + 关闭）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = DesignTokens.SpacePage),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CleaningServices,
                            contentDescription = "缓存管理",
                            tint = MintPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("缓存管理", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    AppIconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                modifier = Modifier
                .widthIn(max = AdaptiveSpec.pageContentMaxWidth).fillMaxSize().padding(horizontal = DesignTokens.SpacePage),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                item(key = "overview") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isScanning) {
                                // B1：原先只有一个转圈 + 一行字，扫描期间整块空白。
                                // 改成骨架屏，形状与目标内容一一对应，扫描完直接"长"出来。
                                ScanningSkeleton()
                            } else {
                                // B1：数字从 0 滚动到实测值
                                Text(
                                    formatSize(totalAnim.value.toLong().coerceAtLeast(0L)),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(if (systemMeasured) "应用总占用 · 系统统计" else "已扫描占用 · 含安装包",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                // 占比条：缓存 / 用户数据 / 书籍数据与设置（+ 其他杂项，让总数与分段自洽）
                                val userTotal = storageRows.filter { it.zone == 1 }.sumOf { it.size }
                                val appDataTotal = storageRows.filter { it.zone == 2 && it.key != "other" }.sumOf { it.size }
                                if (appTotalSize > 0) {
                                    Spacer(Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    ) {
                                        segmentedBar(cacheTotal, userTotal, appDataTotal, otherSize, appTotalSize)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val legend = buildList {
                                        add(Triple("可直接清理", cacheTotal, Color(0xFF00B894)))
                                        add(Triple("按需管理与离线内容", userTotal, Color(0xFFE17055)))
                                        add(Triple("应用数据与设置", appDataTotal, Color(0xFF74B9FF)))
                                        if (otherSize > 0) add(Triple("其他", otherSize, Color(0xFF90A4AE)))
                                    }
                                    legend.forEach { (label, size, color) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(8.dp).background(color, CircleShape))
                                            Spacer(Modifier.width(6.dp))
                                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.weight(1f))
                                            Text(formatSize(size), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                if (freeBytes > 0) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Storage, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "设备剩余 ${formatSize(freeBytes)} / 共 ${formatSize(deviceTotalBytes)}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "clear_all") {
                    Button(
                        onClick = { clearAllCache() },
                        enabled = !isScanning && !isClearing && cacheTotal > 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isClearing) {
                            // B1：正在清理 → 文件飞进垃圾桶的粒子动画，
                            // 取代原来一个 18dp 转圈（完全看不出在"清理垃圾"）
                            FileSweepIndicator(tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text("正在清理…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(
                                if (cacheTotal > 0) "清理可再生缓存 · 约 ${formatSize(cacheTotal)}" else "清理可再生缓存",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 三个分区：可再生缓存 → 按需管理与离线内容 → 核心数据；空区整段隐藏
                val zoneMeta = listOf(0 to "可清理缓存", 1 to "离线内容与个人文件", 2 to "应用与设置")
                zoneMeta.forEach { (zone, title) ->
                    val zoneRows = storageRows.filter { it.zone == zone && it.size > 0 }
                    if (zoneRows.isNotEmpty()) {
                        item(key = "zone_header_$zone") {
                            Text(title, modifier = Modifier.padding(top = 2.dp), fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        items(zoneRows, key = { it.key }) { row ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // B1：清理后本行内容会变（尺寸/文案），
                                    // 加 animateContentSize 让变化平滑，不再瞬间跳
                                    .animateContentSize(
                                        androidx.compose.animation.core.spring(
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                    .then(
                                        if (targetsFor(row.key).isNotEmpty()) Modifier.clickable { detailRow = row }
                                        else Modifier
                                    )
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            row.name,
                                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                                        )
                                        Text(row.desc, fontSize = 12.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        formatSize(row.size), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (row.deletable) {
                                        Spacer(Modifier.width(12.dp))
                                        AppIconButton(
                                            onClick = {
                                                if (row.key in setOf("downloads", "imports", "comics", "covers")) detailRow = row
                                                else if (row.confirmTip != null) confirmRow = row
                                                else clearRow(row.key)
                                            },
                                            enabled = !isScanning && !isClearing
                                        ) {
                                            Icon(
                                                Icons.Default.Delete, "清理${row.name}",
                                                // 颜色语言：缓存=中性灰（随便删），用户数据=警示红（需确认）
                                                tint = when {
                                                    row.zone == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    else -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                                },
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                // ── B1 清理成功动画：打勾 + 释放量，弹簧弹出后自动消失 ──
                // 单独包一层居中 Box：不依赖外层 Box 的 align 重载，
                // 也不影响 LazyColumn 自身的 TopCenter 排布。
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = successFreed != null,
                        enter = fadeIn(androidx.compose.animation.core.tween(180)) + scaleIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            initialScale = 0.6f
                        ),
                        exit = fadeOut(androidx.compose.animation.core.tween(260))
                    ) {
                        CleanSuccessBadge(freedText = formatSize(successFreed ?: 0L))
                    }
                }
            }
        }
        }
    }

    // 用户数据删除确认：一句话说清删什么、多少、多大、后果
    confirmRow?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmRow = null },
            title = { Text("删除「${row.name}」") },
            text = {
                val countPart = row.countLabel?.let { "$it，" } ?: ""
                Text("将删除「${row.name}」${countPart}约 ${formatSize(row.size)}。\n\n${row.confirmTip}")
            },
            confirmButton = {
                // 有下载进行中时不允许删除对应离线内容，避免与写入中的文件竞态（执行前还有二次复核兜底）
                val deleteEnabled = !((row.key == "downloads" && hasActiveDownload) ||
                    (row.key == "comics" && hasActiveComicDownload))
                TextButton(
                    onClick = { confirmRow = null; clearRow(row.key) },
                    enabled = deleteEnabled
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRow = null }) { Text("取消") }
            }
        )
    }
}

/** 总览占比条：缓存/用户数据/书籍数据与设置，可选「其他」段。 */
@Composable
private fun RowScope.segmentedBar(
    cacheTotal: Long,
    userTotal: Long,
    appDataTotal: Long,
    otherTotal: Long,
    grandTotal: Long
) {
    val segments = buildList {
        add(Triple(cacheTotal, Color(0xFF00B894), "cache"))
        add(Triple(userTotal, Color(0xFFE17055), "user"))
        add(Triple(appDataTotal, Color(0xFF74B9FF), "appdata"))
        if (otherTotal > 0) add(Triple(otherTotal, Color(0xFF90A4AE), "other"))
    }
    // B1：原先 weight 直接赋值 → 占比条瞬间成形。
    // 改为逐段错峰生长（每段延迟 90ms），扫描完有"填充起来"的过程感。
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(grandTotal) { grown = grandTotal > 0L }
    segments.forEachIndexed { index, (size, color, _) ->
        val target = (size.toFloat() / grandTotal).coerceAtLeast(0.005f)
        val w by animateFloatAsState(
            targetValue = if (grown) target else 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 520,
                delayMillis = index * 90,
                easing = FastOutSlowInEasing
            ),
            label = "segment_$index"
        )
        Box(
            Modifier
                .weight(w.coerceAtLeast(0.0001f))
                .fillMaxHeight()
                .background(color)
        )
    }
}

/* ── B1 清理动画组件 ───────────────────────────────────────────────── */

/** 扫描中骨架屏：形状与扫描完成后的内容一一对应。 */
@Composable
private fun ScanningSkeleton() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ShimmerBox(modifier = Modifier.size(width = 128.dp, height = 38.dp), cornerRadius = 10)
        Spacer(Modifier.height(8.dp))
        ShimmerBox(modifier = Modifier.size(width = 76.dp, height = 16.dp), cornerRadius = 8)
        Spacer(Modifier.height(18.dp))
        // 占比条
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(8.dp), cornerRadius = 4)
        Spacer(Modifier.height(14.dp))
        // 三行图例
        repeat(3) {
            ShimmerBox(modifier = Modifier.fillMaxWidth().height(14.dp), cornerRadius = 7)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 「文件飞进垃圾桶」粒子动画：清理中替代原来那个 18dp 转圈。
 * 3 个方块从左上朝右下抛物线飞入并缩小消失，循环播放。
 */
@Composable
private fun FileSweepIndicator(
    modifier: Modifier = Modifier,
    tint: Color
) {
    val transition = rememberInfiniteTransition(label = "fileSweep")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1250, easing = LinearEasing)
        ),
        label = "sweepT"
    )
    Canvas(modifier = modifier.size(22.dp)) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.70f
        for (i in 0..2) {
            val phase = (t + i / 3f) % 1f
            // 水平匀速、垂直加速（抛物线感）
            val px = size.width * 0.04f + (cx - size.width * 0.04f) * phase
            val py = size.height * 0.02f + (cy - size.height * 0.02f) * (phase * phase)
            val alpha = (1f - phase).coerceIn(0f, 1f)
            val side = size.width * 0.26f * (1f - phase * 0.55f)
            drawRect(
                color = tint.copy(alpha = alpha),
                topLeft = Offset(px - side / 2f, py - side / 2f),
                size = ComposeSize(side, side)
            )
        }
        // 底部"桶口"
        drawLine(
            color = tint,
            start = Offset(size.width * 0.22f, size.height * 0.88f),
            end = Offset(size.width * 0.78f, size.height * 0.88f),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
    }
}

/** 清理成功：打勾（逐笔画出）+ 释放量。 */
@Composable
private fun CleanSuccessBadge(freedText: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 0.dp,
        modifier = Modifier.consistentShadow(
            elevation = 12.dp,
            shape = RoundedCornerShape(24.dp),
            ambientColor = Color.Black.copy(alpha = 0.12f),
            spotColor = Color.Black.copy(alpha = 0.16f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF00B894), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedCheckMark()
            }
            Spacer(Modifier.height(12.dp))
            Text("清理完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "已释放 $freedText",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 打勾：短边先画完、再画长边，避免整条线一起出现显得生硬。 */
@Composable
private fun AnimatedCheckMark(color: Color = Color.White) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(420, easing = FastOutSlowInEasing),
        label = "checkProgress"
    )
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 3.5.dp.toPx()
        val p1 = (progress / 0.45f).coerceIn(0f, 1f)
        val p2 = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
        drawLine(
            color = color,
            start = Offset(w * 0.18f, h * 0.52f),
            end = Offset(w * 0.18f + w * 0.24f * p1, h * 0.52f + h * 0.24f * p1),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (p2 > 0f) {
            drawLine(
                color = color,
                start = Offset(w * 0.42f, h * 0.76f),
                end = Offset(w * 0.42f + w * 0.42f * p2, h * 0.76f - h * 0.48f * p2),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
