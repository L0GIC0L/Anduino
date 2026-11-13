package com.l0gic0l.anduino.env

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Runs Linux (glibc) programs of the rootfs natively, without proot — the "fast path"
 * for compilers, which spend most of their time in file lookups that proot would
 * otherwise intercept one by one (~30x slower on include-heavy ESP32 builds).
 *
 * Android forbids exec() of files in app storage but allows it for the APK's native
 * library dir, where glibc's loader ships (libanduino-ld.so). The loader maps the
 * program from app storage (allowed) and libanduino-shim.so, preloaded into it,
 * reroutes the program's own exec()s the same way. See native/exec_shim.c.
 */
class NativeExec(
    private val nativeLibDir: File,
    /** Canonical rootfs path; it is also bound to the same path inside proot. */
    private val rootfs: File,
    /** Where the glibc soname symlinks go (outside the rootfs). */
    private val glibcDir: File,
    private val triplet: String,
) {
    val loader = File(nativeLibDir, "libanduino-ld.so")
    val shim = File(nativeLibDir, "libanduino-shim.so")
    private val client = File(nativeLibDir, "libanduino-run.so")

    val root: String get() = rootfs.absolutePath

    val libraryPath: String
        get() = listOf(glibcDir.path, "$root/lib/$triplet", "$root/usr/lib/$triplet").joinToString(":")

    val isAvailable: Boolean get() = loader.isFile && shim.isFile && client.isFile

    /** Guest path of the relay client that compiler recipes are prefixed with. */
    val clientGuestPath = CLIENT_GUEST_PATH

    /** Creates the glibc soname links and copies the relay client into the rootfs. */
    fun install() {
        glibcDir.mkdirs()
        for ((lib, soname) in GLIBC_LIBS) {
            val link = Paths.get(glibcDir.path, soname)
            val target = Paths.get(nativeLibDir.path, lib)
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) continue
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        // Copied every time (once per app start): app updates replace the APK's lib dir.
        val dest = File(rootfs, CLIENT_GUEST_PATH.removePrefix("/"))
        dest.parentFile?.mkdirs()
        client.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
    }

    /** Environment for natively run programs (and inherited by their children). */
    fun environment(program: String): Map<String, String> = linkedMapOf(
        "PATH" to "$root/usr/local/bin:$root/usr/bin:$root/bin",
        "HOME" to "$root/root",
        "TMPDIR" to "$root/tmp",
        "LANG" to "C.UTF-8",
        // glibc's restartable-sequences registration can be refused by Android's seccomp filter.
        "GLIBC_TUNABLES" to "glibc.pthread.rseq=0",
        "ANDUINO_LDSO" to loader.path,
        "ANDUINO_LIBPATH" to libraryPath,
        "ANDUINO_SHIM" to shim.path,
        "ANDUINO_ROOT" to root,
        "ANDUINO_SELF_EXE" to program,
    )

    /** Command line that runs [program] through the loader with [args] (argv[0] excluded). */
    fun command(program: String, argv0: String, args: List<String>): List<String> =
        listOf(
            loader.path, "--library-path", libraryPath, "--preload", shim.path,
            "--argv0", argv0, program,
        ) + args

    fun processBuilder(program: String, argv0: String, args: List<String>, cwd: File): ProcessBuilder =
        ProcessBuilder(command(program, argv0, args)).apply {
            directory(cwd)
            environment().apply {
                clear()
                putAll(environment(program))
            }
        }

    /** True for a dynamically linked ELF built for this device (what the loader can run). */
    fun canRun(file: File): Boolean = isDynamicElf(file, expectedMachine)

    private val expectedMachine: Int = if (triplet.startsWith("x86_64")) EM_X86_64 else EM_AARCH64

    companion object {
        const val CLIENT_GUEST_PATH = "/opt/anduino/bin/anduino-run"
        const val CONF_GUEST_PATH = "/opt/anduino/run.conf"

        private const val EM_X86_64 = 62
        private const val EM_AARCH64 = 183
        private const val PT_INTERP = 3

        /** jniLibs file -> soname the toolchains ask for. */
        val GLIBC_LIBS = listOf(
            "libglibc-c.so" to "libc.so.6",
            "libglibc-m.so" to "libm.so.6",
            "libglibc-pthread.so" to "libpthread.so.0",
            "libglibc-dl.so" to "libdl.so.2",
            "libglibc-rt.so" to "librt.so.1",
            "libglibc-util.so" to "libutil.so.1",
            "libglibc-resolv.so" to "libresolv.so.2",
        )

        fun isDynamicElf(file: File, machine: Int): Boolean {
            if (!file.isFile) return false
            return try {
                RandomAccessFile(file, "r").use { f ->
                    val head = ByteArray(64)
                    if (f.read(head) < 64) return false
                    if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                        head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
                    ) return false
                    if (head[4].toInt() != 2 || head[5].toInt() != 1) return false // 64-bit, little endian
                    val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                    if ((h.getShort(18).toInt() and 0xFFFF) != machine) return false
                    val phoff = h.getLong(32)
                    val phentsize = h.getShort(54).toInt() and 0xFFFF
                    val phnum = h.getShort(56).toInt() and 0xFFFF
                    val ph = ByteArray(phentsize)
                    for (i in 0 until phnum) {
                        f.seek(phoff + i.toLong() * phentsize)
                        if (f.read(ph) < 4) return false
                        if (ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == PT_INTERP) return true
                    }
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
