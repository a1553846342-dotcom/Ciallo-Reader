package com.example.library

import android.content.Context
import com.example.source.zlibrary.network.ZLibraryDns
import com.example.source.zlibrary.network.ZLibraryHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Z-Library 节点管理：
 * - 默认节点固定为 1lib.sk（用户实测唯一正确官网，账号可登录、有免费下载额度；
 *   走 DiamWall 挑战，App 的 PoW 拦截器 + WebView 兜底自动过）；
 * - 节点从 https://zlib.wwkejishe.top/ 扒取（#official-urls 表格全部官方入口，
 *   更新勤、含假站黑名单——2026-09-04 用户指定）；
 * - 支持用户自定义节点；
 * - 选择的节点写入 SharedPreferences，并在启动时恢复。
 */
object ZLibraryNodeManager {
    // 2026-09-04 实测校准：z-lib.li / z-lib.cc 等均为仿冒站（无下载功能或流程不符）；
    // 1lib.sk 为唯一正确官网（DiamWall 挑战可自动过，rpc.php/eapi/搜索全链路已验证）。
    const val DEFAULT_NODE = "1lib.sk"
    const val SCRAPE_URL = "https://zlib.wwkejishe.top/"

    private const val PREFS_NAME = "zlib_node_manager"
    private const val KEY_SCRAPED_NODES = "scraped_nodes"
    private const val KEY_CUSTOM_NODES = "custom_nodes"
    private const val KEY_SELECTED_NODE = "selected_node"

    /** 2026-09-04 晚 Edge 登录态逐个实测的活节点（首页真实 + 搜索出书卡片），
     *  节点管理页显示"已验证可用"标签的依据。 */
    val VERIFIED_LIVE_NODES = setOf(
        "1lib.sk",
        "z-lib.by",
        "z-library.sk",
        "zh.z-lib.by",
        "zh.z-library.sk",
        "en.z-lib.by"
    )

    /** 首次使用即内置的候选节点，扒取成功后可替换刷新（2026-09-04 晚 Edge
     *  全核实测，含登录态验证）。
     *  六活节点（CN 网络逐一实测搜索出书卡片）：1lib.sk（唯一正确官网）→
     *  z-lib.by（无挑战直通，全链路含下载验证）→ z-library.sk → zh.z-lib.by
     *  （中文）→ zh.z-library.sk（中文）→ en.z-lib.by → z-lib.sk（DiamWall 硬
     *  挑战留池）→ CF 域（海外/代理兜底）。
     *  退役：z-lib.is（指纹网关 fp=-7）、z-library.se（ww38 停放页）、z-lib.id
     *  （停放页，官方发布页证实钓鱼）、z-lib.li / z-lib.cc（仿冒站）、zlib.bz /
     *  101k 系 / zlib.ch / zlib.re / zh.z-lib.rest（死透或 301 归拢枢纽）。 */
    val INITIAL_SCRAPED_NODES = listOf(
        "1lib.sk",
        "z-lib.by",
        "z-library.sk",
        "zh.z-lib.by",
        "zh.z-library.sk",
        "en.z-lib.by",
        "z-lib.sk",
        "z-library.co",
        "z-library.net",
        "zlib-official.com",
        "zlibrary-global.com",
        "z-lib.my",
        "z-lib.ad"
    )

    /** 已退役域名（2026-09-04 22:10 Edge 全核实测：死透 / 归拢枢纽已灭 / 仿冒站 /
     *  停放页 / 指纹网关拒绝）：老用户存储的候选列表里把它们过滤掉——节点管理页
     *  不再显示死节点占位，扫描池也不为 GFW 黑洞域名白等连接超时。 */
    private val RETIRED_NODES = setOf(
        "z-lib.id",     // 停放落地页（"Z-Library" 文案 59 处但无任何站点结构）
        "z-lib.li",     // 仿冒站
        "z-lib.cc",     // 仿冒站
        "z-lib.is",     // 指纹网关 fp=-7 拒绝（真 Edge 20s 亦不可过，2026-09-04 22:10）
        "z-library.se", // 已沦为 ww28/ww38 域名停放页
        "zlib.bz",      // 归拢枢纽，已死
        "zh.z-lib.rest",// 301 → zlib.bz（死枢纽），2026-09-04 23:20 curl 实测
        "zh.101k.by", "tw.101k.by", "zh.101z.by",
        "zlib.ch", "zlib.re",
        "zh.z-library.by"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getScrapedNodes(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SCRAPED_NODES, null) ?: return INITIAL_SCRAPED_NODES
        val nodes = raw.split("\n").map { it.trim() }
            .filter { it.isNotBlank() && it !in RETIRED_NODES }
        return nodes.ifEmpty { INITIAL_SCRAPED_NODES }
    }

    fun saveScrapedNodes(context: Context, nodes: List<String>) {
        // 合并而非替换（用户要求"作为内置节点的补充"）：六个实测活节点始终保留，
        // 新扒取的节点追加——导航站某次漏报/部分失败时不会丢掉已验证的主力节点。
        val cleaned = (INITIAL_SCRAPED_NODES + nodes.mapNotNull { cleanNode(it) }).distinct()
        prefs(context).edit().putString(KEY_SCRAPED_NODES, cleaned.joinToString("\n")).apply()
    }

