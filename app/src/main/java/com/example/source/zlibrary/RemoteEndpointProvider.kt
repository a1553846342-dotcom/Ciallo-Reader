package com.example.source.zlibrary

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程 Z-Library 节点动态发现。
 *
 * 第十三轮：官方把零散镜像持续 301 归拢（2026-09 实测 zh.101k.by / zlib.ch /
 * zlib.re / zh.z-library.by 全部 → zlib.bz），写死的节点清单注定过期。改为从
 * **官方访问入口页 z-lib.app** 抓取它披露的官方域名列表（纯静态页、不挂挑战、
 * 无需登录）——官方把用户引导到哪个域，这里就能发现哪个域，"站点变动自动跟上"。
 *
 * 2026-09-04 晚（回应"内置节点全部无效怎么办"）：单一来源一被墙，发现链就断。
 * 扩为**四源并行、互为备份**——任一源成功即可贡献域名，发现能力不再绑定任何
 * 单个站点的存亡。官方换域 → 门户/频道更新 → 下一次扫描自动跟上，内置清单
 * 过期不再是死局。
 *
 * 原远程 JSON 配置（example.com 占位）从未启用，本类替代其职责；
 * JSON 缓存格式保留兼容（旧缓存 key 独立，不冲突）。
 * 第十一轮瘦身沿用：org.json 手写解析。
 */
class RemoteEndpointProvider(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("zlib_remote_config", Context.MODE_PRIVATE)
    private val okHttpClient = com.example.source.SharedHttpTransport.builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 六个发现源（并行抓取）：
     *  1. zlib.wwkejishe.top  中文导航站（用户指定，2026-08-12 更新；#official-urls
     *                          表格维护全部官方入口 + 假站黑名单，发现价值最高：
     *                          2026-09-04 实测它披露的 z-lib.by 完全可用）；
     *  2. z-lib.app           官方门户，披露全部官方镜像域（2026-09-04 CN 实测可达 200）；
     *  3. go-to-zlibrary.com  官方跳转入口（CN 暂被墙 000，海外/挂系统代理网络可达）；
     *  4. z.wwwnav.com        第三方中文导航页（CN 可达性最高；节点管理页同源）；
     *  5. t.me/s/Zlib_IO      官方 TG 频道公开预览（新域公告第一现场；CN 被墙，
     *                          用户挂系统代理时可达）；
     *  6. znew.pages.dev      发布页"永久收藏页"（2026-09-04 22:40 实测披露了可用的
     *                          z-library.sk 官方入口 + 钓鱼站黑名单，发现价值已验证）。 */
    private val PORTAL_SOURCES = listOf(
        "https://zlib.wwkejishe.top/",
        "https://z-lib.app/",
        "https://go-to-zlibrary.com/",
        "https://z.wwwnav.com/rkfby.html",
        "https://t.me/s/Zlib_IO",
        "https://znew.pages.dev/"
    )

    private companion object {
        /** 抓到的域名里，这些入口页/归拢枢纽价值低，排到候选列表尾部。 */
        private val DEPRIORITIZE = listOf("z-lib.app", "zlibrary-forum", "pages.dev", "go-to-zlibrary")
    }

    suspend fun fetchLatestEndpoints(): List<RemoteEndpointConfig>? = withContext(Dispatchers.IO) {
        try {
            // 页面上全部官方域名（z-lib.* / zlib* / z-library* / zlibrary* / 1lib* / singlelogin*）。
            val domainRegex = Regex(
                """https?://((?:[a-z0-9-]+\.)*(?:z-lib|zlib|z-library|zlibrary|1lib|singlelogin)[a-z0-9.-]+)""",
                RegexOption.IGNORE_CASE
            )

            // 四源并行抓取：单源失败/被墙不影响其他源（6s 连接超时快速失败）。
            val htmls = coroutineScope {
                PORTAL_SOURCES.map { url ->
                    async {
                        runCatching {
                            val request = Request.Builder().url(url).header("Accept", "text/html").build()
                            okHttpClient.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) resp.body?.string() else null
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            val found = LinkedHashSet<String>()
            htmls.forEach { html ->
                if (html.isNullOrBlank()) return@forEach
                domainRegex.findAll(html).forEach { m ->
                    val host = m.groupValues[1].lowercase()
                    if (host.contains('.') && !host.endsWith(".png") && !host.endsWith(".js")) {
                        found.add(host)
                    }
                }
            }
            // 门户自身也常是可用入口（静态可达）
            found.add("z-lib.app")

            if (found.isEmpty()) {
                Log.w("ZLibRemote", "all ${PORTAL_SOURCES.size} discovery sources disclosed nothing")
                return@withContext getCachedEndpoints()
            }
            Log.i(
                "ZLibRemote",
                "discovered ${found.size} domains from ${htmls.count { !it.isNullOrBlank() }}/${PORTAL_SOURCES.size} sources: $found"
            )

            // 排序：非归拢枢纽优先（真实镜像在前），压过纯入口页
            val ranked = found.sortedBy { host ->
                DEPRIORITIZE.indexOfFirst { host.contains(it) }.let { if (it >= 0) 1 else 0 }
            }
            val configs = ranked.mapIndexed { i, host ->
                RemoteEndpointConfig(
                    url = host,
                    priority = (ranked.size - i),
                    updatedAt = System.currentTimeMillis(),
                    version = "portal"
                )
            }

            // 缓存为 JSON（复用 parseConfigs 格式）
            val arr = JSONArray()
            configs.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("url", c.url)
                        .put("priority", c.priority)
                        .put("updatedAt", c.updatedAt)
                        .put("version", c.version)
                )
            }
            saveCache(arr.toString())
            configs
        } catch (e: Exception) {
            Log.w("ZLibRemote", "portal fetch failed: ${e.message}")
            getCachedEndpoints()
        }
    }

    private fun parseConfigs(json: String): List<RemoteEndpointConfig>? {
        return try {
            val array = JSONArray(json)
            val out = ArrayList<RemoteEndpointConfig>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(
                    RemoteEndpointConfig(
                        url = o.optString("url"),
                        priority = o.optInt("priority", 0),
                        updatedAt = o.optLong("updatedAt", 0L),
                        version = o.optString("version"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCache(json: String) {
        prefs.edit().putString("cached_portal_domains", json).commit()
    }

    private fun getCachedEndpoints(): List<RemoteEndpointConfig>? {
        val json = prefs.getString("cached_portal_domains", null) ?: return null
        return parseConfigs(json)
    }
}
