package com.l0gic0l.anduino.core

import com.l0gic0l.anduino.env.OutputLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileProgressTest {
    private val gxx = "/root/.arduino15/packages/esp32/tools/esp-x32/2601/bin/xtensa-esp32-elf-g++"

    @Test
    fun showsPhasesAndCountsFilesWithoutPrintingCommands() {
        val p = CompileProgress(showCommands = false)
        fun out(t: String) = p.accept(OutputLine(t, isError = false))

        assertTrue(out("FQBN: esp32:esp32:esp32").let { !it.show && it.step == null })
        assertEquals(CompileProgress.Decision(true, "Detecting libraries used…"), out("Detecting libraries used..."))
        val detect = out("$gxx -c @/tmp/flags -w -x c++ -E -CC /root/.cache/b/sketch/sketch.ino.cpp -o /dev/null")
        assertFalse(detect.show)
        assertEquals("Detecting libraries used: sketch.ino.cpp (1 files checked)", detect.step)
        assertEquals(
            "Detecting libraries used: 2 files checked (1 from cache)",
            out("Using cached library dependencies for file: /root/Arduino/libraries/GxEPD2/src/GxEPD2_EPD.cpp").step,
        )
        assertEquals("Compiling core…", out("Compiling core...").step)

        val compile = out(
            "$gxx -MMD -c @/root/.arduino15/packages/esp32/tools/esp32-libs/3.3.12/esp32/flags/cpp_flags -O2 " +
                "/root/.arduino15/packages/esp32/hardware/esp32/3.3.12/cores/esp32/HardwareSerial.cpp " +
                "-o /root/.cache/anduino/build/x/core/HardwareSerial.cpp.o",
        )
        assertFalse(compile.show)
        assertEquals("Compiling core: HardwareSerial.cpp (1 files)", compile.step)

        val quoted = out("\"$gxx\" -c \"/root/Arduino/My Lib/src/a.c\" -o \"/root/.cache/b/libraries/a.c.o\"")
        assertEquals("Compiling core: a.c (2 files)", quoted.step)

        val cached = out("Using previously compiled file: /root/.cache/b/core/Esp.cpp.o")
        assertEquals("Compiling core: 3 files (1 already built)", cached.step)

        assertTrue(out("Sketch uses 289589 bytes (22%) of program storage space. Maximum is 1310720 bytes.").show)
        assertTrue(p.accept(OutputLine("sketch.ino:3:1: error: expected ';'", isError = true)).show)
        assertEquals(2, p.compiled)
        assertEquals(1, p.reused)
    }

    @Test
    fun verboseModeShowsCommands() {
        val p = CompileProgress(showCommands = true)
        assertTrue(p.accept(OutputLine("$gxx -c /x/a.cpp -o /y/a.cpp.o", false)).show)
    }

    @Test
    fun timesPhasesAndSummarisesFailures() {
        var now = 0L
        val p = CompileProgress(showCommands = false, clock = { now })
        fun out(t: String, err: Boolean = false) = p.accept(OutputLine(t, isError = err))

        now = 5_000; out("Detecting libraries used...")
        now = 65_000; out("Compiling sketch...")
        out("/root/Arduino/s/s.ino:12:3: error: 'display' was not declared in this scope", err = true)
        out("/root/Arduino/s/s.ino:14:1: warning: unused variable 'x'", err = true)
        out("compilation terminated.", err = true)
        now = 65_500; out("Used library Version Path")
        out("Error during build: exit status 1", err = true)
        p.finish()

        assertEquals("preparing 5 s · detecting libraries 60 s", p.timeSummary { "$it s" })
        assertEquals(listOf("/root/Arduino/s/s.ino:12:3: error: 'display' was not declared in this scope"), p.failureSummary())
    }

    @Test
    fun failureSummaryFallsBackToTheBuildErrorOrToolCrash() {
        val crash = CompileProgress(showCommands = false)
        crash.accept(OutputLine("xtensa-esp-elf-g++: fatal error: Bad system call signal terminated program cc1plus", isError = true))
        assertEquals(1, crash.failureSummary().size)

        val cli = CompileProgress(showCommands = false)
        cli.accept(OutputLine("Error during build: platform not installed", isError = true))
        assertEquals(listOf("platform not installed"), cli.failureSummary())

        val bare = CompileProgress(showCommands = false)
        bare.accept(OutputLine("Error during build: exit status 1", isError = true))
        assertTrue(bare.failureSummary().isEmpty())
    }
}
