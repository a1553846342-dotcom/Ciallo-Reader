package com.example.source.js

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compatibility cleanup for the pinned quickjs-kt 1.0.0-alpha13 binding.
 * A failed bridge can cancel its internal scope. Settle pending native work and
 * defensively prune completed jobs before reuse. Closing one runtime is unsafe
 * with other live alpha13 contexts.
 * Keep this adapter isolated; the existing ProGuard rule preserves QuickJs fields.
 */
internal object QuickJsAsyncLifecycle {
    private fun field(name: String) = QuickJs::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val jobsField = field("asyncJobs")
    private val mutexField = field("jobsMutex")
    private val scopeField = field("coroutineScope")
    private val dispatcherField = field("jobDispatcher")
    private val handlerField = field("exceptionHandler")
    private val exceptionField = field("evalException")

    /** Called under the source's mutex, after its evaluation has completed. */
    suspend fun settle(runtime: QuickJs) {
        val mutex = mutexField.get(runtime) as Mutex
        @Suppress("UNCHECKED_CAST")
        val jobs = jobsField.get(runtime) as MutableList<Job>
        val pending = mutex.withLock { jobs.filter { !it.isCompleted } }
        pending.joinAll()
        mutex.withLock { jobs.removeAll { it.isCompleted } }
        val scope = scopeField.get(runtime) as CoroutineScope
        if (scope.coroutineContext[Job]?.isActive == false) {
            scopeField.set(runtime, CoroutineScope((dispatcherField.get(runtime) as CoroutineDispatcher) +
                (handlerField.get(runtime) as CoroutineExceptionHandler)))
        }
        exceptionField.set(runtime, null)
        runtime.gc()
    }
}
