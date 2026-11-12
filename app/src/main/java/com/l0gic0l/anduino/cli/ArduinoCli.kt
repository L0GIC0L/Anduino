package com.l0gic0l.anduino.cli

import com.l0gic0l.anduino.env.LinuxEnv
import com.l0gic0l.anduino.env.OutputLine
import com.l0gic0l.anduino.flash.BuildProperties
import kotlinx.serialization.json.Json
import java.io.IOException

class CliException(message: String) : IOException(message)

/** Fully qualified board name, e.g. `esp32:esp32:esp32s3:CDCOnBoot=cdc,PSRAM=opi`. */
data class Fqbn(val base: String, val options: Map<String, String> = emptyMap()) {
    override fun toString(): String =
        if (options.isEmpty()) base else base + ":" + options.entries.joinToString(",") { "${it.key}=${it.value}" }

    val packager: String get() = base.substringBefore(':')
    val architecture: String get() = base.split(':').getOrElse(1) { "" }

    companion object {
        fun parse(s: String): Fqbn {
            val parts = s.split(':')
            if (parts.size < 3) return Fqbn(s)
            val opts = LinkedHashMap<String, String>()
            parts.getOrNull(3)?.split(',')?.forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) opts[kv.substring(0, i)] = kv.substring(i + 1)
            }
            return Fqbn(parts.take(3).joinToString(":"), opts)
        }
    }
}

/**
 * Thin wrapper around the arduino-cli binary inside the Linux environment.
 * arduino-cli is the same engine Arduino IDE 2 runs on, so every board package and
 * library from the Arduino ecosystem works through it.
 */
class ArduinoCli(private val env: LinuxEnv) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private suspend inline fun <reified T> query(args: List<String>): T {
        val r = env.capture(listOf(LinuxEnv.ARDUINO_CLI) + args + listOf("--format", "json", "--no-color"))
        if (!r.ok) throw CliException(r.stderr.ifBlank { r.stdout }.trim().ifBlank { "arduino-cli exited with ${r.exitCode}" })
        val start = r.stdout.indexOf('{')
        if (start < 0) throw CliException("Unexpected output: ${r.stdout.take(200)}")
        return json.decodeFromString(r.stdout.substring(start))
    }

    /** Runs a command, streaming its human-readable output. Returns the exit code. */
    suspend fun run(args: List<String>, onOutput: (OutputLine) -> Unit): Int {
        val argv = listOf(LinuxEnv.ARDUINO_CLI) + args + "--no-color"
        onOutput(OutputLine("$ arduino-cli ${args.joinToString(" ")}", false))
        return env.stream(argv, onOutput = onOutput)
    }

    // --------------------------------------------------------------- platforms

    suspend fun updateIndexes(onOutput: (OutputLine) -> Unit): Int {
        val a = run(listOf("core", "update-index"), onOutput)
        val b = run(listOf("lib", "update-index"), onOutput)
        return if (a != 0) a else b
    }

    suspend fun coreSearch(query: String = ""): List<PlatformSummary> =
        query<CoreSearchResult>(listOf("core", "search") + query.split(' ').filter { it.isNotBlank() }).platforms

    suspend fun coreList(): List<PlatformSummary> =
        query<CoreSearchResult>(listOf("core", "list")).platforms

    suspend fun coreInstall(id: String, version: String?, onOutput: (OutputLine) -> Unit) =
        run(listOf("core", "install", if (version != null) "$id@$version" else id), onOutput)

    suspend fun coreUninstall(id: String, onOutput: (OutputLine) -> Unit) =
        run(listOf("core", "uninstall", id), onOutput)

    suspend fun setBoardManagerUrls(urls: List<String>): Boolean {
        val clean = urls.map { it.trim() }.filter { it.isNotEmpty() }
        val argv = if (clean.isEmpty()) {
            listOf(LinuxEnv.ARDUINO_CLI, "config", "delete", "board_manager.additional_urls")
        } else {
            listOf(LinuxEnv.ARDUINO_CLI, "config", "set", "board_manager.additional_urls") + clean
        }
        return env.capture(argv).ok
    }

    // --------------------------------------------------------------- boards

    suspend fun boardListAll(): List<BoardListItem> =
        query<BoardListAllResult>(listOf("board", "listall")).boards.filterNot { it.isHidden }

    suspend fun boardDetails(fqbn: String): BoardDetails =
        query(listOf("board", "details", "-b", fqbn))

    // --------------------------------------------------------------- libraries

    suspend fun libSearch(query: String): List<SearchedLibrary> =
        query<LibSearchResult>(
            listOf("lib", "search", "--omit-releases-details") + query.split(' ').filter { it.isNotBlank() },
        ).libraries

    suspend fun libList(): List<InstalledLibrary> =
        query<LibListResult>(listOf("lib", "list")).installedLibraries

    suspend fun libInstall(name: String, version: String?, onOutput: (OutputLine) -> Unit) =
        run(listOf("lib", "install", if (version != null) "$name@$version" else name), onOutput)

    suspend fun libInstallZip(guestZipPath: String, onOutput: (OutputLine) -> Unit) =
        run(listOf("lib", "install", "--zip-path", guestZipPath), onOutput)

    suspend fun libUninstall(name: String, onOutput: (OutputLine) -> Unit) =
        run(listOf("lib", "uninstall", name), onOutput)

    /** Examples of all installed libraries, including the ones bundled with the board's core. */
    suspend fun libExamples(fqbn: String?): List<LibraryExamples> =
        query<LibExamplesResult>(listOf("lib", "examples") + (fqbn?.let { listOf("-b", Fqbn.parse(it).base) } ?: emptyList())).examples

    // --------------------------------------------------------------- build

    fun buildPathFor(sketchName: String, fqbn: String): String {
        val board = Fqbn.parse(fqbn).base.replace(':', '.')
        return "${LinuxEnv.BUILD_ROOT}/${sketchName}_$board"
    }

    suspend fun compile(
        sketchDir: String,
        fqbn: String,
        buildPath: String,
        warnings: String,
        verbose: Boolean,
        onOutput: (OutputLine) -> Unit,
    ): Int {
        val args = mutableListOf("compile", "-b", fqbn, "--build-path", buildPath, "--warnings", warnings)
        if (verbose) args += "-v"
        args += sketchDir
        return run(args, onOutput)
    }

    /** Expanded build properties for this sketch/board (no compilation happens). */
    suspend fun buildProperties(sketchDir: String, fqbn: String, buildPath: String): Map<String, String> {
        val r = env.capture(
            listOf(
                LinuxEnv.ARDUINO_CLI, "compile", "-b", fqbn, "--build-path", buildPath,
                "--show-properties=expanded", "--no-color", sketchDir,
            ),
        )
        if (!r.ok) throw CliException(r.stderr.ifBlank { r.stdout }.trim())
        return BuildProperties.parse(r.stdout)
    }

    /** Over-the-air upload (ArduinoOTA / espota) handled by arduino-cli itself. */
    suspend fun uploadNetwork(
        sketchDir: String,
        fqbn: String,
        buildPath: String,
        address: String,
        password: String?,
        onOutput: (OutputLine) -> Unit,
    ): Int {
        val args = mutableListOf(
            "upload", "-b", fqbn, "--input-dir", buildPath, "-p", address, "--protocol", "network",
        )
        if (!password.isNullOrEmpty()) args += listOf("--upload-field", "password=$password")
        args += sketchDir
        return run(args, onOutput)
    }
}
