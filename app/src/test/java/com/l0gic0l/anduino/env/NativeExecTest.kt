package com.l0gic0l.anduino.env

import com.l0gic0l.anduino.core.PlatformOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class NativeExecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jni = File("src/main/jniLibs/x86_64")
    private val client = File(jni, "libanduino-run.so")

    @Test
    fun overridesPrefixTheHeavyRecipesOnly() {
        val platformTxt = """
            # ESP32-like excerpt
            compiler.path={tools.xtensa-esp-elf-gcc.path}/bin/
            recipe.c.o.pattern="{compiler.path}{compiler.c.cmd}" {compiler.c.flags} "{source_file}" -o "{object_file}"
            recipe.cpp.o.pattern="{compiler.path}{compiler.cpp.cmd}" {compiler.cpp.flags} "{source_file}" -o "{object_file}"
            recipe.cpp.o.pattern.windows="{compiler.path}{compiler.cpp.cmd}.exe" -o x
            recipe.ar.pattern="{compiler.path}{compiler.ar.cmd}" cr "{archive_file_path}" "{object_file}"
            recipe.c.combine.pattern.linux="{compiler.path}{compiler.c.elf.cmd}" -o "{build.path}/x.elf"
            recipe.hooks.prebuild.1.pattern=/usr/bin/env bash -c "true"
            recipe.objcopy.bin.pattern="{tools.esptool_py.path}/esptool" elf2image
            recipe.objcopy.bin.pattern_args=--chip esp32
        """.trimIndent()
        val out = PlatformOverrides.generate(platformTxt, "/opt/anduino/bin/anduino-run")!!
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(PlatformOverrides.MARKER, lines[0])
        val c = "/opt/anduino/bin/anduino-run"
        assertEquals(
            listOf(
                "recipe.c.o.pattern=$c \"{compiler.path}{compiler.c.cmd}\" {compiler.c.flags} \"{source_file}\" -o \"{object_file}\"",
                "recipe.cpp.o.pattern=$c \"{compiler.path}{compiler.cpp.cmd}\" {compiler.cpp.flags} \"{source_file}\" -o \"{object_file}\"",
                "recipe.ar.pattern=$c \"{compiler.path}{compiler.ar.cmd}\" cr \"{archive_file_path}\" \"{object_file}\"",
                "recipe.c.combine.pattern.linux=$c \"{compiler.path}{compiler.c.elf.cmd}\" -o \"{build.path}/x.elf\"",
                "recipe.hooks.prebuild.1.pattern=$c /usr/bin/env bash -c \"true\"",
                "recipe.objcopy.bin.pattern=$c \"{tools.esptool_py.path}/esptool\" elf2image",
            ),
            lines.drop(1),
        )
        // Windows-only variants, *_args helpers and already prefixed values are left alone.
        assertTrue(lines.none { it.contains(".windows") || it.contains("pattern_args") })
        assertNull(PlatformOverrides.generate("recipe.c.o.pattern=$c gcc\n", c))
        assertNull(PlatformOverrides.generate("name=Nothing to do\n", "/x"))
    }

    @Test
    fun recognisesDynamicElfOfTheRightMachine() {
        val gcc = File("/usr/bin/gcc").canonicalFile
        assumeTrue(gcc.isFile && System.getProperty("os.arch").orEmpty() in setOf("amd64", "x86_64"))
        assertTrue(NativeExec.isDynamicElf(gcc, 62))
        assertFalse(NativeExec.isDynamicElf(gcc, 183)) // not aarch64
        assertFalse(NativeExec.isDynamicElf(client, 62)) // static
        assertFalse(NativeExec.isDynamicElf(File("src/main/AndroidManifest.xml"), 62))
    }

    /**
     * End to end on an x86_64 Linux host: relay client -> ExecServer -> packaged glibc
     * loader + exec shim -> the host's gcc, whose own cc1 and as must be rerouted too.
     */
    @Test
    fun relaysCompilerCommandsThroughTheNativeLoader() {
        val gcc = File("/usr/bin/gcc")
        assumeTrue(gcc.exists() && System.getProperty("os.name") == "Linux" && System.getProperty("os.arch").orEmpty() in setOf("amd64", "x86_64"))

        // A stand-in rootfs whose lib dirs are the host's.
        val rootfs = tmp.newFolder("rootfs")
        for (d in listOf("lib", "usr", "bin")) Files.createSymbolicLink(Paths.get(rootfs.path, d), Paths.get("/$d"))
        val native = NativeExec(jni.absoluteFile, rootfs, File(tmp.root, "glibc"), "x86_64-linux-gnu")
        native.install()
        val conf = File(tmp.root, "run.conf")
        val logs = mutableListOf<String>()
        val server = ExecServer(native, conf) { logs += it }
        server.start()
        try {
            val work = tmp.newFolder("work")
            File(work, "ok.c").writeText("int add(int a, int b) { return a + b; }\n")
            File(work, "bad.c").writeText("int broken( {\n")

            fun relay(vararg cmd: String): Triple<Int, String, String> {
                val p = ProcessBuilder(listOf(client.absolutePath) + cmd).directory(work).apply {
                    environment()["ANDUINO_RUN_CONF"] = conf.path
                }.start()
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                assertTrue("timed out", p.waitFor(60, TimeUnit.SECONDS))
                return Triple(p.exitValue(), out, err)
            }

            val ok = relay(gcc.path, "-c", "ok.c", "-o", "ok.o")
            assertEquals(ok.third, 0, ok.first)
            assertTrue(File(work, "ok.o").length() > 0)

            val bad = relay(gcc.path, "-c", "bad.c", "-o", "bad.o")
            assertEquals(1, bad.first)
            assertTrue(bad.third, bad.third.contains("error"))

            val pre = relay(gcc.path, "-E", "ok.c")
            assertEquals(0, pre.first)
            assertTrue(pre.second.contains("int add"))
            assertEquals(3, server.commandsRun)

            // Core hooks: a Linux path to env, bash, and a command found via PATH.
            File(work, "a.txt").writeText("hook")
            val hook = relay("/usr/bin/env", "bash", "-c", "cp a.txt b.txt && echo hook-ok")
            assertEquals(hook.third, 0, hook.first)
            assertEquals("hook-ok", hook.second.trim())
            assertEquals("hook", File(work, "b.txt").readText())

            // A bare program name, as in "python3 gen_esp32part.py".
            if (File("/usr/bin/python3").exists()) {
                val py = relay("python3", "-c", "import json, sys; print(json.dumps([1, 2]))")
                assertEquals(py.third, 0, py.first)
                assertEquals("[1, 2]", py.second.trim())
            }
            val nativeRuns = server.commandsRun

            // A non-compiler step failing natively is rerun by the client (here: same result).
            val failing = relay("/usr/bin/env", "bash", "-c", "echo held-back; exit 3")
            assertEquals(3, failing.first)
            assertEquals("held-back", failing.second.trim()) // printed once, by the rerun
            assertTrue(logs.toString(), logs.single().contains("failed natively"))
            logs.clear()

            // Not a dynamic ELF: the server declines and the client runs it itself.
            val script = File(work, "hello.sh").apply { writeText("#!/bin/sh\necho fallback-ok\n"); setExecutable(true) }
            val fb = relay(script.path)
            assertEquals(0, fb.first)
            assertEquals("fallback-ok", fb.second.trim())
            assertEquals(nativeRuns + 1, server.commandsRun)
            assertTrue(logs.toString(), logs.isEmpty())
        } finally {
            server.stop()
        }
    }
}
