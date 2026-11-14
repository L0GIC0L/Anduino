package com.l0gic0l.anduino.serial

import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.l0gic0l.anduino.usb.UsbPorts
import com.l0gic0l.anduino.usb.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MonitorLine(val id: Long, val timeMillis: Long, val text: String, val outgoing: Boolean = false)

sealed interface MonitorState {
    data object Disconnected : MonitorState
    data class Connecting(val deviceName: String) : MonitorState
    data class Connected(val deviceName: String, val baud: Int) : MonitorState
    data class Failed(val message: String) : MonitorState
}

enum class LineEnding(val label: String, val bytes: String) {
    NONE("No line ending", ""),
    LF("New line", "\n"),
    CR("Carriage return", "\r"),
    CRLF("Both NL & CR", "\r\n"),
}

/**
 * The one serial connection shared by the Serial Monitor and Serial Plotter (as in
 * Arduino IDE 2). Incoming data is batched and published at most every 50 ms so a
 * chatty board can't flood the UI.
 */
class SerialMonitor(private val usbPorts: UsbPorts, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<MonitorState>(MonitorState.Disconnected)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _lines = MutableStateFlow<List<MonitorLine>>(emptyList())
    val lines: StateFlow<List<MonitorLine>> = _lines.asStateFlow()

    private val _partial = MutableStateFlow("")
    /** Text received after the last newline. */
    val partial: StateFlow<String> = _partial.asStateFlow()

    val plotter = PlotterData(500)
    private val _plotVersion = MutableStateFlow(0L)
    /** Increments whenever [plotter] changes; read [plotter] under `synchronized(plotter)`. */
    val plotVersion: StateFlow<Long> = _plotVersion.asStateFlow()

    private val connectMutex = Mutex()
    private var transport: UsbSerialTransport? = null
    private var ioManager: SerialInputOutputManager? = null
    private val assembler = LineAssembler()

    private val lock = Any()
    private val buffer = ArrayDeque<MonitorLine>()
    private var nextId = 0L
    private var dirty = false
    var maxLines = 5000

    /** Remembered so the connection can be restored after an upload. */
    private var lastTarget: Pair<String, Int>? = null

    init {
        scope.launch {
            while (isActive) {
                delay(50)
                publish()
            }
        }
    }

    suspend fun connect(deviceName: String, baud: Int) = connectMutex.withLock {
        closeLocked()
        _state.value = MonitorState.Connecting(deviceName)
        try {
            val t = usbPorts.open(deviceName, baud)
            // Most dev boards reset when DTR/RTS toggle; leave both released so the
            // sketch keeps running, as the IDE does.
            runCatching { t.setDtr(false); t.setRts(false) }
            transport = t
            assembler.reset()
            val mgr = SerialInputOutputManager(t.port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = onData(data)
                override fun onRunError(e: Exception) {
                    scope.launch { connectMutex.withLock { closeLocked() }; _state.value = MonitorState.Failed("Connection lost: ${e.message}") }
                }
            })
            mgr.start()
            ioManager = mgr
            lastTarget = deviceName to baud
            _state.value = MonitorState.Connected(deviceName, baud)
        } catch (e: Exception) {
            closeLocked()
            _state.value = MonitorState.Failed(e.message ?: "Could not open port")
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        closeLocked()
        lastTarget = null
        _state.value = MonitorState.Disconnected
    }

    private fun closeLocked() {
        ioManager?.let { runCatching { it.listener = null; it.stop() } }
        ioManager = null
        transport?.close()
        transport = null
    }

    /**
     * Frees the port while [block] runs (e.g. for an upload) and reconnects afterwards
     * if the monitor was open before.
     */
    suspend fun <T> withPortReleased(block: suspend () -> T): T {
        val resume = (state.value as? MonitorState.Connected)?.let { lastTarget }
        if (resume != null) {
            connectMutex.withLock { closeLocked() }
            _state.value = MonitorState.Disconnected
        }
        try {
            return block()
        } finally {
            if (resume != null) {
                delay(800) // let the board boot and re-enumerate
                usbPorts.refresh()
                val name = if (usbPorts.ports.value.any { it.deviceName == resume.first }) resume.first
                else usbPorts.ports.value.singleOrNull()?.deviceName
                if (name != null) connect(name, resume.second)
            }
        }
    }

    suspend fun send(text: String, ending: LineEnding) = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext
        val payload = text + ending.bytes
        runCatching { t.write(payload.toByteArray(Charsets.UTF_8)) }
            .onSuccess { append(listOf(text), outgoing = true) }
            .onFailure { _state.value = MonitorState.Failed("Send failed: ${it.message}") }
    }

    fun setDtr(value: Boolean) = runCatching { transport?.setDtr(value) }

    fun setRts(value: Boolean) = runCatching { transport?.setRts(value) }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            dirty = true
        }
        synchronized(plotter) { plotter.clear() }
        _plotVersion.value++
    }

    private fun onData(data: ByteArray) {
        val complete = synchronized(assembler) { assembler.feed(data) }
        if (complete.isNotEmpty()) {
            append(complete, outgoing = false)
            synchronized(plotter) {
                for (line in complete) plotter.add(PlotterParser.parse(line))
            }
            _plotVersion.value++
        }
        _partial.value = synchronized(assembler) { assembler.pending }
    }

    private fun append(texts: List<String>, outgoing: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (t in texts) buffer.addLast(MonitorLine(nextId++, now, t, outgoing))
            while (buffer.size > maxLines) buffer.removeFirst()
            dirty = true
        }
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            buffer.toList()
        }
        _lines.value = snapshot
    }
}
