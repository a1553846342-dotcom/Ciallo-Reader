package com.example.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.source.BookSource
import com.example.source.js.JsComicSource

/** Describe declared capabilities separately from current enablement or reachability. */
@Composable
internal fun SourceInfoDialog(source: BookSource, enabled: Boolean, onDismiss: () -> Unit) {
    val caps = source.capabilities
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(source.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源 ID：${source.id}")
                Text("启用状态：${if (enabled) "已启用" else "已停用"}")
                if (source is JsComicSource) {
                    Text("适配方式：Venera / JS 漫画源")
                    Text("脚本版本：${source.version}")
                } else Text("适配方式：应用内漫画源")
                Text("站内搜索：${if (caps.supportSearch) "支持" else "不支持"}")
                Text("章节阅读：${if (caps.supportComic) "支持图片漫画" else "未声明"}")
                Text("章节下载：${if (caps.supportDownload) "支持" else "不支持"}")
                Text("搜索登录：${if (caps.searchRequiresLogin) "需要登录" else "适配器未要求登录"}")
                Text("阅读 / 下载登录：${if (caps.downloadRequiresLogin) "需要登录" else "适配器未要求登录"}")
                Text("作品资料以源站实际返回为准，缺失字段会留空。启用状态表示是否参与搜索；站点可用性以本次请求为准。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道啦") } },
    )
}
