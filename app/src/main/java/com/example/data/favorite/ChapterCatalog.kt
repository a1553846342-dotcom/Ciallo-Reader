package com.example.data.favorite

import android.content.Context
import android.util.AtomicFile
import com.example.source.ComicChapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only unique chapter titles/volumes are reconciled; list positions never prove identity. */
internal class ChapterCatalog(private val context:Context) {
    private fun file(source:String,comic:String):File {
        val key="${source.length}:$source$comic"
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir,"chapter_catalogs").apply { mkdirs() },"$hash.json")
    }
    fun read(source:String,comic:String):List<ComicChapter> = runCatching {
        val f=file(source,comic)
        if(!f.isFile) return@runCatching emptyList()
        require(f.length()<=4L*1024*1024)
        val a=JSONArray(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
        require(a.length()<=10_000)
        (0 until a.length()).map { i -> val o=a.getJSONObject(i)
            ComicChapter(o.getString("id"),o.getString("title"),o.optString("volume").takeIf { it.isNotBlank() },o.optDouble("order",0.0).toFloat()) }
    }.getOrDefault(emptyList())
    fun write(source:String,comic:String,chapters:List<ComicChapter>) {
        require(chapters.size<=10_000)
        val a=JSONArray()
        chapters.forEach { a.put(JSONObject().put("id",it.id).put("title",it.title).put("volume",it.volume ?: "").put("order",it.order)) }
        val raw=a.toString().toByteArray(); require(raw.size<=4*1024*1024)
        val atomic=AtomicFile(file(source,comic)); val out=atomic.startWrite()
        try { out.write(raw); atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out); throw e }
    }
    fun mapping(old:List<ComicChapter>,fresh:List<ComicChapter>):Map<String,ComicChapter> {
        fun key(c:ComicChapter)=(c.volume ?: "").trim().lowercase()+"|"+c.title.trim().lowercase().filterNot { it.isWhitespace() }
        val groupedOld=old.groupBy(::key)
        val groupedNew=fresh.groupBy(::key)
        val byId=fresh.associateBy { it.id }
        return old.mapNotNull { chapter ->
            val exact=byId[chapter.id]?.takeIf { key(it) == key(chapter) }
            val semantic=groupedNew[key(chapter)]?.singleOrNull()?.takeIf { groupedOld[key(chapter)]?.size==1 }
            (exact ?: semantic)?.let { chapter.id to it }
        }.toMap()
    }
}
