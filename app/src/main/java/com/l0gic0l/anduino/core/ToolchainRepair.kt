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
 * Keeps installed toolchains usable despite proot's hard-link emulation (see
 * [BrokenLinks]): link targets in the wrong form are rewritten in place, and installs
 * whose files are lost are reinstalled at the same version from arduino-cli's download
 * cache, so nothing is downloaded again.
 */
class ToolchainRepair(
    private val env: LinuxEnv,
    private val cli: ArduinoCli,
    private val console: ConsoleLog,
) {
    private val checkedMarker = File(env.base, ".toolchains-checked-v2")

    private fun scan() = BrokenLinks.scan(env.packagesDir.toPath(), env.l2sDir.toPath(), env.rootfs.canonicalPath)

    /**
     * Runs once per install at app start: fixes what can be fixed in place (instant) and
     * returns true if some install lost its files and needs the reinstall [repair].
     */
    suspend fun checkAndFix(): Boolean = withContext(Dispatchers.IO) {
        if (checkedMarker.exists()) return@withContext false
        val s = scan()
        if (s.fixable.isNotEmpty()) {
            val n = BrokenLinks.fix(s.fixable)
            console.success("Repaired $n toolchain links left unusable by an earlier Anduino version.")
        }
        if (s.damagedInstallDirs.isEmpty()) checkedMarker.writeText("ok")
        s.damagedInstallDirs.isNotEmpty()
    }

    /** After a core install: fix and verify links, so a problem shows up here, not at compile time. */
    suspend fun verifyAfterInstall(job: JobScope) {
        job.step("Checking toolchain links")
        val s = withContext(Dispatchers.IO) { scan() }
        if (s.fixable.isNotEmpty()) {
            val n = withContext(Dispatchers.IO) { BrokenLinks.fix(s.fixable) }
            console.info("Rewrote $n toolchain links.")
        }
        reportGuestView()
    }

    suspend fun repair(job: JobScope) {
        job.step("Checking installed toolchains")
        val s = withContext(Dispatchers.IO) { scan() }
        val root = env.packagesDir.toPath()
        if (s.fixable.isNotEmpty()) {
            val n = withContext(Dispatchers.IO) { BrokenLinks.fix(s.fixable) }
            console.success("Rewrote $n toolchain links.")
        }
        if (s.damagedInstallDirs.isNotEmpty()) {
            console.info("Files lost in: " + s.damagedInstallDirs.joinToString { root.relativize(it).toString() })
            reinstallAll(job)
        }
        if (reportGuestView()) checkedMarker.writeText("ok")
    }

    private suspend fun reinstallAll(job: JobScope) {
        // Read the installed versions before deleting anything: a deleted core
        // directory makes arduino-cli forget the core is installed.
        val platforms = cli.coreList().filter { it.installed != null && !it.manuallyInstalled }
        val damaged = withContext(Dispatchers.IO) { scan().damagedInstallDirs }
        withContext(Dispatchers.IO) { damaged.forEach(BrokenLinks::deleteTree) }
        val log: (OutputLine) -> Unit = { line ->
            console.append(line)
            if (line.transient) job.step(line.text)
        }
        // Uninstalling also drops tools no other core needs, so the reinstall extracts
        // every tool again.
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
    }

    /**
     * Asks the Linux side itself which emulated hard links it can't follow, which is the
     * ground truth whatever the cause. Returns true if there are none.
     */
    private suspend fun reportGuestView(): Boolean {
        val r = env.capture(
            listOf(
                "/usr/bin/find", "/root/.arduino15/packages", "-xtype", "l",
                "-lname", "*.l2s.*", "-print",
            ),
        )
        val broken = r.stdout.lines().filter { it.isNotBlank() }
        return if (broken.isEmpty()) {
            console.success("Toolchain links OK.")
            true
        } else {
            console.error("${broken.size} toolchain links are still unusable, for example:")
            broken.take(5).forEach { path ->
                val target = env.capture(listOf("/usr/bin/readlink", path)).stdout.trim()
                console.error("  $path -> $target")
            }
            console.error("Please send this Output text to the developer.")
            false
        }
    }
}
