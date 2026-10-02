package com.example.god

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 神回 ViewModel。
 *
 * 排行榜（统计页 / 全屏页）与编辑窗口监听**同一个** [all] StateFlow：
 * 增删改后两处自动刷新，不存在"改了排行榜不更新"的窗口。
 */
class GodMomentViewModel(application: Application) : AndroidViewModel(application) {

    val repository = GodMomentRepository(
        application,
        AppDatabase.getDatabase(application).godMomentDao(),
    )

    val settings = GodMomentSettingsStore(application)

    /** 全量神回（StateFlow，多处共享） */
    val all: StateFlow<List<GodMomentEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 某本书的神回（chapterId → 实体），书籍详情页用 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun chapterMapOf(bookIdFlow: Flow<String>): Flow<Map<String, GodMomentEntity>> =
        bookIdFlow.flatMapLatest { id ->
            if (id.isBlank()) flowOf(emptyMap()) else repository.observeChapterMap(id)
        }

    fun save(entity: GodMomentEntity, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.save(entity)
            onDone(id)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun deleteForBook(bookId: String) {
        viewModelScope.launch { repository.deleteForBook(bookId) }
    }

    fun deleteForChapter(bookId: String, chapterId: String) {
        viewModelScope.launch { repository.deleteForChapter(bookId, chapterId) }
    }
}
