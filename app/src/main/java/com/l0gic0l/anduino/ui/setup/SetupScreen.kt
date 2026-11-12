package com.l0gic0l.anduino.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.ui.code.ConsolePane

/** First run: installs the Linux environment, arduino-cli and (optionally) the ESP32 core. */
@Composable
fun SetupScreen(c: AppContainer) {
    val job by c.jobs.current.collectAsStateWithLifecycle()
    val log by c.console.entries.collectAsStateWithLifecycle()
    var withEsp32 by rememberSaveable { mutableStateOf(true) }
    var withAvr by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.safeDrawingPadding().fillMaxSize().padding(16.dp),
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Memory, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Welcome to Anduino", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Anduino compiles with arduino-cli, the same engine Arduino IDE 2 uses, running in a small " +
                        "Ubuntu environment inside the app (no root needed). Every Arduino board package and " +
                        "library works the way it does on a desktop.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Setup downloads", style = MaterialTheme.typography.titleSmall)
                        Text("• Ubuntu 24.04 base + python3: about 60 MB")
                        Text("• arduino-cli: about 20 MB")
                        Text("• ESP32 core with all toolchains: about 1.5 GB once installed")
                        Text("Wi-Fi recommended. Keep the app open or let it run in the background; a notification shows progress.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withEsp32, onCheckedChange = { withEsp32 = it }, enabled = job == null)
                    Text("Also install the ESP32 core (esp32 by Espressif)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withAvr, onCheckedChange = { withAvr = it }, enabled = job == null)
                    Text("Also install Arduino AVR boards (Uno, Nano, Mega)")
                }
                job?.let { j ->
                    Text(j.step ?: j.title, style = MaterialTheme.typography.titleSmall)
                    if (j.progress != null) {
                        LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (log.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth().height(260.dp)) { ConsolePane(log) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (job != null) {
                    OutlinedButton(onClick = c.jobs::cancel) { Text("Cancel") }
                } else {
                    Button(onClick = { startSetup(c, withEsp32, withAvr) }) {
                        Text(if (log.isEmpty()) "Install" else "Retry")
                    }
                }
            }
        }
    }
}

private fun startSetup(c: AppContainer, withEsp32: Boolean, withAvr: Boolean) {
    c.jobs.launch("Setting up") {
        c.env.disableSeccomp = c.prefs.disableSeccomp.value
        c.bootstrapper.install(c.dnsServers(), c.prefs.boardUrlList()) { step, fraction, line ->
            step(step, fraction)
            line?.let(c.console::append)
        }
        val log: (com.l0gic0l.anduino.env.OutputLine) -> Unit = { line ->
            c.console.append(line)
            if (line.transient) step(line.text)
        }
        if (withEsp32) {
            step("Installing ESP32 core (this takes a while)")
            if (c.cli.coreInstall("esp32:esp32", null, log) != 0) {
                c.console.error("ESP32 core install failed; retry from the Boards Manager.")
            } else if (c.prefs.fqbn.value == null) {
                c.prefs.fqbn.set("esp32:esp32:esp32")
                c.prefs.boardName.set("ESP32 Dev Module")
            }
        }
        if (withAvr) {
            step("Installing Arduino AVR core")
            if (c.cli.coreInstall("arduino:avr", null, log) != 0) {
                c.console.error("AVR core install failed; retry from the Boards Manager.")
            } else if (c.prefs.fqbn.value == null) {
                c.prefs.fqbn.set("arduino:avr:uno")
                c.prefs.boardName.set("Arduino Uno")
            }
        }
        c.console.success("Setup complete.")
        c.envReady.value = true
    }
}
