package com.l0gic0l.anduino.ui.code

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.core.BuildActions
import com.l0gic0l.anduino.core.Prefs
import com.l0gic0l.anduino.ui.common.JobBar
import com.l0gic0l.anduino.ui.common.SplitHandle
import com.l0gic0l.anduino.ui.serial.MonitorPanel
import com.l0gic0l.anduino.ui.serial.PlotterPanel
import com.l0gic0l.anduino.ui.serial.SerialToolbar
import com.l0gic0l.anduino.ui.serial.SerialViewModel
import com.l0gic0l.anduino.ui.theme.isDarkTheme
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    vm: IdeViewModel,
    serialVm: SerialViewModel,
    prefs: Prefs,
    onOpenSketchbook: () -> Unit,
    onOpenBoardsManager: () -> Unit,
) {
    val sketchDir by vm.sketchDir.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val currentIndex by vm.currentIndex.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val boardName by vm.boardName.collectAsStateWithLifecycle()
    val fqbn by vm.fqbn.collectAsStateWithLifecycle()
    val port by vm.port.collectAsStateWithLifecycle()
    val ports by vm.ports.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val boardDetails by vm.boardDetails.collectAsStateWithLifecycle()
    val repairNeeded by vm.repairNeeded.collectAsStateWithLifecycle()
    val fontSize by prefs.editorFontSize.flow.collectAsStateWithLifecycle()
    val tabSize by prefs.tabSize.flow.collectAsStateWithLifecycle()
    val wordWrap by prefs.wordWrap.flow.collectAsStateWithLifecycle()
    val themeMode by prefs.themeMode.flow.collectAsStateWithLifecycle()
    val otaPassword by prefs.otaPassword.flow.collectAsStateWithLifecycle()
    val dark = isDarkTheme(themeMode)

    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var dialog by remember { mutableStateOf<CodeDialog?>(null) }
    var showFind by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableIntStateOf(0) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val addFileFromDevice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFileFromDevice(context, uri)
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(context, uri)
    }

    // Autosave like IDE 2: when the app goes to the background or this screen is left.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveAllAsync() }
    DisposableEffect(Unit) { onDispose { vm.saveAllAsync() } }

    // Open the output panel whenever a build starts, like the IDE does.
    LaunchedEffect(job?.title) {
        if (job != null) {
            panelOpen = true
            panelTab = 0
        }
    }

    val currentFile = files.getOrNull(currentIndex)
    val title = (sketchDir?.name ?: "") + if (dirty.isNotEmpty()) " •" else ""

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            actions = {
                IconButton(onClick = vm::verify, enabled = job == null) { Icon(Icons.Default.Check, "Verify") }
                IconButton(onClick = vm::upload, enabled = job == null) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Upload") }
                IconButton(onClick = { editor?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { editor?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                IconButton(onClick = { showFind = !showFind }) { Icon(Icons.Default.Search, "Find") }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Save") }, onClick = { menu = false; vm.saveAllAsync() })
                        DropdownMenuItem(text = { Text("New sketch") }, onClick = { menu = false; dialog = CodeDialog.NewSketch })
                        DropdownMenuItem(text = { Text("Sketchbook & examples") }, onClick = { menu = false; onOpenSketchbook() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Open sketch folder…") }, onClick = { menu = false; openFolder.launch(null) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("New tab (file)") }, onClick = { menu = false; dialog = CodeDialog.NewFile })
                        DropdownMenuItem(
                            text = { Text("Add file from device…") },
                            onClick = { menu = false; addFileFromDevice.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete this tab") },
                            enabled = currentIndex > 0,
                            onClick = { menu = false; vm.deleteCurrentFile() },
                        )
                        DropdownMenuItem(text = { Text("Rename sketch") }, onClick = { menu = false; dialog = CodeDialog.Rename })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Board options") }, onClick = { menu = false; dialog = CodeDialog.Options })
                        DropdownMenuItem(
                            text = { Text("Export compiled binary") },
                            onClick = {
                                menu = false
                                scope.launch {
                                    val file = vm.exportBinary(context)
                                    if (file == null) {
                                        vm.message("Verify the sketch first")
                                    } else {
                                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                                        val send = Intent(Intent.ACTION_SEND)
                                            .setType("application/octet-stream")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(Intent.createChooser(send, file.name))
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
        JobBar(job, vm::cancelJob)
        // Board / options / port — the IDE 2 toolbar selectors.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            AssistChip(
                onClick = { dialog = CodeDialog.Board },
                label = { Text(boardName ?: "Select board", maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.DeveloperBoard, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
            if (fqbn != null) {
                AssistChip(
                    onClick = { vm.refreshBoardDetails(); dialog = CodeDialog.Options },
                    label = { Text("Options") },
                    leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                )
            }
            val isOta = port?.startsWith(BuildActions.OTA_PREFIX) == true
            val portLabel = when {
                isOta -> port!!.removePrefix(BuildActions.OTA_PREFIX)
                else -> ports.firstOrNull { it.deviceName == port }?.label
                    ?: ports.singleOrNull()?.label
                    ?: if (ports.isEmpty()) "No port" else "Select port"
            }
            AssistChip(
                onClick = { dialog = CodeDialog.Port },
                label = { Text(portLabel, maxLines = 1) },
                leadingIcon = { Icon(if (isOta) Icons.Default.Wifi else Icons.Default.Usb, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
        }
        // File tabs
        if (files.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = currentIndex.coerceIn(0, files.size - 1),
                    edgePadding = 8.dp,
                    divider = {},
                    modifier = Modifier.weight(1f),
                ) {
                    files.forEachIndexed { i, f ->
                        Tab(
                            selected = i == currentIndex,
                            onClick = { vm.selectFile(i) },
                            text = { Text(f.name + if (f in dirty) " •" else "", maxLines = 1) },
                        )
                    }
                }
                IconButton(onClick = { dialog = CodeDialog.NewFile }) { Icon(Icons.Default.Add, "New tab") }
            }
        }
        HorizontalDivider()
        if (showFind) FindBar(editor, onClose = { showFind = false })

        Workspace(
            panelOpen = panelOpen,
            onTogglePanel = { panelOpen = !panelOpen },
            panelTab = panelTab,
            onPanelTab = { panelTab = it; panelOpen = true },
            editorPane = { modifier ->
                Column(modifier) {
                    CodeEditorView(
                        content = currentFile?.let(vm::contentFor),
                        fontSize = fontSize,
                        tabSize = tabSize,
                        wordWrap = wordWrap,
                        dark = dark,
                        onEdited = { currentFile?.let(vm::markDirty) },
                        onEditorReady = { editor = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    SymbolBar(editor)
                }
            },
            panelContent = { tab, modifier ->
                when (tab) {
                    0 -> ConsolePane(vm.console.collectAsStateWithLifecycle().value, modifier)
                    1 -> Column(modifier) { SerialToolbar(serialVm); MonitorPanel(serialVm, Modifier.weight(1f)) }
                    else -> Column(modifier) { SerialToolbar(serialVm); PlotterPanel(serialVm, Modifier.weight(1f)) }
                }
            },
            onClearOutput = vm::clearConsole,
        )
    }

    if (repairNeeded) {
        AlertDialog(
            onDismissRequest = vm::dismissRepair,
            title = { Text("Repair installed toolchains?") },
            text = {
                Text(
                    "Some compiler files of your installed cores were unpacked incorrectly by an earlier " +
                        "version of Anduino, so compiling fails (\"…-g++ is not exist\"). Repair reinstalls the " +
                        "cores at the same version from the download cache; it takes a few minutes and " +
                        "downloads nothing.",
                )
            },
            confirmButton = { TextButton(onClick = vm::repairToolchains) { Text("Repair") } },
            dismissButton = { TextButton(onClick = vm::dismissRepair) { Text("Later") } },
        )
    }

    when (dialog) {
        CodeDialog.Board -> BoardPickerDialog(vm, onDismiss = { dialog = null }, onOpenBoardsManager = onOpenBoardsManager)
        CodeDialog.Options -> BoardOptionsDialog(
            details = boardDetails,
            onSelect = vm::setBoardOption,
            onReset = vm::resetBoardOptions,
            onDismiss = { dialog = null },
        )
        CodeDialog.Port -> PortPickerDialog(
            ports = ports,
            selected = port,
            otaPassword = otaPassword,
            onSelect = vm::selectPort,
            onOtaPassword = prefs.otaPassword::set,
            onRefresh = vm::refreshPorts,
            onDismiss = { dialog = null },
        )
        CodeDialog.NewFile -> TextInputDialog(
            title = "New tab",
            label = "File name (e.g. helpers.h)",
            confirm = "Create",
            onConfirm = vm::addFile,
            onDismiss = { dialog = null },
        )
        CodeDialog.NewSketch -> TextInputDialog(
            title = "New sketch",
            label = "Sketch name",
            confirm = "Create",
            onConfirm = { vm.newSketch(it) },
            onDismiss = { dialog = null },
        )
        CodeDialog.Rename -> TextInputDialog(
            title = "Rename sketch",
            label = "New name",
            initial = sketchDir?.name ?: "",
            confirm = "Rename",
            onConfirm = vm::renameSketch,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class CodeDialog { Board, Options, Port, NewFile, NewSketch, Rename }

private val PANEL_TABS = listOf("Output", "Monitor", "Plotter")

/**
 * Lays out the editor and the Output/Monitor/Plotter panel for the current window:
 * - Foldable in tabletop posture: editor above the hinge, panel below it.
 * - Foldable opened like a book with a separating hinge: one pane on each side.
 * - Wide windows (landscape, tablets, unfolded): side by side with a draggable divider.
 * - Phones in portrait: editor with a collapsible, resizable bottom panel.
 */
@Composable
private fun Workspace(
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    panelTab: Int,
    onPanelTab: (Int) -> Unit,
    editorPane: @Composable (Modifier) -> Unit,
    panelContent: @Composable (Int, Modifier) -> Unit,
    onClearOutput: () -> Unit,
) {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var sideFraction by rememberSaveable { mutableFloatStateOf(0.6f) }
    var bottomFraction by rememberSaveable { mutableFloatStateOf(0.6f) }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val totalW = maxWidth
        val totalH = maxHeight
        val hinge = posture.hingeList.firstOrNull { it.isSeparating || !it.isFlat }
        val header: @Composable (Boolean) -> Unit = { showToggle ->
            PanelHeader(panelTab, onPanelTab, panelOpen, showToggle, onTogglePanel, onClearOutput)
        }
        when {
            posture.isTabletop && hinge != null && !hinge.isVertical -> {
                val top = with(density) { (hinge.bounds.top - origin.y).toDp() }.coerceIn(120.dp, totalH - 120.dp)
                val gap = with(density) { hinge.bounds.height.toDp() }
                Column(Modifier.fillMaxSize()) {
                    editorPane(Modifier.fillMaxWidth().height(top))
                    Spacer(Modifier.height(gap))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                        Column { header(false); panelContent(panelTab, Modifier.weight(1f)) }
                    }
                }
            }
            hinge != null && hinge.isVertical && hinge.isSeparating -> {
                val left = with(density) { (hinge.bounds.left - origin.x).toDp() }.coerceIn(200.dp, totalW - 200.dp)
                val gap = with(density) { hinge.bounds.width.toDp() }
                Row(Modifier.fillMaxSize()) {
                    editorPane(Modifier.fillMaxHeight().width(left))
                    Spacer(Modifier.width(gap))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                        Column { header(false); panelContent(panelTab, Modifier.weight(1f)) }
                    }
                }
            }
            totalW >= 600.dp && (totalW > totalH || totalW >= 840.dp) -> {
                val editorW: Dp = totalW * sideFraction
                Row(Modifier.fillMaxSize()) {
                    editorPane(Modifier.fillMaxHeight().width(editorW))
                    SplitHandle(vertical = true, onDrag = { dx ->
                        val px = with(density) { totalW.toPx() }
                        sideFraction = (sideFraction + dx / px).coerceIn(0.3f, 0.8f)
                    })
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                        Column { header(false); panelContent(panelTab, Modifier.weight(1f)) }
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    val editorModifier = if (panelOpen) Modifier.fillMaxWidth().height(totalH * bottomFraction) else Modifier.fillMaxWidth().weight(1f)
                    editorPane(editorModifier)
                    if (panelOpen) {
                        SplitHandle(vertical = false, onDrag = { dy ->
                            val px = with(density) { totalH.toPx() }
                            bottomFraction = (bottomFraction + dy / px).coerceIn(0.2f, 0.8f)
                        })
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = if (panelOpen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            header(true)
                            if (panelOpen) panelContent(panelTab, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    tab: Int,
    onTab: (Int) -> Unit,
    open: Boolean,
    showToggle: Boolean,
    onToggle: () -> Unit,
    onClearOutput: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f), divider = {}) {
            PANEL_TABS.forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(name, maxLines = 1) })
            }
        }
        if (tab == 0) TextButton(onClick = onClearOutput) { Text("Clear") }
        if (showToggle) {
            IconButton(onClick = onToggle) {
                Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (open) "Hide panel" else "Show panel")
            }
        }
    }
}

/** Symbols that are awkward to reach on a phone keyboard; shown while the keyboard is up. */
@Composable
private fun SymbolBar(editor: CodeEditor?) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (!imeVisible || editor == null) return
    val symbols = listOf("⇥", "{", "}", "(", ")", ";", "[", "]", "<", ">", "=", "\"", "'", "/", "\\", "&", "|", "!", "+", "-", "*", "#", ",", ".", ":", "_", "%")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            symbols.forEach { s ->
                TextButton(
                    onClick = {
                        if (s == "⇥") editor.indentOrCommitTab() else editor.insertText(s, 1)
                    },
                    modifier = Modifier.width(44.dp),
                ) { Text(s, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun FindBar(editor: CodeEditor?, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query, editor) {
        val searcher = editor?.searcher ?: return@LaunchedEffect
        if (query.isEmpty()) searcher.stopSearch()
        else searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Find") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { runCatching { editor?.searcher?.gotoPrevious() } }) { Icon(Icons.Default.ExpandLess, "Previous") }
                IconButton(onClick = { runCatching { editor?.searcher?.gotoNext() } }) { Icon(Icons.Default.ExpandMore, "Next") }
                TextButton(onClick = { showReplace = !showReplace }) { Text("Replace") }
                IconButton(onClick = { editor?.searcher?.stopSearch(); onClose() }) { Icon(Icons.Default.Close, "Close find") }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        singleLine = true,
                        placeholder = { Text("Replace with") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { runCatching { editor?.searcher?.replaceCurrentMatch(replacement) } }) { Text("One") }
                    TextButton(onClick = { runCatching { editor?.searcher?.replaceAll(replacement) } }) { Text("All") }
                }
            }
        }
    }
}
