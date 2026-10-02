package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Recover early schemas without destructive fallback. Unknown columns remain in legacy copies. */
internal class LegacyDatabaseMigration(from: Int) : Migration(from, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (sql in TABLES) {
            val table = sql.substringAfter("`").substringBefore("`")
            val exists = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
            if (!exists) { db.execSQL(sql); continue }
            val old = "legacy_v${startVersion}_$table"
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", arrayOf(table)).use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                names.forEach { db.execSQL("DROP INDEX `${it.replace("`", "``")}`") }
            }
            db.execSQL("ALTER TABLE `$table` RENAME TO `$old`")
            db.execSQL(sql)
            val oldCols = db.query("PRAGMA table_info(`$old`)").use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            val names = mutableListOf<String>()
            val values = mutableListOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    names.add("`$name`")
                    values.add(when {
                        name in oldCols -> "`$name`"
                        c.getInt(3) == 0 -> "NULL"
                        name == "category" -> "'默认'"
                        name == "contentType" -> "'NOVEL'"
                        name == "author" -> "'未知作者'"
                        name == "colorHex" -> "'#7FD8C8'"
                        c.getString(2) == "TEXT" -> "''"
                        else -> "0"
                    })
                }
            }
            INDEXES.filter { it.contains("ON `$table`") }.forEach { db.execSQL(it) }
            val ordering=if("createdTime" in oldCols) " ORDER BY createdTime DESC" else ""
            db.execSQL("INSERT OR IGNORE INTO `$table` (${names.joinToString()}) SELECT ${values.joinToString()} FROM `$old`$ordering")
        }
        INDEXES.forEach { db.execSQL(it) }
    }
    companion object {
        private val TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `filePath` TEXT NOT NULL, `coverUri` TEXT, `category` TEXT NOT NULL, `currentChapterIndex` INTEGER NOT NULL, `scrollOffset` INTEGER NOT NULL, `isFinished` INTEGER NOT NULL, `totalChapters` INTEGER NOT NULL, `contentType` TEXT NOT NULL, `addedTime` INTEGER NOT NULL, `lastReadTime` INTEGER NOT NULL, `sourceId` TEXT, `comicId` TEXT)",
            "CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `chapterOrder` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `startCharIndex` INTEGER NOT NULL, `endCharIndex` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `scrollOffset` INTEGER NOT NULL, `title` TEXT NOT NULL, `snippet` TEXT NOT NULL, `createdTime` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `selectedText` TEXT NOT NULL, `note` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `createdTime` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isProtected` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `reading_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER, `bookTitle` TEXT NOT NULL, `dateStr` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `reading_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER, `bookTitle` TEXT NOT NULL, `dateStr` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `startHour` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `download_tasks` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUrl` TEXT, `downloadUrl` TEXT NOT NULL, `format` TEXT NOT NULL, `status` TEXT NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `errorMessage` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `anilist_titles` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, `titleType` TEXT NOT NULL, `rawTitle` TEXT NOT NULL, `normalizedTitle` TEXT NOT NULL, `compactTitle` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `favorites` (`sourceId` TEXT NOT NULL, `comicId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `coverUrl` TEXT, `localThumbPath` TEXT, `serialStatus` TEXT NOT NULL, `latestChapterId` TEXT, `latestChapterTitle` TEXT, `latestChapterUpdateAt` INTEGER NOT NULL, `lastCheckedAt` INTEGER NOT NULL, `sourceAlive` INTEGER NOT NULL, `categoryName` TEXT NOT NULL, `favoritedAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `comicId`))",
            "CREATE TABLE IF NOT EXISTS `comic_progress` (`sourceId` TEXT NOT NULL, `comicId` TEXT NOT NULL, `lastChapterId` TEXT, `lastChapterIndex` INTEGER NOT NULL, `lastPageIndex` INTEGER NOT NULL, `lastPageCount` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, `seenTopChapterId` TEXT, `seenChapterCount` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `comicId`))",
            "CREATE TABLE IF NOT EXISTS `comic_chapter_read` (`sourceId` TEXT NOT NULL, `comicId` TEXT NOT NULL, `chapterId` TEXT NOT NULL, `status` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `bookmarked` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `comicId`, `chapterId`))",
            "CREATE TABLE IF NOT EXISTS `favorite_categories` (`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`))",
            "CREATE TABLE IF NOT EXISTS `god_moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contentType` TEXT NOT NULL, `bookId` TEXT NOT NULL, `chapterId` TEXT NOT NULL, `bookTitle` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, `chapterNumber` INTEGER NOT NULL, `title` TEXT NOT NULL, `titleIsCustom` INTEGER NOT NULL, `rating` REAL NOT NULL, `note` TEXT NOT NULL, `coverPath` TEXT, `coverSource` TEXT NOT NULL, `cropParams` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        private val INDEXES = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_bookId_chapterIndex` ON `bookmarks` (`bookId`, `chapterIndex`)",
            "CREATE INDEX IF NOT EXISTS `index_anilist_titles_normalizedTitle` ON `anilist_titles` (`normalizedTitle`)",
            "CREATE INDEX IF NOT EXISTS `index_anilist_titles_compactTitle` ON `anilist_titles` (`compactTitle`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_anilist_titles_mediaId_titleType_rawTitle` ON `anilist_titles` (`mediaId`, `titleType`, `rawTitle`)",
            "CREATE INDEX IF NOT EXISTS `index_favorites_categoryName` ON `favorites` (`categoryName`)",
            "CREATE INDEX IF NOT EXISTS `index_favorites_favoritedAt` ON `favorites` (`favoritedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_favorites_lastCheckedAt` ON `favorites` (`lastCheckedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_comic_progress_lastReadAt` ON `comic_progress` (`lastReadAt`)",
            "CREATE INDEX IF NOT EXISTS `index_comic_chapter_read_sourceId_comicId` ON `comic_chapter_read` (`sourceId`, `comicId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_god_moments_bookId_chapterId` ON `god_moments` (`bookId`, `chapterId`)",
            "CREATE INDEX IF NOT EXISTS `index_god_moments_rating` ON `god_moments` (`rating`)",
            "CREATE INDEX IF NOT EXISTS `index_god_moments_createdAt` ON `god_moments` (`createdAt`)",
        )
    }
}
