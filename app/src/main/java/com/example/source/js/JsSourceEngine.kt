package com.example.source.js

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.example.source.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 单个 JS 书源的 QuickJS 运行环境。
 * 加载 Venera 运行时 + 源脚本，并通过 sendMessage 桥接 Kotlin 能力。
 */
class JsSourceEngine(
    private val runtimeJs: String,
    private val sourceJs: String,
    private val sourceKey: String,
    context: Context,
    insecureTls: Boolean = false
) {
    private val app = context.applicationContext
    private val handler = JsMessageHandler(app, sourceKey, insecureTls)
    @Volatile private var executionDeadline = 0L
    @Volatile private var executionJob: kotlinx.coroutines.Job? = null
    private var initializing = false
    private suspend fun beginBudget() {
        executionJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        executionDeadline = android.os.SystemClock.elapsedRealtime() + 45_000
        handler.requestJob = executionJob
    }
    private fun checkBudget() {
        if (!initializing && executionJob?.isActive == false) throw kotlinx.coroutines.CancellationException("JS request cancelled")
        check(android.os.SystemClock.elapsedRealtime() <= executionDeadline) { "书源脚本执行超过 45 秒" }
    }
    private suspend fun instrument(code:String):String = quickJs.evaluate<String>(
        "__cialloInstrument(${JSONObject.quote(code)})",filename="instrument_source.js") ?: error("JS instrumentation failed")

    init {
        // 注册前台 Activity 跟踪：UI.showInputDialog 需要在 Activity 上弹对话框
        JsActivityTracker.register(context)
    }

    private val quickJs = QuickJs.create(Dispatchers.IO).apply {
        memoryLimit = 32L * 1024 * 1024
        maxStackSize = 512L * 1024
    }
    private val mutex = Mutex()
    private var ready = false
    private var className = ""

    private suspend fun <T> withRuntime(block: suspend () -> T): T = mutex.withLock {
        // Preserve the caller's Job for guard/HTTP cancellation. Let alpha13 finish
        // consuming its native promises before another request can use this context.
        beginBudget()
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            try { block() }
            finally { QuickJsAsyncLifecycle.settle(quickJs) }
        }
    }

    fun setLoggedIn(logged: Boolean) {
        handler.setLoggedIn(logged)
    }

    fun isLoggedIn(): Boolean = handler.isLoggedIn()

    /** 用 Cronet 直接下载图片字节（H@H 等 OkHttp 握手失败的图床）。 */
    suspend fun fetchImageBytes(url: String, headers: Map<String, String>): ByteArray? =
        withContext(Dispatchers.IO) {
            handler.fetchImageBytes(url, headers, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        }

    /** 执行一段以 src 为实例的 JS 表达式，返回 JSON 字符串 {ok, data|error}。 */
    suspend fun call(jsCall: String): String? = withRuntime {
        ensureReadyLocked()
        val code = """
            await (async () => {
                const __flattenChapters = (value) => {
                    const entries = value instanceof Map ? Array.from(value.entries()) : Object.entries(value);
                    const out = [];
                    for (const [k, v] of entries) {
                        if (v instanceof Map) {
                            for (const [k2, v2] of v.entries()) {
                                out.push({ id: String(k2), title: String(v2), group: String(k) });
                            }
                        } else if (v && typeof v === 'object' && !Array.isArray(v)) {
                            for (const [k2, v2] of Object.entries(v)) {
                                out.push({ id: String(k2), title: String(v2), group: String(k) });
                            }
                        } else {
                            out.push({ id: String(k), title: String(v) });
                        }
                    }
                    return out;
                };
                const __normalize = (value) => {
                    if (value instanceof Map) {
                        const out = {};
                        for (const [key, item] of value.entries()) out[String(key)] = __normalize(item);
                        return out;
                    }
                    if (Array.isArray(value)) return value.map(__normalize);
                    if (value && typeof value === 'object') {
                        const out = {};
                        for (const key of Object.keys(value)) {
                            const v = value[key];
                            if (key === 'chapters' && v &&
                                (v instanceof Map || (typeof v === 'object' && !Array.isArray(v)))) {
                                out[key] = __flattenChapters(v);
                            } else {
                                out[key] = __normalize(v);
                            }
                        }
                        return out;
                    }
                    return value;
                };
                try {
                    const src = globalThis.__veneraSrc;
                    const result = await ($jsCall);
                    return JSON.stringify({ ok: true, data: __normalize(result === undefined ? null : result) });
                } catch (e) {
                    return JSON.stringify({
                        ok: false,
                        error: [e && e.message ? String(e.message) : '', String(e && e.stack ? e.stack : e)]
                            .filter(Boolean).join('\n'),
                        message: e && e.message ? String(e.message) : ''
                    });
                }
            })();
        """.trimIndent()
        try {
            val raw = quickJs.evaluate<String?>(instrument(code), filename = "call.js")
            if (raw?.contains("\"ok\":false") == true) {
                Log.w("JsEngine[$sourceKey]", "js error: ${raw.take(400)}")
            }
            raw
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val message = e.message ?: e.javaClass.simpleName
            "{\"ok\":false,\"error\":${org.json.JSONObject.quote(message)}}"
        }
    }

    /** 运行脚本返回的 modifyImage 代码，对图片字节做像素级重排（如禁漫天堂分块乱序）。 */
    suspend fun transformImage(jsCode: String, input: ByteArray): ByteArray? = withRuntime {
        ensureReadyLocked()
        val previousImages=handler.beginImageSession()
        try {
            val key = handler.createImage(input) ?: return@withRuntime null
            val code = """
                (() => {
                    const __img = new Image($key);
                    $jsCode
                    const __res = modifyImage(__img);
                    const __outKey = __res && __res.key != null ? String(__res.key) : String($key);
                    return __outKey;
                })()
            """.trimIndent()
            val raw = quickJs.evaluate<String?>(instrument(code), filename = "modify_image.js")
            val outKey = raw?.trim()?.toIntOrNull()
            if (outKey == null) null else handler.exportImageBytes(outKey)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("JsEngine[$sourceKey]", "modifyImage failed", e)
            null
        } finally {
            handler.endImageSession(previousImages)
        }
    }

    /** OkHttp 拦截器等阻塞场景使用 */
    fun transformImageBlocking(jsCode: String, input: ByteArray): ByteArray? =
        kotlinx.coroutines.runBlocking { transformImage(jsCode, input) }

    private suspend fun ensureReadyLocked() {
        if (ready) return
        // Bootstrap installs non-configurable guards. Finish that one-time setup
        // even if its first caller cancels; HTTP still observes the caller's Job.
        initializing = true
        try { initializeLocked() } finally { initializing = false }
    }

    private suspend fun initializeLocked() {
        quickJs.function("__cialloTick") { checkBudget(); null }
        quickJs.function("__boundedRegex") { args ->
            checkBudget()
            val pattern=args[0].toString()
            val flags=args[1].toString()
            val input=args[2].toString()
            val start=(args[3] as? Number)?.toInt() ?: 0
            require(pattern.length<=4096 && input.length<=2*1024*1024)
            val options=(if('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE else 0) or
                (if('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or (if('s' in flags) java.util.regex.Pattern.DOTALL else 0)
            try {
            val matcher=java.util.regex.Pattern.compile(pattern,options).matcher(com.example.source.parser.RuleBudget.text(input,500))
            val matched=if('y' in flags) {
                if(start !in 0..input.length) false else { matcher.region(start,input.length); matcher.lookingAt() }
            } else if(start !in 0..input.length) false else matcher.find(start)
            if(!matched) "null" else {
                val groups=org.json.JSONArray()
                for(i in 0..matcher.groupCount()) groups.put(matcher.group(i) ?: JSONObject.NULL)
                JSONObject().put("index",matcher.start()).put("end",matcher.end()).put("groups",groups).toString()
            }
            } catch(e: StackOverflowError) { throw IllegalArgumentException("书源正则回溯过深",e) }
        }
        quickJs.function("__boundedRegexBatch") { args ->
            checkBudget()
            val pattern = args[0].toString()
            val flags = args[1].toString()
            val input = args[2].toString()
            val start = (args[3] as? Number)?.toInt() ?: 0
            require(pattern.length <= 4096 && input.length <= 2 * 1024 * 1024)
            val options = (if ('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE else 0) or
                (if ('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or (if ('s' in flags) java.util.regex.Pattern.DOTALL else 0)
            val out = org.json.JSONArray()
            if (start in 0..input.length) try {
                val matcher = java.util.regex.Pattern.compile(pattern, options).matcher(com.example.source.parser.RuleBudget.text(input, 500))
                var position = start
                var captured = 0
                while (out.length() < 128) {
                    val matched = if ('y' in flags) { matcher.region(position, input.length); matcher.lookingAt() }
                        else if (out.length() == 0) matcher.find(start) else matcher.find()
                    if (!matched) break
                    val groups = org.json.JSONArray()
                    for (i in 0..matcher.groupCount()) groups.put(matcher.group(i)?.also { captured += it.length } ?: JSONObject.NULL)
                    out.put(JSONObject().put("index", matcher.start()).put("end", matcher.end()).put("groups", groups))
                    position = matcher.end()
                    if (matcher.start() == matcher.end() || captured >= 65536 || 'g' !in flags && 'y' !in flags) break
                }
            } catch (e: StackOverflowError) { throw IllegalArgumentException("书源正则回溯过深", e) }
            out.toString()
        }
        // defineProperty returns globalThis. Do not send the global object (including
        // native bindings and Symbols) through QuickJS's JS-to-Kotlin value converter.
        quickJs.evaluate<Any?>(
            """
                Object.defineProperty(globalThis, '__cialloTick', {writable:false,configurable:false});
                Object.defineProperty(globalThis, '__boundedRegex', {writable:false,configurable:false});
                Object.defineProperty(globalThis, '__boundedRegexBatch', {writable:false,configurable:false});
                void 0;
            """.trimIndent(),
            filename = "runtime_guards.js"
        )
        quickJs.evaluate<Any?>(app.assets.open("js_safety/acorn.js").bufferedReader().use { it.readText() },filename="acorn.js")
        quickJs.evaluate<Any?>(app.assets.open("js_safety/instrument.js").bufferedReader().use { it.readText() },filename="instrument.js")
        // 同步桥：Convert/Html/Storage/uuid/random 等要求同步返回值
        quickJs.function("sendMessageSync") { args ->
            checkBudget()
            handler.handle(args.firstOrNull())
        }
        // 异步桥：Network/setTimeout/UI 等 await 或 .then 使用。
        // 挂起实现：UI.showInputDialog 需等待用户输入，故走 handleAsync。
        quickJs.asyncFunction("sendMessage") { args ->
            checkBudget()
            handler.handleAsync(args.firstOrNull())
        }
        // Venera App 注入的全局变量（appVersion 等）
        quickJs.evaluate<Any?>("globalThis.appVersion = '1.7.0';", filename = "globals.js")
        quickJs.evaluate<Any?>(runtimeJs, filename = "venera_runtime.js")
        // Venera 的 sendMessage 混合同步/异步语义；这里用同步桥覆盖同步消费方
        quickJs.evaluate<Any?>(SYNC_OVERRIDE, filename = "sync_override.js")
        quickJs.evaluate<Any?>(app.assets.open("venera/comic_metadata.js").bufferedReader().use { it.readText() }, filename = "comic_metadata.js")
        // Venera App 在引擎初始化时注入的模型类（ComicDetails/Chapter 等）
        quickJs.evaluate<Any?>(PREAMBLE, filename = "models.js")
        quickJs.evaluate<Any?>(instrument(sourceJs), filename = "source.js")
        className = Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+ComicSource""")
            .find(sourceJs)
            ?.groupValues
            ?.get(1)
            ?: ""
        if (className.isBlank()) {
            className = quickJs.evaluate<String>(
                """
                (() => {
                    const names = Object.getOwnPropertyNames(globalThis);
                    const found = names.filter(n => {
                        try {
                            return typeof globalThis[n] === 'function'
                                && globalThis[n].prototype instanceof ComicSource;
                        } catch (e) { return false; }
                    });
                    return found[0] || '';
                })()
                """.trimIndent()
            ) ?: ""
        }
        if (className.isBlank()) {
            throw SourceException.ParseError("JS 书源未找到 ComicSource 类")
        }
        // 与 Venera 一致：源实例只创建一次、init() 一次，后续调用全部复用同一实例
        // 先创建实例并抓取 settings 默认值，但暂不执行 init()，
        // 保证 init() 期间 loadSetting 也能拿到默认值（如 jm/ehentai 的域名）。
        quickJs.evaluate<Any?>(
            """
            globalThis.__veneraSrc = new $className();
            // 与 Venera 一致：部分源只实现 search.loadNext（首页传 null），补一个 load 适配
            (() => {
                const s = globalThis.__veneraSrc.search || {};
                if (typeof s.load !== 'function' && typeof s.loadNext === 'function') {
                    s.load = (keyword, options, page) => {
                        const list = s.optionList || [];
                        const opts = list.map((item, i) => {
                            if (options && options[i] !== undefined && options[i] !== null) return options[i];
                            const d = item.default;
                            if (Array.isArray(d)) return JSON.stringify(d);
                            if (d !== undefined && d !== null) return String(d);
                            return null;
                        });
                        return s.loadNext(keyword, opts, null);
                    };
                }
            })();
            (() => {
                const s = globalThis.__veneraSrc.settings || {};
                const out = {};
                for (const k of Object.keys(s)) {
                    const item = s[k];
                    if (item && typeof item === 'object' && item.default !== undefined) {
                        out[k] = String(item.default);
                    }
                }
                globalThis.__veneraDefaults = JSON.stringify(out);
            })();
            """.trimIndent(),
            filename = "instantiate.js"
        )
        val settingsJson = quickJs.evaluate<String>("globalThis.__veneraDefaults") ?: "{}"
        try {
            val defaults = HashMap<String, String>()
            val obj = JSONObject(settingsJson)
            obj.keys().forEach { k -> defaults[k] = obj.optString(k) }
            handler.setSettingDefaults(defaults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 忽略设置解析失败
        }
        // Defaults and bindings are reusable even if the optional init is cancelled.
        ready = true
        // 默认值注入完成后，再执行 init()（错误不致命，源继续用默认配置工作）
        quickJs.evaluate<Any?>(
            """
            if (typeof globalThis.__veneraSrc.init === 'function') {
                try {
                    await (async () => { await globalThis.__veneraSrc.init(); })();
                } catch (e) {
                    // 忽略：源继续用默认配置工作
                }
            }
            """.trimIndent(),
            filename = "instantiate.js"
        )
    }

    companion object {
        private val SYNC_OVERRIDE = """
            // ---- ComicSource 存储/登录状态（同步） ----
            ComicSource.prototype.loadData = function (dataKey) {
                let raw = sendMessageSync({ method: 'load_data', key: this.key, data_key: dataKey });
                if (typeof raw === 'string' && raw.startsWith('JSON:')) {
                    try { return JSON.parse(raw.slice(5)); } catch (e) { return raw; }
                }
                return raw;
            };
            ComicSource.prototype.saveData = function (dataKey, data) {
                sendMessageSync({ method: 'save_data', key: this.key, data_key: dataKey, data: data });
            };
            ComicSource.prototype.deleteData = function (dataKey) {
                sendMessageSync({ method: 'delete_data', key: this.key, data_key: dataKey });
            };
            ComicSource.prototype.loadSetting = function (settingKey) {
                return sendMessageSync({ method: 'load_setting', key: this.key, setting_key: settingKey });
            };
            Object.defineProperty(ComicSource.prototype, 'isLogged', {
                get() { return sendMessageSync({ method: 'isLogged', key: this.key }); }
            });

            // ---- Convert（同步） ----
            Convert.encodeUtf8 = (str) => sendMessageSync({ method: 'convert', type: 'utf8', value: str, isEncode: true });
            Convert.decodeUtf8 = (value) => sendMessageSync({ method: 'convert', type: 'utf8', value: value, isEncode: false });
            Convert.encodeGbk = (str) => sendMessageSync({ method: 'convert', type: 'gbk', value: str, isEncode: true });
            Convert.decodeGbk = (value) => sendMessageSync({ method: 'convert', type: 'gbk', value: value, isEncode: false });
            Convert.encodeBase64 = (value) => sendMessageSync({ method: 'convert', type: 'base64', value: value, isEncode: true });
            Convert.decodeBase64 = (value) => sendMessageSync({ method: 'convert', type: 'base64', value: value, isEncode: false });
            Convert.md5 = (value) => sendMessageSync({ method: 'convert', type: 'md5', value: value, isEncode: true });
            Convert.sha1 = (value) => sendMessageSync({ method: 'convert', type: 'sha1', value: value, isEncode: true });
            Convert.sha256 = (value) => sendMessageSync({ method: 'convert', type: 'sha256', value: value, isEncode: true });
            Convert.sha512 = (value) => sendMessageSync({ method: 'convert', type: 'sha512', value: value, isEncode: true });
            Convert.hmac = (key, value, hash) => sendMessageSync({ method: 'convert', type: 'hmac', value: value, key: key, hash: hash, isEncode: true });
            Convert.hmacString = (key, value, hash) => sendMessageSync({ method: 'convert', type: 'hmac', value: value, key: key, hash: hash, isEncode: true, isString: true });
            Convert.decryptAesEcb = (value, key) => sendMessageSync({ method: 'convert', type: 'aes-ecb', value: value, key: key, isEncode: false });
            Convert.decryptAesCbc = (value, key, iv) => sendMessageSync({ method: 'convert', type: 'aes-cbc', value: value, key: key, iv: iv, isEncode: false });
            Convert.decryptAesCfb = (value, key, blockSize) => sendMessageSync({ method: 'convert', type: 'aes-cfb', value: value, key: key, blockSize: blockSize, isEncode: false });
            Convert.decryptAesOfb = (value, key, blockSize) => sendMessageSync({ method: 'convert', type: 'aes-ofb', value: value, key: key, blockSize: blockSize, isEncode: false });
            Convert.decryptRsa = (value, key) => sendMessageSync({ method: 'convert', type: 'rsa', value: value, key: key, isEncode: false });
            Convert.hexEncode = (value) => sendMessageSync({ method: 'convert', type: 'hex', value: value });

            // ---- Network（桥接返回 JSON 字符串，这里解析为普通对象） ----
            Network.sendRequest = async function (method, url, headers, data) {
                let result = await sendMessage({ method: 'http', http_method: method, url: url, headers: headers, data: data });
                if (typeof result === 'string') { result = JSON.parse(result); }
                if (result.error) { throw result.error; }
                return result;
            };
            Network.fetchBytes = async function (method, url, headers, data) {
                let result = await sendMessage({ method: 'http', http_method: method, bytes: true, url: url, headers: headers, data: data });
                if (typeof result === 'string') { result = JSON.parse(result); }
                if (result.error) { throw result.error; }
                if (typeof result.body === 'string') {
                    result.body = Convert.decodeBase64(result.body);
                }
                return result;
            };
            Network.getCookies = async function (url) {
                const result = await sendMessage({method:'cookie',function:'get',url:url});
                return typeof result === 'string' ? JSON.parse(result) : result;
            };
            Network.setCookies = function (url, cookies) {
                return sendMessageSync({method:'cookie',function:'set',url:url,cookies:cookies});
            };

            // ---- 工具函数（同步） ----
            createUuid = () => sendMessageSync({ method: 'uuid' });
            randomInt = (min, max) => sendMessageSync({ method: 'random', type: 'int', min: min, max: max });
            randomDouble = (min, max) => sendMessageSync({ method: 'random', type: 'double', min: min, max: max });

            // ---- HtmlDocument / HtmlElement / HtmlNode（同步桥版本） ----
            HtmlDocument = class HtmlDocument {
                static _key = 0;
                constructor(html) {
                    this.key = HtmlDocument._key;
                    HtmlDocument._key++;
                    sendMessageSync({ method: 'html', function: 'parse', key: this.key, data: html });
                }
                querySelector(query) {
                    let k = sendMessageSync({ method: 'html', function: 'querySelector', key: this.key, query: query });
                    return k == null ? null : new HtmlElement(k, this.key);
                }
                querySelectorAll(query) {
                    let ks = sendMessageSync({ method: 'html', function: 'querySelectorAll', key: this.key, query: query });
                    return (ks || []).map(k => new HtmlElement(k, this.key));
                }
                getElementById(id) {
                    let k = sendMessageSync({ method: 'html', function: 'getElementById', key: this.key, id: id });
                    return k == null ? null : new HtmlElement(k, this.key);
                }
                dispose() {
                    sendMessageSync({ method: 'html', function: 'dispose', key: this.key });
                }
            };

            HtmlElement = class HtmlElement {
                constructor(key, doc) { this.key = key; this.doc = doc; }
                get text() { return sendMessageSync({ method: 'html', function: 'getText', key: this.key, doc: this.doc }); }
                get attributes() {
                    let raw = sendMessageSync({ method: 'html', function: 'getAttributes', key: this.key, doc: this.doc });
                    return typeof raw === 'string' ? JSON.parse(raw) : raw;
                }
                get children() {
                    let ks = sendMessageSync({ method: 'html', function: 'getChildren', key: this.key, doc: this.doc });
                    return (ks || []).map(k => new HtmlElement(k, this.doc));
                }
                get nodes() {
                    let ks = sendMessageSync({ method: 'html', function: 'getNodes', key: this.key, doc: this.doc });
                    return (ks || []).map(k => new HtmlNode(k, this.doc));
                }
                get innerHTML() { return sendMessageSync({ method: 'html', function: 'getInnerHTML', key: this.key, doc: this.doc }); }
                get parent() {
                    let k = sendMessageSync({ method: 'html', function: 'getParent', key: this.key, doc: this.doc });
                    return k == null ? null : new HtmlElement(k, this.doc);
                }
                get classNames() { return sendMessageSync({ method: 'html', function: 'getClassNames', key: this.key, doc: this.doc }); }
                get id() { return sendMessageSync({ method: 'html', function: 'getId', key: this.key, doc: this.doc }); }
                get localName() { return sendMessageSync({ method: 'html', function: 'getLocalName', key: this.key, doc: this.doc }); }
                get previousElementSibling() {
                    let k = sendMessageSync({ method: 'html', function: 'getPreviousSibling', key: this.key, doc: this.doc });
                    return k == null ? null : new HtmlElement(k, this.doc);
                }
                get nextElementSibling() {
                    let k = sendMessageSync({ method: 'html', function: 'getNextSibling', key: this.key, doc: this.doc });
                    return k == null ? null : new HtmlElement(k, this.doc);
                }
                querySelector(query) {
                    let k = sendMessageSync({ method: 'html', function: 'dom_querySelector', key: this.key, query: query, doc: this.doc });
                    return k == null ? null : new HtmlElement(k, this.doc);
                }
                querySelectorAll(query) {
                    let ks = sendMessageSync({ method: 'html', function: 'dom_querySelectorAll', key: this.key, query: query, doc: this.doc });
                    return (ks || []).map(k => new HtmlElement(k, this.doc));
                }
            };

            HtmlNode = class HtmlNode {
                constructor(key, doc) { this.key = key; this.doc = doc; }
                get text() { return sendMessageSync({ method: 'html', function: 'node_text', key: this.key, doc: this.doc }); }
                get type() { return sendMessageSync({ method: 'html', function: 'node_type', key: this.key, doc: this.doc }); }
                toElement() {
                    let k = sendMessageSync({ method: 'html', function: 'node_toElement', key: this.key, doc: this.doc });
                    return k == null ? null : new HtmlElement(k, this.doc);
                }
            };

            // ---- Image（同步桥版本，底层图像处理暂不支持时返回 null） ----
            Image = class Image {
                constructor(key) { this.key = key; }
                copyRange(x, y, width, height) {
                    let key = sendMessageSync({ method: 'image', function: 'copyRange', key: this.key, x: x, y: y, width: width, height: height });
                    return key == null ? null : new Image(key);
                }
                copyAndRotate90() {
                    let key = sendMessageSync({ method: 'image', function: 'copyAndRotate90', key: this.key });
                    return key == null ? null : new Image(key);
                }
                fillImageAt(x, y, image) {
                    sendMessageSync({ method: 'image', function: 'fillImageAt', key: this.key, x: x, y: y, image: image.key });
                }
                fillImageRangeAt(x, y, image, srcX, srcY, width, height) {
                    sendMessageSync({ method: 'image', function: 'fillImageRangeAt', key: this.key, x: x, y: y, image: image.key, srcX: srcX, srcY: srcY, width: width, height: height });
                }
                get width() { return sendMessageSync({ method: 'image', function: 'getWidth', key: this.key }); }
                get height() { return sendMessageSync({ method: 'image', function: 'getHeight', key: this.key }); }
                static empty(width, height) {
                    let key = sendMessageSync({ method: 'image', function: 'emptyImage', width: width, height: height });
                    return key == null ? null : new Image(key);
                }
            };
        """.trimIndent()

        private val PREAMBLE = """
            // ComicDetails / Comic 已由 Venera 运行时提供，这里只补 Chapter / Category
            class Chapter {
                constructor(options = {}) {
                    this.id = options.id;
                    this.title = options.title;
                    this.order = options.order;
                    this.group = options.group;
                }
            }
            class Category {
                constructor(options = {}) {
                    this.title = options.title;
                    this.category = options.category;
                }
            }
        """.trimIndent()
    }
}
