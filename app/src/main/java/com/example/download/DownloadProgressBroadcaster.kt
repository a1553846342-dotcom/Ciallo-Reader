package com.example.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DownloadProgressBroadcaster {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun updateState(bookId: String, state: DownloadState) {
        _states.update { it + (bookId to state) }
    }

    fun removeState(bookId: String) {
        _states.update { it - bookId }
    }

    fun getState(bookId: String): DownloadState {
        return _states.value[bookId] ?: DownloadState.Idle
    }
}
