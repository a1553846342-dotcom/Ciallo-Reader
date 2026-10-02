package com.example.data

/** Lock order: download controls -> worker task lock -> content gate -> Room transaction. */
internal object ContentMutationGate {
    val mutex=kotlinx.coroutines.sync.Mutex()
    @Volatile var epoch=0L
        private set
    fun invalidatePendingWrites() { epoch++ } // Caller holds mutex.
}
