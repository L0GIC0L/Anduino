package com.l0gic0l.anduino.env

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Finds board-package installs damaged by proot's hard-link emulation running without
 * PROOT_L2S_DIR (Anduino 0.1.0): hard links inside toolchain archives became absolute
 * symlinks into arduino-cli's temporary extraction directory, which was then renamed,
 * leaving them dangling (e.g. `xtensa-esp-elf-g++` "is not exist").
 */
object BrokenLinks {
    private val L2S_MARKERS = listOf("/.l2s.", "/.proot.l2s.")

    /**
     * Returns the install directories under [packagesDir]
     * (`<packager>/tools/<tool>/<version>` and `<packager>/hardware/<arch>/<version>`)
     * that contain a dangling proot hard-link symlink.
     */
    fun findDamagedInstallDirs(packagesDir: Path): Set<Path> {
        if (!Files.isDirectory(packagesDir)) return emptySet()
        val damaged = LinkedHashSet<Path>()
        Files.walkFileTree(
            packagesDir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink && isDanglingL2sLink(file)) {
                        installDirOf(packagesDir, file)?.let { damaged.add(it) }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return damaged
    }

    private fun isDanglingL2sLink(link: Path): Boolean {
        val target = try {
            Files.readSymbolicLink(link).toString()
        } catch (_: IOException) {
            return false
        }
        return L2S_MARKERS.any { target.contains(it) } && !Files.exists(link) // exists() follows the link
    }

    /** `<packages>/<packager>/<tools|hardware>/<name>/<version>` containing [file], if any. */
    private fun installDirOf(packagesDir: Path, file: Path): Path? {
        val rel = packagesDir.relativize(file)
        if (rel.nameCount < 5) return null
        val kind = rel.getName(1).toString()
        if (kind != "tools" && kind != "hardware") return null
        return packagesDir.resolve(rel.subpath(0, 4))
    }

    /** Deletes a directory tree without following symlinks out of it. */
    fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
