package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.DownloadManager
import com.example.download.DownloadTaskEntity

/**
 * 通用存储明细对话框（第十八轮）：手机内存管理式——任意缓存/数据类别的
 * 逐文件/逐目录列表（可读名+大小），单删、批删、全选、全清。
 * 漫画译文缓存条目附加智能标签（原文→译文，复用 TranslationCacheIndex）。
 *
 * 窗口弹出动画：缩放 0.92→1 + 淡入（180ms）。
 */
data class StorageFileEntry(
    val file: File,
    val size: Long,
    val label: String,
    val sublabel: String = "",
    val deletable: Boolean = true,
)

object StorageDetailIndex {

    private fun pathOf(value: String?): String? = value?.takeUnless { it.startsWith("content:") }
        ?.removePrefix("file://")?.let { File(it).absolutePath }

    private fun describe(
        key: String, file: File, books: List<Book>, tasks: List<DownloadTaskEntity>
    ): Pair<String, String> {
        val path = file.absolutePath
        val book = when (key) {
            "covers" -> books.firstOrNull { pathOf(it.coverUri) == path }
            "book_images" -> file.name.toIntOrNull()?.let { id -> books.firstOrNull { it.id == id } }
            "downloads", "imports", "comics" -> books.firstOrNull { pathOf(it.filePath) == path }
            else -> null
        }
        if (book != null) return "《${book.title}》" to when (key) {
            "covers" -> "书架封面"
            "book_images" -> "书内插图"
            "comics" -> "离线漫画页面"
            else -> "${file.extension.uppercase()} 原文件"
        }
        if (key == "downloads") {
            val task = tasks.firstOrNull {
                pathOf(it.filePath) == path ||
                    file.nameWithoutExtension == DownloadManager.sanitizeFileName(it.id)
            }
            if (task != null) return "《${task.title}》" to "下载记录 · ${task.format.uppercase()}"
        }
        if (key in setOf("downloads", "imports", "comics", "covers", "book_images")) {
            return "未关联的${when (key) {
                "covers" -> "封面"
                "book_images" -> "书内插图"
                "comics" -> "漫画"
                else -> "书籍文件"
            }}" to file.name
        }
        if (key == "manga_tr_models") return "离线翻译模型" to file.name
        if (key == "webview") return when (file.name) {
            "Cookies" -> "网页登录信息" to "Cookie"
            "Local Storage" -> "网页本地数据" to "登录与浏览记录"
            "Default" -> "网页浏览资料" to "浏览器数据"
            else -> "网页浏览数据" to file.name
        }
        if (key == "personalization") return when {
            file.name == "custom_font.ttf" -> "自定义字体" to ""
            file.name.startsWith("custom_poster_") -> "启动海报" to ""
            else -> "软件背景" to ""
        }
        if (key == "sources") return if (file.name == "novel_reader_backup.json")
            "设置备份" to "JSON" else "已安装书源" to file.name
        if (key == "appdata") return when {
            file.parentFile?.name == "databases" || file.name == "databases" -> "书架与阅读数据" to file.name
            file.parentFile?.name == "shared_prefs" || file.name == "shared_prefs" -> "应用设置" to file.name
            else -> "应用状态数据" to file.name
        }
        val category = when (key) {
            "ehimg" -> "网页图片缓存"
            "image_cache" -> "封面与图片缓存"
            "comic_image_cache" -> "漫画图片缓存"
            "comic_processed" -> "漫画增强缓存"
            "external_cache" -> "外部缓存"
            "temp" -> "临时文件"
            "other" -> "其他应用数据"
            "external_files" -> "外部应用文件"
            else -> "缓存文件"
        }
        return category to if (file.name.length > 18 && file.name.matches(Regex("[a-fA-F0-9_.-]+")))
            "内部编号 ${file.name.takeLast(8)}" else file.name
    }

    /** 把类别 key 展开为逐文件/逐目录条目（含可读标签）。 */
    suspend fun scan(
        key: String, targets: List<File>, books: List<Book> = emptyList(),
        tasks: List<DownloadTaskEntity> = emptyList()
    ): List<StorageFileEntry> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<StorageFileEntry>()
            val now = System.currentTimeMillis()
            for (dir in targets) {
                if (!dir.exists()) continue
                if (key == "comics" && dir.isDirectory) {
                    val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    val (label, sublabel) = describe(key, dir, books, tasks)
                    val active = dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    out.add(StorageFileEntry(dir, size, label, sublabel, !active))
                    continue
                }
                val activeRoot = when (key) {
                    "temp" -> dir.name != "share_temp" &&
                        dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    "comics" -> dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    "downloads" -> dir.listFiles()?.any {
                        it.isFile && it.name.endsWith(".tmp") && it.lastModified() > now - 30 * 60_000L
                    } == true
                    else -> false
                }
                if (dir.isFile) {
                    val activeFile = (key == "downloads" && dir.name.endsWith(".tmp") &&
                        dir.lastModified() > now - 30 * 60_000L) ||
                        (key == "imports" && dir.lastModified() > now - 2 * 60_000L)
                    val (label, sublabel) = describe(key, dir, books, tasks)
                    out.add(StorageFileEntry(dir, dir.length(), label, sublabel, !activeFile && !activeRoot))
                    continue
                }
                val children = dir.listFiles() ?: continue
                for (f in children.sortedByDescending { it.length() }) {
                    val size = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else f.length()
                    if (size <= 0L && f.isFile) continue
                    val protected = activeRoot ||
                        (key == "downloads" && f.name.endsWith(".tmp") && f.lastModified() > now - 30 * 60_000L) ||
                        (key == "imports" && f.lastModified() > now - 2 * 60_000L) ||
                        (key == "temp" && dir.name == "share_temp" && f.lastModified() > now - 15 * 60_000L) ||
                        (key == "temp" && dir.name != "share_temp" && f.lastModified() > now - 2 * 60_000L)
                    when (key) {
                        "manga_tr_cache" -> {
                            // 译文缓存：复用智能标签（原文→译文）
                            val info = TranslationCacheIndex.describe(f)
                            out.add(StorageFileEntry(f, size, info.first, info.second, !protected))
                        }
                        else -> {
                            val (label, sublabel) = describe(key, f, books, tasks)
                            out.add(StorageFileEntry(f, size, label, sublabel, !protected))
                        }
                    }
                }
            }
            out.sortedByDescending { it.size }
        }

    suspend fun delete(files: List<File>, key: String): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        files.forEach { f ->
            runCatching {
                val now = System.currentTimeMillis()
                val root = when (key) {
                    "comics" -> if (f.name.startsWith("comics_")) f else f.parentFile
                    "temp" -> if (f.parentFile?.name == "share_temp") f.parentFile else f.parentFile
                    else -> f.parentFile
                }
                val active = when (key) {
                    "downloads" -> root?.listFiles()?.any {
                        it.isFile && it.name.endsWith(".tmp") && it.lastModified() > now - 30 * 60_000L
                    } == true
                    "comics" -> root?.walkTopDown()?.any {
                        it.isFile && it.lastModified() > now - 2 * 60_000L
                    } == true
                    "temp" -> if (root?.name == "share_temp") {
                        f.lastModified() > now - 15 * 60_000L
                    } else {
                        root?.walkTopDown()?.any { it.isFile && it.lastModified() > now - 2 * 60_000L } == true
                    }
                    "imports" -> f.lastModified() > now - 2 * 60_000L
                    else -> false
                }
                if (active) return@runCatching
                val before = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else if (f.isFile) f.length() else 0L
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                val after = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else if (f.isFile) f.length() else 0L
                freed += (before - after).coerceAtLeast(0L)
            }
        }
        freed
    }
}

