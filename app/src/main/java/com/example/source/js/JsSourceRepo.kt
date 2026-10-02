package com.example.source.js

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import com.example.source.SourceResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Venera 兼容源仓库管理：拉取 index.json、下载源脚本、本地缓存与更新。
 */
object JsSourceRepo {

    /** 本地补丁版本：升级后尝试更新源脚本，失败时仍可使用旧缓存。 */
    private const val PATCH_VERSION = 39
    // Raw cache format stays stable when compatibility patches change.
    private const val RAW_CACHE_PREFIX = "// EASYREADER_RAW_SOURCE_V28\n"

    /** 已知成人源 key 黑名单（默认隐藏，设置彩蛋开启后可见）。 */
    val ADULT_KEYS = setOf(
        "nhentai", "ehentai", "hitomi", "jm", "picacg", "wnacg", "mxs",
        "mh18", "hcomic", "hot_manga"
    )

    /** 需要账号的源（picacg 账密、爱看漫匿名可搜但部分功能需登录、vomic 免费注册后可读）。
     *  mycomic 无账号系统：Cloudflare 盾由 CfWebViewSolver 在网络层自动过，不算登录。 */
    val LOGIN_KEYS = setOf("picacg", "ikmmh", "vomic")

    /** 与 App 内置源重复、同 key 多账号、或需要用户自建服务器的源。 */
    private val EXCLUDED_KEYS = setOf(
        "manga_dex", "lanraragi", "komga", "kavita",
        "baozi", "jcomic",
        // 当前网络/站点确认不可用：同步仓库时默认排除
        // （ccc 曾短期解除排除，2026-09-26 按用户"移除会员门槛源"要求重新排除：
        //   其付费章节需账号购买，免费章之外不可读）
        "zaimanhua", "ManHuaGui", "ykmh", "happy", "Komiic",
        "shonen_jump_plus", "mh1234", "comic_walker",
        "ccc", "mh18"
        // 2026-10-02 repeated search/read audit: unstable WAF exit blocking.
        // Keep the cached script for repair, but remove it from the offered list.
        , "ikmmh"
    )

    /** 证书不完整/自签名，需要忽略 TLS 校验的源。 */
    private val INSECURE_KEYS = setOf("baozi")

    /**
     * 本地内置源：不在远端仓库索引里，随 App 资产分发（assets/js_extra/）。
     * 嗶哩/vomic 参考 Keiyoushi；扑飞按本站公开目录及阅读器数据格式适配。
     * （tencent/kuaikan 已按用户要求下架：官方平台付费墙锁内容，归档于
     *   %LOCALAPPDATA%/Temp/mp/archive_sources/，可随时恢复）
     */
    private val LOCAL_EXTRA_SOURCES = listOf(
        Triple("bilimanga", "嗶哩漫畫", "bilimanga.js"),
        Triple("vomic", "vomic漫画", "vomic.js"),
        Triple("pufei", "扑飞漫画", "pufei.js"),
    )

    /** 从 assets 读取本地内置源并包装成 JsComicSource（不受远端仓库可用性影响）。 */
    private fun loadLocalExtras(context: Context): List<JsComicSource> {
        return LOCAL_EXTRA_SOURCES.mapNotNull { (key, name, fileName) ->
            runCatching {
                val body = context.assets.open("js_extra/$fileName").bufferedReader().readText()
                if (!validScript(body)) {
                    Log.w("JsRepo", "invalid local extra script: $fileName")
                    return@mapNotNull null
                }
                JsComicSource(
                    context = context,
                    sourceKey = key,
                    name = name,
                    version = Regex("""\bversion\s*=\s*["']([^"']+)["']""")
                        .find(body)?.groupValues?.get(1) ?: "1.0.0",
                    script = patchScript(key, body),
                    insecureTls = key in INSECURE_KEYS,
                    loginRequired = key in LOGIN_KEYS
                )
            }.onFailure { Log.w("JsRepo", "load local extra failed: $fileName", it) }.getOrNull()
        }
    }

    private val baseClient = com.example.source.SharedHttpTransport.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 默认 jsDelivr 不通时依次尝试的镜像（国内/代理环境可达性不同）。 */
    private val INDEX_MIRRORS = listOf(
        "https://fastly.jsdelivr.net/gh/venera-app/venera-configs@main/index.json",
        "https://gcore.jsdelivr.net/gh/venera-app/venera-configs@main/index.json",
        "https://raw.githubusercontent.com/venera-app/venera-configs/main/index.json"
    )

    data class SourceMeta(
        val key: String,
        val name: String,
        val fileName: String,
        val version: String,
        val adult: Boolean,
        val insecure: Boolean
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "js_sources").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    /** 从本地缓存加载已安装的 JS 源（尊重成人源开关）。 */
    suspend fun loadCached(context: Context, includeAdult: Boolean): List<JsComicSource> =
        withContext(Dispatchers.IO) {
            val index = indexFile(context)
            if (!index.exists()) return@withContext loadLocalExtras(context)
            try {
                val metas = parseIndex(index.readText())
                    .filter { includeAdult || !it.adult }
                val loaded = metas.mapNotNull { meta ->
                    val file = File(dir(context), meta.fileName)
                    if (!file.exists() || file.length()>512*1024) return@mapNotNull null
                    val body = file.readText()
                    if (!validScript(body)) {
                        Log.w("JsRepo", "invalid cached script: ${meta.fileName}")
                        return@mapNotNull null
                    }
                    JsComicSource(
                        context = context,
                        sourceKey = meta.key,
                        name = meta.name,
                        version = meta.version,
                        // 旧版缓存已存放补丁后的脚本；新版有标记，才在内存里补丁。
                        script = if (body.startsWith(RAW_CACHE_PREFIX)) {
                            patchScript(meta.key, body.removePrefix(RAW_CACHE_PREFIX))
                        } else if (meta.key in setOf("wnacg", "mxs")) {
                            // These repairs are idempotent and also migrate pre-raw-cache scripts.
                            patchScript(meta.key, body)
                        } else body,
                        insecureTls = meta.insecure,
                        loginRequired = meta.key in LOGIN_KEYS
                    )
                }
                // 本地内置源不受成人开关影响；即便远端缓存为空也始终可用
                loaded + loadLocalExtras(context)
            } catch (e: Exception) {
                loadLocalExtras(context)
            }
        }

