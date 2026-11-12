package com.l0gic0l.anduino.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashPlanTest {

    @Test
    fun parsesFlashArgsWrittenByEsp32Core() {
        // Exactly what recipe.hooks.objcopy.postobjcopy.4 in arduino-esp32 3.x writes.
        val text = """
            --flash-mode dio --flash-freq 80m --flash-size 4MB
            0x1000 Blink.ino.bootloader.bin
            0x8000 Blink.ino.partitions.bin
            0xe000 boot_app0.bin
            0x10000 Blink.ino.bin
        """.trimIndent()
        val (segments, size) = FlashPlanParser.parseFlashArgs(text, "/root/.cache/anduino/build/Blink/")
        assertEquals(4L * 1024 * 1024, size)
        assertEquals(
            listOf(
                FlashSegment(0x1000, "/root/.cache/anduino/build/Blink/Blink.ino.bootloader.bin"),
                FlashSegment(0x8000, "/root/.cache/anduino/build/Blink/Blink.ino.partitions.bin"),
                FlashSegment(0xe000, "/root/.cache/anduino/build/Blink/boot_app0.bin"),
                FlashSegment(0x10000, "/root/.cache/anduino/build/Blink/Blink.ino.bin"),
            ),
            segments,
        )
    }

    @Test
    fun parsesExpandedEsptoolUploadPattern() {
        // tools.esptool_py.upload.pattern_args from arduino-esp32 3.x, expanded, with erase enabled.
        val args = "--chip esp32s3 --port \"{serial.port}\" --baud 921600  --before default-reset " +
            "--after hard-reset write-flash -e -z --flash-mode keep --flash-freq keep --flash-size keep " +
            "0x0 \"/tmp/b/My Sketch.ino.bootloader.bin\" 0x8000 \"/tmp/b/My Sketch.ino.partitions.bin\" " +
            "0xe000 \"/root/.arduino15/packages/esp32/hardware/esp32/3.3.0/tools/partitions/boot_app0.bin\" " +
            "0x10000 \"/tmp/b/My Sketch.ino.bin\" "
        val (segments, size, erase) = FlashPlanParser.parseEsptoolArgs(args)
        assertEquals(null, size) // "keep"
        assertTrue(erase)
        assertEquals(4, segments.size)
        assertEquals(FlashSegment(0x0, "/tmp/b/My Sketch.ino.bootloader.bin"), segments[0])
        assertEquals(0x10000L, segments[3].address)
    }

    @Test
    fun legacyUnderscoreSpellingWorks() {
        val (segments, _, _) = FlashPlanParser.parseEsptoolArgs(
            "--chip esp32 write_flash -z --flash_mode dio --flash_freq 80m --flash_size 4MB 0x1000 a.bin 0x10000 b.bin",
        )
        assertEquals(listOf(FlashSegment(0x1000, "a.bin"), FlashSegment(0x10000, "b.bin")), segments)
    }

    @Test
    fun parsesBuildProperties() {
        val props = BuildProperties.parse(
            "build.mcu=esp32s3\nupload.tool=esptool_py\nupload.tool.default=esptool_py\nupload.speed=921600\nempty=\n",
        )
        assertEquals("esp32s3", props["build.mcu"])
        assertEquals("esptool_py", BuildProperties.uploadTool(props))
        assertEquals("", props["empty"])
    }

    @Test
    fun sizes() {
        assertEquals(16L * 1024 * 1024, FlashPlanParser.parseSize("16MB"))
        assertEquals(512L * 1024, FlashPlanParser.parseSize("512KB"))
        assertEquals(null, FlashPlanParser.parseSize("keep"))
    }

    @Test
    fun intelHexParsesDataAndExtendedAddresses() {
        val hex = """
            :100000000C9434000C943E000C943E000C943E0082
            :020000040001F9
            :0400000001020304F2
            :00000001FF
        """.trimIndent()
        val bin = IntelHex.parse(hex)
        assertEquals(0x10000 + 4, bin.size)
        assertArrayEquals(byteArrayOf(0x0C, 0x94.toByte(), 0x34, 0x00), bin.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bin.copyOfRange(0x10000, 0x10004))
        assertEquals(0xFF.toByte(), bin[0x20])
    }
}
