package com.example.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.example.download.DownloadState
import com.example.source.SearchBook
import com.example.ui.theme.luminance

@Composable
private fun novelAccentColor(): Color {
    val primary = MaterialTheme.colorScheme.primary
    return if (MaterialTheme.colorScheme.surface.luminance() < .3f) lerp(primary, Color.White, .35f)
        else lerp(primary, Color.Black, .18f)
}

@Composable
internal fun novelActionColors(): ButtonColors {
    val background = lerp(MaterialTheme.colorScheme.primary, Color.Black, .12f)
    return ButtonDefaults.buttonColors(containerColor = background,
        contentColor = if (background.luminance() > .179f) Color(0xFF111111) else Color.White)
}

@Composable
private fun NovelCover(book: SearchBook, loader: ImageLoader, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = .22f), MaterialTheme.colorScheme.secondaryContainer)))) {
        @Composable fun Placeholder() {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(book.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text(book.format.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        val context = LocalContext.current
        val request = remember(context, book.cover, book.sourceId) { novelCoverRequest(context, book) }
        if (book.cover.isNullOrBlank()) Placeholder() else SubcomposeAsyncImage(
            model = request, imageLoader = loader, contentDescription = book.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
        ) { if (painter.state is AsyncImagePainter.State.Success) SubcomposeAsyncImageContent() else Placeholder() }
    }
}

internal fun novelCoverRequest(context: android.content.Context, book: SearchBook): ImageRequest =
    ImageRequest.Builder(context).data(book.cover).apply {
        when (book.sourceId) {
            "ixdzs8" -> addHeader("Referer", "https://ixdzs8.com/")
            "wenku8_library" -> addHeader("Referer", "https://wenku8.ywy.moe/")
        }
    }.build()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovelLabels(labels: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.filter(String::isNotBlank).distinct().forEach { label ->
            Text(label, fontSize = 11.sp, color = novelAccentColor(),
                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun NovelSearchCard(book: SearchBook, sourceName: String, imageLoader: ImageLoader,
    state: DownloadState = DownloadState.Idle, downloaded: Boolean = false,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val info = book.novelInfo
    Surface(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovelCover(book, imageLoader, Modifier.width(86.dp).height(124.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(book.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(listOfNotNull(book.author.takeIf(String::isNotBlank), sourceName).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NovelLabels(listOfNotNull(info?.status, info?.category, info?.translation ?: book.language, book.format.uppercase()))
                info?.latestChapter?.takeIf(String::isNotBlank)?.let {
                    Text("最新 · $it", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                info?.synopsis?.takeIf(String::isNotBlank)?.let {
                    Text(it, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
                }
                val facts = listOfNotNull(info?.wordCount, info?.volumeCount?.let { "$it 卷" }, info?.chapterCount?.let { "$it 章" })
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val downloadLabel = when (state) {
                    is DownloadState.Success -> "已在书架 · 离线阅读"
                    is DownloadState.Downloading -> "整本下载 ${(state.progress * 100).toInt()}%"
                    is DownloadState.Pending -> "正在准备整本下载"
                    is DownloadState.Paused -> "整本下载已暂停"
                    is DownloadState.Error -> if (downloaded) "可读离线版本 · 更新下载失败" else "下载失败 · 点开重试"
                    else -> if (downloaded) "已在书架 · 离线阅读" else "查看详情 · 整本下载"
                }
                Text(downloadLabel, fontSize = 11.sp, color = novelAccentColor(), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NovelDetailContent(book: SearchBook, sourceName: String, imageLoader: ImageLoader,
    loading: Boolean = false, error: String? = null, modifier: Modifier = Modifier) {
    val info = book.novelInfo
    Column(modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NovelCover(book, imageLoader, Modifier.width(106.dp).height(150.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sourceName, fontSize = 12.sp, color = novelAccentColor())
                NovelLabels(listOfNotNull(info?.status, info?.category, book.format.uppercase(), book.language))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val facts = listOfNotNull(
            info?.wordCount?.let { "字数" to it }, info?.chapterCount?.let { "源站目录" to "$it 章" },
            info?.volumeCount?.let { "收录卷数" to "$it 卷" }, book.size?.let { "下载大小" to "%.1f MB".format(it / 1024.0 / 1024) },
            info?.publisher?.let { "出版社" to it }, info?.translation?.let { "阅读版本" to it })
        if (facts.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                facts.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                } }
            }
        }
        if (!info?.latestChapter.isNullOrBlank() || !info?.updatedAt.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("连载进度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                info?.latestChapter?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                info?.updatedAt?.let { Text("源站更新 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        info?.originalTitle?.takeIf { it != book.title && it.isNotBlank() }?.let {
            Text("原名 · $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (info?.tags?.isNotEmpty() == true) NovelLabels(info.tags.take(12))
        val synopsis = info?.synopsis?.takeIf(String::isNotBlank) ?: book.description
        if (!synopsis.isNullOrBlank()) {
            var expanded by remember(book.id, book.sourceId) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("作品简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(synopsis, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 7, overflow = TextOverflow.Ellipsis)
                if (synopsis.length > 180) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起简介" else "展开完整简介") }
            }
        }
        info?.notice?.takeIf(String::isNotBlank)?.let { notice ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f), RoundedCornerShape(14.dp))
                .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailSheet(book: SearchBook, sourceName: String, imageLoader: ImageLoader, loading: Boolean,
    error: String?, state: DownloadState, hasLocal: Boolean = false, onDismiss: () -> Unit, onDownload: () -> Unit,
    onRead: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            NovelDetailContent(book, sourceName, imageLoader, loading, error,
                Modifier.weight(1f).verticalScroll(rememberScrollState()))
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        is DownloadState.Downloading -> {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("整本下载 ${(state.progress * 100).toInt()}%", Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = onPause) { Text("暂停") }; TextButton(onClick = onCancel) { Text("取消") }
                            }
                        }
                        is DownloadState.Pending -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("准备整本文件…", fontSize = 12.sp); TextButton(onClick = onCancel) { Text("取消") } }
                        is DownloadState.Paused -> Button(onClick = onResume, colors = novelActionColors(), modifier = Modifier.fillMaxWidth()) { Text("继续整本下载") }
                        is DownloadState.Success -> Button(onClick = if (hasLocal) onRead else onDownload, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(8.dp)); Text(if (hasLocal) "打开书架里的小说" else "整本下载到书架") }
                        else -> {
                            if (state is DownloadState.Error) Text(state.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            Button(onClick = onDownload, enabled = !loading, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (state is DownloadState.Error) "重试整本下载" else "整本下载到书架")
                            }
                        }
                    }
                    if (hasLocal && state !is DownloadState.Success) OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("阅读已下载版本") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelUpdateSheet(update: LibraryViewModel.NovelUpdate, state: DownloadState, onDismiss: () -> Unit,
    onRetry: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("检查小说更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(update.local.title, style = MaterialTheme.typography.titleSmall)
            Text(update.sourceName, fontSize = 12.sp, color = novelAccentColor())
            when {
                update.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在获取源站最新信息…") }
                update.error != null -> { Text(update.error, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onRetry) { Text("重试检查") } }
                else -> {
                    Text(when (update.changed) { true -> "源站信息有更新"; false -> "源站信息未变化"; null -> "已获取源站当前信息" }, fontWeight = FontWeight.SemiBold)
                    update.remote?.novelInfo?.let { info ->
                        info.latestChapter?.let { Text("源站最新 · $it", fontSize = 14.sp) }
                        info.updatedAt?.let { Text("更新于 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        info.chapterCount?.let { Text("源站目录 $it 章", fontSize = 12.sp) }
                        info.notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text("整本新版验证成功后替换，保留阅读位置、书签和笔记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (state) {
                        is DownloadState.Success -> {
                            if (state.path != update.local.filePath.removePrefix("file://")) {
                                Text("新版已存入书架", color = novelAccentColor())
                                Button(onClick = onDismiss, colors = novelActionColors(), modifier = Modifier.fillMaxWidth()) { Text("完成，继续阅读") }
                            } else Button(onClick = onDownload, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text(if (update.changed == true) "下载整本新版" else "重新下载整本") }
                        }
                        is DownloadState.Downloading -> { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); Text("正在下载整本新版 ${(state.progress * 100).toInt()}%"); Row { TextButton(onClick = onPause) { Text("暂停") }; TextButton(onClick = onCancel) { Text("取消") } } }
                        is DownloadState.Pending -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备整本新版…") }
                        is DownloadState.Paused -> Button(onClick = onResume, colors = novelActionColors(), modifier = Modifier.fillMaxWidth()) { Text("继续下载新版") }
                        else -> {
                            if (state is DownloadState.Error) Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            Button(onClick = onDownload, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(if (update.changed == true) "下载整本新版" else "重新下载整本")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NovelUpdatePanel(viewModel: LibraryViewModel, localBook: com.example.data.Book?) {
    val update by viewModel.novelUpdate.collectAsState()
    val states by viewModel.downloadStates.collectAsState()
    val current = update ?: return
    if (localBook?.id != current.local.id) {
        LaunchedEffect(current.local.id, localBook?.id) { viewModel.dismissNovelUpdate() }
        return
    }
    val taskId = com.example.download.DownloadManager.taskId(current.local.sourceId.orEmpty(), current.local.comicId.orEmpty())
    NovelUpdateSheet(current, states[taskId] ?: DownloadState.Idle, onDismiss = viewModel::dismissNovelUpdate,
        onRetry = { viewModel.checkNovelUpdate(current.local) }, onDownload = { current.remote?.let(viewModel::downloadNovelUpdate) },
        onPause = { viewModel.pauseDownload(taskId) }, onResume = { viewModel.resumeDownload(taskId) }, onCancel = { viewModel.cancelDownload(taskId) })
}
