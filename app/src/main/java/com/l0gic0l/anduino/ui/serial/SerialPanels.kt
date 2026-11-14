package com.l0gic0l.anduino.ui.serial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.serial.LineEnding
import com.l0gic0l.anduino.serial.MonitorState
import com.l0gic0l.anduino.serial.PlotSnapshot
import com.l0gic0l.anduino.ui.theme.MonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Port, baud and connect controls shared by the monitor and plotter. */
@Composable
fun SerialToolbar(vm: SerialViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ports by vm.ports.collectAsStateWithLifecycle()
    val selected by vm.selectedPort.collectAsStateWithLifecycle()
    val baud by vm.baud.collectAsStateWithLifecycle()
    var portMenu by remember { mutableStateOf(false) }
    var baudMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val connected = state is MonitorState.Connected
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box {
            val label = ports.firstOrNull { it.deviceName == selected }?.label
                ?: if (ports.isEmpty()) "No device" else "Select port"
            AssistChip(
                onClick = { vm.refreshPorts(); portMenu = true },
                label = { Text(label, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Usb, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = portMenu, onDismissRequest = { portMenu = false }) {
                if (ports.isEmpty()) {
                    DropdownMenuItem(text = { Text("Plug in a board with a USB-OTG cable") }, onClick = { portMenu = false }, enabled = false)
                }
                ports.forEach { p ->
                    DropdownMenuItem(
                        text = { Column { Text(p.label); Text(p.detail, style = MaterialTheme.typography.bodySmall) } },
                        onClick = { vm.selectPort(p.deviceName); portMenu = false },
                    )
                }
            }
        }
        Box {
            AssistChip(
                onClick = { baudMenu = true },
                label = { Text("$baud baud") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = baudMenu, onDismissRequest = { baudMenu = false }) {
                SerialViewModel.BAUD_RATES.forEach { b ->
                    DropdownMenuItem(text = { Text("$b") }, onClick = { vm.setBaud(b); baudMenu = false })
                }
            }
        }
        FilledTonalButton(onClick = vm::toggleConnection) {
            Icon(if (connected) Icons.Default.UsbOff else Icons.Default.Usb, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when (state) {
                    is MonitorState.Connected -> "Disconnect"
                    is MonitorState.Connecting -> "Connecting…"
                    else -> "Connect"
                },
            )
        }
        IconButton(onClick = vm::clear) { Icon(Icons.Default.DeleteSweep, "Clear") }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
            val timestamps by vm.timestamps.collectAsStateWithLifecycle()
            val autoScroll by vm.autoScroll.collectAsStateWithLifecycle()
            var dtr by rememberSaveable { mutableStateOf(false) }
            var rts by rememberSaveable { mutableStateOf(false) }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                CheckItem("Show timestamps", timestamps) { vm.setTimestamps(it) }
                CheckItem("Autoscroll", autoScroll) { vm.setAutoScroll(it) }
                CheckItem("DTR asserted", dtr) { dtr = it; vm.setDtr(it) }
                CheckItem("RTS asserted", rts) { rts = it; vm.setRts(it) }
            }
        }
    }
    (state as? MonitorState.Failed)?.let {
        Text(
            it.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

/** The Serial Monitor: received lines plus a send box. */
@Composable
fun MonitorPanel(vm: SerialViewModel, modifier: Modifier = Modifier) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val timestamps by vm.timestamps.collectAsStateWithLifecycle()
    val autoScroll by vm.autoScroll.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    LaunchedEffect(lines.lastOrNull()?.id, partial, autoScroll) {
        val count = lines.size + if (partial.isNotEmpty()) 1 else 0
        if (autoScroll && count > 0) listState.scrollToItem(count - 1)
    }
    Column(modifier) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines, key = { it.id }) { line ->
                    val prefix = if (timestamps) timeFormat.format(Date(line.timeMillis)) + (if (line.outgoing) " <- " else " -> ") else ""
                    Text(
                        prefix + line.text,
                        fontFamily = MonoFamily,
                        fontSize = 13.sp,
                        color = if (line.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (partial.isNotEmpty()) {
                    item(key = "partial") { Text(partial, fontFamily = MonoFamily, fontSize = 13.sp) }
                }
            }
        }
        HorizontalDivider()
        SendBar(
            enabled = state is MonitorState.Connected,
            vm = vm,
        )
    }
}

@Composable
private fun SendBar(enabled: Boolean, vm: SerialViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val ending by vm.lineEnding.collectAsStateWithLifecycle()
    var endingMenu by remember { mutableStateOf(false) }
    val send = {
        vm.send(text)
        text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(if (enabled) "Message" else "Not connected") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { endingMenu = true }) { Text(ending.short()) }
            DropdownMenu(expanded = endingMenu, onDismissRequest = { endingMenu = false }) {
                LineEnding.entries.forEach { e ->
                    DropdownMenuItem(text = { Text(e.label) }, onClick = { vm.setLineEnding(e); endingMenu = false })
                }
            }
        }
        IconButton(onClick = send, enabled = enabled) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
    }
}

