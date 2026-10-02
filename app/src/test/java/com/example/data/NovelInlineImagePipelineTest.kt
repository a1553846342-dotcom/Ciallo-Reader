package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ui.reader.NovelInlineImages
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream

/**
 * 小说内嵌图片全链路回归（epzip 零副本架构）：
 * 1) EPUB 导入时 <img> 提取为 epzip 零副本占位符（不落盘图片文件）；
 * 2) 旧书（无占位符章节）经 migrateInlineImagesIfNeeded 重解析后占位符恢复；
 * 3) 渲染层 NovelInlineImages.TOKEN_REGEX 与导入产出的引用格式兼容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelInlineImagePipelineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    // 1x1 透明 PNG
    private val pngBytes = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=",
        android.util.Base64.DEFAULT
    )

    @Before
    fun setUp() = runBlocking<Unit> {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        context.getSharedPreferences("book_migration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 构造带一张内嵌图的最小 EPUB（mimetype + container + opf + xhtml + png）。 */
    private fun buildEpubWithImage(dest: File) {
        val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch1</title></head>
<body><p>第一段文字，图片之前。</p><img src="images/pic.png" alt="内嵌图"/><p>第二段文字，图片之后。</p></body></html>"""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>内嵌图测试书</dc:title><dc:creator>测试作者</dc:creator>
    <dc:identifier id="id">inline-img-test-uid</dc:identifier>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="pic" href="images/pic.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""
        val container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        java.util.zip.ZipOutputStream(FileOutputStream(dest)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml")); zip.write(container.toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf")); zip.write(opf.toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/ch1.xhtml")); zip.write(xhtml.toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/images/pic.png")); zip.write(pngBytes); zip.closeEntry()
        }
    }

    private fun fileUri(f: File) = android.net.Uri.fromFile(f)

    @Test
    fun epubImportExtractsInlineImagesAsEpzipRefs() = runBlocking {
        val epubFile = File(context.cacheDir, "inline_img_test_${System.currentTimeMillis()}.epub")
        buildEpubWithImage(epubFile)

        val result = EpubParser.importEpub(
            context,
            fileUri(epubFile),
            epubFile.name,
            db.bookDao()
        )
        assertTrue("导入失败: ${result.exceptionOrNull()}", result.isSuccess)
        val book = result.getOrThrow()
        assertTrue("章节数异常", book.totalChapters >= 1)

        val chapters = db.bookDao().getChaptersListForBook(book.id)
        val withToken = chapters.firstOrNull { it.content.contains("[IMG:") }
        assertNotNull("章节正文未找到 [IMG: 占位符 —— 图片提取链路断裂", withToken)

        val m = withToken?.let { NovelInlineImages.TOKEN_REGEX.find(it.content) }
        assertNotNull("渲染层 TOKEN_REGEX 无法解析导入产出的占位符", m)
        val imgPath = m!!.groupValues[1]
        assertTrue(
            "EPUB 内图应为 epzip 零副本引用: $imgPath",
            imgPath.startsWith("epzip:") && imgPath.contains("pic.png")
        )
        assertEquals(
            "file:// 引用应能从 EPUB 原包读取图片字节",
            pngBytes.toList(),
            NovelInlineImages.NovelImageCache.readEpubImageBytes(imgPath)?.toList()
        )
        assertNotNull(
            "file:// 引用应能解码为阅读页位图",
            NovelInlineImages.NovelImageCache.decodeSampledFromEpub(imgPath)
        )

        // 零副本：不落盘图片文件（epub_images 目录应为空或不存在）
        val bookImageDir = File(context.filesDir, "epub_images/${book.id}")
        val copied = bookImageDir.listFiles()?.size ?: 0
        assertEquals("零副本方案不应落盘 EPUB 内图片", 0, copied)
    }

    @Test
    fun migrationRestoresImagesForLegacyBook() = runBlocking {
        // 先正常导入拿到 bookId 与解析结果
        val epubFile = File(context.cacheDir, "legacy_img_test_${System.currentTimeMillis()}.epub")
        buildEpubWithImage(epubFile)
        val imported = EpubParser.importEpub(
            context, fileUri(epubFile), epubFile.name, db.bookDao()
        ).getOrThrow()

        // 模拟旧版本导入的书：章节正文剥掉全部占位符（旧解析器丢弃 <img> 的结果）
        val legacyChapters = db.bookDao().getChaptersListForBook(imported.id).map {
            it.copy(id = 0, content = it.content.replace(Regex("\\[IMG:[^]]*]"), ""))
        }
        db.bookDao().deleteChaptersForBook(imported.id)
        db.bookDao().insertChapters(legacyChapters)
        db.bookDao().updateBook(imported.copy(totalChapters = legacyChapters.size))

        // 迁移前：无占位符
        assertTrue(
            "前置条件失败：旧章节不应含占位符",
            db.bookDao().searchChapters(imported.id, "[IMG:").isEmpty()
        )

        val repo = BookRepository(context, db.bookDao(), db)
        val migrated = repo.migrateInlineImagesIfNeeded(imported)
        assertNotNull("迁移未执行（返回 null）—— 检查嗅探/标记逻辑", migrated)

        val after = db.bookDao().searchChapters(imported.id, "[IMG:")
        assertTrue("迁移后仍未提取到图片占位符", after.isNotEmpty())
        val total = db.bookDao().getChaptersListForBook(imported.id).size
        assertTrue("迁移后章节数为 0", total > 0)
        // 零副本：迁移后引用为 epzip 形态
        assertTrue("迁移后引用应为 epzip 形态", after.first().content.contains("epzip:"))
    }
}
