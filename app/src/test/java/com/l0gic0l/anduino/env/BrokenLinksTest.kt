package com.l0gic0l.anduino.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class BrokenLinksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun link(at: File, to: String) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), Paths.get(to))
    }

    /** A rootfs at real/rootfs, also reachable through alias -> real (like /data/user/0 -> /data/data). */
    private class Layout(val rootfs: File, val alias: File) {
        val packages: File get() = File(rootfs, "root/.arduino15/packages")
        val l2s: File get() = File(rootfs, ".l2s")
        fun aliasPath(f: File): String = alias.path + f.path.removePrefix(rootfs.parentFile!!.path)
    }

    private fun layout(): Layout {
        val real = tmp.newFolder("real")
        val alias = File(tmp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val rootfs = File(real, "rootfs").apply { mkdirs() }
        return Layout(rootfs.canonicalFile, alias)
    }

    private fun scan(l: Layout) = BrokenLinks.scan(l.packages.toPath(), l.l2s.toPath(), l.rootfs.canonicalPath)

    @Test
    fun rewritesLinksThatUseANonCanonicalRootfsPath() {
        val l = layout()
        // What Anduino 0.1.1 produced: intermediate and final backing files in .l2s,
        // with link contents spelled through the alias.
        val final = File(l.l2s, ".l2s.xtensa-esp-elf-c++0001").apply { parentFile!!.mkdirs(); writeText("compiler") }
        val intermediate = File(l.l2s, ".l2s.xtensa-esp-elf-c++0001.0002")
        link(intermediate, l.aliasPath(final))
        val gxx = File(l.packages, "esp32/tools/esp-x32/2601/bin/xtensa-esp-elf-g++")
        link(gxx, l.aliasPath(intermediate))

        val s = scan(l)
        assertTrue(s.damagedInstallDirs.isEmpty())
        assertEquals(setOf(gxx.toPath(), intermediate.toPath()), s.fixable.keys)
        assertEquals(2, BrokenLinks.fix(s.fixable))

        assertEquals(intermediate.path, Files.readSymbolicLink(gxx.toPath()).toString())
        assertEquals(final.path, Files.readSymbolicLink(intermediate.toPath()).toString())
        assertEquals("compiler", gxx.readText())
        assertTrue(scan(l).isClean)
    }

    @Test
    fun reportsInstallsWhoseBackingFilesAreGone() {
        val l = layout()
        // What 0.1.0 produced: the backing file was inside the renamed temp dir.
        val gxx = File(l.packages, "esp32/tools/esp-x32/2601/bin/xtensa-esp-elf-g++")
        link(gxx, l.rootfs.path + "/root/.arduino15/staging/tmp/package-123/xtensa-esp-elf/bin/.l2s.xtensa-esp-elf-c++0001.0002")
        // Ordinary symlinks, valid or dangling, are not ours to judge.
        File(gxx.parentFile, "xtensa-esp-elf-gcc").writeText("gcc")
        link(File(gxx.parentFile, "xtensa-esp-elf-cc"), "xtensa-esp-elf-gcc")
        link(File(gxx.parentFile, "dangling-but-normal"), "/nonexistent/file")
        File(l.packages, "esp32/hardware/esp32/3.3.12/cores").mkdirs()

        val s = scan(l)
        assertTrue(s.fixable.isEmpty())
        assertEquals(setOf(File(l.packages, "esp32/tools/esp-x32/2601").toPath()), s.damagedInstallDirs)
    }

    @Test
    fun healthyLinksAndMissingDirsAreClean() {
        val l = layout()
        val final = File(l.l2s, ".l2s.libc.a0001").apply { parentFile!!.mkdirs(); writeText("lib") }
        link(File(l.packages, "esp32/tools/esp-x32/2601/lib/libg.a"), final.path)
        assertTrue(scan(l).isClean)
        val missing: Path = File(tmp.root, "missing").toPath()
        assertTrue(BrokenLinks.scan(missing, missing, "/x").isClean)
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
