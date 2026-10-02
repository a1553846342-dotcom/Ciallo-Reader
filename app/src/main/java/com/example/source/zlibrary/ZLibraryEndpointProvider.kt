package com.example.source.zlibrary

import android.content.Context
import com.example.library.ZLibraryNodeConfig
import com.example.library.ZLibraryNodeManager
import com.example.source.zlibrary.network.ZLibraryDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.example.source.executeCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ZLibraryEndpointProvider(
    private val context: Context,
    private val credentialStorage: ZLibraryCredentialStorage = ZLibraryCredentialStorage(context),
    private val healthChecker: EndpointHealthChecker = EndpointHealthChecker(context),
    private val remoteProvider: RemoteEndpointProvider = RemoteEndpointProvider(context)
) {
    companion object {
        private const val PREFS_NAME = "zlib_domain_cache"
        private const val KEY_CACHE_DOMAIN = "cached_domain"
        private const val KEY_CACHE_TIMESTAMP = "cached_timestamp"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

        // 节点容灾体检：选中/缓存节点定期快速探测，死了自动换活节点（2026-09-04）
        private const val PROBE_TTL_MS = 60 * 1000L // 探测结果 60s 内复用，常规搜索不重复探测
        private const val PROBE_TIMEOUT_S = 4L      // 单次探测 4s 超时，不拖慢主链路

        // Preset high-availability mirror domains（2026-09-04 晚 Edge 全核实测，登录态）：
        // 六个活节点（CN 网络逐一实测：首页真实 + "harry potter" 搜索 51~102 卡片）：
        // - 1lib.sk：用户指定唯一正确官网（DiamWall 透明 PoW 自动过；账号登录/下载全链路已验证）；
        // - z-lib.by：导航站"优先尝试"第一位，无任何挑战直通（登录→搜索→对应书→
        //   EPUB 下载→内容无乱码，全链路实测通过）；
        // - z-library.sk：无挑战直通；
        // - zh.z-lib.by：中文界面，登录态与 z-lib.by 共享；
        // - zh.z-library.sk：中文界面，DiamWall 自动过；
        // - en.z-lib.by：英文，与 z-lib.by 同基础设施。
        // 域名级冗余对 CN 用户有效（GFW 按域封锁）；基础设施级共 3 个独立后端。
        // 其余：z-lib.sk（DiamWall 硬挑战，留池）；CF 域（z-library.co 等，海外/代理兜底）。
        // 退役：z-lib.is / z-library.se / z-lib.id / z-lib.li / z-lib.cc / zlib.bz /
        // 101k 系 / zlib.ch / zlib.re / zh.z-lib.rest；当前网络不可达（不入预置，
        // 仍可被导航站扒取到）：zh.zlib.li / zh.z-lib.gd / zh.intcn.online。
        val PRESET_DOMAINS = listOf(
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
        private val probeSlots = Semaphore(3)
        val FALLBACK_DOMAINS = PRESET_DOMAINS
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 节点快速可达性体检（容灾第一步）----
    // 只判断“TCP/TLS/HTTP 能通”：被墙（SNI 阻断/RST）、DNS 污染（ZLibraryDns
    // 已过滤 Meta 假 IP）、站点挂掉 → 探不通；而 DiamWall/Cloudflare 挑战码
    // （403/503/513）也算“活着”——挑战随后由 PoW 拦截器 / WebView 兜底自动过，
    // 不构成换节点的理由。结果带 60s TTL，常规搜索不额外付探测延迟。
    private val quickCheckClient = com.example.source.SharedHttpTransport.builder()
        .dns(ZLibraryDns.INSTANCE)
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastProbe: Triple<String, Boolean, Long>? = null

    private suspend fun isNodeReachable(domain: String): Boolean {
        if (domain.isBlank()) return false
        val now = System.currentTimeMillis()
        lastProbe?.let { (d, ok, at) ->
            if (d == domain && now - at < PROBE_TTL_MS) return ok
        }
        val ok = try {
            val request = Request.Builder().url("https://$domain/").head().build()
            quickCheckClient.newCall(request).executeCancellable().use { it.code > 0 }
        } catch(e: Exception) { currentCoroutineContext().ensureActive(); false }
        lastProbe = Triple(domain, ok, now)
        return ok
    }

    /**
     * 扫描候选池选活节点：远程门户动态发现（官方披露域名，自动跟上站点归拢/
     * 迁移）∪ 节点管理候选（官网/备用入口/用户自加）∪ 预置域名。并行健康检查
     * ——最多 3 个并发，首个搜索可用节点立即返回，整轮最多 25 秒；优先取“搜索可用”的节点（可达但搜索故障的只作
     * 下载兜底，否则搜到的全是主页推荐书）。全挂返回 null。
     */
    private suspend fun scanForLiveNode(): String? {
        val remoteDomains = try {
            withTimeoutOrNull(4_000) { remoteProvider.fetchLatestEndpoints() }
                ?.sortedByDescending { it.priority }
                ?.map { it.url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
                ?.filter { it.isNotBlank() }
        } catch(e: Exception) { currentCoroutineContext().ensureActive(); null }.orEmpty()
        val candidates = LinkedHashMap<String, Boolean>()
        remoteDomains.forEach { candidates.putIfAbsent(it, true) }
        runCatching { ZLibraryNodeManager.getScrapedNodes(context) }
            .getOrDefault(emptyList())
            .forEach { candidates.putIfAbsent(it, true) }
        runCatching { ZLibraryNodeManager.getCustomNodes(context) }
            .getOrDefault(emptyList())
            .forEach { candidates.putIfAbsent(it, true) }
        PRESET_DOMAINS.forEach { candidates.putIfAbsent(it, false) }
        val candidateList = candidates.keys.take(32)
        if (candidateList.isEmpty()) return null
        return coroutineScope {
            val results = Channel<Pair<String, EndpointHealthResult>>(Channel.UNLIMITED)
            val jobs = candidateList.map { candidate ->
                launch(Dispatchers.IO) {
                    val health = probeSlots.withPermit { healthChecker.checkHealth(candidate) }
                    results.send(candidate to health)
                }
            }
            var reachable: String? = null
            try {
                withTimeoutOrNull(25_000) {
                    repeat(candidateList.size) {
                        val (candidate, health) = results.receive()
                        if (health.isAvailable && health.searchAvailable) return@withTimeoutOrNull candidate
                        if (health.isAvailable && reachable == null) reachable = candidate
                    }
                    reachable
                } ?: reachable
            } finally { jobs.forEach { it.cancel() }; results.close() }

        }
    }

    /** 容灾/扫描选中活节点后的固化：写 24h 缓存、内存选中值跟随（本进程内登录/
     *  搜索/下载立即用新节点，含书库隐藏 WebView 会话）、预热体检缓存避免下一
     *  次调用重复探测。不覆写节点管理页的持久化选择——临时被墙的网络恢复后
     *  重启 App 即回到用户手选节点。 */
    private fun adoptLiveNode(domain: String) {
        saveCache(domain)
        runCatching { ZLibraryNodeConfig.domain = domain }
        lastProbe = Triple(domain, true, System.currentTimeMillis())
    }

    /** 选中/自定义节点体检失败后的自动容灾：扫描换活节点并固化。
     *  返回 null = 全池不可达（保持原节点，交给 WebView Chromium 指纹/系统代理兜底）。 */
    private suspend fun failoverFrom(deadDomain: String): String? {
        val winner = scanForLiveNode() ?: return null
        if (winner == deadDomain) {
            // 快速体检误报（HEAD 被挡但全量健康检查通过）：预热缓存避免反复重扫
            lastProbe = Triple(deadDomain, true, System.currentTimeMillis())
            return null
        }
        adoptLiveNode(winner)
        return winner
    }

    suspend fun getEndpoint(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        // 0. 用户在“节点管理”里选中的节点优先（立即生效，不被缓存/扫描结果覆盖）。
        //    容灾（2026-09-04）：选中节点先做 4s 快速体检——挂了/被墙时不再原地
        //    卡死，自动扫描候选池换活节点并固化，登录/搜索/下载全部跟随新节点，
        //    用户零操作。全池探不通（无 VPN 整段被墙等极端情况）仍返回选中节点
        //    本身，交给 WebView（Chromium TLS 指纹 + 系统代理）兜底再试。
        val selectedNode = runCatching { ZLibraryNodeConfig.domain }
            .getOrDefault("")
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removeSuffix("/")
        if (!selectedNode.isNullOrBlank() && !forceRefresh) {
            if (isNodeReachable(selectedNode)) {
                return@withContext selectedNode
            }
            failoverFrom(selectedNode)?.let { return@withContext it }
            return@withContext selectedNode
        }

        // 1. User custom config（同样体检：死了不再原地卡死，落回扫描流程自动换）
        val customDomain = prefs.getString(KEY_CUSTOM_ENDPOINT, null)?.trim()
        if (!customDomain.isNullOrBlank() && !forceRefresh) {
            val cleanCustom = customDomain.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            if (isNodeReachable(cleanCustom)) return@withContext cleanCustom
        }

        // 2. History success cache（24h 内验证过的节点直接复用，省一次扫描往返；
        //    体检失败说明缓存节点已死/被墙，作废重扫，绝不把死节点再交出去）
        if (!forceRefresh) {
            val cached = prefs.getString(KEY_CACHE_DOMAIN, null)
            val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
            if (!cached.isNullOrBlank() && (System.currentTimeMillis() - timestamp < CACHE_DURATION_MS)) {
                if (isNodeReachable(cached)) return@withContext cached
                invalidateCache()
            }
        }

        // 3. 扫描候选池选活节点并固化（forceScan 强制重扫、缓存过期、容灾均走这里）。
        //    注意：远程发现的高优先级域名不再直接采信（旧逻辑曾把被墙的远程冠军
        //    原样返回，forceScan 也救不回来）——一律过健康检查才算数。
        scanForLiveNode()?.let {
            adoptLiveNode(it)
            return@withContext it
        }

        // 4. Fallback to first preset domain if all fail
        PRESET_DOMAINS.first()
    }

    fun saveCache(domain: String) {
        prefs.edit()
            .putString(KEY_CACHE_DOMAIN, domain)
            .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
            .commit()
    }

    fun setCustomEndpoint(domain: String?) {
        val cleanDomain = domain?.trim()?.removePrefix("https://")?.removePrefix("http://")?.removeSuffix("/")
        prefs.edit()
            .putString(KEY_CUSTOM_ENDPOINT, cleanDomain)
            .commit()
        if (!cleanDomain.isNullOrBlank()) {
            credentialStorage.saveCredentials(
                userId = credentialStorage.getUserId(),
                userKey = credentialStorage.getUserKey(),
                domain = cleanDomain,
                cookies = credentialStorage.getCookies()
            )
        }
    }

    fun getCustomEndpoint(): String? = prefs.getString(KEY_CUSTOM_ENDPOINT, null)

    fun invalidateCache() {
        prefs.edit().remove(KEY_CACHE_DOMAIN).remove(KEY_CACHE_TIMESTAMP).commit()
    }

    suspend fun diagnoseEndpoint(): Map<String, Any> = withContext(Dispatchers.IO) {
        val endpoint = getEndpoint(forceRefresh = true)
        val health = healthChecker.checkHealth(endpoint)
        mapOf(
            "endpoint" to endpoint,
            "dnsOk" to health.dnsOk,
            "tlsOk" to health.tlsOk,
            "httpCode" to health.httpCode,
            "isAvailable" to health.isAvailable,
            "isCloudflare" to health.isCloudflare,
            "rtt" to health.rtt,
            "error" to (health.error ?: "None")
        )
    }

    suspend fun diagnoseAllEndpoints(): List<EndpointHealthResult> = withContext(Dispatchers.IO) {
        val domains = (listOfNotNull(getCustomEndpoint()) + PRESET_DOMAINS).distinct()
        domains.map { domain ->
            healthChecker.checkHealth(domain)
        }
    }
}