    /** 缓存中有脚本缺失或无效时，下次启动补拉，同时继续提供已加载的源。 */
    fun needsRepair(context: Context, includeAdult: Boolean): Boolean = runCatching {
        if (context.getSharedPreferences("js_source_meta", Context.MODE_PRIVATE)
                .getInt("patch_version", 0) != PATCH_VERSION
        ) return@runCatching true
        val index = indexFile(context)
        if (!index.exists()) return@runCatching true
        parseIndex(index.readText())
            .filter { includeAdult || !it.adult }
            .any { meta ->
                val file = File(dir(context), meta.fileName)
                if (!file.exists()) true else {
                    val body = file.readText()
                    !validScript(body) || !body.startsWith(RAW_CACHE_PREFIX)
                }
            }
    }.getOrDefault(true)

    private fun validScript(body: String): Boolean =
        body.length >= 1024 && !body.trimStart().startsWith("<")

    /**
     * 书源健康检查：并行搜索一个通用关键词，
     * 返回“搜索失败/超时”的源 key（当前网络下不可用，建议停用）。
     */
    suspend fun healthCheck(
        sources: List<JsComicSource>
    ): Set<String> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptySet()
        val disabled = HashSet<String>()
        coroutineScope {
            sources.map { source ->
                async {
                    val result = withTimeoutOrNull(12000) { source.search("斗破苍穹") }
                    when (result) {
                        is SourceResult.Success -> {
                            // 能连通但搜出结果时再验一次章节，避免“搜到却点不开”的源留在列表里
                            if (result.data.isNotEmpty()) {
                                val chapterResult = withTimeoutOrNull(10000) {
                                    source.getChapters(result.data.first().id)
                                }
                                if (chapterResult == null || chapterResult is SourceResult.Error) {
                                    disabled.add(source.sourceKey)
                                }
                            }
                        }
                        is SourceResult.Error -> disabled.add(source.sourceKey)
                        null -> disabled.add(source.sourceKey)
                    }
                }
            }.awaitAll()
        }
        disabled
    }

    /**
     * 从远程仓库安装/更新全部源（跳过失败源），并写入本地缓存。
     * 返回成功安装的源列表；网络失败返回空并保留旧缓存。
     */
    suspend fun install(
        context: Context,
        repoUrl: String,
        includeAdult: Boolean,
        onStatus: (String) -> Unit = {}
    ): List<JsComicSource> = withContext(Dispatchers.IO) {
        try {
            onStatus("正在获取源仓库列表…")
            val indexEntry = fetchIndex(context, repoUrl)
            if (indexEntry == null) {
                Log.w("JsRepo", "fetch index failed: $repoUrl")
                return@withContext emptyList()
            }
            val (indexUrl, indexJson) = indexEntry
            val metas = parseIndex(indexJson).filter { includeAdult || !it.adult }
            Log.i("JsRepo", "index ok, metas=${metas.size}")
            val baseUrl = indexUrl.substringBeforeLast('/', indexUrl)
            val semaphore = Semaphore(4)
            val results = coroutineScope {
                metas.map { meta ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val script = fetchScript(context, baseUrl, meta.fileName)
                                if (script != null && script.length<=512*1024) {
                                    val patched = patchScript(meta.key, script)
                                    // 缓存原始脚本；loadCached 会在内存中补丁一次，避免重启后二次改写。
                                    val atomic=android.util.AtomicFile(File(dir(context),meta.fileName))
                                    val out=atomic.startWrite()
                                    try { out.write((RAW_CACHE_PREFIX+script).toByteArray()); atomic.finishWrite(out) }
                                    catch(e:Exception) { atomic.failWrite(out); throw e }
                                    meta to patched
                                } else null
                            } catch (e: Exception) {
                                Log.w("JsRepo", "install failed: ${meta.fileName}", e)
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (results.isEmpty()) return@withContext emptyList()
            indexFile(context).writeText(indexJson)
            context.getSharedPreferences("js_source_meta", Context.MODE_PRIVATE)
                .edit()
                .putInt("patch_version", PATCH_VERSION)
                .apply()
            Log.i("JsRepo", "installed ${results.size} sources")
            onStatus("已安装 ${results.size} 个漫画源")
            results.map { (meta, script) ->
                JsComicSource(
                    context = context,
                    sourceKey = meta.key,
                    name = meta.name,
                    version = meta.version,
                    script = script,
                    insecureTls = meta.insecure,
                    loginRequired = meta.key in LOGIN_KEYS
                )
            } + loadLocalExtras(context)
        } catch (e: Exception) {
            Log.w("JsRepo", "install failed", e)
            loadLocalExtras(context)
        }
    }

    /** 依次尝试配置仓库与镜像，返回（成功 URL，index 内容）。 */
    private fun fetchIndex(context: Context, repoUrl: String): Pair<String, String>? {
        val candidates = listOf(repoUrl) + INDEX_MIRRORS
        for (url in candidates) {
            val body = fetch(context, url)
            if (body != null && runCatching { JSONArray(body) }.isSuccess) {
                Log.i("JsRepo", "index fetched from: $url")
                return url to body
            }
            Log.w("JsRepo", "index mirror failed: $url")
        }
        return null
    }

    /** 脚本优先从 index 成功的那条链路下载，失败时再试其它镜像的对应路径。 */
    private fun fetchScript(context: Context, baseUrl: String, fileName: String): String? {
        val candidates = listOf("$baseUrl/$fileName") + INDEX_MIRRORS.map {
            "${it.substringBeforeLast('/', it)}/$fileName"
        }
        for (url in candidates) {
            val body = fetch(context, url)
            if (body != null && validScript(body)) return body
        }
        return null
    }

    private fun parseIndex(json: String): List<SourceMeta> {
        com.example.source.parser.RuleBudget.json(json)
        val arr = JSONArray(json)
        require(arr.length()<=500) { "书源仓库条目过多" }
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = obj.optString("key")
            val fileName = obj.optString("fileName")
            if (key.isBlank() || fileName.isBlank() || key in EXCLUDED_KEYS) return@mapNotNull null
            if(fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || !fileName.endsWith(".js")) return@mapNotNull null
            if (!seen.add(key)) return@mapNotNull null
            SourceMeta(
                key = key,
                name = obj.optString("name", key),
                fileName = fileName,
                version = obj.optString("version", "0"),
                adult = key in ADULT_KEYS,
                insecure = key in INSECURE_KEYS
            )
        }
    }

    private fun patchWnacgSearch(script: String): String {
        val searchStart = script.indexOf("search = {")
        val searchEnd = script.indexOf("    // favorite related", searchStart.coerceAtLeast(0))
        if (searchStart < 0 || searchEnd <= searchStart) return script
        return script.replaceRange(searchStart, searchEnd, """
            search = {
                load: async (keyword, options, page) => {
                    const url = this.baseUrl + '/search/?q=' + encodeURIComponent(keyword)
                        + '&f=_all&s=create_time_DESC&syn=yes&p=' + Math.max(1, Number(page) || 1);
                    const res = await Network.get(url, {});
                    if (res.status !== 200) throw 'Invalid Status Code ' + res.status;
                    const document = new HtmlDocument(res.body);
                    try {
                        const container = document.querySelector('div.gallary_wrap > ul.cc, #classify_container, ul.imgBox');
                        if (!container) throw '搜索页面未返回漫画列表，请稍后重试';
                        const comics = [];
                        const seen = new Set();
                        for (const item of container.children) {
                            const link = item.querySelector('a[href*="photos-index-aid-"]');
                            const match = (link?.attributes?.href || '').match(/photos-index-aid-(\d+)/);
                            const img = link?.querySelector('img');
                            const titleEl = item.querySelector('div.info > div.title > a, a.ImgA span');
                            const title = (titleEl?.text || link?.attributes?.title || link?.text || '').trim();
                            if (!match || !title || seen.has(match[1])) continue;
                            let cover = String(img?.attributes?.['data-original'] || img?.attributes?.['data-src'] || img?.attributes?.src || '').trim();
                            if (cover.startsWith('//')) cover = 'https://' + cover.replace(/^\/+/, '');
                            else if (cover.startsWith('/')) cover = this.baseUrl + cover;
                            const info = item.querySelector('div.info_col, span.info');
                            comics.push(new Comic({id:match[1],title:title,cover:cover,description:info?.text?.trim() || ''}));
                            seen.add(match[1]);
                        }
                        const total = Number((document.querySelector('p.result > b')?.text || '').replace(/,/g, ''));
                        let pages = total > 0 ? Math.ceil(total / 24) : 1;
                        for (const a of document.querySelectorAll('.paginator a[href]')) {
                            const match = (a.attributes.href || '').match(/[?&]p=(\d+)/);
                            if (match) pages = Math.max(pages, Number(match[1]));
                        }
                        return {comics:comics,maxPage:pages};
                    } finally { document.dispose(); }
                }
            }

        """.trimIndent() + "\n\n")
    }

    private fun patchMxsReader(script: String): String = script.replace(
        Regex("""loadEp:\s*async\s*\(comicId, epId\)\s*=>\s*\{[\s\S]*?(?=// 加载评论列表)""")
    ) {
        """
        loadEp: async (comicId, epId) => {
            const url = this.baseUrl + '/chapter/' + epId;
            const doc = await this.fetchDocument(url);
            try {
                const images = [];
                for (const img of doc.querySelectorAll('img.lazy, .comicpage img, #manga-reader img')) {
                    const attrs = img.attributes || {};
                    let value = String(attrs['data-original'] || attrs['data-src'] || attrs['data-lazy-src'] || attrs.src || '').trim();
                    if (!value || /^(data:|javascript:)/i.test(value)) continue;
                    if (value.startsWith('//')) value = 'https://' + value.replace(/^\/+/, '');
                    else if (!/^https?:\/\//i.test(value)) value = this.baseUrl + (value.startsWith('/') ? '' : '/') + value;
                    images.push(value);
                }
                if (!images.length) throw '本章中未找到图片';
                return {images:images};
            } finally { doc.dispose(); }
        },
        onImageLoad: (url, comicId, epId) => ({
            headers: {
                'Referer': this.baseUrl + '/chapter/' + epId,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        }),

        """.trimIndent() + "\n\n        "
    }

    /**
     * 针对远端脚本的本地兼容补丁（站点改版后脚本选择器失效，等上游更新前先兜底）。
     * 仅做最小改动，不破坏源脚本其它逻辑。
     */
    private fun patchScript(key: String, script: String): String = when (key) {
        // 漫蛙吧 API 域名迁移（mwuu.cc 301 → manwaxu.cc），2026-09-26 实测
        "manwaba" -> script.replace("https://mwuu.cc", "https://manwaxu.cc")
        "ehentai" -> {
            var p = script.replace(
            Regex(
                """if\(isFavorited\) \{\s*let position = document\s*\.querySelector\("div#fav"\)\s*\.children\[0\]\s*\.attributes\["style"\]\s*\.split\("background-position:0px -"\)\[1\]\s*\.split\("px;"\)\[0\];\s*folder = \(Number\(position-2\) / 19\)\.toString\(\)\s*\}""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ),
            """
            if(isFavorited) {
                let favEl = document.querySelector("div#fav");
                let favStyle = "";
                if (favEl && favEl.children[0] && favEl.children[0].attributes) {
                    favStyle = favEl.children[0].attributes["style"] || "";
                }
                let posMatch = favStyle.split("background-position:0px -")[1];
                if (posMatch) {
                    let position = posMatch.split("px;")[0];
                    folder = (Number(position-2) / 19).toString();
                }
            }
            """.trimIndent()
        )
            // 预连接暖身：Clash 代理首连 e-hentai 可能冷启动 30s+，init 时后台预热连接
            p = p.replace(
                """    // update url
    url = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/ehentai.js"""",
                """    // update url
    url = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/ehentai.js"

    async init() {
        try {
            Network.get(this.baseUrl + '/', {}).catch(() => {});
            Network.get(this.baseUrl + '/popular', {}).catch(() => {});
        } catch (e) {}
    }"""
            )
            // 详情页走 nw=session（delta-comic 风格）；不预置 sl/ns cookie（实测会导致匿名搜索挂起）
            // 详情页请求与 delta-comic 一致：hc=1&nw=session + referer（临时保留诊断日志）
            p = p.replace(
                """            let res = await Network.get(id, {
                'cookie': 'nw=1'
            });""",
                """            let __detailUrl = id.includes('?') ? id : id + '?hc=1&nw=session';
            let res = await Network.get(__detailUrl, {
                'cookie': 'nw=1',
                'referer': this.baseUrl + '/',
            });"""
            )
            // 缩略图分页请求同样带 referer 与 nw=session
            p = p.replace(
                """            let res = await Network.get(url, {
                'cache-time': 'long',
                'prevent-parallel': 'true',
                'cookie': 'nw=1'
            });""",
                """            let __thumbUrl = url.includes('?') ? url + '&nw=session' : url + '?nw=session';
            let res = await Network.get(__thumbUrl, {
                'cache-time': 'long',
                'prevent-parallel': 'true',
                'cookie': 'nw=1',
                'referer': this.baseUrl + '/',
            });"""
            )
            // 复制 delta-comic 的取图方式：直接抓图片页 #img，不再走 api.e-hentai.org
            p = p.replace(
                Regex(
                    """onImageLoad: async \(image, comicId, epId, nl\) => \{.*?\n        \},\n        /\*\*\n         \* \[Optional\] provide configs for a thumbnail loading""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ),
                """        onImageLoad: async (image, comicId, epId, nl) => {
            if (typeof image === 'string' && image.startsWith('http') && !image.includes('/s/')) {
                return {
                    url: image,
                    headers: { 'referer': this.baseUrl + '/' },
                }
            }
            let url = ''
            if (typeof image === 'string' && image.startsWith('http') && image.includes('/s/')) {
                url = image
            } else {
                let page = Number(image)
                if (!(page >= 0)) page = 0
                let cache = await this.getThumbUrls(comicId)
                let urls = cache.urls || []
                let pageSize = cache.pageSize || Math.max(urls.length, 1)
                if (page < urls.length) {
                    url = urls[page]
                } else {
                    let pageIndex = Math.floor(page / pageSize)
                    let index = page % pageSize
                    let more = await this.getThumbPage(comicId, pageIndex)
                    url = more[index] || urls[urls.length - 1]
                }
            }
            if (!url) throw "Failed to get image page url"
            let res = await Network.get(url, {
                'cookie': 'nw=1',
                'referer': this.baseUrl + '/',
            })
            if (res.status !== 200) throw 'Invalid status code: ' + res.status
            let document = new HtmlDocument(res.body)
            let img = document.querySelector('#img')
            let imgUrl = img ? img.attributes["src"] : ""
            document.dispose()
            if (!imgUrl) throw "Failed to get image url"
            return {
                url: imgUrl,
                headers: { 'referer': this.baseUrl + '/' },
            }
        },
        /**
         * [Optional] provide configs for a thumbnail loading"""
            )
            // loadEp 只返回每页的“图片页 URL”（每 40 页一次请求），真正图片在阅读/下载时懒加载
            p = p.replace(
                """        loadEp: async (comicId, epId) => {
            let comic = await this.comic.loadInfo(comicId)
            return {
                images: Array.from({length: comic.maxPage}, (_, i) => i.toString())
            }
        },""",
                """        loadEp: async (comicId, epId) => {
            let comic = await this.comic.loadInfo(comicId)
            let total = comic.maxPage || 0
            if (total === 0) return { images: [] }
            let cache = await this.getThumbUrls(comicId)
            let urls = cache.urls || []
            let pageSize = cache.pageSize || Math.max(urls.length, 1)
            let results = new Array(total)
            let pageIndices = new Set()
            for (let p = 0; p < total; p++) {
                if (p < urls.length) {
                    results[p] = urls[p]
                } else {
                    pageIndices.add(Math.floor(p / pageSize))
                }
            }
            await Promise.all(Array.from(pageIndices).map((pi) => this.getThumbPage(comicId, pi)))
            for (let p = 0; p < total; p++) {
                if (p >= urls.length) {
                    let pi = Math.floor(p / pageSize)
                    let idx = p % pageSize
                    let more = await this.getThumbPage(comicId, pi)
                    results[p] = more[idx] || urls[urls.length - 1]
                }
            }
            return { images: results }
        },"""
            )
            // 搜索作者：从 tags 里提取 artist/cosplayer/group，方便卡片显示真实作者
            p = p.replace(
                "    async onLoadFailed() {",
                """    getThumbUrls(comicId) {
        if (!this.__thumbCache) this.__thumbCache = {};
        if (!this.__thumbCache[comicId]) {
            this.__thumbCache[comicId] = (async () => {
                let first = await this.comic.loadThumbnails(comicId);
                return {
                    urls: first.urls || [],
                    pageSize: Math.max(first.thumbnails.length, first.urls.length, 1)
                };
            })();
        }
        return this.__thumbCache[comicId];
    }

    getThumbPage(comicId, pageIndex) {
        if (!this.__thumbCache) this.__thumbCache = {};
        let key = comicId + '#p' + pageIndex;
        if (!this.__thumbCache[key]) {
            this.__thumbCache[key] = (async () => {
                let t = await this.comic.loadThumbnails(comicId, String(pageIndex));
                return t.urls || [];
            })();
        }
        return this.__thumbCache[key];
    }

    getAuthorFromTags(tags, fallback) {
        try {
            if (Array.isArray(tags)) {
                for (let prefix of ["artist:", "cosplayer:", "group:"]) {
                    let hit = tags.find((e) => e && e.toLowerCase().startsWith(prefix));
                    if (hit) {
                        let v = hit.split(":")[1];
                        if (v && v.trim()) return v.trim();
                    }
                }
            }
        } catch (e) {}
        return fallback || "";
    }

    async onLoadFailed() {"""
            )
            // extended 模式
            p = p.replace(
                """                    subTitle: uploader,
                    cover: coverPath,
                    tags: tags,""",
                """                    subTitle: this.getAuthorFromTags(tags, uploader),
                    cover: coverPath,
                    tags: tags,"""
            )
            // compact 模式
            p = p.replace(
                """                    subTitle: uploader,
                    cover: cover,
                    tags: tags,""",
                """                    subTitle: this.getAuthorFromTags(tags, uploader),
                    cover: cover,
                    tags: tags,"""
            )
            p
        }
        "wnacg" -> {
            var patched = script.replace(
            Regex(
                """let title = document\.querySelector\("div\.userwrap > h2"\)\.text\s*""" +
                    """let cover = document\.querySelector\("div\.userwrap > div\.asTB > div\.asTBcell\.uwthumb > img"\)\.attributes\["src"\]\s*""" +
                    """cover = 'https:' \+ cover\s*""" +
                    """cover = cover\.substring\(0, 6\) \+ cover\.substring\(8\)\s*""" +
                    """let labels = document\.querySelectorAll\("div\.asTBcell\.uwconn > label"\)\s*""" +
                    """let category = labels\[0\]\.text\.split\("："\)\[1\]\s*""" +
                    """let pages = labels\[1\]\.text\.split\("："\)\[1\];\s*""" +
                    """let tagsDom = document\.querySelectorAll\("a\.tagshow"\);\s*""" +
                    """let tags = new Map\(\)\s*""" +
                    """tags\.set\("頁數", \[pages\]\)\s*""" +
                    """tags\.set\("分類", \[category\]\)\s*""" +
                    """if \(tagsDom\.length > 0\) \{\s*""" +
                    """tags\.set\("標籤", tagsDom\.map\(\(e\) => e\.text\)\)\s*""" +
                    """\}\s*""" +
                    """let description = document\.querySelector\("div\.asTBcell\.uwconn > p"\)\.text;\s*""" +
                    """let uploader = document\.querySelector\("div\.asTBcell\.uwuinfo > a > p"\)\.text;""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ),
            """
            let titleEl = document.querySelector("div#comicName") || document.querySelector("div.userwrap > h2")
            let title = titleEl ? titleEl.text : String(id)
            let coverEl = document.querySelector("div#Cover > img") || document.querySelector("div.userwrap > div.asTB > div.asTBcell.uwthumb > img")
            let cover = coverEl ? (coverEl.attributes["src"] || "") : ""
            if (cover.startsWith("////")) {
                cover = 'https://' + cover.substring(4)
            } else if (cover.startsWith("//")) {
                cover = 'https://' + cover.substring(2)
            }
            let labels = document.querySelectorAll("div.asTBcell.uwconn > label")
            let pagesEl = document.querySelector("p.txtItme > span.date") || labels[1]
            let categoryEl = document.querySelectorAll("p.txtItme > a.pd")[0] || labels[0]
            let category = categoryEl ? categoryEl.text.split("：").pop() : ""
            let pages = pagesEl ? pagesEl.text.replace(/[^0-9]/g, "") : "1"
            let tagsDom = document.querySelectorAll("a.tagshow");
            let tags = new Map()
            tags.set("頁數", [pages])
            tags.set("分類", [category])
            if (tagsDom.length > 0) {
                tags.set("標籤", tagsDom.map((e) => e.text))
            }
            let descriptionEl = document.querySelector("div.asTBcell.uwconn > p") || document.querySelector("div.Introduct_Sub")
            let description = descriptionEl ? descriptionEl.text : ""
            let uploaderEl = document.querySelector("a.introName") || document.querySelector("div.asTBcell.uwuinfo > a > p")
            let uploader = uploaderEl ? uploaderEl.text : ""
            """.trimIndent()
        )
            patched = patched.replace(
                "return `https://${'$'}{domain0.trim()}`",
                "return `https://${'$'}{domain0.trim() === 'wnacg.com' ? (Wnacg.domains[0] || 'www.wn001.cfd') : domain0.trim()}`"
            ).let { body -> if (body.contains("!/^wnacg\\d+\\.link")) body else body.replace(
                "!domain.includes(\"wn01.link\")",
                // The publisher links to wnacg01/02.link before the actual comic sites.
                // Publishing pages have no /search/ route and must not become baseUrl.
                "!domain.includes(\"wn01.link\") && !/^wnacg\\d+\\.link${'$'}/i.test(domain)"
            ) }.let { body -> if (body.contains("chapters: chapters,")) body else body.replace(
                "            return new ComicDetails({\n                id: id,",
                """            let chapters = new Map();
            document.querySelectorAll('a[href*="/photos-slide-aid-"]').forEach((link) => {
                let match = (link.attributes['href'] || '').match(/photos-slide-aid-(\d+)/);
                if (match) chapters.set(match[1], link.text.trim() || `第${'$'}{chapters.size + 1}章`);
            });
            return new ComicDetails({
                id: id,
                chapters: chapters,"""
            ) }.replace(
                "`${'$'}{this.baseUrl}/photos-gallery-aid-${'$'}{comicId}.html`",
                "`${'$'}{this.baseUrl}/photos-gallery-aid-${'$'}{epId || comicId}.html`"
            )
            patchWnacgSearch(patched)
        }
        // hitomi：gg.js（图片子域映射）10 分钟内复用，避免每开一个章节都重新下载并 eval
        "hitomi" -> {
            var patched = script.replace(
                Regex(
                    """async function get_image_srcs\(files\) \{\s*const resp = await Network\.get\(\s*"https://" \+ domain \+ "/" \+ "gg\.js\?_=" \+ new Date\(\)\.getTime\(\),\s*\{\s*referer: refererUrl,\s*\}\s*\);\s*if \(resp\.status >= 400\) \{\s*throw new Error\(resp\.status\);\s*\}\s*eval\(resp\.body\);\s*if \(!gg\.b\) throw new Error\(\);""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ),
                """
                let __ggCacheTime = 0;
                async function get_image_srcs(files) {
                  const now = Date.now();
                  if (!(__ggCacheTime && now - __ggCacheTime < 600000 && typeof gg !== 'undefined' && gg && gg.b)) {
                    const resp = await Network.get(
                      "https://" + domain + "/" + "gg.js?_=" + now,
                      {
                        referer: refererUrl,
                      }
                    );
                    if (resp.status >= 400) {
                      throw new Error(resp.status);
                    }
                    eval(resp.body);
                    if (!gg.b) throw new Error();
                    __ggCacheTime = now;
                  }
                """.trimIndent()
            )
            // hitomi CDN 现在默认给 AVIF（Android 平台解码不稳定），改要 webp
            patched.replace(
                Regex("""return files\.map\(\(image\) => url_from_url_from_hash\(0, image, "avif"\)\);"""),
                """return files.map((image) => url_from_url_from_hash(0, image, "webp"));"""
            )
        }
        "mxs" -> patchMxsReader(script)
        // 漫画人：loadEp 直接把 epId 拼成相对路径，Cronet 无法请求；补成绝对 URL
        "manhuaren" -> script.replace(
            Regex("""let url = `\$\{epId\}/`;"""),
            """
            let url = epId.startsWith('http') ? epId : this.baseUrl + epId;
            if (!url.endsWith('/')) url += '/';
            """.trimIndent()
        )
        // comick：loadEp 请求章节页没带 headers，容易被反爬拒绝导致图片列表为空；
        // 且章节列表 key 含 hid，同语言同章节号被不同汉化组重复上传时会出现 11、11、22、22 的重复章节
        "comick" -> {
            var patched = script.replace(
                Regex("""let res = await Network\.get\(url\);"""),
                """let res = await Network.get(url, Comick.getRandomHeaders());"""
            )
            // Large books have dozens of catalogue pages. Preserve the entire list and its
            // order, but fetch later pages in bounded batches instead of a serial chain.
            val catalogueStart = patched.indexOf("                while (page <= lastPage) {")
            val catalogueEnd = if (catalogueStart >= 0)
                patched.indexOf("                let result = new Map();", catalogueStart) else -1
            if (catalogueEnd > catalogueStart) {
                patched = patched.replaceRange(catalogueStart, catalogueEnd, """
                const fetchCataloguePage = async (number) => {
                    const url = `https://comick.art/api/comics/${'$'}{slug}/chapter-list?page=${'$'}{number}`;
                    const response = await Network.get(url, Comick.getRandomHeaders());
                    if (response.status !== 200) throw `Invalid status code: ${'$'}{response.status}`;
                    return JSON.parse(response.body);
                };
                const firstPage = await fetchCataloguePage(1);
                const firstItems = Array.isArray(firstPage.data) ? firstPage.data : [];
                if (firstItems.length) {
                    latestTimestamp = firstItems[0].updated_at || firstItems[0].publish_at || firstItems[0].created_at || null;
                }
                collectChapters(firstItems);
                lastPage = Number(firstPage.pagination?.last_page || 1);
                if (!Number.isInteger(lastPage) || lastPage < 1 || lastPage > 512) throw 'Invalid catalogue page count';
                for (let start = 2; start <= lastPage; start += 4) {
                    const requests = [];
                    for (let number = start; number <= Math.min(start + 3, lastPage); number++) {
                        requests.push(fetchCataloguePage(number));
                    }
                    const replies = await Promise.all(requests);
                    for (const reply of replies) collectChapters(Array.isArray(reply.data) ? reply.data : []);
                }

                """.trimIndent().prependIndent("                ") + "\n")
            }
            patched = patched.replace(
                """} catch (error) {
                chapters = new Map();
            }""",
                """} catch (error) {
                throw error;
            }"""
            )
            patched = patched.replace(
                Regex(
                    """orderedItems\.forEach\(item => \{.*?chaptersMap\.set\(key, label\);\s*\}\);""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ),
                """
                // 两遍扫描去重（编号相同仅保留最新上传的 hid）：
                // orderedItems 已按旧→新排列，latestByNumber 后写覆盖即最新
                let latestByNumber = new Map();
                orderedItems.forEach(item => {
                    let lang = item?.lang || 'unknown';
                    let hasChap = item?.chap != null && item.chap !== "";
                    let hasVol = item?.vol != null && item.vol !== "";
                    if (hasChap) {
                        latestByNumber.set('chapter//' + item.chap + '//' + lang, item);
                    } else if (hasVol) {
                        latestByNumber.set('volume//' + item.vol + '//' + lang, item);
                    }
                });
                orderedItems.forEach(item => {
                    let lang = item?.lang || 'unknown';
                    let hid = item?.hid || 'unknown';
                    let hasChap = item?.chap != null && item.chap !== "";
                    let hasVol = item?.vol != null && item.vol !== "";
                    let key;
                    let label;
                    let dedupKey = null;

                    if (hasChap) {
                        key = hid + '//chapter//' + item.chap + '//' + lang;
                        label = '第' + item.chap + '话';
                        dedupKey = 'chapter//' + item.chap + '//' + lang;
                    } else if (hasVol) {
                        key = hid + '//volume//' + item.vol + '//' + lang;
                        label = '第' + item.vol + '卷';
                        dedupKey = 'volume//' + item.vol + '//' + lang;
                    } else {
                        key = hid + '//no//-1//' + lang;
                        label = item?.title ? item.title : '无标卷';
                    }

                    if (dedupKey != null && latestByNumber.get(dedupKey) !== item) {
                        return;
                    }

                    chaptersMap.set(key, label);
                });
                """.trimIndent()
            )
            val searchStart = patched.indexOf("    search = {")
            val searchEnd = if (searchStart >= 0) patched.indexOf("    /// single comic related", searchStart) else -1
            if (searchEnd >= 0) {
                var searchBlock = patched.substring(searchStart, searchEnd)
                val urlStart = searchBlock.indexOf("            let url = `https://comick.art/search?")
                val urlEnd = if (urlStart >= 0) searchBlock.indexOf(';', urlStart) else -1
                if (urlEnd >= 0) searchBlock = searchBlock.replaceRange(
                    urlStart, urlEnd + 1,
                    """
            if (!this.__searchCursors) this.__searchCursors = {};
            if (Number(page) === 1) this.__searchCursors[keyword] = {};
            let cursor = this.__searchCursors[keyword]?.[Number(page)];
            let url = cursor
                ? `https://comick.art/search?q=${'$'}{encodeURIComponent(keyword)}&cursor=${'$'}{encodeURIComponent(cursor)}`
                : `https://comick.art/search?q=${'$'}{encodeURIComponent(keyword)}&page=${'$'}{page}`;
                    """.trimIndent()
                )
                searchBlock = searchBlock.replace(
                    "let maxpage = mangaList.total/mangaList.per_page",
                    """
                    if (jsonData.next_cursor) {
                        this.__searchCursors[keyword][Number(page) + 1] = jsonData.next_cursor;
                    }
                    let maxpage = jsonData.next_cursor ? Number(page) + 1 : Number(page);
                    """.trimIndent()
                )
                patched = patched.replaceRange(searchStart, searchEnd, searchBlock)
            }
            patched
        }
        // picacg：登录后补存账号，否则搜索时的 reLogin 报 Invalid account data
        "picacg" -> {
            var patched = script.replace(
                Regex("""this\.saveData\('token', json\.data\.token\)\s*return 'ok'"""),
                """this.saveData('token', json.data.token); this.saveData('account', [account, pwd]); return 'ok'"""
            )
            // 账号数据缺失时不再中断，直接用现有 token 重试
            patched = patched.replace(
                Regex("""if\(!Array\.isArray\(account\)\) \{\s*throw new Error\('Failed to reLogin: Invalid account data'\);\s*\}"""),
                """if(!Array.isArray(account)) { return 'ok'; }"""
            )
            patched = patched.replace(
                Regex("""this\.buildHeaders\('POST', `comics/advanced-search\?page=\$\{page\}`, this\.loadData\('token'\)\)"""),
                """this.buildHeaders('POST', 'comics/advanced-search?page=' + page, this.loadData('token'))"""
            )
            patched.replace(
                Regex("""throw 'Invalid status code: ' \+ res\.status"""),
                """throw 'Invalid status code: ' + res.status + ' body=' + String(res.body || '').substring(0, 160)"""
            )
        }
        // 拷贝漫画：动态 API 域名没有真正持久化（脚本里写 this.settings 不走 loadSetting），
        // 导致一直用默认域名 api.copy2000.online，它一抽风就全挂。
        // 这里把网络2接口返回的可用域名 saveData 持久化，并加入候选域名 + 失败自动刷新重试。
        "copy_manga" -> {
            var patched = script
            // 1) apiUrl getter：优先使用已持久化的动态域名
            val apiStart = patched.indexOf("get apiUrl() {")
            if (apiStart >= 0) {
                // 原 getter 的 return 是模板字符串，里面有 ${...} 的右花括号，
                // 不能从 apiStart 直接找第一个 }，要等 return 行结束后的下一个 }
                val apiReturn = patched.indexOf("loadSetting('base_url')", apiStart)
                val apiClose = if (apiReturn >= 0) {
                    patched.indexOf('}', patched.indexOf('\n', apiReturn))
                } else {
                    patched.indexOf('}', apiStart)
                }
                if (apiClose > apiStart) {
                    patched = patched.substring(0, apiStart) +
                        """
                        get apiUrl() {
                            const dynamic = this.loadData("_api_url");
                            if (dynamic && dynamic.startsWith("http")) return dynamic;
                            return "https://" + (this.loadSetting('base_url') || CopyManga.defaultApiUrl);
                        }
                        """.trimIndent() +
                        patched.substring(apiClose + 1)
                }
            }
            // 2) refreshAppApi：候选域名逐个尝试，成功后 saveData 持久化
            val refreshStart = patched.indexOf("async refreshAppApi() {")
            if (refreshStart >= 0) {
                val marker = "this.settings.base_url = data.results.api[0][0];"
                val markerIdx = patched.indexOf(marker, refreshStart)
                if (markerIdx >= 0) {
                    val ifClose = patched.indexOf('}', markerIdx)
                    val funcClose = patched.indexOf('}', ifClose + 1)
                    if (funcClose > markerIdx) {
                        patched = patched.substring(0, refreshStart) +
                            """
                            async refreshAppApi() {
                                const candidates = [
                                    "https://api.copy-manga.com/api/v3/system/network2?platform=3",
                                    "https://api.copy2000.online/api/v3/system/network2?platform=3",
                                    "https://api.copymanga.tv/api/v3/system/network2?platform=3",
                                    "https://api.mangacopy.com/api/v3/system/network2?platform=3"
                                ];
                                for (const url of candidates) {
                                    try {
                                        const res = await fetch(url, { headers: this.headers });
                                        if (res.status === 200) {
                                            const data = await res.json();
                                            const apiList = data && data.results && data.results.api;
                                            const host = apiList && apiList[0] && apiList[0][0]
                                                ? String(apiList[0][0]).replace(/^https?:\/\//, "").replace(/\/.*$/, "")
                                                : "";
                                            if (host) {
                                                this.saveData("_api_url", "https://" + host);
                                                this.settings.base_url = host;
                                                return;
                                            }
                                        }
                                    } catch (e) {}
                                }
                            }
                            """.trimIndent() +
                            patched.substring(funcClose + 1)
                    }
                }
            }
            patched
        }
        "jm" -> script.replace(
            "this.convertData(await res.text(), domainSecret)",
            "this.convertData((await res.text()).replace(/^\\uFEFF/, ''), domainSecret)"
        )
        "nhentai" -> script.replace(
            "    getApiBaseHeaders() {\n        return {",
            """
    getApiBaseHeaders() {
        const apiKey = this.getApiKey();
        return {
            ...(apiKey ? {Authorization: 'Key ' + apiKey} : {}),
            """.trimIndent()
        )
        "ikmmh" -> {
            // Keiyoushi's current adapter uses the site's public app entry point.
            // Migrate the former default without replacing a user-chosen mirror.
            var patched = script.replace("\"https://www.ikmmh.com\"", "\"https://ymcdnyfqdapp.ikmmh.com\"")
                .replace(
                    "Ikm.baseUrl = String(baseUrl).trim().replace(/\\/+\u0024/, \"\");",
                    "Ikm.baseUrl = String(baseUrl).trim().replace(/\\/+\u0024/, \"\");\n" +
                        "    if (Ikm.baseUrl === 'https://www.ikmmh.com') Ikm.baseUrl = 'https://ymcdnyfqdapp.ikmmh.com';"
                ).replace(
                "`user=${'$'}{account}&pass=${'$'}{pwd}`",
                "`user=${'$'}{encodeURIComponent(account)}&pass=${'$'}{encodeURIComponent(pwd)}`"
            )
            val searchStart = patched.indexOf("  search = {")
            val searchEnd = if (searchStart >= 0) patched.indexOf("  // 收藏功能", searchStart) else -1
            if (searchEnd >= 0 && patched.substring(searchStart, searchEnd).contains("li.comic-item")) {
                val searchBlock = patched.substring(searchStart, searchEnd)
                    .replace("        let res = await Network.get(",
                        "        await validatorGet(Ikm.baseUrl + '/', Ikm.webHeaders);\n        let res = await Network.get(")
                    .replace("err.message", "(err?.message || String(err))")
                    .replace(
                        "        let document = new HtmlDocument(res.body);",
                        "        if (res.status !== 200) throw new Error('Invalid status code: ' + res.status);\n        let document = new HtmlDocument(res.body);"
                    )
                    .replace("li.comic-item", "li.comic-item, div.classification")
                    .replace(
                        "e.querySelector(\"p.title\").text.split(\"~\")[0]",
                        "(e.querySelector(\"p.title\")?.text || e.querySelector(\"h2 a\")?.text || \"\").split(\"~\")[0]"
                    )
                    .replace(
                        "e.querySelector(\"img\").attributes[\"src\"]",
                        "e.querySelector(\"img\")?.attributes[\"data-src\"] || e.querySelector(\"img\")?.attributes[\"src\"] || \"\""
                    )
                    .replace(
                        "e.querySelector(\"span.chapter\").text",
                        "e.querySelector(\"span.chapter\")?.text || e.querySelector(\"p.describe a\")?.text || \"\""
                    )
                    .replace(
                        "`${'$'}{Ikm.baseUrl}${'$'}{e.querySelector(\"a\").attributes[\"href\"]}`",
                        "absoluteUrl(e.querySelector(\"a\")?.attributes[\"href\"] || \"\")"
                    )
                patched = patched.replaceRange(searchStart, searchEnd, searchBlock)
            }
            val detailStart = patched.indexOf("      let title = document.querySelector(\n        \"div.book-hero__detail > div.title\"")
            val detailEndMarker = "        isFavorite: isFavorite,\n      };"
            val detailEnd = if (detailStart >= 0) patched.indexOf(detailEndMarker, detailStart) else -1
            if (detailEnd >= 0) {
                patched = patched.replaceRange(
                    detailStart,
                    detailEnd + detailEndMarker.length,
                    """
      const titleMeta = document.querySelector("meta[property='og:title']");
      const coverMeta = document.querySelector("meta[property='og:image']");
      const descriptionMeta = document.querySelector("meta[name='description']");
      let title = titleMeta?.attributes["content"] || id;
      return {
        title: title.split("~")[0],
        cover: coverMeta?.attributes["content"] || "",
        description: descriptionMeta?.attributes["content"] || "",
        tags: {},
        chapters: eps,
        recommend: [],
        isFavorite: isFavorite,
      };
                    """.trimIndent()
                )
            }
            patched = patched.replace("    loadEp: async (comicId, epId) => {", """
    loadEp: async (comicId, epId) => {
      if (Ikm.baseUrl === 'https://ymcdnyfqdapp.ikmmh.com') {
        const ids = String(epId).match(/\/chapter\/(\d+)\/(\d+)/);
        if (!ids) throw new Error('章节地址无效');
        // The current public app endpoint serves complete batches through read/pics.
        // Warm up PHPSESSID once; keep it through all batches, as in Keiyoushi.
        await validatorGet(Ikm.baseUrl + '/', Ikm.webHeaders);
        const readBatch = async (offset) => {
          const response = await validatorPost(Ikm.baseUrl + '/api/comic/read/pics',
            {...Ikm.jsonHead, Referer: epId},
            'id=' + encodeURIComponent(ids[2]) + '&aid=' + encodeURIComponent(ids[1]) +
              '&offset=' + offset + '&limit=10');
          if (response.status !== 200) throw new Error('HTTP ' + response.status);
          const batch = parseReadPicsImages(response, epId);
          if (!batch.ok) throw new Error('源站未提供正文图片，请检查章节权限');
          return batch;
        };
        const first = await readBatch(0);
        if (!first.images.length) throw new Error('该章节没有可读取的正文图片');
        const images = first.images.slice();
        const total = first.total;
        if (total > 1000) throw new Error('章节页数超过安全上限');
        if (total > 0) {
          for (let offset = images.length; offset < total; offset += 20) {
            const startedAt = Date.now();
            const offsets = [];
            for (let value = offset; value < Math.min(offset + 20, total); value += 10) offsets.push(value);
            const batches = await Promise.all(offsets.map(readBatch));
            for (let index = 0; index < batches.length; index++) {
              const batch = batches[index];
              const expected = Math.min(10, total - offsets[index]);
              if (batch.images.length !== expected) throw new Error('源站返回了不完整的章节图片，请重试');
              images.push(...batch.images);
            }
            // Match the maintained adapter's two requests per 500 ms.
            const pause = 500 - (Date.now() - startedAt);
            if (offset + 20 < total && pause > 0) await new Promise(resolve => setTimeout(resolve, pause));
          }
          if (images.length !== total) throw new Error('章节图片数量不完整');
        } else {
          for (let offset = images.length; offset < 1000; offset += 10) {
            if (images.length % 10 !== 0) break;
            const batch = await readBatch(offset);
            images.push(...batch.images);
            if (batch.images.length < 10) break;
          }
        }
        return {images};
      }
            """.trimIndent())
            patched
        }
        else -> script
    }

    private fun fetch(context: Context, url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
                )
                .build()
            JsSourceProxy.failoverClient(context.applicationContext, baseClient)
                .newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }
}
