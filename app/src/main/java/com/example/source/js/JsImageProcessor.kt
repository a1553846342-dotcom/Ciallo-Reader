package com.example.source.js

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片后处理注册表：JsComicSource 解析 onImageLoad 时，
 * 把需要 modifyImage 重排的 URL 与对应 JS 引擎/代码登记到这里，
 * 在线阅读器拦截器与章节下载器在拿到原始字节后统一调用 transform。
 */
object JsImageProcessor {

    private data class Entry(
        val engine: JsSourceEngine,
        val code: String
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(url: String, engine: JsSourceEngine, code: String?) {
        if (code.isNullOrBlank()) {
            entries.remove(url)
        } else {
            entries[url] = Entry(engine, code)
        }
    }

    fun unregister(url: String) {
        entries.remove(url)
    }

    /** Scrambled pages must never be displayed before their complete-image transform. */
    fun hasTransform(url: String): Boolean = entries.containsKey(url)

    /** 同步执行（拦截器/下载器都在 IO 线程），失败时返回 null 表示保持原图。 */
    fun transform(url: String, bytes: ByteArray): ByteArray? {
        val entry = entries[url] ?: return null
        Log.i("JsImageProcessor", "transform found for ${url.take(80)}")
        return try {
            entry.engine.transformImageBlocking(entry.code, bytes)
        } catch (e: Exception) {
            Log.w("JsImageProcessor", "transform failed: $url", e)
            null
        }
    }

    fun clear() {
        entries.clear()
    }
}
