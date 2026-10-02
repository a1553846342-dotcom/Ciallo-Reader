package com.example.ui.help

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.MintSecondary
import com.example.ui.theme.clickableWithFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.ui.components.AppIconButton
import androidx.compose.foundation.layout.widthIn
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHelpBottomSheet(
    onDismissRequest: () -> Unit,
    onOpenSourceManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxHeight(0.9f)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("library_help_bottom_sheet"),
        shape = RoundedCornerShape(24.dp)
    ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
Column(modifier = Modifier.widthIn(max = AdaptiveSpec.sheetMaxWidth).fillMaxSize()) {
            // 顶部标题栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpCenter,
                            contentDescription = "帮助中心",
                            tint = MintPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "书库使用手册",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    AppIconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag("close_help_bottom_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭帮助",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 欢迎提示
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MintPrimary.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "书库使用手册已更新：快速掌握搜索、下载与书源管理。",
                        fontSize = 12.sp,
                        color = MintPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // 1. 快速上手
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "1. 快速上手")
                    FlowStepItem(
                        stepNumber = "1",
                        title = "选择书源",
                        desc = "顶部书源选择器可切换小说源或漫画源；选择“聚合漫画（全部）”可并行搜索已启用的漫画源。Venera 源可在书源管理中更新。"
                    )
                    FlowStepItem(
                        stepNumber = "2",
                        title = "搜索图书",
                        desc = "输入书名或作者后点击搜索 / 回车；可按小说或漫画查看分类结果。点击搜索框会显示搜索历史，点历史词可直接再次搜索，也可一键清空。"
                    )
                    FlowStepItem(
                        stepNumber = "3",
                        title = "下载与阅读",
                        desc = "结果卡片点击“下载/阅读”：小说自动后台下载到书架，漫画先进章节页，选择章节在线阅读或下载。"
                    )
                    FlowStepItem(
                        stepNumber = "4",
                        title = "打开书架",
                        desc = "下载完成的书籍会出现在书架，点击即可打开阅读器，支持离线阅读。"
                    )
                }

                // 2. 搜索与搜索历史
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "2. 搜索与搜索历史")
                    TextDetailCard(
                        text = "点击搜索框且输入为空时，会显示最近 10 条搜索历史；点历史词直接搜索，点“清空”删除全部历史。漫画聚合搜索会优先显示先返回的结果，再补齐其他源和标题变体；个别源失败不会阻塞其他源。"
                    )
                }

                // 3. 下载与进度
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "3. 下载与进度")
                    TextDetailCard(
                        text = "书库右上角下载按钮可打开下载面板查看任务进度、速度与剩余大小；支持小说整本下载和漫画章节下载，完成后可离线阅读。漫画下载支持暂停后续传；Z-Library 下载前可能需要登录，按提示进入登录窗口即可。"
                    )
                }

                // 4. 书源管理
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "4. 书源管理")
                    TextDetailCard(
                        text = "管理书源：可启用或停用来源、导入自定义 JSON 书源、更新 Venera 漫画源，也可删除失效源。开启成人漫画源：进入“设置”标签页，连续点击六次“主色按钮实时联动效果”，显示“高级内容”后打开其中的“带你登大郎~~~”；等待 Venera 源列表更新后，返回书库并在漫画书源选择器中选择对应来源。关闭开关会隐藏成人源。自定义源兼容 Legado 规则与 JSON API。"
                    )
                }

                // 5. 漫画阅读
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "5. 漫画阅读")
                    TextDetailCard(
                        text = "在线阅读优先加载当前页并渐进显示后续图片，支持单页、滚动和仿真翻页等模式。章节可添加书签；作品详情可查看标签、神回入口及神回排行榜。站外链接章节一般只能在线阅读，推荐优先选择站内章节下载。"
                    )
                }

                // 6. 常见问题
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpSectionHeader(title = "6. 常见问题")

                    FaqExpandableItem(
                        question = "为什么搜索没有结果？",
                        answer = "确认当前书源已启用且网络正常；可尝试切换“聚合漫画（全部）”或更换关键词。个别源因站点维护 / 网络波动失败时，不影响其他源继续搜索。"
                    )

                    FaqExpandableItem(
                        question = "下载后书架没有书？",
                        answer = "下载完成后系统需要解析 / 打包，稍等片刻再进入书架刷新。漫画需要先在章节页下载章节，才会出现在书架。"
                    )

                    FaqExpandableItem(
                        question = "成人漫画源不显示？",
                        answer = "进入“设置”标签页，连续点击六次“主色按钮实时联动效果”以显示“高级内容”，再打开其中的“带你登大郎~~~”；等待 Venera 更新源列表后回到书库，在漫画书源选择器中切换到需要的来源。此开关关闭时成人源会隐藏。"
                    )

                    FaqExpandableItem(
                        question = "自定义 JSON 源搜不到？",
                        answer = "先确认 listSelector / detailUrl 与站点当前 HTML 匹配，推荐先用 @css: 规则，并用浏览器开发者工具核对元素类名；需要登录或 JS 渲染的站点暂不支持。"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 快捷操作
                Text(
                    text = "快捷操作",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickableWithFeedback {
                                onDismissRequest()
                                onOpenSourceManager()
                            }
                            .testTag("shortcut_open_source_manager"),
                        colors = CardDefaults.cardColors(containerColor = MintPrimary.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "打开书源管理",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MintPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickableWithFeedback {
                                AppToast.makeText(context, "已复制 JSON 示例模板到剪贴板", Toast.LENGTH_SHORT).show()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("JSON_Template", """{
  "id": "example_comic",
  "name": "示例漫画源",
  "baseUrl": "https://example.com",
  "htmlSearch": {
    "url": "/search?q={keyword}",
    "listSelector": "@css:ul.list li.item",
    "title": "@css:a.book-name@text",
    "author": "@css:span.author@text",
    "cover": "@css:img.cover@data-src",
    "detailUrl": "@css:a.book-name@href"
  },
  "htmlChapters": {
    "url": "{id}",
    "listSelector": "@css:ul.chapters li",
    "name": "@css:a@text",
    "href": "@css:a@href"
  },
  "htmlContent": {
    "url": "{chapterUrl}",
    "imageSelector": "@css:div.reader img@data-src"
  }
}""")
                                clipboard.setPrimaryClip(clip)
                            }
                            .testTag("shortcut_copy_template"),
                        colors = CardDefaults.cardColors(containerColor = MintSecondary.copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "查看JSON模板",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MintSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableWithFeedback {
                            // 真正的下载临时文件在 filesDir/downloads/*.tmp；只清 30 分钟前的旧文件，
                            // 避免误伤进行中/暂停待续传的下载；磁盘 IO 放后台线程
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    val dlDir = File(context.filesDir, "downloads")
                                    val cutoff = System.currentTimeMillis() - 30 * 60_000L
                                    val stale = dlDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") && f.lastModified() < cutoff }?.toList() ?: emptyList()
                                    stale.forEach { runCatching { it.delete() } }
                                    if (stale.isNotEmpty()) "已清除 ${stale.size} 个下载临时文件" else "没有可清理的下载临时文件"
                                }
                                AppToast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("shortcut_clear_cache"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "清除下载临时文件",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 版本信息
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ciallo阅读 · 书库引擎",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Legado Rule Compatible · Venera JS 兼容",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
        }
    }
}
