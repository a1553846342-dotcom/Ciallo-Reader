package com.example.ui.source

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.library.ZLibraryNodeManager
import com.example.source.SearchBook
import com.example.source.zlibrary.ZLibraryCredentialStorage
import com.example.source.zlibrary.ZLibraryWebViewHelper
import com.example.source.zlibrary.network.ZLibraryHttpClient
import com.example.source.zlibrary.parser.ZLibraryParserManager
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppButtonVariant
import com.example.ui.components.AppIconButton
import com.example.ui.components.DialogLiquidGlass
import com.example.ui.components.AppIconButton
import com.example.ui.theme.MintPrimary
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import com.example.ui.components.AppToast

/**
 * Z-Library 节点管理窗口：
 * - 默认节点（1lib.sk）+ 官网/备用入口三个节点 + 用户自定义节点；
 * - 每个节点可“检测”（游离 WebView 真实搜索“三体”并解析）；
 * - “扒取节点”按钮抓取 https://z.wwwnav.com/rkfby.html 的三个入口，确认后替换；
 * - 切换节点立即生效并持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibraryNodeManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }

    var scrapedNodes by remember { mutableStateOf(ZLibraryNodeManager.getScrapedNodes(context)) }
    var customNodes by remember { mutableStateOf(ZLibraryNodeManager.getCustomNodes(context)) }
    var selectedNode by remember { mutableStateOf(ZLibraryNodeManager.getSelectedNode(context)) }
    var customInput by remember { mutableStateOf("") }
    var scraping by remember { mutableStateOf(false) }
    var showReplaceDialog by remember { mutableStateOf(false) }
    var foundNodes by remember { mutableStateOf<List<String>>(emptyList()) }

    val statuses = remember { mutableStateMapOf<String, String>() }
    var testingNode by remember { mutableStateOf<String?>(null) }

    fun testNode(node: String) {
        if (testingNode != null) return
        testingNode = node
        statuses[node] = "测试中…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val httpClient = ZLibraryHttpClient(
                        credentialStorage = ZLibraryCredentialStorage(context),
                        context = context
                    )
                    val encodedKw = URLEncoder.encode("三体", "UTF-8").replace("+", "%20")
                    val url = "https://$node/s/$encodedKw"
                    val response = httpClient.get(url, referer = "https://$node/")
                    val code = response.code
                    val html = response.body?.string() ?: ""
                    // 修复"搜索出40本"假通过：入口跳转域会把 /s/{kw} 302 丢路径跳到
                    // 主页，主页书卡片被当搜索结果充数。真实搜索 ① 最终 URL 仍在 /s/
                    // ② 结果与关键词相关（命中书名含"三体"）。
                    val finalPath = response.request.url.encodedPath
                    val redirectedOffSearch = !finalPath.startsWith("/s/")
                    response.close()

                    // 官网搜索服务故障（2026-08 全站性）：节点本身可达但搜索不可用。
                    // 此时不算"不可用"，也不再走 WebView 兜底（只会浪费 ~36s 后同样失败）。
                    if (html.contains("Search service temporary unavailable", ignoreCase = true)) {
                        return@withContext "站点可达，搜索服务暂不可用（官网故障）"
                    }

                    // 重定向丢路径：搜索请求根本没到搜索服务，主页书卡不算数
                    if (redirectedOffSearch) {
                        return@withContext "不可用（搜索被重定向到主页，非真实镜像）"
                    }

                    val httpResult = if (code !in 200..299) {
                        "不可用（HTTP $code）"
                    } else if (
                        html.contains("Verifying your browser", ignoreCase = true) ||
                        html.contains("Checking your browser", ignoreCase = true) ||
                        html.contains("Just a moment", ignoreCase = true) ||
                        html.contains("solve this captcha", ignoreCase = true) ||
                        html.contains("cpt.lib")
                    ) {
                        "需验证（无法自动搜索）"
                    } else {
                        val books: List<SearchBook> = try {
                            ZLibraryParserManager.parseSearchPage(html, "https://$node", "zlibrary")
                        } catch (e: Exception) {
                            emptyList()
                        }
                        // 关键词相关性校验：真实搜索结果必然含命中书名
                        val relevant = books.isNotEmpty() &&
                            (html.contains("三体") || books.any { it.title.contains("三体") })
                        when {
                            books.isNotEmpty() && relevant -> "可用（搜索到 ${books.size} 本）"
                            books.isNotEmpty() -> "不可用（结果与关键词无关，疑似主页充数）"
                            html.contains("/book/") ||
                            html.contains("z-bookcard") ||
                            html.contains("resItemBox") ||
                            html.contains("book-item") -> "可用（页面可打开）"
                            html.contains("Z-Library", ignoreCase = true) -> "可用（站点可达）"
                            else -> "不可用（无搜索结果）"
                        }
                    }
                    // 仅"不可用 / 需验证"才尝试 WebView（挑战/JS 渲染场景），
                    // "搜索服务暂不可用"直接返回，不再浪费 WebView 超时。
                    if (httpResult.startsWith("不可用") || httpResult.startsWith("需验证")) {
                        val web = ZLibraryWebViewHelper.searchViaWebView(
                            context,
                            node,
                            "三体",
                            cookies = ZLibraryCredentialStorage(context).getCookies()
                        )
                        when {
                            web.books.isNotEmpty() -> "可用（搜索到 ${web.books.size} 本）"
                            web.pageHasZlibMarkers -> "可用（页面可打开）"
                            web.stillChallenged -> "需验证（无法自动搜索）"
                            else -> httpResult
                        }
                    } else {
                        httpResult
                    }
                } catch (e: Exception) {
                    val web = ZLibraryWebViewHelper.searchViaWebView(
                        context,
                        node,
                        "三体",
                        cookies = ZLibraryCredentialStorage(context).getCookies()
                    )
                    if (web.books.isNotEmpty()) {
                        "可用（搜索到 ${web.books.size} 本）"
                    } else if (web.pageHasZlibMarkers) {
                        "可用（页面可打开）"
                    } else {
                        "不可用（${e.message?.take(40) ?: "连接失败"}）"
                    }
                }
            }
            if (testingNode == node) {
                statuses[node] = result
                testingNode = null
            }
        }
    }

    fun selectNode(node: String) {
        ZLibraryNodeManager.selectNode(context, node)
        selectedNode = node
        AppToast.makeText(context, "已切换到 $node", Toast.LENGTH_SHORT).show()
    }

    val defaultNode = ZLibraryNodeManager.DEFAULT_NODE
    val builtinNodes = (listOf(defaultNode) + scrapedNodes).distinct()
    val customNodeList = customNodes.filterNot { it in builtinNodes }

    AnimatedVisibility(
        visible = contentVisible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(260)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) +
            slideInVertically(
                initialOffsetY = { it / 12 },
                animationSpec = tween(280)
            )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("节点管理", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        AppIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "当前节点：$selectedNode",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                AppActionButton(
                                    text = if (scraping) "扒取中…" else "扒取节点",
                                    onClick = {
                                        scope.launch {
                                            scraping = true
                                            val nodes = ZLibraryNodeManager.scrapeNodes(context)
                                            scraping = false
                                            if (nodes.isNotEmpty()) {
                                                foundNodes = nodes
                                                showReplaceDialog = true
                                            } else {
                                                AppToast.makeText(context, "扒取失败，请检查网络后重试", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    variant = AppButtonVariant.Primary,
                                    buttonSize = AppButtonSize.Medium,
                                    icon = Icons.Default.Refresh,
                                    enabled = !scraping,
                                    loading = scraping
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "从 zlib.wwkejishe.top 抓取全部官方入口（优先尝试/备用/中文），检测后点“使用”切换，立即生效。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Text(
                        "节点列表",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(builtinNodes, key = { it }) { node ->
                    val isDefault = node == defaultNode
                    val verifiedLive = node in ZLibraryNodeManager.VERIFIED_LIVE_NODES
                    val label = when {
                        isDefault -> "默认节点"
                        verifiedLive -> "已验证可用"
                        node == "z-lib.sk" -> "硬挑战·留池"
                        else -> "导航站节点"
                    }
                    NodeCard(
                        node = node,
                        label = label,
                        isSelected = selectedNode == node,
                        status = statuses[node] ?: "未测试",
                        isTesting = testingNode == node,
                        canDelete = false,
                        onTest = { testNode(node) },
                        onSelect = { selectNode(node) },
                        onDelete = {
                            ZLibraryNodeManager.removeCustomNode(context, node)
                            customNodes = ZLibraryNodeManager.getCustomNodes(context)
                            selectedNode = ZLibraryNodeManager.getSelectedNode(context)
                            statuses.remove(node)
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "自定义节点",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(customNodeList, key = { it }) { node ->
                    NodeCard(
                        node = node,
                        label = "自定义节点",
                        isSelected = selectedNode == node,
                        status = statuses[node] ?: "未测试",
                        isTesting = testingNode == node,
                        canDelete = true,
                        onTest = { testNode(node) },
                        onSelect = { selectNode(node) },
                        onDelete = {
                            ZLibraryNodeManager.removeCustomNode(context, node)
                            customNodes = ZLibraryNodeManager.getCustomNodes(context)
                            selectedNode = ZLibraryNodeManager.getSelectedNode(context)
                            statuses.remove(node)
                        }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            placeholder = { Text("自定义节点域名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                                                AppActionButton(
                            text = "添加",
                            onClick = {
                                if (ZLibraryNodeManager.addCustomNode(context, customInput)) {
                                    customNodes = ZLibraryNodeManager.getCustomNodes(context)
                                    customInput = ""
                                    AppToast.makeText(context, "已添加自定义节点", Toast.LENGTH_SHORT).show()
                                } else {
                                    AppToast.makeText(context, "请输入有效的域名", Toast.LENGTH_SHORT).show()
                                }
                            },
                            variant = AppButtonVariant.Secondary,
                            buttonSize = AppButtonSize.Small,
                            icon = Icons.Default.Add
                        )
                    }
                }
            }
        }
    }

    if (showReplaceDialog) {
        AlertDialog(
            onDismissRequest = { showReplaceDialog = false },
            title = { Text("找到 ${foundNodes.size} 个节点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("是否合并以下节点？（内置已验证节点会保留）")
                    foundNodes.forEach { node ->
                        Text("•  $node", fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                DialogLiquidGlass(fillMaxSize = false) {
                    AppActionButton(
                        text = "合并",
                        onClick = {
                            ZLibraryNodeManager.saveScrapedNodes(context, foundNodes)
                            scrapedNodes = ZLibraryNodeManager.getScrapedNodes(context)
                            showReplaceDialog = false
                            AppToast.makeText(context, "已合并节点", Toast.LENGTH_SHORT).show()
                        },
                        variant = AppButtonVariant.Primary,
                        buttonSize = AppButtonSize.Small
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun NodeCard(
    node: String,
    label: String,
    isSelected: Boolean,
    status: String,
    isTesting: Boolean,
    canDelete: Boolean,
    onTest: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val isOk = status.startsWith("可用") || status.startsWith("站点可达")
    val isFail = status == "不可用" || status.startsWith("需验证") || status == "初始化失败"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MintPrimary.copy(alpha = 0.15f)
                isOk -> MintPrimary.copy(alpha = 0.10f)
                isFail -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(node, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MintPrimary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "当前使用",
                                    color = MintPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MintPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (isOk) {
                            Icon(Icons.Default.CheckCircle, null, tint = MintPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (isFail) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("$label · $status", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppActionButton(
                    text = "检测",
                    onClick = onTest,
                    enabled = !isTesting,
                    variant = AppButtonVariant.Secondary,
                    buttonSize = AppButtonSize.Small,
                    icon = Icons.Default.PlayArrow
                )
                                AppActionButton(
                    text = if (isSelected) "使用中" else "使用",
                    onClick = onSelect,
                    variant = AppButtonVariant.Secondary,
                    buttonSize = AppButtonSize.Small
                )
                if (canDelete) {
                    Spacer(modifier = Modifier.weight(1f))
                    AppIconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除节点",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
