package com.l0gic0l.anduino.ui.code

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.cli.BoardDetails
import com.l0gic0l.anduino.cli.BoardListItem
import com.l0gic0l.anduino.cli.Fqbn
import com.l0gic0l.anduino.cli.LibraryExamples
import com.l0gic0l.anduino.core.BuildActions
import com.l0gic0l.anduino.core.JobScope
import com.l0gic0l.anduino.sketch.SafImport
import com.l0gic0l.anduino.sketch.SketchInfo
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State of the IDE proper: the open sketch and its files, board and port selection,
 * and the Verify / Upload actions. Shared by the Code and Sketchbook screens.
 */
class IdeViewModel(private val c: AppContainer) : ViewModel() {
    private val prefs = c.prefs

    private val _sketchDir = MutableStateFlow<File?>(null)
    val sketchDir: StateFlow<File?> = _sketchDir.asStateFlow()

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _dirty = MutableStateFlow<Set<File>>(emptySet())
    val dirty: StateFlow<Set<File>> = _dirty.asStateFlow()

    /** One sora [Content] per open file, so each keeps its own undo history. */
    private val contents = HashMap<File, Content>()

    val fqbn = prefs.fqbn.flow
    val boardName = prefs.boardName.flow
    val port = prefs.port.flow
    val ports = c.usbPorts.ports
    val job = c.jobs.current
    val console = c.console.entries

    private val _boards = MutableStateFlow<List<BoardListItem>?>(null)
    val boards: StateFlow<List<BoardListItem>?> = _boards.asStateFlow()

    private val _boardDetails = MutableStateFlow<BoardDetails?>(null)
    val boardDetails: StateFlow<BoardDetails?> = _boardDetails.asStateFlow()

    private val _sketches = MutableStateFlow<List<SketchInfo>>(emptyList())
    val sketches: StateFlow<List<SketchInfo>> = _sketches.asStateFlow()

    private val _examples = MutableStateFlow<List<LibraryExamples>?>(null)
    val examples: StateFlow<List<LibraryExamples>?> = _examples.asStateFlow()

