package com.example.source.js

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 部分源站图床域名会被本地 DNS 解析到不可用节点（2026-09 实测：*.cdndm5.com 在
 * 某些网络下全域解析到 TLS 握手即被 RST 的 IP，而同 CDN 其它子域的边缘节点能以
 * 原 SNI/Host 正常服务图片，用 --resolve 指到该节点后整章 29 页全部 200）。
 *
 * 这里把已验证可用的边缘 IP 追加在系统 DNS 结果之后：系统地址连接失败时
 * OkHttp 会自动换下一个候选地址重连；系统 DNS 正常的网络行为完全不变。
 */
object SourceDns {

    /** 域名后缀 -> 已验证可用的候选边缘 IP。 */
    private val pinnedCandidates: List<Pair<String, List<InetAddress>>> = listOf(
        "cdndm5.com" to ips("222.200.254.75"),
    )

    private const val CACHE_TTL_MS = 5 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<InetAddress>>>()

    /** 给 OkHttp 客户端挂上带兜底候选的 DNS。 */
    fun dns(base: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase().trim().trimEnd('.')
            val extra = pinnedCandidates
                .firstOrNull { (zone, _) -> host == zone || host.endsWith(".$zone") }
                ?.second
                .orEmpty()
            if (extra.isEmpty()) return base.lookup(hostname)
            synchronized(cache) {
                cache[host]?.takeIf { System.currentTimeMillis() - it.first < CACHE_TTL_MS }
                    ?.let { return it.second }
            }
            val system = runCatching { base.lookup(host) }.getOrElse { emptyList() }
            val merged = system + extra.filter { pinned -> system.none { it.address == pinned.address } }
            if (merged.isEmpty()) throw UnknownHostException(host)
            synchronized(cache) { cache[host] = System.currentTimeMillis() to merged }
            return merged
        }
    }

    private fun ips(vararg addresses: String): List<InetAddress> = addresses.mapNotNull {
        runCatching {
            val parts = it.split('.')
            require(parts.size == 4)
            InetAddress.getByAddress(parts.map { p -> p.toInt().toByte() }.toByteArray())
        }.getOrNull()
    }
}
