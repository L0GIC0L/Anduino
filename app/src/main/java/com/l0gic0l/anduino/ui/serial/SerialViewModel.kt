package com.l0gic0l.anduino.ui.serial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.core.BuildActions
import com.l0gic0l.anduino.serial.LineEnding
import com.l0gic0l.anduino.serial.MonitorState
import com.l0gic0l.anduino.serial.PlotSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SerialViewModel(private val c: AppContainer) : ViewModel() {
    private val prefs = c.prefs
    val state = c.monitor.state
    val lines = c.monitor.lines
    val partial = c.monitor.partial
    val plotVersion = c.monitor.plotVersion
    val ports = c.usbPorts.ports
    val selectedPort = prefs.port.flow
    val baud = prefs.baud.flow
    val lineEnding = prefs.lineEnding.flow
    val timestamps = prefs.showTimestamps.flow
    val autoScroll = prefs.autoScroll.flow
    val plotWindow = prefs.plotterWindow.flow

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        synchronized(c.monitor.plotter) { c.monitor.plotter.capacity = prefs.plotterWindow.value }
    }

    val isConnected: Boolean get() = state.value is MonitorState.Connected

    fun plotSnapshot(): PlotSnapshot = synchronized(c.monitor.plotter) { c.monitor.plotter.snapshot() }

    fun selectPort(deviceName: String) {
        prefs.port.set(deviceName)
        if (isConnected) connect()
    }

    fun connect() {
        val chosen = prefs.port.value?.takeUnless { it.startsWith(BuildActions.OTA_PREFIX) }
        val ports = c.usbPorts.ports.value
        val port = chosen?.takeIf { p -> ports.any { it.deviceName == p } } ?: ports.singleOrNull()?.deviceName
        if (port == null) {
            _messages.tryEmit(if (ports.isEmpty()) "No USB serial device connected" else "Select a port first")
            return
        }
        prefs.port.set(port)
        viewModelScope.launch { c.monitor.connect(port, prefs.baud.value) }
    }

    fun disconnect() {
        viewModelScope.launch { c.monitor.disconnect() }
    }

    fun toggleConnection() = if (isConnected) disconnect() else connect()

    fun setBaud(baud: Int) {
        prefs.baud.set(baud)
        if (isConnected) connect()
    }

    fun send(text: String) {
        viewModelScope.launch { c.monitor.send(text, prefs.lineEnding.value) }
    }

    fun setLineEnding(e: LineEnding) = prefs.lineEnding.set(e)
    fun setTimestamps(v: Boolean) = prefs.showTimestamps.set(v)
    fun setAutoScroll(v: Boolean) = prefs.autoScroll.set(v)
    fun setDtr(v: Boolean) = c.monitor.setDtr(v)
    fun setRts(v: Boolean) = c.monitor.setRts(v)
    fun clear() = c.monitor.clear()
    fun refreshPorts() = c.usbPorts.refresh()

    fun setPlotWindow(points: Int) {
        prefs.plotterWindow.set(points)
        synchronized(c.monitor.plotter) { c.monitor.plotter.capacity = points }
    }

    companion object {
        val BAUD_RATES = listOf(
            300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880, 115200, 230400, 250000,
            460800, 500000, 921600, 1000000, 2000000,
        )
    }
}
