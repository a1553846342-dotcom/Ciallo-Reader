package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.reader.NovelInlineImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * 用用户提供的真实 EPUB（《败北女角太多了！》，z-library 来源）做全链路实测：
 * 1) 导入后图片占位符是否真的提取（用户实测：插图位置没有图片）；
 * 2) 搜索"插图"的结果里是否还存在不含关键词的条目（用户实测：仍有）。
 * 每一步打印真实数据，失败处即断点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RealEpubPipelineTest {

    private val realEpub = File(
        "C:\\Users\\GuanXingRen\\Downloads\\败北女角太多了！(败犬女主太多了！) (作者：雨森たきび) (z-library.sk, 1lib.sk, z-lib.sk).epub"
    )

    private fun fileUri(f: File) = android.net.Uri.parse("file://" + f.absolutePath.replace('\\', '/'))

    @Test
    fun realEpubImportSearchMigrate() = runBlocking {
        ShadowLog.stream = System.out
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("真实 EPUB 文件不存在: $realEpub", realEpub.exists())
        // in-memory DB：磁盘单例在 Robolectric 线程池复用下偶发 "Illegal connection pointer"
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // ── 1. 全新导入：token 提取 ──
        val imported = EpubParser.importEpub(context, fileUri(realEpub), realEpub.name, db.bookDao())
        assertTrue("导入失败: ${imported.exceptionOrNull()}", imported.isSuccess)
        val book = imported.getOrThrow()
        val chapters = db.bookDao().getChaptersListForBook(book.id)
        val tokenChapters = chapters.filter { it.content.contains("[IMG:") }
        val tokenCount = chapters.sumOf { NovelInlineImages.TOKEN_REGEX.findAll(it.content).count() }
        println("[REAL] 章节=${chapters.size} 含token章=${tokenChapters.size} token总数=$tokenCount")
        tokenChapters.take(3).forEach { ch ->
            NovelInlineImages.TOKEN_REGEX.findAll(ch.content).take(2).forEach {
                val f = File(it.groupValues[1])
                println("[REAL] token → exists=${f.exists()} len=${f.length()} path=${it.groupValues[1]}")
            }
        }
        val epzipCount = chapters.sumOf { it.content.split("epzip:").size - 1 }
        println("[REAL] epzip 引用数=$epzipCount")
        println("[REAL] >>> 问题1判定：token总数 ${if (tokenCount > 0) "> 0（提取链路 OK）" else "== 0（解析层断）"}")

        // ── 2. 搜索"插图"：结果条目是否全部含关键词 ──
        val query = "插图"
        val matched = chapters.filter { it.content.contains(query, ignoreCase = true) }
        println("[REAL] LIKE/contains 命中章数=${matched.size}")
        val results = SearchLocator.buildResults(matched, query, { it }, { null })
        println("[REAL] 搜索结果条数=${results.size}")
        val bad = results.filter { r ->
            val inner = r.snippet.trimStart('.').trimEnd('.')
            !inner.contains(query, ignoreCase = true)
        }
        bad.take(5).forEach { println("[REAL] 无关键词条目: occ=${it.occurrence} ch=${it.chapterIndex} snippet=${it.snippet}") }
        println("[REAL] >>> 问题2判定：无关键词条目 ${bad.size}/${results.size} ${if (bad.isEmpty()) "（数据层 OK，问题在 UI 层或旧包）" else "（数据层就有）"}")

        // ── 3. 旧书迁移：剥 token 后重解析恢复 ──
        db.bookDao().deleteChaptersForBook(book.id)
        db.bookDao().insertChapters(chapters.map { it.copy(id = 0, content = NovelInlineImages.stripTokens(it.content)) })
        val repo = BookRepository(context, db.bookDao(), db)
        val migrated = repo.migrateInlineImagesIfNeeded(book.copy(totalChapters = chapters.size))
        val after = db.bookDao().getChaptersListForBook(book.id)
        val afterTokens = after.sumOf { NovelInlineImages.TOKEN_REGEX.findAll(it.content).count() }
        println("[REAL] 迁移执行=${migrated != null} 迁移后章=${after.size} token总数=$afterTokens")
        println("[REAL] 迁移后 filePath=${db.bookDao().getBookById(book.id)?.filePath}".take(180))
        println("[REAL] >>> 迁移判定 ${if (afterTokens > 0) "OK" else "失败"}")

        // 数据层硬断言
        assertTrue("真实 EPUB 导入后没有提取到任何插图 token —— 插图位置必然空白", tokenCount > 0)
        assertTrue("搜索结果数据层存在无关键词条目", bad.isEmpty())
        assertTrue("旧书迁移后没有恢复 token", afterTokens > 0)
    }
}