private fun LineEnding.short() = when (this) {
    LineEnding.NONE -> "None"
    LineEnding.LF -> "NL"
    LineEnding.CR -> "CR"
    LineEnding.CRLF -> "NL+CR"
}

val PlotColors = listOf(
    Color(0xFF2196F3), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFF8BC34A),
    Color(0xFF3F51B5), Color(0xFFFFC107), Color(0xFF795548), Color(0xFF607D8B),
    Color(0xFF009688), Color(0xFFCDDC39), Color(0xFF673AB7), Color(0xFFFF5722),
)

/** The Serial Plotter, compatible with Arduino IDE 2's input format. */
@Composable
fun PlotterPanel(vm: SerialViewModel, modifier: Modifier = Modifier) {
    val version by vm.plotVersion.collectAsState()
    val window by vm.plotWindow.collectAsStateWithLifecycle()
    var paused by rememberSaveable { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var frozen by remember { mutableStateOf<PlotSnapshot?>(null) }
    val live = remember(version, window) { vm.plotSnapshot() }
    val snap = if (paused) frozen ?: live else live
    var windowMenu by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = {
                frozen = if (!paused) live else null
                paused = !paused
            }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause") }
            Box {
                TextButton(onClick = { windowMenu = true }) { Text("$window points") }
                DropdownMenu(expanded = windowMenu, onDismissRequest = { windowMenu = false }) {
                    listOf(50, 100, 250, 500, 1000, 2000, 5000).forEach { n ->
                        DropdownMenuItem(text = { Text("$n points") }, onClick = { vm.setPlotWindow(n); windowMenu = false })
                    }
                }
            }
            snap.labels.forEachIndexed { i, label ->
                val color = PlotColors[i % PlotColors.size]
                val value = snap.latest(i)
                FilterChip(
                    selected = label !in hidden,
                    onClick = { hidden = if (label in hidden) hidden - label else hidden + label },
                    label = {
                        Text(
                            label + (value?.let { " " + formatValue(it) } ?: ""),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
                )
            }
        }
        val textMeasurer = rememberTextMeasurer()
        val axisColor = MaterialTheme.colorScheme.outlineVariant
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            if (snap.rows.isEmpty()) {
                Text(
                    "Print numbers from your sketch, e.g. Serial.println(value) or\n" +
                        "Serial.print(\"temp:\"); Serial.print(t); Serial.print(\",hum:\"); Serial.println(h);",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            PlotCanvas(snap, hidden, axisColor, labelColor, textMeasurer, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PlotCanvas(
    snap: PlotSnapshot,
    hidden: Set<String>,
    axisColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val visible = { i: Int -> snap.labels.getOrNull(i)?.let { it !in hidden } ?: false }
        val (rawLo, rawHi) = snap.range(visible) ?: (0f to 1f)
        val span = (rawHi - rawLo).takeIf { it > 0f } ?: 2f
        val lo = if (rawHi > rawLo) rawLo - span * 0.05f else rawLo - 1f
        val hi = if (rawHi > rawLo) rawHi + span * 0.05f else rawHi + 1f
        val labelWidth = 56.dp.toPx()
        val plotLeft = labelWidth
        val plotWidth = size.width - plotLeft
        val h = size.height
        val style = TextStyle(fontSize = 10.sp, color = labelColor)
        // Grid + Y labels
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(axisColor, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1f)
            val v = hi - (hi - lo) * i / 4f
            val layout = textMeasurer.measure(formatValue(v), style)
            drawText(layout, topLeft = Offset(0f, (y - layout.size.height / 2f).coerceIn(0f, h - layout.size.height)))
        }
        if (snap.rows.size < 2) return@Canvas
        val n = snap.capacity.coerceAtLeast(2)
        val step = plotWidth / (n - 1)
        val offset = n - snap.rows.size // newest sample at the right edge
        for (s in snap.labels.indices) {
            if (!visible(s)) continue
            val path = Path()
            var penDown = false
            snap.rows.forEachIndexed { i, row ->
                val v = if (s < row.size) row[s] else Float.NaN
                if (v.isNaN()) {
                    penDown = false
                } else {
                    val x = plotLeft + (offset + i) * step
                    val y = h - (v - lo) / (hi - lo) * h
                    if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                    penDown = true
                }
            }
            drawPath(path, PlotColors[s % PlotColors.size], style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun formatValue(v: Float): String = when {
    v == 0f -> "0"
    kotlin.math.abs(v) >= 10000 || kotlin.math.abs(v) < 0.01f -> "%.3g".format(v)
    kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
    else -> "%.2f".format(v)
}
