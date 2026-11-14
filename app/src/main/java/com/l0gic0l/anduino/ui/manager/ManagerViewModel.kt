package com.l0gic0l.anduino.ui.manager

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.l0gic0l.anduino.AppContainer
import com.l0gic0l.anduino.cli.InstalledLibrary
import com.l0gic0l.anduino.cli.PlatformSummary
import com.l0gic0l.anduino.cli.SearchedLibrary
import com.l0gic0l.anduino.core.JobScope
import com.l0gic0l.anduino.env.OutputLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Boards Manager and Library Manager. */
class ManagerViewModel(private val c: AppContainer) : ViewModel() {
    val job = c.jobs.current

    private val _platforms = MutableStateFlow<List<PlatformSummary>?>(null)
    val platforms: StateFlow<List<PlatformSummary>?> = _platforms.asStateFlow()

    private val _libResults = MutableStateFlow<List<SearchedLibrary>>(emptyList())
    val libResults: StateFlow<List<SearchedLibrary>> = _libResults.asStateFlow()

    private val _libSearching = MutableStateFlow(false)
    val libSearching: StateFlow<Boolean> = _libSearching.asStateFlow()

    private val _installedLibs = MutableStateFlow<List<InstalledLibrary>?>(null)
    val installedLibs: StateFlow<List<InstalledLibrary>?> = _installedLibs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var searchJob: Job? = null

    fun cancelJob() = c.jobs.cancel()

    // ---------------------------------------------------------------- boards

    fun loadPlatforms() = io {
        _platforms.value = c.cli.coreSearch()
            .sortedWith(compareBy<PlatformSummary>({ it.installed == null }, { it.name.lowercase() }))
    }

    fun installCore(p: PlatformSummary, version: String? = null) =
        job("Installing ${p.name}") {
            check(c.cli.coreInstall(p.id, version, log(this)) == 0) { "arduino-cli core install failed" }
            c.repair.verifyAfterInstall(this)
            c.console.success("${p.name} installed.")
            loadPlatforms()
        }

    fun uninstallCore(p: PlatformSummary) =
        job("Removing ${p.name}") {
            check(c.cli.coreUninstall(p.id, log(this)) == 0) { "arduino-cli core uninstall failed" }
            loadPlatforms()
        }

    fun updateIndexes() =
        job("Updating package indexes") {
            c.cli.setBoardManagerUrls(c.prefs.boardUrlList())
            c.cli.updateIndexes(log(this))
            loadPlatforms()
            loadInstalledLibs()
        }

    // ---------------------------------------------------------------- libraries

    fun searchLibraries(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _libResults.value = emptyList()
            _libSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            _libSearching.value = true
            try {
                _libResults.value = withContext(Dispatchers.IO) { c.cli.libSearch(query) }
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Search failed")
            } finally {
                _libSearching.value = false
            }
        }
    }

    fun loadInstalledLibs() = io {
        _installedLibs.value = c.cli.libList().sortedBy { it.library?.name?.lowercase() }
    }

    fun installLibrary(name: String, version: String? = null) =
        job("Installing $name") {
            check(c.cli.libInstall(name, version, log(this)) == 0) { "arduino-cli lib install failed" }
            c.console.success("$name installed.")
            loadInstalledLibs()
        }

    fun uninstallLibrary(name: String) =
        job("Removing $name") {
            check(c.cli.libUninstall(name, log(this)) == 0) { "arduino-cli lib uninstall failed" }
            loadInstalledLibs()
        }

    /** Sketch > Include Library > Add .ZIP Library. */
    fun installZip(context: Context, uri: Uri) =
        job("Installing library from ZIP") {
            val guest = "/tmp/import-library.zip"
            withContext(Dispatchers.IO) {
                val dest = c.env.hostPath(guest)
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    ?: error("Could not read the selected file")
            }
            try {
                check(c.cli.libInstallZip(guest, log(this)) == 0) { "arduino-cli lib install --zip-path failed" }
                c.console.success("Library installed.")
            } finally {
                File(c.env.hostPath(guest).path).delete()
            }
            loadInstalledLibs()
        }

    // ---------------------------------------------------------------- helpers

    private fun log(scope: JobScope): (OutputLine) -> Unit = { line ->
        c.console.append(line)
        if (line.transient) scope.step(line.text)
    }

    private fun job(title: String, block: suspend JobScope.() -> Unit) {
        if (!c.jobs.launch(title, block)) _messages.tryEmit("Another task is still running")
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
