package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.source.anilist.AniListMediaTitles
import com.example.source.anilist.SearchVariantBuilder
import com.example.source.anilist.TitleNormalizer

/**
 * 第七轮验收测试（data / search / privacy 域）：
 * - 第 4 条：今日分钟换算
 * - 第 6 条：分类迁移（6→7：isProtected + 默认种子 + 书籍归位）、PIN 管理器
 * - 第 7 条：标题归一化 / 变体构建 / toRows 字段跳过 / AniList DAO 查询
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Round7DataTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /* ── 第 4 条：今日分钟换算 ── */

    @Test
    fun todayMinutes_exactFiveMinutes() {
        // 精确阅读 5 分钟（含 30s flush 粒度 ±2s 误差）→ 显示 5 分钟
        assertEquals(5, com.example.ui.todayReadMinutes(300L))
        assertEquals(5, com.example.ui.todayReadMinutes(299L))
        assertEquals(5, com.example.ui.todayReadMinutes(301L))
    }

    @Test
    fun todayMinutes_zeroStaysZero() {
        // 旧 bug 回归契约：0 分钟不得被 coerceAtLeast(1) 显示为"1 分钟"
        assertEquals(0, com.example.ui.todayReadMinutes(0L))
        assertEquals(0, com.example.ui.todayReadMinutes(29L))
    }

    /* ── 第 6 条：迁移 6→7 ── */

    /** 手工搭 v6 形态库，跑 MIGRATION_6_7 后验证默认分类与书籍归位语义 */
    @Test
    fun migration6to7_seedsDefaultAndRelocatesBooks() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE categories (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE books (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "title TEXT NOT NULL, author TEXT NOT NULL, filePath TEXT NOT NULL, " +
                                "coverUri TEXT, category TEXT NOT NULL DEFAULT '未分类', " +
                                "currentChapterIndex INTEGER NOT NULL, scrollOffset INTEGER NOT NULL, " +
                                "isFinished INTEGER NOT NULL, totalChapters INTEGER NOT NULL, " +
                                "contentType TEXT NOT NULL, addedTime INTEGER NOT NULL, lastReadTime INTEGER NOT NULL)"
                        )
                        db.execSQL("INSERT INTO categories (name) VALUES ('小说')")
                        fun book(title: String, category: String) {
                            db.execSQL(
                                "INSERT INTO books (title, author, filePath, category, currentChapterIndex, " +
                                    "scrollOffset, isFinished, totalChapters, contentType, addedTime, lastReadTime) " +
                                    "VALUES ('$title', 'a', 'p', '$category', 0, 0, 0, 10, 'NOVEL', 0, 0)"
                            )
                        }
                        book("A", "未分类")
                        book("B", "全部")
                        book("C", "不存在的分类")
                        book("D", "小说")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_6_7.migrate(db)

        db.query("SELECT COUNT(*) FROM categories WHERE name = '默认'").use { c ->
            c.moveToFirst()
            assertEquals("默认分类应被种子插入", 1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM categories WHERE isProtected = 0").use { c ->
            c.moveToFirst()
            assertTrue("isProtected 列已添加且默认 0", c.getInt(0) >= 2)
        }
        // 未分类 / 全部 / 孤儿分类 → 默认；已有归属不动
        db.query("SELECT category FROM books ORDER BY id").use { c ->
            val cats = ArrayList<String>()
            while (c.moveToNext()) cats.add(c.getString(0))
            assertEquals(listOf("默认", "默认", "默认", "小说"), cats)
        }

        // 7→8：anilist_titles 表可写入可查询
        AppDatabase.MIGRATION_7_8.migrate(db)
        db.execSQL(
            "INSERT INTO anilist_titles (mediaId, titleType, rawTitle, normalizedTitle, compactTitle) " +
                "VALUES (1, 'ROMAJI', 'Mushoku Tensei', 'mushoku tensei', 'mushokutensei')"
        )
        db.query("SELECT COUNT(*) FROM anilist_titles").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        helper.close()
    }

    /* ── 第 6.4 条：PrivacyManager ── */

    @Test
    fun privacyManager_pinLifecycle() {
        val pm = PrivacyManager(ctx)
        assertFalse(pm.isEnabled())
        assertFalse(pm.hasPin())

        // 非法 PIN 拒绝
        assertFalse(pm.enableWithPin("12345"))     // 太短
        assertFalse(pm.enableWithPin("12345a"))    // 非数字
        assertFalse(pm.enableWithPin("1234567"))   // 太长

        // 设置 + 启用
        assertTrue(pm.enableWithPin("135790"))
        assertTrue(pm.isEnabled())
        assertTrue(pm.hasPin())

        // 验证
        assertTrue(pm.verifyPin("135790"))
        assertFalse(pm.verifyPin("135791"))
        assertFalse(pm.verifyPin(""))

        // 修改 PIN（旧 PIN 错误 / 正确）
        assertFalse(pm.changePin("111111", "246810"))
        assertTrue(pm.changePin("135790", "246810"))
        assertFalse(pm.verifyPin("135790"))
        assertTrue(pm.verifyPin("246810"))

        // 关闭（PIN 错误 / 正确）
        assertFalse(pm.disable("000000"))
        assertTrue(pm.disable("246810"))
        assertFalse(pm.isEnabled())
        // PIN 保留：重新启用后旧 PIN 仍可用（关闭≠遗忘）
        assertTrue(pm.changePin("246810", "135790"))
    }

    /* ── 第 7 条：标题归一化 ── */

    @Test
    fun titleNormalizer_caseWidthPunctSpaces() {
        // 大小写
        assertEquals("mushoku tensei", TitleNormalizer.normalize("Mushoku Tensei"))
        // 全角字母/数字（NFKC 折半角）
        assertEquals("abc 123", TitleNormalizer.normalize("ＡＢＣ　１２３"))
        // 常见标点 → 空格 + 空白压缩
        assertEquals("re zero", TitleNormalizer.normalize("Re:Zero"))
        assertEquals("re zero", TitleNormalizer.normalize("Re：Zero"))
        assertEquals("无职转生", TitleNormalizer.normalize("无职转生"))
        assertEquals("无职转生", TitleNormalizer.normalize("《无职转生》"))
        // 紧凑形态：去空白（拉丁词间空格也去）
        assertEquals("mushokutensei", TitleNormalizer.compact("Mushoku Tensei"))
        assertEquals("rezero", TitleNormalizer.compact("Re:Zero"))
        // 空输入
        assertEquals("", TitleNormalizer.normalize("   "))
    }

    @Test
    fun titleNormalizer_cjkLatinUnified() {
        // 同一关键词的两种写法归一到同一匹配形态（中英日混合匹配的基础）
        assertEquals(
            TitleNormalizer.normalize("MUSHOKU TENSEI"),
            TitleNormalizer.normalize("mushoku tensei"),
        )
        assertEquals(
            TitleNormalizer.compact("MushokuTensei"),
            TitleNormalizer.compact("Mushoku Tensei"),
        )
    }

    @Test
    fun variantBuilder_originalFirst_capAndDedupe() {
        // 原始关键词永远在首位
        val variants = SearchVariantBuilder.build(
            "无职转生",
            listOf("Mushoku Tensei", "無職転生", "無職転生 ～異世界行ったら本気だす～", "x"),
        )
        assertEquals("无职转生", variants.first())
        assertTrue(variants.contains("Mushoku Tensei"))
        // 第十一轮（繁简折叠）：短名"無職転生"折叠后与关键词"无职转生"同形，按去重规则
        // 不再重复发送；完整原名因带副标题仍保留为独立变体
        assertFalse(variants.contains("無職転生"))
        assertTrue(variants.contains("無職転生 ～異世界行ったら本気だす～"))
        // 归一化后过短的噪音词被过滤
        assertFalse(variants.contains("x"))
        // 上限
        val many = SearchVariantBuilder.build("kw", (1..20).map { "variant$it" })
        assertTrue(many.size <= SearchVariantBuilder.MAX_VARIANTS)
        // 去重（compact 相同视为同一变体，原始关键词始终保留）
        val allDup = SearchVariantBuilder.build("Alpha", listOf("alpha", "ALPHA ", "αlpha".replace("α", "a")))
        assertEquals(listOf("Alpha"), allDup)
        val mixed = SearchVariantBuilder.build("无职转生", listOf("Mushoku Tensei", "mushoku tensei "))
        assertEquals(listOf("无职转生", "Mushoku Tensei"), mixed)
    }

    @Test
    fun mediaTitles_rowsSkipMissingFields() {
        val rows = AniListMediaTitles(
            mediaId = 101,
            romaji = "Mushoku Tensei: Jobless Reincarnation",
            english = null,          // 缺 english：跳过
            native = "無職転生",
            synonyms = listOf("", "  ", "Mushoku Tensei"), // 空白 synonym 跳过；重复保留原文由唯一索引去重
        ).toRows()
        // romaji + native + 一个非空 synonym = 3 行；english 不产出行
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.titleType == "ENGLISH" })
        assertTrue(rows.any { it.normalizedTitle == "mushoku tensei jobless reincarnation" })
        // 第十一轮（繁简折叠）：日文原名的归一化/紧凑列为折叠后的简体形态
        assertTrue(rows.any { it.rawTitle == "無職転生" && it.compactTitle == "无职转生" })
    }

    /* ── 第 7 条：AniList DAO（Room in-memory） ── */

    private lateinit var db: AppDatabase

    @Before
    fun setUpDb() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDownDb() {
        db.close()
    }

    @Test
    fun anilistDao_findByAnyLanguageForm() = runBlocking {
        val rows = listOf(
            AniListTitleEntity(mediaId = 101, titleType = "ROMAJI", rawTitle = "Mushoku Tensei", normalizedTitle = "mushoku tensei", compactTitle = "mushokutensei"),
            AniListTitleEntity(mediaId = 101, titleType = "NATIVE", rawTitle = "無職転生", normalizedTitle = "無職転生", compactTitle = "無職転生"),
            AniListTitleEntity(mediaId = 101, titleType = "SYNONYM", rawTitle = "无职转生", normalizedTitle = "无职转生", compactTitle = "无职转生"),
            AniListTitleEntity(mediaId = 202, titleType = "ROMAJI", rawTitle = "Sousou no Frieren", normalizedTitle = "sousou no frieren", compactTitle = "sousounofrieren"),
        )
        db.anilistDao().insertTitles(rows)
        assertEquals(4, db.anilistDao().countTitles())

        // 任意语言形态 → 同一作品
        listOf(
            "mushoku tensei" to "mushokutensei",
            "無職転生" to "無職転生",
            "无职转生" to "无职转生",
            "MushokuTensei" to "mushokutensei",
        ).forEach { (norm, comp) ->
            val ids = db.anilistDao().findMediaIds(norm, comp)
            assertEquals(listOf(101), ids)
        }
        // 变体扩展：101 的全部标题原文可取回
        val titles = db.anilistDao().getRawTitlesFor(listOf(101))
        assertEquals(3, titles.size)
        assertTrue(titles.contains("Mushoku Tensei"))

        // 未命中
        assertTrue(db.anilistDao().findMediaIds("no such title", "nosuchtitle").isEmpty())

        // IGNORE 策略：重复插入不膨胀
        db.anilistDao().insertTitles(rows)
        assertEquals(4, db.anilistDao().countTitles())
    }

    /* ── 第 6.1 条：默认分类常量与守卫 ── */

    @Test
    fun repository_defaultCategoryGuard() = runBlocking {
        val repo = BookRepository(ctx, db.bookDao(), db)
        repo.ensureDefaultCategory()
        repo.ensureDefaultCategory() // 幂等
        val def = db.bookDao().getCategoryByName(DEFAULT_CATEGORY)
        assertNotNull("默认分类必须存在", def)
        // 默认分类删除被拒绝
        assertFalse(repo.deleteCategory(def!!))
        // 用户分类可删，且书籍迁回默认
        repo.addCategory("测试分类")
        val user = db.bookDao().getCategoryByName("测试分类")
        assertNotNull(user)
        db.bookDao().insertBook(Book(title = "B1", filePath = "p1", category = "测试分类"))
        assertTrue(repo.deleteCategory(user!!))
        db.bookDao().getAllBooksSync().first { it.title == "B1" }.let {
            assertEquals(DEFAULT_CATEGORY, it.category)
        }
    }
}