    private val _repairNeeded = MutableStateFlow(false)
    /** True when toolchains installed by Anduino 0.1.0 have broken hard links. */
    val repairNeeded: StateFlow<Boolean> = _repairNeeded.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            c.sketches.sketchbook.mkdirs()
            val last = prefs.lastSketch.value?.let(::File)?.takeIf { File(it, "${it.name}.ino").isFile }
            val dir = last ?: c.sketches.list().firstOrNull()?.dir ?: c.sketches.create(c.sketches.nextDefaultName())
            openSketchNow(dir)
            refreshSketches()
        }
        refreshBoardDetails()
        viewModelScope.launch {
            _repairNeeded.value = runCatching { c.repair.checkAndFix() }.getOrDefault(false)
        }
    }

    fun dismissRepair() {
        _repairNeeded.value = false
    }

    fun repairToolchains() {
        _repairNeeded.value = false
        if (!c.jobs.launch("Repairing toolchains") { c.repair.repair(this) }) message("Another task is still running")
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ------------------------------------------------------------- files

    val currentFile: File? get() = _files.value.getOrNull(_currentIndex.value)

    fun contentFor(file: File): Content = synchronized(contents) {
        contents.getOrPut(file) { Content(if (file.isFile) file.readText() else "") }
    }

    fun selectFile(index: Int) {
        _currentIndex.value = index.coerceIn(0, (_files.value.size - 1).coerceAtLeast(0))
    }

    fun markDirty(file: File) {
        if (file !in _dirty.value) _dirty.value = _dirty.value + file
    }

    suspend fun saveAll() {
        // The editor mutates Content on the main thread, so take the snapshot there.
        val snapshot = withContext(Dispatchers.Main.immediate) {
            _dirty.value.mapNotNull { f -> synchronized(contents) { contents[f] }?.let { f to it.toString() } }
        }
        if (snapshot.isEmpty()) return
        withContext(Dispatchers.IO) { snapshot.forEach { (f, text) -> f.writeText(text) } }
        _dirty.value = _dirty.value - snapshot.map { it.first }.toSet()
    }

    fun saveAllAsync() {
        viewModelScope.launch { saveAll() }
    }

    fun openSketch(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            saveAll()
            openSketchNow(dir)
        }
    }

    private fun openSketchNow(dir: File) {
        synchronized(contents) { contents.clear() }
        _dirty.value = emptySet()
        _files.value = c.sketches.files(dir)
        _currentIndex.value = 0
        _sketchDir.value = dir
        prefs.lastSketch.set(dir.absolutePath)
    }

    fun newSketch(name: String? = null) = io {
        saveAll()
        val dir = c.sketches.create(name?.takeIf { it.isNotBlank() } ?: c.sketches.nextDefaultName())
        openSketchNow(dir)
        refreshSketches()
    }

    fun addFile(name: String) = io {
        val dir = _sketchDir.value ?: return@io
        val f = c.sketches.addFile(dir, name.trim())
        _files.value = c.sketches.files(dir)
        _currentIndex.value = _files.value.indexOf(f).coerceAtLeast(0)
    }

    fun deleteCurrentFile() = io {
        val dir = _sketchDir.value ?: return@io
        val f = currentFile ?: return@io
        c.sketches.deleteFile(dir, f)
        synchronized(contents) { contents.remove(f) }
        _dirty.value = _dirty.value - f
        _files.value = c.sketches.files(dir)
        _currentIndex.value = 0
    }

    fun renameSketch(newName: String) = io {
        val dir = _sketchDir.value ?: return@io
        saveAll()
        val dest = c.sketches.rename(dir, newName.trim())
        openSketchNow(dest)
        refreshSketches()
    }

    fun deleteSketch(info: SketchInfo) = io {
        c.sketches.delete(info.dir)
        if (info.dir == _sketchDir.value) {
            val next = c.sketches.list().firstOrNull()?.dir ?: c.sketches.create(c.sketches.nextDefaultName())
            openSketchNow(next)
        }
        refreshSketches()
    }

    fun refreshSketches() = io {
        _sketches.value = c.sketches.list()
    }

    fun importText(fileName: String, text: String) = io {
        saveAll()
        openSketchNow(c.sketches.importText(fileName, text))
        refreshSketches()
        message("Imported $fileName")
    }

    /** Sketchbook → Open folder: copies a sketch folder from device storage, secrets.h and all. */
    fun importFolder(context: Context, treeUri: Uri) = io {
        saveAll()
        val staging = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        try {
            val copied = SafImport.copyTree(context.applicationContext, treeUri, staging)
            val dir = c.sketches.importSketchFolder(copied)
            openSketchNow(dir)
            refreshSketches()
            message("Opened ${dir.name} (${dir.walkTopDown().count { it.isFile }} files copied into the sketchbook)")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Add file from device: copies one file (e.g. secrets.h) into the open sketch. */
    fun addFileFromDevice(context: Context, uri: Uri) = io {
        val dir = _sketchDir.value ?: return@io
        val name = SafImport.displayName(context, uri) ?: throw java.io.IOException("Can't read the file name")
        val added = context.contentResolver.openInputStream(uri)?.use { c.sketches.importFileInto(dir, name, it) }
            ?: throw java.io.IOException("Can't read $name")
        _files.value = c.sketches.files(dir)
        val index = _files.value.indexOf(added)
        if (index >= 0) {
            _currentIndex.value = index
            message("Added ${added.name}")
        } else {
            message("Added ${added.name} to the sketch folder (not a source file, so no tab)")
        }
    }

    // ------------------------------------------------------------- examples

    fun loadExamples() = io {
        _examples.value = c.cli.libExamples(prefs.fqbn.value)
            .filter { it.examples.isNotEmpty() }
            .sortedBy { it.library?.name?.lowercase() }
    }

    fun openExample(guestPath: String) = io {
        saveAll()
        val dir = c.sketches.importFolder(c.env.hostPath(guestPath))
        openSketchNow(dir)
        refreshSketches()
        message("Example copied to your sketchbook as ${dir.name}")
    }

    // ------------------------------------------------------------- board & port

    fun loadBoards(force: Boolean = false) {
        if (_boards.value != null && !force) return
        io { _boards.value = c.cli.boardListAll().sortedBy { it.name.lowercase() } }
    }

    fun selectBoard(item: BoardListItem) {
        prefs.fqbn.set(item.fqbn)
        prefs.boardName.set(item.name)
        _boardDetails.value = null
        refreshBoardDetails()
    }

    fun refreshBoardDetails() {
        val fqbn = prefs.fqbn.value ?: return
        io { _boardDetails.value = c.cli.boardDetails(fqbn) }
    }

    fun setBoardOption(option: String, value: String) {
        val current = Fqbn.parse(prefs.fqbn.value ?: return)
        prefs.fqbn.set(current.copy(options = current.options + (option to value)).toString())
        refreshBoardDetails()
    }

    fun resetBoardOptions() {
        val current = Fqbn.parse(prefs.fqbn.value ?: return)
        prefs.fqbn.set(current.base)
        refreshBoardDetails()
    }

    fun selectPort(deviceNameOrOta: String) {
        prefs.port.set(deviceNameOrOta)
    }

    fun refreshPorts() = c.usbPorts.refresh()

    private fun resolvePort(): String? {
        val chosen = prefs.port.value
        if (chosen != null && chosen.startsWith(BuildActions.OTA_PREFIX)) return chosen
        val ports = c.usbPorts.ports.value
        if (chosen != null && ports.any { it.deviceName == chosen }) return chosen
        // Device names change when a board is re-plugged; with one board attached, just use it.
        return ports.singleOrNull()?.deviceName?.also { prefs.port.set(it) }
    }

    // ------------------------------------------------------------- build

    fun verify() = runJob("Verify") { fqbn, dir ->
        c.build.compile(this, dir, fqbn)
    }

    fun upload() {
        val port = resolvePort()
        if (port == null) {
            message(if (c.usbPorts.ports.value.isEmpty()) "No USB serial device connected" else "Select a port first")
            return
        }
        runJob("Upload") { fqbn, dir -> c.build.upload(this, dir, fqbn, port) }
    }

    fun cancelJob() = c.jobs.cancel()

    fun clearConsole() = c.console.clear()

    private fun runJob(title: String, block: suspend JobScope.(String, File) -> Unit) {
        val fqbn = prefs.fqbn.value
        val dir = _sketchDir.value
        if (fqbn == null) {
            message("Select a board first")
            return
        }
        if (dir == null) return
        val started = c.jobs.launch(title) {
            saveAll()
            block(fqbn, dir)
        }
        if (!started) message("Another task is still running")
    }

    /** Copies the flashable output of the last build to the cache for sharing. */
    suspend fun exportBinary(context: Context): File? = withContext(Dispatchers.IO) {
        val fqbn = prefs.fqbn.value ?: return@withContext null
        val dir = _sketchDir.value ?: return@withContext null
        val build = c.env.hostPath(c.cli.buildPathFor(dir.name, fqbn))
        val candidates = listOf(".ino.merged.bin", ".ino.uf2", ".ino.bin", ".ino.hex").map { File(build, dir.name + it) }
        val src = candidates.firstOrNull { it.isFile } ?: return@withContext null
        val out = File(context.cacheDir, "export").apply { mkdirs() }
        src.copyTo(File(out, src.name), overwrite = true)
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                message(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
