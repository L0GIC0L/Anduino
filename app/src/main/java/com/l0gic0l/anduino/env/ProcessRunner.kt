package com.l0gic0l.anduino.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** A piece of process output. [transient] is set for `\r`-terminated progress updates. */
data class OutputLine(val text: String, val isError: Boolean, val transient: Boolean = false)

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

object ProcessRunner {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun stripAnsi(s: String): String = ANSI.replace(s, "")

    /**
     * Starts [builder] and streams stdout/stderr line by line to [onOutput]
     * (called from reader threads). Cancelling the coroutine kills the process.
     */
    suspend fun stream(builder: ProcessBuilder, onOutput: (OutputLine) -> Unit): Int {
        val process = withContext(Dispatchers.IO) { builder.start() }
        runCatching { process.outputStream.close() }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { kill(process) }
            thread(name = "process-wait", isDaemon = true) {
                val out = thread(name = "process-stdout", isDaemon = true) { pump(process.inputStream, false, onOutput) }
                val err = thread(name = "process-stderr", isDaemon = true) { pump(process.errorStream, true, onOutput) }
                val code = process.waitFor()
                out.join(5000)
                err.join(5000)
                cont.resume(code) // ignored if already cancelled
            }
        }
    }

    /** Runs [builder] to completion and returns everything it printed. */
    suspend fun capture(builder: ProcessBuilder): ExecResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val lock = Any()
        val code = stream(builder) { line ->
            if (line.transient) return@stream
            synchronized(lock) { (if (line.isError) err else out).append(line.text).append('\n') }
        }
        return ExecResult(code, out.toString(), err.toString())
    }

    private fun kill(process: Process) {
        process.destroy()
        thread(isDaemon = true) {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun pump(stream: InputStream, isError: Boolean, onOutput: (OutputLine) -> Unit) {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val buf = CharArray(4096)
        val line = StringBuilder()
        var pendingCr = false
        while (true) {
            val n = try {
                reader.read(buf)
            } catch (_: java.io.IOException) {
                -1
            }
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (pendingCr) {
                    pendingCr = false
                    if (c != '\n') {
                        // A lone \r: progress bars redraw the line; report it as transient.
                        if (line.isNotEmpty()) onOutput(OutputLine(stripAnsi(line.toString()), isError, transient = true))
                        line.clear()
                    } else {
                        onOutput(OutputLine(stripAnsi(line.toString()), isError))
                        line.clear()
                        continue
                    }
                }
                when (c) {
                    '\n' -> {
                        onOutput(OutputLine(stripAnsi(line.toString()), isError))
                        line.clear()
                    }
                    '\r' -> pendingCr = true
                    else -> line.append(c)
                }
            }
        }
        if (line.isNotEmpty()) onOutput(OutputLine(stripAnsi(line.toString()), isError))
    }
}
