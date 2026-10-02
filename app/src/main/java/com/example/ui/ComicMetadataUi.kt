package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.source.SearchBook
import com.example.ui.components.AppToast

@Composable
internal fun ComicTextActions(label: String, text: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSearch(text) }) { Text("聚合搜索") }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                AppToast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("复制") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComicMetadataBlock(book: SearchBook, onTextLongPress: (String, String) -> Unit) {
    val info = book.comicInfo
    var expanded by remember(book.sourceId, book.id) { mutableStateOf(false) }
    val aliases = info?.alternateTitles.orEmpty().filter { it != book.title }.distinct()
    val tags = info?.tags.orEmpty()
    val rows = buildList {
        info?.status?.let { raw -> add("状态" to when (raw.lowercase()) {
            "ongoing" -> "连载中"
            "completed", "finished" -> "已完结"
            "hiatus" -> "休刊中"
            "cancelled", "canceled" -> "已中止"
            else -> raw
        }) }
        book.language?.let { add("源站标注语言" to it) }
        info?.originalLanguage?.let { add("原作语言" to languageName(it)) }
        info?.artists?.takeIf { it.isNotEmpty() }?.let { add("画师" to it.joinToString("、")) }
        info?.updatedAt?.let { add("源站更新信息" to it) }
    }
    if (rows.isEmpty() && aliases.isEmpty() && tags.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Text("$label：$value", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (aliases.isNotEmpty()) Text("别名：${(if (expanded) aliases else aliases.take(3)).joinToString(" / ")}",
                style = MaterialTheme.typography.bodySmall)
            if (tags.isNotEmpty()) {
                Text("标签", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (if (expanded) tags else tags.take(6)).forEach { tag ->
                        val tagName = tag.substringAfter('：', tag).trim().ifBlank { tag }
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(
                                onClick = {},
                                onLongClickLabel = "复制或搜索标签",
                                onLongClick = { onTextLongPress("标签", tagName) },
                            ),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(tag, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (aliases.size > 3 || tags.size > 6) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起资料" else "展开全部资料")
            }
        }
    }
}

private fun languageName(code: String): String = java.util.Locale.forLanguageTag(code)
    .getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE).takeIf { it.isNotBlank() } ?: code
