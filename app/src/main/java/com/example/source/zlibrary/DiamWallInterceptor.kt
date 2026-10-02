package com.example.source.zlibrary

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.net.URLEncoder

class DiamWallInterceptor(private val cookieJar: okhttp3.CookieJar) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var followUpCount = 0
        val urlCounts = mutableMapOf<String, Int>()

        while (followUpCount < 8) {
            val code = response.code
            Log.d("DiamWall", "Response code: $code for ${request.url.host}")

            // Loop guard: DiamWall's 307→503 challenge dance legitimately revisits the
            // same URL (first visit 307, second visit the challenge page, third visit
            // after PoW solve). Only abort when the same URL has been hit 4+ times
            // without progress — the old set-based guard broke out on the second
            // hit, skipping the 503 PoW solving entirely (2026-09-04 1lib.sk fix).
            val hits = urlCounts.merge(request.url.toString(), 1, Int::plus) ?: 1
            if (hits > 3) {
                Log.w("DiamWall", "Redirect/challenge loop detected for ${request.url}; stopping")
                break
            }

            // 1. Handle DiamWall 517 / 403 / 503 / 513 PoW Verification
            //    兜底：个别节点把挑战页以 HTTP 200 + text/html 返回，
            //    按 body 特征（DiamWall / Checking your browser / TOKEN）同样识别并求解，
            //    否则下载器会把挑战页当文件下载、进度条走满后才报“HTML 错误页”。
            val isChallengeCode = code == 517 || code == 403 || code == 503 || code == 513
            val smellsLikeDiamWall = if (!isChallengeCode && code == 200 &&
                (response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true)
            ) {
                try {
                    val peek = response.peekBody(4096).string()
                    peek.contains("diamwall", ignoreCase = true) ||
                        peek.contains("checking your browser", ignoreCase = true) ||
                        peek.contains("verify your browser", ignoreCase = true) ||
                        peek.contains("var TOKEN=")
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            if (isChallengeCode || smellsLikeDiamWall) {
                val bodyString = response.peekBody(1024 * 1024).string()
                
                // Parse DWID JS Cookie
                val dwidMatch = Regex("""document\.cookie="dwid=([^;]+);""").find(bodyString)
                if (dwidMatch != null) {
                    val dwid = dwidMatch.groupValues[1]
                    val cookie = Cookie.Builder()
                        .name("dwid")
                        .value(dwid)
                        .domain(request.url.host)
                        .path("/")
                        .build()
                    cookieJar.saveFromResponse(request.url, listOf(cookie))
                    Log.d("DiamWall", "Challenge cookie received")
                }
                // DiamWall also sets _dwa=0 on the challenge page
                val dwaMatch = Regex("""document\.cookie="_dwa=([^;]+);""").find(bodyString)
                if (dwaMatch != null) {
                    val dwa = dwaMatch.groupValues[1]
                    val cookie = Cookie.Builder()
                        .name("_dwa")
                        .value(dwa)
                        .domain(request.url.host)
                        .path("/")
                        .build()
                    cookieJar.saveFromResponse(request.url, listOf(cookie))
                    Log.d("DiamWall", "Challenge cookie received")
                }
                
                // Parse PoW
                var powHtml = bodyString
                
                // If it contains an iframe, fetch the iframe content
                val iframeMatch = Regex("""<iframe[^>]*src="([^"]+\.well-known/diamwall/load/html/[^"]*)"[^>]*>""").find(bodyString)
                if (iframeMatch != null) {
                    val iframeUrlStr = iframeMatch.groupValues[1]
                    val iframeUrl = request.url.resolve(iframeUrlStr)
                    if (iframeUrl != null) {
                        android.util.Log.d("DiamWall", "Found iframe, fetching: $iframeUrl")
                        val iframeRequest = okhttp3.Request.Builder()
                            .url(iframeUrl)
                            .header("Referer", request.url.toString())
                            .build()
                        val iframeResponse = chain.proceed(iframeRequest)
                        powHtml = iframeResponse.peekBody(1024 * 1024).string()
                        Log.d("DiamWall", "Challenge iframe received")
                        iframeResponse.close()
                    }
                }

                // New DiamWall v2 uses an interactive CAPTCHA (cpt.lib) which cannot be solved
                // by a plain HTTP client. Detect it and stop retrying immediately.
                val isCaptchaChallenge = powHtml.contains("solve this captcha", ignoreCase = true) ||
                        powHtml.contains("Verifying your browser", ignoreCase = true) ||
                        powHtml.contains("chl/v2/captcha") ||
                        powHtml.contains("cpt.lib")
                if (isCaptchaChallenge) {
                    Log.w("DiamWall", "Captcha-based DiamWall challenge detected; HTTP client cannot auto-solve. Use WebView verification.")
                    break
                }

                // New DiamWall PoW: SHA-1 digest of (TOKEN + nonce), checking two bytes:
                //   s1['array'](TOKEN + i)[n1] == byte1 && [n1+1] == byte2
                // where n1 = hex value of the first char of TOKEN.
                val powTokenMatch = Regex("""['"]([0-9A-Fa-f]{40})['"]""").find(bodyString)
                val byte1Match = Regex("""s\[n1\]===(0x[0-9a-fA-F]+)""").find(powHtml)
                val byte2Match = Regex("""s\[n1\+0x1\]===(0x[0-9a-fA-F]+)""").find(powHtml)
                if (powTokenMatch != null && byte1Match != null && byte2Match != null) {
                    val powToken = powTokenMatch.groupValues[1].uppercase()
                    val n1 = Integer.parseInt(powToken.substring(0, 1), 16)
                    val target1 = byte1Match.groupValues[1].substring(2).toInt(16)
                    val target2 = byte2Match.groupValues[1].substring(2).toInt(16)
                    Log.d("DiamWall", "Solving c_token PoW token=$powToken n1=$n1 targets=(${target1.toString(16)},${target2.toString(16)})")

                    val nonce = solveBounded("sha1|$powToken|$n1|$target1|$target2", "SHA-1",
                        { chain.call().isCanceled() }, { nonce -> powToken + nonce }) { digest ->
                        (digest[n1].toInt() and 0xFF) == target1 && (digest[n1 + 1].toInt() and 0xFF) == target2
                    } ?: return response

                    val cookieHost = request.url.host
                    cookieJar.saveFromResponse(
                        request.url,
                        listOf(
                            Cookie.Builder().name("c_token").value(powToken + nonce)
                                .domain(cookieHost).path("/").build(),
                            Cookie.Builder().name("c_time").value("1")
                                .domain(cookieHost).path("/").build()
                        )
                    )
                    Log.d("DiamWall", "c_token solved nonce=$nonce, retrying $cookieHost")
                    response.close()
                    response = chain.proceed(request)
                    followUpCount++
                    continue
                }
                
                if (powHtml.contains("Checking your browser") || powHtml.contains("DiamWall", ignoreCase = true) || powHtml.contains("TOKEN")) {
                    val tokenMatch = Regex("""var TOKEN="([^"]+)"""").find(powHtml)
                    val diffMatch = Regex("""var DIFF=(\d+)""").find(powHtml)
                    
                    if (tokenMatch != null && diffMatch != null) {
                        val token = tokenMatch.groupValues[1]
                        val diff = diffMatch.groupValues[1].toIntOrNull() ?: return response
                        val nonce = solveSha256(token, diff) { chain.call().isCanceled() } ?: return response

                        val originalUrl = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                        val verifyUrl = request.url.newBuilder()
                            .encodedPath("/__ab/verify")
                            .query("t=${URLEncoder.encode(token, "UTF-8")}&n=$nonce&r=${URLEncoder.encode(originalUrl, "UTF-8")}")
                            .build()
                            
                        request = request.newBuilder().url(verifyUrl).build()
                        response.close()
                        response = chain.proceed(request)
                        followUpCount++
                        continue
                    }
                }
            }
            
            // 2. Handle HTTP Redirects (since followRedirects is false)
            if (response.isRedirect) {
                val location = response.header("Location")
                if (location != null) {
                    val newUrl = request.url.resolve(location)
                    if (newUrl != null) {
                        // Check for dwid cookie in redirect response body
                        val bodyString = response.peekBody(1024 * 1024).string()
                        val dwidMatch = Regex("""document\.cookie="dwid=([^;]+);""").find(bodyString)
                        if (dwidMatch != null) {
                            val dwid = dwidMatch.groupValues[1]
                            val cookie = Cookie.Builder()
                                .name("dwid")
                                .value(dwid)
                                .domain(request.url.host)
                                .path("/")
                                .build()
                            cookieJar.saveFromResponse(request.url, listOf(cookie))
                            Log.d("DiamWall", "Redirect cookie received")
                        }
                        
                        request = request.newBuilder().url(newUrl).build()
                        response.close()
                        response = chain.proceed(request)
                        followUpCount++
                        continue
                    }
                }
            }
            
            // If no redirect or PoW handled, break
            break
        }
        
        return response
    }

    companion object {
        private val solveLock = java.util.concurrent.locks.ReentrantLock()
        private val solved = LinkedHashMap<String, Long>()

        internal fun solveSha256(token: String, difficulty: Int, cancelled: () -> Boolean = { false }): Long? {
            if (difficulty !in 0..6) return null
            return solveBounded("sha256|$token|$difficulty", "SHA-256", cancelled, { "$token:$it" }) { hash ->
                (0 until difficulty).all { nibble ->
                    val byte = hash[nibble / 2].toInt() and 0xff
                    if (nibble % 2 == 0) byte ushr 4 == 0 else byte and 15 == 0
                }
            }
        }

        private fun solveBounded(key: String, algorithm: String, cancelled: () -> Boolean,
            input: (Long) -> String, matches: (ByteArray) -> Boolean): Long? {
            if (cancelled()) return null
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            if (!solveLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) return null
            try {
                if (cancelled()) return null
                solved[key]?.let { return it }
                val digest = MessageDigest.getInstance(algorithm)
                for (nonce in 0L until 1_000_000L) {
                    if (nonce % 256L == 0L && (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)) return null
                    if (matches(digest.digest(input(nonce).toByteArray()))) {
                        if (solved.size >= 64) solved.remove(solved.keys.first())
                        solved[key] = nonce
                        return nonce
                    }
                }
                return null
            } finally { solveLock.unlock() }
        }
    }

}
