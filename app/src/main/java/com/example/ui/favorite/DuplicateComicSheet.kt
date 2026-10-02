package com.example.ui.favorite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.favorite.FavoriteAddRequest
import com.example.data.favorite.FavoriteEntity
import com.example.source.anilist.TitleNormalizer

/** All add entrances share this decision; selecting a card never performs migration by itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateComicSheet(
    request: FavoriteAddRequest,
    sourceName: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: (FavoriteEntity?) -> Unit,
) {
    var manual by remember(request.book.sourceId, request.book.id) { mutableStateOf(false) }
    var query by remember(request.book.sourceId, request.book.id) { mutableStateOf("") }
    var selected by remember(request.book.sourceId, request.book.id) { mutableStateOf<FavoriteEntity?>(null) }
    val normalizedQuery = TitleNormalizer.compact(query)
    val shown = if (manual) request.favorites.filter {
        normalizedQuery.isBlank() || TitleNormalizer.compact(it.title).contains(normalizedQuery) ||
            TitleNormalizer.compact(it.author).contains(normalizedQuery)
    } else request.candidates.map { it.favorite }
    ModalBottomSheet(
        onDismissRequest = { if (!request.busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("可能重复的作品",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("「${request.book.title}」 · ${sourceName(request.book.sourceId)}",
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text("喜欢里有名称相似的作品。选一本替换并迁移阅读标记，或让两个来源并存。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !request.busy, onClick = { manual = !manual; selected = null }) {
                Text(if (manual) "返回相似作品" else "另一种译名？手动选旧收藏")
            }
            if (manual) {
                OutlinedTextField(value = query, onValueChange = { query = it }, enabled = !request.busy,
                    label = { Text("搜喜欢里的书名或作者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { "${it.sourceId}::${it.comicId}" }) { favorite ->
                        val chosen = selected == favorite
                        Surface(shape = RoundedCornerShape(16.dp),
                            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !request.busy) {
                                selected = if (chosen) null else favorite
                            }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = favorite.localThumbPath ?: favorite.coverUrl, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp, 78.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(favorite.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (favorite.author.isNotBlank()) Text(favorite.author, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(sourceName(favorite.sourceId), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    favorite.latestChapterTitle?.let { Text("最新：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                    request.candidates.firstOrNull { it.favorite == favorite }?.let {
                                        Text(it.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                RadioButton(selected = chosen, onClick = null)
                            }
                        }
                    }
                }
            } else if (manual) {
                Text("没有匹配的旧收藏，换个关键词试试", style = MaterialTheme.typography.bodySmall)
            }
            selected?.let {
                Spacer(Modifier.height(12.dp))
                Text("会保留旧收藏的分类，按话号 / 卷号 / 标题迁移已读和书签。页码从对应话的第一页开始；未匹配记录保留在旧来源。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            request.error?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            if (request.busy) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在保存与迁移…")
                }
            } else {
                selected?.let { favorite ->
                    Button(onClick = { onConfirm(favorite) }, modifier = Modifier.fillMaxWidth()) { Text("替换这一本并迁移阅读记录") }
                }
                if (selected == null) {
                    Button(onClick = { onConfirm(null) }, modifier = Modifier.fillMaxWidth()) { Text("依然添加 · 两本并存") }
                } else {
                    TextButton(onClick = { onConfirm(null) }, modifier = Modifier.fillMaxWidth()) { Text("依然添加 · 两本并存") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
