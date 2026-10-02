package com.example.download

import android.content.Context
import com.example.source.NovelInfo
import com.example.source.SearchBook
import com.example.source.WholeBookNovelSources
import org.json.JSONArray
import org.json.JSONObject

/** Separate from Z-Library credentials and reader preferences. Baselines advance only after import. */
class NovelDownloadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("whole_book_novel_downloads", Context.MODE_PRIVATE)
    data class Pending(val book: SearchBook, val replace: Boolean)
    private fun key(source: String, id: String) = "book:$source:$id"

    fun prepare(taskId: String, book: SearchBook, replace: Boolean) {
        require(WholeBookNovelSources.contains(book.sourceId))
        check(prefs.edit().putString("pending:$taskId", encode(book).put("replace", replace).toString()).commit())
    }
    fun pending(taskId: String): Pending? = prefs.getString("pending:$taskId", null)?.let {
        runCatching { val json = JSONObject(it); Pending(decode(json), json.optBoolean("replace")) }.getOrNull()
    }
    fun baseline(source: String, id: String): SearchBook? = prefs.getString(key(source, id), null)?.let {
        runCatching { decode(JSONObject(it)) }.getOrNull()
    }
    fun imported(taskId: String) {
        val pending = pending(taskId) ?: return
        require(WholeBookNovelSources.contains(pending.book.sourceId))
        check(prefs.edit().putString(key(pending.book.sourceId, pending.book.id), encode(pending.book).toString())
            .remove("pending:$taskId").commit())
    }
    private fun encode(book: SearchBook): JSONObject = JSONObject().put("id", book.id).put("sourceId", book.sourceId)
        .put("title", book.title).put("author", book.author).put("cover", book.cover).put("format", book.format)
        .put("language", book.language).put("description", book.description).put("size", book.size)
        .apply { book.novelInfo?.let { info -> put("info", JSONObject()
            .put("synopsis", info.synopsis).put("originalTitle", info.originalTitle).put("status", info.status)
            .put("category", info.category).put("latestChapter", info.latestChapter).put("updatedAt", info.updatedAt)
            .put("chapterCount", info.chapterCount).put("volumeCount", info.volumeCount).put("wordCount", info.wordCount)
            .put("publisher", info.publisher).put("translation", info.translation).put("notice", info.notice)
            .put("tags", JSONArray(info.tags))) } }
    private fun decode(json: JSONObject): SearchBook {
        fun JSONObject.text(key: String) = opt(key) as? String
        val info = json.optJSONObject("info")?.let { data -> NovelInfo(
            synopsis = data.text("synopsis"), originalTitle = data.text("originalTitle"), status = data.text("status"),
            category = data.text("category"), latestChapter = data.text("latestChapter"), updatedAt = data.text("updatedAt"),
            chapterCount = data.optInt("chapterCount").takeIf { it > 0 }, volumeCount = data.optInt("volumeCount").takeIf { it > 0 },
            wordCount = data.text("wordCount"), publisher = data.text("publisher"), translation = data.text("translation"), notice = data.text("notice"),
            tags = data.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()) }
        return SearchBook(json.getString("id"), json.getString("sourceId"), json.getString("title"), json.optString("author"),
            cover = json.text("cover"), description = json.text("description"), format = json.optString("format", "epub"),
            language = json.text("language"), size = json.optLong("size").takeIf { it > 0 }, novelInfo = info)
    }
}
