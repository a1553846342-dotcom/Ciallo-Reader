package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单次阅读会话（新统计口径）。
 * 每次“阅读器可见且 App 在前台”的连续时段记一行，用于：
 *  - 日历视图的时段明细
 *  - 趋势图的高峰时段分布（startHour）
 * 注意：阅读总时长仍以 reading_records（天 x 书）为聚合基准，
 * 会话表只补充明细/时段信息，避免两条链路重复累加。
 */
@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Int?,
    val bookTitle: String,
    /** 会话开始时间的本地日期 yyyy-MM-dd（跨天会话按本地午夜拆分）。 */
    val dateStr: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSeconds: Long,
    /** 0-23，会话开始小时，用于高峰时段分布。 */
    val startHour: Int
)

/** 某天阅读总时长（日历热力图 / 趋势用）。 */
data class DailyReadingTotal(
    val dateStr: String,
    val totalSeconds: Long
)

/** 某月阅读总时长。 */
data class MonthlyReadingTotal(
    val month: String,
    val totalSeconds: Long
)

/** 某年阅读总时长。 */
data class YearlyReadingTotal(
    val year: String,
    val totalSeconds: Long
)
