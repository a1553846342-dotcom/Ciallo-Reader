package com.example.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.example.data.favorite.FavoriteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 书签数据层直测：setChapterBookmark 持久化 + 进度保存/标记已读不抹书签。
 * （REPLACE 整行覆盖是历史实现——合并写入回归必须锁死）
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChapterBookmarkDataTest {

    private lateinit var context: Context
    private lateinit var repo: FavoriteRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FavoriteRepository(dao = db.favoriteDao(), comicSourceOf = { null })
    }

    @Test
    fun bookmarkPersistsAndSurvivesProgressAndMark() = runBlocking {
        val s = "src"
        val c = "comic"

        // 1) 加书签
        repo.setChapterBookmark(s, c, "ch1", 0, true)
        val afterSet = db.favoriteDao().chapterStatesSync(s, c)
        assertTrue("加书签后应持久化", afterSet.first { it.chapterId == "ch1" }.bookmarked)

        // 2) 进度保存（REPLACE 写入）不应抹书签
        repo.saveProgress(s, c, "ch1", 0, pageIndex = 3, pageCount = 10)
        val afterProgress = db.favoriteDao().chapterStatesSync(s, c)
        val prog = afterProgress.first { it.chapterId == "ch1" }
        assertTrue(
            "进度保存抹掉了书签（REPLACE 未合并）",
            prog.bookmarked
        )
        assertTrue("进度字段应更新", prog.pageIndex == 3)

        // 3) 标记已读（REPLACE 写入）不应抹书签
        repo.markChapter(s, c, "ch1", 0, read = true)
        val afterMark = db.favoriteDao().chapterStatesSync(s, c)
        assertTrue(
            "标记已读抹掉了书签（REPLACE 未合并）",
            afterMark.first { it.chapterId == "ch1" }.bookmarked
        )

        // 4) 再滑取消
        repo.setChapterBookmark(s, c, "ch1", 0, false)
        val afterUnset = db.favoriteDao().chapterStatesSync(s, c)
        assertFalse("取消书签后应为 false", afterUnset.first { it.chapterId == "ch1" }.bookmarked)
    }
}
