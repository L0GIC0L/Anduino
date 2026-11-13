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
        assertFalse(out("$gxx -c @/tmp/flags -w -x c++ -E -CC /root/.cache/b/sketch/sketch.ino.cpp -o /dev/null").show)
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
}
