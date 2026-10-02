package com.example.library

import android.content.Context
import com.example.data.Book
import com.example.data.ReadingRecord
import com.example.source.SearchBook
import org.json.JSONObject

/** Save the original reading destination before a statistics lookup needs the network. */
internal object ReadingRecordMetadata {
    private fun preferences(context: Context) =
        context.getSharedPreferences("reading_record_metadata", Context.MODE_PRIVATE)

    private fun key(bookId: Int?, title: String) = bookId?.let { "book:$it" } ?: "title:$title"

    fun remember(context: Context, book: SearchBook, bookId: Int? = null, recordId: Int? = null) {
        if (book.title.isBlank()) return
        val json = JSONObject().apply {
            put("title", book.title)
            put("sourceId", book.sourceId)
            put("comicId", book.comicId?.takeIf { it.isNotBlank() } ?: book.id)
            put("cover", book.cover.orEmpty())
            put("author", book.author)
        }.toString()
        preferences(context).edit().apply {
            putString(key(bookId, book.title), json)
            recordId?.let { putString("record:$it", json) }
        }.apply()
    }

    fun remember(context: Context, book: Book, recordId: Int? = null) = remember(context, SearchBook(
        id = book.comicId ?: "local:${book.id}", sourceId = book.sourceId.orEmpty(),
        title = book.title, author = book.author, cover = book.coverUri
    ), book.id, recordId)

    fun find(context: Context, record: ReadingRecord): SearchBook? {
        val prefs = preferences(context)
        return decode(prefs.getString("record:${record.id}", null), record.bookTitle)
            ?: decode(prefs.getString(key(record.bookId, record.bookTitle), null), record.bookTitle)
    }

    fun decode(json: String?, title: String): SearchBook? = runCatching {
        val obj = JSONObject(json ?: return null)
        if (obj.optString("title") != title) return null
        val id = obj.optString("comicId")
        if (id.isBlank() || id.startsWith("record_")) return null
        SearchBook(id = id, sourceId = obj.optString("sourceId"), title = title,
            author = obj.optString("author"), cover = obj.optString("cover").ifBlank { null })
    }.getOrNull()
}