    fun getCustomNodes(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_NODES, null) ?: return emptyList()
        return raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun addCustomNode(context: Context, rawNode: String): Boolean {
        val clean = cleanNode(rawNode) ?: return false
        val list = (getCustomNodes(context) + clean).distinct()
        prefs(context).edit().putString(KEY_CUSTOM_NODES, list.joinToString("\n")).apply()
        return true
    }

    fun removeCustomNode(context: Context, node: String) {
        val list = getCustomNodes(context).filterNot { it == node }
        prefs(context).edit().putString(KEY_CUSTOM_NODES, list.joinToString("\n")).apply()
        if (getSelectedNode(context) == node) {
            selectNode(context, DEFAULT_NODE)
        }
    }

    fun getSelectedNode(context: Context): String {
        return prefs(context).getString(KEY_SELECTED_NODE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NODE
    }

    fun selectNode(context: Context, rawNode: String) {
        val clean = cleanNode(rawNode) ?: return
        prefs(context).edit().putString(KEY_SELECTED_NODE, clean).apply()
        ZLibraryNodeConfig.domain = clean
    }

    /** 启动时恢复上次选择的节点。
     *  迁移（2026-09-04）：旧默认 zh.101k.by 已整站 301 → zlib.bz，存的是它就
     *  就地升级为新默认，避免老用户启动即落在重定向域上（搜索全卡/假结果）。 */
    fun restoreSelection(context: Context) {
        val stored = prefs(context).getString(KEY_SELECTED_NODE, null)?.trim()
        if (stored != null && MIGRATED_NODES.containsKey(stored)) {
            val migrated = MIGRATED_NODES[stored]!!
            prefs(context).edit().putString(KEY_SELECTED_NODE, migrated).apply()
            ZLibraryNodeConfig.domain = migrated
        } else {
            ZLibraryNodeConfig.domain = getSelectedNode(context)
        }
    }

    /** 节点迁移表：旧节点 → 现节点（启动恢复时自动升级）。
     *  2026-09-04 23:30 校准：z-lib.sk / z-library.sk / z-lib.by 实测复活
     *  （z-lib.by 无挑战直通，z-library.sk 全链路可用），从迁移表移除——
     *  只在它们上面存的用户不再被强制搬家。
     *  仍迁移：z-library.co 走 CF Turnstile（App 解不了）、z-lib.li / z-lib.cc 为
     *  仿冒站（无下载功能）、其余死透/归拢域——全部迁到 1lib.sk。 */
    private val MIGRATED_NODES: Map<String, String> = mapOf(
        "zh.101k.by" to DEFAULT_NODE,
        "tw.101k.by" to DEFAULT_NODE,
        "zh.101z.by" to DEFAULT_NODE,
        "zlib.bz" to DEFAULT_NODE,
        "zh.z-library.by" to DEFAULT_NODE,
        "zlib.ch" to DEFAULT_NODE,
        "zlib.re" to DEFAULT_NODE,
        "z-lib.li" to DEFAULT_NODE,
        "z-lib.cc" to DEFAULT_NODE,
        "z-library.co" to DEFAULT_NODE,
        "z-library.net" to DEFAULT_NODE,
        "zlib-official.com" to DEFAULT_NODE,
        "zlibrary-global.com" to DEFAULT_NODE,
        "z-lib.id" to DEFAULT_NODE,
        "z-lib.is" to DEFAULT_NODE,
        "z-library.se" to DEFAULT_NODE,
        "z-lib.ad" to DEFAULT_NODE,
        "z-lib.my" to DEFAULT_NODE
    )

    /** 从 https://zlib.wwkejishe.top/ 扒取 #official-urls 表格内的全部官方入口
     *  （z-lib.by / z-library.sk / zh.zlib.li 等优先尝试+备用+中文入口，2026-09-04
     *  实测解析验证）。站点长期维护（更新日期标注在页面），是当前最可靠的
     *  实时节点来源。 */
    suspend fun scrapeNodes(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            val client = com.example.source.SharedHttpTransport.builder()
                .dns(ZLibraryDns.INSTANCE)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder()
                .url(SCRAPE_URL)
                .header("User-Agent", ZLibraryHttpClient.DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", ZLibraryHttpClient.DEFAULT_ACCEPT_LANG)
                .build()
            val response = client.newCall(request).execute()
            val html = if (response.isSuccessful) response.body?.string() else null
            response.close()
            if (html.isNullOrBlank()) return@withContext emptyList()

            val doc = Jsoup.parse(html)
            // #official-urls 区块的入口表格：每行第一格是 <a href="https://域名/">
            val scraped = doc.select("#official-urls table a[href]")
                .eachAttr("href")
                .mapNotNull { cleanNode(it) }
                .distinct()
            // 兜底：站点改版导致表格选择器失灵时，退化为抓整页 zlib 系域名
            val fallback = if (scraped.isEmpty()) {
                val domainRegex = Regex(
                    """https?://((?:[a-z0-9-]+\.)*(?:z-lib|zlib|z-library|zlibrary|1lib)[a-z0-9.-]+)""",
                    RegexOption.IGNORE_CASE
                )
                domainRegex.findAll(html)
                    .map { it.groupValues[1].lowercase() }
                    .filter { it.contains('.') && !it.endsWith(".png") && !it.endsWith(".js") }
                    .distinct()
                    .toList()
            } else scraped
            // 退役域名过滤（zh.z-lib.rest → 301 死枢纽 zlib.bz 等）
            fallback.filter { it !in RETIRED_NODES }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cleanNode(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        var host = trimmed.substringAfter("://", trimmed)
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.trim().lowercase().ifBlank { null }
    }
}
