package com.l0gic0l.anduino.env

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Runs commands handed over by the relay client (native/run_client.c) natively via
 * [NativeExec] and streams their output back. Listens on 127.0.0.1 only and accepts a
 * request only with the random token written to the rootfs (readable only by this app).
 * Anything it can't run natively is answered with "fall back", and the client then runs
 * the command under proot as before, so builds never depend on this working.
 */
class ExecServer(
    private val native: NativeExec,
    /** Host file for the guest path [NativeExec.CONF_GUEST_PATH]. */
    private val confFile: File,
    private val log: (String) -> Unit = {},
) {
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "native-exec").apply { isDaemon = true } }

    /** Set when native runs proved unusable on this device; from then on everything falls back. */
    @Volatile var disabled = false

    @Volatile var commandsRun = 0
        private set

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { writeConf(it.localPort); return it.localPort }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        writeConf(s.localPort)
        thread(name = "native-exec-accept", isDaemon = true) {
            while (!s.isClosed) {
                val socket = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { serve(socket) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        confFile.delete()
    }

    private fun writeConf(port: Int) {
        confFile.parentFile?.mkdirs()
        confFile.writeText("$port $token\n")
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = DataInputStream(s.getInputStream().buffered())
            val output = DataOutputStream(s.getOutputStream().buffered())
            try {
                val magic = ByteArray(4).also { input.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != "ANR1") return
                if (readString(input) != token) return
                val cwd = readString(input)
                val argc = input.readInt()
                if (argc !in 1..100_000) return
                val argv = List(argc) { readString(input) }
                run(argv, cwd, s.getInputStream(), output)
            } catch (_: IOException) {
                // client went away
            }
        }
    }

    private fun run(argv: List<String>, guestCwd: String, clientIn: InputStream, out: DataOutputStream) {
        val program = File(argv[0])
        if (disabled || !program.isAbsolute || !native.canRun(program)) {
            sendFallback(out)
            return
        }
        val process = try {
            native.processBuilder(program.path, argv[0], argv.drop(1), hostCwd(guestCwd)).start()
        } catch (e: IOException) {
            disableBecause("cannot start the native loader: ${e.message}")
            sendFallback(out)
            return
        }
        commandsRun++
        process.outputStream.close()
        // The client closing its end (build cancelled) kills the command.
        thread(isDaemon = true, name = "native-exec-watch") {
            try {
                while (true) {
                    if (clientIn.read() < 0) break
                }
            } catch (_: IOException) {
            }
            if (process.isAlive) process.destroyForcibly()
        }
        val pumps = listOf(
            thread(isDaemon = true) { pump(process.inputStream, 'O', out) },
            thread(isDaemon = true) { pump(process.errorStream, 'E', out) },
        )
        val code = process.waitFor()
        pumps.forEach { it.join() }
        if (code == 128 + SIGSYS) {
            // Killed by Android's seccomp filter: native runs can't work on this device.
            disableBecause("a native compiler process was stopped by the system (SIGSYS)")
            sendFallback(out)
            return
        }
        synchronized(out) {
            out.writeByte('X'.code)
            out.writeInt(4)
            out.writeInt(code)
            out.flush()
        }
    }

    /** The client's working directory as a path outside proot. */
    private fun hostCwd(guestCwd: String): File {
        if (guestCwd == native.root || guestCwd.startsWith(native.root + "/")) return File(guestCwd)
        File(native.root + guestCwd).takeIf { it.isDirectory }?.let { return it } // e.g. /root
        File(guestCwd).takeIf { it.isDirectory }?.let { return it }
        return File(native.root)
    }

    private fun pump(stream: InputStream, type: Char, out: DataOutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                synchronized(out) {
                    out.writeByte(type.code)
                    out.writeInt(n)
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun sendFallback(out: DataOutputStream) {
        synchronized(out) {
            out.writeByte('F'.code)
            out.writeInt(0)
            out.flush()
        }
    }

    private fun disableBecause(reason: String) {
        if (!disabled) {
            disabled = true
            log("Fast native compiler turned off: $reason. Builds continue the slower way.")
        }
    }

    private fun readString(input: DataInputStream): String {
        val n = input.readInt()
        if (n !in 0..1_000_000) throw IOException("bad string length $n")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val SIGSYS = 31
    }
}
