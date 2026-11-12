package com.l0gic0l.anduino.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.cli.BoardDetails
import com.l0gic0l.anduino.cli.BoardListItem
import com.l0gic0l.anduino.core.BuildActions
import com.l0gic0l.anduino.usb.UsbPortInfo

/** "Select Board" — searchable list of every board of every installed core. */
@Composable
fun BoardPickerDialog(vm: IdeViewModel, onDismiss: () -> Unit, onOpenBoardsManager: () -> Unit) {
    val boards by vm.boards.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadBoards() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select board") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search boards") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = boards
                when {
                    list == null -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(
                        "No boards installed yet. Install a core (for example esp32 by Espressif) in the Boards Manager.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    else -> {
                        val filtered = list.filter { b ->
                            query.isBlank() || b.name.contains(query, true) || b.fqbn.contains(query, true)
                        }
                        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                            items(filtered, key = { it.fqbn }) { b: BoardListItem ->
                                ListItem(
                                    headlineContent = { Text(b.name) },
                                    supportingContent = { Text(b.fqbn, style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.clickable { vm.selectBoard(b); onDismiss() },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onOpenBoardsManager() }) { Text("Boards Manager") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The IDE's Tools menu entries for the board: CPU speed, partition scheme, PSRAM, USB mode... */
@Composable
fun BoardOptionsDialog(details: BoardDetails?, onSelect: (String, String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details?.name ?: "Board options") },
        text = {
            when {
                details == null -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
                details.configOptions.isEmpty() -> Text("This board has no configurable options.")
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(details.configOptions, key = { it.option }) { opt ->
                        var open by remember { mutableStateOf(false) }
                        val current = opt.values.firstOrNull { it.selected } ?: opt.values.firstOrNull()
                        Box {
                            ListItem(
                                headlineContent = { Text(opt.optionLabel.ifBlank { opt.option }) },
                                supportingContent = { Text(current?.valueLabel ?: "") },
                                trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                                modifier = Modifier.clickable { open = true },
                            )
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                opt.values.forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v.valueLabel.ifBlank { v.value }) },
                                        onClick = { open = false; onSelect(opt.option, v.value) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset to defaults") } },
    )
}

@Composable
fun PortPickerDialog(
    ports: List<UsbPortInfo>,
    selected: String?,
    otaPassword: String,
    onSelect: (String) -> Unit,
    onOtaPassword: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var otaHost by remember { mutableStateOf(selected?.removePrefix(BuildActions.OTA_PREFIX)?.takeIf { selected.startsWith(BuildActions.OTA_PREFIX) } ?: "") }
    var password by remember { mutableStateOf(otaPassword) }
    LaunchedEffect(Unit) { onRefresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select port") },
        text = {
            Column {
                if (ports.isEmpty()) {
                    Text(
                        "No USB serial device found. Connect the board with a USB-OTG cable or adapter.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ports.forEach { p ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Usb, null) },
                        headlineContent = { Text(p.label) },
                        supportingContent = { Text(p.detail + if (!p.hasPermission) " · permission needed" else "") },
                        trailingContent = { if (p.deviceName == selected) Text("✓") },
                        modifier = Modifier.clickable { onSelect(p.deviceName); onDismiss() },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Network (OTA, ESP32 with ArduinoOTA)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = otaHost,
                    onValueChange = { otaHost = it.trim() },
                    leadingIcon = { Icon(Icons.Default.Wifi, null) },
                    placeholder = { Text("192.168.1.42") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("OTA password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = otaHost.isNotBlank(),
                onClick = {
                    onOtaPassword(password)
                    onSelect(BuildActions.OTA_PREFIX + otaHost)
                    onDismiss()
                },
            ) { Text("Use network port") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
