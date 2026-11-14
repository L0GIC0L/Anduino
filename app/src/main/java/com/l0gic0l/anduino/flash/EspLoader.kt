package com.l0gic0l.anduino.flash

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * Talks to the Espressif ROM serial bootloader. This is a port of the parts of
 * esptool 5.x needed for `write-flash -z` in ROM mode (`--no-stub`), which every
 * ESP32-family chip supports. The protocol is documented at
 * https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/serial-protocol.html
 */
class EspLoader(
    private val port: SerialTransport,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Called often during long operations; should throw [UploadCancelledException] to abort. */
    private val checkCancelled: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    class Response(val value: Long, val data: ByteArray)

    class RomError(description: String, val status: Int, val reason: Int) :
        IOException("Failed to $description (status 0x%02x: %s)".format(reason, reasonText(reason)))

    class UnsupportedCommandError(op: Int) : IOException("ROM does not support command 0x%02x".format(op))

    class TimeoutError(message: String) : IOException(message)

    private val decoder = SlipDecoder()
    private val readBuf = ByteArray(4096)

    // ---------------------------------------------------------------- packets

    private fun sendCommand(op: Int, data: ByteArray, chk: Long) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x00).put(op.toByte()).putShort(data.size.toShort()).putInt(chk.toInt())
            .array()
        port.write(Slip.encode(header + data))
    }

    private fun readPacket(timeoutMs: Int): ByteArray {
        val deadline = clock() + timeoutMs
        while (true) {
            decoder.poll()?.let { return it }
            val remaining = deadline - clock()
            if (remaining <= 0) throw TimeoutError("Timed out waiting for packet")
            val n = port.read(readBuf, remaining.coerceAtMost(100).toInt().coerceAtLeast(1))
            if (n > 0) decoder.feed(readBuf, n)
            checkCancelled()
        }
    }

    /** Sends [op] and waits for the matching response (other packets are skipped). */
    fun command(op: Int, data: ByteArray = EMPTY, chk: Long = 0, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Response {
        sendCommand(op, data, chk)
        repeat(100) {
            val p = readPacket(timeoutMs)
            if (p.size < 8 || p[0].toInt() != 0x01) return@repeat
            val opRet = p[1].toInt() and 0xFF
            val value = u32(p, 4)
            val body = p.copyOfRange(8, p.size)
            if (opRet == op) return Response(value, body)
            if (body.size >= 2 && body[0].toInt() != 0 && (body[1].toInt() and 0xFF) == ROM_INVALID_RECV_MSG) {
                drainInput(200)
                throw UnsupportedCommandError(op)
            }
        }
        throw IOException("Response doesn't match request 0x%02x".format(op))
    }

    /**
     * Executes a command and checks the status bytes. Returns the first
     * [respDataLen] bytes of payload, or the 32-bit value as 4 LE bytes when 0.
     */
    fun checkCommand(
        description: String,
        op: Int,
        data: ByteArray = EMPTY,
        chk: Long = 0,
        respDataLen: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Response {
        val r = command(op, data, chk, timeoutMs)
        val d = r.data
        if (d.size < respDataLen + STATUS_BYTES) {
            if (d.isNotEmpty() && d[0].toInt() != 0) {
                throw RomError(description, d[0].toInt() and 0xFF, if (d.size > 1) d[1].toInt() and 0xFF else 0)
            }
            throw IOException("Failed to $description. Only got ${d.size} byte status response.")
        }
        val status = d[respDataLen].toInt() and 0xFF
        val reason = d[respDataLen + 1].toInt() and 0xFF
        if (status != 0) throw RomError(description, status, reason)
        return Response(r.value, d.copyOfRange(0, respDataLen))
    }

    private fun drainInput(ms: Long) {
        val deadline = clock() + ms
        while (clock() < deadline) {
            port.read(readBuf, 20)
        }
        port.purgeInput()
        decoder.reset()
    }

    private fun flushInput() {
        port.purgeInput()
        decoder.reset()
    }

    // ---------------------------------------------------------------- connect

    fun sync() {
        command(OP_SYNC, SYNC_PAYLOAD, timeoutMs = SYNC_TIMEOUT_MS)
        // The ROM answers a SYNC with 8 identical responses; swallow the rest.
        repeat(7) {
            try {
                readPacket(SYNC_TIMEOUT_MS)
            } catch (_: TimeoutError) {
                return
            }
        }
    }

    enum class ResetMode { DEFAULT, NO_RESET }

    /**
     * Resets the chip into the serial bootloader and synchronises, cycling through
     * the same reset strategies esptool uses.
     */
    fun connect(mode: ResetMode = ResetMode.DEFAULT, attempts: Int = 7) {
        val strategies: List<Pair<String, () -> Unit>> = when {
            mode == ResetMode.NO_RESET -> listOf("no reset" to {})
            port.usbVendorId == ESPRESSIF_VID && port.usbProductId == USB_JTAG_SERIAL_PID ->
                listOf("USB-Serial/JTAG reset" to ::usbJtagSerialReset)
            else -> listOf(
                "tight reset" to { unixTightReset(DEFAULT_RESET_DELAY_MS) },
                "tight reset (slow)" to { unixTightReset(EXTRA_RESET_DELAY_MS) },
                "classic reset" to { classicReset(DEFAULT_RESET_DELAY_MS) },
                "classic reset (slow)" to { classicReset(EXTRA_RESET_DELAY_MS) },
            )
        }
        log("Connecting...")
        var lastError: Exception? = null
        for (attempt in 0 until attempts) {
            checkCancelled()
            val (name, reset) = strategies[attempt % strategies.size]
            flushInput()
            reset()
            val bootLog = readBootLog()
            for (i in 0 until 5) {
                try {
                    flushInput()
                    sync()
                    log("Connected (after $name).")
                    return
                } catch (e: UploadCancelledException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    sleep(50)
                }
            }
            if (bootLog != null) {
                lastError = IOException(
                    if (bootLog.second) "Download mode detected, but no sync reply (TX path down?)"
                    else "Wrong boot mode detected (${bootLog.first}); the chip needs to be in download mode",
                )
            }
        }
        throw UploadException(
            "Failed to connect to the ESP chip: ${lastError?.message}. " +
                "Hold the BOOT button, tap RESET, release BOOT, then retry.",
            lastError,
        )
    }

    /** Returns (boot mode, waiting-for-download) if the ROM printed its boot banner. */
    private fun readBootLog(): Pair<String, Boolean>? {
        val out = ByteArrayOutputStream()
        val deadline = clock() + 30
        while (clock() < deadline) {
            val n = port.read(readBuf, 10)
            if (n > 0) out.write(readBuf, 0, n)
        }
        val text = out.toString(Charsets.ISO_8859_1.name())
        val m = BOOT_LOG.find(text) ?: return null
        return m.groupValues[1] to m.groupValues[2].isNotEmpty()
    }

    // Reset sequences, translated from esp_pylib.serial_reset. "true" = line asserted
    // = the connected chip pin (EN / IO0) is pulled LOW by the auto-reset circuit.

    private fun classicReset(resetDelay: Long) {
        port.setDtr(false) // IO0 = HIGH
        port.setRts(true) // EN = LOW, chip in reset
        sleep(100)
        port.setDtr(true) // IO0 = LOW
        port.setRts(false) // EN = HIGH, chip out of reset
        sleep(resetDelay)
        port.setDtr(false) // IO0 = HIGH, done
    }

    private fun unixTightReset(resetDelay: Long) {
        setDtrRts(dtr = false, rts = false)
        setDtrRts(dtr = true, rts = true)
        setDtrRts(dtr = false, rts = true) // IO0 = HIGH & EN = LOW, chip in reset
        sleep(100)
        setDtrRts(dtr = true, rts = false) // IO0 = LOW & EN = HIGH, chip out of reset
        sleep(resetDelay)
        setDtrRts(dtr = false, rts = false) // IO0 = HIGH, done
        port.setDtr(false)
    }

    private fun usbJtagSerialReset() {
        port.setRts(false)
        port.setDtr(false) // idle
        sleep(100)
        port.setDtr(true) // set IO0
        port.setRts(false)
        sleep(100)
        port.setRts(true) // reset; calls inverted to go through (1,1) instead of (0,0)
        port.setDtr(false)
        port.setRts(true)
        sleep(100)
        port.setDtr(false)
        port.setRts(false) // chip out of reset
    }

    private fun setDtrRts(dtr: Boolean, rts: Boolean) {
        port.setDtr(dtr)
        port.setRts(rts)
    }

    /** Pulses EN low via RTS so the freshly flashed firmware starts. */
    fun hardReset(usesUsb: Boolean) {
        log("Hard resetting via RTS pin...")
        port.setRts(true)
        sleep(if (usesUsb) 200 else 100)
        port.setRts(false)
        if (usesUsb) sleep(200)
    }

    // ---------------------------------------------------------------- chip info

    fun readReg(address: Long): Long =
        checkCommand("read target memory", OP_READ_REG, le32(address)).value

    fun detectChip(): EspChip {
        try {
            val info = checkCommand("get security info", OP_GET_SECURITY_INFO, respDataLen = 20).data
            val chipId = u32(info, 12).toInt()
            EspChip.fromChipId(chipId)?.let { return it }
            throw IOException("Unknown chip id $chipId")
        } catch (e: UploadCancelledException) {
            throw e
        } catch (_: IOException) {
            // ESP32 / ESP32-S2 / ESP8266: fall back to the magic register.
            drainInput(200)
        }
        val magic = readReg(CHIP_DETECT_MAGIC_REG_ADDR)
        return EspChip.fromMagic(magic)
            ?: throw UploadException("Unrecognised chip (magic value 0x%08x)".format(magic))
    }

    /** ROM loaders don't enable the SPI flash until told to. */
    fun spiAttach(chip: EspChip) {
        val value = if (chip == EspChip.ESP32) esp32SpiPadsConfig() else 0L
        checkCommand("configure SPI flash pins", OP_SPI_ATTACH, le32(value) + byteArrayOf(0, 0, 0, 0))
    }

    /** ESP32 modules with in-package flash (e.g. PICO-D2) wire it through eFuse-selected pads. */
    private fun esp32SpiPadsConfig(): Long {
        val efuseBase = 0x3FF5A000L
        val rdata5 = readReg(efuseBase + 0x014)
        val rdata3 = readReg(efuseBase + 0x00C)
        val clk = rdata5 and 0x1F
        val q = (rdata5 shr 5) and 0x1F
        val d = (rdata5 shr 10) and 0x1F
        val cs = (rdata5 shr 15) and 0x1F
        val hd = (rdata3 shr 4) and 0x1F
        if (clk == 0L && q == 0L && d == 0L && hd == 0L && cs == 0L) return 0
        log("Attaching flash from eFuse SPI pad configuration")
        return (hd shl 24) or (cs shl 18) or (d shl 12) or (q shl 6) or clk
    }

    fun setFlashParameters(sizeBytes: Long) {
        val data = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0) // flash id
            .putInt(sizeBytes.toInt())
            .putInt(64 * 1024) // block
            .putInt(4 * 1024) // sector
            .putInt(256) // page
            .putInt(0xFFFF) // status mask
            .array()
        checkCommand("set SPI params", OP_SPI_SET_PARAMS, data)
    }

    fun changeBaud(baud: Int) {
        log("Changing baud rate to $baud...")
        command(OP_CHANGE_BAUDRATE, le32(baud.toLong()) + le32(0))
        port.setBaudRate(baud)
        sleep(50) // get rid of garbage sent during the change
        flushInput()
    }

    // ---------------------------------------------------------------- flashing

    fun flashDeflBegin(chip: EspChip, size: Int, compressedSize: Int, offset: Long): Int {
        val numBlocks = (compressedSize + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val eraseBlocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val writeSize = eraseBlocks.toLong() * FLASH_WRITE_SIZE // ROM wants the erase size rounded up
        var params = le32(writeSize) + le32(numBlocks.toLong()) + le32(FLASH_WRITE_SIZE.toLong()) + le32(offset)
        if (chip.romSupportsEncryptedFlag) params += le32(0)
        checkCommand(
            "enter compressed flash mode",
            OP_FLASH_DEFL_BEGIN,
            params,
            timeoutMs = timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB_MS, writeSize),
        )
        return numBlocks
    }

    fun flashDeflBlock(block: ByteArray, seq: Int, timeoutMs: Int) {
        val data = le32(block.size.toLong()) + le32(seq.toLong()) + le32(0) + le32(0) + block
        var attemptsLeft = WRITE_BLOCK_ATTEMPTS
        while (true) {
            try {
                checkCommand(
                    "write compressed data to flash after seq $seq",
                    OP_FLASH_DEFL_DATA,
                    data,
                    checksum(block),
                    timeoutMs = timeoutMs,
                )
                return
            } catch (e: UploadCancelledException) {
                throw e
            } catch (e: IOException) {
                if (--attemptsLeft <= 0) throw e
                log("Block $seq failed (${e.message}), retrying...")
            }
        }
    }

    /** ROM loaders return the MD5 as 32 ASCII hex characters. */
    fun flashMd5(address: Long, size: Int): String {
        val res = checkCommand(
            "calculate md5sum",
            OP_SPI_FLASH_MD5,
            le32(address) + le32(size.toLong()) + le32(0) + le32(0),
            respDataLen = 32,
            timeoutMs = timeoutPerMb(MD5_TIMEOUT_PER_MB_MS, size.toLong()),
        )
        return String(res.data, Charsets.US_ASCII).lowercase()
    }

    class Image(val address: Long, val data: ByteArray, val name: String)

    /**
     * Writes all [images] compressed and verifies each one by MD5.
     * [onProgress] receives overall progress 0..1.
     */
    fun writeFlash(chip: EspChip, images: List<Image>, onProgress: (Float) -> Unit) {
        val prepared = images.map { img ->
            val padded = padTo(img.data, 4)
            Triple(img, padded, deflate(padded))
        }
        val totalCompressed = prepared.sumOf { it.third.size }.coerceAtLeast(1)
        var sent = 0L
        for ((img, data, compressed) in prepared) {
            checkCancelled()
            log("Erasing and writing ${img.name} at 0x%08x (%d bytes, %d compressed)...".format(img.address, data.size, compressed.size))
            val blocks = flashDeflBegin(chip, data.size, compressed.size, img.address)
            for (seq in 0 until blocks) {
                checkCancelled()
                val from = seq * FLASH_WRITE_SIZE
                val block = compressed.copyOfRange(from, minOf(from + FLASH_WRITE_SIZE, compressed.size))
                // The ROM writes the block to flash before ACKing, so allow for slow flash.
                flashDeflBlock(block, seq, timeoutMs = BLOCK_TIMEOUT_MS)
                sent += block.size
                onProgress(sent.toFloat() / totalCompressed)
            }
            val expected = md5Hex(data)
            val actual = flashMd5(img.address, data.size)
            if (expected != actual) {
                throw UploadException("MD5 of ${img.name} does not match (expected $expected, got $actual)")
            }
            log("Hash of ${img.name} verified.")
        }
        onProgress(1f)
    }

    companion object {
        val EMPTY = ByteArray(0)

        const val OP_FLASH_BEGIN = 0x02
        const val OP_FLASH_DATA = 0x03
        const val OP_SYNC = 0x08
        const val OP_READ_REG = 0x0A
        const val OP_SPI_SET_PARAMS = 0x0B
        const val OP_SPI_ATTACH = 0x0D
        const val OP_CHANGE_BAUDRATE = 0x0F
        const val OP_FLASH_DEFL_BEGIN = 0x10
        const val OP_FLASH_DEFL_DATA = 0x11
        const val OP_FLASH_DEFL_END = 0x12
        const val OP_SPI_FLASH_MD5 = 0x13
        const val OP_GET_SECURITY_INFO = 0x14

        const val ROM_INVALID_RECV_MSG = 0x05
        const val STATUS_BYTES = 2
        const val FLASH_WRITE_SIZE = 0x400
        const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000L
        const val ESPRESSIF_VID = 0x303A
        const val USB_JTAG_SERIAL_PID = 0x1001
        const val ROM_BAUD = 115200

        const val DEFAULT_TIMEOUT_MS = 3000
        const val SYNC_TIMEOUT_MS = 100
        const val BLOCK_TIMEOUT_MS = 10_000
        const val MD5_TIMEOUT_PER_MB_MS = 8000L
        const val ERASE_REGION_TIMEOUT_PER_MB_MS = 40_000L
        const val DEFAULT_RESET_DELAY_MS = 50L
        const val EXTRA_RESET_DELAY_MS = 550L
        const val WRITE_BLOCK_ATTEMPTS = 3

        private val SYNC_PAYLOAD = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        private val BOOT_LOG = Regex("boot:(0x[0-9a-fA-F]+)(.*waiting for download)?", RegexOption.DOT_MATCHES_ALL)

        fun checksum(data: ByteArray): Long {
            var state = 0xEF
            for (b in data) state = state xor (b.toInt() and 0xFF)
            return state.toLong()
        }

        fun timeoutPerMb(msPerMb: Long, sizeBytes: Long): Int =
            maxOf(DEFAULT_TIMEOUT_MS.toLong(), msPerMb * sizeBytes / 1_000_000).toInt()

        fun padTo(data: ByteArray, alignment: Int): ByteArray {
            val rem = data.size % alignment
            if (rem == 0) return data
            return data + ByteArray(alignment - rem) { 0xFF.toByte() }
        }

        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(9) // zlib wrapper, like Python's zlib.compress
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return out.toByteArray()
        }

        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

        fun le32(v: Long): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

        fun u32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

        fun reasonText(reason: Int): String = when (reason) {
            0x05 -> "received message is invalid"
            0x06 -> "failed to act on received message"
            0x07 -> "invalid CRC in message"
            0x08 -> "flash write error"
            0x09 -> "flash read error"
            0x0A -> "flash read length error"
            0x0B -> "deflate error"
            else -> "unknown error"
        }
    }
}
