package com.example.source.zlibrary.network

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ZLibraryDns : Dns {

    companion object {
        private const val TAG = "ZLibDns"
        val INSTANCE = ZLibraryDns()
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes (DoH results only)
        private const val FAIL_CACHE_TTL_MS = 5 * 1000L // Short negative cache avoids amplifying transient failures
        private const val KNOWN_GOOD_TTL_MS = 5 * 60 * 1000L // remember verified IPs for 24h

        /**
         * IP ranges that are never valid for Z-Library nodes. GFW / DNS-hijacking routers
         * commonly poison blocked domains with Meta/Facebook addresses (157.240.x, 173.252.x,
         * 31.13.x, 185.45.x, 104.244.x, ...) or private/reserved addresses.
         * Stored as (base IPv4 int, CIDR prefix length).
         */
        private val BLOCKED_PREFIXES: List<Pair<Int, Int>> = listOf(
            // Private / reserved
            0x00000000.toInt() to 8,   // 0.0.0.0/8
            0x0A000000.toInt() to 8,   // 10.0.0.0/8
            0x64400000.toInt() to 10,  // 100.64.0.0/10
            0x7F000000.toInt() to 8,   // 127.0.0.0/8
            0xA9FE0000.toInt() to 16,  // 169.254.0.0/16
            0xAC100000.toInt() to 12,  // 172.16.0.0/12
            0xC0000000.toInt() to 24,  // 192.0.0.0/24
            0xC0A80000.toInt() to 16,  // 192.168.0.0/16
            0xC6120000.toInt() to 15,  // 198.18.0.0/15
            0xE0000000.toInt() to 4,   // 224.0.0.0/4
            0xF0000000.toInt() to 4,   // 240.0.0.0/4
        )

        private fun isSuspicious(ip: InetAddress): Boolean {
            if (ip !is Inet4Address) return false
            val raw = ip.address ?: return true
            val value = ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
            return BLOCKED_PREFIXES.any { (base, prefix) ->
                (value ushr (32 - prefix)) == (base ushr (32 - prefix))
            }
        }
    }

    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val failCache = ConcurrentHashMap<String, Long>()
    // hostname -> (ip -> last verified timestamp). Rotating/poisoned DNS can hide the real IP
    // on some queries; remembering verified-reachable IPs makes lookups stable across runs.
    private val knownGoodCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    private val resolverPool: ExecutorService = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "ZLibDns-Resolver").apply { isDaemon = true }
    }
    private val systemDnsPool: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ZLibDns-System").apply { isDaemon = true }
    }
    private val probePool: ExecutorService = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "ZLibDns-Probe").apply { isDaemon = true }
    }

    private val dohClient: OkHttpClient by lazy {
        com.example.source.SharedHttpTransport.builder()
            .dns(object : Dns {
                @Volatile private var watchingNetwork = false
    fun watchNetwork(context: android.content.Context) {
        if (watchingNetwork) return
        synchronized(this) {
            if (watchingNetwork) return
            val manager = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            runCatching {
                manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { clearCaches() }
                    override fun onLost(network: android.net.Network) { clearCaches() }
                })
                watchingNetwork = true
            }
        }
    }
    fun clearCaches() { cache.clear(); failCache.clear(); knownGoodCache.clear() }
    override fun lookup(hostname: String): List<InetAddress> {
        if (cache.size + failCache.size > 512 || knownGoodCache.size > 256) clearCaches()
                    return when (hostname) {
                        "dns.alidns.com" -> listOf(InetAddress.getByAddress("dns.alidns.com", byteArrayOf(223.toByte(), 5.toByte(), 5.toByte(), 5.toByte())))
                        "doh.pub" -> listOf(InetAddress.getByAddress("doh.pub", byteArrayOf(1.toByte(), 12.toByte(), 12.toByte(), 12.toByte())))
                        "dns.google" -> listOf(InetAddress.getByAddress("dns.google", byteArrayOf(8.toByte(), 8.toByte(), 8.toByte(), 8.toByte())))
                        "1.1.1.1" -> listOf(InetAddress.getByAddress("1.1.1.1", byteArrayOf(1.toByte(), 1.toByte(), 1.toByte(), 1.toByte())))
                        else -> try { Dns.SYSTEM.lookup(hostname) } catch (e: Exception) { emptyList() }
                    }
                }
            })
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile private var watchingNetwork = false
    fun watchNetwork(context: android.content.Context) {
        if (watchingNetwork) return
        synchronized(this) {
            if (watchingNetwork) return
            val manager = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            runCatching {
                manager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { clearCaches() }
                    override fun onLost(network: android.net.Network) { clearCaches() }
                })
                watchingNetwork = true
            }
        }
    }
    fun clearCaches() { cache.clear(); failCache.clear(); knownGoodCache.clear() }
    override fun lookup(hostname: String): List<InetAddress> {
        if (cache.size + failCache.size > 512 || knownGoodCache.size > 256) clearCaches()
        // Check positive cache (DoH results only)
        val cached = cache[hostname]
        if (cached != null && (System.currentTimeMillis() - cached.second < CACHE_TTL_MS)) {
            Log.d(TAG, "DNS cache hit for $hostname: ${cached.first.map { it.hostAddress }}")
            return cached.first
        }

        // Fast-fail on recently failed lookups (avoids 20s+ stalls during multi-node scans)
        val lastFail = failCache[hostname]
        if (lastFail != null && (System.currentTimeMillis() - lastFail < FAIL_CACHE_TTL_MS)) {
            Log.w(TAG, "DNS negative cache hit for $hostname")
            throw UnknownHostException("Unable to resolve host \"$hostname\" (recent failure)")
        }

        // 1. System DNS is only a HINT: poisoned resolvers return fake (but valid-looking)
        //    IPs for blocked domains, so its answers join the candidate pool instead of
        //    being trusted directly. Preserve system IPv6 candidates for IPv6-only networks;
        //    reachability ordering below still avoids prioritizing an unusable address.
        //    The lookup itself is time-bounded: poisoned resolvers can hang for 10-30s.
        val candidates = LinkedHashSet<InetAddress>()
        val systemDnsFuture = CompletableFuture.supplyAsync({
            try {
                Dns.SYSTEM.lookup(hostname)
                    .filterNot { isSuspicious(it) }
            } catch (e: Exception) {
                Log.w(TAG, "System DNS lookup failed for $hostname: ${e.message}")
                emptyList()
            }
        }, systemDnsPool)
        try {
            val systemAddresses = systemDnsFuture.get(2000, TimeUnit.MILLISECONDS)
            candidates.addAll(systemAddresses)
            if (systemAddresses.isEmpty()) {
                Log.w(TAG, "System DNS returned only suspicious/unusable addresses for $hostname, falling back to DoH")
            } else {
                Log.d(TAG, "System DNS resolved $hostname -> ${systemAddresses.map { it.hostAddress }}")
            }
        } catch (e: TimeoutException) {
            Log.w(TAG, "System DNS lookup timed out for $hostname; using DoH only")
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostname: ${e.message}")
        }

        // 2. Query ALL DoH providers in parallel (AliDNS / DNSPod / Cloudflare / Google).
        //    In poisoned networks the CN-friendly resolvers return rotating FAKE IPs, while
        //    Cloudflare/Google usually return the real ones — so we aggregate every answer
        //    instead of trusting the first provider that responds.
        candidates.addAll(resolveViaDoH(hostname))

        // 3. Re-add previously verified-reachable IPs (24h memory). This covers the case where
        //    every DoH provider times out or returns fakes on a particular query.
        val now = System.currentTimeMillis()
        knownGoodCache[hostname]?.let { known ->
            val stale = mutableListOf<String>()
            known.forEach { (ipStr, lastSeen) ->
                if (now - lastSeen < KNOWN_GOOD_TTL_MS) {
                    parseIpToInetAddress(hostname, ipStr)?.let { candidates.add(it) }
                } else {
                    stale.add(ipStr)
                }
            }
            stale.forEach { known.remove(it) }
        }

        if (candidates.isEmpty()) {
            failCache[hostname] = System.currentTimeMillis()
            throw UnknownHostException("Unable to resolve host \"$hostname\" via System DNS or DoH")
        }

        // 4. TCP-probe candidates and put reachable IPs first. OkHttp connects serially,
        //    so without this, a fake-but-valid-looking IP at the front causes "connect timeout".
        val (sorted, reachable) = probeAndSort(candidates.toList())
        val known = knownGoodCache.getOrPut(hostname) { ConcurrentHashMap() }
        reachable.forEach { known[it] = now }
        known.entries.removeIf { now - it.value > KNOWN_GOOD_TTL_MS }
        Log.d(TAG, "Final resolution for $hostname -> ${sorted.map { it.hostAddress }}")
        cache[hostname] = Pair(sorted, System.currentTimeMillis())
        return sorted
    }

    private fun parseIpToInetAddress(hostname: String, ipString: String): InetAddress? {
        return try {
            val parts = ipString.trim().split(".")
            if (parts.size == 4) {
                val bytes = ByteArray(4) { i -> parts[i].toInt().toByte() }
                InetAddress.getByAddress(hostname, bytes)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        val result = ConcurrentHashMap<String, InetAddress>()
        val providers = listOf(
            "AliDNS" to { queryDohProvider(hostname, "https://dns.alidns.com/resolve?name=$hostname&type=1", "application/dns-json") },
            "DNSPod" to { queryDohProvider(hostname, "https://doh.pub/dns-query?name=$hostname&type=A", "application/dns-json") },
            "Cloudflare" to { queryDohProvider(hostname, "https://1.1.1.1/dns-query?name=$hostname&type=A", "application/dns-json") },
            "Google" to { queryDohProvider(hostname, "https://dns.google/resolve?name=$hostname&type=A", "application/dns-json") }
        )

        val futures = providers.map { (name, query) ->
            CompletableFuture.supplyAsync(
                {
                    try {
                        query.invoke()
                    } catch (e: Exception) {
                        Log.w(TAG, "$name DoH failed for $hostname: ${e.message}")
                        emptyList()
                    }
                },
                resolverPool
            )
        }

        val deadline = System.currentTimeMillis() + 4000
        futures.forEach { future ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return@forEach
            try {
                future.get(remaining, TimeUnit.MILLISECONDS).forEach { addr ->
                    addr.hostAddress?.let { result.putIfAbsent(it, addr) }
                }
            } catch (e: TimeoutException) {
                Log.w(TAG, "DoH query timed out for $hostname")
            } catch (e: Exception) {
                Log.w(TAG, "DoH query failed for $hostname: ${e.message}")
            }
        }
        return result.values.toList()
    }

    private fun queryDohProvider(hostname: String, urlStr: String, accept: String): List<InetAddress> {
        val url = urlStr.toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .get()
            .build()
        val response = dohClient.newCall(request).execute()
        val out = mutableListOf<InetAddress>()
        if (response.isSuccessful) {
            val jsonStr = response.body?.string() ?: ""
            val json = JSONObject(jsonStr)
            if (json.has("Answer")) {
                val answerArray = json.getJSONArray("Answer")
                for (i in 0 until answerArray.length()) {
                    val item = answerArray.getJSONObject(i)
                    if (item.optInt("type", 0) == 1) {
                        val ip = item.getString("data")
                        parseIpToInetAddress(hostname, ip)?.let { addr ->
                            if (!isSuspicious(addr)) out.add(addr)
                        }
                    }
                }
            }
        }
        response.close()
        return out
    }

    private fun probeAndSort(candidates: List<InetAddress>): Pair<List<InetAddress>, Set<String>> {
        if (candidates.size <= 1) return Pair(candidates, emptySet())
        val futures = candidates.map { ip ->
            CompletableFuture.supplyAsync({ tcpReachable(ip) }, probePool)
        }
        val ok = mutableListOf<InetAddress>()
        val fail = mutableListOf<InetAddress>()
        val deadline = System.currentTimeMillis() + 2500
        candidates.forEachIndexed { index, ip ->
            val remaining = deadline - System.currentTimeMillis()
            val reachable = if (remaining <= 0) {
                false
            } else {
                try {
                    futures[index].get(remaining, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    false
                }
            }
            if (reachable) ok.add(ip) else fail.add(ip)
        }
        return Pair(ok + fail, ok.mapNotNull { it.hostAddress }.toSet())
    }

    private fun tcpReachable(ip: InetAddress): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 443), 1500)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
