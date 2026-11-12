package com.l0gic0l.anduino.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class BrokenLinksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun findsToolDirsWithDanglingProotHardLinks() {
        val packages = tmp.newFolder("packages")
        val bin = File(packages, "esp32/tools/esp-x32/2601/bin").apply { mkdirs() }
        File(bin, "xtensa-esp-elf-c++").writeText("real compiler")
        File(bin, "xtensa-esp-elf-gcc").writeText("gcc")
        // What proot's link2symlink left behind: an absolute link into the renamed temp dir.
        Files.createSymbolicLink(
            File(bin, "xtensa-esp-elf-g++").toPath(),
            Paths.get("/data/x/rootfs/root/.arduino15/staging/tmp/package-123/xtensa-esp-elf/bin/.l2s.xtensa-esp-elf-c++0001.0002"),
        )
        // Ordinary symlinks, valid or dangling, are not ours to judge.
        Files.createSymbolicLink(File(bin, "xtensa-esp-elf-cc").toPath(), Paths.get("xtensa-esp-elf-gcc"))
        Files.createSymbolicLink(File(bin, "dangling-but-normal").toPath(), Paths.get("/nonexistent/file"))
        // An intact second tool and a core.
        File(packages, "esp32/tools/esptool_py/5.3.1").mkdirs()
        File(packages, "esp32/hardware/esp32/3.3.12/cores").mkdirs()

        val damaged = BrokenLinks.findDamagedInstallDirs(packages.toPath())
        assertEquals(setOf(File(packages, "esp32/tools/esp-x32/2601").toPath()), damaged)
    }

    @Test
    fun nothingDamagedInCleanOrMissingDir() {
        assertTrue(BrokenLinks.findDamagedInstallDirs(tmp.newFolder("empty").toPath()).isEmpty())
        assertTrue(BrokenLinks.findDamagedInstallDirs(File(tmp.root, "missing").toPath()).isEmpty())
    }

    @Test
    fun deleteTreeDoesNotFollowSymlinksOutOfTheTree() {
        val outside = tmp.newFolder("outside").apply { File(this, "keep.txt").writeText("keep") }
        val tree = tmp.newFolder("tree")
        File(tree, "sub").mkdirs()
        File(tree, "sub/file").writeText("x")
        Files.createSymbolicLink(File(tree, "link-to-outside").toPath(), outside.toPath())
        BrokenLinks.deleteTree(tree.toPath())
        assertFalse(tree.exists())
        assertTrue(File(outside, "keep.txt").exists())
    }
}