/** 明细对话框：任意类别通用。rowKey 用于译文缓存智能标签分支。 */
@Composable
fun StorageDetailDialog(
    rowKey: String,
    title: String,
    targets: List<File>,
    confirmTip: String? = null,
    allowDelete: Boolean = true,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<StorageFileEntry>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    val checked = remember { mutableStateMapOf<File, Boolean>() }
    var pendingDeletion by remember { mutableStateOf<List<File>?>(null) }
    // 弹出动画：0.92→1 缩放 + 淡入
    var appeared by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (appeared) 1f else 0.92f, tween(180), label = "detailScale")
    val alpha by animateFloatAsState(if (appeared) 1f else 0f, tween(180), label = "detailAlpha")

    fun refresh() {
        scope.launch {
            scanning = true
            try {
                val (books, tasks) = if (rowKey in setOf("downloads", "imports", "comics", "covers", "book_images")) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(context)
                            db.bookDao().getAllBooksSync() to db.downloadTaskDao().getAllTasksSync()
                        }
                    }.getOrDefault(emptyList<Book>() to emptyList<DownloadTaskEntity>())
                } else emptyList<Book>() to emptyList<DownloadTaskEntity>()
                entries = StorageDetailIndex.scan(rowKey, targets, books, tasks).map {
                    if (allowDelete) it else it.copy(deletable = false)
                }
                checked.keys.retainAll(entries.map { it.file }.toSet())
            } finally {
                scanning = false
            }
        }
    }
    fun requestDeletion(files: List<File>) {
        if (files.isEmpty()) return
        if (confirmTip != null) {
            pendingDeletion = files
        } else {
            scope.launch {
                StorageDetailIndex.delete(files, rowKey)
                onChanged()
                refresh()
            }
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        appeared = true
    }

    val selectedFiles = entries.filter { checked[it.file] == true }.map { it.file }
    val totalSize = entries.sumOf { it.size }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    this.alpha = alpha
                },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Close, contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onDismiss),
                    )
                }
                Text(
                    if (scanning) "扫描中…" else "${entries.size} 项 · 共 ${formatCacheSize(totalSize)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))

                if (!scanning && entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("暂无可管理的内容", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (allowDelete) item(key = "select_all") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val allOn = entries.isNotEmpty() && entries.all { checked[it.file] == true }
                                        entries.forEach { if (it.deletable) checked[it.file] = !allOn }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (entries.isNotEmpty() && entries.all { checked[it.file] == true || !it.deletable }) "取消全选"
                                    else "全选",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                if (selectedFiles.isNotEmpty()) {
                                    Text(
                                        "已选 ${selectedFiles.size} 项 / ${formatCacheSize(entries.filter { checked[it.file] == true }.sumOf { it.size })}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(entries, key = { it.file.absolutePath }) { entry ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (entry.deletable) Modifier.clickable {
                                            checked[entry.file] = !(checked[entry.file] ?: false)
                                        } else Modifier
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (entry.deletable) {
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (checked[entry.file] == true) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (checked[entry.file] == true) {
                                            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (entry.sublabel.isNotEmpty()) {
                                        Text(
                                            entry.sublabel, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    formatCacheSize(entry.size), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (entry.deletable) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Filled.Delete, contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                requestDeletion(listOf(entry.file))
                                            },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                if (allowDelete) Row {
                    TextButton(
                        onClick = {
                            requestDeletion(entries.filter { it.deletable }.map { it.file })
                        },
                        enabled = entries.any { it.deletable } && !scanning,
                    ) { Text("全部清理", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    TextButton(
                        onClick = {
                            requestDeletion(selectedFiles)
                        },
                        enabled = selectedFiles.isNotEmpty(),
                    ) { Text("删除所选（${selectedFiles.size}）") }
                }
                else TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
    pendingDeletion?.let { files ->
        val selectedBytes = entries.filter { it.file in files }.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("确认清理 ${files.size} 项") },
            text = { Text("预计清理 ${formatCacheSize(selectedBytes)}。\n\n${confirmTip.orEmpty()}") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                    scope.launch {
                        StorageDetailIndex.delete(files, rowKey)
                        onChanged()
                        refresh()
                    }
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("取消") }
            }
        )
    }
}
