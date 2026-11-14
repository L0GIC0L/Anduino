package com.l0gic0l.anduino.core

import com.l0gic0l.anduino.env.OutputLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConsoleKind { OUTPUT, ERROR, COMMAND, INFO, SUCCESS }

data class ConsoleEntry(val id: Long, val text: String, val kind: ConsoleKind, val transient: Boolean = false)

/** The "Output" panel: compile, upload and install logs. Updates are batched every 60 ms. */
class ConsoleLog(scope: CoroutineScope, private val maxEntries: Int = 4000) {
    private val lock = Any()
    private val buffer = ArrayDeque<ConsoleEntry>()
    private var nextId = 0L
    private var dirty = false

    private val _entries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val entries: StateFlow<List<ConsoleEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(60)
                val snapshot = synchronized(lock) {
                    if (!dirty) null else { dirty = false; buffer.toList() }
                }
                if (snapshot != null) _entries.value = snapshot
            }
        }
    }

    fun append(line: OutputLine) {
        val kind = when {
            line.text.startsWith("$ ") -> ConsoleKind.COMMAND
            line.isError -> ConsoleKind.ERROR
            else -> ConsoleKind.OUTPUT
        }
        add(line.text, kind, line.transient)
    }

    fun info(text: String) = add(text, ConsoleKind.INFO)
    fun error(text: String) = add(text, ConsoleKind.ERROR)
    fun success(text: String) = add(text, ConsoleKind.SUCCESS)

    /** Transient lines (progress bars) replace the previous transient line. */
    fun add(text: String, kind: ConsoleKind, transient: Boolean = false) {
        synchronized(lock) {
            if (buffer.lastOrNull()?.transient == true) buffer.removeLast()
            buffer.addLast(ConsoleEntry(nextId++, text, kind, transient))
            while (buffer.size > maxEntries) buffer.removeFirst()
            dirty = true
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            dirty = true
        }
    }

    fun plainText(): String = synchronized(lock) { buffer.joinToString("\n") { it.text } }
}
