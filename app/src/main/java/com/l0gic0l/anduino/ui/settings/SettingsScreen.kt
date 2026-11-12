package com.l0gic0l.anduino.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.BuildConfig
import com.l0gic0l.anduino.core.ThemeMode
import com.l0gic0l.anduino.env.Bootstrapper
import com.l0gic0l.anduino.ui.code.ConsolePane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer) {
    val p = c.prefs
    val theme by p.themeMode.flow.collectAsStateWithLifecycle()
    val dynamic by p.dynamicColor.flow.collectAsStateWithLifecycle()
    val fontSize by p.editorFontSize.flow.collectAsStateWithLifecycle()
    val tabSize by p.tabSize.flow.collectAsStateWithLifecycle()
    val wordWrap by p.wordWrap.flow.collectAsStateWithLifecycle()
    val warnings by p.warnings.flow.collectAsStateWithLifecycle()
    val verbose by p.verboseCompile.flow.collectAsStateWithLifecycle()
    val urls by p.boardManagerUrls.flow.collectAsStateWithLifecycle()
    val noSeccomp by p.disableSeccomp.flow.collectAsStateWithLifecycle()
    val job by c.jobs.current.collectAsStateWithLifecycle()
    var urlDraft by rememberSaveable(urls) { mutableStateOf(urls) }
    var confirmReinstall by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Appearance")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = theme == m,
                            onClick = { p.themeMode.set(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow("Material You colours", "Use colours from your wallpaper", dynamic) { p.dynamicColor.set(it) }
                }

                Section("Editor")
                Text("Font size: ${fontSize.toInt()} sp")
                Slider(value = fontSize, onValueChange = { p.editorFontSize.set(it) }, valueRange = 10f..24f, steps = 13)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tab size", Modifier.padding(end = 8.dp))
                    SingleChoiceSegmentedButtonRow {
                        listOf(2, 4, 8).forEachIndexed { i, n ->
                            SegmentedButton(selected = tabSize == n, onClick = { p.tabSize.set(n) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text("$n") }
                        }
                    }
                }
                SwitchRow("Word wrap", null, wordWrap) { p.wordWrap.set(it) }

                Section("Compiler")
                Text("Warnings")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val levels = listOf("none", "default", "more", "all")
                    levels.forEachIndexed { i, w ->
                        SegmentedButton(selected = warnings == w, onClick = { p.warnings.set(w) }, shape = SegmentedButtonDefaults.itemShape(i, levels.size)) {
                            Text(w.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                SwitchRow("Verbose compile output", "Show every compiler command", verbose) { p.verboseCompile.set(it) }

                Section("Additional boards manager URLs")
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = { Text("One URL per line") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Bootstrapper.PRESET_URLS.forEach { (name, url) ->
                        if (!urlDraft.contains(url)) {
                            AssistChip(onClick = { urlDraft = (urlDraft.trimEnd() + "\n" + url).trim() }, label = { Text("+ $name") })
                        }
                    }
                }
                FilledTonalButton(
                    enabled = job == null,
                    onClick = {
                        p.boardManagerUrls.set(urlDraft.trim())
                        c.jobs.launch("Updating board index") {
                            c.cli.setBoardManagerUrls(p.boardUrlList())
                            c.cli.updateIndexes { line -> c.console.append(line); if (line.transient) step(line.text) }
                            c.console.success("Board index updated. Install new cores from the Boards Manager.")
                        }
                    },
                ) { Text("Save and update index") }

                Section("Linux environment")
                SwitchRow(
                    "Disable proot seccomp acceleration",
                    "Slower, but works around crashes on some devices",
                    noSeccomp,
                ) {
                    p.disableSeccomp.set(it)
                    c.env.disableSeccomp = it
                }
                ShellCard(c)
                OutlinedButton(
                    onClick = { c.jobs.launch("Repairing toolchains") { c.repair.repair(this) } },
                    enabled = job == null,
                ) { Text("Repair installed cores") }
                OutlinedButton(onClick = { confirmReinstall = true }, enabled = job == null) { Text("Repair / reinstall environment") }

                Section("About")
                ListItem(
                    headlineContent = { Text("Anduino ${BuildConfig.VERSION_NAME}") },
                    supportingContent = {
                        Text(
                            "Builds with arduino-cli (GPL-3.0) inside an Ubuntu rootfs run by proot (GPL-2.0, from Termux). " +
                                "Editor: sora-editor (LGPL-2.1). USB: usb-serial-for-android (MIT).",
                        )
                    },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text("Reinstall the environment?") },
            text = {
                Text(
                    "Setup runs again: Ubuntu packages and arduino-cli are refreshed. Your sketches, " +
                        "installed cores and libraries are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReinstall = false
                    c.env.clearInstalledMarker()
                    c.envReady.value = false
                }) { Text("Reinstall") }
            },
            dismissButton = { TextButton(onClick = { confirmReinstall = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** Runs a shell command inside the Ubuntu environment; output goes to the Output log. */
@Composable
private fun ShellCard(c: AppContainer) {
    var command by rememberSaveable { mutableStateOf("arduino-cli version") }
    val job by c.jobs.current.collectAsStateWithLifecycle()
    val log by c.console.entries.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Linux shell", style = MaterialTheme.typography.titleSmall)
            Text(
                "Advanced: run any command (apt, arduino-cli, python3…) as root inside the environment.",
                style = MaterialTheme.typography.bodySmall,
            )
            val run = {
                open = true
                c.jobs.launch("Shell") {
                    c.console.append(com.l0gic0l.anduino.env.OutputLine("$ $command", false))
                    val code = c.env.stream(listOf("/bin/bash", "-lc", command)) { c.console.append(it) }
                    c.console.info("exit code $code")
                }
                Unit
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { run() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = run, enabled = job == null && command.isNotBlank()) { Text("Run") }
                if (job != null) OutlinedButton(onClick = c.jobs::cancel) { Text("Stop") }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide output" else "Show output") }
            }
            if (open) Card(Modifier.fillMaxWidth().height(240.dp)) { ConsolePane(log) }
        }
    }
}
