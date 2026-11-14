package com.l0gic0l.anduino.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EspLoaderTest {

    private fun flashWith(chip: EspChip): Pair<FakeEspRom, List<EspLoader.Image>> {
        val rom = FakeEspRom(chip)
        val loader = EspLoader(rom, sleep = {})
        val rnd = Random(42)
        val images = listOf(
            EspLoader.Image(0x1000, ByteArray(20_001) { rnd.nextInt().toByte() }, "bootloader"),
            EspLoader.Image(0x8000, ByteArray(3072) { 0xAA.toByte() }, "partitions"),
            // Mostly zeros compresses well, and spans multiple 1 KiB compressed blocks.
            EspLoader.Image(0x10000, ByteArray(300_000) { if (it % 997 == 0) it.toByte() else 0 }, "app"),
        )
        loader.connect()
        val detected = loader.detectChip()
        assertEquals(chip, detected)
        loader.spiAttach(detected)
        loader.setFlashParameters(4L * 1024 * 1024)
        loader.changeBaud(921600)
        var lastProgress = 0f
        loader.writeFlash(detected, images) { lastProgress = it }
        assertEquals(1f, lastProgress)
        loader.hardReset(usesUsb = false)
        return rom to images
    }

    private fun assertFlashContains(rom: FakeEspRom, images: List<EspLoader.Image>) {
        for (img in images) {
            val padded = EspLoader.padTo(img.data, 4)
            val actual = ByteArray(padded.size) { rom.flash[img.address + it] ?: 0 }
            assertArrayEquals(img.name, padded, actual)
        }
    }

    @Test
    fun flashesEsp32UsingMagicRegisterDetection() {
        val (rom, images) = flashWith(EspChip.ESP32)
        assertFlashContains(rom, images)
        // ESP32 ROM uses the short FLASH_DEFL_BEGIN parameter block.
        assertTrue(rom.beginParamSizes.all { it == 16 })
        assertEquals(4L * 1024 * 1024, rom.flashSizeSet)
        assertEquals(921600, rom.baud)
        assertTrue(rom.resetPulses >= 2) // bootloader entry + final hard reset
    }

    @Test
    fun flashesEsp32S3UsingChipIdDetection() {
        val (rom, images) = flashWith(EspChip.ESP32_S3)
        assertFlashContains(rom, images)
        // Newer ROMs take the extra "encrypted" word.
        assertTrue(rom.beginParamSizes.all { it == 20 })
        // Chip id path must not need the magic register.
        assertTrue(EspLoader.OP_GET_SECURITY_INFO in rom.commandLog)
    }

    @Test
    fun detectsEsp32C3() {
        val (rom, images) = flashWith(EspChip.ESP32_C3)
        assertFlashContains(rom, images)
    }

    @Test
    fun checksumMatchesEsptool() {
        // esptool: state = 0xEF; for b in data: state ^= b
        assertEquals(0xEFL xor 0x01 xor 0x02 xor 0xFF, EspLoader.checksum(byteArrayOf(1, 2, 0xFF.toByte())))
    }

    @Test
    fun slipRoundTripEscapesSpecialBytes() {
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0x02, 0xDB.toByte(), 0x03)
        val encoded = Slip.encode(payload)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 1, 0xDB.toByte(), 0xDC.toByte(), 2, 0xDB.toByte(), 0xDD.toByte(), 3, 0xC0.toByte()),
            encoded,
        )
        val dec = SlipDecoder()
        dec.feed("garbage".toByteArray() + encoded.copyOfRange(0, 4))
        assertEquals(null, dec.poll())
        dec.feed(encoded.copyOfRange(4, encoded.size))
        assertArrayEquals(payload, dec.poll())
    }

    @Test
    fun timeoutScalesWithSize() {
        assertEquals(3000, EspLoader.timeoutPerMb(8000, 1000))
        assertEquals(40_000, EspLoader.timeoutPerMb(40_000, 1_000_000))
    }
}
