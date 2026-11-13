package com.l0gic0l.anduino.env

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Finds and fixes symlinks left by proot's hard-link emulation ("link2symlink") that
 * the Linux side can't follow. Android forbids link(2) in app storage, so proot turns
 * every hard link into absolute *host* symlinks to backing files named `.l2s.*`. proot
 * can only follow such a link when its target starts with the fully resolved (canonical)
 * rootfs path. Two bugs in Anduino broke that:
 *
 * - 0.1.0 had no PROOT_L2S_DIR, so backing files sat inside arduino-cli's temporary
 *   extraction directory and the links dangled after it was renamed into place. Those
 *   files are gone: the install has to be redone ([Scan.damagedInstallDirs]).
 * - 0.1.1 passed PROOT_L2S_DIR as `/data/user/0/...`, a symlink to `/data/data/...`, so
 *   proot didn't recognise its own links. The files exist; rewriting the link targets in
 *   canonical form repairs them instantly ([Scan.fixable]).
 *
 * Either way the compiler launcher reports e.g. "xtensa-esp-elf-g++ is not exist".
 */
object BrokenLinks {
    private val L2S_MARKERS = listOf("/.l2s.", "/.proot.l2s.")

    data class Scan(
        /** link -> the canonical target it should have. */
        val fixable: Map<Path, Path>,
        /** `<packager>/<tools|hardware>/<name>/<version>` dirs whose backing files are lost. */
        val damagedInstallDirs: Set<Path>,
    ) {
        val isClean: Boolean get() = fixable.isEmpty() && damagedInstallDirs.isEmpty()
    }

    /**
     * Scans [packagesDir] (installed cores and tools) and [l2sDir] (proot's backing
     * files, whose intermediate links have the same problem). [canonicalRootfs] is the
     * rootfs path with every symlink resolved, as proot sees it.
     */
    fun scan(packagesDir: Path, l2sDir: Path, canonicalRootfs: String): Scan {
        val fixable = LinkedHashMap<Path, Path>()
        val damaged = LinkedHashSet<Path>()
        val rootPrefix = canonicalRootfs.trimEnd('/') + "/"
        for (dir in listOf(packagesDir, l2sDir)) {
            if (!Files.isDirectory(dir)) continue
            walkLinks(dir) { link ->
                val target = try {
                    Files.readSymbolicLink(link).toString()
                } catch (_: IOException) {
                    return@walkLinks
                }
                if (!target.startsWith("/") || L2S_MARKERS.none { target.contains(it) }) return@walkLinks
                if (target.startsWith(rootPrefix) && Files.exists(link)) return@walkLinks
                val canonical = canonicalTarget(target)
                if (canonical != null && canonical.toString().startsWith(rootPrefix)) {
                    if (canonical.toString() != target) fixable[link] = canonical
                } else if (dir == packagesDir) {
                    installDirOf(packagesDir, link)?.let { damaged.add(it) }
                }
            }
        }
        return Scan(fixable, damaged)
    }

    /** Rewrites each fixable link to its canonical target. Returns how many were fixed. */
    fun fix(fixable: Map<Path, Path>): Int {
        var fixed = 0
        for ((link, target) in fixable) {
            try {
                Files.delete(link)
                Files.createSymbolicLink(link, target)
                fixed++
            } catch (_: IOException) {
            }
        }
        return fixed
    }

    /**
     * Resolves symlinks in the target's *directory* only, keeping the final name, so a
     * link to an intermediate stays a link to that intermediate (proot counts links
     * through them). Null if the target doesn't exist at all.
     */
    private fun canonicalTarget(target: String): Path? {
        val file = File(target)
        val parent = file.parentFile ?: return null
        if (!parent.exists()) return null
        val resolved = Paths.get(parent.canonicalPath, file.name)
        return if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) resolved else null
    }

    private fun walkLinks(root: Path, onLink: (Path) -> Unit) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) onLink(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
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
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
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
