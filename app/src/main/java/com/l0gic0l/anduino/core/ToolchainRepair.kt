package com.l0gic0l.anduino.core

import com.l0gic0l.anduino.cli.ArduinoCli
import com.l0gic0l.anduino.env.BrokenLinks
import com.l0gic0l.anduino.env.LinuxEnv
import com.l0gic0l.anduino.env.OutputLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Repairs board packages installed before proot was given PROOT_L2S_DIR (see
 * [BrokenLinks]). Damaged tool/core directories are deleted and every installed core is
 * reinstalled at the same version from arduino-cli's download cache, so nothing is
 * downloaded again.
 */
class ToolchainRepair(
    private val env: LinuxEnv,
    private val cli: ArduinoCli,
    private val console: ConsoleLog,
) {
    private val checkedMarker = File(env.base, ".toolchains-checked-v1")

    private fun scan() = BrokenLinks.findDamagedInstallDirs(env.packagesDir.toPath())

    /** Scans once per install; true if damaged toolchains were found. */
    suspend fun needsRepair(): Boolean = withContext(Dispatchers.IO) {
        if (checkedMarker.exists()) return@withContext false
        val damaged = scan()
        if (damaged.isEmpty()) checkedMarker.writeText("ok")
        damaged.isNotEmpty()
    }

    suspend fun repair(job: JobScope) {
        job.step("Checking installed toolchains")
        val damaged = withContext(Dispatchers.IO) { scan() }
        if (damaged.isEmpty()) {
            console.success("No damaged toolchains found.")
            checkedMarker.writeText("ok")
            return
        }
        val root = env.packagesDir.toPath()
        console.info("Damaged installs: " + damaged.joinToString { root.relativize(it).toString() })

        // Read the installed versions before deleting anything: a deleted core
        // directory makes arduino-cli forget the core is installed.
        val platforms = cli.coreList().filter { it.installed != null && !it.manuallyInstalled }
        withContext(Dispatchers.IO) { damaged.forEach(BrokenLinks::deleteTree) }

        val log: (OutputLine) -> Unit = { line ->
            console.append(line)
            if (line.transient) job.step(line.text)
        }
        // Uninstalling also drops tools no other core needs, so the reinstall
        // extracts every tool again (with working hard links this time).
        for (p in platforms) {
            job.step("Removing ${p.name}")
            cli.coreUninstall(p.id, log) // may fail for an already deleted core; that's fine
        }
        for (p in platforms) {
            job.step("Reinstalling ${p.name} ${p.installed}")
            if (cli.coreInstall(p.id, p.installed, log) != 0) {
                throw IOException("Reinstalling ${p.id} failed; install it again from the Boards Manager")
            }
        }

        val remaining = withContext(Dispatchers.IO) { scan() }
        if (remaining.isEmpty()) {
            checkedMarker.writeText("ok")
            console.success("Toolchains repaired. Verify the sketch again.")
        } else {
            console.error("Still damaged: " + remaining.joinToString { root.relativize(it).toString() })
        }
    }
}
