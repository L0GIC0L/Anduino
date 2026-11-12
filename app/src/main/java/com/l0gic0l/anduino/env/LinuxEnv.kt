package com.l0gic0l.anduino.env

import android.content.Context
import android.os.Build
import java.io.File

/**
 * A Ubuntu userland running under proot. proot (shipped as libproot.so in the
 * APK's native library dir, which Android allows to be executed) intercepts the
 * syscalls of the Linux programs and maps paths into [rootfs], so glibc-linked
 * toolchains such as arduino-cli, gcc and esptool run unmodified and without root.
 */
class LinuxEnv(context: Context) {
    private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibDir, "libproot.so")
    private val loader = File(nativeLibDir, "libproot-loader.so")

    val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val prootTmp = File(base, "proot-tmp")
    private val fakeProc = File(base, "fake-proc")
    private val marker = File(base, ".installed")

    /** Set from settings; disables proot's seccomp acceleration on devices where it misbehaves. */
    @Volatile var disableSeccomp: Boolean = false

    val isInstalled: Boolean
        get() = marker.exists() && hostPath(ARDUINO_CLI).exists()

    fun markInstalled(version: String) {
        marker.writeText(version)
    }

    fun clearInstalledMarker() {
        marker.delete()
    }

    /** Maps a path inside the Linux environment to the real file on Android. */
    fun hostPath(guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    /** Inverse of [hostPath]; null if [host] is outside the rootfs. */
    fun guestPath(host: File): String? {
        val root = rootfs.canonicalPath
        val p = host.canonicalPath
        if (p == root) return "/"
        return if (p.startsWith("$root/")) p.removePrefix(root) else null
    }

    /**
     * Builds a [ProcessBuilder] that runs [argv] inside the environment with a clean
     * environment. [cwd] is a guest path.
     */
    fun processBuilder(argv: List<String>, cwd: String = HOME, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        prootTmp.mkdirs()
        val cmd = ArrayList<String>()
        cmd += proot.absolutePath
        cmd += listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "--root-id")
        cmd += "--rootfs=${rootfs.absolutePath}"
        cmd += "--cwd=$cwd"
        cmd += listOf(
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=${File(rootfs, "tmp").absolutePath}:/dev/shm",
        )
        // Android hides several /proc files from apps; some tools expect them.
        for (name in FAKE_PROC_FILES) {
            val fake = File(fakeProc, name)
            if (fake.exists() && !File("/proc/$name").canRead()) cmd += "--bind=${fake.absolutePath}:/proc/$name"
        }
        cmd += "/usr/bin/env"
        cmd += "-i"
        val env = linkedMapOf(
            "HOME" to HOME,
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "TERM" to "dumb",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        env.putAll(extraEnv)
        env.forEach { (k, v) -> cmd += "$k=$v" }
        cmd += argv

        return ProcessBuilder(cmd).apply {
            directory(base)
            environment().apply {
                clear()
                put("PROOT_LOADER", loader.absolutePath)
                put("PROOT_TMP_DIR", prootTmp.absolutePath)
                put("LD_LIBRARY_PATH", nativeLibDir.absolutePath)
                put("PATH", System.getenv("PATH") ?: "/system/bin")
                put("ANDROID_ROOT", System.getenv("ANDROID_ROOT") ?: "/system")
                put("ANDROID_DATA", System.getenv("ANDROID_DATA") ?: "/data")
                if (disableSeccomp) put("PROOT_NO_SECCOMP", "1")
            }
        }
    }

    /** Convenience: run a bash script inside the environment. */
    fun bash(script: String, cwd: String = HOME): ProcessBuilder =
        processBuilder(listOf("/bin/bash", "-c", script), cwd)

    suspend fun stream(argv: List<String>, cwd: String = HOME, onOutput: (OutputLine) -> Unit): Int =
        ProcessRunner.stream(processBuilder(argv, cwd), onOutput)

    suspend fun capture(argv: List<String>, cwd: String = HOME): ExecResult =
        ProcessRunner.capture(processBuilder(argv, cwd))

    /** Writes the small files the rootfs needs to work under proot on Android. */
    fun configureRootfs(dnsServers: List<String>) {
        File(rootfs, "tmp").apply { mkdirs(); setWritable(true, false) }
        File(rootfs, "root/Arduino").mkdirs()
        writeResolvConf(dnsServers)
        File(rootfs, "etc/hosts").apply {
            delete()
            writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        // apt normally drops privileges to "_apt", which proot's fake root can't do.
        File(rootfs, "etc/apt/apt.conf.d/99anduino").writeText(
            "APT::Sandbox::User \"root\";\nAPT::Install-Recommends \"false\";\nAcquire::Retries \"3\";\n",
        )
        fakeProc.mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors()
        File(fakeProc, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(fakeProc, "uptime").writeText("7212.50 21310.03\n")
        File(fakeProc, "version").writeText(
            "Linux version ${System.getProperty("os.version")} (android@anduino) #1 SMP PREEMPT ${Build.VERSION.RELEASE}\n",
        )
        File(fakeProc, "stat").writeText(
            buildString {
                append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
                for (i in 0 until cpus) append("cpu$i 100 0 100 10000 10 10 10 0 0 0\n")
                append("intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n")
            },
        )
        File(fakeProc, "vmstat").writeText("nr_free_pages 100000\npgpgin 0\npgpgout 0\n")
    }

    /** Uses the DNS servers of the active Android network, so the rootfs resolves names like the phone does. */
    fun writeResolvConf(dnsServers: List<String>) {
        val servers = (dnsServers + FALLBACK_DNS).distinct().take(4)
        File(rootfs, "etc/resolv.conf").apply {
            delete() // Ubuntu ships it as a dangling symlink to systemd-resolved
            writeText(servers.joinToString("") { "nameserver $it\n" })
        }
    }

    companion object {
        const val HOME = "/root"
        const val SKETCHBOOK = "/root/Arduino"
        const val ARDUINO_CLI = "/usr/local/bin/arduino-cli"
        const val BUILD_ROOT = "/root/.cache/anduino/build"
        private val FAKE_PROC_FILES = listOf("loadavg", "stat", "uptime", "version", "vmstat")
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    }
}
