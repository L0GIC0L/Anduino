package com.l0gic0l.anduino.ui.manager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.cli.PlatformSummary
import com.l0gic0l.anduino.ui.common.EmptyState
import com.l0gic0l.anduino.ui.common.JobBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerScreen(vm: ManagerViewModel, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    LaunchedEffect(initialTab) { tab = initialTab }
    val job by vm.job.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installZip(context, uri)
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (tab == 0) "Boards Manager" else "Library Manager") },
            actions = {
                if (tab == 1) {
                    IconButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) {
                        Icon(Icons.Default.FolderZip, "Add .ZIP library")
                    }
                }
                IconButton(onClick = vm::updateIndexes, enabled = job == null) { Icon(Icons.Default.Refresh, "Update indexes") }
            },
        )
        JobBar(job, vm::cancelJob)
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Boards") }, icon = { Icon(Icons.Default.DeveloperBoard, null) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Libraries") }, icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null) })
        }
        if (tab == 0) BoardsTab(vm, busy = job != null) else LibrariesTab(vm, busy = job != null)
    }
}

@Composable
private fun BoardsTab(vm: ManagerViewModel, busy: Boolean) {
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadPlatforms() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Filter cores (esp32, avr, rp2040…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        val list = platforms
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(
                Icons.Default.DeveloperBoard,
                "No board packages",
                "Tap refresh to download the package indexes. Extra indexes (ESP32, RP2040, STM32…) are set in Settings.",
            )
            else -> {
                val filtered = list.filter { p ->
                    query.isBlank() || p.name.contains(query, true) || p.id.contains(query, true) ||
                        p.releases.values.lastOrNull()?.boards?.any { it.name.contains(query, true) } == true
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { p -> PlatformCard(p, busy, vm) }
                }
            }
        }
    }
}

@Composable
private fun PlatformCard(p: PlatformSummary, busy: Boolean, vm: ManagerViewModel) {
    var versionMenu by remember { mutableStateOf(false) }
    val installed = p.installed
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (installed != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text("${p.id} · by ${p.maintainer}", style = MaterialTheme.typography.bodySmall)
            val boards = p.releases[p.latest ?: ""]?.boards.orEmpty()
            if (boards.isNotEmpty()) {
                Text(
                    "Boards: " + boards.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    if (installed != null) "Installed $installed" else "Latest ${p.latest ?: "?"}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (installed != null) {
                    OutlinedButton(onClick = { vm.uninstallCore(p) }, enabled = !busy) { Text("Remove") }
                    if (p.updatable) Button(onClick = { vm.installCore(p) }, enabled = !busy) { Text("Update") }
                } else {
                    Box {
                        TextButton(onClick = { versionMenu = true }, enabled = !busy) { Text("Version") }
                        DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                            p.releases.keys.reversed().take(15).forEach { v ->
                                DropdownMenuItem(text = { Text(v) }, onClick = { versionMenu = false; vm.installCore(p, v) })
                            }
                        }
                    }
                    Button(onClick = { vm.installCore(p) }, enabled = !busy) { Text("Install") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrariesTab(vm: ManagerViewModel, busy: Boolean) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val installed by vm.installedLibs.collectAsStateWithLifecycle()
    val results by vm.libResults.collectAsStateWithLifecycle()
    val searching by vm.libSearching.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInstalledLibs() }
    val installedVersions = installed.orEmpty().associate { (it.library?.name ?: "") to (it.library?.version ?: "") }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            listOf("Installed", "Search all").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = mode == i,
                    onClick = { mode = i },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        if (mode == 1) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.searchLibraries(it) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.padding(12.dp)) },
                placeholder = { Text("Search the Arduino library index") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (query.isBlank()) {
                EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, "Every Arduino library", "Search by name, author or topic, e.g. \"neopixel\" or \"bme280\".")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.name }) { lib ->
                        val inst = installedVersions[lib.name]
                        LibraryCard(
                            name = lib.name,
                            author = lib.latest?.author ?: "",
                            sentence = lib.latest?.sentence ?: "",
                            version = lib.latest?.version ?: "",
                            installedVersion = inst,
                            versions = lib.availableVersions,
                            busy = busy,
                            onInstall = { v -> vm.installLibrary(lib.name, v) },
                            onRemove = { vm.uninstallLibrary(lib.name) },
                        )
                    }
                }
            }
        } else {
            val list = installed
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, "No libraries installed", "Switch to \"Search all\" to find one.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { (it.library?.name ?: "") + (it.library?.installDir ?: "") }) { item ->
                        val lib = item.library ?: return@items
                        val bundled = lib.location.contains("platform", true) || lib.location.contains("builtin", true)
                        LibraryCard(
                            name = lib.name,
                            author = lib.author,
                            sentence = lib.sentence,
                            version = item.release?.version ?: lib.version,
                            installedVersion = lib.version,
                            versions = emptyList(),
                            busy = busy || bundled,
                            onInstall = { v -> vm.installLibrary(lib.name, v) },
                            onRemove = { vm.uninstallLibrary(lib.name) },
                            note = if (bundled) "Bundled with ${lib.containerPlatform.ifBlank { "the core" }}" else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(
    name: String,
    author: String,
    sentence: String,
    version: String,
    installedVersion: String?,
    versions: List<String>,
    busy: Boolean,
    onInstall: (String?) -> Unit,
    onRemove: () -> Unit,
    note: String? = null,
) {
    var versionMenu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (author.isNotBlank()) Text("by $author", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sentence.isNotBlank()) Text(sentence, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    if (installedVersion != null) "Installed $installedVersion" else version,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (installedVersion != null) {
                    OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                    if (versions.isNotEmpty() && version.isNotBlank() && version != installedVersion) {
                        FilledTonalButton(onClick = { onInstall(version) }, enabled = !busy) { Text("Update") }
                    }
                } else {
                    if (versions.size > 1) {
                        Box {
                            TextButton(onClick = { versionMenu = true }, enabled = !busy) { Text("Version") }
                            DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                                versions.reversed().take(15).forEach { v ->
                                    DropdownMenuItem(text = { Text(v) }, onClick = { versionMenu = false; onInstall(v) })
                                }
                            }
                        }
                    }
                    Button(onClick = { onInstall(null) }, enabled = !busy) { Text("Install") }
                }
            }
        }
    }
}
