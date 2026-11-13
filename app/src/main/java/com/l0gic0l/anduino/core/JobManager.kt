package com.l0gic0l.anduino.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.l0gic0l.anduino.service.WorkService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JobState(
    val title: String,
    val step: String? = null,
    val progress: Float? = null,
    val startedAt: Long = System.currentTimeMillis(),
)

class JobScope internal constructor(private val manager: JobManager, val title: String) {
    /** Updates the status line and the notification. [progress] null = indeterminate. */
    fun step(step: String?, progress: Float? = null) = manager.update(JobState(title, step, progress))
}

/**
 * Runs one long operation at a time (install, compile, upload...). arduino-cli is not
 * safe to run concurrently against the same data directory, so this also serialises
 * access to it. While a job runs, [WorkService] keeps the process in the foreground so
 * Android doesn't kill a 10 minute toolchain download when the app is backgrounded.
 */
class JobManager(private val context: Context, private val scope: CoroutineScope, private val console: ConsoleLog) {
    private val _current = MutableStateFlow<JobState?>(null)
    val current: StateFlow<JobState?> = _current.asStateFlow()

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Returns false (and does nothing) if another job is running. */
    fun launch(title: String, block: suspend JobScope.() -> Unit): Boolean {
        synchronized(this) {
            if (isBusy) return false
            _current.value = JobState(title)
            job = scope.launch(Dispatchers.IO) {
                startService()
                try {
                    JobScope(this@JobManager, title).block()
                } catch (e: CancellationException) {
                    console.error("$title cancelled.")
                    throw e
                } catch (e: Exception) {
                    console.error("$title failed: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    // WorkService watches this and stops itself.
                    _current.value = null
                }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    internal fun update(state: JobState) {
        val current = _current.value ?: return
        _current.value = state.copy(startedAt = current.startedAt)
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java))
        }
    }

}
