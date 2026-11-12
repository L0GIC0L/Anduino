package com.l0gic0l.anduino.flash

import java.io.IOException

/** Intel HEX -> flat binary starting at address 0. */
object IntelHex {
    fun parse(text: String): ByteArray {
        val mem = HashMap<Long, Byte>()
        var base = 0L
        var maxAddr = -1L
        for ((lineNo, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            require(line.startsWith(":")) { "Line ${lineNo + 1}: missing ':'" }
            val bytes = line.substring(1).chunked(2).map { it.toInt(16) }
            require(bytes.size >= 5) { "Line ${lineNo + 1}: record too short" }
            require(bytes.sum() and 0xFF == 0) { "Line ${lineNo + 1}: bad checksum" }
            val count = bytes[0]
            val addr = (bytes[1] shl 8) or bytes[2]
            val data = bytes.subList(4, 4 + count)
            when (bytes[3]) {
                0x00 -> data.forEachIndexed { i, b ->
                    val a = base + addr + i
                    mem[a] = b.toByte()
                    if (a > maxAddr) maxAddr = a
                }
                0x01 -> break
                0x02 -> base = (((data[0] shl 8) or data[1]).toLong()) shl 4
                0x04 -> base = (((data[0] shl 8) or data[1]).toLong()) shl 16
                else -> Unit // 03/05 start address records are irrelevant for flashing
            }
        }
        val out = ByteArray((maxAddr + 1).toInt()) { 0xFF.toByte() }
        for ((a, b) in mem) out[a.toInt()] = b
        return out
    }
}

/**
 * STK500 v1 uploader as spoken by the Optiboot / ATmegaBOOT bootloaders on the
 * Uno, Nano, Pro Mini and similar boards (avrdude `-c arduino`).
 */
class Stk500v1(
    private val port: SerialTransport,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val checkCancelled: () -> Unit = {},
) {
    private val buf = ByteArray(512)

    fun upload(image: ByteArray, pageSize: Int = 128, onProgress: (Float) -> Unit) {
        resetIntoBootloader()
        sync()
        val sig = readSignature()
        log("Device signature: %02X %02X %02X".format(sig[0], sig[1], sig[2]))
        command(byteArrayOf(STK_ENTER_PROGMODE, CRC_EOP))
        val pages = (image.size + pageSize - 1) / pageSize
        // Write
        for (p in 0 until pages) {
            checkCancelled()
            val page = pageOf(image, p, pageSize)
            loadAddress(p * pageSize)
            command(
                byteArrayOf(STK_PROG_PAGE, (page.size shr 8).toByte(), page.size.toByte(), 'F'.code.toByte()) +
                    page + byteArrayOf(CRC_EOP),
                timeoutMs = 1000,
            )
            onProgress((p + 1).toFloat() / (pages * 2))
        }
        // Verify
        for (p in 0 until pages) {
            checkCancelled()
            val page = pageOf(image, p, pageSize)
            loadAddress(p * pageSize)
            val read = command(
                byteArrayOf(STK_READ_PAGE, (page.size shr 8).toByte(), page.size.toByte(), 'F'.code.toByte(), CRC_EOP),
                responseData = page.size,
            )
            if (!read.contentEquals(page)) throw UploadException("Verification failed at 0x%04x".format(p * pageSize))
            onProgress((pages + p + 1).toFloat() / (pages * 2))
        }
        command(byteArrayOf(STK_LEAVE_PROGMODE, CRC_EOP))
        log("${image.size} bytes written and verified.")
    }

    private fun pageOf(image: ByteArray, page: Int, pageSize: Int): ByteArray {
        val from = page * pageSize
        return image.copyOfRange(from, minOf(from + pageSize, image.size))
    }

    /** Toggling DTR/RTS pulses the reset line through the board's 100 nF capacitor. */
    fun resetIntoBootloader() {
        port.setDtr(false)
        port.setRts(false)
        sleep(250)
        port.setDtr(true)
        port.setRts(true)
        sleep(50)
        port.purgeInput()
    }

    fun sync() {
        var last: Exception? = null
        repeat(10) {
            checkCancelled()
            try {
                command(byteArrayOf(STK_GET_SYNC, CRC_EOP), timeoutMs = 200)
                log("In sync with bootloader.")
                return
            } catch (e: IOException) {
                last = e
                port.purgeInput()
            }
        }
        throw UploadException("Bootloader not responding (not in sync). Check the board type and port.", last)
    }

    fun readSignature(): ByteArray = command(byteArrayOf(STK_READ_SIGN, CRC_EOP), responseData = 3)

    private fun loadAddress(byteAddress: Int) {
        val word = byteAddress / 2 // flash is word addressed
        command(byteArrayOf(STK_LOAD_ADDRESS, word.toByte(), (word shr 8).toByte(), CRC_EOP))
    }

    /** Sends [cmd] and expects INSYNC, [responseData] bytes, OK. Returns the data bytes. */
    private fun command(cmd: ByteArray, responseData: Int = 0, timeoutMs: Int = 500): ByteArray {
        port.write(cmd)
        val resp = readExactly(responseData + 2, timeoutMs)
        if (resp[0] != STK_INSYNC) throw IOException("Expected INSYNC, got 0x%02x".format(resp[0]))
        if (resp[resp.size - 1] != STK_OK) throw IOException("Expected OK, got 0x%02x".format(resp[resp.size - 1]))
        return resp.copyOfRange(1, resp.size - 1)
    }

    private fun readExactly(n: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(n)
        var have = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (have < n) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("Timeout ($have/$n bytes)")
            val got = port.read(buf, left.toInt().coerceIn(1, 100))
            val take = minOf(got, n - have)
            System.arraycopy(buf, 0, out, have, take)
            have += take
        }
        return out
    }

    companion object {
        const val STK_OK: Byte = 0x10
        const val STK_INSYNC: Byte = 0x14
        const val CRC_EOP: Byte = 0x20
        const val STK_GET_SYNC: Byte = 0x30
        const val STK_ENTER_PROGMODE: Byte = 0x50
        const val STK_LEAVE_PROGMODE: Byte = 0x51
        const val STK_LOAD_ADDRESS: Byte = 0x55
        const val STK_PROG_PAGE: Byte = 0x64
        const val STK_READ_PAGE: Byte = 0x74
        const val STK_READ_SIGN: Byte = 0x75
    }
}
