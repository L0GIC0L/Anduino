package com.l0gic0l.anduino.ui.sketchbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.sketch.SketchInfo
import com.l0gic0l.anduino.ui.code.IdeViewModel
import com.l0gic0l.anduino.ui.code.TextInputDialog
import com.l0gic0l.anduino.ui.common.EmptyState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SketchbookScreen(vm: IdeViewModel, onOpened: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.ino"
            val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            if (text != null) {
                vm.importText(name, text)
                onOpened()
            }
        }
    }
    LaunchedEffect(Unit) { vm.refreshSketches() }
    Scaffold(
        // The navigation suite and the top app bar already handle the system bars.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Sketchbook") },
                actions = {
                    IconButton(onClick = { openDocument.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, "Open .ino file") }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { newDialog = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New sketch") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My sketches") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Examples") })
            }
            if (tab == 0) SketchList(vm, onOpened) else ExamplesList(vm, onOpened)
        }
    }
    if (newDialog) {
        TextInputDialog(
            title = "New sketch",
            label = "Sketch name",
            confirm = "Create",
            onConfirm = { vm.newSketch(it); onOpened() },
            onDismiss = { newDialog = false },
        )
    }
}

@Composable
private fun SketchList(vm: IdeViewModel, onOpened: () -> Unit) {
    val sketches by vm.sketches.collectAsStateWithLifecycle()
    val current by vm.sketchDir.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<SketchInfo?>(null) }
    if (sketches.isEmpty()) {
        EmptyState(Icons.Default.Folder, "No sketches yet", "Create one, or open an example.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(sketches, key = { it.dir.path }) { s ->
            ListItem(
                leadingContent = { Icon(Icons.Default.Code, null) },
                headlineContent = { Text(s.name + if (s.dir == current) "  (open)" else "") },
                supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(s.lastModified))) },
                trailingContent = {
                    IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Delete, "Delete ${s.name}") }
                },
                modifier = Modifier.clickable { vm.openSketch(s.dir); onOpened() },
            )
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("The sketch folder and all its files will be removed.") },
            confirmButton = { TextButton(onClick = { vm.deleteSketch(s); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExamplesList(vm: IdeViewModel, onOpened: () -> Unit) {
    val examples by vm.examples.collectAsStateWithLifecycle()
    val fqbn by vm.fqbn.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(fqbn) { vm.loadExamples() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Filter examples") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        val list = examples
        if (list == null) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            return
        }
        if (list.isEmpty()) {
            EmptyState(
                Icons.Default.Folder,
                "No examples",
                "Examples come with installed libraries and with the core of the selected board.",
            )
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            list.forEach { group ->
                val lib = group.library?.name ?: "Other"
                val matching = group.examples.filter {
                    query.isBlank() || lib.contains(query, true) || it.substringAfterLast('/').contains(query, true)
                }
                if (matching.isEmpty()) return@forEach
                val open = lib in expanded || query.isNotBlank()
                item(key = "lib:$lib") {
                    ListItem(
                        headlineContent = { Text(lib, style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                listOfNotNull(group.library?.containerPlatform?.takeIf { it.isNotBlank() }, "${matching.size} examples")
                                    .joinToString(" · "),
                            )
                        },
                        trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        modifier = Modifier.clickable { expanded = if (lib in expanded) expanded - lib else expanded + lib },
                    )
                }
                if (open) {
                    items(matching, key = { "ex:$it" }) { path ->
                        val base = group.library?.installDir?.let { "$it/examples/" }
                        val label = base?.let { path.removePrefix(it) } ?: path.substringAfterLast('/')
                        ListItem(
                            headlineContent = { Text(label) },
                            modifier = Modifier
                                .padding(start = 24.dp)
                                .clickable { vm.openExample(path); onOpened() },
                        )
                    }
                }
            }
        }
    }
}
