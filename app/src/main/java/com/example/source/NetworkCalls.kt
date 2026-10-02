package com.example.source

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

/** Parent cancellation closes blocked socket reads; response close removes the hook. */
internal suspend fun Call.executeCancellable(): Response = executeWithCancellation(currentCoroutineContext()[Job])

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal fun Call.executeWithCancellation(job: Job?):Response {
    job?.ensureActive()
    val call=this
    val handle=job?.invokeOnCompletion(onCancelling=true,invokeImmediately=true) { cause ->
        if(cause!=null) call.cancel()
    }
    try {
        val response=execute()
        try { job?.ensureActive() } catch(e:Exception) { response.close(); throw e }
        val body=response.body
        if(body==null) { handle?.dispose(); return response }
        val source=object:ForwardingSource(body.source()) {
            override fun close() { try { super.close() } finally { handle?.dispose() } }
        }.buffer()
        return response.newBuilder().body(object:ResponseBody() {
            override fun contentType()=body.contentType()
            override fun contentLength()=body.contentLength()
            override fun source()=source
        }).build()
    } catch(e:Exception) { handle?.dispose(); call.cancel(); job?.ensureActive(); throw e }
}

/** Share pool and dispatcher, keep per-source TLS, cookies and proxy configuration. */
internal object SharedHttpTransport {
    private val pool=okhttp3.ConnectionPool(8,5,java.util.concurrent.TimeUnit.MINUTES)
    private val dispatcher=okhttp3.Dispatcher().apply { maxRequests=32; maxRequestsPerHost=4 }
    fun builder():okhttp3.OkHttpClient.Builder=okhttp3.OkHttpClient.Builder().connectionPool(pool).dispatcher(dispatcher)
}

internal object SourceNetworkPolicy {
    private fun privateAddress(address: java.net.InetAddress): Boolean {
        val raw=address.address
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
            (raw.size==16 && (raw[0].toInt() and 0xfe)==0xfc)
    }
    fun checkAddress(host: String, address: java.net.InetAddress, allowedPrivateHost: String?) {
        require(!privateAddress(address) || host.lowercase()==allowedPrivateHost) { "书源跳转或 DNS 指向未配置的内网地址" }
    }
    fun check(url:okhttp3.HttpUrl,allowedPrivateHost:String?) {
        require(url.scheme=="https" || url.scheme=="http") { "仅支持 HTTP/HTTPS 书源" }
        val host=url.host.lowercase()
        val local=host=="localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host=="::1" || host=="0.0.0.0" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        val ipv6Private=if(host.contains(':')) runCatching { privateAddress(java.net.InetAddress.getByName(host)) }.getOrDefault(true) else false
        require(!(local || ipv6Private) || host==allowedPrivateHost) { "书源规则尝试访问未配置的内网地址" }
    }
}
