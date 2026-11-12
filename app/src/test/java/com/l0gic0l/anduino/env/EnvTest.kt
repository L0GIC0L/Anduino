package com.l0gic0l.anduino.env

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class EnvTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarGz(file: File, build: TarArchiveOutputStream.() -> Unit) {
        TarArchiveOutputStream(GzipCompressorOutputStream(file.outputStream())).use {
            it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            it.build()
        }
    }

    private fun TarArchiveOutputStream.file(name: String, text: String, mode: Int = 0b110_100_100) {
        val bytes = text.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong(); this.mode = mode })
        write(bytes)
        closeArchiveEntry()
    }

    @Test
    fun extractsFilesSymlinksHardlinksAndModes() = runBlocking {
        val archive = tmp.newFile("rootfs.tar.gz")
        tarGz(archive) {
            putArchiveEntry(TarArchiveEntry("./usr/bin/", TarArchiveEntry.LF_DIR)); closeArchiveEntry()
            file("./usr/bin/bash", "#!bash", mode = 0b111_101_101)
            putArchiveEntry(TarArchiveEntry("./bin", TarArchiveEntry.LF_SYMLINK).apply { linkName = "usr/bin" }); closeArchiveEntry()
            putArchiveEntry(TarArchiveEntry("./usr/bin/sh", TarArchiveEntry.LF_LINK).apply { linkName = "./usr/bin/bash" }); closeArchiveEntry()
            file("./etc/" + "x".repeat(150) + "/deep.conf", "long names work")
        }
        val dest = tmp.newFolder("out")
        TarGz.extract(archive, dest)
        assertEquals("#!bash", File(dest, "usr/bin/bash").readText())
        assertTrue(File(dest, "usr/bin/bash").canExecute())
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("#!bash", File(dest, "bin/bash").readText()) // through the symlink
        assertEquals("#!bash", File(dest, "usr/bin/sh").readText()) // hard link copied
        assertEquals("long names work", File(dest, "etc/" + "x".repeat(150) + "/deep.conf").readText())
    }

    @Test(expected = IOException::class)
    fun rejectsPathTraversal() = runBlocking {
        val archive = tmp.newFile("evil.tar.gz")
        tarGz(archive) { file("../escape.txt", "nope") }
        TarGz.extract(archive, tmp.newFolder("out2"))
    }

    @Test
    fun mapNameFiltersEntries() = runBlocking {
        val archive = tmp.newFile("cli.tar.gz")
        tarGz(archive) {
            file("LICENSE.txt", "GPL")
            file("arduino-cli", "binary", mode = 0b111_101_101)
        }
        val dest = tmp.newFolder("bin")
        TarGz.extract(archive, dest) { if (it == "arduino-cli") it else null }
        assertEquals(listOf("arduino-cli"), dest.list()!!.toList())
    }

    @Test
    fun processRunnerSplitsLinesAndProgressUpdates() = runBlocking {
        val lines = mutableListOf<OutputLine>()
        val code = ProcessRunner.stream(
            ProcessBuilder("sh", "-c", "printf 'one\\r\\ntwo\\n10%%\\r50%%\\rdone\\n\\033[32mgreen\\033[0m\\n'; printf 'err\\n' >&2; exit 3"),
        ) { synchronized(lines) { lines += it } }
        assertEquals(3, code)
        val out = lines.filter { !it.isError }
        assertEquals(listOf("one", "two", "10%", "50%", "done", "green"), out.map { it.text })
        assertEquals(listOf(false, false, true, true, false, false), out.map { it.transient })
        assertEquals(listOf("err"), lines.filter { it.isError }.map { it.text })
    }

    @Test
    fun captureCollectsOutput() = runBlocking {
        val r = ProcessRunner.capture(ProcessBuilder("sh", "-c", "echo hello; echo bad >&2"))
        assertTrue(r.ok)
        assertEquals("hello\n", r.stdout)
        assertEquals("bad\n", r.stderr)
    }
}
