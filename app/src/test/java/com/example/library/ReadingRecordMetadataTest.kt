package com.example.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ReadingRecord
import com.example.source.SearchBook
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadingRecordMetadataTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("reading_record_metadata", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun book(source: String) = SearchBook("id:$source", source, "同名作品", "作者", "https://cdn.example/cover")

    @Test fun deletingLocalBookRetainsItsOriginalOnlineDestination() {
        ReadingRecordMetadata.remember(context, book("original"), bookId = 12, recordId = 7)
        val deleted = ReadingRecord(7, null, "同名作品", "2026-10-02", 60)
        assertEquals("id:original", ReadingRecordMetadata.find(context, deleted)?.id)
    }

    @Test fun sameTitleRecordsKeepTheirOwnSourcesAndRejectAnotherTitle() {
        ReadingRecordMetadata.remember(context, book("first"), recordId = 1)
        ReadingRecordMetadata.remember(context, book("second"), recordId = 2)
        assertEquals("first", ReadingRecordMetadata.find(context, ReadingRecord(1, null, "同名作品", "2026-10-01", 60))?.sourceId)
        assertEquals("second", ReadingRecordMetadata.find(context, ReadingRecord(2, null, "同名作品", "2026-10-02", 60))?.sourceId)
        assertNull(ReadingRecordMetadata.find(context, ReadingRecord(1, null, "其他作品", "2026-10-01", 60)))
    }
}
